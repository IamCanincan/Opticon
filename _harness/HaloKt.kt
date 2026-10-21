package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **字外圈上墨账**：主人报「为什么字符外圈会有原本不存在的灰色」。
 *
 * 这一族**不是** `AirInkKt` 那个"空气上墨"：那个量的是源里**全透明**（α<8）的格被给了墨，
 * 形状是图标**外面**一圈方框。而字外面这圈灰，源里那格是**实心的底色**（不透明、白的），
 * 所以空气账一张都不抓 —— 两把尺的分母不一样，得各量各的。
 *
 * 判据（三个条件同时成立才算"字外圈的灰"）：
 *  · 交付 α 落在 8..120 —— 屏幕上是一层看得见的淡灰，不是墨也不是空；
 *  · 源那一格**实心**，且颜色离这张图自己的底色（实心格里最多的那个量化色）很近 ——
 *    也就是说**源里这里本来就是背景**，不是字的一部分；
 *  · 四邻里至少有一格交付 α ≥ 180 —— 它**贴着字**。这三条一起才排掉"整片盘偏淡"那一族。
 *
 * 用法：`harness.HaloKtKt <目录…> [--edge=384] [--top=N] [--dump=包名]`
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
  val top = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toIntOrNull() ?: 14
  val dump = args.firstOrNull { it.startsWith("--dump=") }?.substringAfter("=")
  // `--hist=包名`：把这圈灰**离最近的真墨有多远**摊开 —— 1~2 格 = 抗锯齿边，
  //  好几格 = 源里本来就画了一条带（那不该叫"外圈"，是内容）。两种病修法相反。
  val hist = args.firstOrNull { it.startsWith("--hist=") }?.substringAfter("=")
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u") }
  val rows = ArrayList<Triple<String, Int, Long>>()
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    for (f in dir.listFiles { x -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
      val px = load(f, edge); if (px.isEmpty()) continue
      // 这张图自己的底色 = 实心格里最多的那个量化色（4 bit/通道）
      val cnt = HashMap<Int, Int>(); var modal = 0; var modalN = 0
      for (p in px) {
        if (((p ushr 24) and 0xff) < 255) continue
        val q = ((p ushr 20) and 0xf) * 256 + ((p ushr 12) and 0xf) * 16 + ((p ushr 4) and 0xf)
        val c = (cnt[q] ?: 0) + 1; cnt[q] = c; if (c > modalN) { modalN = c; modal = q }
      }
      val mr = (modal shr 8) and 0xf; val mg = (modal shr 4) and 0xf; val mb = modal and 0xf
      val out = MonochromeMask.build(px, edge, edge).mask ?: continue
      // 离最近真墨（交付 α≥180）几格：多源 BFS，封顶 12 格（超过就当"很远"）
      val dd = IntArray(edge * edge) { 99 }
      val q = IntArray(edge * edge); var h0 = 0; var h1 = 0
      for (i in out.indices) if (((out[i] ushr 24) and 0xff) >= 180) { dd[i] = 0; q[h1++] = i }
      while (h0 < h1) {
        val i = q[h0++]; val cy = i / edge; val cx = i - cy * edge; val d0 = dd[i]
        if (d0 >= 12) continue
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val ny = cy + dy; val nx = cx + dx
          if (ny < 0 || nx < 0 || ny >= edge || nx >= edge) continue
          val j = ny * edge + nx
          if (dd[j] <= d0 + 1) continue
          dd[j] = d0 + 1; q[h1++] = j
        }
      }
      val histN = IntArray(14)
      var halo = 0; var sum = 0L; var fx = -1; var fy = -1
      for (y in 0 until edge) for (x in 0 until edge) {
        val i = y * edge + x
        val a = (out[i] ushr 24) and 0xff
        if (a < 8 || a > 120) continue
        if (((px[i] ushr 24) and 0xff) < 255) continue
        val dr = ((px[i] ushr 16) and 0xff) - mr * 17
        val dg = ((px[i] ushr 8) and 0xff) - mg * 17
        val db = (px[i] and 0xff) - mb * 17
        if (maxOf(abs(dr), abs(dg), abs(db)) > 26) continue          // 源里这里就是背景
        if (dd[i] > 12) continue                                     // 离真墨太远 = 整片盘偏淡那一族，不算"外圈"
        halo++; sum += a; histN[minOf(13, dd[i])]++
        if (fx < 0 && a >= 40) { fx = x; fy = y }
      }
      val nm = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
      if (hist != null && nm.contains(hist)) {
        println("  ${nm} 外圈灰 ${halo} 格 均α=${if (halo == 0) 0 else sum / halo}   离真墨几格：")
        for (k in 1..12) if (histN[k] > 0) println("     ${k}格远: ${histN[k]}")
        println("     13+(封顶): ${histN[13]}")
      }
      if (dump != null && nm.contains(dump)) {
        println("  逐格：$nm 底色档=${Integer.toHexString(modal)} 字外圈 $halo 格 均α=${if (halo == 0) 0 else sum / halo}")
        for (k in 0 until 6) {
          if (fx < 0) break
          val i = fy * edge + fx
          println("    格($fx,$fy) 源#%06X α源=${(px[i] ushr 24) and 0xff} 交付α=${(out[i] ushr 24) and 0xff}".format(
            px[i] and 0xffffff))
          fx += 1; if (fx >= edge) { fx = 0; fy++ }
        }
      }
      rows.add(Triple(nm, histN[1], if (halo == 0) 0 else sum / halo))
    }
  }
  rows.sortBy { -it.second }
  println("**紧贴字那一圈（离墨 1 格）**  共 ${rows.size} 张")
  for (r in rows.take(top)) if (r.second > 0)
    println("  %-44s %6d 格  均α=%d".format(r.first.take(44), r.second, r.third))
  println("  有这圈灰的：${rows.count { it.second > 0 }} 张，合计 ${rows.sumOf { it.second }} 格")
}

private fun abs(x: Int) = if (x < 0) -x else x
