package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **斜坡形状定线台（只量，不改模型）** —— 2026-10-08。
 *
 * 起因：给 azure 加"塌底守卫"时，用**平均墨量**判塌底，量出来是错的尺子 ——
 * 滴滴塌底 净 76、feimo 正常 净 77，**同一个数**（见 README 那条 ❌）。
 * 平均/中位/占比都是**总量**，而"塌底"与"主体本来更暗"的差别不在总量，在**分布的形状**：
 *  塌底的图 = 像素挤在斜坡**一端**；有调子的图 = 像素**铺满整条**斜坡。
 *
 * 所以这里量两把形状量，都只用这张自己的亮度直方图（L*，实心像素）：
 *  · **偏中度 off** = |p50 − (p5+p95)/2| / (p95−p5) —— 0 = 对称铺满，→0.5 = 全挤在一端。
 *  · **铺满度 cov** = (p80 − p20) / (p95 − p5) —— 中间 60% 的像素占了自己量程的多少。
 *    平涂 logo（一块底 + 一个字形）是**双峰**：中间 60% 挤在某一峰上 → cov 低；
 *    肖像是连续调 → cov 高。
 *
 * 跑法：`java -cp … harness.RampShapeKtKt <目录> [目录…]`
 * ⚠ 这台子**只出数**：先看这两把能不能把"该开火"与"不该开火"分开，分不开就别再动模型。
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }
  if (dirs.isEmpty()) { println("用法：ShapeKtKt <目录> [目录…]"); return }
  println("%-46s %6s %6s %6s %6s %6s".format("图标", "跨度", "偏中度", "铺满度", "暗侧%", "亮侧%"))
  for (d in dirs) {
    val fs = File(d).listFiles { f: File -> f.extension == "png" &&
        !f.name.contains("preview") && !f.name.contains("_mono") } ?: continue
    for (f in fs.sortedBy { it.name }) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val w = im.width; val h = im.height
      val px = IntArray(w * h).also { im.getRGB(0, 0, w, h, it, 0, w) }
      // L* 直方图（0..100），只看实心像素 —— 与 tonalClass 同一口径（同一张表，别再造第二套）
      val hist = IntArray(101); var n = 0
      for (i in px.indices) {
        if (((px[i] ushr 24) and 0xff) < 128) continue
        n++
        val r = ((px[i] shr 16) and 0xff) / 255.0; val g = ((px[i] shr 8) and 0xff) / 255.0
        val b = (px[i] and 0xff) / 255.0
        val f3 = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        val yy = 0.2126 * f3(r) + 0.7152 * f3(g) + 0.0722 * f3(b)
        val fy = { t: Double -> if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0 }
        val lp = 116.0 * fy(yy / 1.0) - 16.0
        hist[(lp.coerceIn(0.0, 100.0).toInt())]++
      }
      if (n < 100) { println("%-46s %s".format(f.nameWithoutExtension.take(46), "实心像素太少")); continue }
      fun pct(p: Int): Int {
        val want = (n.toDouble() * p / 100.0).toLong(); var c = 0L
        for (b2 in 0..100) { c += hist[b2]; if (c >= want) return b2 }
        return 100
      }
      val p5 = pct(5); val p20 = pct(20); val p50 = pct(50); val p80 = pct(80); val p95 = pct(95)
      val span = p95 - p5
      if (span <= 0) { println("%-46s %6d %6s %6s   ← 整张一个调子".format(f.nameWithoutExtension.take(46), 0, "—", "—")); continue }
      val off = Math.abs(p50 - (p5 + p95) / 2.0) / span
      val cov = (p80 - p20).toDouble() / span
      val dark = (0 until 50).sumOf { hist[it] } * 100.0 / n
      println("%-46s %6d %6.2f %6.2f %5.0f/%-4.0f".format(
        f.nameWithoutExtension.take(46), span, off, cov, dark, 100 - dark))
    }
  }
}
