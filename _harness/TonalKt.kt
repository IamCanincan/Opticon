package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 「什么时候可以不要参照」的筛查台 —— 只测不改。
 *
 * 点名的四张（browndust2 / sgameGlobal / sgame / wisdomgame）是同一类内容：**满幅插画**。
 * `LumaKt` 已经逐张看过图：绝对亮度当浓度（暗=墨）在**四张上全部赢过模型现在的输出**，
 * 而模型现在给这四张的是四支不同分支（H1 0.86 / H2 / H3×2）与四种不同的错法。
 *
 * 但那条斜坡**不能对普通图标开**：红盘白字的 YouTube 用"亮度当浓度"会把盘子画满、字掏空
 * （正是他最早报的「颜色反一下」）。所以这里先把**开火条件**当成一个可数的东西量出来：
 *
 *   纹理%  相邻两格都实心、亮度**差得开但差得少**（0<|ΔL|≤12）的对数占比。
 *          平涂 logo（一块盘 + 一个字）内部几乎恒等，只有边界上大跳 → 天生低；
 *          插画/照片有大量的渐变与阴影 → 天生高。**这是这一族第一次问"画面有多少纹理"，
 *          而不是"那块参照长什么样"**（前五把尺子：占比 / 厚度 / 围合 / 环散 / 轮廓归一）。
 *          ⚠ **必须先在固定 96×96 的格上算**：逐像素版的 纹理% 随边长漂 —— 王者荣耀在
 *          96/192/384/768 上分别是 55/67/73/36（768 是放大出来的，四邻几乎相等 → ΔL=0 被排除）。
 *          降采样到固定格 = 与 [MonochromeMask.polarity] 同一个套路（它也只按 96 降采样）。
 *   跨L    这张自己的绝对亮度 p95−p5（有没有连续的调子可发）
 *   空气%  内容框里透明格的占比（透明底剪影一律不许开）
 *   主色%  最大那一整块精确源色的占比（平涂盘会很高）
 *   中调%  **模型现在交的**那张里，浓度落在 16..239 的占比 —— 它低就说明参照没给出形状
 *
 * 用法：`harness.TonalKtKt <目录> [--edge=384] [--tsv=FILE] [--top=N]`
 */

private fun load(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null)
  g.dispose()
  val px = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, px, 0, edge)
  return px
}

private fun luma(p: Int): Int {
  fun ch(v: Int): Double {
    val c = v / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }
  val y = 0.2126 * ch(p shr 16 and 0xff) + 0.7152 * ch(p shr 8 and 0xff) + 0.0722 * ch(p and 0xff)
  val f = if (y > 0.008856) y.pow(1.0 / 3.0) else 7.787 * y + 16.0 / 116.0
  return ((116.0 * f - 16.0) * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
}

private fun pctile(hist: IntArray, n: Int, p: Int): Int {
  if (n <= 0) return 0
  val want = (n * p / 100.0).toInt().coerceIn(0, n - 1)
  var run = 0
  for (b in hist.indices) { run += hist[b]; if (run > want) return b }
  return 255
}

private fun fld(rep: String, key: String): String =
  Regex("(?:^| )$key=([^ ]*)").find(rep)?.groupValues?.get(1) ?: "-"

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val tsv = args.firstOrNull { it.startsWith("--tsv=") }?.substringAfter("=")?.let { File(it) }
  val top = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 30
  val grid = args.firstOrNull { it.startsWith("--grid=") }?.substringAfter("=")?.toInt() ?: 96
  val dir = File(args.first { !it.startsWith("--") })
  val rows = ArrayList<Array<String>>()
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    val alpha = IntArray(px.size) { (px[it] ushr 24) and 0xff }
    // 内容框（与模型同口径：实心格的包围盒）
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (alpha[y * edge + x] >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) continue
    val lum = IntArray(px.size) { luma(px[it] and 0x00FFFFFF) }
    val hist = IntArray(256); var opq = 0
    val colours = HashMap<Int, Int>()
    var pairs = 0L; var cont = 0L
    for (y in t..b) for (x in l..r) {
      val i = y * edge + x
      if (alpha[i] < 128) continue
      opq++; hist[lum[i]]++
      if (alpha[i] == 255) colours.merge(px[i] and 0x00FFFFFF, 1, Int::plus)
    }
    // 纹理在**固定 96×96 格**上数：每格取自己的实心格均值，格与格相邻才比。
    val gn = minOf(grid, edge)
    val cell = (edge / gn).coerceAtLeast(1)
    val gL = IntArray(gn * gn) { -1 }
    for (gy in 0 until gn) for (gx in 0 until gn) {
      var sum = 0; var n = 0
      for (y in gy * cell until (gy + 1) * cell) for (x in gx * cell until (gx + 1) * cell)
        if (alpha[y * edge + x] >= 128) { sum += lum[y * edge + x]; n++ }
      if (n * 4 >= cell * cell) gL[gy * gn + gx] = sum / n
    }
    for (gy in 0 until gn) for (gx in 0 until gn) {
      val i = gy * gn + gx
      val v = gL[i]; if (v < 0) continue
      if (gx + 1 < gn && gL[i + 1] >= 0) { pairs++; val d = abs(gL[i + 1] - v); if (d in 1..12) cont++ }
      if (gy + 1 < gn && gL[i + gn] >= 0) { pairs++; val d = abs(gL[i + gn] - v); if (d in 1..12) cont++ }
    }
    val boxArea = (r - l + 1).toLong() * (b - t + 1)
    val air = (boxArea - opq) * 100 / boxArea
    val span = pctile(hist, opq, 95) - pctile(hist, opq, 5)
    val modal = if (colours.isEmpty()) 0 else (colours.values.maxOrNull()!! * 100 / opq)
    val t0 = System.nanoTime()
    val out = MonochromeMask.build(px, edge, edge)
    val ms = (System.nanoTime() - t0) / 1_000_000
    val m = out.mask
    var mid = 0; var full = 0; var empty = 0
    if (m != null) for (i in px.indices) {
      if (alpha[i] < 128) continue
      val a = (m[i] ushr 24) and 0xff
      when {
        a <= 15 -> empty++
        a >= 240 -> full++
        else -> mid++
      }
    }
    rows.add(arrayOf(
      f.nameWithoutExtension.take(46),
      "${cont * 100 / pairs}", "$span", "$air", "$modal",
      "${mid * 100 / opq}", "${full * 100 / opq}", "${empty * 100 / opq}",
      fld(out.report, "H"), fld(out.report, "底占"), fld(out.report, "明暗"),
      fld(out.report, "框内"), fld(out.report, "分档"), fld(out.report, "淡"),
      fld(out.report, "cc"), fld(out.report, "字均净"), fld(out.report, "色驱"), "$ms"
    ))
  }
  val hdr = listOf("图标", "纹理%", "跨L", "空气%", "主色%", "中调%", "满%", "空%",
    "H", "底占", "明暗", "框内", "分档", "淡", "cc", "字均净", "色驱", "ms")
  tsv?.let { it.writeText(hdr.joinToString("\t") + "\n" + rows.joinToString("\n") { rr -> rr.joinToString("\t") } + "\n") }
  println("=== 按纹理%降序前 $top 张（共 ${rows.size}）===")
  println(hdr.joinToString("\t"))
  for (rr in rows.sortedByDescending { it[1].toInt() }.take(top)) println(rr.joinToString("\t"))
  println("=== 按主色%升序前 $top 张（没有任何一块平涂占大头 = 误开火的候选）===")
  for (rr in rows.sortedBy { it[4].toInt() }.take(top)) println(rr.joinToString("\t"))
  val sum = { k: Int -> rows.map { it[k].toDoubleOrNull() ?: 0.0 }.sorted() }
  for ((i, name) in hdr.withIndex()) if (i in 1..7) {
    val v = sum(i)
    println("%-6s 中位 %5.1f  p10 %5.1f  p90 %5.1f".format(name, v[v.size / 2], v[v.size / 10], v[v.size * 9 / 10]))
  }
}
