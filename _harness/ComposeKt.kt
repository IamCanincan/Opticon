package harness

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * 把同一个图标在几个变体下的预览**拼成一张**，行=变体、列=图标。
 *
 * 为什么要有这个：「层次多一点 / 颜色割裂」是观感，直方图只能证明"质量挪了位置"，
 * 不能证明挪对了。判的人得在同一张图上并排看到三个版本，才分得出哪一列是真的层次 ——
 * 分开看三张图，每次都会觉得最后看的那张更好。
 *
 * 用法：`ComposeKt 输出.png 标签1=目录1 标签2=目录2 … -- 图1 图2 …`
 */
fun main(args: Array<String>) {
  val out = File(args[0])
  val sep = args.indexOf("--")
  val rows = args.copyOfRange(1, sep).map {
    val i = it.indexOf('=')
    it.substring(0, i) to File(it.substring(i + 1))
  }
  val names = args.copyOfRange(sep + 1, args.size)
  val tiles = ArrayList<ArrayList<BufferedImage>>()
  var w = 0; var h = 0
  for ((_, dir) in rows) {
    val line = ArrayList<BufferedImage>()
    for (n in names) {
      val f = File(dir, n)
      val img = if (f.exists()) ImageIO.read(f) else BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      val m = mid(img)
      line.add(m); w = maxOf(w, m.width); h = maxOf(h, m.height)
    }
    tiles.add(line)
  }
  val lab = 34
  val canvas = BufferedImage(w * tiles[0].size, (h + lab) * tiles.size, BufferedImage.TYPE_INT_RGB)
  val g = canvas.createGraphics()
  g.color = java.awt.Color.WHITE
  g.fillRect(0, 0, canvas.width, canvas.height)
  g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 26)
  for ((r, line) in tiles.withIndex()) {
    for ((c, img) in line.withIndex()) {
      g.drawImage(img, c * w, r * (h + lab) + lab, null)
    }
    g.color = java.awt.Color.BLACK
    g.drawString(rows[r].first, 8, r * (h + lab) + 26)
  }
  g.dispose()
  ImageIO.write(canvas, "png", out)
  println("写出 ${out.absolutePath}  ${canvas.width}x${canvas.height}  ${tiles.size} 行 × ${names.size} 列")
}

/** 预览条是 源 | 浅底 | 深底 三格：只取中间那一格（浅底最能看出层次）。 */
private fun mid(img: BufferedImage): BufferedImage {
  if (img.width < img.height * 2) return img
  val e = img.height
  val x = (img.width - e) / 2
  return img.getSubimage(x, 0, e, img.height)
}
