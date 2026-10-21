package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **台阶尺（只量不改）**：交付里"相邻两格浓度硬跳一级"的那些边，**源图在那条边上其实还是连续的** ——
 * 也就是屏幕上看得见的**一层层平台**（banding）。
 *
 * 为什么是这一把（2026-10-12）：主人这句「色度可以广，别让有些过渡也难看」把方向钉成了两半 ——
 * **广是允许的**（满幅那一支的存在理由），**但过渡不许被做成台阶**。而"广"与"台阶"在
 * 现有任何一把尺上都长同一个数：`总灰区` 只看摊在中间调的面积，`档数` 只看有几种浓度，
 * 两者都**不看相邻两格之间那一步有多大、源图同不同意这一步**。
 *
 * 判据三句话：① 两格都交了墨但都没实心（α 1..254）；② 交付浓度差 ≥ `--jump`；
 * ③ **源图这两格的明度差 ≤ `--smooth`**（源还在连续变化，是我们把它拍平成两级台阶）。
 * 三条同时成立才数一格。这样"双色 logo 该硬的那条边界"不会被数进来（源在那里 ΔL 极大），
 * 而"照片/渐变被做成三块平色"会。
 *
 * 用法：`TerrKt [--model] [--input=model] 目录… [--edge=384] [--jump=24] [--smooth=8] [--show=子串]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val jump = (args.firstOrNull { it.startsWith("--jump=") }?.substringAfter("="))?.toIntOrNull() ?: 24
  val smooth = (args.firstOrNull { it.startsWith("--smooth=") }?.substringAfter("="))?.toIntOrNull() ?: 8
  val model = args.contains("--model")
  /** `--pair-mono`：按 index 的「彩色源 ↔ 设计师自己画的单色层」成对量（对照集用这一档）。 */
  val monoPair = args.contains("--pair-mono")
  val sfx = args.firstOrNull { it.startsWith("--suffix=") }?.substringAfter("=")
  val modelInput = args.contains("--input=model")
  val show = (args.firstOrNull { it.startsWith("--show=") }?.substringAfter("="))?.split(',') ?: emptyList()
  for (d in args.filter { !it.startsWith("--") }) {
    val dir = File(d)
    val plate = HashMap<String, Boolean>(); val adapted = HashSet<String>(); val underOf = HashMap<String, String>()
    val ix = File(dir, "index.tsv")
    if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
      val c = l.split('\t')
      if (c.size >= 9) {
        adapted.add(if (c[8] == "有") c[2] else "")
        plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
        if (c.size >= 19 && modelInput) {
          // 没有 under 的那 42 张也要进名单（`File("")` 是个占位，读它必然失败 → under=null）。
          // 第一版只登记带 `_modelbg.png` 的，`--input=model` 当场只剩 20/62 张 —— 分母自己缩水，
          // 而"中位 0.00%"照样印得像结论。
          val mf = if (c[17] == "same") c[2] else c[17]
          if (File(dir, mf).isFile && c[8] != "有") {
            plate[mf] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
            underOf[mf] = if (c[18] == "-" || c[18] == "null" || c[18] == "err") "" else c[18]
          }
        }
      }
    }
    val pairs = ArrayList<Pair<File, File>>()
    if (monoPair) {
      // **对照集必须是"彩色源 + 设计师自己画的那一层"这一对**：台阶尺的第二问（源在这条边上连不连续）
      //  要拿彩色源量。直接对 `_mono.png` 自己量源，等于拿墨层当源 —— 那条判据当场失效。
      if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
        val c = l.split('\t')
        if (c.size >= 13 && c[12] != "无" && c[12] != "-" && c[12].isNotEmpty()) {
          val s = File(dir, c[2]); val m = File(dir, c[12])
          if (s.isFile && m.isFile) pairs.add(Pair(s, m))
        }
      }
    }
    // ⚠ 给了 `--suffix` 就**只按它筛**（要吃的正是 `_mono.png` 这类文件）。第一版把两条与条件写在
    //   一起，`--suffix=_mono` 当场 0 张 —— 一台数不出东西的台子，看起来和"这一族真的没有"是同一种响法。
    val files = (if (modelInput) underOf.keys.map { File(dir, it) }.sortedBy { it.name }
      else if (sfx != null) dir.listFiles { x -> x.extension.equals("png", true) && x.name.contains(sfx) }!!
        .sortedBy { it.name }
      else dir.listFiles { x -> x.extension.equals("png", true) && !x.name.endsWith("_bg.png") &&
        !x.name.endsWith("_fg.png") && !x.name.endsWith("_mono.png") && !x.name.endsWith("_model.png") &&
        !x.name.endsWith("_modelbg.png") && !x.name.contains("preview") && !adapted.contains(x.name) }!!
        .sortedBy { it.name })
    val items: List<Triple<File, File?, String>> =
      if (monoPair) pairs.map { Triple(it.first, it.second, it.first.nameWithoutExtension) }
      else files.map { Triple(it, null, it.nameWithoutExtension) }
    val rows = ArrayList<Triple<String, Double, Int>>()
    val flats = ArrayList<Double>()
    // `--flat=N`：换问另一件事 —— **一片同浓度的平台里，源自己跨了多少明度**？
    //   台阶尺问的是"相邻两格跳了多少"，而 sangfor 那种难看在尺上是 2 档（218 / 252），
    //   屏幕上却是一条穿盾的**接缝**：因为源那条绿→蓝斜坡很长，被整片压进 218 一档。
    //   所以量"平台内部源跨度 ≥ N 的格占框多少" —— 这才是"斜坡被拍平成平台"。
    val flatSpan = (args.firstOrNull { it.startsWith("--flat=") }?.substringAfter("="))?.toIntOrNull()
    for (item in items) {
      val f = item.first
      val px = read(f, edge) ?: continue
      val ink = item.second?.let { read(it, edge) } ?: (if (model) {
        val u = underOf[f.name]?.let { read(File(dir, it), edge) }
          ?: run { val s = File(dir, f.nameWithoutExtension + "_bg.png"); if (s.isFile) read(s, edge) else null }
        MonochromeMask.build(px, edge, edge, plate[f.name] == true, u).mask ?: continue
      } else px)
      val b = edge * 3 / 4; val o = (edge - b) / 2
      var step = 0; var worst = 0
      for (y in o until o + b) for (x in o until o + b) {
        val i = y * edge + x
        val a1 = (ink[i] ushr 24) and 0xff
        if (a1 == 0 || a1 == 255) continue
        for (k in 0..1) {
          val j = i + (if (k == 0) 1 else edge)
          if (if (k == 0) x + 1 >= o + b else y + 1 >= o + b) continue
          val a2 = (ink[j] ushr 24) and 0xff
          if (a2 == 0 || a2 == 255) continue
          val dd = kotlin.math.abs(a1 - a2)
          if (dd < jump) continue
          // 源图在这两格上还是连续的吗？（只看两边都不透明的那对格，混色边缘不算证据）
          val p1 = px[i]; val p2 = px[j]
          if (((p1 ushr 24) and 0xff) < 250 || ((p2 ushr 24) and 0xff) < 250) continue
          val l1 = (0.2126 * ((p1 shr 16) and 0xff) + 0.7152 * ((p1 shr 8) and 0xff) + 0.0722 * (p1 and 0xff))
          val l2 = (0.2126 * ((p2 shr 16) and 0xff) + 0.7152 * ((p2 shr 8) and 0xff) + 0.0722 * (p2 and 0xff))
          if (kotlin.math.abs(l1 - l2) > smooth) continue
          step++; if (dd > worst) worst = dd
        }
      }
      val cells = b.toLong() * b
      if (flatSpan != null) flats.add(plateauFlatten(ink, px, edge, b, o, flatSpan))
      // `--show=` 命中的那几张：把台阶按**档对**摊开（"并档之后台阶变多"这种话必须能当场看见是哪一对）。
      if (show.isNotEmpty() && show.any { f.name.contains(it) }) {
        val byPair = HashMap<Int, Int>()
        for (y in o until o + b) for (x in o until o + b) for (k in 0..1) {
          val i = y * edge + x
          val a1 = (ink[i] ushr 24) and 0xff
          if (a1 == 0 || a1 == 255) continue
          val j = i + (if (k == 0) 1 else edge)
          if (if (k == 0) x + 1 >= o + b else y + 1 >= o + b) continue
          val a2 = (ink[j] ushr 24) and 0xff
          if (a2 == 0 || a2 == 255) continue
          val dd = kotlin.math.abs(a1 - a2)
          if (dd < jump) continue
          val p1 = px[i]; val p2 = px[j]
          if (((p1 ushr 24) and 0xff) < 250 || ((p2 ushr 24) and 0xff) < 250) continue
          val l1 = (0.2126 * ((p1 shr 16) and 0xff) + 0.7152 * ((p1 shr 8) and 0xff) + 0.0722 * (p1 and 0xff))
          val l2 = (0.2126 * ((p2 shr 16) and 0xff) + 0.7152 * ((p2 shr 8) and 0xff) + 0.0722 * (p2 and 0xff))
          if (kotlin.math.abs(l1 - l2) > smooth) continue
          val key = (minOf(a1, a2) shl 8) or maxOf(a1, a2)
          byPair[key] = (byPair[key] ?: 0) + 1
        }
        for ((kk, n) in byPair.toList().sortedByDescending { it.second }.take(6))
          println("      档对 ${(kk ushr 8)}→${kk and 0xff}（步 ${(kk and 0xff) - (kk ushr 8)}）：$n 格对")
      }
      rows.add(Triple(item.third.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
        .take(30) + " 最大步$worst", step * 100.0 / cells, worst))
    }
    val s = rows.map { it.second }.sorted()
    if (flats.isNotEmpty()) {
      val fs = flats.sorted()
      println("   **平台内源跨度 ≥$flatSpan 的格占框**  中位 ${"%.1f".format(fs[fs.size / 2])}%  " +
        "p90 ${"%.1f".format(fs[minOf(fs.size - 1, (fs.size * 0.9).toInt())])}%  最狠 ${"%.1f".format(fs.last())}%  " +
        "零平台的张数 ${flats.count { it == 0.0 }}/${flats.size}")
      println("   （这一列才是「sangfor 那条穿盾接缝」的形状：源在跨 60 档，交付整片压在 218 一档）")
    }
    println("=== ${if (model) "交付" else "源图"}  ${dir.name}  ${rows.size} 张  jump≥$jump 源ΔL≤$smooth")
    if (s.isNotEmpty()) println("   台阶格占框  中位 ${"%.2f".format(s[s.size / 2])}%  " +
      "p90 ${"%.2f".format(s[minOf(s.size - 1, (s.size * 0.9).toInt())])}%  最狠 ${"%.2f".format(s.last())}%  " +
      "零台阶的张数 ${rows.count { it.second == 0.0 }}")
    for (r in rows.sortedByDescending { it.second }.take(12))
      println("   %6.2f%%  %s".format(r.second, r.first))
    for (q in show.filter { it.isNotBlank() }) for (r in rows.filter { it.first.contains(q) })
      println("   点名「$q」 %6.2f%%  %s".format(r.second, r.first))
  }
}

/**
 * **一片同浓度的中间调平台里，源自己跨了多少明度** —— 占内切框的%。
 * 只数面积 ≥ 1% 框的平台（碎屑不是"看得见的平台"），源只看两侧都不透明的格。
 * 平台内部源跨度 ≥ [span] 才算"斜坡被拍平"：屏幕上那就是穿过去的一条接缝。
 */
private fun plateauFlatten(ink: IntArray, px: IntArray, edge: Int, b: Int, o: Int, span: Int): Double {
  val n = edge * edge
  val seen = BooleanArray(n)
  val q = IntArray(b * b)
  val ls = ArrayList<Int>(b * b / 4)
  var hit = 0L
  val boxCells = b.toLong() * b
  for (y in o until o + b) for (x in o until o + b) {
    val i0 = y * edge + x
    if (seen[i0]) continue
    val a0 = (ink[i0] ushr 24) and 0xff
    if (a0 == 0 || a0 == 255) { seen[i0] = true; continue }
    var sp = 0; var size = 0
    ls.clear()
    q[sp++] = i0; seen[i0] = true
    while (sp > 0) {
      val c = q[--sp]; size++
      val p = px[c]
      if (((p ushr 24) and 0xff) >= 250) ls.add(
        (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt())
      val cx = c % edge; val cy = c / edge
      fun push(j: Int) {
        if (!seen[j] && ((ink[j] ushr 24) and 0xff) == a0) { seen[j] = true; q[sp++] = j }
      }
      if (cx + 1 < o + b) push(c + 1)
      if (cx > o) push(c - 1)
      if (cy + 1 < o + b) push(c + edge)
      if (cy > o) push(c - edge)
    }
    if (size.toLong() * 100 < boxCells) continue            // 不足 1% 框的不算"一片平台"
    if (ls.size < 40) continue
    ls.sort()
    val lo = ls[(ls.size * 0.05).toInt().coerceAtMost(ls.size - 1)]
    val hi = ls[((ls.size * 0.95).toInt()).coerceAtMost(ls.size - 1)]
    if (hi - lo >= span) hit += size
  }
  return hit * 100.0 / boxCells
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
