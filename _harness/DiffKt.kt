package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **这一版比那一版"多出来的墨"聚成了什么形状** —— 主人说"莫名有些突出处"，那就把
 * 两版逐格相减，只留下**新版更浓**的那些格，连成块，数块的大小与**长宽比**。
 *
 * "突出处"不是一句观感，它有一个可量的形状：**一小块墨，细长、挂在主体边上或飘在外面**。
 * 所以每张报三个数：
 *  · **多墨格** = 新比旧浓 ≥64 的格数；
 *  · **最大一块** 有多大、**长宽比**多少（≥4 就是"一条/一个尖"，不是"一片"）；
 *  · 这块**离主体多远**（0 = 贴在主体边上鼓出来，>0 = 飘在外面）。
 * 最狠的几张另外导一张 PNG：灰=旧、红=多出来的墨，直接看形状。
 *
 * 用法：`harness.DiffKtKt <旧目录> <新目录> [--min=200] [--shot=DIR]`（两边同名 `.alpha`）
 */
fun main(args: Array<String>) {
  val oldDir = File(args[0]); val newDir = File(args[1])
  val min = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 200
  val shot = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")
  val shotDir = shot?.let { File(it).apply { mkdirs() } }
  val files = newDir.listFiles()!!.filter { it.extension == "alpha" }.sortedBy { it.name }
  val rows = ArrayList<DRow>()
  for (nf in files) {
    val of = File(oldDir, nf.name)
    if (!of.exists()) continue
    val a = of.readBytes(); val b = nf.readBytes()
    if (a.size != b.size) continue
    val edge = Math.sqrt(a.size.toDouble()).toInt()
    if (edge * edge != a.size) continue
    val gained = BooleanArray(a.size) { (b[it].toInt() and 0xff) - (a[it].toInt() and 0xff) >= 64 }
    var cells = 0
    for (v in gained) if (v) cells++
    if (cells < min) continue
    // 主体 = 新版里最浓那一半的格（用来判"多出来的这块是贴在边上，还是飘在外面"）
    var hi = 0
    for (i in a.indices) if ((b[i].toInt() and 0xff) >= 200) hi++
    val seen = BooleanArray(a.size)
    val q = IntArray(a.size)
    var best = 0; var bx0 = edge; var by0 = edge; var bx1 = -1; var by1 = -1; var bestFar = 0
    for (i in a.indices) {
      if (!gained[i] || seen[i]) continue
      var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
      var size = 0; var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1; var far = 0
      while (head < tail) {
        val c = q[head++]; size++
        val cx = c % edge; val cy = c / edge
        if (cx < x0) x0 = cx; if (cy < y0) y0 = cy; if (cx > x1) x1 = cx; if (cy > y1) y1 = cy
        if (hi > 0 && (b[c].toInt() and 0xff) < 200) {
          // 到最近"实心墨"的切比雪夫距离，封顶 12（只关心"贴边"还是"飘着"）
          var d = 13
          outer@ for (r in 0..12) {
            for (oy in -r..r) for (ox in -r..r) {
              if (Math.max(Math.abs(ox), Math.abs(oy)) != r) continue
              val nx = cx + ox; val ny = cy + oy
              if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
              if ((b[ny * edge + nx].toInt() and 0xff) >= 200) { d = r; break@outer }
            }
          }
          if (d > far) far = d
        }
        for (k in 0 until 4) {
          val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (gained[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
        }
      }
      if (size > best) {
        best = size; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1; bestFar = far
      }
    }
    val w = bx1 - bx0 + 1; val h = by1 - by0 + 1
    val aspect = maxOf(w, h) / maxOf(1.0, minOf(w, h).toDouble())
    rows += DRow(nf.nameWithoutExtension.removeSuffix("-src"), cells, best, aspect, bestFar, edge, bx0, by0, w, h)
    if (shotDir != null && aspect >= 3 && best >= min) {
      val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
      for (i in 0 until edge * edge) {
        val av = a[i].toInt() and 0xff
        val g = 255 - av
        img.setRGB(i % edge, i / edge, if (gained[i]) 0xFF3B30 else (g shl 16) or (g shl 8) or g)
      }
      ImageIO.write(img, "png", File(shotDir, nf.nameWithoutExtension + "-diff.png"))
    }
  }
  rows.sortByDescending { it.blob * it.aspect }
  println("%-42s %7s %7s %6s %6s".format("图标", "多墨格", "最大块", "长宽比", "离主体"))
  rows.take(16).forEach { println(it.line()) }
  if (rows.isNotEmpty()) {
    val bl = rows.map { it.blob }.sorted(); val asp = rows.map { it.aspect }.sorted()
    println("有突出块的张数 = ${rows.size}   最大块 中位 ${bl[bl.size / 2]} p90 ${bl[bl.size * 9 / 10]}   " +
      "长宽比 中位 ${"%.1f".format(asp[asp.size / 2])}")
    println("细长（长宽比 ≥4）且 ≥200 格的 = ${rows.count { it.aspect >= 4 }} 张   " +
      "飘在主体外（离主体 ≥6px）的 = ${rows.count { it.far >= 6 }} 张")
  } else println("没有一张有 ≥$min 格的「多出来的墨」块")
}

private class DRow(
  val name: String, val cells: Int, val blob: Int, val aspect: Double, val far: Int,
  val edge: Int, val x0: Int, val y0: Int, val w: Int, val h: Int,
) {
  fun line() = "%-42s %7d %7d %6.1f %6d".format(name.take(42), cells, blob, aspect, far)
}
