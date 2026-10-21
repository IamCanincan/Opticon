package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **单色剪影**：一种颜色 + 全透明背景，画成几种常见形状，看模型交出什么。
 *
 * 起因：`CompatKt` 那条 GAP 写着"1px 横杠 → 交出空掩码"，像是取整边界。
 * 但把横杠加厚到 64px（384 的画布里占 1/6 高、80% 宽）**还是空掩码** ——
 * 所以那一格的真名不是"1px"，是「**整张图标只有一种颜色、其余全透明**」。
 * 而单色剪影是现实里非常常见的图标形状（一个黑圆、一个环、一个字形的镂空板）。
 * 这个台子就是去数：**这些形状里，哪些还活着。**
 *
 * 用法：`harness.SiloKtKt <输出目录> [--edge=384]`
 */
fun main(args: Array<String>) {
  val dir = File(args[0]).apply { mkdirs() }
  val e = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val c = e / 2.0
  fun put(name: String, f: (Int, Int) -> Boolean) {
    val img = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until e) for (x in 0 until e) if (f(x, y)) img.setRGB(x, y, java.awt.Color(255,255,255).rgb)
    ImageIO.write(img, "png", File(dir, "silo_${name}_${e}-src.png"))
  }
  val r = e * 0.38
  put("圆盘") { x, y -> sqrt(((x - c) * (x - c) + (y - c) * (y - c)).toDouble()) <= r }
  put("环") { x, y -> val d = sqrt(((x - c) * (x - c) + (y - c) * (y - c)).toDouble())
    d in r..(r + e * 0.07) }
  put("方板") { x, y -> x in (e * 0.15).toInt()..(e * 0.85).toInt() && y in (e * 0.15).toInt()..(e * 0.85).toInt() }
  put("横条") { x, y -> y in (e / 2 - e / 12)..(e / 2 + e / 12) && x in (e * 0.1).toInt()..(e * 0.9).toInt() }
  put("细横条") { x, y -> y in (e / 2 - 1)..(e / 2 + 1) && x in (e * 0.1).toInt()..(e * 0.9).toInt() }
  put("十字") { x, y -> (x in (e * 0.44).toInt()..(e * 0.56).toInt() && y in (e * 0.2).toInt()..(e * 0.8).toInt()) ||
    (y in (e * 0.44).toInt()..(e * 0.56).toInt() && x in (e * 0.2).toInt()..(e * 0.8).toInt()) }
  put("四角点") { x, y -> listOf(0.28, 0.72).any { ax -> listOf(0.28, 0.72).any { ay ->
    sqrt(((x - e * ax) * (x - e * ax) + (y - e * ay) * (y - e * ay)).toDouble()) <= e * 0.06 } } }
  println("写了 6 个单色剪影 → ${dir.path}（edge=$e）")
}
