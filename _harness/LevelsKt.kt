package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **同一块同色，被发了几个档？**（只量不改）
 *
 * 为什么要这一台。刚上屏的 `flatVote` 把浓度切成三档（`α≤4` / `<80` / `≥80`），
 * 所以"一个物体交成 143 / 218 / 240 一片灰渣"它**看成一档、根本不开火** —— 而全量扫
 * 113 张真机像素时，这类"一物多浓度"是候选池最大的一类（约 31 行的代理命中，见 README
 * 那张 A/B/C 表）。它是 `flatVote` 的直接延长线，且不碰那面写了六次的墙（不问哪块是底）。
 *
 * 量法：与 `flatVote` 同一套连通定义（4 邻、与块**起点**源色真 ΔE<2），但把交付浓度按
 * **16 一格**分箱，数有几个箱各占这块的 ≥10%。≥2 个箱 = 这块同色被发了几个答案。
 * 报：块大小、各箱的均值与占比、按多数箱统一要动多少格 / 多少墨。
 *
 * ⚠ 16 这一格宽是**模型自己的档位尺度**（`LEVEL_FLOOR` 那段：档每步至少让 30，
 *   但两档看得出差别要差 ~30）—— 所以这里只把 ≥16 的差当"两个答案"，比它小的差不算。
 *
 * 用法：`harness.LevelsKtKt <目录> [目录…] [--min=500] [--share=10] [--bin=16]`
 */
fun main(args: Array<String>) {
  val minCells = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=") ?: "500").toInt()
  val share = (args.firstOrNull { it.startsWith("--share=") }?.substringAfter("=") ?: "10").toInt()
  val bin = (args.firstOrNull { it.startsWith("--bin=") }?.substringAfter("=") ?: "16").toInt()
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val rows = ArrayList<Array<Comparable<*>>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val labL = pf(s, "labL") as ShortArray
    val labA = pf(s, "labA") as ShortArray
    val labB = pf(s, "labB") as ShortArray
    val total = w * h
    val solid = BooleanArray(total) { (px[it] ushr 24) == 255 }
    val al = IntArray(total) { m[it] ushr 24 }
    val seen = BooleanArray(total)
    val stack = IntArray(total)
    var delivered = 0
    for (i in 0 until total) delivered += al[i]
    for (start in 0 until total) {
      if (!solid[start] || seen[start] || al[start] < 12) continue
      val sl = labL[start].toInt() and 0xffff; val sa = labA[start].toInt() and 0xffff
      val sb = labB[start].toInt() and 0xffff
      var sp = 0; stack[sp++] = start; seen[start] = true
      val cells = ArrayList<Int>(1024)
      while (sp > 0) {
        val c = stack[--sp]; cells.add(c)
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
          if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)) {
          if (j < 0 || !solid[j] || seen[j] || al[j] < 12) continue
          if (deE(labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff,
                  sl, sa, sb) >= (2 shl 6)) continue
          seen[j] = true; stack[sp++] = j
        }
      }
      if (cells.size < minCells) continue
      // 按 bin 分箱
      val nb = 256 / bin + 1
      val cnt = IntArray(nb); val sum = LongArray(nb)
      for (c in cells) { val b = al[c] / bin; cnt[b]++; sum[b] += al[c] }
      val major = cnt.indices.filter { cnt[it] * 100 >= cells.size * share }
      if (major.size < 2) continue
      var topB = major[0]
      for (b in major) if (cnt[b] > cnt[topB]) topB = b
      val target = (sum[topB] / cnt[topB]).toInt()
      var move = 0; var inkDelta = 0L
      for (b in major) if (b != topB) { move += cnt[b]; inkDelta += Math.abs(sum[b] - cnt[b].toLong() * target) }
      rows += arrayOf(f.name.substringAfter("_").substringBefore("-src").take(38), cells.size, major.size,
        move, inkDelta, delivered)
    }
  }
  val uniq = rows.map { it[0] }.distinct().size
  println("候选块（同色连通 ≥$minCells 格、按 $bin 分箱后 ≥2 个箱各占 ≥$share%）：${rows.size} 块 / $uniq 个应用")
  println("按「要动的墨」排序前 14（`动格`=统一到多数档要改的格数，`占交付`=这些格改的墨占该图交付%）：")
  for (row in rows.sortedByDescending { it[4] as Long }.take(14))
    println("  %-38s 块=%-7d 档数=%d  动格=%-7d 动墨=%-9d 占交付=%5.2f%%"
      .format(row[0], row[1] as Int, row[2] as Int, row[3] as Int, row[4] as Long,
        (row[4] as Long) * 100.0 / maxOf((row[5] as Int), 1)))
}

/** 模型口径：输入 Lab ×64，输出真 ΔE ×64。 */
private fun deE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2).toLong(); val da = (a1 - a2).toLong(); val db = (b1 - b2).toLong()
  return Math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
}

private fun pf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
