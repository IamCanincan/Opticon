package harness

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 把**选支结论不同的那几张**拼成一张能看的对照（每台子给的是 `源｜满幅那一支｜参照那一支`，
 * 所以一张条本身就同时装着两版的成品 —— 要判的是"这张该交哪一边"）。
 *
 * 用法：`DiffSheetKt <条目录> <输出目录> 名字子串1,子串2,…`
 */
fun main(args: Array<String>) {
  val src = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
  val needles = args[2].split(',').filter { it.isNotBlank() }
  val picked = ArrayList<Pair<String, BufferedImage>>()
  for (n in needles) {
    val f = src.listFiles { x -> x.name.contains(n) }?.sortedBy { it.name }?.firstOrNull()
    if (f == null) { println("!! 找不到「$n」的条 —— 台子上没跑出这张"); continue }
    val img = ImageIO.read(f) ?: continue
    picked.add(f.nameWithoutExtension to img)
  }
  val per = 4
  picked.chunked(per).forEachIndexed { k, part ->
    val w = part[0].second.width; val h = part[0].second.height
    val sheet = BufferedImage(w, (h + 26) * part.size, BufferedImage.TYPE_INT_RGB)
    val g: Graphics2D = sheet.createGraphics()
    g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
    g.font = Font("SansSerif", Font.PLAIN, 15)
    part.forEachIndexed { i, p ->
      val y = i * (h + 26)
      g.color = Color.BLACK; g.drawString(p.first, 6, y + 17)
      g.drawImage(p.second, 0, y + 22, null)
      g.color = Color.GRAY; g.drawRect(0, y + 21, w, h)
    }
    g.dispose()
    val o = File(outDir, "_diff%02d.png".format(k))
    ImageIO.write(sheet, "png", o); println("写出 ${o.absolutePath}（${part.size} 张 × 三条）")
  }
  println("每格的三条 = 源图｜白天（浅盘深墨）｜夜间（深盘浅墨）—— 与 `MaskBench --shot` 同一张三联。")
}
