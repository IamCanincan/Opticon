package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **把一块区域放大到能数格子**：左边是源图，右边是交付的墨（同一块、同一倍率）。
 * 主人说"globalspeed 右上角有莫名其妙的缺口"—— 这种话只能对着放大图判，整张缩略图看不出
 * 是"少了一块"还是"淡了一块"。
 *
 * 右半画的是**实际交出去的 alpha**（灰底上压黑墨，和桌面同一套合成），
 * 再叠两层标记：**红** = 源里实心、我们交 0（整格丢了）；**黄** = 源 alpha 255 而交付 1..127（丢了大半）。
 *
 * 用法：`harness.CropKtKt <src.png> <x> <y> <w> <h> <放大倍数> <out.png> [--edge=384]`
 */
fun main(args: Array<String>) {
  val f = File(args[0])
  val x0 = args[1].toInt(); val y0 = args[2].toInt(); val cw = args[3].toInt(); val ch = args[4].toInt()
  val k = args[5].toInt(); val out = File(args[6])
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val img = ImageIO.read(f)
  val sw = img.width; val sh = img.height
  val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
    val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
    px[y * edge + x] = s0[sy * sw + sx]
  }
  val m = MonochromeMask.build(px, edge, edge).mask ?: error("没有掩码")
  val v = BufferedImage(cw * k * 2 + 8, ch * k, BufferedImage.TYPE_INT_RGB)
  var lost = 0; var half = 0
  for (cy in 0 until ch) for (cx in 0 until cw) {
    val gx = x0 + cx; val gy = y0 + cy
    if (gx < 0 || gy < 0 || gx >= edge || gy >= edge) continue
    val i = gy * edge + gx
    val src = px[i]; val a = m[i] ushr 24
    // 左：源图（透明处铺灰）
    val sa = src ushr 24
    val sr = (src shr 16 and 0xff); val sg = (src shr 8 and 0xff); val sb = (src and 0xff)
    val blend = if (sa >= 250) (sr shl 16 or sg shl 8 or sb)
    else { val t = sa / 255.0; (((sr * t + 200 * (1 - t)).toInt()) shl 16) or
      (((sg * t + 200 * (1 - t)).toInt()) shl 8) or ((sb * t + 200 * (1 - t)).toInt()) }
    for (dy in 0 until k) for (dx in 0 until k) v.setRGB(cx * k + dx, cy * k + dy, blend)
    // 右：交付的墨压在浅灰上
    val base = 0xEDEDED
    val ink = 0x1F1F1F
    val t = a / 255.0
    val rr = ((ink shr 16 and 0xff) * t + (base shr 16 and 0xff) * (1 - t)).toInt()
    val gg = ((ink shr 8 and 0xff) * t + (base shr 8 and 0xff) * (1 - t)).toInt()
    val bb = ((ink and 0xff) * t + (base and 0xff) * (1 - t)).toInt()
    var col = (rr shl 16) or (gg shl 8) or bb
    val flag = if (sa >= 250 && a == 0) { lost++; 0xE03A2F }
    else if (sa >= 250 && a in 1..127) { half++; 0xFFC531 } else 0
    if (flag != 0) col = flag
    for (dy in 0 until k) for (dx in 0 until k) v.setRGB(cw * k + 8 + cx * k + dx, cy * k + dy, col)
  }
  for (dy in 0 until ch * k) for (dx in 0 until 8) v.setRGB(cw * k + dx, dy, 0x444444)
  ImageIO.write(v, "png", out)
  println("${out.name}  区域($x0,$y0 ${cw}x$ch)@${k}x  源实心而交 0 = $lost 格   交付 1~127 = $half 格")
}
