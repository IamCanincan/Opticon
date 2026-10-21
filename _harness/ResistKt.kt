package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **Malody 那圈淡灰能不能靠"让盘沿相邻相似色生长"吃掉 —— 这把刀做不做得出来，只取决于一个数。**
 *
 * 从**模型自己判成底的那些格**（`Scratch.plate`，生产那本账，不是我按 L* 猜的）出发做 minimax 扩张：
 *   R(格) = 所有从盘走到这里的路径里，"沿途最大的一步 ΔE" 的最小值。
 * 淡渍那一族要能被吃到（R 小），主体内部必须吃不到（R 大）。
 * 逐档报「吃到 T 为止：吃掉多少渍格 / 同时吃掉多少主体格」——**只要这两列同一档一起跳，就没有 T**。
 *
 * ⚠ 上一版拿"最暗 1%"当种子，落点在角落那块深色纹理上，跨不过去就把"没有 T"报成了结论 ——
 *   那是仪器自己造的墙。这次种子用生产自己的判词。
 *
 * 用法：`harness.ResistKt <png…>`
 */
fun main(args: Array<String>) {
  val fld = MonochromeMask::class.java.getDeclaredField("pool")
  fld.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val pool = fld.get(MonochromeMask) as ThreadLocal<Any>
  for (path in args.filter { !it.startsWith("--") }) {
    val f = File(path)
    if (!f.exists()) { println("跳过（不存在）：$path"); continue }
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height; val n = w * h
    val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h)
    val out = r.mask ?: run { println("${f.name}: 没有掩码"); continue }
    val s = pool.get()
    val plate = (field(s, "plate") as ByteArray).clone()
    val labL = field(s, "labL") as ShortArray
    val labA = field(s, "labA") as ShortArray
    val labB = field(s, "labB") as ShortArray
    fun de(i: Int, j: Int): Int {
      val dl = (labL[i] - labL[j]) / 64.0; val da = (labA[i] - labA[j]) / 64.0; val db = (labB[i] - labB[j]) / 64.0
      return Math.ceil(Math.sqrt(dl * dl + da * da + db * db)).toInt().coerceAtMost(127)
    }
    // 分组：渍 = 模型没判成底、但交出去的墨很淡；主体 = 交出浓墨的
    val wash = ArrayList<Int>(); val subj = ArrayList<Int>()
    val seed = ArrayList<Int>()
    for (i in 0 until n) {
      if (Color.alpha(px[i]) != 255) continue
      if (plate[i] != 0.toByte()) { seed.add(i); continue }
      val ink = out[i] ushr 24
      if (ink in 1..95) wash.add(i) else if (ink >= 160) subj.add(i)
    }
    val R = IntArray(n) { Int.MAX_VALUE }
    // Dial 式桶队列：代价是 0..127 的整数，按桶递增处理，每格最多出队一次（SPFA 会反复重排，不是能上机的形状）
    val buckets = Array(129) { ArrayList<Int>() }
    val t0 = System.nanoTime()
    for (i in seed) { R[i] = 0; buckets[0].add(i) }
    var pops = 0
    for (c0 in 0..128) {
      val list = buckets[c0]
      var k = 0
      while (k < list.size) {
        val c = list[k++]; pops++
        val cx = c % w; val cy = c / w
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val nx = cx + dx; val ny = cy + dy
          if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
          val j = ny * w + nx
          val cand = maxOf(R[c], de(c, j))
          if (cand < R[j]) { R[j] = cand; buckets[cand].add(j) }
        }
      }
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("  扩张耗时 ${ms}ms（${w}x$h=${n} 格，出队 $pops 次，桶 ${buckets.size} 档）")
    println("=== ${f.name} ${w}x$h  种子(模型判成底的格)=${seed.size}  渍(1..95 墨)=${wash.size}  主体(>=160 墨)=${subj.size}")
    fun pct(list: List<Int>, p: Double): Int {
      if (list.isEmpty()) return -1
      val v = list.map { R[it] }.sorted()
      return v[(v.size * p).toInt().coerceAtMost(v.size - 1)]
    }
    println("  R 分位  渍: p50=${pct(wash, .5)} p90=${pct(wash, .9)} p99=${pct(wash, .99)}   主体: p50=${pct(subj, .5)} p10=${pct(subj, .1)} p01=${pct(subj, .01)}")
    println("  %-6s %-14s %-14s %-10s".format("T", "吃到的渍", "吃到的主体", "渍回收率"))
    // 悬崖 = 第一格"浓墨主体"被碰到的那一档；自适应 T 只能取它下面
    var cliff = -1
    for (t in 0..128) { if (subj.any { R[it] <= t }) { cliff = t; break } }
    for (t in intArrayOf(2, 4, 6, 8, 10, 12, 16, 20, 24, 32)) {
      val cw = wash.count { R[it] <= t }; val cs = subj.count { R[it] <= t }
      println("  %-6d %-14d %-14d %-8.1f%%".format(t, cw, cs, if (wash.isEmpty()) 0.0 else cw * 100.0 / wash.size))
    }
    val safeT = cliff - 1
    val safeW = if (safeT >= 0) wash.count { R[it] <= safeT } else 0
    println("  悬崖=$cliff  ⇒ 自适应 T=$safeT 会吃掉 渍 $safeW/${wash.size}" +
        "（${if (wash.isEmpty()) 0.0 else safeW * 100.0 / wash.size}%）" +
        if (cliff <= 2) "  **窗口为空：这张自动退回不生长**" else "")
    if (args.contains("--grow")) {
      val g = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
      for (i in 0 until n) {
        val grown = safeT >= 2 && R[i] <= safeT && plate[i] == 0.toByte()
        val ink = out[i] ushr 24
        val c = when {
          grown -> (0xff shl 24) or (0xE0 shl 16) or (0x20 shl 8) or 0x20      // 红 = 自适应生长会吃掉的格
          plate[i] != 0.toByte() -> (0xff shl 24) or (0xC0 shl 16) or (0xC0 shl 8) or 0xC0  // 浅灰 = 模型已判成底
          ink >= 160 -> (0xff shl 24)                                          // 白 = 现在交的浓墨（主体，不许碰）
          else -> { val v = 255 - ink * 2; (0xff shl 24) or (v shl 16) or (v shl 8) or v }  // 深灰 = 现在的淡墨
        }
        g.setRGB(i % w, i / w, c)
      }
      val o = File("D:/tmp/grow"); o.mkdirs()
      ImageIO.write(g, "png", File(o, f.name))
    }
  }
}

private object Color {
  fun alpha(p: Int) = (p ushr 24) and 0xff
}

private fun field(o: Any, name: String): Any {
  val c = o.javaClass.getDeclaredField(name)
  c.isAccessible = true
  return c.get(o)
}
