package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **边界能不能画出来 —— 先出图，再谈实现。**
 *
 * 主人两次报易班「青色带子与熊头粘在一起」。10-12 我把这条裁定成硬边界（单通道无处安放），
 * 但"裁定"没有回答一个问题：**是放不下，还是我们放得太挤？**
 * 青带源色 L*=86.0、熊身 L*=85.7 —— **亮度几乎相同，差别全在色度**（a=−51.6 vs b=+42.5）。
 * 现在两块交付 135 与 166，差 31/255 ≈ 12%，肉眼读成一片。
 *
 * 这一台把同一张图按几种"故意拉开"的分配各画一遍，让他指认哪一种算分开了 ——
 * **他指的那一档就是要写的规则，不是我猜的**。最后一格是两块都涂实 = 边界必然消失，当反面对照。
 *
 * 用法：`BoundaryMockKt <出图> <源图> [--edge=300]`
 */
fun main(args: Array<String>) {
  val out = File(args[0])
  val src = File(args[1])
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt()) ?: 300
  val img = ImageIO.read(src)
  val canvas = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g0 = canvas.createGraphics()
  g0.color = Color(0, 0, 0, 0); g0.fillRect(0, 0, edge, edge)
  g0.drawImage(img, 0, 0, edge, edge, null); g0.dispose()
  val px = IntArray(edge * edge); canvas.getRGB(0, 0, edge, edge, px, 0, edge)
  val mask = MonochromeMask.build(px, edge, edge).mask ?: error("没有掩码")

  // 按 RGB 分这两块：青 = 绿和蓝都比红高出一截；黄 = 红和绿都比蓝高出一截。
  // （不用 Lab 是因为这台驱动在模型外，`labChannel` 是 private；而这两块在 RGB 里就分得开。）
  fun kind(p: Int): Int {
    val r = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val b = p and 0xff
    return when {
      gg > r + 20 && b > r + 20 -> 1        // 青带
      r > b + 40 && gg > b + 40 -> 2        // 黄熊
      else -> 0
    }
  }
  val k = IntArray(edge * edge) { if (((px[it] ushr 24) and 0xff) >= 128) kind(px[it]) else 0 }
  var nTeal = 0; var nBear = 0
  for (i in 0 until edge * edge) { if (k[i] == 1) nTeal++; if (k[i] == 2) nBear++ }
  println("青带 $nTeal 格、熊身 $nBear 格（实心像素里）")

  // 变体：(青带 α, 熊身 α)。null = 保持模型现在的交付。
  val variants = listOf<Pair<Int?, Int?>>(
    null to null, 90 to 230, 255 to 90, 90 to 255, 255 to 255
  )
  val labels = listOf("V0 现状 135/166", "V1 青90 熊230", "V2 青255 熊90", "V3 青90 熊255", "V4 都255(反面对照)")
  val cell = edge + 12
  val board = BufferedImage(cell * variants.size + 12, edge + 46, BufferedImage.TYPE_INT_RGB)
  val g = board.createGraphics()
  g.color = Color(0x33, 0x33, 0x33); g.fillRect(0, 0, board.width, board.height)
  g.font = Font("SansSerif", Font.BOLD, 15)
  for ((vi, v) in variants.withIndex()) {
    val (ta, ba) = v
    val one = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val gg = one.createGraphics()
    gg.color = Color(0xED, 0xED, 0xED); gg.fillRect(0, 0, edge, edge)      // 浅盘
    for (i in 0 until edge * edge) {
      val src0 = mask[i]
      val a = when {
        ta != null && k[i] == 1 -> ta
        ba != null && k[i] == 2 -> ba
        else -> (src0 ushr 24) and 0xff
      }
      if (a > 0) one.setRGB(i % edge, i / edge, (a shl 24) or 0x00101010)
    }
    gg.dispose()
    g.drawImage(one, 12 + vi * cell, 30, null)
    g.color = Color.WHITE
    g.drawString(labels[vi], 12 + vi * cell, 22)
  }
  g.dispose()
  ImageIO.write(board, "png", out)
  println("写出 ${out.path}  ${board.width}x${board.height}")
}
