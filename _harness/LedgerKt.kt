package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **肉眼账的族尺（只量不改）** —— 给 `named.txt` 那三族各配一把**逐张出数**的尺子。
 *
 * 为什么要它：`_named.cjs` 的覆盖账原来只读 `MaskBench` 那一行里的 8 个数。逐字段扫过
 * 122 行 × 40 个键之后能确定一件事：主人报的这三族在那些数上**一个都不极端**（4 张未覆盖的
 * 图在每一列上都落在中位附近）。族与数是断开的 —— 不是阈值画错了，是**没有一把尺在量这件事**。
 *
 * 两把尺，各自回答一句他逐字说过的话：
 *
 *  · **擦空**「某某没了 / 半边没有 / 上色不全」
 *    模型自己把这一格归在**墨侧**（`plate==0`）、源那一格是**实心**的，交付却给了 0，
 *    并且这些格连成**看得见的一块**（≥25 格 = 5×5，与 `DustKt` 同一个"屏幕上不该有单像素"的约定）。
 *    这就是 `SheetKt` 中间那张图上那层红色 —— 它一直只有图、没有数。
 *    ⚠ 分母**不能**是"源实心且交 0"（`SheetKt` 第一版就是这么废的：白盘子整圈被涂红，
 *      那是判对了的底），必须借模型自己的归属。
 *    ⚠ 与 `CoverKt` 的分工：那一把问"刻了**一半**"（覆盖率 0.15~0.85），整块擦空的覆盖率≈0，
 *      正好在它窗外 —— 那一族的另一半从没被数过。与 `EraseKt` 的分工：那一把要"两侧都是墨"，
 *      量的是**断口**；被整条吃掉的第一个字（zhuanzhuan 的「不」）两侧没有墨，它数不到。
 *
 *  · **平台跨**「线条不对 / 过渡不清晰 / 颜色生硬」
 *    交付里一整片同浓度的平台（含实心 255），**源自己在里面跨了多少明度**。
 *    与 `TerrKt --flat` 同一问，只差一件事：那一台把 α==255 那块平台挡在门外，于是
 *    "整片渐变压成实心"这种压平法它一个字都读不出来（autonavi 那架纸飞机的棱线就是这一种）。
 *    线画在 **30** 上，用的是**模型自己的档距**（`LevelsKt` 头部那条：档每步至少让 30、
 *    两档看得出差别要差 ~30）—— 源跨得比一步还多而交付只给了一档，那才是"本可以画出来没画"。
 *    同时报两个**不依赖阈值**的数（最大那片平台的源跨度、按面积加权的源跨度），
 *    因为"线画在哪"本身就是结论，得让人能自己重画。
 *
 * 另外两把**只出数、不接进覆盖账**的：`台阶`（把 `TerrKt` 那条"两侧都不许实心"放宽成
 * "两侧都有墨"）与 `墨内台阶`（再收紧成两侧都成墨，排掉形状外沿的抗锯齿斜坡）。
 * 留着是因为它们是 `TerrKt` 的射程延长、对所有语料出数；不接进账是因为这一轮它们
 * 对那 4 张**一张都不单独说**（globalspeed 台阶=0，autonavi 已被平台跨说中），接进去只是抬覆盖率。
 *
 * 跑法：`harness.LedgerKtKt <目录…> [--edge=384] [--tsv=文件] [--jump=24] [--smooth=8]
 *        [--span=30] [--minblk=25]`
 * 每图标一行 TSV：`名称 擦空格% 半擦格% 台阶格% 墨内台阶格% 平台跨% 最大平台源跨度 加权平台源跨度 擦空处数`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val jump = (args.firstOrNull { it.startsWith("--jump=") }?.substringAfter("="))?.toIntOrNull() ?: 24
  val smooth = (args.firstOrNull { it.startsWith("--smooth=") }?.substringAfter("="))?.toIntOrNull() ?: 8
  val span = (args.firstOrNull { it.startsWith("--span=") }?.substringAfter("="))?.toIntOrNull() ?: 30
  val minBlk = (args.firstOrNull { it.startsWith("--minblk=") }?.substringAfter("="))?.toIntOrNull() ?: 25
  val tsv = args.firstOrNull { it.startsWith("--tsv=") }?.substringAfter("=")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val out = ArrayList<String>()
  for (a0 in args.filter { !it.startsWith("--") }) {
    val dir = File(a0)
    val plateByLayer = HashMap<String, Boolean>()
    val ix = File(dir, "index.tsv")
    if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
      val c = l.split('\t')
      // 与 `MaskBench` 同一个口径：自适应 + 背景是 ColorDrawable ⇒ 盘在另一层，走装机那一条
      if (c.size >= 8) plateByLayer[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    }
    val files = dir.listFiles { x -> x.extension.equals("png", true) && !x.name.contains("preview") &&
      !x.name.endsWith("_modelbg.png") && !x.name.endsWith("_bg.png") && !x.name.endsWith("_fg.png") &&
      !x.name.endsWith("_mono.png") && !x.name.endsWith("_model.png") }!!.sortedBy { it.name }
    val rows = ArrayList<Pair<String, DoubleArray>>()
    for (f in files) {
      val px = read(f, edge) ?: continue
      val modelSib = File(f.parentFile, f.nameWithoutExtension + "_modelbg.png")
      val bgSib = if (modelSib.isFile) modelSib else File(f.parentFile, f.nameWithoutExtension + "_bg.png")
      val under = if (bgSib.isFile) read(bgSib, edge) else null
      val m = MonochromeMask.build(px, edge, edge, plateByLayer[f.name] == true, under).mask ?: continue
      val plate = fld(pool.get() ?: continue, "plate") as ByteArray
      val b = edge * 3 / 4; val o = (edge - b) / 2
      val cells = b.toLong() * b
      // —— 擦空 / 半擦：模型判为墨侧、源实心、交付 0（或 1..127）
      val hit = BooleanArray(px.size)
      var er = 0L; var half = 0L
      for (y in o until o + b) for (x in o until o + b) {
        val i = y * edge + x
        if (plate[i] != 0.toByte() || ((px[i] ushr 24) and 0xff) != 255) continue
        when ((m[i] ushr 24) and 0xff) {
          0 -> { er++; hit[i] = true }
          in 1..127 -> half++
        }
      }
      // —— 台阶：两侧都有墨、交付硬跳 ≥jump、源在这条边上还在连续变化；`sti` 再要求两侧都成墨
      var st = 0L; var sti = 0L
      for (y in o until o + b) for (x in o until o + b) {
        val i = y * edge + x
        val a1 = (m[i] ushr 24) and 0xff
        if (a1 == 0) continue
        for (k in 0..1) {
          val j = i + (if (k == 0) 1 else edge)
          if (if (k == 0) x + 1 >= o + b else y + 1 >= o + b) continue
          val a2 = (m[j] ushr 24) and 0xff
          if (a2 == 0 || kotlin.math.abs(a1 - a2) < jump) continue
          val p1 = px[i]; val p2 = px[j]
          if (((p1 ushr 24) and 0xff) < 250 || ((p2 ushr 24) and 0xff) < 250) continue
          if (kotlin.math.abs(lum(p1) - lum(p2)) > smooth) continue
          st++; if (a1 >= 128 && a2 >= 128) sti++
        }
      }
      val pp = plateaus(px, m, edge, o, b, span)
      val v = doubleArrayOf(
        er * 100.0 / cells, half * 100.0 / cells, st * 100.0 / cells, sti * 100.0 / cells,
        pp[0], pp[1], pp[2], countBlks(hit, edge, o, b, minBlk).toDouble())
      rows.add(Pair(f.nameWithoutExtension, v))
    }
    println("=== ${dir.name}  ${rows.size} 张 ===")
    for (col in listOf(0 to "擦空格%", 1 to "半擦格%", 2 to "台阶格%", 3 to "墨内台阶%", 4 to "平台跨%")) {
      val s = rows.map { it.second[col.first] }.sorted()
      println("   %-10s 中位 %8.3f  p90 %8.3f  最狠 %8.3f  非零张数 %d/%d".format(
        col.second, pc(s, .5), pc(s, .9), s.last(), s.count { it > 0.0 }, s.size))
    }
    for (col in listOf(5 to "最大平台源跨度", 6 to "加权平台源跨度", 7 to "擦空处数")) {
      val s = rows.map { it.second[col.first] }.sorted()
      println("   %-10s 中位 %8.1f  p90 %8.1f  最狠 %8.1f  非零张数 %d/%d".format(
        col.second, pc(s, .5), pc(s, .9), s.last(), s.count { it > 0.0 }, s.size))
    }
    out.addAll(rows.map { r -> "${r.first}\t" + (0..7).joinToString("\t") { "%.3f".format(r.second[it]) } })
  }
  if (tsv != null) {
    File(tsv).writeText("名称\ter\teh\tst\tsti\tpl\tpw\tpa\terb\n" + out.joinToString("\n") + "\n")
    println("   → $tsv（${out.size} 行）")
  }
}

private fun pc(s: List<Double>, q: Double) = s[minOf(s.size - 1, (s.size * q).toInt())]
private fun lum(p: Int) = 0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)

/** 8 邻域洪泛数"处"：屏幕上读得出来的一块，不是单像素。 */
private fun countBlks(hit: BooleanArray, edge: Int, o: Int, b: Int, min: Int): Int {
  val seen = BooleanArray(hit.size)
  val q = IntArray(b * b)
  var n = 0
  for (y in o until o + b) for (x in o until o + b) {
    val i0 = y * edge + x
    if (!hit[i0] || seen[i0]) continue
    var sp = 0; var size = 0
    q[sp++] = i0; seen[i0] = true
    while (sp > 0) {
      val c = q[--sp]; size++
      val cx = c % edge; val cy = c / edge
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val nx = cx + dx; val ny = cy + dy
        if (nx < o || ny < o || nx >= o + b || ny >= o + b) continue
        val j = ny * edge + nx
        if (hit[j] && !seen[j]) { seen[j] = true; q[sp++] = j }
      }
    }
    if (size >= min) n++
  }
  return n
}

/**
 * 交付里每一片同浓度平台（α≥128，含实心 255），源自己在里面跨了多少明度。
 * 只数面积 ≥1% 框、源实心格 ≥40 的平台 —— 碎屑不是"看得见的平台"（与 `TerrKt --flat` 同一约定）。
 *   [0] 源跨度 ≥ span 的平台占框%   [1] 最大那片的源跨度   [2] 按面积加权的源跨度
 */
private fun plateaus(px: IntArray, m: IntArray, edge: Int, o: Int, b: Int, span: Int): DoubleArray {
  val seen = BooleanArray(px.size)
  val q = IntArray(b * b)
  val ls = ArrayList<Int>(b * b / 4)
  val boxCells = b.toLong() * b
  var hit = 0L; var worst = 0.0; var wsum = 0.0; var warea = 0L
  for (y in o until o + b) for (x in o until o + b) {
    val i0 = y * edge + x
    if (seen[i0]) continue
    val a0 = (m[i0] ushr 24) and 0xff
    if (a0 < 128) { seen[i0] = true; continue }
    var sp = 0; var size = 0
    ls.clear()
    q[sp++] = i0; seen[i0] = true
    while (sp > 0) {
      val c = q[--sp]; size++
      val p = px[c]
      if (((p ushr 24) and 0xff) >= 250) ls.add(lum(p).toInt())
      val cx = c % edge; val cy = c / edge
      fun push(j: Int) { if (!seen[j] && ((m[j] ushr 24) and 0xff) == a0) { seen[j] = true; q[sp++] = j } }
      if (cx + 1 < o + b) push(c + 1)
      if (cx > o) push(c - 1)
      if (cy + 1 < o + b) push(c + edge)
      if (cy > o) push(c - edge)
    }
    if (size.toLong() * 100 < boxCells || ls.size < 40) continue
    ls.sort()
    val lo = ls[(ls.size * 0.05).toInt().coerceAtMost(ls.size - 1)]
    val hi = ls[((ls.size * 0.95).toInt()).coerceAtMost(ls.size - 1)]
    val s = (hi - lo).toDouble()
    if (s >= span) hit += size
    if (s > worst) worst = s
    wsum += s * size; warea += size
  }
  return doubleArrayOf(hit * 100.0 / boxCells, worst, if (warea == 0L) 0.0 else wsum / warea)
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
