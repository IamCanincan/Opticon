package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **「文」和它的投影，粗细分得开吗** —— 把背景层里"不是盘"的每一块连通域拆出来，
 * 报它的面积与**估算厚度**（面积 / 边界格数 ≈ 半宽），并按平均明度排。
 *
 * 为什么问这个：translate 那道斜黑带是楔形投在灰盘上的投影，而 `盘刀` 只切"盘那一族"，
 * 所以整条带留下了墨。想切掉它，唯一的非颜色证据是**几何**：投影是贴着强色边缘、
 * 宽度有限的一条带，「文」是笔画。**但如果两者的粗细重叠，这条判据就不存在** ——
 * 那这道缝就和 `转转` 那批一起进"已知边界"，不再试第七根尺子。
 *
 * 用法：`harness.BandThickKt <背景层单独那张.png> [--plate-l=200] [--min=120]`
 */
fun main(args: Array<String>) {
  val f = File(args.first { !it.startsWith("--") })
  val plateL = (args.firstOrNull { it.startsWith("--plate-l=") }?.substringAfter("="))?.toIntOrNull() ?: 200
  val minArea = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("="))?.toIntOrNull() ?: 120
  val img = ImageIO.read(f)
  val w = img.width; val h = img.height
  val px = IntArray(w * h) { img.getRGB(it % w, it / w) }
  fun luma(p: Int) = 0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)
  // "不是盘" = 背景层里不透明、且明度低于盘的那些格（盘在本机是 #EEEEEE，L≈238）
  val ink = BooleanArray(w * h) { ((px[it] ushr 24) and 0xff) >= 128 && luma(px[it]) < plateL }
  val seen = BooleanArray(w * h)
  val q = IntArray(w * h)
  val out = ArrayList<Triple<Int, Double, Double>>()      // 面积, 厚度, 平均明度
  for (i in 0 until w * h) {
    if (!ink[i] || seen[i]) continue
    var sp = 0; var area = 0; var edge = 0; var lsum = 0.0
    q[sp++] = i; seen[i] = true
    while (sp > 0) {
      val c = q[--sp]; area++; lsum += luma(px[c])
      val cx = c % w; val cy = c / w
      for (k in 0..3) {
        val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) { edge++; continue }
        val j = ny * w + nx
        if (!ink[j]) { edge++; continue }
        if (!seen[j]) { seen[j] = true; q[sp++] = j }
      }
    }
    if (area >= minArea) out.add(Triple(area, area / 2.0 / edge.coerceAtLeast(1), lsum / area))
  }
  println("${f.name}  背景层里「不是盘」的连通域（面积 ≥$minArea）：")
  println("      面积    估算半宽   平均明度")
  for (o in out.sortedByDescending { it.first }.take(16))
    println("   %7d   %6.1f    %6.0f".format(o.first, o.second, o.third))
  val thin = out.filter { it.second <= 12 }; val thick = out.filter { it.second > 12 }
  println("   半宽 ≤12px 的 ${thin.size} 块（合计 ${thin.sumOf { it.first.toDouble() }.toInt()} 格），" +
    ">12px 的 ${thick.size} 块（合计 ${thick.sumOf { it.first.toDouble() }.toInt()} 格）")
}
