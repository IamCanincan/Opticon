package harness

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **两块挨着，是"一个物体的渐变"还是"两个物体的硬边"？**
 *
 * 为什么要有这一台：主人在这条轴上说过两句**方向相反**的话 ——
 *  · 2026-10-12（sangfor 那道盾）：「色度可以广，**别让有些过渡也难看**」→ 不许为色度差画明度台阶；
 *  · 2026-10-13（易班青带 vs 熊头）：「**粘在一起**」→ 这两块得拉开。
 * 同一个动作（把相邻两块的 alpha 拉开）在一张上是对的、另一张上是错的，说明缺的不是勇气，
 * 是**一个能把这两种情况分开的判据**。这一台量的就是那个判据：
 *
 * **源图里这两块颜色之间有没有中间色。**
 *  · 有（一片连续的颜色把 A 和 B 接起来）= 一个物体自己的**渐变** → 加台阶 = 在平滑的地方劈一刀（sangfor）；
 *  · 没有（A 的像素直接挨着 B 的像素，中间那种颜色压根不存在）= **两个物体**的交界 → 不加台阶 = 粘成一片（易班）。
 *
 * 只统计"**亮度几乎相同、色度差得开**"的那一对（`|ΔY| ≤ dY` 且 `dRGB ≥ dC`）—— 那才是
 * "单通道本来无处安放"的那一族；亮度本来就不同的两块不需要这个判据（距离自己就把它们分开了）。
 *
 * 用法：`BoundaryKindKt <目录|图> [--edge=384] [--adj=200] [--show=名,名]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt()) ?: 384
  val adjMin = (args.firstOrNull { it.startsWith("--adj=") }?.substringAfter("=")?.toInt()) ?: 200
  val show = args.firstOrNull { it.startsWith("--show=") }?.substringAfter("=")?.split(",") ?: emptyList()
  val dYmax = (args.firstOrNull { it.startsWith("--dy=") }?.substringAfter("=")?.toDouble()) ?: 8.0
  val dCmin = (args.firstOrNull { it.startsWith("--dc=") }?.substringAfter("=")?.toDouble()) ?: 90.0
  val dbg = args.contains("--pairs")
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
      !it.name.endsWith("_modelbg.png") }.sortedBy { it.name } else listOf(f)
  }
  println("== 相邻两块：渐变 or 硬边（只看亮度同、色度异的那一对）==")
  var hardIcons = 0; var gradIcons = 0; var both = 0; var n = 0
  val hardRows = ArrayList<String>(); val gradRows = ArrayList<String>()
  for (f in files) {
    val img = ImageIO.read(f) ?: continue
    val cv = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g0 = cv.createGraphics(); g0.color = Color(0, 0, 0, 0); g0.fillRect(0, 0, edge, edge)
    g0.drawImage(img, 0, 0, edge, edge, null); g0.dispose()
    val px = IntArray(edge * edge); cv.getRGB(0, 0, edge, edge, px, 0, edge)
    // 按 12 位色（每通道 16 级）归堆 —— 比模型的色桶更粗，目的是让"渐变"落到**不同**的堆里，
    // 这样"中间有没有堆"才量的是真的中间色，而不是同一个堆里的抖动。
    val cnt = HashMap<Int, LongArray>()          // key -> [像素数, r和, g和, b和]
    val key = IntArray(edge * edge) { -1 }
    for (i in 0 until edge * edge) {
      if (((px[i] ushr 24) and 0xff) < 128) continue
      val r = (px[i] shr 16) and 0xff; val gg = (px[i] shr 8) and 0xff; val b = px[i] and 0xff
      val k = ((r shr 4) shl 8) or ((gg shr 4) shl 4) or (b shr 4)
      key[i] = k
      val a = cnt.getOrPut(k) { LongArray(4) }
      a[0]++; a[1] += r; a[2] += gg; a[3] += b
    }
    if (cnt.size < 2) continue
    n++
    val mean = HashMap<Int, IntArray>()
    for ((k, a) in cnt) if (a[0] >= adjMin) mean[k] = intArrayOf((a[1] / a[0]).toInt(), (a[2] / a[0]).toInt(), (a[3] / a[0]).toInt())
    // 邻接对
    val adj = HashMap<Int, Int>()
    for (y in 0 until edge) for (x in 0 until edge) {
      val i = y * edge + x; val ka = key[i]; if (ka < 0) continue
      for (j in intArrayOf(if (x + 1 < edge) i + 1 else -1, if (y + 1 < edge) i + edge else -1)) {
        if (j < 0) continue; val kb = key[j]; if (kb < 0 || kb == ka) continue
        val pk = (min(ka, kb) shl 16) or max(ka, kb)
        adj[pk] = (adj[pk] ?: 0) + 1
      }
    }
    var iconHard = false; var iconGrad = false
    for ((pk, c) in adj) {
      if (c < adjMin) continue
      val a = mean[pk ushr 16] ?: continue; val b = mean[pk and 0xffff] ?: continue
      val dY = abs(lum(a) - lum(b))
      val dAB = dist(a, b)
      if (dY > dYmax || dAB < dCmin) continue                // 不是"亮度同、色度异"这一族（默认 ΔL* ≤ 8）
      if (dbg && show.isNotEmpty() && show.any { f.name.contains(it) })
        println("    [对] 邻接 %6d  ΔL*=%5.1f  色差=%5.0f".format(c, dY, dAB));
      // **中间色**：到两头的距离都不超过"两头之间距离的一半再多一点"的那些像素
      var mid = 0L
      for ((k, v) in cnt) {
        if (k == (pk ushr 16) || k == (pk and 0xffff)) continue
        val m = intArrayOf((v[1] / v[0]).toInt(), (v[2] / v[0]).toInt(), (v[3] / v[0]).toInt())
        if (dist(m, a) <= dAB * 0.62 && dist(m, b) <= dAB * 0.62) mid += v[0]
      }
      val share = mid * 100.0 / (mid + (cnt[pk ushr 16]?.get(0) ?: 0L) + (cnt[pk and 0xffff]?.get(0) ?: 0L) + 1)
      val row = "    %-42s 邻接 %6d 格  ΔL*=%3.0f 色差=%3.0f  中间色 %5.1f%%  %s"
        .format(f.nameWithoutExtension.take(40), c, dY, dAB, share, if (share < 2.0) "硬边（两物体）" else if (share >= 5.0) "渐变（一物体）" else "中间态")
      if (share < 2.0) { iconHard = true; hardRows += row }
      else if (share >= 5.0) { iconGrad = true; gradRows += row }
      if (show.isNotEmpty() && show.any { f.name.contains(it) }) println(row)
    }
    if (iconHard) hardIcons++; if (iconGrad) gradIcons++; if (iconHard && iconGrad) both++
  }
  println("\n$n 张里：有**硬边**色度对的 $hardIcons 张；有**渐变**色度对的 $gradIcons 张；两者都有的 $both 张")
  println("\n—— 硬边名单（前 14 行）——"); hardRows.take(14).forEach { println(it) }
  println("\n—— 渐变名单（前 14 行）——"); gradRows.take(14).forEach { println(it) }
}

private fun lum(c: IntArray): Double {
  // **CIELAB 的 L\*，不是 RGB 亮度。** 第一版用了 `0.299R+0.587G+0.114B`，于是易班那一对
  // （青带 vs 黄熊，L\* 86.0 vs 85.7，差 0.3）被算成"亮度差 43"当场滤掉 —— 全语料 **0 命中**。
  // 0 命中是关于量具的陈述：这条轴上"亮度相同"必须按感知亮度说，模型自己也是这么说的。
  fun lin(v: Int): Double {
    val s = v / 255.0
    return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
  }
  val y = 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
  return if (y <= 0.008856) 903.3 * y else 116.0 * Math.pow(y, 1.0 / 3.0) - 16.0
}
private fun dist(a: IntArray, b: IntArray): Double =
  sqrt(((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])).toDouble())
