package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **这一张前景层里，有没有一块"自己就是盘"的平底色？**（只量不改）
 *
 * 为什么要这一台（2026-10-10，主人报「com.baidu.tieba 取色有问题」）：
 * `plateIsLayer` 说的是"承载面在**另一层**，这一层里没有盘"。贴吧那张前景层**自带一块铺满画布的白底**
 * —— 前提对这一张是假的。于是甲类那两刀（实心格不许当底 + 斜坡零端压到 128）把这块白底
 * **整片画成半墨**：屏幕上就是一块盖住桌面的灰板。
 *
 * 这一台量的不是颜色，是**形状**：一块平色如果它的**外接框几乎铺满内容框**（横竖都 ≥ 门槛），
 * 它就不是"图形的一部分"，它是这张自己带的盘。混元那半个白太极横竖都只占一半 —— 分得开，
 * 而且分它用的是"围不围着画布"这一条结构，不是又一根颜色尺子。
 *
 * 用法：`harness.PlateBgKtKt <目录> [--edge=384] [--cov=90] [--share=20]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val cov = args.firstOrNull { it.startsWith("--cov=") }?.substringAfter("=")?.toInt() ?: 90
  val share = args.firstOrNull { it.startsWith("--share=") }?.substringAfter("=")?.toInt() ?: 20
  val dir = File(args.first { !it.startsWith("--") })
  val jia = HashMap<String, Boolean>()
  File(dir, "index.tsv").let { ix ->
    if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
      val c = l.split('\t')
      if (c.size >= 8) jia[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    }
  }
  println("图标\t甲?\t平色块占%\t横铺%\t竖铺%\t色\t判\t最大连通块\t横\t竖\t判2\t四边同色\t边色L/亮端\t判3")
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val src = ImageIO.read(f)
    val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
    val px = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, px, 0, edge)
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 8) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) continue
    val bw = r + 1 - l; val bh = b + 1 - t
    var solid = 0
    val cnt = HashMap<Int, IntArray>()   // 量化色 -> [n, minx, miny, maxx, maxy]
    for (y in t..b) for (x in l..r) {
      val v = px[y * edge + x]
      if (((v ushr 24) and 0xff) < 128) continue
      solid++
      // 量化到每通道 4 bit：渐变的两端算两块，平涂的抖动算一块 —— 这里要的就是"平涂"
      val k = (((((v shr 16) and 0xff) ushr 4) * 16 + (((v shr 8) and 0xff) ushr 4)) * 16 + ((v and 0xff) ushr 4))
      val a = cnt.getOrPut(k) { intArrayOf(0, edge, edge, -1, -1) }
      a[0]++; if (x < a[1]) a[1] = x; if (y < a[2]) a[2] = y; if (x > a[3]) a[3] = x; if (y > a[4]) a[4] = y
    }
    var best = intArrayOf(0, 0, 0, 0)
    var bestK = 0
    for ((k, a) in cnt) {
      val n = a[0]; if (n * 100 < solid * share) continue
      val cw = (a[3] + 1 - a[1]) * 100 / bw; val ch = (a[4] + 1 - a[2]) * 100 / bh
      if (minOf(cw, ch) > minOf(best[1], best[2])) { best = intArrayOf(n, cw, ch, 0); bestK = k }
    }
    // **最大单色连通块**：一块"盘"的定义是**一个连着的东西铺满四边**，不是"这个色的像素散落各处"。
    // 拼多多那些白三角散落得再开，每一块都只是心形里的一小片；贴吧那块白底是**一个**贴着四边的面。
    val q = IntArray(bw * bh)
    val seen = BooleanArray(edge * edge)
    var big = 0; var bx1 = edge; var by1 = edge; var bx2 = -1; var by2 = -1; var bigK = 0; var bigL = 0L
    val key = IntArray(edge * edge) { -1 }
    var maxL = 0
    for (y in t..b) for (x in l..r) {
      val v = px[y * edge + x]
      if (((v ushr 24) and 0xff) < 128) continue
      val ll = MonochromeMask.labChannel(v, 0) shr 6
      if (ll > maxL) maxL = ll
      key[y * edge + x] = (((((v shr 16) and 0xff) ushr 4) * 16 + (((v shr 8) and 0xff) ushr 4)) * 16 + ((v and 0xff) ushr 4))
    }
    for (y in t..b) for (x in l..r) {
      val i0 = y * edge + x
      if (seen[i0] || key[i0] < 0) continue
      val k0 = key[i0]
      var head = 0; var tail = 0
      q[tail++] = i0; seen[i0] = true
      var n = 0; var x1 = x; var y1 = y; var x2 = x; var y2 = y; var lsum = 0L
      while (head < tail) {
        val i = q[head++]; val cy = i / edge; val cx = i - cy * edge
        n++; lsum += MonochromeMask.labChannel(px[i], 0) shr 6
        if (cx < x1) x1 = cx; if (cx > x2) x2 = cx; if (cy < y1) y1 = cy; if (cy > y2) y2 = cy
        for (d in intArrayOf(-1, 1)) {
          val j = i + d
          if (cx + d >= l && cx + d <= r && !seen[j] && key[j] == k0) { seen[j] = true; q[tail++] = j }
          val j2 = i + d * edge
          if (cy + d >= t && cy + d <= b && !seen[j2] && key[j2] == k0) { seen[j2] = true; q[tail++] = j2 }
        }
      }
      if (n > big) { big = n; bx1 = x1; by1 = y1; bx2 = x2; by2 = y2; bigK = k0; bigL = lsum / n }
    }
    val cw = (bx2 + 1 - bx1) * 100 / bw; val ch = (by2 + 1 - by1) * 100 / bh
    // **这一层自己有没有带一块盘**（便宜版判据）：看**内容框的四条边**上是不是同一个色在包场。
    // 一块"盘"的语义就是"它铺到自己这一层的边上"；拼多那些白三角再大也够不着自己外接框的四条边。
    val per = HashMap<Int, Int>()
    var perN = 0
    for (x in l..r) for (y in intArrayOf(t, b)) {
      val v = px[y * edge + x]
      if (((v ushr 24) and 0xff) < 128) continue
      per.merge((((((v shr 16) and 0xff) ushr 4) * 16 + (((v shr 8) and 0xff) ushr 4)) * 16 + ((v and 0xff) ushr 4)), 1, Int::plus); perN++
    }
    for (y in t..b) for (x in intArrayOf(l, r)) {
      val v = px[y * edge + x]
      if (((v ushr 24) and 0xff) < 128) continue
      per.merge((((((v shr 16) and 0xff) ushr 4) * 16 + (((v shr 8) and 0xff) ushr 4)) * 16 + ((v and 0xff) ushr 4)), 1, Int::plus); perN++
    }
    val pk = per.maxByOrNull { it.value }
    // 那个边上的色，在不在**这张自己的亮端**（p95）？混元铺满四边的是绿盘，可它上面压着更亮的白 —— 不算盘。
    var edgeL = -1
    if (pk != null) {
      for (y in t..b) for (x in l..r) {
        val v = px[y * edge + x]
        if (((v ushr 24) and 0xff) < 128) continue
        val ll = MonochromeMask.labChannel(v, 0) shr 6
        val k = (((((v shr 16) and 0xff) ushr 4) * 16 + (((v shr 8) and 0xff) ushr 4)) * 16 + ((v and 0xff) ushr 4))
        if (k == pk.key && ll > edgeL) edgeL = ll
      }
    }
    val nm = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").removeSuffix("-src")
      .replace(Regex("_[0-9a-f]{12}$"), "").take(60)
    val isJia = jia[f.name] == true
    println("$nm\t${if (isJia) "甲" else "·"}\t${best[0] * 100 / maxOf(solid, 1)}\t${best[1]}\t${best[2]}\t" +
      "%03X".format(bestK) + "\t" + (if (minOf(best[1], best[2]) >= cov) "散铺" else "") +
      "\t连块${big * 100 / maxOf(solid, 1)}%\t$cw\t$ch\t" + (if (minOf(cw, ch) >= cov && big * 100 >= solid * share) "**一块盘**" else "") +
      "\t边占${if (perN == 0) 0 else pk!!.value * 100 / perN}%\t连块L$bigL/亮端$maxL\t" +
      (if (minOf(cw, ch) >= cov && big * 100 >= solid * share && bigL >= maxL - 2) "**判3：自带盘**" else ""))
  }
}
