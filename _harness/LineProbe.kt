package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **沿一条扫描线把三件事并排打出来**：模型吃的合成图（α 与色）、背景层单独那张（`_modelbg`）、
 * 以及"这一格在不在盘那一族里"。为的是回答一个具体问题：
 * translate 那道斜黑带 —— **它是前景层的东西，还是背景层自己画的投影？**
 *
 * 用法：`harness.LineProbeKt <合成图.png> [背景层.png] [--row=192] [--step=8]`
 */
fun main(args: Array<String>) {
  val a = File(args[0]); val b = args.getOrNull(1)?.let { File(it) }
  val row = (args.firstOrNull { it.startsWith("--row=") }?.substringAfter("="))?.toIntOrNull() ?: 192
  val step = (args.firstOrNull { it.startsWith("--step=") }?.substringAfter("="))?.toIntOrNull() ?: 8
  val px = grab(a); val un = if (b != null && b.isFile) grab(b) else null
  val w = px!![0].size
  val y = row
  println("行 y=$y   x: 合α 合色      盘α 盘色      盘族?")
  // 盘那一族 = 背景层不透明格里最大的 /24 桶
  var fam = HashMap<Int, Int>()
  if (un != null) for (p in un[0]) { if (((p ushr 24) and 0xff) >= 128) {
    val k = (((p shr 16) and 0xff) / 24 shl 10) or (((p shr 8) and 0xff) / 24 shl 5) or ((p and 0xff) / 24)
    fam[k] = (fam[k] ?: 0) + 1 } }
  val top = fam.maxByOrNull { it.value }?.key ?: -1
  for (x in 0 until w step step) {
    val p = px[y][x]; val pa = (p ushr 24) and 0xff
    val u = un?.get(y)?.get(x) ?: 0; val ua = (u ushr 24) and 0xff
    val uk = (((u shr 16) and 0xff) / 24 shl 10) or (((u shr 8) and 0xff) / 24 shl 5) or ((u and 0xff) / 24)
    println("  %3d: %3d #%06X   %3d #%06X   %s".format(x, pa, p and 0xFFFFFF,
      ua, u and 0xFFFFFF, if (ua >= 128 && uk == top) "盘" else if (ua >= 128) "盘上别的东西" else "盘外"))
  }
}

private fun grab(f: File): Array<IntArray>? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  return Array(img.height) { y -> IntArray(img.width) { x -> img.getRGB(x, y) } }
}
