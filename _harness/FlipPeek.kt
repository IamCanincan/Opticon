package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 把每一对 (src, 桌面给的遮罩) 铺成三格：原图 / 不翻 / 翻。
 * 极性这种事光看数字会自欺，遮罩按主题色画出来才看得见。
 * 底色用浅灰，模拟桌面那颗 themed 图标的气泡。
 */
fun main() {
  val dir = File("cap3")
  val out = File("flip"); out.mkdirs()
  val srcs = dir.listFiles { f -> f.name.endsWith("-src.png") }!!.sortedBy { it.name }
  val masks = dir.listFiles { f -> f.name.endsWith("-mask.png") } ?: emptyArray()
  val seen = HashSet<String>()
  val edge = 224
  val tint = 0xFF3B4048.toInt()          // 一个中性的"主题色"
  for (src in srcs) {
    val key = src.name.substringAfter("_").removeSuffix("-src.png")
    if (!seen.add(key)) continue
    val maskFile = masks.firstOrNull { it.name.substringAfter("_").removeSuffix("-mask.png") == key }
    val a = ImageIO.read(src)
    val img = java.awt.image.BufferedImage(edge * 3 + 16, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(0xEDEEF0); g.fillRect(0, 0, img.width, img.height)
    g.dispose()
    blit(img, a, 0, edge)
    if (maskFile != null) {
      val m = ImageIO.read(maskFile)
      paintMask(img, m, edge + 8, edge, tint, false)
      paintMask(img, m, (edge + 8) * 2, edge, tint, true)
    }
    val name = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
    ImageIO.write(img, "png", File(out, "${name}.png"))
    println("写了 ${name}.png  遮罩=${maskFile != null}")
  }
}

fun blit(dst: java.awt.image.BufferedImage, src: java.awt.image.BufferedImage, ox: Int, w: Int) {
  val h = w * src.height / src.width
  val g = dst.createGraphics()
  g.drawImage(src, ox, 0, w, h, null); g.dispose()
}

fun paintMask(dst: java.awt.image.BufferedImage, m: java.awt.image.BufferedImage, ox: Int, w: Int, argb: Int, flip: Boolean) {
  val h = w * m.height / m.width
  val px = IntArray(m.width * m.height); m.getRGB(0, 0, m.width, m.height, px, 0, m.width)
  val out = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val sx = min(m.width - 1, x * m.width / w)
    val sy = min(m.height - 1, y * m.height / h)
    val p = px[sy * m.width + sx]
    var al = (p ushr 24) and 0xff
    if (flip) al = 255 - al
    // 遮罩本身是"黑 + alpha"，按主题色重染，再叠到灰底上
    val r = (argb ushr 16) and 0xff; val gg = (argb ushr 8) and 0xff; val b = argb and 0xff
    val t = al / 255.0
    val bg = dst.getRGB(ox + x, y)
    out[y * w + x] = (0xff shl 24) or
      ((r * t + ((bg ushr 16) and 0xff) * (1 - t)).toInt() shl 16) or
      ((gg * t + ((bg ushr 8) and 0xff) * (1 - t)).toInt() shl 8) or
      ((b * t + (bg and 0xff) * (1 - t)).toInt())
  }
  dst.setRGB(ox, 0, w, h, out, 0, w)
}
