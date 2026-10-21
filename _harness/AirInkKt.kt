package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File

/**
 * **空气上墨账**：源那一格是全透明（α<8），交付却给了墨 —— 屏幕上就是图标外面那圈**灰方框**
 * （形状是内容外接矩形，因为那是空气被成批抬起来的地方）。
 *
 * 为什么单独一台：判据台的 `淡=` 与 `浓度[…]` 都只数"交了墨的格"，**不看源那格是不是空气** ——
 * 所以这一族在表里长得和"标记自己偏淡"一模一样。2026-10-09 逐张看 96 张真机图时，
 * xbox 那张外面那圈灰被 `NoSolidKt` 顺带量出来：现版本 `空气上墨=15308`。
 *
 * ## 两条路各数一遍（2026-10-21 加）
 * 这一台**以前**只走 3 参 `build(src, w, h)`，于是 `plateIsLayer` 恒 false —— 而真机 `rec_model`
 * 里有 **10/62 张** 那一位是 true，[`MonochromeMask` 的【地板】那一刀](MonochromeMask.kt) 就住在
 * `plateLayer` 为真的分支里。也就是说：**这台子从来没有量过那 10 张，而它守的正是那类改动。**
 * 2026-10-21 语义变异现量：把地板从 `× 源α` 之前挪到之后（= 历史上真出过的那版坏代码），
 * 老口径 `0 / 98` 照样绿 —— 它看不见。
 *
 * 两条路都留着，因为它们回答的不是同一个问题：
 *  - `装机` 传 `plateLayer` + `under`：与 [MaskBench]、与真机**同一套输入构造**（[cases]）。
 *    这条红了 = 屏幕上真有那圈灰。**只有这一条的数字可以对装机说话。**
 *  - `从严` 只传 `plateLayer`、不传 `under`：`盘刀` 没东西可擦，**擦得掉的墨在这里仍算墨**。
 *    以前那条"故意不传 under"的理由保留，只是它顺手把 `plateLayer` 也丢了 —— 那一位不该跟着丢。
 *    ⚠ **这一条是刻意造的装机不可能的组合**（真机上 `plateLayer=true` 必有背景层，也就必有 `under`）。
 *    它是一把**探针**，不是一条口径：它的数不许写进任何"装机会怎样"的结论里。
 *    （同一种形态写在 `SizeVarKt` 里就是病 —— 那边把 `plateIsLayer` 写死 false 却**当成装机口径**用。）
 *
 * 用法：`harness.AirInkKtKt <语料目录…> [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u", "rec_blank") }
  println("图标                                                支    全透明上墨     半透明边     最浓    占交付%")
  var fail = 0
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    // 语料**读一遍**、两条路各跑一遍：`cases` 是惰性的，走两次就是解码两次（一台 98 张 ≈ 50 秒）。
    val all = cases(dir, edge, inset = false, fgAll = false).toList()
    for (road in listOf("装机", "从严")) {
      var nBad = 0; var nAll = 0
      val rows = ArrayList<String>()
      for (c in all) {
        val r = if (road == "装机") MonochromeMask.build(c.src, edge, edge, c.plateLayer, c.under)
        else MonochromeMask.build(c.src, edge, edge, c.plateLayer, null)
        val m = r.mask ?: continue
        nAll++
        var air = 0; var halo = 0; var inked = 0; var top = 0
        for (i in c.src.indices) {
          val da = (m[i] ushr 24) and 0xff
          if (da >= 8) inked++
          val sa = (c.src[i] ushr 24) and 0xff
          // **两列分开数**：`sa==0` 是真正的空气（抬起来就是那圈灰方框）；`sa` 在 1~7 是抗锯齿边，
          // 把它算进闸口就是拿一条边当缺陷 —— 第一版写的 `<8` 混了这两类。
          if (sa == 0 && da >= 8) { air++; if (da > top) top = da }
          if (sa in 1..7 && da >= 8) halo++
        }
        if (air == 0) continue
        nBad++
        rows.add("%-46s %4s %8d %8d %6d %7d%%".format(
          c.file.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").take(44),
          if (r.report.contains("调=1")) "满幅" else "参照", air, halo, top,
          if (inked == 0) 0 else air * 100 / inked))
      }
      rows.forEach { println(it) }
      println("\n[$road] $nBad / $nAll 张在 **α=0 的格子**上交了墨 —— 屏幕上那圈灰方框就是它。")
      // 机器可读的一行：闸门不许只 grep 那句话（改了形就永远抓不到），也不许"这一路没印"当成过。
      println("空气上墨账[$road]=$nBad/$nAll")
      if (nAll == 0) { println("!! 这一路一张都没数到 —— 语料或输入构造坏了，不算过"); fail = 1 }
      if (nBad > 0) fail = 1
    }
  }
  println("（「半透明边」那一列只报数、不当闸：源 α 1~7 被抬上来是抗锯齿，不是空气。）")
  // **退出码就是账**：闸门不能只靠 grep 一句话（那句话改了形就永远抓不到）。
  if (fail > 0) kotlin.system.exitProcess(1)
}
