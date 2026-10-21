package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **贴着墨、却和"远处的底"不是一种颜色**的那一圈底侧格（只量不改）。
 *
 * 前两把尺子都死了，死法值得记下来：
 *  · `FieldKt` 拿**边界中位色**当真底 → feimo 报 98%，因为插画的底本来就多色；
 *  · `LocalKt` 拿**每格周围一圈的中位色**当参照 → sgame 报 56.7%，因为插画里"周围一圈"本身就花。
 * 结论是结构性的：**颜色统计量（全局或局部）分不开"底本来就花"与"底被图形占了"** ——
 * 对一张满幅插画来说，"哪一块是底"这件事本来就没有定义。
 *
 * 所以这一把不再问"它和底差多少"，改问两件**位置**事实：
 *  1. 这一格**离交付出去的墨很近**（≤ NEAR px）—— 它是"贴着图形"的；
 *  2. 它和**离墨很远（≥ FAR px）的那些底**的中位色差 ≥ DE —— 它和"这张图真正的背景"不是一种颜色。
 * 插画会自己限制住自己：满幅插画里几乎没有"离墨 30px 以外的底"，第 2 条的样本就不成立，
 * 量不出稳定的"真背景"，于是一条都不会报。**globalspeed 那条续弧**则两条都成立：
 * 背景铺满整张（FAR 样本巨大），而续弧贴着亮弧那截墨、颜色离真背景 13.4。
 *
 * 用法：`harness.HugKtKt [目录] [--edges=384] [--de=12] [--near=12] [--far=30] [--shot=DIR]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val DE = args.firstOrNull { it.startsWith("--de=") }?.substringAfter("=")?.toInt() ?: 12
  val NEAR = args.firstOrNull { it.startsWith("--near=") }?.substringAfter("=")?.toInt() ?: 12
  val FAR = args.firstOrNull { it.startsWith("--far=") }?.substringAfter("=")?.toInt() ?: 30
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let {
    File(it).apply { mkdirs() }
  }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<HRow>()
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
      val plate = cf(s, "plate") as ByteArray
      val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
      val labB = cf(s, "labB") as ShortArray
      val n = edge * edge
      // 到最近"交付出去的墨"的距离（多源 BFS）
      val ink = BooleanArray(n) { plate[it] == 0.toByte() && (m[it] ushr 24) >= 128 }
      var inkN = 0
      for (v in ink) if (v) inkN++
      if (inkN < 256) continue
      val d = IntArray(n) { if (ink[it]) 0 else 1 shl 20 }
      val q = IntArray(n); var head = 0; var tail = 0
      for (i in 0 until n) if (d[i] == 0) q[tail++] = i
      while (head < tail) {
        val c = q[head++]; val cx = c % edge; val cy = c / edge; val nd = d[c] + 1
        if (nd > FAR + 2) continue
        for (k in 0 until 4) {
          val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (nd < d[j]) { d[j] = nd; q[tail++] = j }
        }
      }
      // 真背景 = 离墨 ≥FAR 的那些底格
      val fl = ArrayList<Int>(); val fa = ArrayList<Int>(); val fb = ArrayList<Int>()
      for (i in 0 until n) {
        if (plate[i] == 0.toByte() || (px[i] ushr 24) < 250 || d[i] < FAR) continue
        fl += labL[i].toInt() and 0xffff; fa += labA[i].toInt(); fb += labB[i].toInt()
      }
      // 样本不够 = 这张图没有"远处的底"，这条判据对它不成立（插画自己把自己限住了）
      if (fl.size < n / 8) { rows += HRow(f.name, 0, 0, fl.size, 0); continue }
      fl.sort(); fa.sort(); fb.sort()
      val ml = fl[fl.size / 2]; val ma = fa[fa.size / 2]; val mb = fb[fb.size / 2]
      val hot = BooleanArray(n)
      var groundN = 0
      for (i in 0 until n) {
        if (plate[i] == 0.toByte() || (px[i] ushr 24) < 250) continue
        groundN++
        if (d[i] > NEAR) continue
        val dl = ((labL[i].toInt() and 0xffff) - ml) / 64.0
        val da = (labA[i].toInt() - ma) / 64.0
        val db = (labB[i].toInt() - mb) / 64.0
        if (sqrt(dl * dl + da * da + db * db) >= DE) hot[i] = true
      }
      var cnt = 0
      for (v in hot) if (v) cnt++
      val seen = BooleanArray(n)
      var best = 0
      for (i in 0 until n) {
        if (!hot[i] || seen[i]) continue
        head = 0; tail = 0; q[tail++] = i; seen[i] = true
        while (head < tail) {
          val c = q[head++]; val cx = c % edge; val cy = c / edge
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (hot[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
          }
        }
        if (tail > best) best = tail
      }
      rows += HRow(f.name, cnt, best, fl.size, groundN)
      if (shotDir != null && best >= 200) {
        val v = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until n) {
          val a = m[i] ushr 24
          val g = 255 - a
          v.setRGB(i % edge, i / edge, when {
            hot[i] -> 0xE03A2F
            plate[i] != 0.toByte() -> 0xE8E8E8
            else -> (g shl 16) or (g shl 8) or g
          })
        }
        ImageIO.write(v, "png", File(shotDir, f.nameWithoutExtension + "-hug.png"))
      }
    }
    rows.sortByDescending { it.blob }
    println("=== edge=$edge · ${rows.size} 张 · 贴墨 ≤${NEAR}px 且离真背景 ≥$DE（远处底样本 ≥12.5% 才算这张成立）===")
    println("%-42s %8s %8s %8s %6s".format("图标", "格数", "最大块", "远底样本", "占底"))
    rows.take(12).forEach { println(it.line()) }
    println("成立的张数（有足够'远处的底'）= ${rows.count { it.far >= 1 }} / ${rows.size}")
    println("有一大块（≥框 0.5%）的 = ${rows.count { it.blob >= edge * edge * 5 / 1000 }} 张   " +
      "中位最大块 ${rows.map { it.blob }.sorted()[rows.size / 2]}")
    println()
  }
}

private class HRow(val name: String, val cells: Int, val blob: Int, val far: Int, val ground: Int) {
  fun line() = "%-42s %8d %8d %8d %5.1f%%".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(42), cells, blob, far,
    cells * 100.0 / maxOf(1, ground))
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
