package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **同一个源色，在图上的不同位置拿到几种墨？**（只量不改）
 *
 * 为什么要这一台（2026-10-10，主人报「com.wuba.zhuanzhuan "不"字没了」）：
 * `LevelsKt` 问的是"**一块**同色被发了几个档"，而"某个字没了"是**两块同色**——
 * 同一个字的每一笔各自连通，它们在源里是**同一个色**，在交付里却一实一空。
 * 这一台按色分组、再按**位置**切开：一行的八个 x 段各报平均 α，谁在左边被抹掉一眼看得见。
 *
 * 用法：`harness.ColourInkKt <png> … [--edge=384] [--share=2]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val share = args.firstOrNull { it.startsWith("--share=") }?.toIntOrNull() ?: 2
  for (path in args.filter { !it.startsWith("--") }) {
    val src = ImageIO.read(File(path))
    val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics(); g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
    val px = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, px, 0, edge)
    val out = MonochromeMask.build(px, edge, edge).mask ?: IntArray(edge * edge)
    // `--probe=x,y;x,y…`：逐格把"源色 / L* / 交付 α"打出来 —— 桶太粗时会骗人，这两格本身不会。
    val probe = args.firstOrNull { it.startsWith("--probe=") }?.substringAfter("=")?.split(";")
    if (probe != null) for (p in probe) {
      val xy = p.split(','); val x = xy[0].toInt(); val y = xy[1].toInt(); val i = y * edge + x
      println("  格($x,$y) 源#%06X α源=${(px[i] ushr 24) and 0xff} L*=${MonochromeMask.labChannel(px[i], 0) shr 6} 交付α=${(out[i] ushr 24) and 0xff}".format(
        px[i] and 0x00FFFFFF))
    }
    val grp = HashMap<Int, LongArray>()   // 量化色 -> [n, α和, α[0..7]和, n[0..7], r和,g和,b和, minx,maxx,miny,maxy]
    var solid = 0
    for (i in px.indices) {
      val a = (px[i] ushr 24) and 0xff
      if (a < 128) continue
      solid++
      val v = px[i]
      val k = ((((v shr 16) and 0xff) ushr 3) shl 10) or ((((v shr 8) and 0xff) ushr 3) shl 5) or (((v and 0xff) ushr 3))
      val x = i % edge; val y = i / edge
      val oct = (x * 8 / edge).coerceIn(0, 7)
      val ink = (out[i] ushr 24) and 0xff
      val s = grp.getOrPut(k) { LongArray(26) }
      s[0]++; s[1] += ink; s[2 + oct] += ink; s[10 + oct]++
      s[18] += (v shr 16) and 0xff; s[19] += (v shr 8) and 0xff; s[20] += v and 0xff
      if (s[21] == 0L || x < s[21]) s[21] = x.toLong()
      if (x > s[22]) s[22] = x.toLong()
      if (s[23] == 0L || y < s[23]) s[23] = y.toLong()
      if (y > s[24]) s[24] = y.toLong()
    }
    println("=== ${File(path).name}  实心 $solid 格 ===")
    // 一行总账：**这张的高调程度**（L≥90 占实心多少、实心平均 L、最亮那一档占多少）——
    // 极性要不要翻，缺的就是这种"整张自己的分布"，不是又一根绝对色尺。
    var lsum = 0L; var hi90 = 0; var hi95 = 0
    for (i in px.indices) {
      if (((px[i] ushr 24) and 0xff) < 128) continue
      val l = MonochromeMask.labChannel(px[i], 0) shr 6
      lsum += l; if (l >= 90) hi90++; if (l >= 95) hi95++
    }
    println("  高调账 平均L=${lsum / maxOf(solid, 1)}  L≥90 占${hi90 * 100 / maxOf(solid, 1)}%  L≥95 占${hi95 * 100 / maxOf(solid, 1)}%")
    for ((k, s) in grp.filter { it.value[0] * 100 >= solid.toLong() * share }.toList().sortedByDescending { it.second[0] }.take(9)) {
      val n = maxOf(s[0], 1)
      val mean = (((s[18] / n).toInt()) shl 16) or (((s[19] / n).toInt()) shl 8) or ((s[20] / n).toInt())
      val per = (0 until 8).joinToString(" ") { o ->
        if (s[10 + o] == 0L) "  -  " else "%4d".format(s[2 + o] / s[10 + o])
      }
      println("  桶#%06X 均色#%06X 占%2d%% 均α=%3d  框x${s[21]}..${s[22]} y${s[23]}..${s[24]}\n      x八段α: $per".format(
        k, mean, (s[0] * 100 / solid.toLong()), s[1] / n))
    }
  }
}
