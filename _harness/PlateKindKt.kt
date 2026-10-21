package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 只量不改：**那块"底"内部是硬边还是平滑过渡** —— 也就是它更像"一个物体"还是"一张板"。
 *
 * 为什么要另起这一列（2026-10-15，主人要修 `org.mewx.wenku8`）：蓝书 + 从顶边挂下来的白书签，
 * 现在被"摘大盘、留字形"的约定交成只剩书签（`框内=0.07`）。两条现成的尺都量过分不开 ——
 * `围墨` 上它与夹具「渐变底+白字」同为 0；`框内` 上它与 美团/WPS/支付宝/微信 挤在 0.06~0.10 一格。
 * 那两族量的全是"这块底占多少、长什么样"。这一列问的是另一件事：**它内部怎么过渡** ——
 * 一本书有 45° 长投影和深色书脊（硬边），一张卡只有平滑渐变（连续），一块平涂大盘两者都没有。
 *
 * 近似口径（这是分布预演，不是实现）：从内容框四角洪泛出"最大那块同色区域"当候选底，
 * 数它内部 4 邻里 |ΔL*| ≥ 硬边阈值的比例。洪泛与阈值都在下面写成参数，扫一遍看有没有空档。
 *
 * 用法：`harness.PlateKindKtKt <语料目录> [--edge=384] [--hard=6]`
 */
private fun labL(r: Int, g: Int, b: Int): Double {
  fun lin(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
  }
  val y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
  return if (y <= 0.008856) 903.3 * y else 116.0 * ln(y) - 16.0
}

fun main(args: Array<String>) {
  val dir = File(args[0])
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val hard = args.firstOrNull { it.startsWith("--hard=") }?.substringAfter("=")?.toDouble() ?: 6.0

  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "没有 PNG" }

  val rows = ArrayList<Triple<Double, Double, String>>()   // 硬边占比, 这块区域占画布, 名字
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
    // 内容框
    var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xFF) >= 8) {
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }
    if (x1 < 0) continue
    val L = DoubleArray(edge * edge) { i ->
      val p = px[i]
      labL((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }
    // 从内容框四角洪泛"同色区域"（ΔL 容差 8，与引擎的 A_SOLID 口径无关，只是预演）
    val inRegion = BooleanArray(edge * edge)
    val q = ArrayDeque<Int>()
    fun seed(x: Int, y: Int) {
      val i = y * edge + x
      if (((px[i] ushr 24) and 0xFF) < 8 || inRegion[i]) return
      inRegion[i] = true; q.add(i)
    }
    // 角上可能是透明的（圆角、书脊斜切、细长图形都会），往里找第一个实心格再下种子
    val step = (max(1, (x1 - x0) / 12)).coerceAtMost(max(1, (y1 - y0) / 12))
    fun firstSolid(cx: Int, cy: Int, dx: Int, dy: Int): Pair<Int, Int>? {
      var x = cx; var y = cy
      var k = 0
      while (k++ < 24) {
        if (((px[y * edge + x] ushr 24) and 0xFF) >= 8) return x to y
        x += dx * step; y += dy * step
        if (x < x0 || x > x1 || y < y0 || y > y1) return null
      }
      return null
    }
    for ((cx, cy, dx, dy) in listOf(
      intArrayOf(x0, y0, 1, 1), intArrayOf(x1, y0, -1, 1),
      intArrayOf(x0, y1, 1, -1), intArrayOf(x1, y1, -1, -1))) {
      val (sx, sy) = firstSolid(cx, cy, dx, dy) ?: continue
      seed(sx, sy)
      while (q.isNotEmpty()) {
        val c = q.removeFirst(); val cx = c % edge; val cy = c / edge
        for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
          val nx = cx + dx; val ny = cy + dy
          if (nx < x0 || ny < y0 || nx > x1 || ny > y1) continue
          val j = ny * edge + nx
          if (inRegion[j] || ((px[j] ushr 24) and 0xFF) < 8) continue
          if (abs(L[j] - L[c]) <= 8.0) { inRegion[j] = true; q.add(j) }
        }
      }
    }
    var cells = 0; var adj = 0; var hardAdj = 0
    for (y in y0..y1) for (x in x0..x1) {
      val i = y * edge + x
      if (!inRegion[i]) continue
      cells++
      for ((dx, dy) in listOf(1 to 0, 0 to 1)) {
        val j = (y + dy) * edge + (x + dx)
        if (j >= edge * edge || !inRegion[j]) continue
        adj++
        if (abs(L[j] - L[i]) >= hard) hardAdj++
      }
    }
    if (adj < edge * 2) { System.err.println("跳过 " + f.nameWithoutExtension + " cells=" + cells + " adj=" + adj); continue }
    rows += Triple(hardAdj.toDouble() / adj, cells.toDouble() / (edge * edge), f.nameWithoutExtension)
  }
  if (rows.isEmpty()) { println("!! 一张都没量到 —— 这台子是空的"); kotlin.system.exitProcess(1) }
  rows.sortBy { it.first }
  println("候选底内部的硬边邻接比例（ΔL*≥%.0f），从低到高：".format(hard))
  rows.forEach { println("  %6.2f%%  占画布 %5.1f%%  %s".format(it.first * 100, it.second * 100, it.third.take(44))) }
  val v = rows.map { it.first }.sorted()
  println("中位 %.2f%%  p90 %.2f%%  最狠 %.2f%%  张数 %d".format(
    v[v.size / 2] * 100, v[(v.size * 9 / 10).coerceAtMost(v.size - 1)] * 100, v.last() * 100, v.size))
}
