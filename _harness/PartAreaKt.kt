package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **探针（只量不改）**：`assignLevels` 把顶档（255）发给了**哪一块**？
 *
 * 起因：真机 census 里 32/190 张 `字均净<200`，其中 **30 张 `明暗=0`** —— 亮度轴根本没参与，
 * 所以淡不是"轴选错"，是**发档**发出来的。而 `assignLevels` 排的是
 * `sortedByDescending { mean[it] }`：**当前均浓**最高的那块拿 255，其余只许往下让。
 * 这两件事于是可以分开：均浓最高的那块，和**面积最大**的那块，不一定是同一块。
 * 若最大块落在中间档，屏幕上就是"图形在，但整体发灰"——正是「颜色偏淡，没涂满」的形状。
 *
 * 分块口径与模型一致：按**精确源色**分（`f2196b7` 之后出图也是按精确色给浓度的），
 * 只数实心（α=255）且交付了墨（>0）的格子。
 *
 * 跑法：`java ... harness.PartAreaKt <图标目录> [--min=25]`
 *   报告每行 = 面积最大那块的平均交付浓度；`嫌疑` = 最大块明显低于某一小块（差过阈值）。
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val minGap = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=") ?: "25").toInt()
  val coloursOnly = args.any { it == "--colours" }
  if (args.any { it == "--enclosed" }) { enclosedRun(dir); return }
  // `--mem`：反射读 [MonochromeMask] 自己那块的 ThreadLocal 缓冲，把每个边长上真正**常驻**
  // 的字节数加出来。不这么量的话，文档里那句"大概多少 MB"就又是一条没验过的断言
  // （本仓已经吃过几次"注释里的数是自己估的"的亏）。
  if (args.any { it == "--mem" }) {
    for (e in listOf(192, 384, 512, 768)) {
      val src = IntArray(e * e) { if (it % 7 == 0) 0 else -0x1 }        // 一块几乎满幅的实心图
      MonochromeMask.build(src, e, e)                                    // 先让缓冲按这个尺寸长起来
      val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
        .get(MonochromeMask) as ThreadLocal<*>
      val scratch = pool.get() ?: error("ThreadLocal 没给出缓冲")
      var bytes = 0L
      val detail = ArrayList<String>()
      for (f in scratch.javaClass.declaredFields) {
        f.isAccessible = true
        val v = f.get(scratch) ?: continue
        val b = when (v) {
          is ShortArray -> v.size * 2L; is ByteArray -> v.size.toLong(); is IntArray -> v.size * 4L
          else -> 0L
        }
        if (b > 0) { bytes += b; if (b >= 65536) detail += "${f.name}=${b / 1024}KB" }
      }
      println("edge=$e  常驻 ${String.format("%.1f", bytes / 1024.0 / 1024.0)} MB   " + detail.take(6).joinToString(" "))
    }
    return
  }
  val files = dir.walkTopDown().filter { it.isFile && (it.extension == "png" || it.extension == "webp") && !it.name.contains("preview") }.toList()
  if (coloursOnly) {
    val counts = ArrayList<Int>()
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val w = img.width; val h = img.height
      val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
      val set = HashSet<Int>()
      for (p in src) if ((p ushr 24) == 255) set.add(p and 0x00FFFFFF)
      counts += set.size
      println("${set.size.toString().padStart(7)}  ${f.name.removeSuffix(".png").take(50)}")
    }
    counts.sort()
    println()
    println("张数=${counts.size}  实心源色种数：中位=${counts[counts.size / 2]} p90=${counts[counts.size * 9 / 10]} 最大=${counts.last()}")
    return
  }
  var suspect = 0
  var counted = 0
  val rows = ArrayList<Triple<String, Int, Int>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w <= 1 || h <= 1) continue
    val src = IntArray(w * h)
    img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = out?.mask ?: continue
    // 按精确源色分组：面积 + 交付浓度之和
    val area = HashMap<Int, Int>()
    val sum = HashMap<Int, Long>()
    for (i in mask.indices) {
      if ((src[i] ushr 24) != 255) continue
      val a = mask[i] ushr 24
      if (a == 0) continue
      val k = src[i] and 0x00FFFFFF
      area[k] = (area[k] ?: 0) + 1
      sum[k] = (sum[k] ?: 0L) + a
    }
    val big = area.entries.filter { it.value * 100 >= (area.values.sum() * 3) }  // 至少占墨的 3%
    if (big.size < 2) continue
    counted++
    val byArea = big.sortedByDescending { it.value }
    val top = byArea.first()
    val topMean = (sum[top.key]!! / top.value).toInt()
    val richest = byArea.maxOf { (sum[it.key]!! / it.value).toInt() }
    rows += Triple(f.name.removeSuffix(".png").take(46), topMean, top.value * 100 / area.values.sum())
    if (richest - topMean >= minGap) {
      suspect++
      println("嫌疑 ${f.name.removeSuffix(".png").take(46)}  最大块=${topMean} 最浓块=${richest} 差=${richest - topMean}" +
        " 最大块占墨 ${top.value * 100 / area.values.sum()}%  块数=${byArea.size}")
    }
  }
  rows.sortBy { it.second }
  println()
  println("可分块的图标 = $counted   最大块比某一小块淡 >=$minGap 的 = $suspect")
  println("最大块交付浓度最低的 12 张：")
  rows.take(12).forEach { println("   ${it.second.toString().padStart(3)}  占墨${it.third}%  ${it.first}") }
  val m = rows.map { it.second }.sorted()
  if (m.isNotEmpty()) println("最大块浓度 中位数=${m[m.size / 2]}  p90=${m[m.size * 9 / 10]}")
}

/**
 * **这一族到底丢多少**：颜色等于底、却被非底**围住**的格子 —— 穿着底色的图形。
 *
 * globalspeed（全球网测）就是这个现场：仪表盘那道弧是 蓝→绿→深蓝 的渐变，
 * 它的深色尾段颜色就等于背景（`底占=0.88 零=15`），于是右半段被判成底、直接淡掉。
 * 契约上"图形可以合法地就是底色"（见 `propagateField` 那两段被否掉的记录），
 * 所以不能按颜色一刀切；能分开两者的是**位置**：真的底连着画布边，穿着底色的图形被围住。
 *
 * 跑法：`java ... harness.PartAreaKtKt <目录> --enclosed`
 */
private fun enclosedRun(dir: File) {
  val files = dir.walkTopDown().filter {
    it.isFile && it.extension == "png" && !it.name.contains("preview")
  }.toList()
  var icons = 0; var allLost = 0; var totalPx = 0L; var lostPx = 0L
  val rows = ArrayList<Pair<String, String>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < 32 || h < 32) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = out?.mask ?: continue
    var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
    for (y in 0 until h) for (x in 0 until w) if ((src[y * w + x] ushr 24) >= 8) {
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
    }
    if (x1 < 0) continue
    val corners = listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)
      .map { src[it.second * w + it.first] }.filter { (it ushr 24) == 255 }
    if (corners.isEmpty()) continue
    fun lab(p: Int, i: Int) = MonochromeMask.labChannel(p, i)
    val cl = corners.map { intArrayOf(lab(it, 0), lab(it, 1), lab(it, 2)) }
    fun dmin(p: Int): Int {
      var best = 1 shl 30
      for (c in cl) {
        val dl = lab(p, 0) - c[0]; val da = lab(p, 1) - c[1]; val db = lab(p, 2) - c[2]
        val d = kotlin.math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
        if (d < best) best = d
      }
      return best
    }
    val plateLike = BooleanArray(w * h)
    for (i in 0 until w * h) if ((src[i] ushr 24) == 255 && dmin(src[i]) <= 12) plateLike[i] = true
    val seen = BooleanArray(w * h); val q = IntArray(w * h); var head = 0; var tail = 0
    for (y in y0..y1) for (x in x0..x1) {
      val i = y * w + x
      if ((x == x0 || y == y0 || x == x1 || y == y1) && plateLike[i] && !seen[i]) { seen[i] = true; q[tail++] = i }
    }
    while (head < tail) {
      val i = q[head++]; val y = i / w; val x = i - y * w
      val nb = intArrayOf(if (x > x0) i - 1 else -1, if (x < x1) i + 1 else -1,
        if (y > y0) i - w else -1, if (y < y1) i + w else -1)
      for (j in nb) if (j >= 0 && plateLike[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
    }
    var enc = 0; var encLost = 0; var encSum = 0L
    for (i in 0 until w * h) {
      if (!plateLike[i] || seen[i]) continue
      enc++; val a = mask[i] ushr 24; encSum += a; if (a < 64) encLost++
    }
    if (enc == 0) continue
    icons++; totalPx += enc; lostPx += encLost
    val mean = (encSum / enc).toInt()
    if (mean < 64) { allLost++; rows += f.name to "围住=${enc}格 均墨=${mean} 丢=${100 * encLost / enc}%" }
  }
  println("有『穿着底色的图形』的图标 = $icons 张；这些格子几乎全丢(均墨<64)的 = $allLost 张")
  println("围住的格子合计 $totalPx，其中丢掉 $lostPx 格 (${if (totalPx > 0) 100 * lostPx / totalPx else 0}%)")
  rows.sortedByDescending { it.second.substringAfter("围住=").substringBefore("格").toLongOrNull() ?: 0L }
    .take(14).forEach { println("   ${it.first.take(46).padEnd(47)} ${it.second}") }
}
