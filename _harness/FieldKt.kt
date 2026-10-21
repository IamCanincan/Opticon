package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **有多少图标，"底"其实被候选物自己污染了**（只量不改）。
 *
 * globalspeed 右上角那个缺口的机制不是"阈值差 1.6"那么简单：那几格**离自己的参照只差 3**
 * （参照 L=22、它自己 L=19.8）—— 提供参照的那个色格子**被续弧自己的像素占满了**，
 * 于是"续弧成了自己的底"。这条如果常见，就值得动参照机器；如果不常见，就是又一张已知边界。
 *
 * 量法：拿**内容框边界那一圈**的中位色当"真底"（这是模型自己认底的口径，见 cornerFamilies），
 * 然后看每个被判成底的实心格离这个真底有多远：
 *  · `≥10` 的占多少 —— 底里有一大块其实不是背景色；
 *  · 这些格连成的**最大一块**有多大 —— 一小片是抗锯齿，一大块才是"被认成底的图形"。
 *
 * 用法：`harness.FieldKtKt [目录] [--edges=384] [--de=10]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val DE = args.firstOrNull { it.startsWith("--de=") }?.substringAfter("=")?.toInt() ?: 10
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<FRow>()
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
      val s = pool.get() ?: continue
      val plate = cf(s, "plate") as ByteArray
      val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
      val labB = cf(s, "labB") as ShortArray
      val n = edge * edge
      // 真底 = 内容框边界一圈里、被判成底的那些格的**中位色**
      val ring = ArrayList<Int>()
      for (i in 0 until n) {
        val x = i % edge; val y = i / edge
        if ((x == 0 || y == 0 || x == edge - 1 || y == edge - 1) &&
          plate[i] != 0.toByte() && (px[i] ushr 24) >= 250
        ) ring += i
      }
      if (ring.size < 64) continue
      val ml = ring.map { labL[it].toInt() and 0xffff }.sorted()[ring.size / 2]
      val ma = ring.map { labA[it].toInt() }.sorted()[ring.size / 2]
      val mb = ring.map { labB[it].toInt() }.sorted()[ring.size / 2]
      val hot = BooleanArray(n)
      var groundN = 0
      for (i in 0 until n) {
        if (plate[i] == 0.toByte() || (px[i] ushr 24) < 250) continue
        groundN++
        val dl = ((labL[i].toInt() and 0xffff) - ml) / 64.0
        val da = (labA[i].toInt() - ma) / 64.0
        val db = (labB[i].toInt() - mb) / 64.0
        if (sqrt(dl * dl + da * da + db * db) >= DE) hot[i] = true
      }
      var cnt = 0
      for (v in hot) if (v) cnt++
      if (cnt == 0) { rows += FRow(f.name, 0, 0, 0); continue }
      // 最大连通块
      val seen = BooleanArray(n); val q = IntArray(n)
      var best = 0
      for (i in 0 until n) {
        if (!hot[i] || seen[i]) continue
        var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
        while (head < tail) {
          val c = q[head++]; val cx = c % edge; val cy = c / edge
          for (k in 0 until 4) {
            val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (hot[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
          }
        }
        if (tail > best) best = tail
      }
      rows += FRow(f.name, cnt, best, groundN)
    }
    rows.sortByDescending { it.blob }
    println("=== edge=$edge · ${rows.size} 张 · 底侧但离真底 ≥$DE 的格 ===")
    println("%-44s %8s %7s %6s".format("图标", "格数", "最大块", "占底"))
    rows.take(14).forEach { println(it.line()) }
    val share = rows.map { it.cells * 100 / maxOf(1, it.ground) }.sorted()
    val bl = rows.map { it.blob }.sorted()
    println("占底 中位 ${share[share.size / 2]}%  p90 ${share[share.size * 9 / 10]}%   " +
      "最大块 中位 ${bl[bl.size / 2]}  p90 ${bl[bl.size * 9 / 10]}")
    println("有一大块（≥框 1%）被认成底的图 = ${rows.count { it.blob >= edge * edge / 100 }} 张   " +
      "≥5% 的 = ${rows.count { it.blob >= edge * edge * 5 / 100 }} 张")
    println()
  }
}

private class FRow(val name: String, val cells: Int, val blob: Int, val ground: Int) {
  fun line() = "%-44s %8d %7d %5.1f%%".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(44), cells, blob,
    cells * 100.0 / maxOf(1, ground))
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
