package harness

import java.io.File
import javax.imageio.ImageIO
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 交出去那一张的**浓度分布**：8 个等宽 bin（1..255）+ 顶在 255 的比例 + 中间带（64..191）的比例。
 *
 * 「层次多一点、颜色有点割裂」是两句话，量的是同一个分布的两种毛病：
 *  · 层次少 = 质量堆在少数几个 bin 里（中间带空着）；
 *  · 割裂 = **两头重、中间空**（一块平色直接挨着一块实心，中间没有过渡的像素）。
 * 只看 `分档` 分不出来：六块落在六档，也可能三档挤在 250 附近、另一档孤零零在 20。
 *
 * 用法：`ToneKt [--edge=N] 目录|图 …`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name } else listOf(f)
  }
  var midSum = 0.0; var topSum = 0.0; var n = 0
  var worstMid = 1.0; var worstName = ""
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val px = IntArray(img.width * img.height)
    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
    val out = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
      val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
      out[y * edge + x] = px[sy * img.width + sx]
    }
    val r = MonochromeMask.build(out, edge, edge)
    val m = r.mask ?: continue
    val bin = IntArray(8)
    var inked = 0; var top = 0; var mid = 0
    for (v in m) {
      val a = (v ushr 24) and 0xff
      if (a == 0) continue
      inked++
      bin[((a - 1) / 32).coerceIn(0, 7)]++
      if (a == 255) top++
      if (a in 64..191) mid++
    }
    if (inked == 0) continue
    n++
    val midF = mid.toDouble() / inked; val topF = top.toDouble() / inked
    midSum += midF; topSum += topF
    if (midF < worstMid) { worstMid = midF; worstName = f.nameWithoutExtension }
    println("--- ${f.nameWithoutExtension}")
    println("    " + bin.map { "%.1f%%".format(it * 100.0 / inked) }.joinToString(" ") +
      "   (1..32 左 → 224..255 右)  实心=%4.1f%% 中间带=%4.1f%%".format(topF * 100, midF * 100))
  }
  println("=== $n 张：中间带平均 %.1f%%，实心平均 %.1f%%；最空的是 %s（%.1f%%）"
    .format(midSum / n * 100, topSum / n * 100, worstName, worstMid * 100))
}
