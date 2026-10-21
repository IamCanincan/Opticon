package harness

import java.io.File
import javax.imageio.ImageIO

/**
 * 把桌面的极性规则**当成一条曲线**读出来，而不是一个相关系数：
 * 按 src 亮度分桶，看每一桶里桌面给的遮罩墨量。
 * 一路看下来是递增 → 「亮=墨」；递减 → 「暗=墨」；先平后跳 → 它按 min/max 归一化，
 * 于是"图里最亮的那块"必然被画成实心 —— 这正是很多图标整张反掉的来路。
 */
fun main() {
  val dir = File("cap3")
  val srcs = dir.listFiles { f -> f.name.endsWith("-src.png") }!!.sortedBy { it.name }
  val masks = dir.listFiles { f -> f.name.endsWith("-mask.png") } ?: emptyArray()
  val seen = HashSet<String>()
  for (src in srcs) {
    val key = src.name.substringAfter("_").removeSuffix("-src.png")
    if (!seen.add(key)) continue
    val maskFile = masks.firstOrNull { it.name.substringAfter("_").removeSuffix("-mask.png") == key } ?: continue
    val (sl, sc, _) = sample(ImageIO.read(src))
    val (_, mk, _) = sample(ImageIO.read(maskFile))
    val nb = 8
    val sum = DoubleArray(nb); val cnt = IntArray(nb)
    for (i in 0 until GRID * GRID) {
      if (sc[i] < 0.8) continue                        // 只看 src 实心的地方
      val b = (sl[i] / 256.0 * nb).toInt().coerceIn(0, nb - 1)
      sum[b] += mk[i] * 255; cnt[b]++
    }
    val sb = StringBuilder()
    for (b in 0 until nb) sb.append("%.0f ".format(if (cnt[b] > 0) sum[b] / cnt[b] else -1.0))
    println("%-44s 遮罩墨量(按src亮度暗→亮): %s| 格数: %s"
      .format(key.take(42), sb, (0 until nb).joinToString(" ") { if (cnt[it] > 0) cnt[it].toString() else "-" }))
  }
}
