package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 「没有参照的时候怎么办」的对照台 —— 只测不改。
 *
 * 点名的四张（browndust2 / sgameGlobal / sgame / wisdomgame）在模型里落到**四支不同的分支**
 * （H1 底占 0.86 / H2 / H3 空气参照 ×2），`明暗` 权重是 0 / 59 / 100 / 1，
 * 而四张的内容类型是同一类：**满幅插画，压根没有"哪块是盘"这件事**。
 * 所以这里把"不依赖参照"的几种画法一次性摆出来逐张看：
 *
 *   A 模型现在交的                     —— 现成的 `MonochromeMask.build`
 *   B 绝对亮度当浓度，**暗=墨**         —— 与模型对普通图形的极性一致（见 polarity 那一段）
 *   C 绝对亮度当浓度，**亮=墨**         —— 桌面原生那条假定
 *   D 高通：亮度减局部均值             —— 只留线条与边缘（插画可能是"描边"而不是"色块"）
 *
 * 归一用**这张图自己的** p5/p95（不是最值）：一条 JPEG 噪点线不该决定标尺。
 * 端点分位与 [MonochromeMask] 里 `LUM_LOW_PCT/LUM_HIGH_PCT` 同口径，好对照。
 *
 * 用法：`java -cp out;stubs_out;<stdlib> harness.LumaKtKt <目录> [--edge=384] [--shot=DIR]`
 */

private fun load(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null)
  g.dispose()
  val px = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, px, 0, edge)
  return px
}

/** sRGB → CIE L\\*（0..255 标度）。透明像素按"没有亮度"处理，调用方自己跳过。 */
private fun luma(p: Int): Int {
  fun ch(v: Int): Double {
    val c = v / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }
  val y = 0.2126 * ch(p shr 16 and 0xff) + 0.7152 * ch(p shr 8 and 0xff) + 0.0722 * ch(p and 0xff)
  val f = if (y > 0.008856) y.pow(1.0 / 3.0) else 7.787 * y + 16.0 / 116.0
  return ((116.0 * f - 16.0) * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
}

private fun pctile(hist: IntArray, n: Int, p: Int): Int {
  if (n <= 0) return 0
  val want = (n * p / 100.0).toInt().coerceIn(0, n - 1)
  var run = 0
  for (b in hist.indices) { run += hist[b]; if (run > want) return b }
  return 255
}

/** 局部均值（盒式，半径 r）。跑在 384² 上，用两趟一维前缀和，不开 KD 树。 */
private fun boxMean(lum: IntArray, w: Int, h: Int, r: Int): IntArray {
  val out = IntArray(lum.size)
  val acc = IntArray(lum.size + 1)
  for (y in 0 until h) {
    val row = y * w
    var s = 0
    for (x in 0 until w) { s += lum[row + x]; acc[row + x + 1] = s }
  }
  for (y in 0 until h) for (x in 0 until w) {
    var sum = 0L; var n = 0
    val y0 = (y - r).coerceAtLeast(0); val y1 = (y + r).coerceAtMost(h - 1)
    val x0 = (x - r).coerceAtLeast(0); val x1 = (x + r).coerceAtMost(w - 1)
    for (yy in y0..y1) sum += acc[yy * w + x1 + 1] - acc[yy * w + x0]
    n = (y1 - y0 + 1) * (x1 - x0 + 1)
    out[y * w + x] = (sum / n).toInt()
  }
  return out
}

private fun ramp(lum: IntArray, alpha: IntArray, lo: Int, hi: Int, span: Int, invert: Boolean): IntArray {
  val out = IntArray(lum.size)
  for (i in out.indices) {
    if (alpha[i] < 128) continue
    var t = (lum[i] - lo) * 255 / span
    if (!invert) t = 255 - t
    out[i] = t.coerceIn(0, 255)
  }
  return out
}

private fun highPass(lum: IntArray, mean: IntArray, alpha: IntArray, k: Int): IntArray {
  val out = IntArray(lum.size)
  for (i in out.indices) {
    if (alpha[i] < 128) continue
    out[i] = ((mean[i] - lum[i]) * k / 100 + 128).coerceIn(0, 255)
  }
  return out
}

private fun prev(a: IntArray, w: Int, h: Int, plate: Int, ink: Int): IntArray =
  IntArray(a.size) { i ->
    val v = if (a[i] >= 0) a[i] else 0
    val r = (((plate shr 16 and 0xff) * (255 - v) + (ink shr 16 and 0xff) * v) / 255) shl 16
    val g = (((plate shr 8 and 0xff) * (255 - v) + (ink shr 8 and 0xff) * v) / 255) shl 8
    val b = ((plate and 0xff) * (255 - v) + (ink and 0xff) * v) / 255
    (0xff shl 24) or r or g or b
  }

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val outDir = File(args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=") ?: "bench/luma")
    .apply { mkdirs() }
  val hpK = args.firstOrNull { it.startsWith("--hp=") }?.substringAfter("=")?.toInt() ?: 100
  val dir = File(args.first { !it.startsWith("--") })
  val cell = 240
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    val alpha = IntArray(px.size) { (px[it] ushr 24) and 0xff }
    val lum = IntArray(px.size) { luma(px[it] and 0x00FFFFFF) }
    val hist = IntArray(256); var n = 0
    for (i in px.indices) if (alpha[i] >= 128) { hist[lum[i]]++; n++ }
    val lo = pctile(hist, n, 5); val hi = pctile(hist, n, 95)
    val span = (hi - lo).coerceAtLeast(1)
    val t0 = System.nanoTime()
    val model = MonochromeMask.build(px, edge, edge)
    val ms = (System.nanoTime() - t0) / 1_000_000
    val m = model.mask ?: IntArray(px.size)
    val mA = IntArray(m.size) { (m[it] ushr 24) and 0xff }
    val darkInk = ramp(lum, alpha, lo, hi, span, invert = false)
    val brightInk = ramp(lum, alpha, lo, hi, span, invert = true)
    val hp = highPass(lum, boxMean(lum, edge, edge, (edge / 24).coerceAtLeast(2)), alpha, hpK)
    println("--- ${f.nameWithoutExtension}  ${model.report.substringAfter("明暗=").substringBefore(" ")
      .let { "明暗=$it" }} · ${ms}ms | 亮度 p5=$lo p95=$hi 跨=$span")

    fun tile(argb: IntArray): BufferedImage =
      BufferedImage(cell, cell, BufferedImage.TYPE_INT_RGB).also { t ->
        val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB).apply {
          setRGB(0, 0, edge, edge, argb, 0, edge)
        }
        val gg = t.createGraphics()
        gg.drawImage(big, 0, 0, cell, cell, null); gg.dispose()
      }
    val panels = listOf(
      IntArray(px.size) { (0xff shl 24) or (px[it] and 0x00FFFFFF) },          // 源
      prev(mA, edge, edge, 0xEDEDED, 0x1F1F1F),                                  // A 模型
      prev(darkInk, edge, edge, 0xEDEDED, 0x1F1F1F),                              // B 暗=墨
      prev(brightInk, edge, edge, 0xEDEDED, 0x1F1F1F),                            // C 亮=墨
      prev(hp, edge, edge, 0xEDEDED, 0x1F1F1F),                                   // D 高通
      prev(mA, edge, edge, 0x2B2B2B, 0xE8E8E8)                                    // E 模型·深色主题
    )
    val img = BufferedImage(cell * panels.size + 4 * (panels.size - 1), cell, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    var x = 0
    for (p in panels) { g.drawImage(tile(p), x, 0, null); x += cell + 4 }
    g.dispose()
    ImageIO.write(img, "png", File(outDir, "${f.nameWithoutExtension}_luma.png"))
  }
  println("列序：源 | A=模型 | B=绝对亮度·暗→墨 | C=绝对亮度·亮→墨 | D=高通(局部均值−亮度) | E=模型·深色主题")
}
