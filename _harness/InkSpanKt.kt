package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * **墨那一半有没有调子**（只量，不改模型）—— 2026-10-09。
 *
 * 逼它出来的：PH（橙底渐变 + 黑三角）走的是满幅那一支，极性翻成"亮=墨"，
 * 于是整块橙底被涂满、三角空着（`框内=0.87`）。前十把尺子问的都是
 * **"这块场是不是底"** —— 全部失败。这一把问的是另一件事：
 * **满幅那一支的前提（"这张有连续调子要转录"）到底落在哪一半身上。**
 * 一张"平涂标记坐在渐变盘上"的图，连续调子全在**盘**里，墨那一半是**一个颜色**；
 * 而真插画（feimo / sgame / browndust2）的调子在**墨**里。
 *
 * 量的还是模型自己那份 `Scratch`（反射读 `plate` / `labL`，与出图同一份），
 * 两半各取实心格的 L\* p5/p95。跑法：`harness.InkSpanKtKt <语料> [--edge=384]`
 */
private fun loadArgb(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun mean(v: IntArray): Int = if (v.isEmpty()) -1 else (v.sum().toDouble() / v.size / 64.0).toInt()

private fun pct(v: IntArray, p: Int): Int {  if (v.isEmpty()) return -1
  val s = v.clone(); s.sort()
  return s[minOf(v.size - 1, v.size * p / 100)].let { it / 64 }
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_runtime")
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val rows = ArrayList<Triple<String, IntArray, IntArray>>()
  for (f in dir.listFiles { x: File -> x.extension == "png" && !x.name.contains("preview") &&
    !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
    val src = runCatching { loadArgb(f.absolutePath, edge) }.getOrNull() ?: continue
    val r = MonochromeMask.build(src, edge, edge)
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val labL = fld(s, "labL") as ShortArray
    val g = ArrayList<Int>(); val ink = ArrayList<Int>()
    for (i in src.indices) {
      if (((src[i] ushr 24) and 0xff) < 250) continue
      val l = labL[i].toInt() and 0xffff
      if (plate[i] != 0.toByte()) g.add(l) else ink.add(l)
    }
    rows.add(Triple(f.nameWithoutExtension, g.toIntArray(), ink.toIntArray()))
  }
  println("%-40s %5s %5s %5s %5s %6s %6s %6s".format("图标", "底跨", "墨跨", "底占", "墨占", "墨格", "底L", "墨L"))
  for ((name, g, ink) in rows.sortedBy { r ->
      if (r.third.isEmpty()) 9999 else pct(r.third, 95) - pct(r.third, 5) }) {
    val gs = pct(g, 95) - pct(g, 5); val isp = pct(ink, 95) - pct(ink, 5)
    val tot = g.size + ink.size
    println("%-40s %5d %5d %4d%% %5d%% %6d %6d %6d".format(name.take(40), gs, isp,
      if (tot == 0) 0 else g.size * 100 / tot, if (tot == 0) 0 else ink.size * 100 / tot, ink.size,
      mean(g), mean(ink)))
  }
  // 极性那条规则现在用的是**绝对阈值** `底 L < 80 就翻成亮=墨`。要挪它就得先看分布，
  // 不能对着 PH 一张挑数：哪一档有人、哪一档是空的，一眼就知道 80 是不是踩在人堆里。
  val g1 = rows.filter { it.second.isNotEmpty() &&
    it.second.size * 5 >= it.second.size + it.third.size }
  println("\n底 L\\* 分布（只数「底占 ≥20%」的 ${g1.size} 张，也就是那条规则真的会看的）：")
  for (lo in 0..100 step 10) {
    val band = g1.filter { mean(it.second) in lo until (lo + 10) }
    println("  L %3d~%3d  %2d 张  ${band.joinToString(" ") { it.first.substringBeforeLast('_') }.take(150) }")
  }
  val flat = rows.filter { it.third.isNotEmpty() && pct(it.third, 95) - pct(it.third, 5) <= 3 }
  println("\n**墨那一半几乎没有调子（L\\* 跨 ≤3）**= ${flat.size} 张：满幅那一支在这一张上无事可转录")
  flat.forEach { println("   " + it.first) }
}
