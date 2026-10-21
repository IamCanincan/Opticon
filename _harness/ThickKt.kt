package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 一张图里**最占面积的那块色有多厚** —— 用来分「这是一块底板，上面坐着图形」和
 * 「这整张就是图形本身（线条字）」，这两种情况该交出的图正好相反。
 *
 * 做法：取不透明像素里最像"多数色"的那一块，从它的**边界**（挨着别的颜色或挨着空气）
 * 做多源距离变换，报这块区域到边界的距离分布（p50 / p85 / 最厚处），按 384 边长归一。
 *  · 满幅圆盘、卡片底：p85 上百 —— 厚，是底板；
 *  · 线条字、描边图形：p85 只有几像素 —— 薄，那就是图形本身。
 *
 * 用法：`ThickKt [--edge=N] 目录 …`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }
  val files = dirs.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.endsWith("_preview.png") }.sortedBy { it.name }
    else listOf(f)
  }
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val src = IntArray(img.width * img.height)
    img.getRGB(0, 0, img.width, img.height, src, 0, img.width)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
      val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
      px[y * edge + x] = src[sy * img.width + sx]
    }
    val r = MonochromeMask.build(px, edge, edge)
    val t = thickness(px, edge) ?: continue
    val verdict = if (t.p85 >= 24) "厚 → 那是底板" else "薄 → 那就是图形本身"
    println("%-62s 多数色占不透明 %4.1f%%  到边界 p50=%3d p85=%3d 最厚=%3d  %s"
      .format(f.nameWithoutExtension.take(62), t.share * 100, t.p50, t.p85, t.max, verdict))
    println("    ${r.report.take(150)}")
  }
}

private class T(var share: Double, var p50: Int, var p85: Int, var max: Int)

/** 中位色 + 距离变换。为量得准，直接按 Lab 的粗桶取最大的一块同类色。 */
private fun thickness(px: IntArray, n: Int): T? {
  var opaque = 0
  var mr = 0L; var mg = 0L; var mb = 0L
  for (p in px) {
    if (((p ushr 24) and 0xff) < 128) continue
    opaque++
    mr += (p shr 16) and 0xff; mg += (p shr 8) and 0xff; mb += p and 0xff
  }
  if (opaque < 64) return null
  val R = mr / opaque; val G = mg / opaque; val B = mb / opaque
  // 多数块 = 与"不透明像素的平均色"够近的那些（粗略的 sRGB 距离，够分厚薄）
  val same = BooleanArray(px.size)
  var cnt = 0
  for (i in px.indices) {
    val a = (px[i] ushr 24) and 0xff
    if (a < 128) continue
    val dr = ((px[i] shr 16) and 0xff) - R
    val dg = ((px[i] shr 8) and 0xff) - G
    val db = (px[i] and 0xff) - B
    if (dr * dr + dg * dg + db * db < 128 * 128) { same[i] = true; cnt++ }
  }
  if (cnt == 0) return null
  // 多源 BFS：起点是"多数块里挨着别的颜色或空气"的那些
  val dist = IntArray(px.size) { -1 }
  val q = IntArray(px.size)
  var head = 0; var tail = 0
  for (y in 0 until n) for (x in 0 until n) {
    val i = y * n + x
    if (!same[i]) continue
    var edge = false
    for ((dx, dy) in PAIRS) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= n || ny >= n || !same[ny * n + nx]) { edge = true; break }
    }
    if (edge) { dist[i] = 0; q[tail++] = i }
  }
  if (tail == 0) return null
  while (head < tail) {
    val i = q[head++]
    val x = i % n; val y = i / n
    for ((dx, dy) in PAIRS) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
      val j = ny * n + nx
      if (same[j] && dist[j] < 0) { dist[j] = dist[i] + 1; q[tail++] = j }
    }
  }
  val hist = IntArray(512)
  var seen = 0; var mx = 0
  for (i in px.indices) if (same[i] && dist[i] >= 0) {
    hist[dist[i].coerceAtMost(511)]++; seen++; if (dist[i] > mx) mx = dist[i]
  }
  if (seen == 0) return null
  fun pct(p: Int): Int {
    var run = 0
    for (v in hist.indices) { run += hist[v]; if (run * 100 >= seen * p) return v }
    return hist.size - 1
  }
  return T(cnt.toDouble() / opaque, pct(50), pct(85), mx)
}

private val PAIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
