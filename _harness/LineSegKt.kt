package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * **按线条分割**（主人 2026-10-18：「按照线条来分」）。
 *
 * 思路：线稿里的暗线就是分界线 —— 先把线找出来当墙，再对剩下的像素做连通分量。
 * 这样「头发」和「脸」即使颜色连续（kazumi 的 40–49 与 50–59 只差 1~10），只要中间有一条线，
 * 就会被切成两块。然后按主人的判据（**贴外圈的弧长 + 面积**）挑出哪一块是底。
 *
 * 线怎么找：**黑帽（black-hat）** —— `3x3 邻域最亮 - 自己 > T`。这一条对渐变天然免疫
 * （平滑渐变里邻域最亮与自己几乎相同），只对「比周围明显暗的细线」响应，
 * 而且能吃到线的**整根**（不是只吃两侧边缘 —— 边缘梯度会让线的芯漏掉，4 邻接就顺着芯漏过去）。
 *
 * 用法：
 *   `sh run.sh harness.LineSegKt rec_model/<某张>.png [T]`     单张，打 ASCII 分区图 + 分量表
 *   `sh run.sh harness.LineSegKt rec_model [T]`                 全语料，每张一行
 */
object LineSegKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val target = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val T = args.getOrNull(1)?.toIntOrNull() ?: 18
    val cols = args.firstOrNull { it.startsWith("--cols=") }?.substring(7)?.toIntOrNull() ?: 40
    val outDir = args.firstOrNull { it.startsWith("--out=") }?.substring(6)?.let { File(it) }
    if (outDir != null) outDir.mkdirs()
    if (target.isDirectory) {
      val fs = target.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: return
      println("name\t实心\t分量\t最大块%\t最大块贴环\t最大块平均L\t次大块%\t次大块贴环\t次大块平均L\t线%")
      for (f in fs) seg(f, T, null)
    } else {
      seg(target, T, cols, outDir)
    }
  }

  private fun seg(f: File, T: Int, cols: Int?, outDir: File? = null) {
    val img = ImageIO.read(f) ?: return
    val w = img.width; val h = img.height
    val px = IntArray(w * h)
    img.getRGB(0, 0, w, h, px, 0, w)
    val n = w * h
    val L = IntArray(n) { MonochromeMask.labChannel(px[it], 0) / 64 }        // 0..100
    val op = BooleanArray(n) { (px[it] ushr 24) >= 128 }

    // ① 线 = 黑帽：3x3 邻域最亮 - 自己 > T
    val wall = BooleanArray(n)
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        val i = row + x
        if (!op[i]) { wall[i] = true; continue }
        var mx = L[i]
        for (dy in -1..1) {
          val yy = y + dy
          if (yy < 0 || yy >= h) continue
          for (dx in -1..1) {
            val xx = x + dx
            if (xx < 0 || xx >= w) continue
            val j = yy * w + xx
            if (op[j] && L[j] > mx) mx = L[j]
          }
        }
        wall[i] = mx - L[i] > T
      }
    }

    // ② 轮廓环：不透明且四邻有非不透明（全画布不透明的图就退化成画布边）
    val rim = BooleanArray(n)
    var rimN = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        val i = row + x
        if (!op[i]) continue
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
          !op[i - 1] || !op[i + 1] || !op[i - w] || !op[i + w]
        ) { rim[i] = true; rimN++ }
      }
    }

    // ③ 连通分量（4 邻接，跳过线墙）
    val lab = IntArray(n) { -1 }
    val q = IntArray(n)
    var nc = 0
    val area = ArrayList<Int>()
    val sumL = ArrayList<Long>()
    val rimHit = ArrayList<Int>()
    val sumR = ArrayList<Long>(); val sumG = ArrayList<Long>(); val sumB = ArrayList<Long>()
    for (s in 0 until n) {
      if (!op[s] || wall[s] || lab[s] >= 0) continue
      var head = 0; var tail = 0
      q[tail++] = s; lab[s] = nc
      var a = 0; var sl = 0L; var rh = 0; var sr = 0L; var sg = 0L; var sb = 0L
      while (head < tail) {
        val c = q[head++]
        a++; sl += L[c]
        sr += (px[c] shr 16) and 0xff; sg += (px[c] shr 8) and 0xff; sb += px[c] and 0xff
        if (rim[c]) rh++
        val cy = c / w; val cx = c - cy * w
        if (cx > 0) { val j = c - 1; if (op[j] && !wall[j] && lab[j] < 0) { lab[j] = nc; q[tail++] = j } }
        if (cx < w - 1) { val j = c + 1; if (op[j] && !wall[j] && lab[j] < 0) { lab[j] = nc; q[tail++] = j } }
        if (cy > 0) { val j = c - w; if (op[j] && !wall[j] && lab[j] < 0) { lab[j] = nc; q[tail++] = j } }
        if (cy < h - 1) { val j = c + w; if (op[j] && !wall[j] && lab[j] < 0) { lab[j] = nc; q[tail++] = j } }
      }
      area.add(a); sumL.add(sl); rimHit.add(rh); sumR.add(sr); sumG.add(sg); sumB.add(sb); nc++
    }
    var solid = 0
    var wallN = 0
    for (i in 0 until n) { if (op[i]) solid++; if (op[i] && wall[i]) wallN++ }
    val order = (0 until nc).sortedByDescending { area[it] }

    if (cols != null) {
      println("=== ${f.nameWithoutExtension}  ${w}x$h  T=$T  实心 $solid  线 $wallN(${pct(wallN, solid)})  分量 $nc")
      // **Lab L 最小池化**：每格取该格内最暗的那个像素。细线缩小时不会被平均掉 ——
      //   平均池化会把 1px 的线冲淡成看不见，那是"看着没线、其实有线"的经典假象。
      //   0..9 是 L/10，`A` = 线墙（黑帽命中）。
      println("--- Lab L 最小池化（0 最暗..9 最亮，A=线墙）")
      run {
        val rows = cols * h / w
        for (ry in 0 until rows) {
          val sb = StringBuilder()
          val y0 = ry * h / rows; val y1 = ((ry + 1) * h / rows).coerceAtLeast(y0 + 1)
          for (rx in 0 until cols) {
            val x0 = rx * w / cols; val x1 = ((rx + 1) * w / cols).coerceAtLeast(x0 + 1)
            var mn = 100; var anyWall = false
            for (y in y0 until y1) for (x in x0 until x1) {
              val i = y * w + x
              if (!op[i]) continue
              if (wall[i]) anyWall = true
              if (L[i] < mn) mn = L[i]
            }
            sb.append(when {
              mn == 100 -> '.'
              anyWall -> 'A'
              else -> ('0' + (mn / 10).coerceIn(0, 9))
            })
          }
          println(sb)
        }
      }
      println("--- 分区（同一字母=同一块；#=线；.=透明）")
      val rows = cols * h / w
      for (ry in 0 until rows) {
        val sb = StringBuilder()
        for (rx in 0 until cols) {
          val x = rx * w / cols; val y = ry * h / rows
          val i = y * w + x
          if (!op[i]) { sb.append('.'); continue }
          if (wall[i]) { sb.append('#'); continue }
          val c = lab[i]
          if (c < 0) { sb.append('?'); continue }
          val k = order.indexOf(c)
          sb.append(if (k < 26) ('A' + k) else ('a' + (k - 26) % 26))
        }
        println(sb)
      }
      println("--- 分量（按面积降序）")
      println(String.format(Locale.ROOT, "  %-3s %8s %7s %6s %8s %6s  %-8s", "块", "面积", "占实心", "平均L", "贴环格", "贴环%", "平均色"))
      for (k in 0 until minOf(14, order.size)) {
        val c = order[k]
        val n0 = maxOf(1, area[c])
        println(String.format(Locale.ROOT, "  %-3c %8d %6.1f%% %6.1f %8d %5.1f%%  #%02X%02X%02X",
          if (k < 26) ('A' + k) else 'z', area[c], pct(area[c], solid),
          sumL[c].toDouble() / n0, rimHit[c], pct(rimHit[c], maxOf(1, rimN)),
          (sumR[c] / n0).toInt(), (sumG[c] / n0).toInt(), (sumB[c] / n0).toInt()))
      }
      println("--- 报告")
      val rep0 = MonochromeMask.build(px, w, h)
      println("   " + rep0.report.take(320))
      if (outDir != null) export(outDir, f, px, w, h, rep0.mask, lab, wall, op,
        area, sumL, rimHit, order, rimN)
      return
    }

    // 全语料：一行一张
    run {
      val cur = MonochromeMask.build(px, w, h).mask
      val g = order.firstOrNull { rimHit[it] * 2 >= rimN }
      var inkAll = 0; var inkG = 0; var inkGL = 0L
      val hG = IntArray(10); val hAll = IntArray(10)
      if (cur != null) for (i in 0 until n) {
        if ((cur[i] ushr 24) == 0) continue
        inkAll++; hAll[(L[i] / 10).coerceIn(0, 9)]++
        if (g != null && lab[i] == g) { inkG++; inkGL += L[i]; hG[(L[i] / 10).coerceIn(0, 9)]++ }
      }
      println(String.format(Locale.ROOT, "G	%s	%d	%.1f	%.1f	%.3f	%.3f	%.1f	%s	%s",
        f.nameWithoutExtension, nc,
        if (g == null) -1.0 else pct(area[g], solid),
        if (g == null) -1.0 else sumL[g].toDouble() / area[g],
        inkG * 1.0 / n, inkAll * 1.0 / n,
        if (inkG == 0) -1.0 else inkGL.toDouble() / inkG,
        hG.joinToString(","), hAll.joinToString(",")))
      return
    }
    fun cell(k: Int): String {
      if (k >= order.size) return "-\t-\t-"
      val c = order[k]
      return String.format(Locale.ROOT, "%.1f\t%.2f\t%.0f",
        pct(area[c], solid), pct(rimHit[c], maxOf(1, rimN)), sumL[c].toDouble() / area[c])
    }
    println("${f.nameWithoutExtension}\t$solid\t$nc\t${cell(0)}\t${cell(1)}\t${String.format(Locale.ROOT, "%.1f", pct(wallN, solid))}")
  }

  /**
   * 把三个版本导成 PNG 给主人看（本会话看不了图，主人能看）。
   * 画法：白底 + 黑墨（alpha 越满越黑），这样一眼能认出形状。
   */
  private fun export(dir: File, f: File, px: IntArray, w: Int, h: Int, cur: IntArray?,
    lab: IntArray, wall: BooleanArray, op: BooleanArray,
    area: ArrayList<Int>, sumL: ArrayList<Long>, rimHit: ArrayList<Int>,
    order: List<Int>, rimN: Int) {
    val n = w * h
    if (cur == null) return
    // 底 = 贴环弧长占全环 ≥50% 的那一块（主人：「连接圆圈的弧线长」）
    val ground = order.firstOrNull { rimHit[it] * 2 >= rimN } ?: return
    val groundL = sumL[ground].toDouble() / area[ground]
    // V2 再放宽：凡是和底**一样亮**（L 不低于底 - 4）的块，也算底
    val groundSet = HashSet<Int>()
    groundSet.add(ground)
    for (c in order) {
      if (c == ground) continue
      if (sumL[c].toDouble() / area[c] >= groundL - 4) groundSet.add(c)
    }
    println("--- 底 = 块 " + ('A' + order.indexOf(ground)) + "（贴环 " +
      String.format(Locale.ROOT, "%.1f", pct(rimHit[ground], maxOf(1, rimN))) +
      "%，面积 " + String.format(Locale.ROOT, "%.1f", pct(area[ground], n)) +
      "%，L " + String.format(Locale.ROOT, "%.1f", groundL) + "）；V2 额外并进 " +
      (groundSet.size - 1) + " 块")
    // mode：0=不动、1=只杀"占着外圈那一块"、2=再并进所有和底一样亮的块
    fun write(tag: String, mode: Int) {
      fun killed(c: Int): Boolean = when (mode) {
        0 -> false
        1 -> c == ground
        else -> c >= 0 && groundSet.contains(c)
      }
      val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        var a = cur[i] ushr 24
        if (a > 0 && op[i] && killed(lab[i])) a = 0
        val g = 255 - a
        img.setRGB(x, y, (g shl 16) or (g shl 8) or g)
      }
      val o = File(dir, f.nameWithoutExtension + "_" + tag + ".png")
      ImageIO.write(img, "png", o)
      var ink = 0
      for (i in 0 until n) if ((cur[i] ushr 24) > 0 && !(op[i] && killed(lab[i]))) ink++
      println("   -> ${o.name}  上墨 ${String.format(Locale.ROOT, "%.3f", ink * 1.0 / n)}")
    }
    write("0cur", 0)
    write("1ground", 1)
    write("2ground+light", 2)
  }

  private fun pct(a: Int, b: Int): Double = if (b == 0) 0.0 else a * 100.0 / b
}
