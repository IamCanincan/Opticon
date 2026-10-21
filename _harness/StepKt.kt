package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **源图上连成一片的地方，交付出去跳了多少**（只量不改）。
 *
 * 主人说"有的图标颜色过渡很生硬"。今天那三笔改动**天生就是造这个的**：
 * `assignLevels` 把相邻两块**硬拉开 ≥30**（`d51d4d0` 之前是**每一对**都硬拉开），
 * `propagateBands` 把一条带**压成同一个平色**（`55a45b9`）—— 两者都是把斜坡改成台阶。
 * 所以这把尺子不问"好不好看"，只问一件事：**在源图自己连续的地方，我们跳了多少。**
 *
 * 判据：取所有四邻格对，**只保留源图上连续的那些**（两格 ΔE ≤ SRC_DE，即人眼看成同一片），
 * 然后数交付 alpha 的差：
 *  · `跳≥30 的对` 占多少 —— 30 是 `SEP_MIN_GAP`，也就是"看得见的一格"；
 *  · `跳≥60 的对` —— 两道格，屏幕上是一条硬边；
 *  · 以及这些跳的 p99 与最大值。
 * 源图连续、交付却跳 ≥60 的地方，就是"生硬"本身，不需要主观判断。
 *
 * 用法：`harness.StepKtKt [目录] [--edges=384] [--sourcede=6]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val SRC_DE = args.firstOrNull { it.startsWith("--sourcede=") }?.substringAfter("=")?.toInt() ?: 6
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<ARow>()
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
      var pairs = 0L; var j30 = 0L; var j60 = 0L; var j60edge = 0L; var worst = 0
      val hist = IntArray(257)
      for (y in 0 until edge) for (x in 0 until edge) {
        val i = y * edge + x
        if ((px[i] ushr 24) < 250) continue
        for (k in 0 until 2) {                       // 只数右与下，每对一次
          val nx = x + intArrayOf(1, 0)[k]; val ny = y + intArrayOf(0, 1)[k]
          if (nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if ((px[j] ushr 24) < 250) continue
          val dl = ((labL[i].toInt() and 0xffff) - (labL[j].toInt() and 0xffff)) / 64.0
          val da = (labA[i].toInt() - labA[j].toInt()) / 64.0
          val db = (labB[i].toInt() - labB[j].toInt()) / 64.0
          if (sqrt(dl * dl + da * da + db * db) > SRC_DE) continue   // 源图这里本来就不连续
          pairs++
          val st = abs((m[i] ushr 24) - (m[j] ushr 24))
          hist[st.coerceAtMost(256)]++
          if (st >= 30) j30++
          if (st >= 60) {
            j60++
            // 这一道硬边是不是**色桶边界**？发档是按粗桶整块平移的，两个源色只差 1~6 的相邻格
            // 只要跨在桶边界上，就会拿到差一整档的浓度 —— 那就是"过渡生硬"的来路。
            val bi = bkOf(labL, labA, labB, i); val bj = bkOf(labL, labA, labB, j)
            if (bi != bj) j60edge++
          }
          if (st > worst) worst = st
        }
      }
      if (pairs < 512) continue
      var cum = 0L; var p99 = 0
      for (v in hist.indices) { cum += hist[v]; if (cum * 100 >= pairs * 99) { p99 = v; break } }
      rows += ARow(f.name, pairs, j30 * 100 / pairs, j60 * 100 / pairs, p99, worst,
        if (j60 == 0L) 0 else j60edge * 100 / j60)
    }
    rows.sortByDescending { it.p60 }
    println("=== edge=$edge · ${rows.size} 张 · 源图连续（ΔE ≤$SRC_DE）处的交付跳变 ===")
    println("%-42s %9s %7s %7s %6s %6s %7s".format("图标", "连续对", "跳≥30", "跳≥60", "p99", "最大", "跨桶"))
    rows.take(12).forEach { println(it.line()) }
    fun med(g: (ARow) -> Long) = rows.map(g).sorted()[rows.size / 2]
    println("中位：跳≥30 = ${med { it.p30 }}%   跳≥60 = ${med { it.p60 }}%   p99 跳 = ${med { it.p99.toLong() }}")
    println("跳≥60 超过 1% 连续对的张数 = ${rows.count { it.p60 > 1 }}   " +
      "STEP n=${rows.size} p30=${med { it.p30 }} p60=${med { it.p60 }} p99=${med { it.p99.toLong() }}")
    println()
  }
}

private class ARow(
  val name: String, val pairs: Long, val p30: Long, val p60: Long, val p99: Int, val worst: Int,
  val edgeShare: Long,
) {
  fun line() = "%-42s %9d %6d%% %6d%% %6d %6d %6d%%".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(42), pairs, p30, p60, p99, worst, edgeShare)
}

/** 与 `bucketOf` 同一套粗桶（L/8、a·b/32）—— 发档就是按它整块平移的。 */
private fun bkOf(l: ShortArray, a: ShortArray, b: ShortArray, i: Int): Int {
  val ll = (l[i].toInt() and 0xffff) shr 6
  val aa = ((a[i].toInt() shr 6) + 128).coerceIn(0, 255)
  val bb = ((b[i].toInt() shr 6) + 128).coerceIn(0, 255)
  return ((ll / 8) * 8 + (aa / 32)) * 8 + (bb / 32)
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
