package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **那块中档到底是什么** —— 对每张图"占框最大的那一档中间调（α 90..180）"逐张验尸，
 * 只回答一个问题：它是**盘**、是**物体**，还是**渐变的一段**。
 *
 * 为什么必须先问这个：主人要"过渡别难看 / 一片灰要治"，但**"一整块平的中档"有三种完全不同的东西**：
 *   · **盘** —— 该并进底（`盘刀` 那一族的射程）；
 *   · **物体** —— 易班那只熊就是图形本身，把它"抬实"或"并掉"就是本仓擦掉熊/车/到字的那四条死路；
 *   · **渐变段** —— 那是层次，不许动（`档数` 多的那批全是这一类）。
 * 一台只报"这块占框 10%"的尺子分不开这三者，所以这里把六个独立读数一起摊出来，
 * **判据留给看图那一步**，这台子不下结论。
 *
 * 用法：`MidKt <目录> [--edge=384] [--show=子串,子串]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val show = (args.firstOrNull { it.startsWith("--show=") }?.substringAfter("="))?.split(',') ?: emptyList()
  val dir = File(args.first { !it.startsWith("--") })
  val plate = HashMap<String, Boolean>(); val underOf = HashMap<String, String>(); val adapted = HashSet<String>()
  File(dir, "index.tsv").let { ix -> if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 9) {
      plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
      if (c[8] == "有") adapted.add(c[2])
      if (c.size >= 19 && c[18] != "-" && c[18] != "null") underOf[c[2]] = c[18]
    } } }
  println("图（占框最大的中间调档 α90..180）      占框   α   源L跨  源色      触边  块数  最大块  被实墨围  =背景层主色")
  for (f in dir.listFiles { x -> x.extension.equals("png", true) && !x.name.endsWith("_modelbg.png") &&
      !x.name.endsWith("_bg.png") && !x.name.endsWith("_fg.png") && !x.name.contains("preview") &&
      !adapted.contains(x.name) }!!.sortedBy { it.name }) {
    val px = read(f, edge) ?: continue
    val u = underOf[f.name]?.let { read(File(dir, it), edge) }
    val r = MonochromeMask.build(px, edge, edge, plate[f.name] == true, u)
    val out = r.mask ?: continue
    val b = edge * 3 / 4; val o = (edge - b) / 2
    val hist = IntArray(256)
    for (y in o until o + b) for (x in o until o + b) hist[(out[y * edge + x] ushr 24) and 0xff]++
    var lv = 0; var area = 0
    for (v in 90..180) if (hist[v] > area) { area = hist[v]; lv = v }
    val boxCells = b.toLong() * b
    if (area * 1000 < boxCells) continue                      // 不足 0.1% 框，没有"一块"可言
    // 源里这一档的色与明度跨度
    var rs = 0L; var gs = 0L; var bs = 0L; var n = 0; val ls = ArrayList<Int>(4096)
    for (y in o until o + b) for (x in o until o + b) {
      val i = y * edge + x
      if (((out[i] ushr 24) and 0xff) != lv) continue
      val p = px[i]; rs += (p shr 16) and 0xff; gs += (p shr 8) and 0xff; bs += p and 0xff; n++
      ls.add(((0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt()))
    }
    ls.sort()
    val lspan = if (n > 8) ls[(n * 0.9).toInt().coerceAtMost(n - 1)] - ls[(n * 0.1).toInt()] else -1
    // 触边（这一档的格有多少贴着内容框那一圈）、连通块数、最大块
    var touch = 0; var inkNbr = 0; var edgeNbr = 0
    val seen = BooleanArray(edge * edge); val q = IntArray(b * b); var comps = 0; var biggest = 0
    for (y in o until o + b) for (x in o until o + b) {
      val i = y * edge + x
      if (((out[i] ushr 24) and 0xff) != lv) continue
      if (x <= o + 2 || x >= o + b - 3 || y <= o + 2 || y >= o + b - 3) touch++
      for (k in 0..3) {
        val j = i + intArrayOf(1, -1, edge, -edge)[k]
        val a2 = (out[j] ushr 24) and 0xff
        if (a2 == 0) continue
        edgeNbr++
        if (a2 >= 200) inkNbr++
      }
      if (seen[i]) continue
      comps++; var sp = 0; var size = 0; q[sp++] = i; seen[i] = true
      while (sp > 0) {
        val c0 = q[--sp]; size++
        val cx = c0 % edge; val cy = c0 / edge
        fun push(j: Int, nx: Int, ny: Int) {
          if (nx < o || ny < o || nx >= o + b || ny >= o + b) return
          if (!seen[j] && ((out[j] ushr 24) and 0xff) == lv) { seen[j] = true; q[sp++] = j }
        }
        push(c0 + 1, cx + 1, cy); push(c0 - 1, cx - 1, cy); push(c0 + edge, cx, cy + 1); push(c0 - edge, cx, cy - 1)
      }
      if (size > biggest) biggest = size
    }
    // 与背景层那块主色同色吗（有 `_modelbg` 才算）
    var samePlate = "-"
    if (u != null) {
      val fam = HashMap<Int, Int>(); var sol = 0
      for (i in u.indices) {
        if (((u[i] ushr 24) and 0xff) < 128) continue
        sol++
        val k = (((u[i] shr 16) and 0xff) / 24) shl 10 or ((((u[i] shr 8) and 0xff) / 24) shl 5) or ((u[i] and 0xff) / 24)
        fam[k] = (fam[k] ?: 0) + 1
      }
      val top = fam.maxByOrNull { it.value }
      if (top != null && sol > 0 && top.value * 100 >= sol * 85) {
        val p = (((rs / n.coerceAtLeast(1)).toInt() / 24) shl 10) or
          ((((gs / n.coerceAtLeast(1)).toInt() / 24) shl 5)) or ((bs / n.coerceAtLeast(1)).toInt() / 24)
        samePlate = if (p == top.key) "是" else "否"
      }
    }
    val nm = (f.nameWithoutExtension.split('_').getOrNull(if (f.name.startsWith("icon")) 1 else 0)
      ?: f.nameWithoutExtension).take(30)
    println("  %-31s %5.1f%% %3d %5d  #%02X%02X%02X %5.2f %4d %6.2f  %6.2f  %s"
      .format(nm, area * 100.0 / boxCells, lv, lspan,
        (rs / n.coerceAtLeast(1)).toInt(), (gs / n.coerceAtLeast(1)).toInt(), (bs / n.coerceAtLeast(1)).toInt(),
        touch * 1.0 / n, comps, biggest * 1.0 / n, inkNbr * 1.0 / edgeNbr.coerceAtLeast(1), samePlate))
    if (show.isNotEmpty() && show.any { f.name.contains(it) }) println("  ↑ 点名命中")
  }
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
