package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import java.util.Locale

/**
 * 只读诊断：**「按四角取样」与「沿整圈周长」差多少** —— 主人 2026-10-17 23:2x 指的方向。
 *
 * 主人的话（原意）：应用最终只给一张合并图，不少国产应用乱分层或干脆不分层，所以**底盘得我们
 * 自己判**；底盘甚至可能有很多块；于是只能看**接触到图标最外圈的同色像素的面积**与
 * **图标最外圈周长**；还要考虑渐变底。
 *
 * 生产现在的做法是 [MonochromeMask.cornerFamilies]：从**四个角**各取一个 `2×band` 的窗口
 * 量中位色，去重后最多 4 族。它天生看不见"不落在角上的底盘"（左右两条边各一块、上边一条带、
 * 底部一块渐变），也看不见"角上被图形占了、底盘从边上绕过去"的情况。
 *
 * 本台子只回答一件事：**环上真实的颜色构成是什么，四角取样能覆盖多少。**
 *   · `环长` = 内容框边界带里**实心**像素的个数（透明的不算，那是留白）；
 *   · 沿周长按 ΔE≤[TOL] 归族（4 连通、带内走一圈），报族数与各族占比；
 *   · `角覆盖` = 四角窗口的中位色能盖住的环上比例（用生产的 `CANDIDATE_DISTINCT` 做去重）；
 *   · `渐变` = 环上颜色沿周长的**累计漂移**（首尾色差小、途中大 ⇒ 渐变；首尾大 ⇒ 多色块）。
 *
 * ⚠ 它**不重演**生产的判据（那会是一份镜像）；族聚类只是为了量"环上到底有几种颜色"。
 * 用法：`sh run.sh harness.RimKt rec_model [--top=N]`
 */
object RimKt {
  /** 与生产 `CANDIDATE_DISTINCT` 同值（ΔE76 真值）。 */
  private const val DISTINCT = 18
  /** 环上归族的容差：取生产 `TOL_COARSE` 的一半，先看"最粗的几族"。 */
  private const val TOL = 9

  private fun lab(p: Int): Triple<Int, Int, Int> {
    // ⚠ **不要 `and 0xffff`**。`LabChk` 现量：黑 L=0、白 L=6400、蓝 b=**−6921** ——
    //   a/b 是带符号的 ×64，掩码成无符号之后"跨零"的比较会把 b 差算成 54312 而不是 11224
    //   （ΔE 从 175 变 848）。生产的 `deltaE` 调用点全都是 `and 0xffff` 的写法，
    //   这条单独记进 devlog（改它等于动所有阈值，不能顺手改）。
    return Triple(MonochromeMask.labChannel(p, 0), MonochromeMask.labChannel(p, 1),
      MonochromeMask.labChannel(p, 2))
  }

  private fun dE(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
    val dl = (a.first - b.first) / 64.0
    val da = (a.second - b.second) / 64.0
    val db = (a.third - b.third) / 64.0
    return Math.sqrt(dl * dl + da * da + db * db).toInt()
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val top = args.firstOrNull { it.startsWith("--top=") }?.substring(6)?.toIntOrNull() ?: 99
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name }

    println("name\t环长\t族数\t最大族占比\t角覆盖\t环内段数\t渐变漂移\tbox")
    val rows = ArrayList<Triple<String, DoubleArray, Int>>()
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val w = img.width; val h = img.height
      val px = IntArray(w * h)
      img.getRGB(0, 0, w, h, px, 0, w)
      val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
      if (r.mask == null) continue
      // 内容框：与生产同一条口径（build 里那个 contentBounds），这里用**不透明像素的外接框**近似。
      var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
      for (y in 0 until h) for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) >= 255) {
          if (x < x0) x0 = x; if (x > x1) x1 = x
          if (y < y0) y0 = y; if (y > y1) y1 = y
        }
      }
      if (x1 < 0) continue
      val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
      val band = (minOf(bw, bh) / 24).coerceIn(2, 8)
      // 环上实心像素，按**沿周长顺时针**的顺序收集（上边→右边→下边→左边）
      val ring = ArrayList<Int>()
      for (x in x0..x1) for (y in y0 until minOf(y0 + band, y1 + 1)) {
        val i = y * w + x; if ((px[i] ushr 24) >= 255) ring.add(i)
      }
      for (y in y0..y1) for (x in maxOf(x1 - band + 1, x0)..x1) {
        val i = y * w + x; if ((px[i] ushr 24) >= 255) ring.add(i)
      }
      for (x in x1 downTo x0) for (y in maxOf(y1 - band + 1, y0)..y1) {
        val i = y * w + x; if ((px[i] ushr 24) >= 255) ring.add(i)
      }
      for (y in y1 downTo y0) for (x in x0 until minOf(x0 + band, x1 + 1)) {
        val i = y * w + x; if ((px[i] ushr 24) >= 255) ring.add(i)
      }
      if (ring.size < 40) continue

      // 归族：按出现顺序，与任一已建族的代表色 ΔE≤TOL 就并进去
      val fams = ArrayList<Triple<Int, Int, Int>>()
      val famN = ArrayList<Int>()
      for (i in ring) {
        val c = lab(px[i])
        var hit = -1
        for (k in fams.indices) if (dE(c, fams[k]) <= TOL) { hit = k; break }
        if (hit < 0) { fams.add(c); famN.add(1) } else famN[hit] = famN[hit] + 1
      }
      val maxShare = famN.max().toDouble() / ring.size

      // 角覆盖：四个角各取 2*band 窗口的中位色（近似生产），看去重后能盖住环上多少
      // ⚠ **必须像生产那样"往里走"**：圆角/圆形图标的内容框角上本来就是空气，钉在角点上
      //   会量出"四个角都没有不透明的底"—— 第一版就是这么把角覆盖全量成 0.00 的。
      val win = (band * 2).coerceAtMost(minOf(bw, bh))
      val limit = minOf(bw, bh) / 3
      val corners = ArrayList<Triple<Int, Int, Int>>()
      for (cy in 0..1) for (cx in 0..1) {
        var c: Triple<Int, Int, Int>? = null
        var step = 0
        while (step <= limit && c == null) {
          val cx0 = (if (cx == 0) x0 + step else x1 - win + 1 - step).coerceIn(x0, x1 - win + 1)
          val cy0 = (if (cy == 0) y0 + step else y1 - win + 1 - step).coerceIn(y0, y1 - win + 1)
          val rs = IntArray(win * win); val gs = IntArray(win * win); val bs = IntArray(win * win)
          var n = 0
          for (y in cy0 until cy0 + win) for (x in cx0 until cx0 + win) {
            val i = y * w + x
            if ((px[i] ushr 24) < 255) continue
            rs[n] = (px[i] shr 16) and 0xff; gs[n] = (px[i] shr 8) and 0xff; bs[n] = px[i] and 0xff; n++
          }
          if (n * 2 >= win * win) {
            val mid = IntArray(3)
            for ((k, arr) in listOf(rs, gs, bs).withIndex()) {
              val a = arr.copyOf(n).sorted(); mid[k] = a[n / 2]
            }
            c = lab((0xff shl 24) or (mid[0] shl 16) or (mid[1] shl 8) or mid[2])
          }
          step++
        }
        if (c != null && corners.none { dE(c, it) <= DISTINCT }) corners.add(c)
      }
      var covered = 0
      for (i in ring) {
        val c = lab(px[i])
        if (corners.any { dE(c, it) <= DISTINCT }) covered++
      }
      val cornerCover = covered.toDouble() / ring.size

      // 环内段数：同一个族在环上被别的族隔开的次数（≈ 底盘有几块）
      var segs = 1
      for (k in 1 until ring.size) {
        val a = lab(px[ring[k]]); val b = lab(px[ring[k - 1]])
        if (dE(a, b) > TOL) segs++
      }
      // 渐变漂移：首尾色差 vs 途中最大单步色差
      var maxStep = 0
      for (k in 1 until ring.size) maxStep = maxOf(maxStep, dE(lab(px[ring[k]]), lab(px[ring[k - 1]])))
      // **环上跨度**：环上每个颜色到"环中位色"的最大 ΔE。判渐变靠的是这一对：
      //   跨度小 → 单色盘；跨度大而**单步**小 → 渐变盘（颜色是连续漂过去的）；
      //   跨度大且单步也大 → 多色块 / 插画（环上本来就没有主导色）。
      val medC = medianRingColor(px, ring, w)
      var span = 0
      for (i in ring) span = maxOf(span, dE(lab(px[i]), medC))
      val cls = when {
        span <= DISTINCT -> "单色盘"
        maxStep <= 9 -> "渐变盘"
        else -> "多色"
      }
      println(String.format(Locale.ROOT, "%s\t%d\t%d\t%.2f\t%.2f\t%d\t%d\t%d\t%s\t%dx%d",
        f.nameWithoutExtension, ring.size, fams.size, maxShare, cornerCover, segs, span, maxStep, cls, bw, bh))
      rows.add(Triple(f.nameWithoutExtension,
        doubleArrayOf(fams.size.toDouble(), maxShare, cornerCover, segs.toDouble(), span.toDouble(), maxStep.toDouble()), ring.size))
    }
    if (rows.isEmpty()) return
    val med = { k: Int -> rows.map { it.second[k] }.sorted()[rows.size / 2] }
    println(String.format(Locale.ROOT,
      "# n=%d 中位：族数 %.0f 最大族占比 %.2f 角覆盖 %.2f 段数 %.0f 环跨度 %.0f 单步 %.0f",
      rows.size, med(0), med(1), med(2), med(3), med(4), med(5)))
    val weak = rows.filter { it.second[2] < 0.9 }.sortedBy { it.second[2] }
    println("# 角覆盖 <90% 的：${weak.size} 张")
    for (t in weak.take(top)) println(String.format(Locale.ROOT,
      "   %-48s 族 %.0f 最大族 %.2f 角覆盖 %.2f 段 %.0f 跨度 %.0f 单步 %.0f",
      t.first, t.second[0], t.second[1], t.second[2], t.second[3], t.second[4], t.second[5]))
  }

  /** 环上像素的逐通道中位色（与生产的 `medianColor` 同一口径）。 */
  private fun medianRingColor(px: IntArray, ring: List<Int>, w: Int): Triple<Int, Int, Int> {
    val rs = IntArray(ring.size); val gs = IntArray(ring.size); val bs = IntArray(ring.size)
    for ((k, i) in ring.withIndex()) {
      rs[k] = (px[i] shr 16) and 0xff; gs[k] = (px[i] shr 8) and 0xff; bs[k] = px[i] and 0xff
    }
    rs.sort(); gs.sort(); bs.sort()
    val n = ring.size
    return lab((0xff shl 24) or (rs[n / 2] shl 16) or (gs[n / 2] shl 8) or bs[n / 2])
  }
}
