package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * **这张图的源色到底是"几簇"还是"一条链"**（只量不改）。
 *
 * `LutKt` 量出 `dep→浓度` 几乎是阶跃（中段窗口中位 13 / 255，65 张图最陡处 1 个 dep 跳 ≥60）。
 * 但**窗口小有两种完全不同的原因**：
 *  · 图标本来就只有两三种平色（logo 就该二值，那不是病，是它该有的样子）；
 *  · 源图里有一条**连续渐变**（几百种色排成一条链），却被压成两级 —— 那才是"过渡生硬"。
 * 不分清这两类，淘汰线就是空话。
 *
 * 怎么分：把每张图的精确源色（≥16 格的那些）按 dep 排成一条序列，看**相邻两色的 dep 间距**：
 *  · **簇数** = 沿这条序列，间距 > GAP_CLUSTER 的地方切一刀，切出几段 —— 几段就是"几种颜色"；
 *  · **链密度** = 间距 ≤ GAP_CHAIN 的相邻对占多少 —— 高 = 源图是连续渐变；
 *  · **该柔却跳了** = 在这些"链内相邻对"里，交付 alpha 差 ≥60 的占多少。
 * 判"生硬是病"的条件因此是：**簇数 ≥ 6 且 链密度高**，而不是"窗口小"。
 *
 * 用法：`harness.ChainKtKt [目录] [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val GAP_CLUSTER = 25; val GAP_CHAIN = 8
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val rows = ArrayList<ChRow>()
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
    val d = HashMap<Int, Int>(); val al = HashMap<Int, Int>(); val cn = HashMap<Int, Int>()
    for (i in 0 until w * h) {
      if (plate[i] != 0.toByte() || (px[i] ushr 24) != 255) continue
      val a = m[i] ushr 24
      if (a == 0) continue
      val k = px[i] and 0x00FFFFFF
      d[k] = maxOf(d[k] ?: 0, dist[i].toInt() and 0xffff)
      al[k] = maxOf(al[k] ?: 0, a); cn[k] = (cn[k] ?: 0) + 1
    }
    val keys = d.keys.filter { (cn[it] ?: 0) >= 16 }.sortedBy { d[it] }
    if (keys.size < 8) continue
    val dd = keys.map { d[it]!! }; val aa = keys.map { al[it]!! }
    var clusters = 1; var chain = 0; var pairs = 0; var jump60 = 0; var jump30 = 0
    for (i in 1 until dd.size) {
      val gap = dd[i] - dd[i - 1]
      if (gap <= 0) continue
      pairs++
      if (gap > GAP_CLUSTER) clusters++
      if (gap <= GAP_CHAIN) {
        chain++
        val st = abs(aa[i] - aa[i - 1])
        if (st >= 60) jump60++
        if (st >= 30) jump30++
      }
    }
    rows += ChRow(f.name, keys.size, clusters, chain * 100 / pairs, jump60 * 100 / maxOf(1, chain),
      jump30 * 100 / maxOf(1, chain))
  }
  rows.sortByDescending { it.j60 }
  println("=== ${rows.size} 张 · 源色按 dep 排成链后的簇与跳 ===")
  println("%-40s %6s %6s %8s %8s %8s".format("图标", "色数", "簇数", "链密度", "链内跳≥60", "≥30"))
  rows.take(14).forEach { println(it.line()) }
  fun med(g: (ChRow) -> Int) = rows.map(g).sorted()[rows.size / 2]
  println("中位：色数 ${med { it.colours }}  簇数 ${med { it.clusters }}  链密度 ${med { it.chain }}%")
  val real = rows.filter { it.clusters >= 6 && it.chain >= 30 }
  println("**有真渐变的图（簇数 ≥6 且 链密度 ≥30%）= ${real.size} 张**")
  println("  其中「链内相邻两色跳 ≥60」超过 20% 的 = ${real.count { it.j60 > 20 }} 张   " +
    "超过 50% 的 = ${real.count { it.j60 > 50 }} 张")
  println("  这些图就是「过渡生硬」真正的嫌疑人；簇数 ≤3 的那些，本来就该是两级的。")
}

private class ChRow(
  val name: String, val colours: Int, val clusters: Int, val chain: Int, val j60: Int, val j30: Int,
) {
  fun line() = "%-40s %6d %6d %7d%% %7d%% %7d%%".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(40), colours, clusters, chain, j60, j30)
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
