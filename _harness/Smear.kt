package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **细长痕探测器**：找"够长、够细"的墨连通块。
 *
 * 为什么要专门写一个：用户报的 `com.google.android.apps.translate` 那条斜涂抹痕，
 * 在判据行里**一个字都看不出来** —— 427 个像素（占框 0.29%，`淡=` 四舍五入成 0.00），
 * `cc` 5→1 也只是个数。它在屏幕上扎眼的原因是**长**（横跨整张图），不是多。
 *
 * ⚠ **必须用八邻域洪泛。** 第一版用四邻域，翻译那条 1px 斜线**一个像素都不连通**
 *   （每个像素只与对角线上的下一个相邻），于是 113 张一张都没命中 —— 探测器"没报警"
 *   不代表"没问题"，也可能代表"尺子不对"。
 *
 * 判据（两个都要满足）：
 *  · **长**：外接框对角线 ≥ [long]；
 *  · **细**：面积 / 对角线 ≤ [thin]（1px 线 ≈ 1.0~1.4，2px 线 ≈ 2.0，实心块远大于此）。
 * 不过滤浓度 —— 只把 `最浓/均浓` 报出来，由人判它是"实心的痕"还是"淡的痕"。
 *
 * 用法: `SmearKt <图标目录> [--long=80] [--thin=2.2]`
 */
fun main(args: Array<String>) {
  fun arg(n: String, d: Int) = args.firstOrNull { it.startsWith("--$n=") }?.substringAfter("=")?.toInt() ?: d
  val long = arg("long", 80)
  val thinMax = args.firstOrNull { it.startsWith("--thin=") }?.substringAfter("=")?.toDouble() ?: 2.2
  val dir = File(args.first { !it.startsWith("--") })
  val all = ArrayList<String>()
  var n = 0
  for (f in dir.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name }) {
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val mask = MonochromeMask.build(px, w, h).mask ?: continue
    n++
    val seen = ByteArray(w * h)
    var bestArea = 0; var bestDesc = ""
    for (i in 0 until w * h) {
      if (((mask[i] ushr 24) and 0xff) == 0 || seen[i] != 0.toByte()) continue
      val q = IntArray(w * h); var head = 0; var tail = 0
      q[tail++] = i; seen[i] = 1
      var cnt = 0; var x0 = w; var x1 = -1; var y0 = h; var y1 = -1; var mx = 0; var sum = 0L
      while (head < tail) {
        val j = q[head++]
        val y = j / w; val x = j - y * w
        cnt++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        val a = (mask[j] ushr 24) and 0xff
        if (a > mx) mx = a
        sum += a
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val nx = x + dx; val ny = y + dy
          if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
          val k = ny * w + nx
          if (((mask[k] ushr 24) and 0xff) == 0 || seen[k] != 0.toByte()) continue
          seen[k] = 1; q[tail++] = k
        }
      }
      val diag = Math.hypot((x1 - x0 + 1).toDouble(), (y1 - y0 + 1).toDouble())
      if (diag < long) continue
      val thin = cnt / diag
      if (thin > thinMax) continue
      if (cnt > bestArea) {
        bestArea = cnt
        bestDesc = "面积=%-6d 框=%dx%d 细度=%.2f 最浓=%-3d 均浓=%-3d 起点=(%d,%d)".format(
          cnt, x1 - x0 + 1, y1 - y0 + 1, thin, mx, (sum / cnt).toInt(), x0, y0)
      }
    }
    if (bestDesc.isNotEmpty()) all.add("%-58s %s".format(f.nameWithoutExtension.take(58), bestDesc))
  }
  println("=== $dir  判据 长≥$long 细度≤$thinMax（八邻域）===")
  all.forEach { println("  细长块  $it") }
  println("  命中 ${all.size} / $n 张")
}
