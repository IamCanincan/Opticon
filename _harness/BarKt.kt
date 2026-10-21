package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 造 `_harness/CompatKt` 里那一根 **1px 横杠** 的真图（3px 高、宽 10%~90%、其余全透明），
 * 目的是把它喂给**所有现成的探针**（MaskBench / LineDbg / CropKt…）—— 判据台里它是内存 fixture，
 * 落不了盘，所以那 4 条 GAP 一直只能看一行结论、看不到过程。
 *
 * 用法：`harness.BarKtKt <输出目录>`
 */
fun main(args: Array<String>) {
  val dir = File(args[0]).apply { mkdirs() }
  for (e in intArrayOf(96, 192, 384, 768)) {
    val thick = args.getOrNull(1)?.toInt() ?: 3
    val img = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
    val cy = e / 2
    val lo = (e * 0.10).toInt(); val hi = (e * 0.90).toInt()
    for (y in (cy - thick / 2)..(cy + thick / 2)) for (x in lo until hi) img.setRGB(x, y, 0xFFFFFFFF.toInt())
    ImageIO.write(img, "png", File(dir, "bar${thick}_${e}-src.png"))
  }
  println("写了 4 张 → ${dir.path}（3px 高的白横杠，其余 alpha=0）")
}
