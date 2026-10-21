package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **这一圈灰是不是"它自己踩在中间"？** —— 全局族色的混合测试死了（`BlendKt`：只解释 4%），
 * 因为浙江在线那块底是**连续渐变**（#0018A5→#3079F8→#4B8AF9），拿"全图前几名族"当两端，
 * 渐变中间的像素谁都像不上，全被算成"第三种颜色"。
 *
 * 换成**局部**：一格里外圈的灰，它左边就是墨、右边就是底 —— 那就问它是不是
 * **它自己这两格的混合**。这一问不需要全图口径，渐变再长也照样成立。
 *
 * 三种答案，修法各不同：
 *  · `混合`   = 它就在这两格连线上 → 抗锯齿像素，本来不该自己成墨 → **可以清**；
 *  · `偏墨`   = 比连线更靠近墨那端 → 源里字自己带的高光/描边 → 是内容；
 *  · `偏底外` = 比连线更靠近底那端、却仍然交了墨 → **斜坡给多了**，这是"底被画成灰"，也可以清；
 *  · `第三种` = 两个方向都不沾 → 源里真画了个新颜色，动不得。
 *
 * 用法：`harness.RingKtKt <目录…> [--edge=384] [--top=N] [--why=包名]`
 */
private fun load(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f) ?: return IntArray(0)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toIntOrNull() ?: 384
  val top = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toIntOrNull() ?: 12
  val why = args.firstOrNull { it.startsWith("--why=") }?.substringAfter("=")
  // **阴性对照**：`--ctrl` 把"贴着墨"这个前提换成"三格内没有墨" —— 这些格子**按定义不可能**
  //  是"它自己踩在墨与底中间"。同一个判据要是照样给它们打 99% "混合"，那这把尺就是空的，
  //  不是这批格子真的都是抗锯齿。（33722 格里 99% 判成混合，已经像这种情况。）
  val ctrl = args.contains("--ctrl")
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u") }
  val tol = 30
  val rows = ArrayList<Pair<String, IntArray>>()
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    for (f in dir.listFiles { x -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
      val px = load(f, edge); if (px.isEmpty()) continue
      val out = MonochromeMask.build(px, edge, edge).mask ?: continue
      val al = IntArray(edge * edge) { (out[it] ushr 24) and 0xff }
      var mix = 0; var toInk = 0; var toPlate = 0; var third = 0; var ring = 0
      val ex = ArrayList<String>()
      for (y in 1 until edge - 1) for (x in 1 until edge - 1) {
        val i = y * edge + x
        val a = al[i]
        if (a < 40 || a > 120) continue                                   // 只看"看得见的灰"
        if (((px[i] ushr 24) and 0xff) < 255) continue
        // 找一个"墨"邻居（交付 ≥180）与一个"底"邻居（交付 ≤15），八邻域内取最近的
        var bi = -1; var pi = -1
        val r0 = if (ctrl) 3 else 1; val r1 = if (ctrl) 8 else 2
        for (r in r0..r1) for (dy in -r..r) for (dx in -r..r) {
          if (maxOf(abs(dx), abs(dy)) != r) continue
          if (y + dy < 0 || x + dx < 0 || y + dy >= edge || x + dx >= edge) continue
          val j = (y + dy) * edge + (x + dx)
          if (bi < 0 && al[j] >= 180) bi = j
          if (pi < 0 && al[j] <= 15 && ((px[j] ushr 24) and 0xff) == 255) pi = j
        }
        if (ctrl) {
          // 对照样本 = **两格内没有墨**的那些：它们不可能"踩在墨与底中间"
          var inkNear = false
          for (dy in -2..2) for (dx in -2..2) {
            if (maxOf(abs(dx), abs(dy)) > 2) continue
            val j = (y + dy) * edge + (x + dx)
            if (j in 0 until al.size && al[j] >= 180) inkNear = true
          }
          if (inkNear) continue
        }
        if (bi < 0 || pi < 0) continue
        ring++
        val c = intArrayOf((px[i] ushr 16) and 0xff, (px[i] ushr 8) and 0xff, px[i] and 0xff)
        val ic = intArrayOf((px[bi] ushr 16) and 0xff, (px[bi] ushr 8) and 0xff, px[bi] and 0xff)
        val pc = intArrayOf((px[pi] ushr 16) and 0xff, (px[pi] ushr 8) and 0xff, px[pi] and 0xff)
        // 到"墨—底"这条线段的最大通道残差，以及落在哪一侧
        var best = 1 shl 30; var bs = 0
        for (s in 0..20) {
          val e = maxOf(abs(c[0] - (pc[0] + (ic[0] - pc[0]) * s / 20)),
            abs(c[1] - (pc[1] + (ic[1] - pc[1]) * s / 20)),
            abs(c[2] - (pc[2] + (ic[2] - pc[2]) * s / 20)))
          if (e < best) { best = e; bs = s }
        }
        val dcInk = minOf(abs(c[0] - ic[0]), 999) + minOf(abs(c[1] - ic[1]), 999) + minOf(abs(c[2] - ic[2]), 999)
        val dcPl = minOf(abs(c[0] - pc[0]), 999) + minOf(abs(c[1] - pc[1]), 999) + minOf(abs(c[2] - pc[2]), 999)
        when {
          best <= tol -> mix++
          dcInk < dcPl -> toInk++
          dcPl - dcInk < 90 -> toPlate++                       // 贴着底、却还是被给了墨
          else -> {
            third++
            if (ex.size < 4) ex.add("#%02X%02X%02X α=%d".format(c[0], c[1], c[2], a))
          }
        }
      }
      val nm = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
      if (why != null && nm.contains(why)) println("  ${nm}：看得见的圈 $ring 格 = 混合 $mix + 偏墨 $toInk + " +
        "偏底外 $toPlate + 第三种 $third\n    第三种样本 ${ex.joinToString("  ")}")
      rows.add(nm to intArrayOf(ring, mix, toInk, toPlate, third))
    }
  }
  rows.sortByDescending { it.second[0] }
  println((if (ctrl) "【阴性对照：两格内没有墨的灰】 " else "看得见的字圈（α 40..120，左右就是墨和底）  ") + "${rows.size} 张")
  for (r in rows.take(top)) if (r.second[0] > 40) println("  %-38s 圈%6d  混合%5d  偏墨%5d  偏底%5d  第三种%5d".format(
    r.first.take(38), r.second[0], r.second[1], r.second[2], r.second[3], r.second[4]))
  val s = IntArray(5); for (r in rows) for (k in 0..4) s[k] += r.second[k]
  println("  合计 圈 ${s[0]}：混合 ${s[1]} (${pct(s[1], s[0])}%) 偏墨 ${s[2]} (${pct(s[2], s[0])}%) " +
    "偏底外 ${s[3]} (${pct(s[3], s[0])}%) 第三种 ${s[4]} (${pct(s[4], s[0])}%)")
}

private fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b
private fun abs(x: Int) = if (x < 0) -x else x
