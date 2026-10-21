package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **斜坡的两个端点是谁定的**（只量，不改模型）—— 2026-10-09。
 *
 * 逼它出来的：8 份真机 dump 的 `实255=0`（交付里一个实心像素都没有）。这一支的浓度是
 * `ramp * 255 / (hi − lo)`，而 `lo/hi` 是"这张自己亮度"的 p5/p95 —— **如果那个分位数是在
 * 整张内容框上算的，透明的那一大片（Lab 读出来是黑）就会把 lo 拖到标记自己最暗值之下**，
 * 于是标记最暗的那一面也够不到 255，整张就是一层灰。
 *
 * 所以这里把同一张图的三组端点并排打出来：
 *   `全框` = 内容框里所有格；`实心` = 只有 α=255 的格；`交付` = 模型现在给的最高浓度。
 * 如果 `实心 p5 > 全框 p5` 而 `交付 < 255`，这个类就是**端点被空气拖走**，不是"缺一块地板"。
 */
private fun px(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

private fun p(v: IntArray, q: Int): Int {
  if (v.isEmpty()) return -1
  val s = v.clone(); s.sort(); return s[minOf(v.size - 1, v.size * q / 100)]
}

/** 与模型同一口径的 L*（MonochromeMask.labL 是 L×64，这里自己算 Lab 的 L）。 */
private fun lum(argb: Int): Int {
  val r = ((argb shr 16) and 0xff) / 255.0; val gg = ((argb shr 8) and 0xff) / 255.0
  val b = (argb and 0xff) / 255.0
  val y = 0.2126 * r + 0.7152 * gg + 0.0722 * b
  val f = if (y > 0.008856) Math.cbrt(y) else (7.787 * y + 16.0 / 116.0)
  return ((116.0 * f - 16.0) * 64.0).toInt()
}

fun main(args: Array<String>) {
  val edge = 384
  val dirs = args.toList().ifEmpty { listOf("rec3u", "rec_blank") }
  println("%-46s %5s %6s %6s %6s %6s %6s %6s".format(
    "图标", "支", "透明%", "全框p5", "实心p5", "全框p95", "实心p95", "交付最高"))
  for (d in dirs) for (f in File(d).listFiles { x: File -> x.extension == "png" }!!.sortedBy { it.name }) {
    val src = runCatching { px(f, edge) }.getOrNull() ?: continue
    val r = MonochromeMask.build(src, edge, edge)
    val all = ArrayList<Int>(); val sol = ArrayList<Int>()
    var air = 0
    for (i in src.indices) {
      val a = (src[i] ushr 24) and 0xff
      if (a < 250) { air++; all.add(0) } else { all.add(lum(src[i])); sol.add(lum(src[i])) }
    }
    val top = r.mask?.maxOfOrNull { (it ushr 24) and 0xff } ?: -1
    val ai = all.toIntArray(); val si = sol.toIntArray()
    val line = "%-46s %5s %5d%% %6d %6d %6d %6d %6d".format(
      f.nameWithoutExtension.take(44), if (r.report.contains("调=1")) "满幅" else "参照",
      air * 100 / src.size, p(ai, 5), p(si, 5), p(ai, 95), p(si, 95), top)
    if (top < 250 || air * 100 / src.size > 55) println(line)
  }
  println("\n只打出「交付最高 <250」或「透明 >55%」的那些 —— 前者是 ghost 类，后者是端点最容易被空气拖走的那一类")
}
