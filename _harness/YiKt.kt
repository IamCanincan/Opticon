package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 量「乙」：**把裁圆调整好的那张图标喂给模型**（而不是像甲那样只搬成品掩码的位置）。
 *
 * 为什么要单独一个驱动：甲与乙的差别只有一处 —— 模型的**输入**变不变。
 * 甲不动输入，所以 14 闸结构上不可能红；乙动输入，于是"哪几张的判据变了、变成什么"
 * 必须逐张量出来给人看，不能靠推演（这一族今天已经错过三次）。
 *
 * 用法：`harness.YiKtKt <语料目录> <输出png> [--edge=384]`
 * 输出：逐张 `现状 → 乙` 的底占/分支/墨量，只打**变了**的那些；末尾给计数与最坏几张。
 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val outPng = File(args[1])
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dumpDir = args.firstOrNull { it.startsWith("--dump=") }?.substringAfter("=")?.let { File(it) }
  val inset = (edge * 0.25f / 1.5f).toInt()          // 同 MonochromeSynth.visibleInset（f=1/4）
  val visT = edge - 2 * inset
  val nearest = args.contains("--nearest")
  val cropped = args.contains("--cropped")
  val plate = 0xFFE8E8E8.toInt(); val ink = 0xFF202020.toInt()

  val fgByFile = HashMap<String, Boolean>(); val plateByLayer = HashMap<String, Boolean>()
  File(dir, "index.tsv").let { ix ->
    if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
      val c = l.split('\t')
      if (c.size >= 6) fgByFile[c[2]] = c[5].contains("Adaptive")
      if (c.size >= 8) plateByLayer[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    }
  }

  var n = 0; var nWin = 0; var nDiff = 0
  val rows = ArrayList<Pair<BufferedImage, BufferedImage>>()
  val worst = ArrayList<Triple<String, Double, Double>>()
  println("%-40s %-24s %-24s %s".format("图标", "现状 底占/支/墨", "乙 底占/支/墨", "变了"))
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.endsWith("_modelbg.png") }.sortedBy { it.name }) {
    val px = load(f, edge) ?: continue
    val bgSib = File(dir, f.nameWithoutExtension + "_modelbg.png")
    val under0 = if (bgSib.isFile) load(bgSib, edge) else null
    n++
    val adaptive = fgByFile[f.name] == true
    val win = (if (adaptive) IconCropWindow.of(expandLayer(px, edge), edge, edge)?.inLayer(0.25f)
      else IconCropWindow.of(px, edge, edge))
    // 乙：输入按窗口重排（`under` 一起搬 —— 真机上整张图标都动了，不能只动一层）
    // `--nearest`：重排用最近邻，**不产生新的半透明像素**。双线性会造出一批中间浓度，
    //   而模型的底色场/分档吃的就是浓度分布 —— 第十一闸那次 xbox 换支（底占 0.45→0.00）
    //   与源尺寸漂移 81%→84% 都可能是这批新像素带来的，不是"搬位置"本身。
    val adj0 = if (win == null) px else if (nearest) reframeNearest(px, edge, edge, win, inset, visT)
    else IconCropWindow.reframe(px, edge, edge, win, inset, inset, visT, visT)
    // `--cropped`：**照字面做** —— 喂给模型的就是裁圆那张输出图。
    //   圆内保留重排后的内容、圆外透明；"裁不动"那一档再在圆内铺白底（和 MaskedDrawable 一样）。
    //   这才是主人那句话的直接实现，之前几版都在搬窗口、没搬那张图。
    val noPlate = args.contains("--noPlate")
  val allCropped = args.contains("--all")
    val adj = when {
      !cropped -> adj0
      allCropped -> bakeCrop(adj0, edge, inset, visT, win?.plate == true && !noPlate)
      win == null -> adj0
      else -> bakeCrop(adj0, edge, inset, visT, win.plate && !noPlate)
    }
    // `under` 不能一丢了事：`盘刀` 吃的就是它。裁圆把整张都裁了，那一层也得同样裁。
    val adjUnder = under0?.let { u ->
      val moved = if (win == null) u else if (nearest) reframeNearest(u, edge, edge, win, inset, visT)
      else IconCropWindow.reframe(u, edge, edge, win, inset, inset, visT, visT)
      if (!cropped) moved else bakeCrop(moved, edge, inset, visT, false)
    }
    // 乙的输入语料落盘（给 `CORPUS=… sh regress.sh` 当分母）。**窗口为 null 的那些逐字节原样拷**，
    // 否则闸门红了说不清是谁动的。
    dumpDir?.let { d ->
      // `--cropped` 不落语料：那条路的 `under` 已经被烘进图里，而判据台读目录里的 `_modelbg.png`
      // 就会再喂一遍底 —— 落一份"看起来对、其实口径不同"的语料比不落更坏。
      if (cropped) return@let
      d.mkdirs()
      File(d, f.name).writeBytes(if (win == null) f.readBytes() else encode(adj, edge))
      if (bgSib.isFile) File(d, bgSib.name).writeBytes(if (win == null) bgSib.readBytes()
      else encode(adjUnder ?: IntArray(edge * edge), edge))
    }
    if (win == null) continue
    nWin++
    val a = MonochromeMask.build(px, edge, edge, plateByLayer[f.name] == true, under0)
    val b = MonochromeMask.build(adj, edge, edge, plateByLayer[f.name] == true, adjUnder)
    val ia = inkOf(a?.mask, edge); val ib = inkOf(b?.mask, edge)
    val changed = a?.hyp != b?.hyp || kotlin.math.abs(ia - ib) > 0.02 || a?.mask == null != (b?.mask == null)
    if (!changed) continue
    nDiff++
    val name = f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "").take(40)
    println("%-40s %-24s %-24s %s".format(name,
      fmt(a, ia), fmt(b, ib), if (a?.hyp != b?.hyp) "换支" else "墨量"))
    if (a.mask != null && b.mask != null) {
      rows += render(a.mask, edge, plate, ink) to render(b.mask, edge, plate, ink)
      worst += Triple(name, ia, ib)
    }
  }
  dumpDir?.let { d ->
    File(d, "index.tsv").writeBytes(File(dir, "index.tsv").readBytes())   // 闸门要的分母与口径标记
    println("乙的输入语料写到 ${d.absolutePath}（${d.listFiles()!!.size} 个文件）")
  }
  println("\n共 $n 张，$nWin 张有窗口（乙会改输入的就是这些），其中判据变了 $nDiff 张")
  worst.sortByDescending { kotlin.math.abs(it.third - it.second) }
  println("墨量变化最大的 6 张（墨 = 交付的不透明面积占整幅）：")
  worst.take(6).forEach { println("  %-40s %.3f → %.3f".format(it.first, it.second, it.third)) }
  if (rows.isEmpty()) { println("没有可出的行"); return }
  val cell = 190; val lab = 24
  val canvas = BufferedImage(cell * 2, (cell + lab * 2) * rows.size, BufferedImage.TYPE_INT_RGB)
  val g = canvas.createGraphics()
  g.color = java.awt.Color.WHITE; g.fillRect(0, 0, canvas.width, canvas.height)
  for ((i, r) in rows.withIndex()) {
    g.drawImage(scale(r.first, cell, cell), 0, i * (cell + lab * 2) + lab, null)
    g.drawImage(scale(r.second, cell, cell), cell, i * (cell + lab * 2) + lab, null)
    g.color = java.awt.Color.DARK_GRAY
    g.drawString("甲/现状 ${rows.size - i} 张之一", 2, i * (cell + lab * 2) + 16)
    g.drawString("乙：模型吃调整好的图标", cell + 2, i * (cell + lab * 2) + 16)
  }
  ImageIO.write(canvas, "png", outPng)
  println("写出 ${outPng.absolutePath}  ${rows.size} 行 × 2 列")
}

private fun fmt(o: MonochromeMask.Output?, ink: Double): String =
  if (o == null) "放弃合成 / — / —"
  else "%.2f / H%d / %.3f".format(o.boxFill, o.hyp, ink)

/** ARGB 数组 → PNG 字节（判据台的语料就是 PNG，闸门读的是文件不是内存）。 */
private fun encode(px: IntArray, edge: Int): ByteArray {
  val bi = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  bi.setRGB(0, 0, edge, edge, px, 0, edge)
  val bo = java.io.ByteArrayOutputStream()
  ImageIO.write(bi, "png", bo)
  return bo.toByteArray()
}

/** 交付墨量 = 掩码 alpha 的均值（0..1）。极性翻掉时这个数会大幅跳。 */
private fun inkOf(mask: IntArray?, edge: Int): Double {
  if (mask == null) return -1.0
  var s = 0L
  for (v in mask) s += (v ushr 24)
  return s.toDouble() / (edge.toLong() * edge * 255L)
}

/**
 * 把重排后的图**烘成裁圆那张输出**：圆外透明；`plate` 那一档再往圆内铺白底
 * （与 `MaskedDrawable.draw()` 同一套：先铺白、再画内容、圆外 DST_OUT 掉）。
 */
private fun bakeCrop(px: IntArray, edge: Int, inset: Int, visT: Int, plate: Boolean): IntArray {
  val out = px.copyOf()
  val c = edge / 2f
  val r = visT / 2f
  val r2 = r * r
  for (y in 0 until edge) for (x in 0 until edge) {
    val dx = x + 0.5f - c; val dy = y + 0.5f - c
    val i = y * edge + x
    if (dx * dx + dy * dy > r2) { out[i] = 0; continue }
    if (plate && (out[i] ushr 24) < 8) out[i] = (0xFF shl 24) or 0x00FFFFFF   // 圆内的白底
  }
  return out
}

private fun reframeNearest(px: IntArray, w: Int, h: Int, win: IconCropWindow.Window, inset: Int, visT: Int): IntArray {
  val boxW = ((win.r - win.l) * w).coerceAtLeast(1f)
  val boxH = ((win.b - win.t) * h).coerceAtLeast(1f)
  val scale = maxOf(visT / boxW, visT / boxH)
  val cxS = (win.l + win.r) / 2f * w; val cyS = (win.t + win.b) / 2f * h
  val cxD = inset + visT / 2f; val cyD = inset + visT / 2f
  val out = IntArray(px.size)
  for (y in 0 until h) for (x in 0 until w) {
    val sx = ((x + 0.5f - cxD) / scale + cxS).toInt()
    val sy = ((y + 0.5f - cyD) / scale + cyS).toInt()
    if (sx in 0 until w && sy in 0 until h) out[y * w + x] = px[sy * w + sx]
  }
  return out
}

private fun load(f: File, edge: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val sw = img.width; val sh = img.height
  val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
    val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
    px[y * edge + x] = s0[sy * sw + sx]
  }
  return px
}

private fun expandLayer(px: IntArray, edge: Int): IntArray {
  val out = IntArray(px.size)
  for (y in 0 until edge) for (x in 0 until edge) {
    val u = (x.toFloat() / edge + 0.25f) / 1.5f
    val v = (y.toFloat() / edge + 0.25f) / 1.5f
    if (u > 1f || v > 1f) continue
    out[y * edge + x] = px[(v * (edge - 1)).toInt().coerceIn(0, edge - 1) * edge +
      (u * (edge - 1)).toInt().coerceIn(0, edge - 1)]
  }
  return out
}

private fun render(mask: IntArray, edge: Int, plate: Int, ink: Int): BufferedImage {
  val side = edge - 2 * (edge * 0.25f / 1.5f).toInt()
  val ins = (edge * 0.25f / 1.5f).toInt()
  val bi = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until side) for (x in 0 until side) {
    val a = (mask[(y + ins) * edge + x + ins] ushr 24) / 255.0
    val r = (((plate shr 16) and 0xFF) * (1 - a) + ((ink shr 16) and 0xFF) * a).toInt()
    val gg = (((plate shr 8) and 0xFF) * (1 - a) + ((ink shr 8) and 0xFF) * a).toInt()
    val b = ((plate and 0xFF) * (1 - a) + (ink and 0xFF) * a).toInt()
    bi.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (gg shl 8) or b)
  }
  return bi
}

private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
  val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
  val g = out.createGraphics(); g.drawImage(src, 0, 0, w, h, null); g.dispose()
  return out
}
