package harness

import java.io.File
import javax.imageio.ImageIO

/**
 * 「有的图标像马赛克」到底是**格子**还是**画本身**：
 * 量浓度在"竖边"上的跳变，按这条边是不是落在色场网格的边界上分两堆。
 * 如果网格边界上的平均跳变明显大于其它列，那就是色场漏到了掩码上（可修，且是通用的一条）；
 * 如果两边一样大，那马赛克是源图自己的分面（比如拼多多的心形由三角面拼成），不该由模型负责。
 *
 * 色场是 8×8 格铺在**内容外接框**上，所以边界列 = box.left + k·(框宽/8)。
 */
fun main(args: Array<String>) {
  val grid = args.firstOrNull()?.toIntOrNull() ?: 16
  val dir = File("rec2")
  val files = dir.listFiles { f -> f.name.endsWith("-src.png") }!!.sortedBy { it.name }
  var gridWorse = 0
  var same = 0
  for (f in files) {
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = com.iamcanincan.opticon.circle.MonochromeMask.build(px, w, h)
    val mask = r.mask ?: continue
    var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
    for (y in 0 until h) for (x in 0 until w) if (((mask[y * w + x] ushr 24) and 0xff) > 0) {
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }
    if (x1 - x0 < 40) continue
    // 只量**模型实际用的那个格数**（命令行第一个参数，默认 16）。
    // ⚠ 曾经让它去 4/8/12/16/24 里挑最坏的 —— 那是自欺：格数越少，那几条"格线"就越可能
    //   只是碰巧压在图标自己的内容边界上（4 格就是三条线），量到的"跳变大"是真实边界，
    //   不是色场台阶。要比较两种格数，各量各的。
    val cell = (x1 - x0 + 1).toDouble() / grid
    var onSum = 0.0; var onN = 0; var offSum = 0.0; var offN = 0
    for (y in y0..y1) for (x in x0 until x1) {
      val a = (mask[y * w + x] ushr 24) and 0xff
      val b = (mask[y * w + x + 1] ushr 24) and 0xff
      val d = kotlin.math.abs(a - b).toDouble()
      val near = kotlin.math.round((x - x0 + 0.5) / cell).toInt()
      if (kotlin.math.abs((x - x0) + 0.5 - near * cell) <= 1.5) { onSum += d; onN++ }
      else { offSum += d; offN++ }
    }
    if (onN < 20 || offN < 20) continue
    val on = onSum / onN; val off = offSum / offN
    val ratio = if (off <= 0.001) 1.0 else on / off
    val name = f.name.removeSuffix("-src.png").substringAfter('_').take(42)
    if (ratio > 1.6) {
      gridWorse++
      println("  格线更跳  %-42s 格线上=%.1f 其它=%.1f 比=%.2f (边 %d)"
        .format(name, on, off, ratio, onN))
    } else same++
  }
  println("结论（按 %d 格量）：格线上的跳变明显更大 = %d 张 / 共判 %d 张"
    .format(grid, gridWorse, gridWorse + same))
}
