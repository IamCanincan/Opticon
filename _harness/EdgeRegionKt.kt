package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * 把「相邻像素要区分」拆成**该不该被看见**（2026-10-17）。
 *
 * 上一把尺 `EdgeFlatKt` 数出来：源里亮度真差 ≥9 的边，**我们抹平 13.0 %、设计师那一层抹平 88.4 %**
 * —— 按"边的数量"我们已经比人画的碎 6.8 倍。所以数量不是目标，目标是要分清：
 *
 *  · **病** = 设计师**保留**了这条边、我们**抹平**了 ⇒ 该看见的东西被并掉了；
 *  · **过碎** = 设计师**抹平**了、我们**保留** ⇒ 渐变盘上多出来的一道道（台阶尺那一族）；
 *  · 两边都保 / 两边都抹 = 不用管。
 *
 * ## 为什么只在**甲类**上做（自适应 + 背景是纯色盘）
 * 三方必须在**同一个坐标系**里比。设计师那层 `_mono.png` 是**可见区**几何（`GeoKt` 量死：
 * `mono↔fg = 0.939` 同框架、`mono↔model = 0.175` 差 1.5 倍构图），而 `_model.png` 是满幅。
 * 甲类的图形**全在前景层**，所以 `_fg.png`（可见区）就是它完整的源，三方齐了；
 * 乙类（背景是一张画）没有"可见区的合成源"这个文件，硬凑就要缩放设计师那层 ——
 * 那是本仓记过的方向错误（`iouMed 0.909→0.114`）。**所以乙类不进这本账， coverage 明写出来。**
 *
 * ## 自检
 * 把"我们"和"设计师"**对调**再跑一遍：`病` 与 `过碎` 必须跟着对调。
 * 不对调 = 两边比的是同一个数组（这类"自己跟自己比"的假绿，本仓已经吃过一次）。
 *
 * 用法：`harness.EdgeRegionKtKt [yard_model] [--min=9] [--keep=32]`
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

private fun luma(p: Int): Int {
  val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
  return (r * 299 + g * 587 + b * 114) / 1000
}
private fun alpha(p: Int): Int = (p ushr 24) and 0xff

private class Cell { var n = 0L; var sick = 0L; var over = 0L; var both = 0L; var neither = 0L }

/** 对每条"源里亮度真不同"的边，按两边的行为分四格。`mark` 非空时把**病边**的两格都点上。 */
private fun classify(src: IntArray, ours: IntArray, theirs: IntArray, side: Int, min: Int, keep: Int, c: Cell,
                     mark: Array<Boolean>? = null) {
  for (y in 0 until side) for (x in 0 until side) {
    val i = y * side + x
    for (j in intArrayOf(if (x + 1 < side) i + 1 else -1, if (y + 1 < side) i + side else -1)) {
      if (j < 0) continue
      if (alpha(src[i]) < 128 || alpha(src[j]) < 128) continue
      if (abs(luma(src[i]) - luma(src[j])) < min) continue
      c.n++
      val o = abs(alpha(ours[i]) - alpha(ours[j]))
      val t = abs(alpha(theirs[i]) - alpha(theirs[j]))
      val ok = o >= keep; val tk = t >= keep
      val of = o == 0; val tf = t == 0
      when {
        tk && of -> {
          c.sick++
          if (mark != null) { mark[i] = true; mark[j] = true }
        }
        tf && ok -> c.over++        // 设计师抹平、我们保留 = 过碎
        ok && tk -> c.both++
        of && tf -> c.neither++
        else -> c.neither++          // 半保半糊：两把都不干净，算进"不用管"但单独看得见
      }
    }
  }
}

/** 四联出图：源(可见区) | 我们 | 设计师 | **病边标红**。数字说"该看见的东西丢了"，得能看见是哪一块。 */
private fun shot(out: File, pkg: String, src: IntArray, ours: IntArray, theirs: IntArray,
                 sick: Array<Boolean>, edge: Int) {
  val n = edge * 4 + 10
  val img = java.awt.image.BufferedImage(n, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color.WHITE; g.fillRect(0, 0, n, edge); g.dispose()
  fun panel(base: Int, px: IntArray, ink: Boolean) {
    val bi = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val a = IntArray(edge * edge)
    for (i in a.indices) {
      val v = if (ink) 255 - ((px[i] ushr 24) and 0xff) else luma(px[i])
      a[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
    }
    bi.setRGB(0, 0, edge, edge, a, 0, edge)
    val gg = img.createGraphics(); gg.drawImage(bi, base, 0, null); gg.dispose()
  }
  panel(0, src, false); panel(edge + 2, ours, true); panel(2 * edge + 4, theirs, true)
  val d = IntArray(edge * edge)
  for (i in 0 until edge * edge) {
    val v = 255 - ((ours[i] ushr 24) and 0xff)
    d[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
  }
  for (i in 0 until edge * edge) if (sick[i]) { d[i] = -65536 /* 不透明红 */ }
  val bi = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  bi.setRGB(0, 0, edge, edge, d, 0, edge)
  val gg = img.createGraphics(); gg.drawImage(bi, 3 * edge + 6, 0, null); gg.dispose()
  javax.imageio.ImageIO.write(img, "png", File(out, pkg + "_edges.png"))
}

/** `--hist=<pkg>`：把"源亮度 → 交付 alpha"这张映射表打出来 —— 眼睛被填平这件事，
 *  只有按源亮度分桶看交付值才知道是**谁**把它抬上去的（不先看这一张就去怪某条分支，本仓错过一次）。 */
private fun hist(pkg: String, src: IntArray, ours: IntArray, theirs: IntArray, edge: Int) {
  val buckets = 16
  val n = LongArray(buckets); val o = LongArray(buckets); val t = LongArray(buckets)
  for (i in 0 until edge * edge) {
    if (alpha(src[i]) < 128) continue                 // 空气不算
    val b = (luma(src[i]) * buckets / 256).coerceIn(0, buckets - 1)
    n[b]++; o[b] += alpha(ours[i]); t[b] += alpha(theirs[i])
  }
  println("   $pkg 源亮度→交付 α（只数源里画了东西的格；α 越大越黑）")
  println("   源亮度档      格数    我们    设计师")
  for (b in 0 until buckets) if (n[b] > 0) {
    println("   %2d-%2d      %7d  %5.0f  %5.0f".format(
      b * 256 / buckets, (b + 1) * 256 / buckets - 1, n[b], o[b].toDouble() / n[b], t[b].toDouble() / n[b]))
  }
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "yard_model")
  val min = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 9
  val keep = args.firstOrNull { it.startsWith("--keep=") }?.substringAfter("=")?.toInt() ?: 32
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let { File(it).apply { mkdirs() } }
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")?.split(",")?.toSet() ?: emptySet()
  val histPkg = args.firstOrNull { it.startsWith("--hist=") }?.substringAfter("=") ?: ""
  val index = File(dir, "index.tsv")
  if (!index.isFile) { println("!! 缺 ${index.path}"); exitProcess(2) }
  // ⚠ Kotlin **没有** `/…/` 正则字面量（那是 JS 的写法，编译器会在第一个 `/` 上就断）
  val rows = index.readText().replace("\r\n", "\n").split('\n').drop(1)
    .filter { it.isNotBlank() }.map { it.split('\t') }
  val c = Cell(); val swap = Cell()
  val worst = ArrayList<Pair<String, Double>>()
  var pairs = 0; var skipped = 0
  for (r in rows) {
    if (r.size < 19) { skipped++; continue }
    val isJia = r[5].contains("Adaptive") && r[7] == "ColorDrawable"
    val mono = File(dir, r[12])
    val modelName = if (r[17] != "-" && r[17] != "null" && r[17] != "err") r[17] else r[2]
    val model = File(dir, modelName)
    if (!isJia || !mono.isFile || !model.isFile) { skipped++; continue }
    val edge = r[3].toInt()
    // ⚠ 源**不能**去找 `_fg.png` —— `yard_model` 里根本没有这种文件（现数：只有 63 张裸合成图 +
    //   92 张 `_model` + 57 张 `_modelbg` + 62 张 `_mono`）。第一版按 `r[15]` 的 fgFile 去要，
    //   结果**一张都配不上**、报"甲类配对 0 张"。
    // 三方同框架的做法：源 = 对**满幅的模型输入**用同一个 `toVisible` 取中心 2/3。
    // 甲类的模型输入就是"前景层满幅"，所以这一刀切出来的正是桌面真正看到的那一块前景。
    val modelPx = load(model, edge); if (modelPx == null) { skipped++; continue }
    val res = runCatching { MonochromeMask.build(modelPx, edge, edge, true, null) }.getOrNull()
    val oursRaw = res?.mask; if (oursRaw == null) { skipped++; continue }
    val ours = toVisible(oursRaw, edge); if (ours == null) { skipped++; continue }
    val theirs = load(mono, edge); if (theirs == null) { skipped++; continue }
    val src = toVisible(modelPx, edge); if (src == null) { skipped++; continue }
    pairs++
    val before = c.sick; val beforeN = c.n
    val mark = if (shotDir != null && (only.isEmpty() || r[0] in only)) Array(edge * edge) { false } else null
    classify(src, ours, theirs, edge, min, keep, c, mark)
    classify(src, theirs, ours, edge, min, keep, swap)     // 自检：两格必须对调
    if (mark != null) shot(shotDir!!, r[0], src, ours, theirs, mark, edge)
    if (r[0] == histPkg) hist(r[0], src, ours, theirs, edge)
    // ⚠ 分母必须是**这一张自己的**边数：写成累计的 `c.n` 会让排在后面的图标
    //   被前面几万条边稀释，最差的那几张反而看不见。
    val mine = c.n - beforeN
    if (mine > 0) worst += r[0] to (c.sick - before).toDouble() / mine
  }
  if (pairs == 0) { println("!! 甲类配对 0 张 —— 这本账没开火（coverage 不够，不是「没问题」）"); exitProcess(1) }
  val p = fun(v: Long) = "%.1f".format(v * 100.0 / c.n) + "%"
  println("ER n=$pairs 跳过=$skipped 源亮度差≥$min 的边=${c.n}")
  println("   病（设计师保住、我们抹平）= ${c.sick} (${p(c.sick)})")
  println("   过碎（设计师抹平、我们保住）= ${c.over} (${p(c.over)})")
  println("   都保住 = ${c.both} (${p(c.both)})   都抹平/半糊 = ${c.neither} (${p(c.neither)})")
  val ok = swap.sick == c.over && swap.over == c.sick
  println("   ${if (ok) "✓ 对调自检：病↔过碎 跟着对调（${swap.sick}/${swap.over}）" else "!! 对调自检不成立：${swap.sick}/${swap.over} vs ${c.over}/${c.sick} —— 两边可能在比同一个数组，这本账不能信"}")
  worst.filter { it.second > 0 }.sortedByDescending { it.second }.take(10).forEach { (nm, v) ->
    println("   ${"%.1f".format(v * 100)}%  $nm")
  }
  exitProcess(if (ok) 0 else 1)
}
