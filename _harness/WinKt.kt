package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * 单张图标过一遍**真判据**：落到哪一档、窗口多大、放大几倍。
 *
 * 为什么要它：真机那两行只在"桌面 / 设置页真的画了这张图"时才留，而「某个应用裁出来不好看」
 * 这类报法第一件要回答的就是"它走的是哪一档、被放大到几倍"。离线拿真判据直接量，不用等触发。
 * ⚠ 与线上同口径：线上是把图标**光栅化到 `IconCropWindow.scanEdge`** 再判，
 *   这里按同一个边长喂（`of` 内部只会降采样，所以喂 192 与喂 128 的结论一致）。
 *
 * 用法：`sh run.sh harness.WinKtKt <png 或目录...> [--edge=N]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt()
    ?: IconCropWindow.scanEdge
  println("判据格子 ${edge}x${edge}（线上 scanEdge=${IconCropWindow.scanEdge}，" +
    "KEEP_RATIO 线 = ${IconCropWindow.keepRatioPercent}%）")
  println("%-14s %-9s %-7s %-8s %-8s %-8s %-9s".format(
    "图", "档", "keep%", "zoom", "boxW", "boxH", "rCover"))
  // 目录参数要自己展开：语料 700 多张，一次全塞进 argv 会 "Argument list too long"
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) {
      f.listFiles()!!.filter { it.extension.equals("png", true) && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") &&
        !it.name.endsWith("_ab.png") }.sortedBy { it.name }
    } else listOf(f)
  }
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull()
    if (img == null) { println("!! 读不出 ${f.name}"); continue }
    val sw = img.width
    val sh = img.height
    val s0 = IntArray(sw * sh)
    img.getRGB(0, 0, sw, sh, s0, 0, sw)
    // 与线上同一件事：按 scanEdge 重排成方画布（最近邻，线上那一步也是最近邻）
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    val w = IconCropWindow.of(px, edge, edge)
    if (w == null) {
      println("%-14s %-9s".format(f.name, "满幅/不动"))
    } else {
      println("%-14s %-9s %-7d %-8.3f %-8d %-8d %-9.1f".format(
        f.name, if (w.plate) "缩进补白" else "内切填满",
        w.keepPct, w.zoom, w.boxW, w.boxH, w.rCover))
    }
  }
}
