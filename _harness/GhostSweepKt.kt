package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * **全量真源摊表**（2026-10-20）。
 *
 * 要回答的问题只有一句：上一轮那张 `com.absinthe.libchecker/start_snapshot` 复放出
 * `底占=0.08 实255=10`（源里 85.6% 透明 + 14.4% 纯暗墨，一格里中调都没有）——
 * 这个"输入里没有平色盘时，唯一的暗墨被当成底"到底是**一族**还是**一张**？
 * 14 张不够定阈值，而仓外一直躺着十几份带 `Mono: dump#` 的 logcat：
 * 这里把它们**全部**重组，逐张量三样东西：
 *   输入侧（透明占比 / 最大平色块 / 色族数 / 暗中亮 / 暗墨面积）
 *   判据侧（`底占 H 族 盘实 框内`，离线复算 **和设备上那一趟的读数并排**）
 *   交付侧（浓度直方图 + 均α + 实心/输入不透明之比）
 *
 * 用法：`harness.GhostSweepKt --out=<tsv> <log1> <log2> ...`
 *
 * ⚠ 只把 `MonoDump` 从设备上带回来的 `what=src` 当输入。桌面库 `icon` 列那张**不是**钩子收到的
 *   drawable（这条已经栽过一次：拿它复算出来的 IoU 0.001 直接作废），所以这里一概不读 `.src.png` 文件。
 */

fun main(args: Array<String>) {
  val outPath = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
    ?: error("用法: GhostSweepKt --out=<tsv> <log…>")
  val logs = args.filter { !it.startsWith("--") }
  require(logs.isNotEmpty()) { "没给 logcat" }

  val sb = StringBuilder()
  val header = listOf("label", "log", "W", "H", "层", "under",
    "透明%", "实底%", "盘%", "族数", "暗%", "中%", "亮%", "L中位", "暗墨%",
    "备底占", "备H", "备框内", "离底占", "离H", "离族", "盘实", "墨实", "离框内", "字均净",
    "m0", "m淡", "m中", "m实", "均α", "实/不透明%", "芯α", "芯实%", "芯%", "交空")
  sb.append(header.joinToString("\t")).append('\n')

  val seen = HashSet<String>()
  var rows = 0; var fail = 0
  var agree = 0; var disagree = 0; var noDevH = 0
  val misses = ArrayList<String>()
  for (log in logs) {
    val f = File(log)
    if (!f.isFile) { println("!! 没有这个文件 $log"); continue }
    val (caps, summary) = capture(f.readLines())
    val byKey = caps.groupBy { it.key }
    val srcs = caps.filter { it.what == "src" }
    println("== ${f.name}  $summary   src ${srcs.size} 张")
    for (c in srcs.sortedBy { it.key }) {
      val hash = md5(c.bytes)
      if (!seen.add(hash)) continue                    // 同一张在多个窗口里反复出现，只量一次
      val img = runCatching { ImageIO.read(ByteArrayInputStream(c.bytes)) }.getOrNull()
      if (img == null) { fail++; continue }        // 解不出来 = 这台仪器看不见它，必须报名，不能当"没有这一张"
      val w = img.width; val h = img.height; val n = w * h
      val argb = IntArray(n)
      img.getRGB(0, 0, w, h, argb, 0, w)
      val label = c.note.substringBefore('|').trim().ifEmpty { "?" }
      val layer = c.note.contains("层底=1")
      val underCap = byKey[c.key]?.firstOrNull { it.what == "under" }
      var under: IntArray? = null
      if (underCap != null) {
        val u = ImageIO.read(ByteArrayInputStream(underCap.bytes))
        if (u.width == w && u.height == h) under = IntArray(n).also { u.getRGB(0, 0, w, h, it, 0, w) }
      }

      // ---- 输入侧 ----
      // ⚠ 位运算一律先用具名变量拆开：`p shr 16 and 0xf0 shl 12` 在 Kotlin 里是**从左往右**结合的，
      //   看着像"(高字节取整 4 位) 挪到索引 12"，实际算的是另一个数 —— 这类式子一旦错，
      //   整张"平色盘"的账全是假的，而且假得很安静（分母看着照样是 0..100）。
      var a0 = 0; var aFF = 0
      val bins = HashMap<Int, Int>()
      val luma = ArrayList<Int>(n / 4)
      var dark = 0; var midL = 0; var lite = 0
      for (p in argb) {
        val a = (p ushr 24) and 0xff
        if (a == 0) { a0++; continue }
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val l = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
        if (a != 255) continue                        // 半透明是抗锯齿边，不进"色族/平色盘"这本账
        aFF++
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        bins[key] = (bins[key] ?: 0) + 1
        luma += l
        if (l < 96) dark++ else if (l <= 160) midL++ else lite++
      }
      val transPct = a0 * 100.0 / n
      val ffPct = aFF * 100.0 / n
      val topBin = (bins.values.maxOrNull() ?: 0) * 100.0 / n
      val fams = bins.entries.count { it.value * 100 >= n }   // 占满 1% 画布的色族
      luma.sort()
      val lmed = if (luma.isEmpty()) -1 else luma[luma.size / 2]

      // ---- 判据侧：跑仓里那份生产代码，同时把设备自己那一趟的读数并出来 ----
      val inkAllPct = dark * 100.0 / n                  // 暗墨占**整张画布**：这是"该出墨的面积"
      val r = MonochromeMask.build(argb, w, h, layer, under)
      val devShare = field(c.note, "底占"); val devH = field(c.note, "H"); val devFill = field(c.note, "框内")
      val offShare = field(r.report, "底占"); val offH = field(r.report, "H")
      val offFill = field(r.report, "框内"); val offFam = field(r.report, "族")
      val pn = Regex("盘实=(\\d+)/(\\d+)").find(r.report)
      val net = field(r.report, "字均净") ?: -1.0
      if (devShare != null && offShare != null) {
        // `底占` 是 0..1 的小数、两位打印 —— 容差只给到那半格的舍入，别放宽到"看得出错不开"。
        if (devH == null) noDevH++                          // 老包没打 `H=`：这一张没法算"复放对上了"
        else if (kotlin.math.abs(devShare - offShare) <= 0.011 && devH == offH) agree++
        else {
          disagree++
          if (misses.size < 12) misses += "   ${label.take(46)}  设备 底占=$devShare H=$devH  vs  离线 $offShare/$offH"
        }
      }

      // ---- 交付侧 ----
      val mask = r.mask ?: IntArray(n)
      var m0 = 0; var mSoft = 0; var mMid = 0; var mSolid = 0; var mass = 0L
      for (p in mask) {
        val a = (p ushr 24) and 0xff
        mass += a
        when { a == 0 -> m0++; a < 128 -> mSoft++; a == 255 -> mSolid++; else -> mMid++ }
      }
      val meanA = mass.toDouble() / n
      val perOp = if (aFF == 0) -1.0 else mSolid * 100.0 / aFF

      // **源锚定的浓度**：只统计"源自己就是实的那一格"（α≥250）上我们交付了多少墨。
      //   为什么要有它：整张均α 和 `实255` 都被两件事拖着走 —— 图标自己细不细、以及"哪块该出墨"
      //   这个判断本身。前者是画的样子不是病，后者一猜错就整族误报（深底浅字那一族第一版全中招）。
      //   源 α≥250 的格是**图上确实有东西**的那部分，与颜色无关、与极性无关 ——
      //   它出墨出到 60，屏幕上就是淡的，没有任何解释余地。
      var coreN = 0; var coreSum = 0L; var coreSolid = 0
      // `盘格` = 源里那块最大平色（占画布 ≥15% 才算"底"）—— 它**该**被擦掉，不能算进"芯"里。
      //   不加这一条，深底浅字那一族（华为市场 / 微博 / B 站）的芯α 会被自己的盘子拉到 20 以下，
      //   看着像"擦了九成墨"，其实擦的分母根本不该出墨。第一版就是这么误报了一族。
      val topKey = bins.entries.maxByOrNull { it.value }?.key ?: -1
      val skipPlate = topBin >= 15.0
      if (mask.size == n) for (i in 0 until n) {
        val px = argb[i]
        if (((px ushr 24) and 0xff) < 250) continue
        if (skipPlate) {
          val rr = (px shr 16) and 0xff; val gg = (px shr 8) and 0xff; val bb = px and 0xff
          if ((((rr shr 4) shl 8) or ((gg shr 4) shl 4) or (bb shr 4)) == topKey) continue
        }
        coreN++; coreSum += (mask[i] ushr 24) and 0xff
        if (((mask[i] ushr 24) and 0xff) >= 250) coreSolid++
      }
      val coreA = if (coreN == 0) -1.0 else coreSum.toDouble() / coreN
      val coreHit = if (coreN == 0) -1.0 else coreSolid * 100.0 / coreN
      // `交空` = 这一张压根没交掩码（[MonochromeMask.Output.mask] 为 null），和"交了但没墨"要分得开。
      val nullMask = r.mask == null

      sb.append(listOf(label.take(70), f.name, w, h,
        if (layer) 1 else 0, if (under != null) 1 else 0,
        "%.1f".format(transPct), "%.1f".format(ffPct), "%.1f".format(topBin), fams,
        "%.1f".format(if (aFF == 0) 0.0 else dark * 100.0 / aFF),
        "%.1f".format(if (aFF == 0) 0.0 else midL * 100.0 / aFF),
        "%.1f".format(if (aFF == 0) 0.0 else lite * 100.0 / aFF),
        lmed, "%.1f".format(inkAllPct),
        devShare?.toString() ?: "-", devH?.toString() ?: "-", devFill?.toString() ?: "-",
        offShare?.toString() ?: "-", offH?.toString() ?: "-", offFam?.toString() ?: "-",
        pn?.groupValues?.get(1) ?: "-", pn?.groupValues?.get(2) ?: "-",
        offFill?.toString() ?: "-", "%.0f".format(net),
        m0, mSoft, mMid, mSolid, "%.1f".format(meanA), "%.2f".format(perOp),
        "%.1f".format(coreA), "%.1f".format(coreHit), "%.1f".format(coreN * 100.0 / n),
        if (nullMask) 1 else 0).joinToString("\t"))
        .append('\n')
      rows++
    }
  }
  File(outPath).writeText(sb.toString())
  println("唯一真源 ${seen.size} 个内容 → 表上 $rows 张（PNG 解不出来的 $fail 张，这台仪器看不见它们）")
  println("复放对照：`底占` 差 ≤0.011 且 `H` 相同 = $agree 张 / 不同 = $disagree 张 / 老包没打 H = $noDevH 张")
  misses.forEach { println(it) }
}
