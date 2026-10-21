package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage

/**
 * **正对照：亲手画一张"两块平色、中间一条硬色边"的图标，看「对档」那一刀必不必须开火。**
 *
 * 为什么要有它：`cand/FlatPair.kt` 在 62 张真图上**一张都没开火**。"0 张"既可能是真答案
 * （真图里就没有两块又大又平又硬边的色块），也可能是仪器瞎了（判据串里任何一环写错都会一路归零）。
 * 本仓为这件事记过不止一次：**只有正对照能抓系统性失明**。所以这里造一张按定义必须过的：
 *   左半 RGB(230,60,60)、右半 RGB(60,60,230) —— **同一明度、色差极大、各自绝对平、边界绝对硬**，
 *   底下再压一条深灰横杠当"底"，让它像一张真图标（有底、有两块墨）。
 * 顺带把漏斗每一环的计数打出来：几族过"面积"、几族过"平色"、几条边过"硬色边"。
 *
 * 用法：`harness.PairProbeKt [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  for (case in listOf("两块平色同明度" to Pair(0xE63C3C, 0x3C3CE6), "两块平色不同明度" to Pair(0xE6E6E6, 0x3C3C3C))) {
    val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(0x8C, 0x8C, 0x8C); g.fillRect(0, 0, edge, edge)          // 底
    g.color = java.awt.Color(case.second.first shr 16 and 0xff, case.second.first shr 8 and 0xff, case.second.first and 0xff)
    g.fillRect(edge / 8, edge / 8, edge * 3 / 8, edge * 3 / 4)                          // 左块
    g.color = java.awt.Color(case.second.second shr 16 and 0xff, case.second.second shr 8 and 0xff, case.second.second and 0xff)
    g.fillRect(edge / 2, edge / 8, edge * 3 / 8, edge * 3 / 4)                          // 右块
    g.dispose()
    val px = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, px, 0, edge)
    val r = MonochromeMask.build(px, edge, edge)
    val rep = r.report
    fun col(re: String) = (Regex(re).find(rep)?.groupValues?.get(1)) ?: "-"
    println("${case.first}  →  对档=${col("对档=([0-9]+/[0-9]+)")}  分档=${col("分档=([0-9/(),]+)")}  " +
      "框内=${col("框内=([0-9.]+)")}  字均净=${col("字均净=(-?[0-9]+)")}  淡=${col("淡=([0-9.]+)")}  调=${col("调=([01])")}")
  }
}
