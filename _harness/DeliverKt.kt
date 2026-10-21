package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **交付档数台（只量，不改模型）** —— 2026-10-08，接 `eb2eced` 那一刀。
 *
 * 上一轮的尺子 `分档=档数/块数` 被 feimo 自己否了：它数档之前先把"交付浓度相差 <30"的
 * 两块**合并**，而连续调子的定义就是相邻差异不到 30 —— 所以它**按构造**判连续调子不及格
 * （金标准 feimo 得 2/6）。修模型之前先修量具。
 *
 * 这一台**一个都不合并**，只看交出去那张的 α 直方图在 16 格上铺了几格（≥1% 墨量才算占格）。
 * ⚠ 但单看"交档"会冤枉一类：源图本来就一块色（实心剪影），它只能交出 1 档，那不是压平。
 *   所以每台子同时量**源档**（同一套 16 格，量的是这张自己实心像素的 L\*），
 *   要抓的是 **源有 N 格而只交出 M<N 格** 那种 —— 刻度是源给的，交付却更窄。
 *
 * ⚠ **第一版就撞在"量具有没有牙"上**：拿全部上了墨的格数交档，xiaoheihe `源 2 档 → 交 4 档`，
 *   比值 2.0 —— 多出来那 2 档全是**缩放出来的边缘斜坡**（交付 α = 模型 α × 源 α，源的反锯齿
 *   乘了进来）。这跟当年「淡」那一列被柔光污染是同一个病。
 *   所以这一台的**主档数只数源本来就实心（α=255）的那些格** —— 那里的 α 才是模型自己给的；
 *   「全部墨」那一版同时留着报出来，两个数之差就是边缘斜坡的虚高，看得见才不会又把它当层次。
 *
 * 入场判据（不是阈值，是一条必须成立的事）：这台子得把
 *   · **com.feimo.astralparty**（连续调子，金标准）放在**高**那一端，
 *   · **songpal 那一类 `1/6`**（真压平）放在**低**那一端。
 * 分不开就说明这把尺子还是错的，别再动 `CURVE`/`LEVEL_FLOOR`/`stretchGlobal`。
 *
 * 跑法：`java -cp … harness.DeliverKtKt <目录> [目录…] [--edge=384]`
 */

private fun loadArgb(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  // ⚠ **edge=0 = 按原生尺寸**。判据台默认把源图拉到 384，而 rec3u 里 feimo / browndust2 的源文件
  //   是 **192×192**（MaskBench 自己会报"本张数字不可与真机对照"）。放大这一刀本身就改 α 直方图
  //   —— 插值出来的中间值正好会被"不合并的档数"数成层次，于是**只有那两张**虚高。
  //   这把尺子数的是分布，所以它必须量原生尺寸，否则第一名的位置是插值给的。
  val e = if (edge > 0) edge else maxOf(src.width, src.height)
  val img = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, e, e)
  g.drawImage(src, 0, 0, e, e, null)
  g.dispose()
  val out = IntArray(e * e)
  img.getRGB(0, 0, e, e, out, 0, e)
  return out
}

private fun labL(px: Int): Double {
  val f = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
  val y = 0.2126 * f(((px shr 16) and 0xff) / 255.0) +
    0.7152 * f(((px shr 8) and 0xff) / 255.0) +
    0.0722 * f((px and 0xff) / 255.0)
  val fy = { t: Double -> if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0 }
  return 116.0 * fy(y) - 16.0
}

/** 一格占没占：这一格的像素数 ≥ 总面积的 pct%。返回占格数、熵（bit）、最大占格比。 */
private fun occ(hist: IntArray, n: Int, pct: Int): Triple<Int, Double, Int> {
  if (n == 0) return Triple(0, 0.0, 0)
  val want = n.toDouble() * pct / 100.0
  var used = 0
  var ent = 0.0
  var top = 0
  for (b in hist) {
    if (b >= want) used++
    if (b > top) top = b
    if (b > 0) { val p = b.toDouble() / n; ent -= p * (Math.log(p) / Math.log(2.0)) }
  }
  return Triple(used, ent, top * 100 / n)
}

/** α 的三个分位（p10/p50/p90），按 16 格的格宽还原成浓度数。
 *  为什么还要它：`交实=16` 只说"每个格都有人"，不说**人挤在哪**。
 *  browndust2 第一版就是靠这个蒙过去的 —— 它 16 格全占，可六个部分的均值是
 *  `206,208,230,231,251,255`（全贴顶），屏幕上就是一坨实心墨。**档数**看不见贴顶，**分位**看得见。
 *  ⚠ 源那一侧的格是 L*（0..100）等分 16 格、交付那侧是 α（0..255）等分 16 格，
 *    两边的"宽"都换算成"占满量程的百分之几 ×255"，所以**宽度可以并排比，绝对位置不行**。 */
private fun pct3(hist: IntArray, n: Int): IntArray {
  if (n == 0) return intArrayOf(0, 0, 0)
  val out = IntArray(3)
  val ps = intArrayOf(10, 50, 90)
  for (k in 0..2) {
    val want = n.toDouble() * ps[k] / 100.0
    var c = 0
    for (b in 0 until 16) { c += hist[b]; if (c >= want) { out[k] = b * 16 + 8; break } }
  }
  return out
}

/** 中间调占比：交付墨里落在 α 64..192 的那部分（实心像素口径，与 `交实` 同一拨人）。
 *  为什么加它：档数与斜坡宽度都分不开 feimo 与那几个点名图之后，剩下的读法只有一种 ——
 *  主人说的"色度不广"字面是**没有中间调**（只有黑与白），而 `交实=16 格全占` 与
 *  「两头各占一半、中间是空的」是同一个数。**占格看不见空档，占比看得见。**
 *  64/192 这两条线就是判据台已有的「淡 1..127 / 中 128..254」边界往内收一格，
 *  免得边缘那一圈 1..63 的柔光被算成中间调。 */
private fun midShare(hist: IntArray, n: Int): Int {
  if (n == 0) return 0
  var mid = 0
  for (b in 4..11) mid += hist[b]        // 格 4..11 = α 64..191
  return mid * 100 / n
}

fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 0
  val ctlN = args.firstOrNull { it.startsWith("--ctl=") }?.substringAfter("=")?.toInt() ?: 0
  if (dirs.isEmpty()) { println("用法：DeliverKtKt <目录> [目录…] [--edge=N]（默认 0 = 按原生尺寸）[--ctl=N 档]"); return }
  println("交付档数台 · edge=${if (edge == 0) "原生" else edge}" +
    (if (ctlN == 0) "" else " · 阳性对照：交付 α 量化到 $ctlN 档") + " —— 16 格、不合并；≥1% 墨算占格")
  for (d in dirs) {
    val fs = File(d).listFiles { f: File -> f.extension == "png" &&
      !f.name.contains("preview") && !f.name.contains("_mono") } ?: continue
    println("### $d (${fs.size} 张)")
    println("%-42s %4s %4s %4s %12s %12s %6s %4s %5s %5s %5s %3s %4s".format(
      "图标", "源档", "交实", "交墨", "源p10/50/90", "交p10/50/90", "交宽", "中调", "保留", "旧分档", "框内", "支", "跨"))
    var rows = ArrayList<IntArray>()
    var names = ArrayList<String>()
    var brs = ArrayList<String>()
    for (f in fs.sortedBy { it.name }) {
      val src = runCatching { loadArgb(f.absolutePath, edge) }.getOrNull() ?: continue
      val side = Math.sqrt(src.size.toDouble()).toInt()
      val r = MonochromeMask.build(src, side, side)
      val mask = r.mask
      // 源档：这张自己**实心**（α≥128）像素的 L* 铺了几格 —— 与 tonalClass/RampShape 同口径。
      val hs = IntArray(16); var ns = 0
      for (i in src.indices) {
        if (((src[i] ushr 24) and 0xff) < 128) continue
        ns++
        hs[(labL(src[i]) / 100.0 * 16).toInt().coerceIn(0, 15)]++
      }
      // 交实：**只数源本来就实心（α=255）的那些格** —— 那里的 α 才是模型自己给的，没有边缘斜坡。
      // 交墨：全部上了墨的格（含反锯齿）。留着只为把虚高那几档量出来，不作判据。
      // ⚠ `--ctl=N`：**阳性对照** —— 把交付 α 强行量化到 N 档再数。
      //   一把说"保留率"的尺子必须**会因为压平而掉**，否则它报的 0 与 12 都只是在描述自己。
      //   N=64 近似不改变（真实交付通常 <64 档），N=2/3/4 应当把中间调扫光。
      val ctl = ctlN
      val hd = IntArray(16); var nd = 0
      val he = IntArray(16); var ne = 0
      if (mask != null) for (i in mask.indices) {
        val sa = (src[i] ushr 24) and 0xff
        var a = (mask[i] ushr 24) and 0xff
        if (a == 0) continue
        if (ctl >= 2) a = (((a.toLong() * (ctl - 1) + 127) / 255) * 255 / (ctl - 1)).toInt()
        ne++
        he[(a / 16).coerceIn(0, 15)]++
        if (sa != 255) continue
        nd++
        hd[(a / 16).coerceIn(0, 15)]++
      }
      val s = occ(hs, ns, 1)
      val dd = occ(hd, nd, 1)
      val ee = occ(he, ne, 1)
      val ps = pct3(hs, ns)
      val pd = pct3(hd, nd)
      val old = Regex("分档=([^ ]*)").find(r.report)?.groupValues?.get(1) ?: "-"
      val fill = Regex("框内=([^ ]*)").find(r.report)?.groupValues?.get(1) ?: "-"
      names.add(f.nameWithoutExtension)
      // 中间调占比：源那一侧（L* 的格 4..11 ≈ L* 25..69）与交付那一侧**并排报**，
      // 因为"压平"只剩这一种可查的形式：源有中间调而交付没有。两边都没有 = 素材本身（上限那条）。
      val midS = midShare(hs, ns)
      val midD = midShare(hd, nd)
      // 走的是哪一支：`调=1` = 满幅插画那一支（不要参照，浓度=这张自己的亮度斜坡），
      // 否则走参照那条路。这一列是**结果不是判据** —— 加它是因为 feimo 与 songpal 都在这张表里，
      // 而主人要的"像 feimo"到底是"同一条支"还是"同一种分布"，得能在同一行上看出来。
      val br = if (Regex(" 调=1").containsMatchIn(r.report)) "调" else "参"
      brs.add(br)
      val span = Regex("跨=([0-9]+)").find(r.report)?.groupValues?.get(1) ?: "—"
      // 列序：源档, 交实, 交墨, 交宽(p90−p10), 交中位, 源宽, 墨/实, 源中调, 交中调, 保留%, 这张自己的跨
      val keep = if (midS == 0) 0 else midD * 100 / midS
      rows.add(intArrayOf(s.first, dd.first, ee.first, pd[2] - pd[0], pd[1], ps[2] - ps[0],
        if (nd == 0) 0 else ne * 100 / nd, midS, midD, keep, span.toIntOrNull() ?: 0))
      println("%-42s %4d %4d %4d %6d/%3d/%3d %6d/%3d/%3d %6d %3d/%-3d %4d%% %5s %5s %3s %4s".format(
        f.nameWithoutExtension.take(42), s.first, dd.first, ee.first,
        ps[0], ps[1], ps[2], pd[0], pd[1], pd[2], pd[2] - pd[0], midS, midD, keep, old, fill, br, span))
    }
    // 汇总：只看**源≥3 档**的那些（两档以下没有可丢的），按**交付 IQR 宽度**升序 = 最挤的排前面。
    // ⚠ 排序键换了两次才定：`交实`（占格数）分不开金标准 —— browndust2 占满 16 格、熵 3.88，
    //   比 feimo 还高，可它六个部分均值 `206..255` 全贴顶，屏幕上是一坨实心墨。
    //   所以要抓的是**人挤在哪**（IQR），不是**占了几格**。
    val idx = ArrayList<Int>()
    for (i in rows.indices) if (rows[i][0] >= 3) idx.add(i)
    println("\n  交付最挤（IQR 最窄）而源≥3 档的：")
    for (i in idx.sortedBy { rows[it][3] }.take(14))
      println("    %-42s 交宽 %3d 中位 %3d ｜ 源宽 %3d ｜ 占格 源%d/交%d（墨 %d，墨/实=%d%%）".format(
        names[i].take(42), rows[i][3], rows[i][4], rows[i][5], rows[i][0], rows[i][1], rows[i][2], rows[i][6]))
    val w = idx.map { rows[it][3] }.sorted()
    println("  交付 IQR 宽度：p25=${w[w.size / 4]} 中位=${w[w.size / 2]} p75=${w[w.size * 3 / 4]}（源≥3 档的 ${idx.size} 张）")
    println("  边缘虚高（交墨 − 交实）：中位 ${idx.map { rows[it][2] - rows[it][1] }.sorted()[idx.size / 2]} 档")
    // **入场判据就这一行**：交付中间调占比能不能把 feimo 与点名那几张分开放在两端。
    val mi = idx.map { rows[it][8] }.sorted()
    println("  交付中间调%：p25=${mi[mi.size / 4]} 中位=${mi[mi.size / 2]} p75=${mi[mi.size * 3 / 4]}")
    println("  源有中间调（≥8%）而交付没有（<8%）的 —— 只剩这一种「压平」可查：")
    var hit = 0
    for (i in idx.sortedBy { rows[it][8] }) {
      if (rows[i][7] < 8 || rows[i][8] >= 8) continue
      hit++
      println(("    %-42s 源中调 %2d%% → 交中调 %2d%%（占格 源%d/交%d，支=%s，墨/实=%d%%）").format(
        names[i].take(42), rows[i][7], rows[i][8], rows[i][0], rows[i][1], brs[i], rows[i][6]))
    }
    if (hit == 0) println("    （0 张 —— 交付没有把源的中间调擦掉过）")
    // **保留率 = 交付中间调 / 源中间调**。这是这一台目前唯一**过了入场判据**的数：
    // feimo（金标准）保留 93%，而主人点名的 azure 只保留 25% —— 第一次有一把尺子
    // 把"像 feimo"与"被点名说不广"放在两端。⚠ 但**方向还没定**（线性拉伸必然把中间调摊到两头，
    //   收它 = 降对比），所以这里只列名单与数，不排"坏第几名"，等装机的眼睛。
    // ⚠⚠ **过滤条件必须有第二条**：`源中调高` 不等于 `层次属于图形` ——
    //   一块品牌色（通讯录的绿、米家的橙）L* 就在中间，源中调 83%，可它本来就只有一档，
    //   交成一整块实心是**对的**。这与 `ObjKt` 平色饼那条踩过的坑是同一个坑。
    //   所以只看**源自己 p90−p10 ≥ 60**（= 这块墨里真有明暗差别）的那些张。
    val cond = idx.filter { rows[it][7] >= 20 && rows[it][5] >= 60 }
    val kp = cond.map { rows[it][9] }.sorted()
    if (kp.isNotEmpty())
      println("  源中调≥20% 且源宽≥60 的 ${kp.size} 张，保留率：p10=${kp[kp.size / 10]} p25=${kp[kp.size / 4]} " +
        "中位=${kp[kp.size / 2]} p75=${kp[kp.size * 3 / 4]}")
    println("  保留率最低 12 张（源中调≥20% 且源宽≥60）：")
    for (i in cond.sortedBy { rows[it][9] }.take(12))
      println("    %-42s 保留 %3d%%（源中调 %2d%% → 交 %2d%%）支=%s 这张自己的跨=%s".format(
        names[i].take(42), rows[i][9], rows[i][7], rows[i][8], brs[i],
        if (rows[i][10] == 0) "—" else "%d".format(rows[i][10])))  }
}
