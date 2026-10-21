package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 「入场这条轴到底随不随边长长」—— 同一张图在几档边长上，把**现在那一条**跨度、它的**去掉格子前提**
 * 的版本、和几条候选量并排量出来。
 *
 * 为什么单独要一台：第十闸只报"换了几张"，不报**为什么**换。而"给入场换一个不随边长长的量"
 * 这句话要落地，必须先分清两种可能 ——
 *   ① 跨度这个**量**随分辨率长（那就得换量）；
 *   ② 量是稳的，但 `tonalClass` 开头那句「内容框比 96 的格子还小就返回 null」把小边长的一批
 *     **整张判成了不可测**（那就得换格子，不是换量）。
 * 这两个的修法完全不同，所以这里同时出 `跨`(现) 与 `跨裸`(同一个量、不问格子) 两列。
 *
 * ⚠ 候选量在**台子里**算（仓里只调 `tonalClass` 那一个，它是现在上屏的那一条）。
 *   这里是**比较**候选；哪一条被选中，就要写回 `tonalClass` 再重量一遍。
 *
 * 用法：`harness.SizeKtKt <目录> [--edges=96,192,384,768]`
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

private fun pct(hist: IntArray, n: Int, k: Int): Int {
  if (n <= 0) return 0
  val want = (n * k + 50) / 100
  var acc = 0
  for (i in hist.indices) { acc += hist[i]; if (acc >= want) return i }
  return hist.size - 1
}

fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "96,192,384,768")
    .split(',').map { it.toInt() }
  val dir = File(args.first { !it.startsWith("--") })
  println("图标\t边长\t跨度\t跨裸\t格跨度\t格p10\t阶数\t阶级跨度\t开火\t框小\t框")
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    for (edge in edges) {
      val px = load(f.absolutePath, edge)
      var l = edge; var t = edge; var r = -1; var b = -1
      for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 8) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
      }
      val name = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").substringBeforeLast("_").take(40)
      if (r < 0) { println("$name\t$edge\t-1\t-1\t-1\t-1\t-1\t-1\t0\t1\t0,0"); continue }
      val box = android.graphics.Rect(l, t, r + 1, b + 1)
      // ① 跨度这个量本身：它只用到**逐像素**亮度直方图，跟 96 的格子没有半点关系。
      val ph = IntArray(101); var opq = 0
      for (i in box.top until box.bottom) for (j in box.left until box.right) {
        val v = px[i * edge + j]
        if (((v ushr 24) and 0xff) < 128) continue
        opq++
        ph[(MonochromeMask.labChannel(v, 0) shr 6).coerceIn(0, 100)]++
      }
      val rawSpan = if (opq == 0) -1 else (pct(ph, opq, 95) - pct(ph, opq, 5)).coerceAtLeast(0)
      // ② 仓里那一条（含格子前提）
      val c = MonochromeMask.tonalClass(px, edge, edge, box)
      val bw = box.width(); val bh = box.height()
      // ③ 格上的候选量：格子按 min(96, 框) —— 框小的那些也能量到，这正是"换格子不换量"的那一手
      val n = minOf(96, bw, bh)
      val cellL = IntArray(n * n) { -1 }
      if (n >= 2 && opq > 0) {
        val cl = LongArray(n * n); val cn = IntArray(n * n)
        for (y in box.top until box.bottom) {
          val cy = (y - box.top) * n / bh
          for (x in box.left until box.right) {
            val v = px[y * edge + x]
            if (((v ushr 24) and 0xff) < 128) continue
            val k = cy * n + (x - box.left) * n / bw
            cl[k] += MonochromeMask.labChannel(v, 0); cn[k]++
          }
        }
        for (k in 0 until n * n) if (cn[k] > 0) cellL[k] = (cl[k] / cn[k]).toInt() shr 6
      }
      var cells = 0
      val ch = IntArray(101)
      for (v in cellL) if (v >= 0) { cells++; ch[v.coerceIn(0, 100)]++ }
      val gSpan = if (cells == 0) -1 else (pct(ch, cells, 95) - pct(ch, cells, 5)).coerceAtLeast(0)
      val gP10 = if (cells == 0) -1 else (pct(ch, cells, 90) - pct(ch, cells, 10)).coerceAtLeast(0)
      val mh = IntArray(20)
      for (v in cellL) if (v >= 0) mh[(v / 5).coerceIn(0, 19)]++
      var mc = 0; var lo = -1; var hi = -1
      for (i in 0 until 20) if (mh[i] * 100 >= cells) { mc++; if (lo < 0) lo = i; hi = i }
      println("$name\t$edge\t${if (c == null) -1 else c.span}\t$rawSpan\t$gSpan\t$gP10\t" +
        "${if (mc == 0) 1 else mc}\t${if (lo < 0) 0 else (hi - lo) * 5}\t${if (c?.on == true) 1 else 0}\t" +
        "${if (c == null) 1 else 0}\t$bw,$bh")
    }
  }
}
