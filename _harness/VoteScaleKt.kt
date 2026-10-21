package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 只读诊断：**「同一源色被给了两个答案」这个病，有多少是量化档宽造出来的。**
 *
 * 起因：`BumpScan` 用 `Q=8`（源色 8 一档）量到 rec_model 里 18 张命中，其中 15 张 `票=0`。
 * 但 `VoteScan` 把那 15 张里"参照支"的 7 张逐块拆开看，**每一块都是 100% 单侧**
 * （`过尺寸门=6 / 过两侧门=0`）—— 也就是说 `flatVote` 的票箱里根本没有"两侧"可分。
 * ⇒ 两种可能，必须分开：① 病是真的，只是发生在**块与块之间**（颜色差 ≥ΔE2 被拆开）；
 *   ② 病是**量化档宽**造的（8 一档把 ΔRGB≤7 的两个不同源色并成"同一个颜色"）。
 *
 * 本台子只做一件事：把同一批图在 `Q=8/4/2/1` 四把尺下各量一遍。
 * `Q=1` 是**逐字节同色** —— 生产 `flatVote` 的分块口径（真 ΔE<2）比它松一点点，
 * 但量级上足够回答"这个病是不是尺造出来的"。读数单调下降 ⇒ 是档宽造的；
 * 不降 ⇒ 病是真的、发生在块之间。
 *
 * ⚠ 只看成品掩码，不碰模型；不改任何生产常量。
 * 用法：`sh run.sh harness.VoteScaleKt rec_model`
 */
object VoteScaleKt {
  private const val MIN_SIDE = 60

  private fun key(c: Int, q: Int): Int {
    val r = (c shr 16) and 0xff
    val g = (c shr 8) and 0xff
    val b = c and 0xff
    return if (q <= 1) (r shl 16) or (g shl 8) or b
    else (((r / q) shl 12) or ((g / q) shl 6) or (b / q))
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "rec_model")
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name }
    val qs = intArrayOf(8, 4, 2, 1)

    println("name\t" + qs.joinToString("\t") { "Q$it" })
    val hitAt = IntArray(qs.size)
    val cellsAt = LongArray(qs.size)
    var icons = 0
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val edge = img.width
      val px = IntArray(edge * edge)
      img.getRGB(0, 0, edge, edge, px, 0, edge)
      val r = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      icons++
      val out = ArrayList<String>()
      for ((qi, q) in qs.withIndex()) {
        val ink = HashMap<Int, Int>()
        val bg = HashMap<Int, Int>()
        for (i in 0 until edge * edge) {
          if (((px[i] ushr 24) and 0xff) < 255) continue
          val k = key(px[i], q)
          if (((mask[i] ushr 24) and 0xff) >= 128) ink[k] = (ink[k] ?: 0) + 1
          else bg[k] = (bg[k] ?: 0) + 1
        }
        var colors = 0; var ambig = 0L
        for ((k, v) in ink) {
          val b = bg[k] ?: 0
          if (v >= MIN_SIDE && b >= MIN_SIDE) { colors++; ambig += v }
        }
        if (colors > 0) hitAt[qi]++
        cellsAt[qi] += ambig
        out.add("$colors/$ambig")
      }
      if (out.any { !it.startsWith("0/") }) println("${f.nameWithoutExtension}\t" + out.joinToString("\t"))
    }
    println("# 张数（$icons 张）：" + qs.indices.joinToString(" ") { "Q${qs[it]}=${hitAt[it]}" })
    println("# 含糊墨格合计：" + qs.indices.joinToString(" ") { "Q${qs[it]}=${cellsAt[it]}" })
  }
}
