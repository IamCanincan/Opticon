package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **源图里分开的两块，交付后被同一块墨连起来了没有**（只量不改）。
 *
 * 主人报"图形有粘连"。今天那几笔（发档、补带、小岛豁免）**方向全是"连成一片"**，
 * 所以第一件要分清的是：**是我弄的，还是本来就在。**
 *
 * 判据不靠"看着像不像"：
 *  1. 在**源图**里按粗色桶取连通块（8 邻接、面积 ≥ 框的 0.4%）—— 这是"设计者画了几个分开的形状"；
 *  2. 其中交付均值 ≥128 的算"该有墨的形状"；
 *  3. 在**交付的墨**（α≥128）里再取连通块；
 *  4. 每一块交付连通块**盖住了几个第 2 步的形状** —— **≥2 就是粘连**，
 *     并记下这两个形状的**质心距离**（挨着粘 vs 隔着老远粘，是两种不同的病）。
 *
 * 用法：`harness.GlueKtKt [目录] [--edges=384] [--min=0.4]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val MIN_PERMILLE = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toDouble() ?: 0.4
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<GRow>()
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val sw = img.width; val sh = img.height
      val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
      val px = IntArray(edge * edge)
      for (y in 0 until edge) for (x in 0 until edge) {
        val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
        val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
        px[y * edge + x] = s0[sy * sw + sx]
      }
      val out = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val m = out.mask ?: continue
      val s = pool.get() ?: continue
      val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
      val labB = cf(s, "labB") as ShortArray
      val n = edge * edge
      val minArea = (n * MIN_PERMILLE / 1000.0).toInt().coerceAtLeast(64)
      val bk = IntArray(n)
      for (i in 0 until n) {
        val l = (labL[i].toInt() and 0xffff) shr 6
        val a = ((labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val b = ((labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
        bk[i] = ((l / 8) * 8 + (a / 32)) * 8 + (b / 32)
      }
      val q = IntArray(n)
      // 1+2：源图里分开的形状，以及它们各自交付了多少
      val shapeOf = IntArray(n) { -1 }
      val cx = ArrayList<Double>(); val cy = ArrayList<Double>(); val inked = ArrayList<Boolean>()
      var sid = 0
      for (i in 0 until n) {
        if (shapeOf[i] >= 0 || (px[i] ushr 24) < 250) continue
        val my = bk[i]
        var head = 0; var tail = 0; q[tail++] = i; shapeOf[i] = sid
        var sx = 0L; var sy = 0L; var asum = 0L
        while (head < tail) {
          val c = q[head++]; sx += c % edge; sy += c / edge; asum += (m[c] ushr 24)
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = c % edge + ox; val ny = c / edge + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (shapeOf[j] < 0 && (px[j] ushr 24) >= 250 && bk[j] == my) { shapeOf[j] = sid; q[tail++] = j }
          }
        }
        if (tail < minArea) continue
        cx += sx.toDouble() / tail; cy += sy.toDouble() / tail; inked += (asum / tail) >= 128
        sid++
      }
      val shapes = ArrayList<Int>()
      for (k in inked.indices) if (inked[k]) shapes += k
      if (shapes.size < 2) continue
      // 3：交付的墨的连通块；4：每块盖住几个源形状
      val seen = BooleanArray(n)
      var worst = 0; var worstGap = 0.0; var comps = 0
      for (i in 0 until n) {
        if (seen[i] || (m[i] ushr 24) < 128) continue
        var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
        val hit = HashSet<Int>()
        while (head < tail) {
          val c = q[head++]
          if (shapeOf[c] >= 0) hit += shapeOf[c]
          val ccx = c % edge; val ccy = c / edge
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = ccx + ox; val ny = ccy + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (!seen[j] && (m[j] ushr 24) >= 128) { seen[j] = true; q[tail++] = j }
          }
        }
        if (tail < minArea) continue
        comps++
        val covered = hit.intersect(shapes.toSet())
        if (covered.size >= 2) {
          val ids = covered.toList()
          var gap = 0.0
          for (u in ids.indices) for (v in u + 1 until ids.size) {
            val d = Math.sqrt(
              (cx[ids[u]] - cx[ids[v]]) * (cx[ids[u]] - cx[ids[v]]) +
                (cy[ids[u]] - cy[ids[v]]) * (cy[ids[u]] - cy[ids[v]])
            )
            if (d > gap) gap = d
          }
          if (covered.size > worst || (covered.size == worst && gap > worstGap)) { worst = covered.size; worstGap = gap }
        }
      }
      if (worst >= 2) rows += GRow(f.name, shapes.size, comps, worst, worstGap, edge)
    }
    rows.sortByDescending { it.glued }
    println("=== edge=$edge · 有粘连的图 ${rows.size} / ${files.size} 张 ===")
    println("%-40s %7s %7s %7s %9s".format("图标", "源形状", "墨块", "粘成一块的", "跨距px"))
    rows.take((args.firstOrNull { it.startsWith("--take=") }?.substringAfter("=") ?: "14").toInt()).forEach { println(it.line()) }
    println("粘 ≥3 个源形状成一块的 = ${rows.count { it.glued >= 3 }} 张   " +
      "跨距 ≥40px（隔老远粘上）的 = ${rows.count { it.gap >= 40 }} 张")
    println("GLUE n=${rows.size} worst3=${rows.count { it.glued >= 3 }} far=${rows.count { it.gap >= 40 }}")
    println()
  }
}

private class GRow(val name: String, val shapes: Int, val comps: Int, val glued: Int,
                   val gap: Double, val edge: Int) {
  fun line() = "%-40s %7d %7d %7d %8.0f".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(40), shapes, comps, glued, gap)
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
