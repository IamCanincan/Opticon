package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import javax.imageio.ImageIO

/**
 * **「压倒多数」这条规则有没有自然空档**（只读不改）。
 *
 * 背景：v3/v4 走的是「给少数侧降门槛」，两次都被别的图打脸（farsee 整张重排 /
 * autonavi 抹掉一条细带）。方向反了吗？换一个看法 ——
 *
 *   v3/v4 问的是「少数侧够不够大」；这里问的是「**多数侧够不够压倒**」。
 *   一块平色里 99.7% 的格都判成了同一个答案，剩下 0.3% 判成另一个 —— 这时
 *   少数侧多大根本不重要，10% 那道门槛拦的正是这种块（易班：底 35058/35172=99.68%，
 *   墨只有 114 格，被 `VOTE_SHARE=10` 卡死，疙瘩留到今天）。
 *
 * 本量具只做一件事：把**当前过尺寸门、但过不了两侧门**的那些块全列出来，
 * 报它们的多数侧占比与多数侧是哪一侧 —— 看 99% 附近有没有空档。有空档才配定数；
 * 连续分布就说明这条判据不成立，别硬钉。
 *
 * 用法：`sh run.sh harness.VoteScan2Kt <图标目录>`
 */
object VoteScan2 {
  private const val VOTE_DE = 2 shl 6
  private const val VOTE_MIN_PERMILLE = 3
  private const val VOTE_SHARE = 10

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "rec_model")
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview")
    }.sortedBy { it.name }

    val cls = MonochromeMask::class.java
    val instance = cls.getField("INSTANCE").get(null)
    val poolF: Field = cls.getDeclaredField("pool").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pool = poolF.get(instance) as ThreadLocal<Any>

    // 多数侧占比 → （块数，其中底是多数，其中墨是多数，其中带是多数）
    val hist = HashMap<Int, IntArray>()       // 按 1% 分档
    val examples = ArrayList<Triple<Int, String, Int>>()   // (占比%, 说明, 少数侧格数)
    var totalBlocked = 0

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
        if (n < minN) continue                       // ①尺寸门：不过的不算
        var n0 = 0; var nf = 0; var ns = 0
        for (u in 0 until n) {
          val a = (mask[q[u]] ushr 24)
          if (a <= 4) n0++ else if (a < 80) nf++ else ns++
        }
        val sides = intArrayOf(n0, nf, ns)
        val okShare = sides.count { it * 100 >= n * VOTE_SHARE } >= 2
        if (okShare) continue                        // ②两侧门：已经过的不是本量具的对象
        totalBlocked++
        val maxSide = sides.max()!!
        val pct = maxSide * 100 / n
        val which = if (maxSide == n0) 0 else if (maxSide == nf) 1 else 2
        val slot = hist.getOrPut(pct) { IntArray(3) }
        slot[which]++
        val minor = n - maxSide
        examples.add(Triple(pct, "%-52s n=%-6d 底=%-6d 带=%-5d 墨=%-5d 起=(${start % w},${start / w})"
          .format(f.nameWithoutExtension, n, n0, nf, ns), minor))
      }
    }

    println("过尺寸门但过不了两侧门的块：共 $totalBlocked 个")
    println()
    println("多数侧占比分布（只数这些被卡住的块）：")
    println("  占比   块数   底是多数  带是多数  墨是多数")
    for (pct in hist.keys.sorted()) {
      val v = hist[pct]!!
      println("  %3d%%   %4d    %5d     %5d     %5d".format(pct, v.sum(), v[0], v[1], v[2]))
    }
    println()
    println("≥95% 的那些（这就是新规则会新纳入的候选）：")
    examples.filter { it.first >= 95 }.sortedByDescending { it.first }.forEach {
      println("  ${it.first}% 少数侧=${it.third} 格  ${it.second}")
    }
  }
}
