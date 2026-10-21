package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import java.util.Locale

/**
 * 只读诊断：**按"图标最外周"（不透明轮廓）量底盘，拿已经算对的当参照。**
 *
 * 主人 2026-10-17 23:5x 的口径：
 *  · 「拿现有已算好的图标来做参考」—— 点名 6 张坏的（browndust2 / fbclient / sgameGlobal /
 *    feimo / kazumi / tmgp.sgame），其余视为没问题 ⇒ 这是一个**有标签的样本**，可以直接找分界；
 *  · 「渐变多的可以放宽色度」⇒ 渐变与多色要分开判，不能一把尺；
 *  · 「一定要做到全覆盖，包括未安装的那些应用」⇒ 判据不能只对语料里这 62 张成立；
 *  · 「同时要考虑裁圆算法，了解图标最外周是什么」⇒ **环取不透明轮廓，不是外接框**
 *    （圆角/圆形图标的外接框角上是空气，按框量会把"空气"当成底的一部分）。
 *
 * 本台子报的量（都只在**不透明轮廓那一圈**上算）：
 *  · `环长` 轮廓像素数；
 *  · `族` 与 `最大族占比` 按 ΔE≤[TOL] 归族；
 *  · `跨度` = 环上每个颜色到环中位色的最大 ΔE（单色盘小、渐变与插画大）；
 *  · `单步` = 环上每个像素到它**相邻不透明像素**的最大 ΔE（渐变连续 ⇒ 小；插画跳变 ⇒ 大）。
 *
 * 用法：`sh run.sh harness.RimRefKt rec_model`
 */
object RimRefKt {
  private const val TOL = 9
  private const val SPAN_SOLID = 18
  private const val STEP_SMOOTH = 12

  /** 主人点名的 6 张（"目前看来其他都没问题"）。⚠ `com.fbclient.app` 不在语料里（后装的），
   *  所以这一轮只有 5 张坏样本 —— 别把它当"6 张都验过了"。 */
  private val BAD = listOf("com.neowizgames.game.browndust2", "com.fbclient.app",
    "com.levelinfinite.sgameGlobal", "com.feimo.astralparty", "com.predidit.kazumi",
    "com.tencent.tmgp.sgame")

  private fun lab(p: Int) = Triple(MonochromeMask.labChannel(p, 0), MonochromeMask.labChannel(p, 1),
    MonochromeMask.labChannel(p, 2))

  private fun dE(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
    val dl = (a.first - b.first) / 64.0
    val da = (a.second - b.second) / 64.0
    val db = (a.third - b.third) / 64.0
    return Math.sqrt(dl * dl + da * da + db * db).toInt()
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val dbg = args.firstOrNull { it.startsWith("--dbg=") }?.substring(6)
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.sortedBy { it.name }

    println("name\tlabel\t环长\t族\t最大族占比\t跨度\t单步\t类\t墨占画布")
    val rows = ArrayList<Triple<String, Boolean, DoubleArray>>()
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val w = img.width; val h = img.height
      val px = IntArray(w * h)
      img.getRGB(0, 0, w, h, px, 0, w)
      val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      val bad = BAD.any { f.name.startsWith(it) }

      // ── 最外周 = 不透明轮廓：α==255 且四个邻居里有非不透明（或出界）的格 ──
      val ring = ArrayList<Int>()
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if ((px[i] ushr 24) != 255) continue
        var edge = false
        if (x == 0 || (px[i - 1] ushr 24) != 255) edge = true
        if (!edge && (x == w - 1 || (px[i + 1] ushr 24) != 255)) edge = true
        if (!edge && (y == 0 || (px[i - w] ushr 24) != 255)) edge = true
        if (!edge && (y == h - 1 || (px[i + w] ushr 24) != 255)) edge = true
        if (edge) ring.add(i)
      }
      if (ring.size < 60) continue

      val fams = ArrayList<Triple<Int, Int, Int>>()
      val famN = ArrayList<Int>()
      for (i in ring) {
        val c = lab(px[i])
        var hit = -1
        for (k in fams.indices) if (dE(c, fams[k]) <= TOL) { hit = k; break }
        if (hit < 0) { fams.add(c); famN.add(1) } else famN[hit] = famN[hit] + 1
      }
      val maxShare = famN.max().toDouble() / ring.size
      // **最大族在整张画布上的面积** —— 主人原话是"同色像素的**面积**和外圈周长"，两半都要。
      //   一块真底盘：环上占比高 **且** 面积大；插画的"环色"只是描边那一圈，面积很小。
      val domIdx = famN.indices.maxByOrNull { famN[it] } ?: 0
      val domC = fams[domIdx]
      var domArea = 0
      var solidAll = 0
      for (i in 0 until w * h) {
        if ((px[i] ushr 24) != 255) continue
        solidAll++
        if (dE(lab(px[i]), domC) <= TOL) domArea++
      }

      // 环中位色（逐通道）
      val rs = IntArray(ring.size); val gs = IntArray(ring.size); val bs = IntArray(ring.size)
      for ((k, i) in ring.withIndex()) {
        rs[k] = (px[i] shr 16) and 0xff; gs[k] = (px[i] shr 8) and 0xff; bs[k] = px[i] and 0xff
      }
      rs.sort(); gs.sort(); bs.sort()
      val n = ring.size
      val medC = lab((0xff shl 24) or (rs[n / 2] shl 16) or (gs[n / 2] shl 8) or bs[n / 2])
      var span = 0
      for (i in ring) span = maxOf(span, dE(lab(px[i]), medC))

      // 单步：环上每个像素到它相邻**不透明**像素的最大色差（4 邻域）
      var step = 0
      for (i in ring) {
        val y = i / w; val x = i - y * w
        val ci = lab(px[i])
        if (x > 0 && (px[i - 1] ushr 24) == 255) step = maxOf(step, dE(ci, lab(px[i - 1])))
        if (x < w - 1 && (px[i + 1] ushr 24) == 255) step = maxOf(step, dE(ci, lab(px[i + 1])))
        if (y > 0 && (px[i - w] ushr 24) == 255) step = maxOf(step, dE(ci, lab(px[i - w])))
        if (y < h - 1 && (px[i + w] ushr 24) == 255) step = maxOf(step, dE(ci, lab(px[i + w])))
      }

      var ink = 0
      for (i in 0 until w * h) if ((mask[i] ushr 24) > 0) ink++
      val cls = when {
        span <= SPAN_SOLID -> "单色盘"
        step <= STEP_SMOOTH -> "渐变盘"
        else -> "多色"
      }
      if (dbg != null && f.name.startsWith(dbg)) {
        fun lab3(c: Triple<Int, Int, Int>) = "(${c.first / 64},${c.second / 64},${c.third / 64})"
        println("DBG ${f.nameWithoutExtension}")
        println("  主族 ${lab3(domC)} 环占 ${String.format(Locale.ROOT, "%.2f", maxShare)}" +
          " 面积 ${String.format(Locale.ROOT, "%.1f", if (solidAll == 0) 0.0 else domArea * 100.0 / solidAll)}%" +
          " 跨度 $span 单步 $step 类 $cls 墨占 ${String.format(Locale.ROOT, "%.1f", ink * 100.0 / (w * h))}%")
        run {
          var near = 0; var nearInk = 0
          for (i in 0 until w * h) {
            if ((px[i] ushr 24) != 255) continue
            if (dE(lab(px[i]), domC) > TOL) continue
            near++
            if ((mask[i] ushr 24) > 0) nearInk++
          }
          println("  主族色的格：$near（占实心 " + String.format(Locale.ROOT, "%.1f", near * 100.0 / solidAll) +
            "%），其中**交付成墨**的 $nearInk（" + String.format(Locale.ROOT, "%.1f", nearInk * 100.0 / solidAll) + "% 画布）")
        }
        // 整张不透明区域的族分布（容差 9 与 18 各一遍）：看"头发"是不是被拆成了两三个族
        for (tol in intArrayOf(9, 18)) {
          val f2 = ArrayList<Triple<Int, Int, Int>>()
          val n2 = ArrayList<Int>()
          for (i in 0 until w * h) {
            if ((px[i] ushr 24) != 255) continue
            val c = lab(px[i])
            var hit = -1
            for (k in f2.indices) if (dE(c, f2[k]) <= tol) { hit = k; break }
            if (hit < 0) { f2.add(c); n2.add(1) } else n2[hit] = n2[hit] + 1
          }
          val top = n2.indices.sortedByDescending { n2[it] }.take(5)
          println("  tol=$tol 族数 ${f2.size}；前 5：" + top.joinToString(" ") {
            "(${f2[it].first / 64},${f2[it].second / 64},${f2[it].third / 64})=" +
              String.format(Locale.ROOT, "%.1f%%", n2[it] * 100.0 / solidAll)
          })
        }
      }
      println(String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%.2f\t%.2f\t%d\t%d\t%s\t%.1f",
        f.nameWithoutExtension, if (bad) "坏" else "好", n, fams.size, maxShare,
        if (solidAll == 0) 0.0 else domArea * 100.0 / solidAll, span, step, cls,
        ink * 100.0 / (w * h)))
      rows.add(Triple(f.nameWithoutExtension, bad, doubleArrayOf(fams.size.toDouble(), maxShare,
        span.toDouble(), step.toDouble(), ink * 100.0 / (w * h),
        if (solidAll == 0) 0.0 else domArea * 100.0 / solidAll)))
    }

    for ((label, bad) in listOf("好" to false, "坏" to true)) {
      val xs = rows.filter { it.second == bad }
      if (xs.isEmpty()) continue
      println("\n== $label ${xs.size} 张")
      for (k in 0..5) {
        val v = xs.map { it.third[k] }.sorted()
        println(String.format(Locale.ROOT, "   %s  min %.1f  中位 %.1f  max %.1f",
          listOf("族数", "最大族占比", "跨度", "单步", "墨占画布", "最大族面积%")[k],
          v.first(), v[v.size / 2], v.last()))
      }
    }
  }
}
