package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * 【主形】第七条轴的量具：**被割掉的那块，自己长什么样**。
 *
 * 前六条轴都是"整张图"的读数（墨量、毛刺、等周比…），所以"少了一半 logo"与"清掉一块影子"
 * 在读数上没区别 —— 这是它们全死的同一个理由。这条换到**块**的尺度上问：
 * 影子是**软边**（边界上有一条几格宽的渐变带，像被高斯抹过），真部件是**硬边**（0→实心一两格跳过去）。
 *
 * 用法：`sh run.sh harness.SoftKtKt rec_model [--edge=384] [--only=pkg1,pkg2] [--min=200]`
 *   `--only` 只留指定包（前缀）；`--min` 只打印面积 ≥ 该值的块（默认 200）。
 *   每块一行：面积、α 均值/最大、**平台率**（最多那个 α 值占多少）、不同 α 值个数、
 *   **外沿 α**（贴真背景那一圈的平均 α，影子低、实心部件高）、**软度**（外沿 α ÷ 内部 α）。
 */
private fun loadSq(f: File, side: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
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

private data class Piece(val area: Int, val mean: Double, val max: Int, val plateau: Double,
                       val distinct: Int, val rim: Double, val soft: Double)

private fun blocks(al: IntArray, side: Int, minArea: Int): List<Piece> {
  val n = side * side
  val lab = IntArray(n) { -1 }
  val q = IntArray(n)
  val out = ArrayList<Piece>()
  for (s in 0 until n) {
    if (al[s] <= 0 || lab[s] >= 0) continue
    val id = out.size
    var hd = 0; var tl = 0
    lab[s] = id; q[tl++] = s
    var area = 0; var sum = 0L; var max = 0
    val hist = IntArray(256)
    val px = ArrayList<Int>()
    while (hd < tl) {
      val i = q[hd++]; area++
      val a = al[i]; sum += a; if (a > max) max = a; hist[a]++; px += i
      val y = i / side; val x = i - y * side
      if (x > 0 && al[i - 1] > 0 && lab[i - 1] < 0) { lab[i - 1] = id; q[tl++] = i - 1 }
      if (x < side - 1 && al[i + 1] > 0 && lab[i + 1] < 0) { lab[i + 1] = id; q[tl++] = i + 1 }
      if (y > 0 && al[i - side] > 0 && lab[i - side] < 0) { lab[i - side] = id; q[tl++] = i - side }
      if (y < side - 1 && al[i + side] > 0 && lab[i + side] < 0) { lab[i + side] = id; q[tl++] = i + side }
    }
    if (area < minArea) continue
    var mode = 0
    for (v in 1..255) if (hist[v] > hist[mode]) mode = v
    var distinct = 0
    for (v in 1..255) if (hist[v] > 0) distinct++
    // 外沿：贴 α==0 的那一圈（四邻域）
    var rimN = 0; var rimSum = 0L
    for (i in px) {
      val y = i / side; val x = i - y * side
      var edge = false
      if (x > 0 && al[i - 1] == 0) edge = true
      if (x < side - 1 && al[i + 1] == 0) edge = true
      if (y > 0 && al[i - side] == 0) edge = true
      if (y < side - 1 && al[i + side] == 0) edge = true
      if (edge) { rimN++; rimSum += al[i] }
    }
    val mean = sum.toDouble() / area
    val rim = if (rimN == 0) 0.0 else rimSum.toDouble() / rimN
    out += Piece(area, mean, max, hist[mode].toDouble() / area, distinct, rim, if (mean > 0) rim / mean else 0.0)
  }
  return out.sortedByDescending { it.area }
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val side = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val minArea = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 200
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")?.split(',')?.filter { it.isNotBlank() }
  val index = File(dir, "index.tsv")
  if (!index.isFile) { println("!! 缺 ${index.path}"); exitProcess(2) }
  val rows = index.readText().replace("\r\n", "\n").split('\n').drop(1).filter { it.isNotBlank() }
    .map { it.split('\t') }
  println("包名 / 块# 面积  α均 αmax 平台率 色阶数 外沿α 软度")
  var any = false
  for (c in rows) {
    if (c.size < 18) continue
    if (only != null && only.none { c[0].startsWith(it) }) continue
    val modelName = if (c[17] == "same" || c[17] == "-" || c[17] == "err" || c[17].isEmpty()) c[2] else c[17]
    val mf = File(dir, modelName); if (!mf.isFile) continue
    val mp = loadSq(mf, side) ?: continue
    val plateLayer = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    val bgSib = File(dir, mf.nameWithoutExtension + "_modelbg.png")
    val under = if (bgSib.isFile) loadSq(bgSib, side) else null
    val before = runCatching { MonochromeMask.build(mp, side, side, plateLayer, under, trimSubject = false).mask }.getOrNull() ?: continue
    val after = runCatching { MonochromeMask.build(mp, side, side, plateLayer, under, trimSubject = true).mask }.getOrNull() ?: continue
    val n = side * side
    val cut = IntArray(n) { i ->
      val a = (after[i] ushr 24) and 0xff
      if (a == 0) (before[i] ushr 24) and 0xff else 0
    }
    if (cut.none { it > 0 }) continue
    val bl = blocks(cut, side, minArea)
    if (bl.isEmpty()) continue
    any = true
    bl.forEachIndexed { k, b ->
      println("%-30s %2d %7d %5.0f %5d %6.1f%% %5d %6.1f %5.2f".format(
        if (k == 0) c[0].take(30) else "", k + 1, b.area, b.mean, b.max, b.plateau * 100, b.distinct, b.rim, b.soft))
    }
  }
  if (!any) { println("!! 一张都没开火 —— 台子没牙"); exitProcess(1) }
}
