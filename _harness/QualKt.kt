package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **发档那 6 个槽位，被谁占了**（只量不改）。
 *
 * 现场：语料 113 张平均 **4.24 块**，只发得出 **2.60 档**；`≥4 块却 ≤2 档` 的有 20 张。
 * `partMeans` 认"部分"只问两件事：**不是底** + **占工作集 ≥3% 面积**。它不问这块**离底有多远**。
 * 于是柔光圈、抗锯齿、留白这些" dep 刚过零点"的一圈，也占一个槽位、也领一档浓度。
 *
 * 这不是我新提的猜想 —— `MonochromeMask.kt:846-851` 那段自己写着：上一版铺档重排
 * （平均档数 2.66→2.94、完全分开 8/32→12/32）**在 azure 上画出一块看得见的灰方框**
 * （盾牌外面那圈柔光被当成"一个部分"，重排就发给它一整格浓度），于是修法撤了、数留着，
 * 并写下：**下一步的锚点必须先把"柔光/留白"排除在"部分"之外（按面积占比与离底距离双重门槛）。**
 * 这个探针就是去量那道门槛**有没有牙**：
 *  · 现在这 6 个槽里，有几格坐的是"离底很近"的那类？
 * · 把它们从"部分"里剔掉之后，剩下的块按同样的 ≥30 间隔重数一遍档数 —— **档数涨不涨、涨在几张**？
 * 只有第二问答"涨"，这条路才值得开真 A/B；答"不涨"，那 20 张的合并就不是被柔光挤的，别的路。
 *
 * 跑法：`harness.QualKtKt [目录] [--edges=96,192,384,768] [--dep=45] [--show=子串]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val depFloors = (args.firstOrNull { it.startsWith("--dep=") }?.substringAfter("=") ?: "30,45,60,80")
    .split(",").map { it.trim().toInt() }
  val show = args.firstOrNull { it.startsWith("--show=") }?.substringAfter("=") ?: ""
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val SEP_L = 8; val SEP_AB = 32; val BUCKETS = 13 * 8 * 8
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>

  var icons = 0
  var icons2 = 0; var satN = 0L; var satShare = 0.0; var satSpan = 0L; var satSpanN = 0
  val spans = ArrayList<IntArray>(); var realCollapsed = 0; var invented = 0
  // 「源色撑得起几个看得见的台阶」：一个台阶 = 交付差 30（`SEP_MIN_GAP`，肉眼分得开的最小值）。
  val DSTEP = intArrayOf(10, 15, 20, 30)
  var srcSpread = 0L; var srcN = 0
  val justified = IntArray(DSTEP.size); val openIcons = IntArray(DSTEP.size)
  val gain = IntArray(depFloors.size)                       // 每个门槛下"档数变多"的张数
  val avgA = ArrayList<Int>(); val avgB = ArrayList<Int>()
  val hazeSlots = ArrayList<Int>(); val allSlots = ArrayList<Int>()
  val det = ArrayList<String>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < 96 || h < 96) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull() ?: continue
    val mask = out.mask ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val dist = fld(s, "dist") as ShortArray
    val labL = fld(s, "labL") as ShortArray
    val labA = fld(s, "labA") as ShortArray
    val labB = fld(s, "labB") as ShortArray
    val ref = fld(s, "ref") as IntArray
    val fL = fld(s, "fL") as IntArray
    val fA = fld(s, "fA") as IntArray
    val fB = fld(s, "fB") as IntArray
    val cnt = IntArray(BUCKETS); val sumA = LongArray(BUCKETS); val sumD = LongArray(BUCKETS)
    val sumE = LongArray(BUCKETS)
    val sumL = LongArray(BUCKETS); val sumLA = LongArray(BUCKETS); val sumLB = LongArray(BUCKETS)
    val adj = IntArray(BUCKETS)
    val deAll = ArrayList<Int>()
    var work = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        val i = row + x
        if (plate[i] != 0.toByte()) continue                 // 底板不参与（与 partMeans 同一条）
        work++
        val l = (labL[i].toInt() and 0xffff) shr 6
        val a = ((labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val b = ((labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val k = ((l / SEP_L) * 8 + (a / SEP_AB)) * 8 + (b / SEP_AB)
        val near = (x > 0 && plate[i - 1] != 0.toByte()) || (x + 1 < w && plate[i + 1] != 0.toByte()) ||
          (y > 0 && plate[i - w] != 0.toByte()) || (y + 1 < h && plate[i + w] != 0.toByte())
        cnt[k]++; sumA[k] += (mask[i] ushr 24); sumD[k] += (dist[i].toInt() and 0xffff)
        sumL[k] += labL[i].toInt(); sumLA[k] += labA[i].toInt(); sumLB[k] += labB[i].toInt()
        if (near) adj[k]++
        // **未截断的 ΔE**：`departure` 里那个 `de` 就是它，但它当场被 `min(ΔE, EMAX)` 剪掉了，
        // 剪掉的那一段有没有货，只有在这儿才算得出来。
        if ((src[i] ushr 24) == 255) {
          val c = if (ref[i] >= 0) ref[i] else 0
          // 与 `departure` 同一口径：labL/A/B 是**带符号**的 ×64，fL/fA/fB 也是。
          val dl = labL[i].toInt() - fL[c]
          val da = labA[i].toInt() - fA[c]
          val db = labB[i].toInt() - fB[c]
          val de = Math.sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() shr 6
          sumE[k] += de; deAll += de
        }
      }
    }
    if (work < 64) continue
    val min = maxOf(64, work * 3 / 100)
    val left = cnt.copyOf(); val picked = ArrayList<Int>()
    repeat(6) {
      var best = -1
      for (k in 0 until BUCKETS) if (left[k] >= min && (best < 0 || left[k] > left[best])) best = k
      if (best < 0) return@repeat
      picked += best; left[best] = 0
    }
    if (picked.size < 2) { if (show.isNotEmpty() && f.name.contains(show)) det += "  ${f.name.take(40)}  只 ${picked.size} 块"; continue }
    icons++
    // 每块：[交付α, dep, 占工作集%, 挨底%, 未截断ΔE]
    val rows = picked.map { intArrayOf(
      (sumA[it] / cnt[it]).toInt(), (sumD[it] / cnt[it]).toInt(),
      cnt[it] * 100 / work, adj[it] * 100 / cnt[it], (sumE[it] / cnt[it]).toInt()) }
      .sortedBy { it[0] }
    // **源色自己铺得多开**（块均值之间的最大成对 ΔE）。这是"地板该不该下沉"唯一不撒谎的尺 ——
    // dep 跨 119 是 ΔE 跨 53 的 2.2 倍，拿 dep 当尺会把易班那类现编的档放进来。
    val mean = picked.map { intArrayOf(
      (sumL[it] / cnt[it]).toInt(), (sumLA[it] / cnt[it]).toInt(), (sumLB[it] / cnt[it]).toInt()) }
    var pair = 0
    for (u in mean.indices) for (v in u + 1 until mean.size) {
      val dl = mean[u][0] - mean[v][0]; val da = mean[u][1] - mean[v][1]; val db = mean[u][2] - mean[v][2]
      val de = Math.sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() shr 6
      if (de > pair) pair = de
    }
    srcSpread += pair; srcN++
    for (di in DSTEP.indices) {
      // 源色撑得起几个"看得见"的台阶（一个台阶 = 交付差 30），最多也就 n−1 个。
      val can = minOf(picked.size - 1, pair / DSTEP[di])
      val extra = can - 2                                   // 今天算术上只有 3 档 = 2 个台阶
      if (extra > 0) { justified[di] += extra; openIcons[di]++ }
    }
    val alpha = rows.map { it[0] }
    fun levels(v: List<Int>) = if (v.size < 2) 0 else 1 + (1 until v.size).count { v[it] - v[it - 1] >= 30 }
    val a0 = levels(alpha)
    avgA += a0; allSlots += picked.size
    // **ΔE 被 EMAX=100 剪掉的那一段有多少货**：饱和格的占比、以及饱和格自己的 ΔE 跨度。
    deAll.sort()
    if (deAll.isNotEmpty()) {
      val sat = deAll.filter { it >= 100 }
      satShare += sat.size * 100.0 / deAll.size
      satN += sat.size
      if (sat.size > 16) { satSpan += deAll[deAll.size - 1] - sat[sat.size / 20]; satSpanN++ }
      icons2++
    }
    val keepDet = show.isNotEmpty() && f.name.contains(show)
    if (keepDet) {
      det += "%-40s  %d 块 → %d 档   零=%s   墨格 ΔE 中位=%d p95=%d 饱和%d%%".format(
        f.name.removeSuffix(".png").take(40), picked.size, a0, fld0(out.report, "零"),
        deAll[deAll.size / 2], deAll[deAll.size * 95 / 100],
        if (deAll.isEmpty()) 0 else deAll.count { it >= 100 } * 100 / deAll.size)
      rows.forEach { r -> det += "      α=${r[0].toString().padStart(3)} dep=${r[1].toString().padStart(3)} ΔE=${r[4].toString().padStart(3)} 占${r[2]}% 挨底${r[3]}%" }
    }
    // **跨度对账**：交付拉开的这一段，有多少是 dep 里真有的、有多少是发档现编的。
    //  azure 那一行是标本：ΔE 0/1/11/31、dep 跨 4、交付跨 76 —— 次序几乎不是从源图来的。
    if (rows.size >= 3) {
      val dS = rows.map { it[1] }.max()!! - rows.map { it[1] }.min()!!
      val aS = rows.map { it[0] }.max()!! - rows.map { it[0] }.min()!!
      val eS = (rows.map { it[4] }.max()!! - rows.map { it[4] }.min()!!)
      spans += intArrayOf(dS, aS, eS, rows.size)
      if (dS >= 40 && aS < dS) realCollapsed++            // dep 里有货、被压掉
      if (dS < 15 && aS >= 40) invented++                  // dep 是平的、交付却拉开 = 现编
      if (keepDet) det += "      跨度：ΔE $eS   dep $dS   交付 $aS   ← 交付/dep = " +
        (if (dS == 0) "∞" else "%.1f".format(aS * 1.0 / dS))
    }
    for (t in depFloors.indices) {
      val keep = rows.filter { it[1] >= depFloors[t] }.map { it[0] }
      if (levels(keep) > a0) gain[t]++
      if (t == 0) { avgB += levels(keep); hazeSlots += (picked.size - keep.size).coerceAtLeast(0) }
    }
  }
  println("有 ≥2 块的图标 = $icons 张（目录 ${dir.name}，边长 $edges）")
  println("平均 档数(今天) = ${"%.2f".format(avgA.average())}   槽位/张 = ${"%.2f".format(allSlots.average())}")
  for (t in depFloors.indices)
    println("  剔掉「平均 dep < ${depFloors[t]}」的那几块之后：档数变多的 = ${gain[t]} / $icons 张")
  println("平均 档数(按 dep≥${depFloors[0]} 剔之后) = ${"%.2f".format(avgB.average())}   平均被剔 ${"%.2f".format(hazeSlots.average())} 块/张")
  println("── ΔE 被 EMAX=100 剪掉的那一段有多少货 ──")
  println("  墨格里 ΔE≥100 的比例（按张平均） = ${"%.1f".format(satShare / icons2)}%   合计 ${satN / 1000}k 格")
  println("  饱和格里 ΔE 还散开多少（p95 − p5，按张平均） = ${"%.0f".format(satSpan.toDouble() / satSpanN)}   （$satSpanN 张有饱和格）")
  println("── 跨度对账（≥3 块的 ${spans.size} 张）──")
  fun md(i: Int) = spans.map { it[i] }.sorted()[spans.size / 2]
  println("  中位：ΔE 跨 ${md(2)}   dep 跨 ${md(0)}   交付跨 ${md(1)}")
  println("  **dep 有货（跨 ≥40）却被压掉的 = $realCollapsed 张**   dep 是平的（<15）而交付拉开 ≥40 的（= 现编次序）= $invented 张")
  println("── 源色撑得起几个「看得见」的台阶（一个台阶 = 交付差 30）──")
  println("  块与块之间最大成对 ΔE：按张平均 ${"%.0f".format(srcSpread.toDouble() / srcN)}（$srcN 张）")
  for (d in DSTEP.indices)
    println("  每台阶要源 ΔE ≥ ${DSTEP[d]}：撑得起 >3 档的 = ${openIcons[d]} / $srcN 张   合计可多发 ${justified[d]} 档")
  det.take(80).forEach { println(it) }
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun fld0(report: String, key: String): String {
  for (x in report.split(" ")) if (x.startsWith("$key=")) return x.substringAfter("=")
  return "?"
}
