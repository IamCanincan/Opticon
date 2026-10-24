package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * 应用图标（非快捷方式）的**两格几何全量对表**（2026-10-25，主人「还有应用图标，全量检查」）。
 *
 * 彩色那支量窗口的格子是 `scanSize(原尺寸, ≤128)`；染色那支（非 fgOnly）量的是 384 的判据格。
 * 快捷方式那一轮已经证明**同一条判据在两格上会给出相反结论**（45 张里 2 张）；应用图标是
 * 同一个风险，只是没人量过。这张表逐张给：
 *  · 彩色窗（彩色格）/ 染色窗（384 格）的**有没有**、档位、跨度；
 *  · 屏上占比：彩色 = cb/窗跨（满幅 = cb）；染色 = cb/窗跨（满幅 = cb）——
 *    非自适应两边同一口径；adaptive+flatten 彩色要 ×1.5（`DeckKt` 的换算）。
 * 差 >4 个点或"一边有窗一边满幅"的进点名表。
 *
 * 用法：`sh run.sh harness.AppGeomKtKt rec_model corpus_runtime [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  println("edge=$edge；非自适应：彩色格 vs 384 判据格；adaptive+flatten 按 DeckKt 换算")
  println("%-46s %-8s %-9s %-9s %-9s %-8s %-8s %s".format(
    "图", "类别", "彩窗(跨)", "染窗(跨)", "彩窗@判", "占盘彩", "占盘染", "差/备注"))
  var n = 0; var bad = 0; var branchDiff = 0
  for (d in dirs) {
    val cls = HashMap<String, String>()
    val flat = HashSet<String>()
    File(d, "index.tsv").takeIf { it.isFile }?.readLines()?.drop(1)?.forEach { l ->
      val c = l.split('\t')
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
    val files = d.listFiles()?.filter {
      it.extension.equals("png", true) && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") && !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val adaptive = (cls[f.name] ?: "").contains("Adaptive")
      val flatten = flat.contains(f.name)
      val fgOnly = adaptive && !flatten
      val px384 = rasterTo(img, edge, edge)
      val cbBox = inkBox(px384, edge) ?: continue
      val cb = max(cbBox[2] - cbBox[0], cbBox[3] - cbBox[1]).toFloat() / edge
      if (fgOnly) continue   // 生产：fgOnly 的染色窗口恒空，与彩色整体满幅一致（DeckKt 已对过）
      val (cw, ch) = IconCropWindow.scanSize(img.width, img.height, false)
      val cpx = rasterTo(img, cw, ch)
      val wc = if (adaptive) IconCropWindow.of(expand(cpx, cw), cw, ch) else IconCropWindow.of(cpx, cw, ch)
      val wmRaw = IconCropWindow.of(px384, edge, edge)
      val wm = wmRaw?.takeIf { it.zoom <= 1.82f }   // 生产 `measure` 的上限
      val colorFrac = if (adaptive) {
        if (wc == null) cb * 1.5f else cb * 1.5f / span(wc)
      } else {
        if (wc == null) cb else cb / span(wc)
      }
      val monoFrac = if (adaptive) {
        if (wm == null) cb * 1.5f else cb / span(wm)
      } else {
        if (wm == null) cb else cb / span(wm)
      }
      val dlt = monoFrac - colorFrac
      // adaptive+flatten 的"彩色满幅 / 染色有窗"是已知等价：层的窗口 67% 折回屏上 = 1.0，
      // 与彩色整图标满幅是同一个跨度（DeckKt 的换算）。只有尺寸真差才算坏。
      val expectedFlat = adaptive && wc == null && wm != null && abs(dlt) <= 0.04f
      val nullDiff = (wc == null) != (wm == null) && !expectedFlat
      val branchDiffNow = wc != null && wm != null && wc.plate != wm.plate && !expectedFlat
      val badRow = nullDiff || branchDiffNow || abs(dlt) > 0.04f
      n++
      if (badRow) bad++
      if (nullDiff || branchDiffNow) branchDiff++
      val note = when {
        expectedFlat -> "flatten 满幅→窗（换算后同大）"
        nullDiff -> "**一边满幅一边有窗**"
        branchDiffNow -> "**档位相反**"
        abs(dlt) > 0.04f -> "差 ${"%+.0f".format(dlt * 100)}"
        else -> ""
      }
      println("%-46s %-8s %-9s %-9s %-9s %-8s %-8s %s".format(
        f.name.take(46), if (adaptive) (if (flatten) "flat" else "fgOnly") else "非自适应",
        wc?.let { "%.0f%%".format(span(it) * 100) } ?: "满幅",
        wm?.let { "%.0f%%".format(span(it) * 100) } ?: "满幅",
        if (adaptive) (wc?.let { "%.0f%%".format(span(it) * 100) } ?: "满幅") else "-",
        "%.0f%%".format(colorFrac * 100), "%.0f%%".format(monoFrac * 100), note))
    }
  }
  println("共 $n 张（fgOnly 不参与）；有差 $bad 张；其中\"满幅/有窗\"或档位相反 $branchDiff 张")
}

private fun span(w: IconCropWindow.Window) = max(w.r - w.l, w.b - w.t)

private fun inkBox(px: IntArray, edge: Int): IntArray? {
  var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
  for (y in 0 until edge) for (x in 0 until edge) {
    if ((px[y * edge + x] ushr 24) < 8) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x
    if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
}

/** 与 DeckKt / GeomKt 同源：把层按 AdaptiveIconDrawable.draw() 的摆法铺成整图标。 */
private fun expand(px: IntArray, edge: Int): IntArray {
  val f = 0.25f; val scale = 1f + 2f * f
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

private fun rasterTo(img: java.awt.image.BufferedImage, w: Int, h: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val sy = (y.toLong() * img.height / h).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / w).toInt().coerceAtMost(img.width - 1)
    px[y * w + x] = s0[sy * img.width + sx]
  }
  return px
}
