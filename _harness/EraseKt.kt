package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **笔画被擦断 / 整条擦掉**（只量不改）。主人点名的坏法是「整条消失 / 断开」。
 *
 * ⚠ 为什么不能用"模型认作图形的格"当分母（`StrokeKt` 那张表就是这么废的）：
 *   **一条被当成底板擦掉的线，根本不在那个分母里。** 它报"细线均浓 228、只抹掉 4%"，
 *   证的是"活下来的线不淡"，证不了"没有线被整条吃掉"。
 *
 * 所以这里换一个**不需要模型先承认**的判据：一个源里实心的格子，
 *  **沿某个方向两侧都是交付出去的墨**（±1 或 ±2 处都命中），而它自己出墨 0
 *  → 它就是**一条笔画中间被擦掉的那一截**。把它周围的这种格子连成块，一块 = 一处断口；
 *  块越大 = 断得越长。再补一刀：把这些格子**填回去**，数墨的连通块少了几块 ——
 *  少一块就是"这一处本来是一整条，被擦成两截"。
 *
 * 跑法：`harness.EraseKtKt [目录] [--edges=96,192,384]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "96,192,384")
    .split(",").map { it.trim().toInt() }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  for (edge in edges) {
    val rows = ArrayList<ERow>()
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
      val n = edge * edge
      val solid = BooleanArray(n) { (src[it] ushr 24) >= 250 }
      val ink = BooleanArray(n) { (mask[it] ushr 24) >= 128 }
      var inkN = 0
      for (v in ink) if (v) inkN++
      if (inkN < 400) continue
      val DX = intArrayOf(1, 0, 1, 1); val DY = intArrayOf(0, 1, 1, -1)
      // ⚠ 第一版只问"两侧是不是墨"，结果联系人报出 1619 格"断口"、最长一条 485 格 —— 那根本不是
      //   断掉的笔画，那是**人像与信封之间那块蓝盘子**：它源里实心、两侧都是墨、出墨 0，**全对**，
      //   可它本来就该是 0。所以再加一条：**这一格得和两侧那两截墨是同一种料**（差 ≤25）才算
      //   "笔画被擦断"。盘子透过来的是盘子色，跟笔画差得远，一条都不会漏进来。
      //   （这里用 RGB 欧氏距离近似，探针够用；不是模型那套 Lab。）
      val lab = IntArray(n * 3)
      for (i in 0 until n) {
        val c = src[i]
        lab[i * 3] = (c shr 16 and 0xff) * 64
        lab[i * 3 + 1] = (c shr 8 and 0xff) * 64
        lab[i * 3 + 2] = (c and 0xff) * 64
      }
      val cand = BooleanArray(n)
      for (y in 0 until edge) for (x in 0 until edge) {
        val i = y * edge + x
        if (!solid[i] || ink[i]) continue
        for (d in 0 until 4) {
          val dx = DX[d]; val dy = DY[d]
          var hit = false
          for (k in 1..2) {
            val xa = x - dx * k; val ya = y - dy * k; val xb = x + dx * k; val yb = y + dy * k
            if (xa < 0 || ya < 0 || xb < 0 || yb < 0 || xa >= edge || ya >= edge ||
              xb >= edge || yb >= edge
            ) continue
            val j1 = ya * edge + xa; val j2 = yb * edge + xb
            if (!ink[j1] || !ink[j2]) continue
            if (de2(i, j1, lab) <= 25 && de2(i, j2, lab) <= 25) { hit = true; break }
          }
          if (hit) { cand[i] = true; break }
        }
      }
      var cells = 0
      for (v in cand) if (v) cells++
      if (cells < 8) continue
      // 断口成块（8 邻接），并数"填回去能让几对墨并成一条"
      val seen = BooleanArray(n)
      val q = IntArray(n); var comps = 0; var longest = 0; var big = 0
      for (i in 0 until n) {
        if (!cand[i] || seen[i]) continue
        comps++
        var head = 0; var tail = 0; q[tail++] = i; seen[i] = true; var size = 0
        while (head < tail) {
          val c = q[head++]; size++
          val cx = c % edge; val cy = c / edge
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (cand[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
          }
        }
        if (size > longest) longest = size
        if (size >= 16) big++
      }
      val before = components(ink, edge)
      val merged = BooleanArray(n) { ink[it] || cand[it] }
      val after = components(merged, edge)
      rows += ERow(f.name, cells, comps, big, longest, before - after, inkN)
    }
    rows.sortByDescending { it.cells }
    println("=== ${rows.size} / ${files.size} 张有「两侧是墨、自己是 0」的笔画格 · edge=$edge ===")
    println("%-46s %7s %6s %6s %6s %8s".format("图标", "断口格", "处数", "≥16格", "最长", "填回并块"))
    rows.take(12).forEach { println(it.line()) }
    fun med(g: (ERow) -> Int) = if (rows.isEmpty()) 0 else rows.map(g).sorted()[rows.size / 2]
    println("中位：断口格 ${med { it.cells }}   处数 ${med { it.comps }}   填回并块 ${med { it.merge }}   " +
      "（有断口的张数 ${rows.size}/${files.size}）")
    println("填回后墨的连通块变少（= 这一处**确实把一条笔画擦成两截**）的张数 = ${rows.count { it.merge > 0 }}" +
      "   其中 ≥3 块的 = ${rows.count { it.merge >= 3 }}")
    println()
  }
}

private fun de2(i: Int, j: Int, lab: IntArray): Int {
  val a = i * 3; val b = j * 3
  val dr = lab[a] - lab[b]; val dg = lab[a + 1] - lab[b + 1]; val db = lab[a + 2] - lab[b + 2]
  return Math.sqrt(
    (dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db).toDouble()
  ).toInt() shr 6
}

/** 8 邻接连通块数。 */private fun components(v: BooleanArray, edge: Int): Int {
  val n = v.size
  val seen = BooleanArray(n)
  val q = IntArray(n)
  var c = 0
  for (i in 0 until n) {
    if (!v[i] || seen[i]) continue
    c++
    var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
    while (head < tail) {
      val x = q[head++] % edge; val y = q[head - 1] / edge
      for (oy in -1..1) for (ox in -1..1) {
        if (ox == 0 && oy == 0) continue
        val nx = x + ox; val ny = y + oy
        if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
        val j = ny * edge + nx
        if (v[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
      }
    }
  }
  return c
}

private class ERow(val name: String, val cells: Int, val comps: Int, val big: Int,
                   val longest: Int, val merge: Int, val ink: Int) {
  fun line() = "%-46s %7d %6d %6d %6d %8d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(46), cells, comps, big, longest, merge)
}
