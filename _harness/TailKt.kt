package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **零点那条尾巴是哪几格贡献的**（只量不改）。
 *
 * `零` = 底像素散度的 p97，而它是从**每个像素**头上扣的。上一轮量死："按格放开零点"会把
 * `残盘` 从 0 抬到一万 —— 所以不能动那个数，只能问**为什么它这么大**。
 *
 * 一个具体的怀疑：`fitField` 给每格取的中位色是**逐通道**取的（L / a / b 各取各的中位），
 * 半底半墨的格子里三个通道各自落在双峰的两侧，拼出来是一个**图上根本不存在的颜色**。
 * 于是那块底离"自己的参照"很远 —— 散的不是底，是那格的参照。
 * 如果 p97 那条尾巴集中在**底占一半上下**的格子，这个解释就成立；集中在底占绝大多数的格子，
 * 那它就是真的噪声，这条到此为止。
 *
 * 每张报：底像素散度按**这一格的底占多少**分桶，看 p97 以上那 3% 落在哪几桶。
 * 用法：`harness.TailKtKt <目录|png> … [--top=3]`
 */
fun main(args: Array<String>) {
  val topPct = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 3
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name } else listOf(f)
  }
  // 分桶：这一格里底占 0-25 / 25-50 / 50-75 / 75-95 / 95-100%
  val names = arrayOf("<25%", "25-50", "50-75", "75-95", "95+")
  println("%-38s %5s %6s  %s".format("图标", "零", "底格", "p97 以上那 3% 落在：  " + names.joinToString(" ") { it.padStart(9) }))
  val grand = LongArray(5); val grandAll = LongArray(5)
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val rep = out?.report ?: continue
    if (!rep.contains("零=")) continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val dist = fld(s, "dist") as ShortArray
    val ref = fld(s, "ref") as IntArray
    val g = fld(s, "grid") as Int
    val cells = g * g
    if (ref.size != w * h) continue
    val cGr = IntArray(cells); val cTot = IntArray(cells)
    for (i in 0 until w * h) {
      val k = if (ref[i] in 0 until cells) ref[i] else continue
      cTot[k]++
      if (plate[i] != 0.toByte()) cGr[k]++
    }
    val z = Regex("零=(\\S+)").find(rep)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    // 底像素按"它所在格的底占比"分桶，同时记下散度
    val buckets = Array(5) { ArrayList<Int>() }
    for (i in 0 until w * h) {
      if (plate[i] == 0.toByte()) continue
      val k = if (ref[i] in 0 until cells) ref[i] else continue
      if (cTot[k] == 0) continue
      val share = cGr[k] * 100 / cTot[k]
      val b = when {
        share < 25 -> 0; share < 50 -> 1; share < 75 -> 2; share < 95 -> 3; else -> 4
      }
      buckets[b] += (dist[i].toInt() and 0xffff)
    }
    val nAll = buckets.sumOf { it.size }
    if (nAll == 0) continue
    val all = IntArray(nAll)
    run { var o = 0; for (b in buckets) { for (v in b) all[o++] = v }; all.sort() }
    val cut = all[(all.size * (100 - topPct) / 100).coerceAtMost(all.size - 1)]
    val tail = IntArray(5); val tot = IntArray(5)
    for (b in 0 until 5) {
      tot[b] = buckets[b].size
      for (v in buckets[b]) if (v >= cut) tail[b]++
    }
    val nTail = tail.sum().coerceAtLeast(1)
    println("%-38s %5d %6d  %s".format(
      f.name.removeSuffix(".png").take(38), z, cells,
      (0 until 5).joinToString(" ") { b ->
        "${"%.1f".format(tail[b] * 100.0 / nTail)}%".padStart(9)
      }))
    for (b in 0 until 5) { grand[b] += tail[b].toLong(); grandAll[b] += tot[b].toLong() }
  }
  val nt = grand.sum().coerceAtLeast(1L)
  println("\n全语料合计：尾巴落在 " + (0 until 5).joinToString(" / ") { "${"%.1f".format(grand[it] * 100.0 / nt)}%" })
  println("      分母：  " + (0 until 5).joinToString(" / ") { "${"%.1f".format(grandAll[it] * 100.0 / grandAll.sum().coerceAtLeast(1))}%" })
  println("  读法：如果尾巴的占比**明显高于**该桶在分母里的占比，那条 p97 就是这些格子撑起来的。")
}

private fun fld(o: Any, name: String): Any {
  for (c in generateSequence(o::class.java) { it.superclass }) {
    for (f in c.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  }
  throw NoSuchFieldException(name)
}
