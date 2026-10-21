package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * 第十八把尺（还没成闸）：**交付里有多少条"源里真不同的边"被抹平了**（2026-10-17）。
 *
 * 主人那句「相邻像素颜色不同的话要有区分」第一次试的是 `plateKey` 改细 —— 那是**盘的判据**，
 * 改细之后 `platecut 5/4 → 0/0`，盘刀认不出盘，净亏（全过程见 devlog 18:20 那节）。
 * 所以这句话要落在**成品**上量：
 *
 *  · **一条边** = 相邻两格（4 连通），两格在**源里都画了东西**（α≥128），
 *    且源色真的不同（通道最大差 **Δ≥9** —— 上一把尺量出 Δ≤2 占 77.6 %，那是抗锯齿与渐变
 *    的一格斜坡，不该算"颜色不同"；9 是把它剔掉的那条线）。
 *  · **被抹平** = 交付 alpha 两格**完全相等**（`|Δα|=0`）；另记一档"几乎没区分"（`|Δα|≤4`）。
 *
 * 三个读数一起给，缺一个都不算立起来：
 *  ① **我们**（`rec_model` 的 `_model.png` 喂进生产 `MonochromeMask.build`）；
 *  ② **设计师那一层**（`yard_model`：源 `<base>.png` ↔ 交付 `<base>_mono.png`，
 *    ⚠ 这一对是**同框架=可见区**才准比 —— `_model` 是满幅，混了就是把 0.259 打成 0.114 那次）；
 *  ③ **反面对照**：把①的交付强行压成 2 档，这台尺必须冲到接近 100 % ——
 *    冲不上去说明它看不见"抹平"这件事，那①②两个数都不能信。
 *
 * 用法：`harness.EdgeFlatKtKt [我们语料=rec_model] [--ref=yard_model] [--side=384] [--min=9]`
 */
private fun load(p: File, side: Int): IntArray? {
  val img = runCatching { ImageIO.read(p) }.getOrNull() ?: return null
  val w = img.width; val h = img.height
  val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
  if (w == side && h == side) return src
  val out = IntArray(side * side)
  for (y in 0 until side) for (x in 0 until side) {
    val sy = (y.toLong() * h / side).toInt().coerceAtMost(h - 1)
    val sx = (x.toLong() * w / side).toInt().coerceAtMost(w - 1)
    out[y * side + x] = src[sy * w + sx]
  }
  return out
}

private class Edge { var n = 0L; var flat = 0L; var near = 0L }

/** 一条边的"源里真不同"按**明度**算（Rec.709 亮度，0..255）。
 *  ⚠ 不是通道最大差：单色层表达不了纯色度差（红↔蓝同亮度），拿 RGB 差当"该区分"，
 *    等于要求模型去做一件物理上做不到的事 —— sangfor 那道"穿盾接缝"正是这个病反过来。 */
private fun luma(p: Int): Int {
  val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
  return (r * 299 + g * 587 + b * 114) / 1000
}
private fun rgbDelta(a: Int, b: Int): Int =
  maxOf(abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
    maxOf(abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)), abs((a and 0xff) - (b and 0xff))))

/** 数一张交付里"源真不同的边"被抹平了多少。`delivered[i]` 读它的 alpha。 */
private fun measure(src: IntArray, delivered: IntArray, side: Int, min: Int, a: Edge) {
  for (y in 0 until side) for (x in 0 until side) {
    val i = y * side + x
    for (j in intArrayOf(if (x + 1 < side) i + 1 else -1, if (y + 1 < side) i + side else -1)) {
      if (j < 0) continue
      if (((src[i] ushr 24) and 0xff) < 128 || ((src[j] ushr 24) and 0xff) < 128) continue
      if (rgbDelta(src[i], src[j]) < 3) continue          // 完全一样的两格不算"边"
      if (abs(luma(src[i]) - luma(src[j])) < min) continue // 亮度没差 = 单色表达不了，不问责
      a.n++
      // ⚠ 先各取各的 alpha 再相减：写成 `x and 0xff - (y and 0xff)` 会被 Kotlin 按
      //   "加减比中缀函数绑得紧"解析成 `x and (0xff - …)`，量出来是一个谁都不信的数。
      val da = abs(((delivered[i] ushr 24) and 0xff) - ((delivered[j] ushr 24) and 0xff))
      if (da == 0) a.flat++ else if (da <= 4) a.near++
    }
  }
}

private fun pct(n: Long, d: Long) = if (d == 0L) "-" else "%.1f".format(n * 100.0 / d) + "%"

/** 标定用的两端：恒等（源亮度直接当交付）与 2 档（粗暴量化）。 */
private fun identity(src: IntArray): IntArray =
  IntArray(src.size) { i -> ((luma(src[i]) and 0xff) shl 24) or 0x00FFFFFF }

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val refDir = File(args.firstOrNull { it.startsWith("--ref=") }?.substringAfter("=") ?: "yard_model")
  val side = args.firstOrNull { it.startsWith("--side=") }?.substringAfter("=")?.toInt() ?: 384
  val min = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 9
  if (!dir.isDirectory) { println("!! 语料目录不存在：$dir"); exitProcess(2) }

  val ours = Edge(); val ctrl = Edge(); val ident = Edge()
  val worst = ArrayList<Pair<String, Double>>()
  var nOurs = 0; var skipNull = 0; var skipSize = 0; var skipLoad = 0
  // ⚠ `rec_model` 里**没有** `_model.png` 这种后缀：建目录时（`_mkrec_model.cjs`）就是把
  //   "模型真吃的那一张"直接落成 `<包名>_<资源>_<sig>.png`，另附 20 张 `<…>_modelbg.png` 当层底。
  //   第一版照 yard_model 的习惯过滤 `_model.png` → **匹配 0 个文件**，
  //   于是"我们"那一行是 0 边 0 跳过 —— 看着像"一张都不合格"，其实是根本没进循环。
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") &&
      !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_mono.png")
  }.sortedBy { it.name }
  for (f in files) {
    val src = load(f, side)
    if (src == null) { skipLoad++; continue }
    val r = runCatching { MonochromeMask.build(src, side, side) }.getOrNull()
    val mask = r?.mask
    if (mask == null) { skipNull++; continue }
    if (mask.size < side * side) { skipSize++; continue }
    val before = ours.n
    measure(src, mask, side, min, ours)
    // 标定两端：**恒等**（把源亮度直接当交付）应当接近 0 %，**2 档**应当远高于我们。
    // ⚠ 不许再写"对照必须 >90 %"这种我拍出来的数 —— 2 档实测只有 82.3 %，
    //   因为两格落在同一侧时本来就不该被分开。判"尺有没有牙"看的是**两端分不分得开**，
    //   不是对照有没有冲到我心里那个数。
    val two = IntArray(mask.size) { i -> if (((mask[i] ushr 24) and 0xff) >= 128) 0xFF000000.toInt() else 0 }
    measure(src, two, side, min, ctrl)
    measure(src, identity(src), side, min, ident)
    if (ours.n > before) {
      nOurs++
      val one = Edge(); measure(src, mask, side, min, one)
      if (one.n > 0) worst += f.name.removeSuffix("_model.png") to one.flat * 1.0 / one.n
    }
  }
  println("EF 我们 n=$nOurs 源Δ≥$min 的边=${ours.n} 抹平=${ours.flat}(${pct(ours.flat, ours.n)}) " +
    "几乎没区分(≤4)=${ours.near}(${pct(ours.near, ours.n)})  [跳过 读不出=$skipLoad 无掩码=$skipNull 尺寸不符=$skipSize]")
  val c = pct(ctrl.flat, ctrl.n); val id = pct(ident.flat, ident.n)
  println("   标定两端：恒等=$id%（应当≈0） 2 档粗暴量化=$c（应当远高于我们）")
  val teeth = ident.n > 0 && ident.flat * 1.0 / ident.n < 0.02 && ctrl.flat > ours.flat * 2
  println("   ${if (teeth) "尺有牙 ✓（两端分得开）" else "!! 两端分不开，这本账不能信：恒等=$id 2档=$c 我们=${pct(ours.flat, ours.n)}"}")

  if (refDir.isDirectory) {
    val ref = Edge(); var nRef = 0
    for (m in refDir.listFiles()!!.filter { it.extension == "png" && it.name.endsWith("_mono.png") }.sortedBy { it.name }) {
      val base = File(refDir, m.name.removeSuffix("_mono.png") + ".png")
      val src = load(base, side) ?: continue
      val out = load(m, side) ?: continue
      val before = ref.n
      measure(src, out, side, min, ref)
      if (ref.n > before) nRef++
    }
    println("   设计师那一层 n=$nRef 抹平=${ref.flat}(${pct(ref.flat, ref.n)}) 几乎没区分=${ref.near}(${pct(ref.near, ref.n)})")
    if (nRef == 0) println("   !! 参照 0 张 —— 这一行没有对照，上面那个数只能自己跟自己比")
  } else println("   !! 没有 ${refDir.name}/ —— 没参照，这本账是「只量我们自己」")
  worst.sortedByDescending { it.second }.take(10).forEach { (nm, v) ->
    println("   ${"%.1f".format(v * 100)}%  $nm")
  }
}
