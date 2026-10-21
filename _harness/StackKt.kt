package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 把 `MaskBench --shot` 出来的那张三栏对照图**按边长对齐再堆起来**，
 * 用来回答一类只能看图的问题："同一张图标在 96 与 384 上交出来的墨不一样，哪一侧是对的？"
 *
 * `--shot` 画的永远是 `edge×edge` 的方格，不同边长的产物直接并排会一个指甲大一个脸盆大，
 * 眼睛没法比。这里统一缩到 `--cell`（默认 192）再逐行堆，每行左边标上它原本的边长。
 *
 * 用法：`harness.StackKtKt <标签=目录> [<标签=目录> …] [--cell=192] [--out=bench/stack.png]`
 * 例：`StackKtKt 96=pdd_96 384=pdd_384`
 */
fun main(args: Array<String>) {
  val cell = args.firstOrNull { it.startsWith("--cell=") }?.substringAfter("=")?.toInt() ?: 192
  val out = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/stack.png").apply { parentFile?.mkdirs() }
  val groups = args.filter { it.contains("=") && !it.startsWith("--") }
    .map { it.substringBefore("=") to File(it.substringAfter("=")) }
    .filter { it.second.isDirectory }
  require(groups.isNotEmpty()) { "没有可堆的目录：用法 StackKtKt 96=dirA 384=dirB" }
  // 取所有目录里都存在的同名文件，保证每行是同一张图标
  val common = groups[0].second.listFiles()!!.filter { it.extension == "png" }.map { it.name }
    .toMutableList()
  for ((_, d) in groups.drop(1)) common.retainAll(d.listFiles()!!.filter { it.extension == "png" }.map { it.name })
  common.sort()
  val label = 76
  val gap = 6
  val out0 = BufferedImage(label + (cell + gap) * groups.size, (cell + gap) * common.size + gap, BufferedImage.TYPE_INT_RGB)
  val g = out0.createGraphics()
  g.color = java.awt.Color.WHITE
  g.fillRect(0, 0, out0.width, out0.height)
  g.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 13)
  for ((r, name) in common.withIndex()) {
    for ((c, gr) in groups.withIndex()) {
      val img = ImageIO.read(File(gr.second, name)) ?: continue
      val x = label + c * (cell + gap)
      val y = gap + r * (cell + gap)
      // 三栏原图（源|浅底|深底）横向等比塞进 cell 宽，高度按原比例
      val w = cell
      val hh = (img.height.toLong() * w / img.width).toInt().coerceAtMost(cell)
      g.drawImage(img, x, y + (cell - hh) / 2, w, hh, null)
      if (r == 0) { g.color = java.awt.Color.DARK_GRAY; g.drawString(gr.first, x + 2, y - 0) }
    }
    g.color = java.awt.Color.GRAY
    g.drawString(name.substringBefore("-src").take(9), 2, gap + r * (cell + gap) + cell / 2)
  }
  g.dispose()
  ImageIO.write(out0, "png", out)
  println("${out.path}  ${common.size} 行 × ${groups.size} 列（缩到 ${cell}px 对齐）")
}
