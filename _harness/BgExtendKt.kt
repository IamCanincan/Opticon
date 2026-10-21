package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * 量「缩进补白」那一档的图标**自己有没有一块可延伸的底**（不是纯透明 logo）。
 *
 * 为什么要有它：主人问「能否判断协调性来缩放并**延伸图标背景**」。第一件要回答的不是"能不能做"，
 *   而是"**有多少张图标的底本来就是可延伸的**" —— 纯透明 logo 没有底可延，硬造就是候选 B 的坑。
 *   这一趟只量、不改任何判据。
 *
 * 口径（与 `WinKt.kt` 同一批格子，128 格，最近邻）：
 *  1. 图重排到 128 → 过**真** `IconCropWindow.of` → 拿 `Window`。
 *  2. **还原外框矩形**：`Window` 是绕外框中心造的，`boxW/boxH` 就是外框边长
 *     ⇒ 中心 = ((l+r)/2, (t+b)/2) × 128，矩形 = 中心 ± 边长/2。只对 `plate=true` 那档成立。
 *  3. 在**外框的边框带**上量（四条边各往里 3 格、四角各让开 4 格，免得被圆角/抗锯齿带偏）：
 *     - `ringOpaque` 边框带上 alpha≥128 的格占几成 —— 底有没有"贴到框边"
 *     - `ringSpread` 边框带上不透明格的**通道极差 p95−p5**（三通道取最大）—— 越小越像"一块纯色"
 *     - `clear`      整张画布 alpha<8 的占比（透明率）—— 纯透明 logo 这个数很大
 *  4. 交叉口径：**四角各取 6×6 补丁**，记四角最小实心率 `cornerMin` 与四角平均色的通道极差 `cornerSpread`。
 *
 * ⚠ 这几个数**只报不判**：线要摆在散布的空档里 —— 先看分布，再定线（仓规）。
 *
 * 用法：`sh run.sh harness.BgExtendKt <png 或目录...> [--edge=N]`（目录要自己展开，语料 700 多张）
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt()
    ?: IconCropWindow.scanEdge
  // 目录展开与 WinKt 逐字一致（同一批排除规则），否则两次读数对不上
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) {
      f.listFiles()!!.filter {
        it.extension.equals("png", true) && !it.name.contains("preview") &&
          !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") &&
          !it.name.endsWith("_ab.png")
      }.sortedBy { it.name }
    } else listOf(f)
  }
  println("%-14s %-9s %-6s %-6s %-10s %-11s %-7s %-14s %-13s %-8s".format(
    "图", "档", "boxW", "boxH", "ringOpaque", "ringSpread", "clear", "cornerMinOpaque", "cornerSpread", "底色"))

  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull()
    if (img == null) { println("%-14s %s".format(f.name, "读不出")); continue }
    val sw = img.width
    val sh = img.height
    val s0 = IntArray(sw * sh)
    img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    val w = IconCropWindow.of(px, edge, edge)
    if (w == null) { println("%-14s %s".format(f.name, "满幅/不动")); continue }
    if (!w.plate) {
      println("%-14s %-9s %-6d %-6d".format(f.name, "内切填满", w.boxW, w.boxH))
      continue
    }

    // 还原外框（只对 plate 那档成立）
    val cx = ((w.l + w.r) / 2f * edge).roundToInt()
    val cy = ((w.t + w.b) / 2f * edge).roundToInt()
    val x0 = (cx - w.boxW / 2f).roundToInt().coerceIn(0, edge - 1)
    val x1 = (x0 + w.boxW - 1).coerceIn(0, edge - 1)
    val y0 = (cy - w.boxH / 2f).roundToInt().coerceIn(0, edge - 1)
    val y1 = (y0 + w.boxH - 1).coerceIn(0, edge - 1)
    if (x1 - x0 < 10 || y1 - y0 < 10) {
      println("%-14s %-9s %s".format(f.name, "缩进补白", "框太小，不量"))
      continue
    }

    var clear = 0
    for (i in 0 until edge * edge) if ((px[i] ushr 24) < 8) clear++
    val clearPct = clear * 100 / (edge * edge)

    // 边框带
    val inset = 3
    val cornerSkip = 4
    var ringTot = 0
    var ringOpaque = 0
    val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
    fun take(x: Int, y: Int) {
      ringTot++
      val p = px[y * edge + x]
      if ((p ushr 24) >= 128) {
        ringOpaque++
        hr[(p ushr 16) and 0xFF]++
        hg[(p ushr 8) and 0xFF]++
        hb[p and 0xFF]++
      }
    }
    for (x in (x0 + cornerSkip)..(x1 - cornerSkip)) for (d in 0 until inset) { take(x, y0 + d); take(x, y1 - d) }
    for (y in (y0 + cornerSkip)..(y1 - cornerSkip)) for (d in 0 until inset) { take(x0 + d, y); take(x1 - d, y) }
    if (ringTot == 0) { println("%-14s %s".format(f.name, "缩进补白")); continue }
    val ringPct = ringOpaque * 100 / ringTot

    fun pct(h: IntArray, total: Int, p: Double): Int {
      if (total <= 0) return 0
      val target = (total * p).roundToInt()
      var acc = 0
      for (v in 0..255) { acc += h[v]; if (acc >= target) return v }
      return 255
    }
    val spread = if (ringOpaque > 0) maxOf(
      pct(hr, ringOpaque, 0.95) - pct(hr, ringOpaque, 0.05),
      pct(hg, ringOpaque, 0.95) - pct(hg, ringOpaque, 0.05),
      pct(hb, ringOpaque, 0.95) - pct(hb, ringOpaque, 0.05)) else -1
    val dom = if (ringOpaque > 0) String.format(Locale.ROOT, "#%02X%02X%02X",
      pct(hr, ringOpaque, 0.5), pct(hg, ringOpaque, 0.5), pct(hb, ringOpaque, 0.5)) else "-"

    // 四角 6×6 补丁（交叉口径）
    val patch = 6
    val frac = FloatArray(4)
    val mr = FloatArray(4); val mg = FloatArray(4); val mb = FloatArray(4)
    val xs = intArrayOf(x0, x1 - patch + 1)
    val ys = intArrayOf(y0, y1 - patch + 1)
    for (k in 0 until 4) {
      val bx = xs[k % 2]
      val by = ys[k / 2]
      var op = 0; var sr = 0L; var sg = 0L; var sb = 0L
      for (yy in by until by + patch) for (xx in bx until bx + patch) {
        if (xx !in 0 until edge || yy !in 0 until edge) continue
        val p = px[yy * edge + xx]
        if ((p ushr 24) >= 128) {
          op++
          sr += (p ushr 16) and 0xFF; sg += (p ushr 8) and 0xFF; sb += p and 0xFF
        }
      }
      frac[k] = op / (patch * patch).toFloat()
      mr[k] = if (op > 0) sr.toFloat() / op else 0f
      mg[k] = if (op > 0) sg.toFloat() / op else 0f
      mb[k] = if (op > 0) sb.toFloat() / op else 0f
    }
    val cornerMin = frac.min()
    val cornerSpread = if (cornerMin > 0f)
      maxOf(mr.max() - mr.min(), mg.max() - mg.min(), mb.max() - mb.min()) else -1f

    println("%-14s %-9s %-6d %-6d %-9d%% %-11d %-6d%% %-13d%% %-12.0f %-8s".format(
      f.name, "缩进补白", w.boxW, w.boxH, ringPct, spread, clearPct,
      (cornerMin * 100).roundToInt(), cornerSpread, dom))
  }
}
