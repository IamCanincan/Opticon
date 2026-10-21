package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **按矩形读源图的颜色（只量）** —— 2026-10-09，为 azure 的锁梁写的。
 *
 * 两支队都把锁梁丢了（满幅那一支的极性把它擦成 0；参照那条支把它连同盾牌一起判成底）。
 * 在决定"还要不要再找一把尺子"之前，先要一个数：**锁梁与盾牌在颜色上到底差多少。**
 * 差得远 → 是判据没用好，值得修；差得近（同一色系只差一点明度）→ 任何颜色统计都分不开，
 * 这就是本仓那条墙本身，别再找第九把尺子。
 *
 * 跑法：`java -cp … harness.RectKtKt <png> [--r=x,y,w,h:名字] …`
 */
private fun labL(px: Int): Double {
  val f = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
  val y = 0.2126 * f(((px shr 16) and 0xff) / 255.0) +
    0.7152 * f(((px shr 8) and 0xff) / 255.0) +
    0.0722 * f((px and 0xff) / 255.0)
  val fy = { t: Double -> if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0 }
  return 116.0 * fy(y) - 16.0
}

fun main(args: Array<String>) {
  val path = args.firstOrNull { !it.startsWith("--") } ?: "x"
  val img = runCatching { ImageIO.read(File(path)) }.getOrNull() ?: run { println("读不出 $path"); return }
  val w = img.width; val h = img.height
  val px = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
  println("$path = ${w}x$h")
  println("%-12s %6s %6s %6s %8s %8s %8s".format("区域", "α均", "R", "G", "L*", "a*", "b*"))
  for (a in args.filter { it.startsWith("--r=") }) {
    val body = a.substringAfter("--r=")
    val (geom, name) = body.split(":").let { it[0] to (if (it.size > 1) it[1] else "?") }
    val g = geom.split(",").map { it.toInt() }
    var n = 0L; var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var sl = 0.0
    var lmin = 999.0; var lmax = -999.0
    for (y in g[1] until (g[1] + g[3]).coerceAtMost(h)) for (x in g[0] until (g[0] + g[2]).coerceAtMost(w)) {
      val p = px[y * w + x]
      val al = (p ushr 24) and 0xff
      if (al < 200) continue
      n++; sa += al; sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
      val l = labL(p); sl += l; if (l < lmin) lmin = l; if (l > lmax) lmax = l
    }
    if (n == 0L) { println("%-12s  这一格没有实心像素".format(name.take(12))); continue }
    // Lab 的 a*/b*（D65）
    val f = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    val rr = f((sr / n) / 255.0); val gg = f((sg / n) / 255.0); val bb = f((sb / n) / 255.0)
    val xx = (0.4124 * rr + 0.3576 * gg + 0.1805 * bb) / 0.95047
    val yy = 0.2126 * rr + 0.7152 * gg + 0.0722 * bb
    val zzz = (0.0193 * rr + 0.1192 * gg + 0.9505 * bb) / 1.08883
    val fw = { t: Double -> if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0 }
    val lStar = 116.0 * fw(yy) - 16.0
    println("%-10s %5.0f %4.0f %4.0f %4.0f %6.1f %6.1f %6.1f %.0f..%.0f".format(
      name.take(10), sa.toDouble() / n, sr.toDouble() / n, sg.toDouble() / n, sb.toDouble() / n,
      lStar, 500.0 * (fw(xx) - fw(yy)), 200.0 * (fw(yy) - fw(zzz)), lmin, lmax))
  }
}
