package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 直接调**仓里那一个** `MonochromeMask.tonalClass`，把它的三个判据数在语料上排开。
 *
 * 为什么单独要这一台：`TonalKt` 是我自己另算的一套量（逐像素 ΔL、全分辨率精确色），
 * 而阈值要钉在**上屏那一份实现**的数上 —— 两处的算法只要差一点（降采样平均 vs 逐像素、
 * L\\* 真值 vs 0..255 标度），同一个阈值就是两个不同的判据。
 * 这也是本仓那条老规矩：判据的数必须由将要跑它的那段代码量出来。
 *
 * 用法：`harness.TonalGateKtKt <目录> [--edge=384] [--top=N] [--all]`
 *   列：纹理 主色 跨L 走? —— 走? = 三条判据同时成立（这一张会走"不要参照"那一支）
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

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val top = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 24
  val dir = File(args.first { !it.startsWith("--") })
  val rows = ArrayList<Triple<String, String, IntArray>>()
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) { rows.add(Triple(f.nameWithoutExtension.take(46), "整张透明", IntArray(4))); continue }
    val box = android.graphics.Rect(l, t, r + 1, b + 1)
    val c = MonochromeMask.tonalClass(px, edge, edge, box)
    rows.add(Triple(
      f.nameWithoutExtension.take(46),
      if (c == null) "格比图小" else "${c.tex}/${c.flat}/${c.span}",
      intArrayOf(c?.tex ?: -1, c?.flat ?: -1, c?.span ?: -1, if (c?.on == true) 1 else 0)
    ))
  }
  println("=== edge=$edge 共 ${rows.size} 张 · 走这一支的 ${rows.count { it.third[3] == 1 }} 张 ===")
  println("图标\t纹理/主色/跨\t走")
  for (rr in rows.filter { it.third[3] == 1 }) println("${rr.first}\t${rr.second}\t${rr.third[3]}")
  if (!args.contains("--all")) {
    println("--- 未走、但纹理最高的 $top 张（把阈值往下挪一格就会进来的那批）---")
    for (rr in rows.filter { it.third[3] == 0 }.sortedByDescending { it.third[0] }.take(top))
      println("${rr.first}\t${rr.second}")
  }
}
