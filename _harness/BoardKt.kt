package harness

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **点名对图台**：把一个目录里的图**按原尺寸**平铺成一张，每格左上角写它的编号（文件名去扩展名）。
 *
 * 为什么另起一台：`ComposeKt` 会按最大格缩放/裁切，判"这条缝该不该留"时**缝本身被缩没了**；
 * `MaskPngKt` 写的是透明底白墨，压在白画布上看不见。这两条都不是"图没画对"，是**看图的工具不对** ——
 * 而"点名 → 主人标 → 我写尺"这条路上，看图工具就是量具本身。
 *
 * 用法：`BoardKt <输出.png> <目录> [--cols=N] [--gap=N] [--title=文字] [--scale=N]`
 *  · `--scale=N`（默认 1＝按原尺寸，与历史逐字节一致）：>1 时整张板按倍率放大并用**双线性插值**渲染，
 *    只为把二进制掩码的楼梯边插柔和供眼睛判断形状；**不改掩码本身**（二值不变量 out≈fill 判据台还吃）。
 * ⚠ 编号用 ASCII（文件名），中文标题在无头环境里可能缺字。
 */
fun main(args: Array<String>) {
  val out = File(args[0])
  val dir = File(args[1])
  val cols = (args.firstOrNull { it.startsWith("--cols=") }?.substringAfter("="))?.toIntOrNull() ?: 5
  val gap = (args.firstOrNull { it.startsWith("--gap=") }?.substringAfter("="))?.toIntOrNull() ?: 10
  // 放大倍率：默认 1 = 按原尺寸（与历史逐字节一致）。>1 时整张板按倍率放大、双线性插值渲染 ——
  // 只平滑**二值掩码的楼梯边**供眼睛判断形状，不碰掩码本身（out≈fill 反查「塞淡色」的判据台还吃二值）。
  val scale = (args.firstOrNull { it.startsWith("--scale=") }?.substringAfter("="))?.toIntOrNull()?.coerceAtLeast(1) ?: 1
  val title = args.firstOrNull { it.startsWith("--title=") }?.substringAfter("=") ?: ""
  val files = (dir.listFiles() ?: emptyArray()).filter { it.extension == "png" }
    .sortedBy { it.name }
  if (files.isEmpty()) { println("!! $dir 里没有 png"); return }
  val imgs = files.map { ImageIO.read(it) ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB) }
  val gapS = gap * scale
  val cw = imgs.maxOf { it.width } * scale
  val ch = imgs.maxOf { it.height } * scale
  val lab = 18 * scale
  val rows = (imgs.size + cols - 1) / cols
  val head = if (title.isEmpty()) 0 else 26 * scale
  val canvas = BufferedImage(cols * (cw + gapS) + gapS, head + rows * (ch + lab + gapS) + gapS,
    BufferedImage.TYPE_INT_RGB)
  val g = canvas.createGraphics()
  if (scale > 1) {
    // 只在放大时才改渲染 hint：把二进制掩码的台阶插成柔和过渡；scale=1 走不到这条路，历史板子逐字节不变
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
  }
  g.color = Color(0x33, 0x33, 0x33)
  g.fillRect(0, 0, canvas.width, canvas.height)
  g.font = Font("SansSerif", Font.BOLD, 14 * scale)
  if (title.isNotEmpty()) { g.color = Color.WHITE; g.drawString(title, gapS, 18 * scale) }
  for ((i, img) in imgs.withIndex()) {
    val cx = gapS + (i % cols) * (cw + gapS)
    val cy = head + gapS + (i / cols) * (ch + lab + gapS)
    g.color = Color.WHITE
    // 白底垫在图标下面：源图是透明底，直接贴到深色画布上会看不见它自己的浅色部分
    g.fillRect(cx, cy, img.width * scale, img.height * scale)
    g.drawImage(img, cx, cy, img.width * scale, img.height * scale, null)
    g.color = Color(0xCC, 0xCC, 0xCC)
    g.drawString(files[i].nameWithoutExtension, cx + 2 * scale, cy + img.height * scale + 14 * scale)
  }
  g.dispose()
  ImageIO.write(canvas, "png", out)
  println("写出 ${out.path}  ${canvas.width}x${canvas.height}  格 ${imgs.size} 张（${cw}x$ch） cols=$cols")
}
