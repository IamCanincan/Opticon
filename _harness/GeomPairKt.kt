package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * **第一把尺子：两路「窗口」的离线对表**（2026-10-16）。
 *
 * 为什么要有它：主人要「单色图标几何完全和彩色图标一样」。真机上一次对表是**我逐包 grep 日志**
 * 做的（2026-10-16，抓出 `me.ele` 是唯一对不上的一张），而 `_harness` 里**没有一把能量这条**的尺
 * —— 于是那条改动只能上真机试，**试一次翻一次**（两次回归：先"大一圈"、后"大小不一"）。
 * 仓规原话：「一条判据能不能改，取决于有没有台子能量它」。这把尺子就是把那次对表搬上台子。
 *
 * ## 两条路量的**对象**（产品现状，别混）
 *  · **彩色** = `AdaptiveIconDrawable.draw()` 出来的**整图标**：自适应图标 = 层按 `1+2f` 铺开、裁四边；
 *  · **染色** = 交给模型的**那一层本身**（1.0 倍铺满）。
 * 同一张图标、同一套判据，在两套对象上会给出**不同的档** —— 这就是"单色几何 ≠ 彩色几何"的源头。
 * 非自适应没有这一档（两套对象本来同一张），所以它们**天然一致**（真机逐张对表也证实了）。
 *
 * ## 判「一致」
 * 只看一条：**两边的窗口是不是都为 null / 都非 null**（＝"动不动的是同一批"）。
 * ⚠ **不判最终尺寸** —— 最终尺寸要看"摆好之后各占盘多少"，那是**第二把尺子**的事（还没造）。
 * 所以一把尺子绿 ≠ 改动就能收；但它能把"哪几张两路不一致"**离线**点出来。
 *
 * 用法：`sh run.sh harness.GeomPairKtKt <目录...> [--edge=384]`
 * ⚠ `cls`（是否自适应）取自各目录里的 `index.tsv`（第 3 列文件名 / 第 6 列类名）—— 与 `GeomKt` 同口径。
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  // 每个目录自己那份 index.tsv：文件名 → 类名
  val cls = HashMap<String, String>()
  for (d in dirs) {
    val ix = File(d, "index.tsv")
    if (!ix.isFile) continue
    for (l in ix.readLines().drop(1)) {
      val c = l.split("\t")
      if (c.size > 5) cls[c[2]] = c[5]
    }
  }
  println("%-52s %-5s %-9s %-9s %-7s %s".format("图标", "自适应", "彩色(整图标)", "染色(层)", "一致", "备注"))
  var n = 0; var bad = 0; var unk = 0
  for (d in dirs) {
    val files = d.listFiles()?.filter {
      it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") &&
        !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
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
      val adaptive = (cls[f.name] ?: "").contains("Adaptive")
      n++
      // 彩色：量"整图标"那个对象（自适应 = 层铺开）
      val colorWin = IconCropWindow.of(if (adaptive) expandLayer(px, edge) else px, edge, edge)
      // 染色：量"层"本身（产品现状：`of(pixels)` + 层口径 1.82 那条线）
      val monoWin = IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= 1.82f }
      val same = (colorWin == null) == (monoWin == null)
      if (!same) bad++
      val tag = when {
        colorWin == null && monoWin == null -> "满幅/满幅"
        colorWin != null && monoWin != null -> "都动"
        else -> "!! 一边动一边不动"
      }
      if (cls[f.name] == null) unk++
      println("%-52s %-5s %-9s %-9s %-7s %s".format(
        f.name.take(52), if (adaptive) "是" else "否",
        colorWin?.let { "%.2f×".format(it.zoom) } ?: "满幅",
        monoWin?.let { "%.2f×".format(it.zoom) } ?: "满幅",
        if (same) "✓" else "✗", tag))
    }
  }
  println("共 $n 张；**两路不一致 $bad 张**；没有类名（当非自适应算）$unk 张")
  println("⚠ 这把尺子只判\"动不动的是不是同一批\"，**不判最终尺寸** —— 尺寸要看第二把尺子（没造）。")
}

/** 把"层"按 `AdaptiveIconDrawable.draw()` 的摆法铺成整图标（与 `GeomKt` / 产品同源）。 */
private fun expandLayer(px: IntArray, edge: Int): IntArray {
  val f = 0.25f
  val scale = 1f + 2f * f
  val out = IntArray(px.size)
  for (y in 0 until edge) for (x in 0 until edge) {
    val u = (x.toFloat() / edge + f) / scale
    val v = (y.toFloat() / edge + f) / scale
    if (u > 1f || v > 1f) continue
    val sx = (u * (edge - 1)).toInt().coerceIn(0, edge - 1)
    val sy = (v * (edge - 1)).toInt().coerceIn(0, edge - 1)
    out[y * edge + x] = px[sy * edge + sx]
  }
  return out
}
