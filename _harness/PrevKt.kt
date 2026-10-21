package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 把**交付掩码**导成 PNG 给主人看（本会话看不了图，主人能看）。
 * 画法：白底 + 墨（alpha 越满越黑）—— 这样"形状"一眼能认，不会被透明底糊住。
 *
 * 用法：`sh run.sh harness.PrevKt <图或目录> <输出目录>`
 * 配合 A/B 两份字节码各跑一次，就能拿到"改前 / 改后"两张对着看。
 */
object PrevKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val src = File(args[0])
    val outDir = File(args.getOrElse(1) { "_prev" })
    outDir.mkdirs()
    val fs = if (src.isDirectory) src.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }
      ?: return else listOf(src)
    for (f in fs) {
      val img = ImageIO.read(f) ?: continue
      val w = img.width; val h = img.height
      val px = IntArray(w * h)
      img.getRGB(0, 0, w, h, px, 0, w)
      val m = MonochromeMask.build(px, w, h).mask ?: continue
      val o = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
      for (y in 0 until h) for (x in 0 until w) {
        val a = (m[y * w + x] ushr 24) and 0xff
        val g = 255 - a
        o.setRGB(x, y, (g shl 16) or (g shl 8) or g)
      }
      val dst = File(outDir, f.nameWithoutExtension + ".png")
      ImageIO.write(o, "png", dst)
      println("${dst.absolutePath}")
    }
  }
}
