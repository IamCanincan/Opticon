package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **前景层自己占掉多少画布？** —— 判 `plateIsLayer=true` 这个前提假不假的分布台。
 *
 * 为什么要摊分布再画线（这条规矩今天应验两次）：主人点名 `oss.krtirtho.spotube`
 * 「背景是黑色，被硬生生反色了」。查出来是**它把一块不透明黑底画进了自己的前景层**
 * （背景层是 `ColorDrawable`，于是装机告诉模型"这层里不许有底"→ H3 → 整块黑被交成墨，
 * `底占=0.00 框内=0.91`）。这和贴吧那一类同源：**前景层自带盘**。
 *
 * 但"前景层是一个色族"不等于"自带盘" —— discord / grok / 小黑盒 的前景就是一个**居中的单色 logo**，
 * 占实心格 90%+，可它贴不到边、也不盖住画布。所以这一台子把两个数分开报：
 *   · `画布%` = 最大色族占**整张画布**的比例（盘的候选应该盖掉一大片）；
 *   · `实心%` = 它占实心格的比例（单色 logo 也会很高）。
 * 线该画在哪一个上、画在哪一值，看完这张表再说。
 *
 * 用法：`FgPlateKt <语料目录> [--all]`（默认只看 bg=ColorDrawable 那批，即装机走"只取前景"的那条路）
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "/d/tmp/corpus_new")
  val ix = File(dir, "index.tsv")
  if (!ix.isFile) { println("没有 index.tsv，分不出哪张走的是「只取前景」那条路"); return }
  val rows = ArrayList<Triple<String, Int, Int>>()
  var cd = 0; var other = 0
  for (l in ix.readText().trim().split('\n').drop(1)) {
    val c = l.split('\t')
    if (c.size < 8 || !c[5].contains("Adaptive")) continue
    val isCd = c[7] == "ColorDrawable"
    if (isCd) cd++ else other++
    if (!isCd && !args.contains("--all")) continue
    val fg = File(dir, c[2].removeSuffix(".png") + "_fg.png")
    if (!fg.isFile) continue
    val img = runCatching { ImageIO.read(fg) }.getOrNull() ?: continue
    val (canvas, solid, fill) = shares(img)
    rows.add(Triple(c[0], canvas, fill))
  }
  println("自适应里 bg=ColorDrawable（装机只取前景层的那条路）$cd 张；bg 是真 drawable 的 $other 张")
  println("  包名 · 前景主色占画布% · 该色块填满自己外接框%（方块≈100、圆≈79）")
  for ((p, cv, fl) in rows.sortedByDescending { it.second })
    println("     %-42s 画布 %3d%%  填框 %3d%%%s".format(p.take(42), cv, fl,
      if (cv >= 35) "   ←盘候选" else ""))
  println("自适应里 bg=ColorDrawable（装机只取前景层的那条路）$cd 张；bg 是真 drawable 的 $other 张")
  println("  包名 ····································································································································································································································································································································································································································· 前景主色占画布%  占实心%")
  for ((p, cv, so) in rows.sortedByDescending { it.second })
    println("     %-42s %3d%%   %3d%%%s".format(p.take(42), cv, so, if (cv >= 35) "   ←盘" else ""))
  val v = rows.map { it.second }.sorted()
  println("  占画布%：中位 ${v[v.size / 2]}  p90 ${v[minOf(v.size - 1, (v.size * 0.9).toInt())]}  " +
    "最大 ${v.last()}  ≥35% 的 ${rows.count { it.second >= 35 }} 张")
}

/**
 * 返回（最大色族占整张画布%、占实心格%、**它填满自己外接框的程度%**）。
 * 最后一项是今天为 Spotube 加的：黑方块 ≈100，Spotify 那个圆 logo ≈785/1000 ——
 * 面积占比分不开这两张（44% vs 38%），形状分得开。半透明格不算内容。
 */
private fun shares(img: BufferedImage): Triple<Int, Int, Int> {
  val w = img.width; val h = img.height
  val fam = HashMap<Int, Int>(); var solid = 0
  for (y in 0 until h) for (x in 0 until w) {
    val p = img.getRGB(x, y)
    if (((p ushr 24) and 0xff) < 128) continue
    solid++
    val k = ((((p shr 16) and 0xff) / 24) shl 10) or ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)
    fam[k] = (fam[k] ?: 0) + 1
  }
  if (solid == 0) return Triple(0, 0, 0)
  val top = fam.maxByEntries()
  var minX = w; var maxX = -1; var minY = h; var maxY = -1; var n = 0
  for (y in 0 until h) for (x in 0 until w) {
    val p = img.getRGB(x, y)
    if (((p ushr 24) and 0xff) < 128) continue
    if (key(p) != top.key) continue
    n++
    if (x < minX) minX = x; if (x > maxX) maxX = x
    if (y < minY) minY = y; if (y > maxY) maxY = y
  }
  val box = (maxX - minX + 1).toLong() * (maxY - minY + 1)
  return Triple(top.value * 100 / (w * h), top.value * 100 / solid,
    if (box <= 0) 0 else (n.toLong() * 100 / box).toInt())
}

private fun key(p: Int) = ((((p shr 16) and 0xff) / 24) shl 10) or
  ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)

private fun HashMap<Int, Int>.maxByEntries() = maxByOrNull { it.value }!!
