package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 「墨自己挤在顶上」到底有几张 —— 只测不改。
 *
 * 起因：把 61 张**真会上屏**的图标（`corpus_dev/index.tsv` 里 mono=无/-，已适配的那半模型根本不碰）
 * 拼成 Contact sheet 逐张看，抓到两类坏法，其中一类是"归属对了、层次没了"：
 * 坚果云那张橡子（白盘 + 浅黄果身 + 棕帽 + 深描边）交出去是**一整块实心**，
 * `分档=1/6(223,250,252,255,255,255)` —— 六块颜色全挤在 223..255 这 32 个单位里。
 *
 * 而 2026-09-30 那一晚我量过这个形状，结论是「≥3 块且全部贴 250+ 只有 3 张，
 * 而那些 logo 本来就单色实心」→ **就此收手**。那句话里"本来就单色实心"是**推的，没看图**。
 * 橡子那张就是反例：源里果身 L\*≈78、帽≈55、描边≈35，**层次是真实存在的**，
 * 是 dep→浓度那一条把它压到顶上的。所以这一台先把"到底几张"重新数清楚，
 * 而且必须**同时**问源图那一边（只看交付会数错，这是本仓第 N 次同课）：
 *
 *   顶占%  交付的墨里 ≥250 的比例（只看交付 → 双色 logo 天生高，那不是病）
 *   源跨    **墨那一侧**的源亮度 p95−p5（L\* 真值）—— 有真实层次可发却没发的证据
 *   源档    墨那一侧按 L\* 每 10 一档，占到 1% 以上的档数
 *   跨度    交付浓度的 p95−p5（层次丢在交付这一边的直接读数）
 *
 * 病 = `顶占% ≥ 55` 且 `源档 ≥ 3` 且 `源跨 ≥ 25`：源里有三档以上、彼此差得开的调子，
 * 交出去却全贴在顶上。名单打出来，**逐张看图再判它是不是真的该修**。
 *
 * 用法：`harness.InkTopKtKt <目录> [--edge=384] [--all]`
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

private fun lstar(p: Int): Int {
  fun ch(v: Int): Double {
    val c = v / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }
  val y = 0.2126 * ch(p shr 16 and 0xff) + 0.7152 * ch(p shr 8 and 0xff) + 0.0722 * ch(p and 0xff)
  val f = if (y > 0.008856) y.pow(1.0 / 3.0) else 7.787 * y + 16.0 / 116.0
  return ((116.0 * f - 16.0).roundToInt()).coerceIn(0, 100)
}

private fun pctile(hist: IntArray, n: Int, p: Int): Int {
  if (n <= 0) return 0
  val want = (n * p / 100.0).toInt().coerceIn(0, n - 1)
  var run = 0
  for (b in hist.indices) { run += hist[b]; if (run > want) return b }
  return hist.lastIndex
}

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args.first { !it.startsWith("--") })
  val rows = ArrayList<Triple<String, IntArray, String>>()
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.contains("_mono") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    val out = MonochromeMask.build(px, edge, edge)
    val m = out.mask ?: continue
    // 只看"源本来就实心"的那些格子；再排掉被模型判成底的那些（底该是空的，不算层次）
    val plate = Regex("底占=([^ ]*)").find(out.report)?.groupValues?.get(1) ?: "-"
    val srcH = IntArray(101); val delH = IntArray(256)
    var inkN = 0; var top = 0
    for (i in px.indices) {
      if (((px[i] ushr 24) and 0xff) != 255) continue
      val a = (m[i] ushr 24) and 0xff
      if (a < 128) continue                       // 没上墨的不算"墨那一侧"
      inkN++
      if (a >= 250) top++
      srcH[lstar(px[i] and 0x00FFFFFF)]++
      delH[a]++
    }
    if (inkN * 100 < edge * edge) continue        // 墨太少（<1% 画布）的不在这一族
    val srcSpan = pctile(srcH, inkN, 95) - pctile(srcH, inkN, 5)
    val srcLevels = (0..10).count { b -> (srcH.indices.filter { it / 10 == b }.sumOf { srcH[it] }) * 100 >= inkN }
    val delSpan = pctile(delH, inkN, 95) - pctile(delH, inkN, 5)
    rows.add(Triple(
      f.nameWithoutExtension.take(44),
      intArrayOf(top * 100 / inkN, srcSpan, srcLevels, delSpan, inkN),
      "底占=$plate 分档=${Regex("分档=([^ ]*)").find(out.report)?.groupValues?.get(1) ?: "-"}"
    ))
  }
  val sick = rows.filter { it.second[0] >= 55 && it.second[2] >= 3 && it.second[1] >= 25 }
  println("=== ${dir.name} · ${rows.size} 张 · 病（顶占≥55 且 源≥3档 且 源跨≥25）= ${sick.size} 张 ===")
  println("图标\t顶占%\t源跨\t源档\t交付跨\t墨格\t判据")
  for (r in sick.sortedByDescending { it.second[0] })
    println("${r.first}\t${r.second.joinToString("\t")}\t${r.third}")
  if (args.contains("--all")) {
    println("--- 全部（按顶占%降序前 20）---")
    for (r in rows.sortedByDescending { it.second[0] }.take(20))
      println("${r.first}\t${r.second.joinToString("\t")}\t${r.third}")
  }
}
