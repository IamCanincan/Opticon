package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 快捷方式「彩色 vs 单色」**屏上尺寸**对表（2026-10-25）。
 *
 * 口径（两边都折算到**可见区 256px 的槽位**）：
 *  · 彩色：源按窗口 cover 进槽位；细环那张 = 按满幅（先去掉 ≥0.90R 的环带再量图案）；
 *  · 单色：源 → `reframe(win)` → 真模型；细环那张**不再收可见区**（窗口倍率已把图层换回槽位），
 *    其余非自适应**收一道**（`intoVisibleArea`，屏上占比因此 = 掩码框/edge）；
 *    细环那张屏上占比 = 掩码框/edge × 1.5。
 *
 * 只对**彩色有形状边界**的那两类判差：窗口档（补白/内切）与细环满幅档。
 * 整幅不透明的快捷方式（彩色本身就是一块底）与单色剪影的外框不是同一个东西，单列、不比。
 *
 * 用法：`sh run.sh harness.AlignSizeKtKt shortcut_bit [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val slot = edge * 2 / 3          // 可见区 = 图层 ÷ 1.5（@384 → 256）
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "shortcut_bit")
  println("edge=$edge 槽位=$slot；彩色=框/槽位；单色=掩码框/$edge（非细环）或 ×1.5（细环，不收可见区）")
  println("%-46s %-6s %-8s %-8s %-7s %-9s".format("图", "档", "彩色", "单色", "差", "窗口"))
  var n = 0; var cmp = 0; var bad = 0; var tiles = 0
  for (f in dir.listFiles()!!.filter { it.extension.equals("png", true) }.sortedBy { it.name }) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val (cw, ch) = IconCropWindow.scanSize(img.width, img.height, false)
    val cpx = raster(img, cw, ch)
    val win = IconCropWindow.of(cpx, cw, ch)
    val ring = IconCropWindow.edgeRing(cpx, cw, ch)
    val full = IconCropWindow.shortcutFullBleed(win, cpx, cw, ch)   // 细环满幅（彩色与生产共用）
    // ── 彩色：窗口 → 槽位；细环先去环带（与彩色遮罩的可见圆同一个圈）──
    val clean = if (ring) maskRing(cpx, cw, ch) else cpx
    val atSlot = resize(clean, cw, ch, slot, slot)
    val mapped = if (win == null || full) atSlot
      else IconCropWindow.reframe(atSlot, slot, slot, win, 0, 0, slot, slot)
    // 细环那类：彩色那块「底」是不透明的淡灰盘，alpha 框量到的是盘（~79%），不是图案；
    // 图案是彩色的（红气泡），按**彩度**量才和单色剪影是同一个东西。
    val colorFrac = (if (full) chromaBox(mapped, slot) else box(mapped, slot)) ?: 0f
    // ── 单色：生产同一条路（窗口输入不改，只有"收不收可见区"按 full 定）──
    val mpx = raster(img, edge, edge)
    val min = if (win == null) mpx else IconCropWindow.reframe(mpx, edge, edge, win)
    val out = MonochromeMask.build(min, edge, edge, false)
    val maskBox = out.mask?.let { box(it, edge) } ?: 0f
    val monoFrac = maskBox * (if (full) 1.5f else 1f)
    val d = monoFrac - colorFrac
    n++
    // 可比的三类：源有形状边界的窗口档、细环档。两类**不可比**，单列：
    //  · 整幅底（彩色本身就是一块不透明底/满圆）→ 彩色的框是底，单色是提取出的图案；
    //  · 细环那张模型把淡灰盘也交成墨（掩码 ≥90%）→ 形状问题，不是尺寸问题（拼多多 search_express）。
    val shaped = (full || win != null) && colorFrac < 0.98f && !(full && monoFrac >= 0.9f)
    if (!shaped) tiles++
    if (shaped) { cmp++; if (kotlin.math.abs(d) > 0.04f) bad++ }
    println("%-46s %-6s %-8s %-8s %+7.0f %-9s".format(
      f.name.take(46), if (full) "细环满幅" else if (win == null) "整幅底" else if (win.plate) "补白" else "内切",
      "%.0f%%".format(colorFrac * 100), "%.0f%%".format(monoFrac * 100), d * 100,
      win?.let { "%.2f".format(it.zoom) } ?: "满幅"))
  }
  println("共 $n 张；有形状边界、参与判差 $cmp 张；差 >4 个点 $bad 张；不可比（整幅底/盘入墨）$tiles 张")
  if (bad > 0) kotlin.system.exitProcess(1)
}

/** 外接框占画布（取长边）的比例；alpha<8 视为空。 */
private fun box(px: IntArray, side: Int): Float? {
  var x0 = side; var y0 = side; var x1 = -1; var y1 = -1
  for (y in 0 until side) for (x in 0 until side) {
    if ((px[y * side + x] ushr 24) < 8) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x
    if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) return null
  return maxOf(x1 - x0 + 1, y1 - y0 + 1).toFloat() / side
}

/** 彩度框（max-min ≥ 40）：细环那类的图案是彩色气泡，底是淡灰盘 —— 按 alpha 量会把盘当图案。 */
private fun chromaBox(px: IntArray, side: Int): Float? {
  var x0 = side; var y0 = side; var x1 = -1; var y1 = -1
  for (y in 0 until side) for (x in 0 until side) {
    val p = px[y * side + x]
    if ((p ushr 24) < 64) continue
    val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
    if (maxOf(r, g, b) - minOf(r, g, b) < 40) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x
    if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) return null
  return maxOf(x1 - x0 + 1, y1 - y0 + 1).toFloat() / side
}

/** 把 ≥0.90R 的环带抹掉（只用于彩色侧的图案框；图案最远 0.58R，碰不到）。 */
private fun maskRing(px: IntArray, w: Int, h: Int): IntArray {
  val out = px.copyOf()
  val r = minOf(w, h) / 2f
  val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
  val r2 = (r * 0.80f) * (r * 0.80f)
  for (y in 0 until h) for (x in 0 until w) {
    val dx = x - cx; val dy = y - cy
    if (dx * dx + dy * dy >= r2) out[y * w + x] = 0
  }
  return out
}

private fun raster(img: java.awt.image.BufferedImage, w: Int, h: Int): IntArray {
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

private fun resize(px: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
  val out = IntArray(tw * th)
  for (y in 0 until th) for (x in 0 until tw) {
    val sy = (y.toLong() * h / th).toInt().coerceAtMost(h - 1)
    val sx = (x.toLong() * w / tw).toInt().coerceAtMost(w - 1)
    out[y * tw + x] = px[sy * w + sx]
  }
  return out
}
