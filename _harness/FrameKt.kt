package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **这张图是"模型吃的那一张"还是"桌面裁完画出来的那一张"？**
 *
 * 为什么要问：今天从 logcat 的分片 base64 里重组出设备真吃进去的那张 translate，
 * 和 `CorpusCapture` 抓回来的同名 PNG 一比 —— **尺寸不一样、内容框不一样**。
 * 而 `CorpusCapture` 存分层用的是 `layerBounds`（视口几何），装机那条 `rasterizeLayers`
 * 画的是整张 108dp 画布。如果语料是视口那一张，那今天所有拿 `corpus_runtime` 量的账
 * （全覆盖 35/61、选支搬 20 张）量的都是**放大过的那一张**。
 *
 * 用法：`FrameKt <png…>` —— 每张报：尺寸、四角 alpha、内容框占画布的边长%。
 */
fun main(args: Array<String>) {
  for (p in args.filter { !it.startsWith("--") }) {
    val img = runCatching { ImageIO.read(File(p)) }.getOrNull()
    if (img == null) { println("读不出 $p"); continue }
    val w = img.width; val h = img.height
    var minX = w; var maxX = -1; var minY = h; var maxY = -1; var op = 0
    for (y in 0 until h) for (x in 0 until w) {
      val a = (img.getRGB(x, y) ushr 24) and 0xff
      if (a >= 128) {
        op++
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
      }
    }
    val c = { x: Int, y: Int -> (img.getRGB(x, y) ushr 24) and 0xff }
    println("%-58s %4dx%d  实心%3d%%  内容框 %3d%%  角 %3d/%3d/%3d/%3d".format(
      File(p).name.take(58), w, h, op * 100 / (w * h),
      if (maxX < 0) 0 else maxOf(maxX - minX, maxY - minY) * 100 / minOf(w, h),
      c(1, 1), c(w - 2, 1), c(1, h - 2), c(w - 2, h - 2)))
  }
}
