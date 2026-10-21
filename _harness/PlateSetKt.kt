package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/** 数一遍语料里"裁圆判成裁不动、整幅缩进圆里"（`plate` 那一档）的是哪几张 —— 极性那条假设的波及面。 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val edge = 384
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
  var nPlate = 0; var nCut = 0; var nNull = 0
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    val win = IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= 1.82f }
    when {
      win == null -> nNull++
      win.plate -> { nPlate++; println("  plate  缩%.2f×  剩%d%%  %s".format(win.zoom, win.keepPct, f.nameWithoutExtension.take(46))) }
      else -> nCut++
    }
  }
  println("语料 ${files.size}：满幅不动 $nNull / 内切放大 $nCut / **裁不动缩进圆里(plate) $nPlate**")
}
