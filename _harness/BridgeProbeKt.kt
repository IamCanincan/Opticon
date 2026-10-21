package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * **只读打分台：出口 A「切完留一圈连接组织」到底救不救得回来**（主人 2026-10-18「继续」）。
 *
 * 背景：满幅支切背景（`a10a658`）把底交 0 之后，13 张会**碎**（`PlateCutProbeKt` 量的，云闪付
 * `cc 5→21`、`top 1.00→0.70`），生产那条几何守卫（`ce7bd43`）因此把它们退回不切 —— 于是屏上是
 * 一整片灰糊（云闪付 `淡 0.58`）。出口 A 的赌法是：**底归零，但贴着剩下墨的那一圈底压成淡墨**，
 * 用"看得见的连接"换回形状。⚠ 这笔交易付的就是**淡**，所以不能只看 cc/top，四个数一起看。
 *
 * 三版并排量（同一份交付掩码，只改"底格怎么交"）：
 *   V0 = 现在（不切）
 *   V1 = 底格全交 0（= 被守卫退回前的那一版）
 *   V2 = 底格交 0，但**离剩下墨 ≤K 格**的那些交 α=A（扫 K×A）
 * 每版报：墨占画布 / 块数 cc / 最大块占墨比 top / **淡膜%**（0<α<128 占画布）。
 *
 * 底 = 主人那句判据的口径：**与图标不透明轮廓相连的那一整片同色/渐变**（环上主导族，ΔE≤40 放宽）。
 * ⚠ 比生产的 `plate` 粗，所以这是**上界打分**，不是成品。
 *
 * 用法：`sh run.sh harness.BridgeProbeKt [目录] [K1,K2,..] [α1,α2,..]`
 */
object BridgeProbeKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0)?.takeIf { !it.startsWith("--") } ?: "rec_model")
    val ks = (args.getOrNull(1) ?: "2,4,6").split(",").map { it.toInt() }
    val als = (args.getOrNull(2) ?: "48,96").split(",").map { it.toInt() }
    val only = args.firstOrNull { it.startsWith("--only=") }?.removePrefix("--only=")?.split(",") ?: emptyList()
    val fs = dir.listFiles { f: File -> f.name.endsWith(".png") && !f.name.endsWith("_modelbg.png") }
      ?.sortedBy { it.name } ?: return
    println(String.format(Locale.ROOT, "%-46s %-14s %6s %5s %5s %6s",
      "name", "版本", "墨%", "cc", "top", "淡膜%"))
    for (f in fs) {
      if (only.isNotEmpty() && only.none { f.name.contains(it) }) continue
      val img = ImageIO.read(f) ?: continue
      val w = img.width; val h = img.height; val n = w * h
      val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
      val mask = MonochromeMask.build(px, w, h).mask ?: continue
      val op = BooleanArray(n) { (px[it] ushr 24) >= 128 }
      val rim = ArrayList<Int>()
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!op[i]) continue
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
          !op[i - 1] || !op[i + 1] || !op[i - w] || !op[i + w]) rim.add(i)
      }
      if (rim.size < 64) continue
      val fl = ArrayList<Int>(); val fa = ArrayList<Int>(); val fb = ArrayList<Int>(); val fn = ArrayList<Int>()
      for (i in rim) {
        val l = MonochromeMask.labChannel(px[i], 0); val a = MonochromeMask.labChannel(px[i], 1)
        val b = MonochromeMask.labChannel(px[i], 2)
        var k = -1
        for (j in fl.indices) if (MonochromeMask.deltaE(l, a, b, fl[j], fa[j], fb[j]) <= 18 shl 6) { k = j; break }
        if (k < 0) { fl.add(l); fa.add(a); fb.add(b); fn.add(1) } else fn[k] = fn[k] + 1
      }
      var best = 0; for (j in fn.indices) if (fn[j] > fn[best]) best = j
      val plate = BooleanArray(n)
      for (i in 0 until n) if (op[i]) {
        plate[i] = MonochromeMask.deltaE(MonochromeMask.labChannel(px[i], 0), MonochromeMask.labChannel(px[i], 1),
          MonochromeMask.labChannel(px[i], 2), fl[best], fa[best], fb[best]) <= 40 shl 6
      }
      fun row(tag: String, m: IntArray) {
        var ink = 0; var soft = 0
        for (i in 0 until n) { val a = m[i] ushr 24; if (a > 0) { ink++; if (a < 128) soft++ } }
        val (cc, top) = shape(m, w, h, n, ink)
        println(String.format(Locale.ROOT, "%-46s %-14s %5.1f%% %5d %5.2f %5.1f%%",
          if (tag.startsWith("V0")) f.nameWithoutExtension else "", tag,
          ink * 100.0 / n, cc, top, soft * 100.0 / n))
      }
      row("V0 不切", mask)
      val v1 = mask.copyOf(); for (i in 0 until n) if (plate[i]) v1[i] = 0
      row("V1 全切", v1)
      for (K in ks) for (A in als) {
        val v2 = v1.copyOf()
        // 离剩下墨 ≤K 的底格 → 交 α=A（只补不抢：已经有墨的格不动）
        var frontier: IntArray
        val seen = BooleanArray(n)
        run { val s0 = ArrayList<Int>()
          for (i in 0 until n) if ((v1[i] ushr 24) > 0) { s0.add(i); seen[i] = true }
          frontier = s0.toIntArray() }
        for (step in 1..K) {
          val next = ArrayList<Int>()
          for (c in frontier) {
            val cy = c / w; val cx = c - cy * w
            for (d in intArrayOf(-1, 1, -w, w)) {
              val j = c + d
              if (j < 0 || j >= n) continue
              if (d == -1 && cx == 0) continue; if (d == 1 && cx == w - 1) continue
              if (seen[j]) continue
              seen[j] = true
              if (plate[j] && (v1[j] ushr 24) == 0) { v2[j] = A shl 24 or 0x00FFFFFF; next.add(j) }
              else if (op[j]) next.add(j)
            }
          }
          frontier = next.toIntArray()
        }
        row("V2 K=$K α=$A", v2)
      }
      println()
    }
  }

  private fun shape(m: IntArray, w: Int, h: Int, n: Int, ink: Int): Pair<Int, Double> {
    val seen = BooleanArray(n); val q = IntArray(n)
    var cc = 0; var biggest = 0
    for (s in 0 until n) {
      if ((m[s] ushr 24) == 0 || seen[s]) continue
      cc++; var head = 0; var tail = 0; q[tail++] = s; seen[s] = true; var a = 0
      while (head < tail) {
        val c = q[head++]; a++
        val cy = c / w; val cx = c - cy * w
        fun push(j: Int) { if ((m[j] ushr 24) > 0 && !seen[j]) { seen[j] = true; q[tail++] = j } }
        if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
      }
      if (a > biggest) biggest = a
    }
    return Pair(cc, if (ink == 0) 0.0 else biggest.toDouble() / ink)
  }
}
