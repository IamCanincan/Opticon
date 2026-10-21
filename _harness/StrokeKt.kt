package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **图标里那些"线条"（细笔画、描边、细杠）到底活下来没有**（只量不改）。
 *
 * 主人说"线条渲染有问题"。这一族判据台上早就有一条 GAP：**1px 横杠 → 整条丢（`ink=0`）**，
 * 但它是一条合成的性质，从没在真图标上数过"有多少张、丢在哪一步"。
 *
 * ⚠ **第一版按"宽度 ≤3px"找线条，跑出来 113 张里 0 张有线** —— 那不是"没有线条问题"，
 *   那是**判据在 384 这个尺寸上恒不成立**：设计稿里 1px 的线，铺到 384 已经是 8~16px。
 *   一个跑不起来的检查报"零"，和没跑一样（本仓第四次撞这条，见 `RampKt` 前三轮）。
 *   所以这里改成按**粗细分层**：对每个实心格算它到最近空洞的距离（离边距离 = 局部粗细的一半），
 *   然后按粗细分桶，数**每一桶交付了多少浓度、有多少整条被抹成 0**。
 *   这样"细的东西是不是系统性比粗的东西淡"就是一个能直接读出来的斜率，而不是一句观感。
 *
 * 跑法：`harness.StrokeKtKt [目录] [--edges=96,192,384]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  // 按"离最近空洞的距离 d"分桶：d=1 是 2~3px 宽的线，d=2 是 4~5px，…… 厚的地方 d 大。
  val BANDS = intArrayOf(1, 2, 3, 5, 9)              // 上界（含）：1 / 2 / 3-4 / 5-8 / 9+
  val NAMES = arrayOf("d=1(细线)", "d=2", "d=3~4", "d=5~8", "d≥9(厚块)")
  for (edge in edges) {
    val n = IntArray(BANDS.size); val sum = LongArray(BANDS.size)
    val zero = IntArray(BANDS.size); val faint = IntArray(BANDS.size)
    val slope = ArrayList<Int>()                     // 每张：厚区均浓 − 最细那桶均浓
    var icons = 0
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val sw = img.width; val sh = img.height
      val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
      val src = IntArray(edge * edge)
      for (y in 0 until edge) for (x in 0 until edge) {
        val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
        val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
        src[y * edge + x] = s0[sy * sw + sx]
      }
      val out = runCatching { MonochromeMask.build(src, edge, edge) }.getOrNull() ?: continue
      val mask = out.mask ?: continue
      // ⚠ **分母只能是"模型自己认作图形"的那些格**（`plate[i]==0`）。第一版按"所有实心格"分桶，
      //   结果 d≥9 那一桶 94% 的格其实是**底板**（源里实心、但本来就该抹成 0）—— 于是量出来
      //   "厚块比细线更淡"，一句废话。加了这道过滤，问的才是"**同一堆墨里**，细的比粗的淡多少"。
      val scr = pool.get() ?: continue
      val plate = fld(scr, "plate") as ByteArray
      // 几何：源里实心 = 形状（含底板）。**粗细要按形状算**，否则一条压在盘上的细笔画
      // 会因为"盘也算不进 solid"而被量成 1px 宽。
      val solid = BooleanArray(edge * edge) { (src[it] ushr 24) >= 250 }
      var solidN = 0
      for (v in solid) if (v) solidN++
      if (solidN < 256) continue
      // 多源 BFS：空洞（含画布外）距离 0，实心格到最近空洞的距离 = 局部粗细的一半。
      val d = IntArray(edge * edge) { if (solid[it]) 1 shl 20 else 0 }
      val q = IntArray(edge * edge); var qh = 0; var qt = 0
      for (i in 0 until edge * edge) if (d[i] == 0) q[qt++] = i
      while (qh < qt) {
        val i = q[qh++]; val x = i % edge; val y = i / edge; val nd = d[i] + 1
        if (x > 0 && nd < d[i - 1]) { d[i - 1] = nd; q[qt++] = i - 1 }
        if (x + 1 < edge && nd < d[i + 1]) { d[i + 1] = nd; q[qt++] = i + 1 }
        if (y > 0 && nd < d[i - edge]) { d[i - edge] = nd; q[qt++] = i - edge }
        if (y + 1 < edge && nd < d[i + edge]) { d[i + edge] = nd; q[qt++] = i + edge }
      }
      val bandSum = LongArray(BANDS.size); val bandN = IntArray(BANDS.size)
      val band0 = IntArray(BANDS.size); val bandF = IntArray(BANDS.size)
      for (i in 0 until edge * edge) {
        if (!solid[i] || plate[i] != 0.toByte()) continue     // 只数"图形"那一堆
        val b = bandOf(d[i], BANDS)
        if (b < 0) continue
        val a = mask[i] ushr 24
        bandN[b]++; bandSum[b] += a
        if (a == 0) band0[b]++
        if (a < 128) bandF[b]++
      }
      for (b in BANDS.indices) { n[b] += bandN[b]; sum[b] += bandSum[b]; zero[b] += band0[b]; faint[b] += bandF[b] }
      if (bandN[0] >= 64 && bandN[BANDS.size - 1] >= 256) {
        slope += (bandSum[BANDS.size - 1] / bandN[BANDS.size - 1]).toInt() -
          (bandSum[0] / bandN[0]).toInt()
        icons++
      }
    }
    println("=== ${files.size} 张 · edge=$edge ===")
    println("%-14s %9s %7s %7s %7s %7s".format("粗细带", "格数", "占实心", "均浓", "抹成0", "淡<128"))
    val tot = n.sum().coerceAtLeast(1)
    for (b in BANDS.indices)
      println("%-14s %9d %6.1f%% %7s %6d%% %6d%%".format(NAMES[b], n[b], n[b] * 100.0 / tot,
        if (n[b] == 0) "-" else (sum[b] / n[b]).toString(),
        if (n[b] == 0) 0 else zero[b] * 100 / n[b],
        if (n[b] == 0) 0 else faint[b] * 100 / n[b]))
    slope.sort()
    println("厚区比最细带实多少（$icons 张两头都有内容）：中位 ${if (slope.isEmpty()) "-" else slope[slope.size / 2]}   " +
      "≥40 的 ${slope.count { it >= 40 }} 张   ≥80 的 ${slope.count { it >= 80 }} 张")
    println()
  }
}

private fun bandOf(d: Int, bands: IntArray): Int {
  for (b in bands.indices) if (d <= bands[b]) return b
  return bands.size - 1
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
