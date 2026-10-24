package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * 快捷方式几何对表（2026-10-25）：
 *  ① 同一条"细环 + 图案在圆内"判据，在**彩色格子**（`scanSize`，通常是原尺寸 / 128）与
 *     **判据格子**（edge=384）上各跑一遍 —— 这不是要求两边一致，恰恰是**量它们会不一致**：
 *     实测 45 张里 2 张（拼多多 one_click_search / search_express）在 384 上 `ringFits` 失败，
 *     因为放大后环的内沿压到了 0.95R 边界。生产据此把**染色侧的快捷方式几何改成读彩色那一本**
 *     （`colourWindow` + [IconCropWindow.shortcutFullBleed]），不再量 384。
 *  ② 因此硬闸只钉"环检测本身"两格一致（那是形状，不该随分辨率变）；满幅结论的差异只报数。
 *
 * 用法：`sh run.sh harness.AlignKtKt shortcut_bit [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "shortcut_bit")
  var n = 0; var badRing = 0; var full = 0; var fitDrift = 0
  println("%-46s %-5s %-9s %-9s %-9s %-9s %-9s".format(
    "图", "环一致", "环@彩/判", "fit@彩/判", "满幅@彩/判", "窗@彩", "窗@判"))
  for (f in dir.listFiles()!!.filter { it.extension.equals("png", true) }.sortedBy { it.name }) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val (cw, ch) = IconCropWindow.scanSize(img.width, img.height, false)
    val cpx = raster(img, cw, ch)
    val mpx = raster(img, edge, edge)
    val wc = IconCropWindow.of(cpx, cw, ch)
    val wm = IconCropWindow.of(mpx, edge, edge)
    val ringC = IconCropWindow.edgeRing(cpx, cw, ch)
    val ringM = IconCropWindow.edgeRing(mpx, edge, edge)
    val fitC = ringC && IconCropWindow.ringFits(cpx, cw, ch)
    val fitM = ringM && IconCropWindow.ringFits(mpx, edge, edge)
    val fbC = IconCropWindow.shortcutFullBleed(wc, cpx, cw, ch)
    val fbM = IconCropWindow.shortcutFullBleed(wm, mpx, edge, edge)
    n++
    if (fbC) full++
    if (fbC != fbM) fitDrift++
    if (ringC != ringM) badRing++
    println("%-46s %-5s %-9s %-9s %-9s %-9s %-9s".format(
      f.name.take(46), if (ringC == ringM) "是" else "**否**",
      "$ringC/$ringM", "$fitC/$fitM", "$fbC/$fbM",
      wc?.let { "%.2f".format(it.zoom) } ?: "满幅",
      wm?.let { "%.2f".format(it.zoom) } ?: "满幅"))
  }
  println("共 $n 张；彩色按满幅 $full 张；环检测两格不一致 $badRing 张；" +
    "满幅结论两格不一致 $fitDrift 张（生产只读彩色格，这一列是\"为什么不能量 384\"的记录）")
  if (badRing > 0) kotlin.system.exitProcess(1)
}

private fun raster(img: java.awt.image.BufferedImage, w: Int, h: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val sy = (y.toLong() * img.height / h).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / w).toInt().coerceAtMost(img.width - 1)
    px[y * w + x] = s0[sy * img.width + sx]
  }
  return px
}
