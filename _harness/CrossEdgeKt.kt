package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * 第十七闸：**裁圆判据跨 canvas 不变账**（2026-10-17）。
 *
 * ## 它要钉住的那句主张
 * 窗口存的是**比例**（`Window.l/t/r/b` 与被量的像素尺寸无关），[IconCropWindow.of] 又先把格子降到
 * 扫描边长 —— 所以"同一张图标在桌面 384 与抽屉 192 两联该裁成同一圈"是**设计主张**，不是巧合。
 * 今天真机第一次有了双档样本（19 张在 192/384 都重算过），判据 19/19 没翻、
 * 拉交付掩码逐格比 19/19 仍是同一张画（`_a8sweep.cjs`）。可**没有一台子守着它**：
 * 下一把刀只要把某个门槛写成绝对格数，这一轴就会悄悄变成"两个位置长得不一样"，
 * 而前面十六闸一条都不会红（本仓为这一族记过一次：**绝对格数门槛 = 潜伏的尺寸依赖**）。
 *
 * ## 判"漂移"分两层，别混
 *  - **类**（会改变屏幕上那一档的东西）：满幅 / 开窗(plate+入口) / 内切 / 撞没撞 `GEO_ZOOM_CAP`。
 *    这一层**一张都不许动** → 本台子直接退非 0。
 *  - **数**（同一档里的 `keepPct` 与 `zoom`）：本台子是"源 PNG → 边长 → 再降到 128"**两次重采样**，
 *    不同边长落到的 128 格子本来就不是同一套像素，所以今天就有 3 张在动（`剩差最大=2`、
 *    `倍数差最大=0.0701`）。**不写死一个"≤0"的带** —— 第一版那么写过，正确代码自己就红；
 *    也**不因此放过它**：这些数连同"是谁"一起进 `XS` 那一行，由 `regress.sh` **逐字钉住**，
 *    跳了就要认账重钉（与第十六闸钉 `PS` 行同一口径）。
 * ⚠ 默认比的是 **192/384/768**（真机上学得到的三档）。**128 故意不进对照**：
 *   低于扫描边长时 `of()` 不降采样，那是第三条路径，不是"屏幕上两个尺寸"。
 *
 * ## 这台子自己看得见多少（绿闸必须报这条）
 * `近线=` 判据落在任一阈值邻域内的张数（keep 线 ±2%、spiky 线 ±0.02、上限 ±0.04）。
 * 现量 **0** —— 这一轴上的"骑线样本"这台子**看不见**，所以它的牙全在变异上：
 * ① 把 `of()` 的降采样去掉（判据开始吃各档自己的格子）→ **类漂移仍是 0**，
 *    但 `剩差最大` 从 `2(webapk)` 变 `2(tieba)`、`倍数差最大` 从 `0.0701(uu远程)` 变 `0.0329(termux)`
 *    → **XS 整行逐字不同 → 红** ✓（这一条就是"数层必须钉"的凭据：只判类，变异当场溜走）。
 * ② 把扫描边长 128→64（三档仍落到同一张格子）→ 类漂移 0、`剩差最大 2→0` → 也是红 ✓
 *    （它确实改了交付，不是误报；要放行得 `--update` 认账）。
 *
 * 用法：`harness.CrossEdgeKtKt <语料目录> [--edges=192,384,768]`
 * 退出码非 0 = 有**类**漂移，或一张都没开窗（空分母不算通过）。数层由 XS 行的钉负责。
 */
private class Rec(val capped: Boolean, val w: IconCropWindow.Window?) {
  /** 屏幕上"这是哪一档"。 */
  val arm: String = when {
    w == null -> if (capped) "卡上限" else "满幅"
    w.plate -> "plate:" + if (w.spiky && w.byKeep) "keep+spiky" else if (w.spiky) "spiky" else "keep"
    else -> "内切"
  }
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "")
  if (!dir.isDirectory) { println("!! 语料目录不存在：$dir（这一闸没跑，不算通过）"); exitProcess(2) }
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "192,384,768")
    .split(',').mapNotNull { it.toIntOrNull() }.sortedBy { it }
  require(edges.size >= 2) { "--edges 至少两档才有对照" }
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  if (files.isEmpty()) { println("!! $dir 里没有 png（这一闸没跑，不算通过）"); exitProcess(2) }

  var armDrift = 0
  var opened = 0
  var nearN = 0
  var cappedN = 0
  var worstPct = 0
  var wPct = ""
  var wZoom = 0f
  var wZoomName = ""
  val out = StringBuilder()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val src = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, src, 0, sw)
    val recs = edges.map { e ->
      val px = IntArray(e * e)
      for (y in 0 until e) for (x in 0 until e) {
        val sy = (y.toLong() * sh / e).toInt().coerceAtMost(sh - 1)
        val sx = (x.toLong() * sw / e).toInt().coerceAtMost(sw - 1)
        px[y * e + x] = src[sy * sw + sx]
      }
      val raw = runCatching { IconCropWindow.of(px, e, e) }.getOrNull()
      // 走**生产入口** `measure()`（含 GEO_ZOOM_CAP），不在这台子里重抄 1.82
      Rec(capped = raw != null && runCatching { IconCropWindow.measure(px, e) }.getOrNull() == null,
        w = runCatching { IconCropWindow.measure(px, e) }.getOrNull())
    }
    // ⚠ 判据是"有没有**任何一档**不叫满幅"，不是"第一档开没开窗"：
    //   一张在 192 满幅、在 768 撞上限（`w` 两档都是 null）的图标，恰恰是最该响的那一类。
    if (recs.any { it.arm != "满幅" }) {
      if (recs.any { it.w != null }) opened++
      if (recs.any { it.capped }) cappedN++
      val kp = recs.mapNotNull { it.w?.keepPct }
      val zm = recs.mapNotNull { it.w?.zoom }
      val dp = if (kp.size >= 2) kp.max() - kp.min() else 0
      val dz = if (zm.size >= 2) zm.max() - zm.min() else 0f
      if (dp > worstPct) { worstPct = dp; wPct = f.name.substringBeforeLast('.') }
      if (dz > wZoom) { wZoom = dz; wZoomName = f.name.substringBeforeLast('.') }
      if (recs.any { it.w != null && nearLine(it.w) }) nearN++
      val base = recs.first().arm
      val bad = recs.filter { it.arm != base }
      if (bad.isNotEmpty()) {
        armDrift++
        out.append("  !! 类漂移 ${f.name.substringBeforeLast('.')}\n")
        recs.forEachIndexed { i, r -> out.append("     ${edges[i]}=${r.arm}" +
          (r.w?.let { " 剩=${it.keepPct} zoom=${"%.4f".format(it.zoom)}" } ?: "") + "\n") }
      }
      if (dp > 0 || dz > 0f) {
        // **数层不判红**，只印出来 + 由 regress.sh 整行钉在 XS 上（跳了要认账重钉）。
        // 第一版把它写成"数跳带也算硬失败、带取现量 0"，结果**正确代码自己就红**：
        // 本台子是"源 PNG → 边长 → 再降到 128"两次重采样，192 那一档落到的 128 格子
        // 与 384/768 不是同一套像素（真机 19/19 逐字相同，是因为矢量按档各自光栅化）。
        out.append("  ~ 数层 ${f.name.substringBeforeLast('.')} 剩差=$dp 倍数差=${"%.4f".format(dz)}\n")
        out.append("     " + edges.mapIndexed { i, e ->
          "$e=" + (recs[i].w?.let { "剩${it.keepPct}/z${"%.4f".format(it.zoom)}" } ?: "-")
        }.joinToString("  ") + "\n")
      }
    }
  }
  print(out)
  println("XS n=${files.size} 档=${edges.joinToString("/")} 开窗=$opened 类漂移=$armDrift " +
    "近线=$nearN 卡上限=$cappedN 剩差最大=$worstPct($wPct) 倍数差最大=${"%.4f".format(wZoom)}($wZoomName)")
  if (opened == 0) { println("!! 一张都没开窗 —— 分母是 0，这台子什么都没测到（不算通过）"); exitProcess(1) }
  if (armDrift != 0) exitProcess(1)
  println("CrossEdgeKt OK：${files.size} 张 × ${edges.size} 档**同一档判据**逐字相同" +
    "（开窗 $opened 张、近线 $nearN 张、剩差 ≤$worstPct、倍数差 ≤${"%.4f".format(wZoom)}）")
}

/** 近线：任一判据数骑在它那条线的邻域内（线本身**从生产读**，不抄字面量）。 */
private fun nearLine(w: IconCropWindow.Window): Boolean {
  val ratio = if (w.rBox > 0f) w.rCover / w.rBox else 1f
  return abs(w.keepPct - IconCropWindow.keepRatioPercent) <= 2 ||
    abs(ratio - IconCropWindow.coverBoxFloor) <= 0.02f ||
    abs(w.zoom - IconCropWindow.GEO_ZOOM_CAP) <= 0.04f
}
