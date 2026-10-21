package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 只读诊断：**「同一源色被给了两个答案」的那些张，到底是"门拦了"还是"根本没走到那一刀"。**
 *
 * 起因：`BumpScan` 量到 rec_model 里 18 张有"同色两答案"，其中 14 张日志是
 * `票=0抹/0抬/0块`。**先问顺序、再问门** —— 生产里那一行是
 * `val voteN = if (tonal == null) flatVote(...) else 0`，满幅插画那一支（`调=1`）
 * 压根不跑 `flatVote`；这种张的 `票=0` 不是"被门拦住"，是"这一刀没上场"。
 * 把两者混成一个数，就是本仓记过的"一个不跑的步骤不许报通过"的反面。
 *
 * ⚠ 它**不重演**生产的分块 —— 那是 `VoteScan.kt` 的活，本台子不造第二份镜像。
 *   它只把 `report` 里生产自己打的分支字段与 `BumpScan` 的两答案计数并排放。
 *
 * 用法：`sh run.sh harness.VoteWhyKt rec_model`
 */
object VoteWhyKt {
  private const val MIN_SIDE = 60
  private const val Q = 8

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "rec_model")
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name }

    var all = 0; var hit = 0
    var hitTonal = 0; var hitRef = 0
    var silentTonal = 0; var silentRef = 0
    println("name\tbranch\t色阶\tcolors\t票\t承载")
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val edge = img.width
      val px = IntArray(edge * edge)
      img.getRGB(0, 0, edge, edge, px, 0, edge)
      val r = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      all++

      val rep = r.report
      val tonal = rep.contains("调=1")
      val stretch = Regex("色阶=(\\S+)").find(rep)?.groupValues?.get(1) ?: "-"
      val vote = Regex("票=\\S+").find(rep)?.value ?: "-"
      val carrier = Regex("承载=\\S+").find(rep)?.value ?: "承载=-(未走这一支)"

      // 与 BumpScan 同一口径：源色 8 一档，两侧各 ≥60 格。
      val ink = HashMap<Int, Int>()
      val bg = HashMap<Int, Int>()
      for (i in 0 until edge * edge) {
        if (((px[i] ushr 24) and 0xff) < 255) continue
        val k = ((((px[i] shr 16) and 0xff) / Q) shl 10) or
          ((((px[i] shr 8) and 0xff) / Q) shl 5) or ((px[i] and 0xff) / Q)
        if (((mask[i] ushr 24) and 0xff) >= 128) ink[k] = (ink[k] ?: 0) + 1
        else bg[k] = (bg[k] ?: 0) + 1
      }
      var colors = 0
      for ((k, v) in ink) {
        val b = bg[k] ?: 0
        if (v >= MIN_SIDE && b >= MIN_SIDE) colors++
      }
      if (colors > 0) {
        hit++
        if (tonal) hitTonal++ else hitRef++
        println("${f.nameWithoutExtension}\t${if (tonal) "满幅调=1" else "参照"}\t$stretch\t$colors\t$vote\t$carrier")
      } else {
        if (tonal) silentTonal++ else silentRef++
      }
    }
    println("# 目录 ${dir.name}：$all 张，同色两答案 $hit 张（满幅调=1 支 $hitTonal / 参照支 $hitRef）")
    println("# 无此病的：满幅调=1 支 $silentTonal 张 / 参照支 $silentRef 张")
  }
}
