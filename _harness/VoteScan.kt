package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import javax.imageio.ImageIO

/**
 * **查 `flatVote` 为什么不开火**（只读不改）：把它那套分块原样重放一遍，逐块报
 * `n / 三侧格数 / 卡在哪道门`。
 *
 * `flatVote` 的四道门（数值照抄源码）：
 *  ① 分块：4 连通、与**块起点**同色（真 ΔE < 2）；
 *  ② `n ≥ 画布 3‰`；
 *  ③ 三侧（底 a≤4 / 地板带 4<a<80 / 实墨 a≥80）里**至少两侧各占 ≥10%**；
 *  ④ 之后才决定 target（膜 / 底 / 带 / 实墨）。
 *
 * 用法：`sh run.sh harness.VoteScan <图标目录或单张 png> [最多列几块]`
 */
object VoteScan {
  private const val VOTE_DE = 2 shl 6
  private const val VOTE_MIN_PERMILLE = 3
  private const val VOTE_SHARE = 10
  private const val VOTE_PROP_HI = 40
  private const val VOTE_BAND_INK = 25

  @JvmStatic
  fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: "bench/_yb1/com.yiban.app_yiban_icon_8e21581a9e86.png")
    val limit = args.getOrNull(1)?.toIntOrNull() ?: 12
    val files = if (target.isDirectory) target.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name } else listOf(target)

    val cls = MonochromeMask::class.java
    val instance = cls.getField("INSTANCE").get(null)
    val poolF: Field = cls.getDeclaredField("pool").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pool = poolF.get(instance) as ThreadLocal<Any>

    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val w = img.width; val h = img.height
      val px = IntArray(w * h)
      img.getRGB(0, 0, w, h, px, 0, w)
      val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      val s = pool.get() ?: continue
      val labL = s.javaClass.getDeclaredField("labL").also { it.isAccessible = true }.get(s) as ShortArray
      val labA = s.javaClass.getDeclaredField("labA").also { it.isAccessible = true }.get(s) as ShortArray
      val labB = s.javaClass.getDeclaredField("labB").also { it.isAccessible = true }.get(s) as ShortArray

      val total = w * h
      val minN = total * VOTE_MIN_PERMILLE / 1000
      val seen = ByteArray(total)
      val q = IntArray(total)
      var nBlocks = 0; var passSize = 0; var passShare = 0
      val rows = ArrayList<String>()
      for (start in 0 until total) {
        if ((px[start] ushr 24) != 255 || seen[start] != 0.toByte()) continue
        var head = 0; var tail = 0
        q[tail++] = start; seen[start] = 1
        val sl = labL[start].toInt() and 0xffff; val sa = labA[start].toInt() and 0xffff
        val sb = labB[start].toInt() and 0xffff
        while (head < tail) {
          val c = q[head++]
          val cy = c / w; val cx = c - cy * w
          for (j in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
            if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)) {
            if (j < 0 || (px[j] ushr 24) != 255 || seen[j] != 0.toByte()) continue
            if (MonochromeMask.deltaE(labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff,
                sl, sa, sb) >= VOTE_DE) continue
            seen[j] = 1; q[tail++] = j
          }
        }
        val n = tail
        nBlocks++
        var n0 = 0; var nf = 0; var ns = 0; var sumF = 0L
        for (u in 0 until n) {
          val a = (mask[q[u]] ushr 24)
          if (a <= 4) n0++ else if (a < 80) { nf++; sumF += a } else ns++
        }
        val sides = intArrayOf(n0, nf, ns)
        val okSize = n >= minN
        val okShare = sides.count { it * 100 >= n * VOTE_SHARE } >= 2
        if (okSize) passSize++
        if (okSize && okShare) passShare++
        if (okSize) {
          val bandMean = if (nf > 0) (sumF / nf).toInt() else 0
          rows.add("   n=$n 底=$n0(${n0 * 100 / n}%) 带=$nf(${nf * 100 / n}%,均值$bandMean) 墨=$ns(${ns * 100 / n}%)" +
            " share=${if (okShare) "过" else "卡"} 例=(${start % w},${start / w})")
        }
      }
      println("=== ${f.nameWithoutExtension}")
      println("  report: ${Regex("票=\\S+ 带占=\\S+ 底占=\\S+ H=\\S+").find(r.report)?.value ?: r.report.take(80)}")
      println("  块总数=$nBlocks  过尺寸门=$passSize  过两侧门=$passShare  （尺寸门 n≥$minN）")
      for (row in rows.sortedByDescending { it.length }.take(limit)) println(row)
    }
  }
}
