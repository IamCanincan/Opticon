package harness

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **裁一块、放大几倍** —— 判"这两块有没有粘在一起"时，整张图缩在屏幕上看不见那道缝。
 * 用法：`ZoomKt <出图> <入图> --rect=x,y,w,h --scale=N`
 */
fun main(args: Array<String>) {
  val out = File(args[0]); val src = File(args[1])
  val r = args.firstOrNull { it.startsWith("--rect=") }?.substringAfter("=")?.split(",")?.map { it.toInt() }
    ?: error("--rect=x,y,w,h 必填")
  val sc = (args.firstOrNull { it.startsWith("--scale=") }?.substringAfter("=")?.toInt()) ?: 3
  val img = ImageIO.read(src) ?: error("读不动 ${src.path}")
  val (x, y, w, h) = r
  val crop = img.getSubimage(x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1),
    w.coerceAtMost(img.width - x), h.coerceAtMost(img.height - y))
  val big = BufferedImage(crop.width * sc, crop.height * sc, BufferedImage.TYPE_INT_RGB)
  val g = big.createGraphics()
  // **最近邻**：判的是"缝在不在"，一插值就把 1px 的缝糊成灰带，那正是我要看的假象来源。
  g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
  g.drawImage(crop, 0, 0, big.width, big.height, null)
  g.dispose()
  ImageIO.write(big, "png", out)
  println("写出 ${out.path}  ${big.width}x${big.height}（裁 ${crop.width}x${crop.height} ×${sc}）")
}
