package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 量「甲」：把彩色那支的裁剪窗口搬到**已经交付的 alpha 掩码**上，看两件事 ——
 *   1. 块头有没有真的对齐（墨的外接框占整幅的比例，前后各一个数）；
 *   2. 重采样有没有把边缘弄糊（半透明格占比 + 相邻格最大跳变的分布）。
 *
 * 为什么单独一个驱动：判据台其余七段量的是**浓度模型**，而这一刀刻意不碰浓度（只搬位置），
 * 所以那 14 闸绿不绿与它无关 —— 它自己的判据必须另立一把尺，否则"没红"就等于"没看"。
 *
 * 用法：`harness.GeomKtKt <语料目录> <.alpha 目录> <输出png> [--edge=384] [--only=名子串,名子串]`
 * ⚠ `.alpha` 由 `harness.DumpKtKt <语料> <out>` 落盘（那份走的是简化输入，`盘刀` 的 under 不在里面；
 *   本驱动只关心**几何与边缘**，所以够用 —— 但别拿这里的实心率去和真机判据行对照）。
 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val adir = File(args[1]); val outPng = File(args[2])
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")?.split(",")?.toSet()
  val plate = 0xFFE8E8E8.toInt()   // 桌面浅色盘，只为出图能看
  val ink = 0xFF202020.toInt()

  var nWin = 0; var nTot = 0; var bogus = 0; var nCutOnly = 0
  // 与 `MonochromeSynth.visibleInset` 同一口径（本机 extraInset f = 1/4 → 边长 /6）。
  // 判据台的桩里没有 AdaptiveIconDrawable，所以这里按常数算；改了那边要改这里。
  val inset = (edge * 0.25f / 1.5f).toInt()
  // 哪张是自适应图标，语料自己的 index.tsv 里写着（第 3 列文件名 / 第 6 列类名）——
  // 只有自适应才有 1.5 倍那层展开，非自适应不能照搬。
  val cls: Map<String, String> = File(dir, "index.tsv").let { ix ->
    if (!ix.isFile) emptyMap() else ix.readLines().drop(1).mapNotNull { l ->
      val c = l.split("\t"); if (c.size > 5) c[2] to c[5] else null
    }.toMap()
  }
  val rows = ArrayList<Pair<BufferedImage, BufferedImage>>()
  println("%-46s %-6s %-7s %-9s %-9s %-9s %-9s %-9s".format(
    "图标", "档", "放大", "块头前", "块头后", "半透前", "半透后", "溢出可见区"))
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.endsWith("_modelbg.png") }.sortedBy { it.name }) {
    val a = File(adir, f.nameWithoutExtension + ".alpha")
    if (!a.isFile) continue
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    val bytes = ByteArray(edge * edge).also { b ->
      java.io.FileInputStream(a).use { it.read(b) } }
    // ⚠ 括号是必需的：Kotlin 里 `shl` 与 `or` 是同优先级左结合，
    //   `a or 0x00FFFFFF shl 24` 会先算 `0x00FFFFFF shl 24`（溢出成 0）再 or —— 于是每个像素
    //   alpha 都变成 255，块头/半透两列全 0，**一把永远不响的尺**（第一版就这么绿着出了 13 行）。
    val mask = IntArray(edge * edge) { ((bytes[it].toInt() and 0xFF) shl 24) or 0x00FFFFFF }
    nTot++
    // ⚠ 窗口必须量在**彩色那支量的那个对象**上：自适应图标的子层按 1.5 倍铺进视口、裁掉四边，
    //   而语料里存的是"模型真吃的那一层"（1.0 倍铺满）。直接量这一层会把倍数整体放大约 1.5×
    //   （元宝 2.26× vs 彩色实际 1.49×）—— 与 `MonochromeSynth.geometryWindow` 同一口径。
    val win0 = (if (isAdaptive(f.name, cls)) IconCropWindow.of(expandLayer(px, edge), edge, edge)?.inLayer(0.25f)
    else IconCropWindow.of(px, edge, edge)) ?: continue
    // 与产品同一口径：**只搬"缩进"那一档**，放大那一档在盘上没有理由（彩色那支放大是为了"圆要完整"）。
    if (!win0.plate) { nCutOnly++; continue }
    val win = win0
    if (only != null && only.none { f.nameWithoutExtension.contains(it) }) { nWin++; continue }
    nWin++
    val moved = IconCropWindow.reframe(mask, edge, edge, win, inset, inset, edge - 2 * inset, edge - 2 * inset)
    val before = stats(mask, edge, inset); val after = stats(moved, edge, inset)
    // 自检：交付掩码不可能"四边全贴 + 一个半透明格都没有"。真出现就是**读掩码的方式错了**
    // （或者 .alpha 是上一轮的），而这张表会看起来完全健康 —— 本仓吃过好几次这种亏。
    if (before.span > 0.999 && before.soft < 1e-9) bogus++
    println("%-46s %-6s %-7s %-9s %-9s %-9s %-9s %-9s".format(
      f.nameWithoutExtension.take(46), if (win.plate) "缩进" else "内切",
      "%.3f".format(win.zoom),
      "%.1f%%".format(before.span * 100), "%.1f%%".format(after.span * 100),
      "%.2f%%".format(before.soft * 100), "%.2f%%".format(after.soft * 100),
      "%.2f%%".format(after.over * 100)))
    rows += render(mask, edge, inset, plate, ink) to render(moved, edge, inset, plate, ink)
  }
  println("共 $nTot 张有掩码，其中 $nWin 张**会搬**（缩进那一档）；另有 $nCutOnly 张彩色放大了它、染色不跟（放大档未搬）")
  if (bogus > 0) {
    println("!! $bogus 行的读数不可能（墨贴满四边且没有一个半透明格）—— 量具自己坏了，这一轮不作数")
    kotlin.system.exitProcess(1)
  }
  if (rows.isEmpty()) { println("没有可出的行 —— --only 的名单没匹配上"); return }
  val cell = 200; val lab = 26
  val canvas = BufferedImage(cell * rows.size, (cell + lab) * 2, BufferedImage.TYPE_INT_RGB)
  val g = canvas.createGraphics()
  g.color = java.awt.Color.WHITE; g.fillRect(0, 0, canvas.width, canvas.height)
  for ((i, r) in rows.withIndex()) {
    g.drawImage(scale(r.first, cell, cell), i * cell, lab, null)
    g.drawImage(scale(r.second, cell, cell), i * cell, cell + lab * 2, null)
  }
  g.color = java.awt.Color.DARK_GRAY
  g.drawString("现状（未重排的交付掩码）", 4, 17)
  g.drawString("甲：按裁圆窗口重排之后", 4, cell + lab + 17)
  ImageIO.write(canvas, "png", outPng)
  println("写出 ${outPng.absolutePath}  ${rows.size} 列")
}

class Stats(val span: Double, val soft: Double, val solid: Double, val over: Double)

/**
 * 块头 = 墨的外接框最长边 / **可见区边长**（>100% 就是已经出界）；
 * 半透 = alpha 1..254 的格占比；溢出 = 实心墨落在可见区之外的占比（屏幕上会被桌面切掉的那部分）。
 */
private fun stats(mask: IntArray, edge: Int, inset: Int): Stats {
  val side = edge - 2 * inset
  var x0 = edge; var x1 = -1; var y0 = edge; var y1 = -1
  var soft = 0; var solid = 0; var over = 0; var ink = 0
  for (y in 0 until edge) for (x in 0 until edge) {
    val a = mask[y * edge + x] ushr 24
    if (a == 0) continue
    if (a in 1..254) soft++
    if (a == 255) solid++
    if (a >= 128) {
      ink++
      if (x < inset || y < inset || x >= edge - inset || y >= edge - inset) over++
    }
    if (x < x0) x0 = x; if (x > x1) x1 = x
    if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) return Stats(0.0, 0.0, 0.0, 0.0)
  val span = maxOf(x1 - x0 + 1, y1 - y0 + 1).toDouble() / side
  val n = edge * edge
  return Stats(span, soft.toDouble() / n, solid.toDouble() / n,
    if (ink == 0) 0.0 else over.toDouble() / ink)
}

private fun render(mask: IntArray, edge: Int, inset: Int, plate: Int, ink: Int): BufferedImage {
  // 只画**可见区**：整张 108dp 画布里有 1/3 是桌面根本不画的边，按整张出图会把差异冲淡一半。
  val side = edge - 2 * inset
  val bi = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until side) for (x in 0 until side) {
    val a = (mask[(y + inset) * edge + x + inset] ushr 24) / 255.0
    val r = (((plate shr 16) and 0xFF) * (1 - a) + ((ink shr 16) and 0xFF) * a).toInt()
    val gg = (((plate shr 8) and 0xFF) * (1 - a) + ((ink shr 8) and 0xFF) * a).toInt()
    val b = ((plate and 0xFF) * (1 - a) + (ink and 0xFF) * a).toInt()
    bi.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (gg shl 8) or b)
  }
  return bi
}

private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
  val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
  val g = out.createGraphics()
  g.drawImage(src, 0, 0, w, h, null); g.dispose()
  return out
}

private fun isAdaptive(name: String, cls: Map<String, String>): Boolean =
  (cls[name] ?: "").contains("Adaptive")

/**
 * 把"层"按 `AdaptiveIconDrawable.draw()` 的摆法铺成"整图标"：层占 1.5 倍画布、居中、四边裁掉
 * （f = extraInset = 1/4）。桩里没有 AdaptiveIconDrawable，这一手就是它的替身 ——
 * 改了那边（`geometryWindow`）要改这里。
 */
private fun expandLayer(px: IntArray, edge: Int): IntArray {
  val out = IntArray(px.size)
  for (y in 0 until edge) for (x in 0 until edge) {
    val u = (x.toFloat() / edge + 0.25f) / 1.5f
    val v = (y.toFloat() / edge + 0.25f) / 1.5f
    if (u > 1f || v > 1f) continue                 // 层外 = 透明
    val sx = (u * (edge - 1)).toInt().coerceIn(0, edge - 1)
    val sy = (v * (edge - 1)).toInt().coerceIn(0, edge - 1)
    out[y * edge + x] = px[sy * edge + sx]
  }
  return out
}
