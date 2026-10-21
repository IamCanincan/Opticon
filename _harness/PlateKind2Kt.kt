package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 只量不改：**这块"底"内部有没有同色族的硬边** —— 有就说明它是一个**物体**（投影、描边、结构线），
 * 不是一张承载图形的**板**。
 *
 * 为什么是这一列（2026-10-15，主人要修 `org.mewx.wenku8`）：蓝书 + 从顶边挂下来的白书签，被
 * "摘大盘、留字形"的约定交成只剩书签（`框内=0.07`）。已经量到"分不开"的有两条：
 *  `围墨`（它与夹具「渐变底+白字」同为 0）、`框内/底占`（它与 美团/WPS/支付宝/微信 挤在 0.06~0.10 一格）。
 *
 * ⚠ 还有**一条是这个驱动的上一版**（`PlateKindKt.kt`）：它先按 ΔL≤8 洪泛出"同色区域"再数内部硬边 ——
 *  **结构上就数不到**：硬边正是洪泛停下来的地方，只会落在区域边界上（量出来 wenku8 0.02% vs 夹具 0.00%，
 *  四格之差）。那是"一把永远不响的尺"，不是"这条判据不成立"。所以这一版换定义：
 *  区域 = 从角上种子出发**连通的那一整块实心**（只看 α≥8，不看颜色），
 *  然后只数其中「明度跳得狠、色相几乎没变」的相邻格 —— 书里的书脊与 45° 长投影是同一种蓝，
 *  而卡里的白字是"另一种颜色"，不该算进这条。
 *
 * 用法：`harness.PlateKind2KtKt <语料目录> [--edge=384] [--hard=6] [--hue=6]`
 */
private class Lab2(val l: Double, val a: Double, val b: Double)

private fun labOf2(r: Int, g: Int, b: Int): Lab2 {
  fun lin(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
  }
  val rr = lin(r); val gg = lin(g); val bb = lin(b)
  val x = (0.4124 * rr + 0.3576 * gg + 0.1805 * bb) / 0.95047
  val y = 0.2126 * rr + 0.7152 * gg + 0.0722 * bb
  val z = (0.0193 * rr + 0.1192 * gg + 0.9505 * bb) / 1.08883
  fun f(t: Double) = if (t > 0.008856) 116.0 * ln(t) - 16.0 else 903.3 * t
  val fx = f(x); val fy = f(y); val fz = f(z)
  return Lab2(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}

fun main(args: Array<String>) {
  val dir = File(args[0])
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val hard = args.firstOrNull { it.startsWith("--hard=") }?.substringAfter("=")?.toDouble() ?: 6.0
  val hue = args.firstOrNull { it.startsWith("--hue=") }?.substringAfter("=")?.toDouble() ?: 6.0

  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "没有 PNG" }

  val rows = ArrayList<Triple<Double, Double, String>>()
  var skipped = 0
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
    val solid = BooleanArray(edge * edge) { ((px[it] ushr 24) and 0xFF) >= 8 }
    var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
    for (y in 0 until edge) for (x in 0 until edge) if (solid[y * edge + x]) {
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }
    if (x1 < 0) { skipped++; continue }
    val lab = Array(edge * edge) { i ->
      val p = px[i]; labOf2((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }
    val region = BooleanArray(edge * edge)
    val q = ArrayDeque<Int>()
    val step = max(1, min(x1 - x0, y1 - y0) / 12)
    for ((cx, cy, dx, dy) in listOf(
        intArrayOf(x0, y0, 1, 1), intArrayOf(x1, y0, -1, 1),
        intArrayOf(x0, y1, 1, -1), intArrayOf(x1, y1, -1, -1))) {
      var x = cx; var y = cy; var k = 0; var found = false
      while (k++ < 24) {
        if (solid[y * edge + x]) { found = true; break }
        x += dx * step; y += dy * step
        if (x < x0 || x > x1 || y < y0 || y > y1) break
      }
      if (!found) continue
      val i0 = y * edge + x
      if (region[i0]) continue
      region[i0] = true; q.add(i0)
      while (q.isNotEmpty()) {
        val c = q.removeFirst(); val ccx = c % edge; val ccy = c / edge
        for ((ddx, ddy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
          val nx = ccx + ddx; val ny = ccy + ddy
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (region[j] || !solid[j]) continue
          region[j] = true; q.add(j)
        }
      }
    }
    var cells = 0; var adj = 0; var sameHueHard = 0
    for (y in 0 until edge) for (x in 0 until edge) {
      val i = y * edge + x
      if (!region[i]) continue
      cells++
      for ((dx2, dy2) in listOf(1 to 0, 0 to 1)) {
        val j = (y + dy2) * edge + (x + dx2)
        if (j >= edge * edge || !region[j]) continue
        adj++
        val li = lab[i]; val lj = lab[j]
        if (abs(lj.l - li.l) >= hard && abs(lj.a - li.a) <= hue && abs(lj.b - li.b) <= hue) sameHueHard++
      }
    }
    if (adj < edge * 4) { skipped++; continue }
    rows += Triple(sameHueHard.toDouble() / adj, cells.toDouble() / (edge * edge), f.nameWithoutExtension)
  }
  if (rows.isEmpty()) { println("!! 一张都没量到 —— 这台子是空的"); kotlin.system.exitProcess(1) }
  rows.sortBy { it.first }
  println("候选底内部「同色族硬边」邻接占比（|ΔL*|≥%.0f 且 |Δa*|、|Δb*|≤%.0f），从低到高：".format(hard, hue))
  rows.forEach { println("  %6.2f%%  这块占画布 %5.1f%%  %s".format(it.first * 100, it.second * 100, it.third.take(44))) }
  println("跳过 ${skipped} 张（区域太小或整张透明）；量到 ${rows.size} 张")
}
