package harness

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 把 `MaskBench --shot` 出的预览拼成** Contact sheet **：一屏看几十张，人眼扫类别。
 *
 * 为什么需要它：判据数字在这一族上已经量死过好几轮（满幅插画的 `零=115` 顶格、`降>10%` 那道闸、
 * 三把颜色尺分不开"底本来就花"与"底被图形占了"），再盯数字只会把同一个死端重新想一遍。
 * 而真机语料一次进来 122 张，**里面有没有我们没见过的坏法**，只有看图知道。
 *
 * 预览一张是三联（原图 | 浅色 | 深色），这里取**第三联**：那是掩码当不透明度压在深色盘上的样子，
 * 也就是屏幕上看得见的东西。格子下面写包名（截断）。
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "bench/preview")
  val cell = arg(args, "--cell=") ?: 116
  val cols = arg(args, "--cols=") ?: 7
  val rows = arg(args, "--rows=") ?: 7
  val outDir = File(argS(args, "--out=") ?: "bench/montage").apply { mkdirs() }
  val files = dir.listFiles { f: File -> f.extension == "png" && !f.name.contains("montage") }!!
    .sortedBy { it.name }
  val per = cols * rows
  val labelH = 14
  var sheet = 0
  for (start in files.indices step per) {
    sheet++
    val group = files.subList(start, minOf(files.size, start + per))
    val all = args.contains("--all")
    // --all 时一格要放整张多联（宽 = 联数 × cell），画布必须跟着算，否则右边几格被裁掉，
    // 看上去就只剩源图 —— 第一版就这么错过，四张点名图只看得见原图。
    // 联数从**图自己的宽高比**读（三联 ≈3、四联 ≈4），别写死：写死过一次，加了第四联就裁掉一格。
    val panels = if (args.contains("--all") && group.isNotEmpty())
      (ImageIO.read(group[0]).let { it.width / it.height }.coerceAtLeast(1)) else 1
    val cw = if (args.contains("--all")) cell * panels + 8 * (panels - 1) else cell
    val img = BufferedImage(cols * (cw + 6), rows * (cell + labelH + 6), BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color(0x40, 0x40, 0x40)
    g.fillRect(0, 0, img.width, img.height)
    g.font = Font("Monospaced", Font.PLAIN, 11)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    for ((i, f) in group.withIndex()) {
      val src = ImageIO.read(f)
      // 三联里取第三联。**不能用 getRGB 的 stride 参数直接裁**：那个 stride 是**目标数组**每行
      // 的元素数，按源图宽填进去的话，每行会被塞进错误的偏移，拼出来是一张斜纹噪点（第一版就是）。
      val x = (i % cols) * (cw + 6) + 3
      val y = (i / cols) * (cell + labelH + 6) + 3
      // --all：画整张三联（原图|浅色|深色），不裁第三联 —— 主人点名要看几张的时候用这个，
      //     只看掩码分不出「是源图本来就这样」还是「我们做坏了」。
      val full = args.contains("--all")
      // `--panel=1|2|3`：1=源图、2=**浅盘深墨（=白天桌面上看到的那一张）**、3=深盘浅墨（默认）。
      // ⚠ 默认取第三联是有理由的（夜里那一张就是它），但**比较极性的时候它会把结论读反**：
      //   深盘联里"墨"画成亮的，白天桌面上"墨"是暗的 —— 同一张掩码两联互为负片。
      //   2026-10-10 主人问"哪种风格"时先看了第三联，差点按负片回答他。
      val panel = arg(args, "--panel=") ?: 3
      g.drawImage(if (full) src else src.getSubimage(
        src.width * (panel - 1) / 3 + 2, 0, src.width / 3 - 4, src.height),
        x, y, cw, cell, null)
      g.color = Color.WHITE
      // 标签先去掉 `iconNN_` 再截 —— 原来直接 `substringBefore('_')`，拼出来的全是 "icon70" 这种编号，
      // 一张 contact sheet 上认不出是哪个应用（2026-10-10 全量重看图时才撞上）。
      val name = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").substringBefore('_').take(20)
      g.drawString(name, x, y + cell + 11)
    }
    g.dispose()
    val out = File(outDir, "montage_%02d.png".format(sheet))
    ImageIO.write(img, "png", out)
    println("${out.path}  ${group.size} 张")
  }
  println("共 ${files.size} 张 / $sheet 版")
}

private fun arg(args: Array<String>, key: String): Int? =
  args.firstOrNull { it.startsWith(key) }?.substringAfter('=')?.toIntOrNull()

private fun argS(args: Array<String>, key: String): String? =
  args.firstOrNull { it.startsWith(key) }?.substringAfter('=')
