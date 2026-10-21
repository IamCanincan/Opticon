package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **细桥：我们凭空搭出来的那条连接**（只量不改）。
 *
 * 主人报"图形有粘连"。前三次想量它都失败在同一件事上：要先定义"源图里有几个形状"，
 * 而**用颜色桶定义形状，会把一个汉字的十几条笔画、插画的几百个噪点都数成"形状"** ——
 * 于是报出"94/113 张有粘连"这种不能用也不能反证的数。
 *
 * 换一个**不需要"形状"这个概念**的判据，而且直接对着源图比：
 *  1. 取一个二值图（源图 = 实心格；交付 = 墨 α≥128）；
 *  2. 把里面**厚度 ≥ 3px 的实心块**留下（到边界的距离 ≥3），≤2px 的细条全拿掉；
 *  3. 数拿掉之后分成几块：`n(全) → n(厚)` 的差，就是"靠细条连着的块数"；
 *  4. **关键在最后一步**：同一个源图算一遍、同一张交付算一遍。
 *     `Δ = 交付的细桥连接数 − 源图的细桥连接数`。
 *     **Δ > 0 才是"我们凭空把两块粘上了"** —— 源图本来就靠细线连着的（字的一条横、logo 的一条描边）
 *     两边同涨，Δ = 0，不算病。这一步把"源本来就碎"这个噪声从判据里减掉了。
 *
 * 用法：`harness.BridgeKtKt [目录] [--edges=96,384] [--take=20]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val take = (args.firstOrNull { it.startsWith("--take=") }?.substringAfter("=") ?: "20").toInt()
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  for (edge in edges) {
    val rows = ArrayList<BrkRow>()
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
      val m = runCatching { MonochromeMask.build(px, edge, edge).mask }.getOrNull() ?: continue
      val n = edge * edge
      val src = BooleanArray(n) { (px[it] ushr 24) >= 128 }
      val ink = BooleanArray(n) { (m[it] ushr 24) >= 128 }
      var srcGlue = 0; var inkGlue = 0; var thinArea = 0
      for (v in booleanArrayOf(false, true)) {
        val set = if (v) ink else src
        val total = comps(set, edge)
        val thick = thickOnly(set, edge, 3)
        val after = comps(thick, edge)
        val joined = (after - total).coerceAtLeast(0)
        if (v) { inkGlue = joined; thinArea = count(set) - count(thick) } else srcGlue = joined
      }
      val delta = inkGlue - srcGlue
      // ⚠ 原来只收 `delta > 0`（**我们凭空把两块粘上**），于是 `delta < 0`
      //   （**源里本来连着、被我们切成两块**）这一侧**从来没被看过一眼** ——
      //   2026-10-20 量 malody 那条缝时才发现这台子只有一边有牙。两边都收。
      if (delta != 0 || thinArea > edge * edge / 40) rows += BrkRow(f.name, srcGlue, inkGlue, delta, thinArea)
    }
    rows.sortByDescending { it.delta }
    println("=== edge=$edge · ${files.size} 张 · 细桥：源图本来靠细线连的块数 vs 交付后 ===")
    println("%-40s %8s %8s %7s %9s".format("图标", "源细桥", "交付细桥", "Δ", "细条格"))
    rows.take(take).forEach { println(it.line()) }
    println("**Δ>0（我们凭空把两块粘上）的图 = ${rows.count { it.delta > 0 }} 张**   " +
      "Δ≥2 的 = ${rows.count { it.delta >= 2 }} 张   Δ=0 但细条很多（源本来就靠细线连）= ${rows.count { it.delta == 0 }} 张")
    // 另一侧：`Δ<0` = **源里本来靠细线连着，我们把它切断了**。以前这一侧根本不进表。
    val neg = rows.filter { it.delta < 0 }.sortedBy { it.delta }
    println("**Δ<0（源连着、被我们切断）的图 = ${neg.size} 张**" +
      (if (neg.isEmpty()) "" else "   最狠：" + neg.take(3).joinToString("  ") { "${it.name.removeSuffix("-src.png").removeSuffix(".png").take(28)} Δ${it.delta}" }))
    println("BRIDGE n=${rows.size} delta_pos=${rows.count { it.delta > 0 }} delta2=${rows.count { it.delta >= 2 }} delta_neg=${neg.size}")
    println()
  }
}

/** 8 邻接连通块数（忽略小于 3 格的碎屑，否则抗锯齿会把数撑爆）。 */
private fun comps(v: BooleanArray, edge: Int): Int {
  val n = v.size
  val seen = BooleanArray(n)
  val q = IntArray(n)
  var c = 0
  for (i in 0 until n) {
    if (!v[i] || seen[i]) continue
    var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
    while (head < tail) {
      val x = q[head] % edge; val y = q[head] / edge; head++
      for (oy in -1..1) for (ox in -1..1) {
        if (ox == 0 && oy == 0) continue
        val nx = x + ox; val ny = y + oy
        if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
        val j = ny * edge + nx
        if (v[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
      }
    }
    if (tail >= 3) c++
  }
  return c
}

/** 只留下"到非集合格的切比雪夫距离 ≥ d"的格（= 真正的粗块内部）。 */
private fun thickOnly(v: BooleanArray, edge: Int, d: Int): BooleanArray {
  val n = v.size
  val out = BooleanArray(n)
  for (i in 0 until n) {
    if (!v[i]) continue
    val x = i % edge; val y = i / edge
    var ok = true
    outer@ for (oy in -d..d) for (ox in -d..d) {
      val nx = x + ox; val ny = y + oy
      if (nx < 0 || ny < 0 || nx >= edge || ny >= edge || !v[ny * edge + nx]) { ok = false; break@outer }
    }
    out[i] = ok
  }
  return out
}

private fun count(v: BooleanArray): Int { var c = 0; for (x in v) if (x) c++; return c }

private class BrkRow(val name: String, val src: Int, val ink: Int, val delta: Int, val thin: Int) {
  fun line() = "%-40s %8d %8d %7d %9d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(40), src, ink, delta, thin)
}
