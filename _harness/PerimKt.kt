package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 只量不改：**画布最外那一圈有多少是空气** —— 判"这座底其实是浮在空气里的图形"要用的那把尺。
 *
 * 起因（2026-10-15）：`org.mewx.wenku8` 是一本蓝书 + 一条白丝带，四周 39% 全透明。H1 从四角下种子
 * 时看的是**内容框**的带（`contentBounds` 收到书自己的外框），于是"蓝"成了底、丝带成了墨，
 * 屏幕上就剩一道竖。要把它和「圆角方板 + 白字」那类分开，靠的不是颜色数，是
 * **这座底顶不顶得到画布边上** —— 方板四边贴画布，书四面被空气包着。
 *
 * 用法：`harness.PerimKtKt <语料目录> [--edge=384]`
 * 输出按外周空气占比排序；⚠ 这张表只用来**看有没有空档**，不拿来定阈值 ——
 * 没有空档就说明这条判据不成立，得换一条。
 */
fun main(args: Array<String>) {
  val dir = File(args[0])
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val ring = (edge / 64).coerceAtLeast(1)

  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "目录里没有 PNG" }

  val rows = ArrayList<Triple<Int, String, String>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    var rimAir = 0; var rim = 0; var air = 0
    for (y in 0 until edge) for (x in 0 until edge) {
      val a = (px[y * edge + x] ushr 24) and 0xFF
      val onRim = x < ring || y < ring || x >= edge - ring || y >= edge - ring
      if (a < 8) { air++; if (onRim) rimAir++ }
      if (onRim) rim++
    }
    // 内容外框占画布（= 报告里的 `盒布` 那一档口径）
    var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xFF) >= 8) {
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }
    val boxPct = if (x1 < 0) 0 else (x1 - x0 + 1) * (y1 - y0 + 1) * 100 / (edge * edge)
    val rimPct = rimAir * 100 / rim.coerceAtLeast(1)
    val rep = runCatching { MonochromeMask.build(px, edge, edge, false, null).report }.getOrDefault("")
    fun fld(k: String) = Regex("$k=([0-9.]+)").find(rep)?.groupValues?.get(1) ?: "-"
    rows += Triple(rimPct, f.nameWithoutExtension.take(40),
      "外周空气=%-3d 全透明=%-3d 盒布=%-3d | 底占=%-4s 框内=%-4s H=%s 族=%s".format(
        rimPct, air * 100 / (edge * edge), boxPct, fld("底占"), fld("框内"), fld("H"), fld("族")))
  }
  rows.sortByDescending { it.first }
  println("%-42s %s".format("图标", "这一张的外周/交付读数"))
  rows.forEach { println("%-42s %s".format(it.second, it.third)) }
  val buckets = IntArray(11)
  rows.forEach { buckets[(it.first / 10).coerceIn(0, 10)]++ }
  println("外周空气占比直方图（每格 10%，从 0 到 100）：")
  buckets.forEachIndexed { i, c -> println("  %3d–%3d%%: %s".format(i * 10, i * 10 + 9, "#".repeat(c) + " $c")) }
}
