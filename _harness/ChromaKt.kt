package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **色度跨度定线台（只量，不改模型）** —— 2026-10-08。
 *
 * 主人要"所有图标都像 feimo 那样有渐变"。量完发现 11 个包的前景层 **L 跨度是 0~1**
 * （钉钉、大众点评、小黑盒、美团、TapTap、微信、steamlink、支付宝…）——
 * 明度轴上确实变不出中间调。但**"没有明度差"不等于"没有差"**：
 * 一张双色 logo 的两块可以一样亮而颜色不同，一个蓝色渐变标的明度也可以几乎不动而彩度在动。
 * 所以这一台问一个很窄的问题：**换成 ΔE（离这张自己的主色多远）之后，这些图的可用量程还剩多少？**
 *
 * 主色 = 实心像素按 RGB>>4 粗分桶后**占数最多**那一桶的平均色（不是精确色计数 —— 精确色会被
 * 渐变骗，那条在本仓量过：`框内=0.9856` 全涂墨）。
 *
 * 跑法：`java -cp … harness.ChromaKtKt <目录> [目录…]`
 * 读数：`L跨` = p95−p5 的明度量程；`ΔE跨` = 同样取 p95−p5 的色距量程；
 * **`ΔE跨 / L跨` 大 = 明度轴漏掉了大量信息**，这才是 ΔE 那一刀可能有对象的那些图。
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }
  if (dirs.isEmpty()) { println("用法：ChromaKtKt <目录> [目录…]"); return }
  println("%-40s %6s %7s %7s %7s".format("图标", "L跨", "ΔE跨", "ΔE/L", "主色"))
  for (d in dirs) {
    val fs = File(d).listFiles { f: File -> f.extension == "png" &&
        !f.name.contains("preview") && !f.name.contains("_mono") } ?: continue
    for (f in fs.sortedBy { it.name }) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val w = im.width; val h = im.height
      val px = IntArray(w * h).also { im.getRGB(0, 0, w, h, it, 0, w) }
      val solid = ArrayList<Int>(w * h)
      for (p in px) if (((p ushr 24) and 0xff) >= 128) solid.add(p)
      if (solid.size < 100) { println("%-40s 实心太少".format(f.nameWithoutExtension.take(40))); continue }
      // 主色：RGB>>4 粗桶里占数最多那一桶的平均色
      val bucket = HashMap<Int, LongArray>()   // key → [n, r, g, b]
      for (p in solid) {
        val k = ((p shr 20) and 0xf0) or ((p shr 12) and 0x0f)   // 近似：取 R/G 的高位做桶
        val a = bucket.getOrPut(k) { LongArray(4) }
        a[0]++; a[1] += (p shr 16) and 0xff; a[2] += (p shr 8) and 0xff; a[3] += p and 0xff
      }
      val top = bucket.values.maxByOrNull { it[0] }!!
      val ref = ((top[1] / top[0]).toInt() shl 16) or ((top[2] / top[0]).toInt() shl 8) or (top[3] / top[0]).toInt()
      fun lab(p: Int, which: Int): Double {
        val r = ((p shr 16) and 0xff) / 255.0; val g = ((p shr 8) and 0xff) / 255.0; val b = (p and 0xff) / 255.0
        val f = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        val X = (0.4124 * f(r) + 0.3576 * f(g) + 0.1805 * f(b)) / 0.95047
        val Y = 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
        val Z = (0.0193 * f(r) + 0.1192 * f(g) + 0.9505 * f(b)) / 1.08883
        val g3 = { t: Double -> if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0 }
        val lx = g3(X); val ly = g3(Y); val lz = g3(Z)
        return when (which) { 0 -> 116.0 * ly - 16.0; 1 -> 500.0 * (lx - ly); else -> 200.0 * (ly - lz) }
      }
      val rl = lab(ref, 0); val ra = lab(ref, 1); val rb = lab(ref, 2)
      val lH = IntArray(102); val eH = IntArray(102)
      for (p in solid) {
        lH[lab(p, 0).coerceIn(0.0, 100.0).toInt()]++
        val dl = lab(p, 0) - rl; val da = lab(p, 1) - ra; val db = lab(p, 2) - rb
        eH[(Math.sqrt(dl * dl + da * da + db * db)).coerceIn(0.0, 100.0).toInt()]++
      }
      fun pct(h: IntArray, p: Int): Int {
        val n = h.sum().toDouble(); val want = n * p / 100.0; var c = 0.0
        for (i in h.indices) { c += h[i]; if (c >= want) return i }
        return h.size - 1
      }
      val lSpan = pct(lH, 95) - pct(lH, 5); val eSpan = pct(eH, 95) - pct(eH, 5)
      println("%-40s %6d %7d %7s %4d,%3d,%3d".format(f.nameWithoutExtension.take(40),
        lSpan, eSpan, if (lSpan == 0) "∞" else "%.1f".format(eSpan.toDouble() / lSpan),
        (ref shr 16) and 0xff, (ref shr 8) and 0xff, ref and 0xff))
    }
  }
}
