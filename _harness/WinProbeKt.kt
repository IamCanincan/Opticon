package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * **一张图标的裁圆窗口内部账**：把 PNG 降到判据格（[IconCropWindow.scanSize]）后调
 * `IconCropWindow.measure`，把 `keepPct / zoom / rCover/rBox / spiky / plate` 全部打出来。
 *
 * 为什么要它（2026-11-10 主人「设备策略和密钥验证器都被裁成了最大内切圆」）：
 * 设备日志只记 `框= 缩= 白底=`，看不到**为什么**没走"缩入白底"那一档 ——
 * keep 与 spiky 两条入口的数在装机日志里一个字都没有。
 *
 * 用法：`harness.WinProbeKtKt <图标.png> [--canonical]`
 */
fun main(args: Array<String>) {
  val plain = args.filter { !it.startsWith("--") }
  if (plain.isEmpty()) { println("用法：WinProbeKtKt <图标.png> [--canonical]"); return }
  val f = File(plain[0])
  val img = ImageIO.read(f) ?: run { println("!! 读不了 ${f.path}"); return }
  val canonical = args.contains("--canonical")
  val (sw, sh) = IconCropWindow.scanSize(img.width, img.height, canonical)
  if (sw <= 0 || sh <= 0) { println("!! 尺寸不知道：${img.width}x${img.height}"); return }
  val src = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, src, 0, img.width)
  val px = IntArray(sw * sh)
  for (y in 0 until sh) for (x in 0 until sw) {
    val sy = (y.toLong() * img.height / sh).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / sw).toInt().coerceAtMost(img.width - 1)
    px[y * sw + x] = src[sy * img.width + sx]
  }
  // 内容框的"填充率"：墨格 / 外接框面积。方盘≈0.95+，圆≈0.78，立体图形（立方体/盾）≈0.7~0.8。
  var x0 = sw; var y0 = sh; var x1 = -1; var y1 = -1; var ink = 0
  for (y in 0 until sh) for (x in 0 until sw) {
    if ((px[y * sw + x] ushr 24) < 32) continue
    ink++
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
  val fill = if (bw > 0 && bh > 0) ink.toDouble() / (bw.toLong() * bh) else 0.0
  println("  内容框=$bw" + "x$bh（占画布 ${"%.0f".format(bw * 100.0 / sw)}%）  填充率=${"%.2f".format(fill)}")
  val w = IconCropWindow.measure(px, sw).let { it }
  println("${f.name}  ${img.width}x${img.height} → 判据格 ${sw}x$sh")
  if (w == null) { println("  窗口=null（满幅不动 / 超上限），即「不裁也不缩」"); return }
  println("  窗口=[${"%.3f".format(w.l)},${"%.3f".format(w.t)}]-[${"%.3f".format(w.r)},${"%.3f".format(w.b)}]" +
    "  plate=${w.plate}  keep=${w.keepPct}%  zoom=${"%.3f".format(w.zoom)}" +
    "  rCover/rBox=${"%.3f".format(w.rCover / w.rBox)}  spiky=${w.spiky}  box=${w.boxW}x${w.boxH}")
}
