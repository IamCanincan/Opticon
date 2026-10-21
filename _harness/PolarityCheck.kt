package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * 从真机带回来的 (src, mask) 配对里**量出桌面的极性规则**，不再靠看图猜。
 *
 * corr(亮度, 遮罩 alpha) 接近 −1 → 桌面是「暗=墨」；接近 +1 → 「亮=墨」。
 * 另外单看两块：图形内部（src 实心）与图形之外（src 透明）各自的平均 alpha —— 透明区要是
 * 被画成实心，说明它把"没有东西"当成了最暗。
 *
 * 为什么先归一到同一格：桌面的遮罩是它自己那套尺寸（这台机上是 341），我抓的 src 是 192。
 * 之前按像素逐一对齐，尺寸一不同就整对丢掉，结果"配对 0 张"——量不出任何东西。
 * 两边都按面积平均降采样到同一小格，相关性这种统计量不关心原尺寸。
 */
const val GRID = 128

/**
 * 面积平均降采样。返回三张 GRID×GRID 的表：
 *  - lum：该格的有效亮度（透明像素 RGB 未定义，按"无覆盖即白"补，不掺随机的黑）
 *  - cov：该格的平均 alpha，线性的。**src 用它当覆盖率；mask 用它当墨量** ——
 *        抓回来的遮罩是 ALPHA_8 铺进 ARGB 的 alpha 通道，RGB 全 0，
 *        所以遮罩的证据只能从 alpha 读，且必须线性平均（平方会把中间调压成 0）。
 *  - dens：按 a² 平均，只在需要"画上去有多少墨"时用。
 */
fun sample(img: java.awt.image.BufferedImage): Triple<DoubleArray, DoubleArray, DoubleArray> {
  val w = img.width; val h = img.height
  val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
  val lum = DoubleArray(GRID * GRID); val cov = DoubleArray(GRID * GRID); val dens = DoubleArray(GRID * GRID)
  val cw = w.toDouble() / GRID; val ch = h.toDouble() / GRID
  for (gy in 0 until GRID) for (gx in 0 until GRID) {
    val x0 = (gx * cw).toInt(); val x1 = maxOf(x0 + 1, minOf(w, ((gx + 1) * cw).toInt()))
    val y0 = (gy * ch).toInt(); val y1 = maxOf(y0 + 1, minOf(h, ((gy + 1) * ch).toInt()))
    var lsum = 0.0; var wsum = 0.0; var asum = 0.0; var qsum = 0.0; var n = 0
    for (y in y0 until y1) for (x in x0 until x1) {
      val p = px[y * w + x]
      val a = ((p ushr 24) and 0xff) / 255.0
      val r = if (a > 0) (p ushr 16 and 0xff).toDouble() else 255.0
      val g = if (a > 0) (p ushr 8 and 0xff).toDouble() else 255.0
      val b = if (a > 0) (p and 0xff).toDouble() else 255.0
      lsum += (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
      wsum += a; asum += a; qsum += a * a; n++
    }
    val i = gy * GRID + gx
    lum[i] = if (wsum > 0) lsum / wsum else 255.0
    cov[i] = asum / n
    dens[i] = qsum / n
  }
  return Triple(lum, cov, dens)
}

fun main() {
  val dir = File("cap3")
  val srcs = dir.listFiles { f -> f.name.endsWith("-src.png") }!!.sortedBy { it.name }
  val masks = dir.listFiles { f -> f.name.endsWith("-mask.png") } ?: emptyArray()
  var n = 0
  val seen = HashSet<String>()
  for (src in srcs) {
    val key = src.name.substringAfter("_").removeSuffix("-src.png")
    if (!seen.add(key)) continue                       // 同一个图标抓了两次，只算一次
    val maskFile = masks.firstOrNull { it.name.substringAfter("_").removeSuffix("-mask.png") == key } ?: continue
    val (sl, sc, _) = sample(ImageIO.read(src))
    val (_, mk, _) = sample(ImageIO.read(maskFile))
    // 只在"src 有东西"的格子上算相关性：透明格的 src 亮度是补出来的白，不是证据。
    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var c = 0
    var onInk = 0.0; var nInk = 0; var onAir = 0.0; var nAir = 0
    for (i in 0 until GRID * GRID) {
      if (sc[i] >= 0.8) { onInk += mk[i]; nInk++ } else if (sc[i] <= 0.05) { onAir += mk[i]; nAir++ } else continue
      val x = sl[i]; val y = mk[i]
      if (sc[i] < 0.8) continue                        // 半覆盖格不参与相关性，只进上面两桶
      sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; c++
    }
    val vx = sxx / c - (sx / c) * (sx / c)
    val vy = syy / c - (sy / c) * (sy / c)
    val corr = if (c < 32 || vx <= 0 || vy <= 0) 0.0 else cov(sxy, c, sx, sy) / sqrt(vx * vy)
    n++
    val inkShare = if (nInk + nAir > 0) 1.0 * nInk / (nInk + nAir) else 0.0
    println("%-46s corr=%+.2f  src实心处遮罩墨=%.0f  src透明处遮罩墨=%.0f  实心占=%.2f  (墨格%d)"
      .format(key.take(44), corr, if (nInk > 0) onInk / nInk * 255 else -1.0,
        if (nAir > 0) onAir / nAir * 255 else -1.0, inkShare, c))
  }
  println("配对的图标：$n 张")
  if (n == 0) println("（一张都没配上 —— 先把 cap3 里的 -mask.png 抓回来）")
  println("--- 判据在这些真图上的结论 ---")
  val done = HashSet<String>()
  for (src in srcs) {
    val key = src.name.substringAfter("_").removeSuffix("-src.png")
    if (!done.add(key)) continue
    val a = ImageIO.read(src)
    val w = a.width
    val px = IntArray(w * w)
    a.getRGB(0, 0, w, w, px, 0, w)
    val p = com.iamcanincan.opticon.circle.MonochromeMask.polarity(px, w, w)
    println("%-44s %s".format(key.take(42), p.report))
  }
}

fun cov(sxy: Double, c: Int, sx: Double, sy: Double) = sxy / c - (sx / c) * (sy / c)
