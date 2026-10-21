package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **一根笔画被切成几段浓度**（只量不改）。
 *
 * 现场（2026-09-30，主人点名 全球网测 / 易班）：全球网测那张是**一根弧**，亮的那半段是一条
 * 连续笔画；`cffac3d`（地板按源 ΔE 下沉）之后它从 `2/6(31,39,230,231,253,255)` 变成
 * `4/6(27,34,135,165,225,253)` —— 发档按**源色桶**分组，而这条弧是"一根笔画上的一段渐变"，
 * 渐变路过几个粗桶就被当成"图上有好几个部分"，每个部分被强行拉开 ≥30 → **沿着一根线出现台阶**。
 * 改前地板 200 把它们压在 230~255 那一格，反而看不出来。
 *
 * ⚠ 为什么不能用 `EraseKt` 那套几何判据：它是**满幅不透明**图标，源里一个透明像素都没有，
 *   "两侧都是墨、自己是 0"在它身上恒不成立（跑出来 0 处）。**这是本仓第三次"跑不起来的检查报零"**。
 *
 * 所以这里问的是另一个问题，而且不问源 alpha：
 *  1. 在**交付出去的墨**上做连通域（8 邻接）；
 *  2. 每块算圆度 `p²/(4πA)` —— 细长笔画这个值很大（一条 L×w 的带 ≈ L/(πw)），块状的接近 1；
 *  3. 圆度 ≥4 且面积 ≥256 的，认定是**一根笔画**；数它身上有几个"分得开的浓度"（间隔 ≥30 才算一档）。
 * 一根笔画只该有一个浓度 —— **台阶数 ≥2 就是这条线被切断了**。
 *
 * 跑法：`harness.BandKtKt [目录] [--edges=96,192,384]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "192,384")
    .split(",").map { it.trim().toInt() }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<BRow>()
    val pairs = ArrayList<Triple<String, Int, Int>>()      // 图标 → 相邻两桶的源色差 → 交付差
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
      val scr = pool.get() ?: continue
      val plate = fld(scr, "plate") as ByteArray
      val n = edge * edge
      val ink = BooleanArray(n) { plate[it] == 0.toByte() && (mask[it] ushr 24) > 0 }
      val lab = IntArray(n).apply { fill(-1) }
      val q = IntArray(n)
      var strokes = 0; var bands = 0; var worst = 0; var worstW = 0
      var ci = 0
      for (i in 0 until n) {
        if (!ink[i] || lab[i] >= 0) continue
        var head = 0; var tail = 0
        q[tail++] = i; lab[i] = ci; ci++
        var area = 0L; var perim = 0L
        val hist = HashMap<Int, Int>()
        while (head < tail) {
          val c = q[head++]; area++
          val cx = c % edge; val cy = c / edge
          val a = mask[c] ushr 24
          hist[a] = (hist[a] ?: 0) + 1
          for (k in 0 until 4) {
            val nx = cx + intArrayOf(1, -1, 0, 0)[k]
            val ny = cy + intArrayOf(0, 0, 1, -1)[k]
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) { perim++; continue }
            val j = ny * edge + nx
            if (!ink[j]) { perim++; continue }
            if (lab[j] < 0) { lab[j] = ci; q[tail++] = j }
          }
        }
        if (area < 256) continue
        val circ = perim * perim.toDouble() / (4.0 * Math.PI * area)
        if (circ < 4.0) continue                                     // 块状的，不是笔画
        strokes++
        val v = hist.keys.sorted()
        var lv = 1
        for (j in 1 until v.size) if (v[j] - v[j - 1] >= 30) lv++
        if (lv >= 2) { bands++; worst += lv - 1; worstW = maxOf(worstW, lv) }
      }
      // —— **相邻两桶之间：是"连续渐变的一个切片"，还是"本来就分开的两种颜色"？**
      // 复刻 `partMeans` 的那套粗桶（L/8、a·b/32、≥3% 面积、按面积取前 6），按交付均值排序后
      // 看每一对相邻桶：源色差多少、交付被拉开多少。假设是 —— 弧上那些桶是渐变的**相邻切片**
      // （源色差刚好迈过 15），而易班熊的毛/嘴/衣是**离散的平色**（源色差 40~80）。
      run {
        val labL = fld(scr, "labL") as ShortArray
        val labA = fld(scr, "labA") as ShortArray
        val labB = fld(scr, "labB") as ShortArray
        val B = 13 * 8 * 8
        val cnt = IntArray(B); val sum = LongArray(B)
        val sL = LongArray(B); val sA = LongArray(B); val sB = LongArray(B)
        var work = 0
        for (i in 0 until n) {
          if (plate[i] != 0.toByte() || (src[i] ushr 24) < 250) continue
          work++
          val l = (labL[i].toInt() and 0xffff) shr 6
          val a = ((labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
          val b = ((labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
          val k = ((l / 8) * 8 + (a / 32)) * 8 + (b / 32)
          cnt[k]++; sum[k] += (mask[i] ushr 24)
          sL[k] += labL[i].toInt(); sA[k] += labA[i].toInt(); sB[k] += labB[i].toInt()
        }
        if (work < 64) { if (strokes > 0) rows += BRow(f.name, strokes, bands, worst, worstW); return@run }
        val min = maxOf(64, work * 3 / 100)
        val left = cnt.copyOf(); val pick = ArrayList<Int>()
        repeat(6) {
          var best = -1
          for (k in 0 until B) if (left[k] >= min && (best < 0 || left[k] > left[best])) best = k
          if (best < 0) return@repeat
          pick += best; left[best] = 0
        }
        if (pick.size < 2) { if (strokes > 0) rows += BRow(f.name, strokes, bands, worst, worstW); return@run }
        val m = pick.map { intArrayOf((sum[it] / cnt[it]).toInt(), (sL[it] / cnt[it]).toInt(),
          (sA[it] / cnt[it]).toInt(), (sB[it] / cnt[it]).toInt()) }.sortedBy { it[0] }
        for (j in 1 until m.size) {
          val dl = m[j][1] - m[j - 1][1]; val da = m[j][2] - m[j - 1][2]; val db = m[j][3] - m[j - 1][3]
          val de = sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() shr 6
          pairs += Triple(f.name, de, m[j][0] - m[j - 1][0])
        }
      }
      if (strokes > 0) rows += BRow(f.name, strokes, bands, worst, worstW)
    }
    val byGap = pairs.groupBy { it.first }
    println("── 相邻两桶：源色差 vs 交付差（${byGap.size} 张有 ≥2 桶）──")
    for (key in listOf("globalspeed", "yiban")) {
      val e = byGap.filterKeys { it.contains(key) }.values.flatten()
      if (e.isEmpty()) continue
      println("  %-14s 相邻对 %d   源色差 %s   交付差 %s".format(key, e.size,
        e.map { it.second }.sorted().let { "${it[0]}~${it[it.size - 1]} 中位 ${it[it.size / 2]}" },
        e.map { it.third }.sorted().let { "${it[0]}~${it[it.size - 1]}" }))
    }
    val all = pairs.map { it.second }.sorted()
    println("  全语料相邻对 ${pairs.size} 个：源色差 p10=${all[pairs.size / 10]} p50=${all[pairs.size / 2]} " +
      "p90=${all[pairs.size * 9 / 10]}   差 <20 的对占 ${all.count { it < 20 } * 100 / all.size}%")
    rows.sortByDescending { it.bands }
    println("=== ${rows.size} / ${files.size} 张上有「细长笔画」· edge=$edge ===")
    println("%-46s %6s %6s %6s %6s".format("图标", "笔画数", "被切段数", "累计台阶", "最狠一根"))
    rows.take(12).forEach { println(it.line()) }
    fun med(g: (BRow) -> Int) = if (rows.isEmpty()) 0 else rows.map(g).sorted()[rows.size / 2]
    println("中位：笔画 ${med { it.strokes }} 根/张   被切断 ${med { it.bands }} 根   最狠一根上的台阶 ${med { it.worst }}")
    println("**笔画完好（一根只有一个浓度）的张数 = ${rows.count { it.bands == 0 }} / ${rows.size}**   " +
      "被切成 ≥2 段的张数 = ${rows.count { it.bands >= 2 }}")
    println()
  }
}

private class BRow(val name: String, val strokes: Int, val bands: Int, val steps: Int, val worst: Int) {
  fun line() = "%-46s %6d %6d %6d %6d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(46), strokes, bands, steps, worst)
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
