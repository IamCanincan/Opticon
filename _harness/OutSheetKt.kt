package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **看设备真正交出去的那一张**：`_undump.cjs` 从 logcat 重组回来的 `<x>.src.png`（模型吃的）
 * 与 `<x>.out.png`（我们给的遮罩，alpha 就是墨），并排画成「源图 ｜ 压在白天盘上」。
 *
 * 为什么要它：`MonoDump` 原来只发输入不发输出，于是"屏幕上到底是什么"只能亮屏截屏 ——
 * 手机在休眠时什么都不知道（2026-10-11 主人报 translate 半边黑，修完只能看到 `框内` 一个数）。
 * 上色口径与 `MaskBench --shot` 白天那一联一致：盘 `#EDEDED`、墨 `#1F1F1F`。
 *
 * 用法：`OutSheetKt <目录> [--out=出图目录]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "D:/tmp/undump2")
  val outDir = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "D:/tmp/outsheet")
  outDir.mkdirs()
  val shots = ArrayList<Pair<String, BufferedImage>>()
  for (o in dir.listFiles { f -> f.name.endsWith(".out.png") }!!.sortedBy { it.name }) {
    val base = o.name.removeSuffix(".out.png")
    val mask = runCatching { ImageIO.read(o) }.getOrNull() ?: continue
    val src = File(dir, "$base.src.png").let { if (it.isFile) runCatching { ImageIO.read(it) }.getOrNull() else null }
    val t = 116
    val strip = BufferedImage(t * 2 + 6, t, BufferedImage.TYPE_INT_RGB)
    val g = strip.createGraphics()
    if (src != null) g.drawImage(src, 0, 0, t, t, null)
    g.drawImage(onPlate(mask, t), t + 6, 0, t, t, null)
    g.dispose()
    ImageIO.write(strip, "png", File(outDir, "$base.png"))
    shots.add(Pair(base.take(40), strip))
  }
  val cols = 4
  shots.chunked(cols).forEachIndexed { r, part ->
    val w = cols * 122; val h = part.size * 122
    val grid = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = grid.createGraphics()
    g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
    part.forEachIndexed { i, p -> g.drawImage(p.second, (i % cols) * 122, i * 122, null) }
    g.dispose()
    ImageIO.write(grid, "png", File(outDir, "_grid" + "%02d".format(r) + ".png"))
  }
  println("拼好 ${shots.size} 张到 $outDir（每张：左=设备吃进去的 右=我们交出去的墨压在白天盘上）")
}

private fun onPlate(mask: BufferedImage, t: Int): BufferedImage {
  val img = BufferedImage(t, t, BufferedImage.TYPE_INT_RGB)
  for (y in 0 until t) for (x in 0 until t) {
    val sy = y * mask.height / t; val sx = x * mask.width / t
    val a = (mask.getRGB(sx, sy) ushr 24) and 0xff
    val c = (0xED * (255 - a) + 0x1F * a) / 255
    img.setRGB(x, y, 0xff000000.toInt() or (c shl 16) or (c shl 8) or c)
  }
  return img
}
