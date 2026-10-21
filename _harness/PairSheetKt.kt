package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 「源 | 人画的单色层 | 我们交的」三联台 —— 对表差距压在谁身上，看图才知道。
 *
 * 为什么要单独一台：`YardKt` 给的是每对一个数（IoU / Δα），而**同一个数有两种相反的死法**：
 * 我们 2% 而人画 19%，与我们 70% 而人画 20%，IoU 可以都是 0.0x。
 * 光看数会把两类当一类修，一修就互相抵消（今天 `med 不动、mean 涨` 就是这么来的）。
 *
 * 用法：`harness.PairSheetKtKt <抓取目录> --only=a,b,c [--edge=384] [--out=bench/pairs]`
 */
private fun read(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0x40, 0x40, 0x40); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

fun main(args: Array<String>) {
  val dir = File(args.first { !it.startsWith("--") })
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val outDir = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/pairs")
    .apply { mkdirs() }
  outDir.listFiles()?.forEach { it.delete() }
  val only = (args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=") ?: "").split(',').filter { it != "" }
  val rows = File(dir, "index.tsv").readText().trim().split('\n').drop(1).map { it.split('\t') }
  var n = 0
  for (r in rows) {
    if (r.size < 16) continue
    if (only.isNotEmpty() && only.none { r[0].contains(it) }) continue
    val mono = File(dir, r[12]); val fg = File(dir, if (r[15] != "-") r[15] else r[2])
    if (!mono.isFile || !fg.isFile) continue
    val fgPx = read(fg, edge)
    val plateLayer = r[5].contains("Adaptive") && r[7] == "ColorDrawable" && r[15] != "-"
    var air = 0
    for (p in fgPx) if (((p ushr 24) and 0xff) < 250) air++
    val ours = MonochromeMask.build(fgPx, edge, edge, plateLayer && air * 100 / (edge * edge) >= 25).mask
      ?: IntArray(edge * edge)
    val theirs = read(mono, edge)
    val img = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, edge, edge, fgPx, 0, edge)
    img.setRGB(edge + 8, 0, edge, edge, ink(theirs, edge, true), 0, edge)
    img.setRGB(edge * 2 + 16, 0, edge, edge, ink(ours, edge, false), 0, edge)
    ImageIO.write(img, "png", File(outDir, "${r[0]}.png"))
    n++
  }
  println("$n 张三联（源 | 人画 | 我们）→ $outDir")
}

/** 把一张 α 掩码画成"深盘浅墨"，与屏幕上看到的一致。 */
private fun ink(px: IntArray, edge: Int, isSource: Boolean): IntArray = IntArray(px.size) {
  val p = px[it]; val a = (p ushr 24) and 0xff
  if (isSource) {
    val r = (((p shr 16) and 0xff) * a + 0x2B * (255 - a)) / 255
    val g = (((p shr 8) and 0xff) * a + 0x2B * (255 - a)) / 255
    val b = ((p and 0xff) * a + 0x2B * (255 - a)) / 255
    (0xff shl 24) or (r shl 16) or (g shl 8) or b
  } else {
    val v = (0x2B * (255 - a) + 0xE8 * a) / 255
    (0xff shl 24) or (v shl 16) or (v shl 8) or v
  }
}
