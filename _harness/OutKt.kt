package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **「莫名有些突出处」的形状量法：出界的墨**（只量不改）。
 *
 * 上一版对改前逐格相减，全语料只有 1 张多出 ≥150 格 —— 说明这两轮**没有造出**突出处。
 * 那就换一个不依赖"上一版"的定义，而且是最字面的那个：**源里根本没有像素的地方，我们交了墨。**
 * 桌面上那就是一个平白多出来的鼓包/尖角，正好是"莫名突出"。
 *
 * 每张报三个数：
 *  · **出界格** = 源 alpha < 8 而掩码 alpha > 0 的格数；
 *  · **最大一块** 多大、**长宽比**多少（细长 = 一根刺，方块 = 一个角）；
 *  · **凸出多远** = 这些格里离源形状最远的那个（px）—— 2px 是抗锯齿，10px 就是看得见的包。
 * 另记 **半透明当实心** = 源 alpha 在 8..250 之间却交了 ≥200 的格数（柔光被刻成墨，也是突出处的一种）。
 *
 * 用法：`harness.OutKtKt [目录] [--edges=384] [--min=40] [--shot=DIR]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val min = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 40
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let {
    File(it).apply { mkdirs() }
  }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  for (edge in edges) {
    val rows = ArrayList<ORow>()
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
      val m = runCatching { MonochromeMask.build(px, edge, edge).mask }.getOrNull() ?: continue
      val n = edge * edge
      val shape = BooleanArray(n) { (px[it] ushr 24) >= 8 }
      val out = BooleanArray(n) { !shape[it] && (m[it] ushr 24) > 0 }
      var cells = 0
      for (v in out) if (v) cells++
      var soft = 0
      for (i in 0 until n) {
        val sa = px[i] ushr 24
        if (sa in 8..250 && (m[i] ushr 24) >= 200) soft++
      }
      if (cells < min) { rows += ORow(f.name, cells, 0, 0.0, 0, soft, edge); continue }
      // 离源形状多远（ BFS 从所有 shape 格出发 ）
      val d = IntArray(n) { if (shape[it]) 0 else 1 shl 20 }
      val q = IntArray(n); var head = 0; var tail = 0
      for (i in 0 until n) if (shape[i]) q[tail++] = i
      while (head < tail) {
        val c = q[head++]; val cx = c % edge; val cy = c / edge; val nd = d[c] + 1
        for (k in 0 until 4) {
          val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (nd < d[j]) { d[j] = nd; q[tail++] = j }
        }
      }
      var far = 0
      for (i in 0 until n) if (out[i] && d[i] > far) far = d[i]
      val seen = BooleanArray(n)
      var best = 0; var bx0 = edge; var by0 = edge; var bx1 = -1; var by1 = -1
      for (i in 0 until n) {
        if (!out[i] || seen[i]) continue
        head = 0; tail = 0; q[tail++] = i; seen[i] = true
        var size = 0; var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
        while (head < tail) {
          val c = q[head++]; size++
          val cx = c % edge; val cy = c / edge
          if (cx < x0) x0 = cx; if (cy < y0) y0 = cy; if (cx > x1) x1 = cx; if (cy > y1) y1 = cy
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (out[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
          }
        }
        if (size > best) { best = size; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1 }
      }
      val w = bx1 - bx0 + 1; val h = by1 - by0 + 1
      val aspect = maxOf(w, h) / maxOf(1.0, minOf(w, h).toDouble())
      rows += ORow(f.name, cells, best, aspect, far, soft, edge)
      if (shotDir != null && far >= 6) {
        val v2 = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until n) {
          val sa = px[i] ushr 24
          val base = if (sa >= 8) 0xDDDDDD else 0xFFFFFF
          v2.setRGB(i % edge, i / edge, if (out[i]) 0xFF3B30 else base)
        }
        ImageIO.write(v2, "png", File(shotDir, f.nameWithoutExtension + "-out.png"))
      }
    }
    rows.sortByDescending { it.far }
    println("=== edge=$edge · ${files.size} 张 ===")
    println("%-44s %7s %7s %6s %7s %8s".format("图标", "出界格", "最大块", "长宽比", "凸出px", "半透当实心"))
    rows.take(12).forEach { println(it.line()) }
    val far = rows.map { it.far }.sorted()
    println("出界 ≥1 格的张数 = ${rows.count { it.cells > 0 }}   **凸出 ≥6px（看得见）的 = ${rows.count { it.far >= 6 }} 张**  " +
      "≥12px = ${rows.count { it.far >= 12 }}")
    println("凸出距离 中位 ${far[far.size / 2]}  p90 ${far[far.size * 9 / 10]}  最大 ${far[far.size - 1]}")
    println("「半透明像素被交成 ≥200 实心」最多的 5 张：" +
      rows.sortedByDescending { it.soft }.take(5).joinToString("  ") { it.name.substringAfter('_').take(22) + "=" + it.soft })
    println()
  }
}

private class ORow(val name: String, val cells: Int, val blob: Int, val aspect: Double,
                   val far: Int, val soft: Int, val edge: Int) {
  fun line() = "%-44s %7d %7d %6.1f %7d %8d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(44), cells, blob, aspect, far, soft)
}
