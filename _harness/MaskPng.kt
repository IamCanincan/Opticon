package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 把掩码**原样**导成 PNG（白 + alpha，不做任何上色），供 Python 精确分析连通块/孔洞。
 *  用法: `MaskPngKt <src.png> <out.png>` */
fun main(args: Array<String>) {
  val img = ImageIO.read(File(args[0]))
  val w = img.width; val h = img.height
  val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
  val r = MonochromeMask.build(px, w, h)
  val m = r.mask ?: run { println("没有掩码"); return }
  val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  out.setRGB(0, 0, w, h, m, 0, w)
  ImageIO.write(out, "png", File(args[1]))
  println("${args[1]}  ${r.report}")
}
