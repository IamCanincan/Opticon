package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import javax.imageio.ImageIO

/**
 * 普查（只读不改）：**「同一块源色被给了两个答案」** 有多普遍 —— 就是 [MonochromeMask] 里
 * `flatVote`（`票=` 那一列）要治的那个病。
 *
 * 起因：易班左上角同一支源色 `(108,177,249)` 在 y64–70 / y88–104 是 **0**、在 y71–87 却是
 * **192**，于是青带左沿多出一块凸起。而 yiban 的 `票=0抹/0抬/0块` —— `flatVote` 没开火。
 *
 * 判据（只在成品上量，不碰模型）：把源色按 8 一档量化，对每个颜色统计
 *   `ink`（交付 out ≥ 128）与 `bg`（out < 128）的格数；两边都 ≥ [MIN_SIDE] 的，
 * 就是"这个颜色被给了两个答案"。报张数、以及"含糊墨格"总量。
 *
 * 用法：`sh run.sh harness.BumpScan <图标目录>`
 */
object BumpScan {
  private const val MIN_SIDE = 60      // 两侧都要有这么些格才算"真给了两个答案"
  private const val Q = 8              // 源色量化档

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "rec_model")
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name }
    val cls = MonochromeMask::class.java
    val instance = cls.getField("INSTANCE").get(null)
    val poolF: Field = cls.getDeclaredField("pool").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pool = poolF.get(instance) as ThreadLocal<Any>

    var icons = 0; var hitIcons = 0; var totalAmbig = 0L
    println("name\ttwoAnswerColors\tambigInkCells\tmaxAmbig\tvoteLine")
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val edge = img.width
      val px = IntArray(edge * edge)
      img.getRGB(0, 0, edge, edge, px, 0, edge)
      val r = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      icons++

      val ink = HashMap<Int, Int>()
      val bg = HashMap<Int, Int>()
      for (i in 0 until edge * edge) {
        if (((px[i] ushr 24) and 0xff) < 255) continue
        val key = ((((px[i] shr 16) and 0xff) / Q) shl 10) or
          ((((px[i] shr 8) and 0xff) / Q) shl 5) or ((px[i] and 0xff) / Q)
        if (((mask[i] ushr 24) and 0xff) >= 128) ink[key] = (ink[key] ?: 0) + 1
        else bg[key] = (bg[key] ?: 0) + 1
      }
      var colors = 0; var ambig = 0L; var maxAmbig = 0
      for ((k, v) in ink) {
        val b = bg[k] ?: 0
        if (v >= MIN_SIDE && b >= MIN_SIDE) { colors++; ambig += v; if (v > maxAmbig) maxAmbig = v }
      }
      totalAmbig += ambig
      if (colors > 0) hitIcons++
      val vote = Regex("票=\\S+").find(r.report)?.value ?: "-"
      println("${f.nameWithoutExtension}\t$colors\t$ambig\t$maxAmbig\t$vote")
    }
    println("# 目录 ${dir.name}：$icons 张，出现「同色两个答案」的 $hitIcons 张，含糊墨格合计 $totalAmbig")
  }
}
