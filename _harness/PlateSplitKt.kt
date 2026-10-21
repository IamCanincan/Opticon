package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **「盘 / 标记」到底怎么分（只量，不改模型）** —— 2026-10-12，接 `PlateInvert` 那一版候选的死因。
 *
 * 第一版候选按 **每通道 24 分一档的色族**分盘与标记，装机量出来两张都坏：
 *   · 支付宝：那个「支」是**白字 + 一道渐变**，渐变的尾巴落进了**盘那一族**的桶里 →
 *     被当成盘铺了墨 → 镂空出来是个"带白边的灰影子"（`D:/tmp/pinv/…Alipay…png`）。
 *   · translate：「文」的反锯齿同理漏了一点，下半那道笔画没镂空干净。
 * 而同一版 `PlateInkKt` 报的"标记自己亮度宽"是 支付宝 **6** / translate **63** ——
 * **和眼睛看到的正相反**（translate 那 63 是**斜影**混进"非盘"堆里撑出来的，不是字）。
 * 这条数不能当裁判，图才是。
 *
 * 所以这一台换一条**相对**判据量一遍：先取背景层亮度的**众数**当盘（`plateL`），
 * 再看每格离它多远。**标记可以比盘暗（translate 的文 119 vs 盘 238），也可以比盘亮
 * （支付宝的支 255 vs 盘 108）** —— 所以取**绝对差**，不取符号。
 * 要回答的就一个问题：**有没有一个 Δ，三张都能干净分开、且不误伤别的张。**
 *
 * 用法：`harness.PlateSplitKt <目录> [目录…]`
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }.filter { it.isDirectory }
  val edge = 384
  println("== 盘/标记 用「离盘中位亮度多远」分，分得开吗（背景层 = 叠加那一路的 under）==")
  println("Δ = |这格亮度 − 盘亮度|。报「Δ>40 / >70 / >100 各占背景层实心格几%」")
  println()
  for (d in dirs) for (f in d.listFiles { x -> x.extension == "png" }!!.sortedBy { it.name }) {
    if (f.name.endsWith("_modelbg.png")) continue
    val un = File(d, f.nameWithoutExtension + "_modelbg.png")
    if (!un.isFile) continue
    val u = grab(un, edge) ?: continue
    val L = IntArray(u.size) { (0.2126 * ((u[it] shr 16) and 0xff) + 0.7152 * ((u[it] shr 8) and 0xff) +
      0.0722 * (u[it] and 0xff)).toInt() }
    val h = IntArray(256); var solid = 0
    for (i in u.indices) if (((u[i] ushr 24) and 0xff) >= 128) { h[L[i]]++; solid++ }
    if (solid == 0) continue
    val plateL = (0..255).maxByOrNull { h[it] } ?: 0
    val plateShare = h[plateL] * 100.0 / solid
    if (plateShare < 40.0) continue                 // 没有一块占大头的盘，谈不上"翻它"
    fun pct(min: Int): Double {
      var n = 0
      for (i in u.indices) {
        if (((u[i] ushr 24) and 0xff) < 128) continue
        if (kdist(L[i], plateL) >= min) n++
      }
      return n * 100.0 / solid
    }
    val tag = if (plateShare >= 85.0) "盘≥85%" else if (plateShare >= 60.0) "盘60-85" else "盘40-60"
    // **这块盘有多少被前景层盖住了** —— translate 那块盘上坐着一张蓝卡（气泡），系统图标那种
    // 是"盘就是整张图标的底、上面没有第二张卡"。这两种"平盘 + 标记"是**完全不同的东西**，
    // 而反相对前者是对的（后面那块卡该黑）、对后者是错的（设计师画的就是浅盘深字）。
    val c = File(d, f.nameWithoutExtension + ".png")
    var occl = 0
    if (c.isFile) {
      val sx = grab(c, edge)
      if (sx != null) for (i in u.indices) {
        if (((u[i] ushr 24) and 0xff) < 128) continue
        if (Math.abs(invL(sx[i]) - L[i]) >= 24) occl++
      }
    }
    println("  ${"%.1f".format(plateShare)}% 盘(L=$plateL)  Δ>40 ${"%.1f".format(pct(40))}%" +
      "  Δ>70 ${"%.1f".format(pct(70))}%  Δ>100 ${"%.1f".format(pct(100))}%" +
      "  盘被前景盖住 ${"%.0f".format(occl * 100.0 / solid)}%  $tag  ${short(f.name)}")
  }
}

private fun invL(p: Int): Int =
  (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt()

/** 亮度**距离**，不分方向 —— 白字在蓝盘上、深字在灰盘上，是同一种"它是标记"。 */
private fun kdist(l: Int, plate: Int): Int = Math.abs(l - plate)
private fun short(s: String) = s.removeSuffix(".png").substringBefore("_adaptive").substringBeforeLast("_")
private fun grab(f: File, edge: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val out = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics(); g.drawImage(img, 0, 0, edge, edge, null); g.dispose()
  return IntArray(edge * edge).also { out.getRGB(0, 0, edge, edge, it, 0, edge) }
}
