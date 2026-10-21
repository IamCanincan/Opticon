package harness

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **改了哪几张，就看哪几张** —— 把两个目录里**同名**的成品逐张比像素，只把不一样的
 * 并排（左=前、右=后）拼成联表。
 *
 * 为什么不用整册拼图：一册 42 张里通常只有个位数真的变了，整册看等于在没变的东西上
 * 花眼睛；而"完全覆盖 + 风格统一"要判的恰恰是**变了的那几张是不是都变对了方向**。
 *
 * 用法：`harness.ABSheetKtKt <前目录> <后目录> [--cell=150] [--cols=6] [--out=bench/ab]`
 */
fun main(args: Array<String>) {
  val a = File(args[0]); val b = File(args[1])
  val cell = (args.firstOrNull { it.startsWith("--cell=") }?.substringAfter("="))?.toIntOrNull() ?: 150
  val cols = (args.firstOrNull { it.startsWith("--cols=") }?.substringAfter("="))?.toIntOrNull() ?: 6
  val outDir = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/ab")
    .apply { mkdirs() }
  val lab = 16
  val changed = ArrayList<Pair<String, BufferedImage>>()
  for (fa in a.listFiles { f -> f.extension.equals("png", true) }!!.sortedBy { it.name }) {
    val fb = File(b, fa.name)
    if (!fb.exists()) continue
    val ia = ImageIO.read(fa); val ib = ImageIO.read(fb)
    if (ia.width != ib.width || ia.height != ib.height) continue
    var diff = 0
    for (y in 0 until ia.height step 3) for (x in 0 until ia.width step 3)
      if (ia.getRGB(x, y) != ib.getRGB(x, y)) diff++
    if (diff == 0) continue
    val c = BufferedImage(cell * 2 + 6, cell + lab, BufferedImage.TYPE_INT_RGB)
    val g = c.createGraphics() as Graphics2D
    g.color = Color(0xEE, 0xEE, 0xEE); g.fillRect(0, 0, c.width, c.height)
    g.drawImage(ia, 0, 0, cell, cell, null)
    g.drawImage(ib, cell + 6, 0, cell, cell, null)
    g.color = Color(0x22, 0x22, 0x22)
    g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11)
    val nm = fa.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").substringBefore("_")
      .take(26) + " 差$diff"
    g.drawString(nm, 2, cell + lab - 4)
    g.color = Color(0xCC, 0x22, 0x22); g.drawLine(cell + 3, 0, cell + 3, cell)
    g.dispose()
    changed.add(fa.name to c)
  }
  println("变了 ${changed.size} 张（左=前，右=后）")
  if (changed.isEmpty()) return
  var sheet = 0
  for (i in changed.indices step cols * 5) {
    sheet++
    val part = changed.subList(i, minOf(i + cols * 5, changed.size))
    val rows = (part.size + cols - 1) / cols
    val big = BufferedImage(cols * (cell * 2 + 6) + 4, rows * (cell + lab) + 4, BufferedImage.TYPE_INT_RGB)
    val g = big.createGraphics() as Graphics2D
    g.color = Color.WHITE; g.fillRect(0, 0, big.width, big.height)
    part.forEachIndexed { k, p -> g.drawImage(p.second, (k % cols) * (cell * 2 + 6), (k / cols) * (cell + lab), null) }
    g.dispose()
    val out = File(outDir, "ab_%02d.png".format(sheet))
    ImageIO.write(big, "png", out)
    println("  ${out.path}  ${part.size} 张")
  }
}
