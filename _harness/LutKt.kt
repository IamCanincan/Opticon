package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **dep→浓度那条曲线有多陡**（只量不改）。
 *
 * 触发：王者荣耀"过渡生硬"，而它 ≥60 的硬跳有 **71% 发生在同一个粗桶内部** —— 发档按桶整块平移，
 * 解释不了。桶内还能动浓度的只剩一处：**按精确源色查的那张 `cDep` 表 + 全局 LUT**。
 * 如果 LUT 在某一段几乎垂直，那么源色只差几个单位的相邻两格，交付能差 60 以上 ——
 * 平滑渐变就是这么被印成台阶的。
 *
 * 每张报三个数（单位都是"dep 的格"，dep 0..255）：
 *  · **中段窗口** = 交付 alpha 从 20% 走到 80% 需要多宽的 dep —— **越小越陡**，
 *    理想是"用满 0..255"，如果 40→200 全挤在 15 个 dep 单位里，那就是台阶发生器；
 *  · **最陡一段** = 相邻两个 dep 值之间最大的 alpha 跳（按 dep 排序后）；
 *  · **色数** = 这张图有多少种精确源色（插画天生多，最容易撞上陡段）。
 *
 * 用法：`harness.LutKtKt [目录] [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val rows = ArrayList<KRow>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < 96 || h < 96) continue
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val out = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = out.mask ?: continue
    val s = pool.get() ?: continue
    val plate = cf(s, "plate") as ByteArray
    val dist = cf(s, "dist") as ShortArray
    // 按精确源色归并：dep 取该色的代表值，alpha 取该色交付的值
    val d = HashMap<Int, Int>(); val al = HashMap<Int, Int>(); val cn = HashMap<Int, Int>()
    for (i in 0 until w * h) {
      if (plate[i] != 0.toByte() || (px[i] ushr 24) != 255) continue
      val a = m[i] ushr 24
      if (a == 0) continue
      val k = px[i] and 0x00FFFFFF
      val dv = dist[i].toInt() and 0xffff
      d[k] = maxOf(d[k] ?: 0, dv); al[k] = maxOf(al[k] ?: 0, a); cn[k] = (cn[k] ?: 0) + 1
    }
    val keys = d.keys.filter { (cn[it] ?: 0) >= 16 }.sortedBy { d[it] }
    if (keys.size < 12) continue
    val dd = keys.map { d[it]!! }; val aa = keys.map { al[it]!! }
    val lo = aa.min()!!; val hi = aa.max()!!
    val span = hi - lo
    if (span < 60) { rows += KRow(f.name, keys.size, 0, 0, span); continue }
    val a20 = lo + span * 20 / 100; val a80 = lo + span * 80 / 100
    val i20 = dd.indices.first { aa[it] >= a20 }; val i80 = dd.indices.first { aa[it] >= a80 }
    var steep = 0
    for (i in 1 until dd.size) if (dd[i] != dd[i - 1]) {
      val sl = kotlin.math.abs(aa[i] - aa[i - 1]) * 100 / (dd[i] - dd[i - 1])
      if (sl > steep) steep = sl
    }
    rows += KRow(f.name, keys.size, (dd[i80] - dd[i20]).coerceAtLeast(1), steep, span)
  }
  rows.sortBy { it.window }
  println("=== ${rows.size} 张 · 按精确源色（≥16 格）归并后的 dep→浓度 曲线 ===")
  println("%-42s %6s %8s %8s %6s".format("图标", "色数", "中段窗口", "最陡斜率", "α跨度"))
  rows.take(14).forEach { println(it.line()) }
  val wnd = rows.map { it.window }.sorted()
  println("中段窗口 中位 ${wnd[wnd.size / 2]}  p10 ${wnd[wnd.size / 10]}   （越小 = 曲线越陡 = 越容易把渐变印成台阶）")
  println("窗口 ≤15 个 dep 单位的张数 = ${rows.count { it.window <= 15 }}   ≤8 = ${rows.count { it.window <= 8 }}")
  println("最陡斜率 ≥60（相邻两种色差 1 个 dep 就跳 60 浓度）的张数 = ${rows.count { it.steep >= 60 }}")
}

private class KRow(val name: String, val colours: Int, val window: Int, val steep: Int, val span: Int) {
  fun line() = "%-42s %6d %8d %8d %6d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(42), colours, window, steep, span)
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
