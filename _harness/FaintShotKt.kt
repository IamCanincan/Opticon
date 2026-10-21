package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **交付档渲染台**（只出图，不判据）—— 2026-10-19。
 *
 * 造它的理由：`_eg7` 那批 PNG 查不出是哪一台、哪一次编出来的，而 `out/` 的 class 比源文件还早两分钟。
 * 「漏了多少细线」这种数一旦建立在**上一轮的字节码**上就全废（本仓反复犯的那条：探针必须走生产那份原语）。
 * 所以这里直接调 `MonochromeMask.build(pixels, w, h)`（生产入口、默认参数），
 * 把返回掩码的 α 合成到白底上：`值 = 255 - α`、α 一律 255 —— 和 `_eg7` 同格式，
 * 但字节码保证是当前工作区那份源。
 *
 * 用法：`sh run.sh harness.FaintShotKtKt <源目录> <输出目录>`
 */
fun main(args: Array<String>) {
  if (args.size < 2) { println("用法：FaintShotKtKt <源目录> <输出目录>"); return }
  val src = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
  var n = 0
  src.listFiles { f: File -> f.name.endsWith(".png") }?.sortedBy { it.name }?.forEach { f ->
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h)
    val m = r.mask
    if (m == null) { println("无掩码 ${f.name}  ${r.report}"); return@forEach }
    val v = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) for (x in 0 until w) {
      val a = (m[y * w + x] ushr 24) and 0xff
      val g = 255 - a
      v.setRGB(x, y, (0xff shl 24) or (g shl 16) or (g shl 8) or g)
    }
    ImageIO.write(v, "png", File(outDir, f.name))
    n++
  }
  println("导出 $n 张 -> ${outDir.path}")
}
