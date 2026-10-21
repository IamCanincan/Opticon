package harness

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **对表台的几何底案**：`_mono.png` / `_fg.png` / `_model.png` / `file`(合成图) 这四张，
 * 到底哪两张在同一个坐标系里 —— 用它们自己的**墨迹 IoU** 直接量，不推理。
 *
 * 为什么必须量：把对表台换成吃 `_model.png` 之后，`iouMed` 从 **0.909 掉到 0.259**；
 * 我按"设计师那层是可见区几何"把它放大 1.5 倍对齐，反而掉到 **0.114**。
 * 三个数互相矛盾，说明**我对抓取通道那几种文件的几何关系是猜的**。
 * 这台子把四种配对的 IoU 一起打出来，谁跟谁同框架一目了然 —— **在它给出答案之前，
 * 对表闸的 0.909 不许当基线引用**（它可能是两个错位口径的巧合）。
 *
 * 用法：`harness.GeoKtKt <抓取目录> [--edge=384]`
 */
fun main(args: Array<String>) {
  val dir = File(args.first { !it.startsWith("--") })
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val lines = File(dir, "index.tsv").readText().trim().split('\n').drop(1)
  val pairs = ArrayList<HashMap<String, Double>>()
  var n = 0
  for (l in lines) {
    val c = l.split('\t')
    if (c.size < 19 || c[8] != "有") continue                 // 只有已适配的才有设计师那层
    val mono = px(File(dir, c[12]), edge) ?: continue
    val fg = File(dir, c[15]).let { if (it.isFile) px(it, edge) else null }
    val comp = px(File(dir, c[2]), edge)
    val model = px(File(dir, if (c[17] == "same") c[2] else c[17]), edge)
    val monoUp = fullBleed(File(dir, c[12]), edge)
    val m = HashMap<String, Double>()
    m["mono ↔ fg（都是可见区？）"] = fg?.let { iu(it, mono) } ?: -1.0
    m["mono ↔ 合成图"] = comp?.let { iu(it, mono) } ?: -1.0
    m["mono ↔ model（装机那一张）"] = model?.let { iu(it, mono) } ?: -1.0
    m["mono放大1.5 ↔ model"] = model?.let { iu(it, monoUp ?: mono) } ?: -1.0
    m["fg ↔ 合成图"] = fg?.let { f2 -> comp?.let { iu(f2, it) } } ?: -1.0
    pairs.add(m); n++
  }
  println("已适配 $n 对（每格 = 两张墨迹的 IoU 中位；-1 = 那张文件不在）")
  for (k in pairs[0].keys) {
    val v = pairs.map { it[k]!! }.filter { it >= 0 }.sorted()
    println("  %-22s 中位 %s  范围 %s..%s".format(k,
      if (v.isEmpty()) "无" else "%.3f".format(v[v.size / 2]),
      if (v.isEmpty()) "-" else "%.3f".format(v.first()), "%.3f".format(v.last())))
  }
}

private fun px(f: File, edge: Int): IntArray? {
  if (!f.isFile) return null
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics(); g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  return IntArray(edge * edge).also { img.getRGB(0, 0, edge, edge, it, 0, edge) }
}

/** 把可见区那一张放大 1.5 倍、居中 —— 换算到满幅坐标系。 */
private fun fullBleed(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val q = edge / 4
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, -q, -q, edge + 2 * q, edge + 2 * q, null); g.dispose()
  return IntArray(edge * edge).also { img.getRGB(0, 0, edge, edge, it, 0, edge) }
}

private fun iu(a: IntArray, b: IntArray): Double {
  var both = 0; var either = 0
  for (i in a.indices) {
    val x = ((a[i] ushr 24) and 0xff) >= 128; val y = ((b[i] ushr 24) and 0xff) >= 128
    if (x || y) either++; if (x && y) both++
  }
  return if (either == 0) -1.0 else both * 1.0 / either
}
