package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **背景层那一层到底是不是"一块盘"** —— 只量 `<图>_bg.png`，不看合成图。
 *
 * 为什么要它：装机那条路现在只有一种情况会**丢掉背景层**（背景是 `ColorDrawable` = 一层纯色，
 * 34/123 张）。剩下的自适应图背景是一张真 drawable，于是被合成进模型输入。
 * translate 今天被抓出来「半边黑了」就是这一族：它的**两块平板**（蓝 + 浅灰）一张在前景层、
 * 一张在背景层，模型只认一块底，另一块就被极性"亮=墨"整片交成墨。
 * 这一台子回答的是开那道门**值不值**：背景层里有多少张其实就是"另一块盘"（一个色族铺满四边），
 * 有多少张是真画在上面、丢了就少一半内容的画。
 *
 * 用法：`BgPlateKt <语料目录>`（目录里要有 index.tsv 与 `*_bg.png`）
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "/d/tmp/corpus_new")
  val ix = File(dir, "index.tsv")
  if (!ix.isFile) { println("没有 index.tsv，分不出哪张是自适应"); return }
  var plate = 0; var art = 0; var none = 0; var color = 0
  val rows = ArrayList<Triple<String, Int, Int>>()
  for (l in ix.readText().trim().split('\n').drop(1)) {
    val c = l.split('\t')
    if (c.size < 8) continue
    if (!c[5].contains("Adaptive")) continue
    if (c[7] == "ColorDrawable") { color++; continue }
    val bg = File(dir, c[2].removeSuffix(".png") + "_bg.png")
    if (!bg.isFile) { none++; continue }
    val img = runCatching { ImageIO.read(bg) }.getOrNull() ?: continue
    val (top, edge) = flatness(img)
    if (top >= 85) {
      plate++
      if (plate <= 14) rows.add(Triple(c[0], top, edge))
    } else art++
  }
  println("自适应 123 张里：背景=ColorDrawable（现在就丢背景的那条路）$color 张")
  println("  背景是一张真 drawable 的：$none 张没有 _bg 文件")
  println("  ── 其中**背景层自己就是一块盘**（最大色族 ≥85% 画布）：$plate 张")
  println("  ── 背景层是真有内容的画：$art 张")
  println("  前 14 张「盘」样张（包名 · 最大色族% · 四边覆盖率%）：")
  for ((p, t, e) in rows) println("     %-42s %3d%%  %3d%%".format(p.take(42), t, e))
}

/** 把 bg 层按粗量化归成色族，返回（最大族占画布%、最大族贴到四边的%）。半透明格不算内容。 */
private fun flatness(img: BufferedImage): Pair<Int, Int> {
  val w = img.width; val h = img.height
  val fam = HashMap<Int, Int>(); var solid = 0
  for (y in 0 until h) for (x in 0 until w) {
    val p = img.getRGB(x, y)
    if (((p ushr 24) and 0xff) < 128) continue
    solid++
    val k = (((p shr 19) and 0xff) / 24 shl 10) or (((p shr 11) and 0xff) / 24 shl 5) or ((p and 0xff) / 24)
    fam.merge(k, 1, Int::plus)
  }
  if (solid == 0) return Pair(0, 0)
  val top = fam.maxByOrNull { it.value }!!
  var onEdge = 0; var edgeN = 0
  for (x in 0 until w) for (y in intArrayOf(0, h - 1)) {
    val p = img.getRGB(x, y)
    if (((p ushr 24) and 0xff) < 128) continue
    edgeN++
    val k = (((p shr 19) and 0xff) / 24 shl 10) or (((p shr 11) and 0xff) / 24 shl 5) or ((p and 0xff) / 24)
    if (k == top.key) onEdge++
  }
  return Pair(top.value * 100 / solid, if (edgeN == 0) 0 else onEdge * 100 / edgeN)
}
