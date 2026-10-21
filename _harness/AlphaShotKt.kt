package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 把两份**同尺寸**的 `.alpha`（`DumpKt` 落的裸字节）并排画出来，第三格是**差值热区**：
 * 底图=后一版上色的灰阶，红=比前一版浓、蓝=比前一版淡。
 *
 * 为什么要它：`票=抹/抬` 只告诉我"动了多少格"，不告诉我**动在哪**。icbc 那张真机报抬 788，
 * 我按格一看全是 ≤32 的微调 —— 到底是"环边上的一圈毛"还是"盘上的一块糊"，只有画出来才说得清。
 *
 * 用法：`harness.AlphaShotKt <前.alpha> <后.alpha> <输出.png> [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val a = File(args[0]).readBytes()
  val b = File(args[1]).readBytes()
  val n = edge * edge
  require(a.size >= n && b.size >= n) { "alpha 字节数不够 edge×edge：${a.size}/${b.size} vs $n" }
  val out = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_ARGB)
  var up = 0
  var down = 0
  var maxAbs = 0
  for (y in 0 until edge) {
    for (x in 0 until edge) {
      val i = y * edge + x
      val old = a[i].toInt() and 0xFF
      val new = b[i].toInt() and 0xFF
      val d = new - old
      if (d > 0) up++ else if (d < 0) down++
      if (kotlin.math.abs(d) > maxAbs) maxAbs = kotlin.math.abs(d)
      // 1) 前一版：白底黑墨
      out.setRGB(x, y, 0xFF000000.toInt() or (0xFFFFFF - old * 0x010101))
      // 2) 后一版
      out.setRGB(edge + 8 + x, y, 0xFF000000.toInt() or (0xFFFFFF - new * 0x010101))
      // 3) 差值：底=后一版灰阶，叠 红(变浓)/蓝(变淡)，×8 增益才看得见 ≤32 的微调
      val g = 0xFFFFFF - new * 0x010101
      val c = when {
        d > 0 -> {
          val k = (kotlin.math.abs(d) * 8).coerceAtMost(255)
          (g and 0x00FFFF) or ((255 - k) shl 16)
        }
        d < 0 -> {
          val k = (kotlin.math.abs(d) * 8).coerceAtMost(255)
          (g and 0xFF00FF) or ((255 - k) shl 8)
        }
        else -> g
      }
      out.setRGB(edge * 2 + 16 + x, y, 0xFF000000.toInt() or c)
    }
  }
  val f = File(args[2]).apply { parentFile?.mkdirs() }
  ImageIO.write(out, "png", f)
  println("${f.path}  前|后|差(×8)   抬$up 抹$down 最大单格Δα=$maxAbs")
}
