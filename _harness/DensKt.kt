package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **「上色偏淡」到底淡在哪一层**：把交出去的墨按**源像素自己实不实心**分成两堆量。
 *
 * 为什么这么分：判据台上"实心占比"这个数（`实255 / (中128-254 + 实255)`）在 42 张真图标上有
 * 33 张不到一半。但"不到一半"有两种完全不同的病：
 *  · 该实心的是**字形**（源 α=255 的那批），它没被顶到 255 → 浓度的标定/抬顶有问题；
 *  · 不该上墨的是**盘子自己那圈抗锯齿**（源 α<255 的那批），它反而拿了最多的墨 → 底没腾空，
 *    这正是判据台上还开着的那条 GAP「板子那圈抗锯齿交出 0 墨」。
 * 两种病的修法互斥，而只看总占比分不出来 —— 所以按源 α 劈开再量。
 *
 * 用法：`DensKt [--edge=N] 目录|文件 …`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val flag = System.getProperty("skip", "preview")
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains(flag)
    }.sortedBy { it.name } else listOf(f)
  }
  var nSolidInk = 0
  var nInverted = 0
  println("图\tsolid_px\tsolid_share\tsolid_mean\taa_px\taa_share\taa_mean\tdiag")
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val sw = img.width; val sh = img.height
    val src = IntArray(sw * sh)
    img.getRGB(0, 0, sw, sh, src, 0, sw)
    val bmp = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = bmp.createGraphics()
    g.drawImage(BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, sw, sh, src, 0, sw) },
      0, 0, edge, edge, null)
    g.dispose()
    val px = IntArray(edge * edge)
    bmp.getRGB(0, 0, edge, edge, px, 0, edge)
    val out = MonochromeMask.build(px, edge, edge)
    val mask = out.mask ?: continue
    // 两堆：源 α=255（真材实料的画）与源 α 在 128..254（边缘 / 柔光）
    val bins = Array(2) { IntArray(3) }   // [0]=<128 的墨, [1]=128-254, [2]=255 —— 每堆里按出墨浓度分
    var inkA = 0L; var inkB = 0L
    for (i in px.indices) {
      val a = (mask[i] ushr 24) and 0xff
      if (a == 0) continue
      val sa = (px[i] ushr 24) and 0xff
      val gk = if (sa >= 255) 0 else 1
      bins[gk][if (a >= 250) 2 else if (a >= 128) 1 else 0]++
      if (gk == 0) inkA += a else inkB += a
    }
    val totA = bins[0][0] + bins[0][1] + bins[0][2]
    val totB = bins[1][0] + bins[1][1] + bins[1][2]
    if (totA + totB == 0) continue
    nSolidInk++
    val solidShareA = if (totA == 0) -1.0 else bins[0][2].toDouble() / totA
    val solidShareB = if (totB == 0) -1.0 else bins[1][2].toDouble() / totB
    // "反了"= 那圈边缘比字形更实心（而且边缘不是零星几个）
    val inverted = totA > 0 && totB > totA / 4 && solidShareB > solidShareA
    if (inverted) nInverted++
    println(listOf(
      f.nameWithoutExtension.take(52), totA, "%.0f".format(if (totA == 0) 0.0 else solidShareA * 100),
      "%.0f".format(if (totA == 0) 0.0 else inkA.toDouble() / totA),
      totB, "%.0f".format(if (totB == 0) 0.0 else solidShareB * 100),
      "%.0f".format(if (totB == 0) 0.0 else inkB.toDouble() / totB),
      if (inverted) "边比字实" else if (solidShareA < 0.5) "字不实" else "OK")
      .joinToString("\t")
    )
  }
  println("=== $nSolidInk 张里：字形自己不实心的 ${nInverted} 张是「边比字实」")
}
