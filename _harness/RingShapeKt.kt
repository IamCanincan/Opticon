package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * 细环满幅快捷方式（拼多多四条）的**淡灰盘**探针（2026-10-25）。
 *
 * 现场：这四条按满幅 + 窗口重排后，三条的掩码只有红色线稿（bbox ≈ 43–48%），`search_express`
 * 却是 **98% 的一整块盘**（模型把淡灰盘交成了墨）。彩色那支的淡灰盘是底（主题图标会由桌面
 * 自己的盘代替），单色不该把它当图案。
 *
 * 这一台只回答一个问题：**把低彩度（淡灰）像素从模型输入里去掉，`search_express` 的掩码
 * 会不会回到线稿，另外三条会不会变。** 判据与生产同一个 `build`；生产若采用，应只在
 * "首张掩码 bbox ≥0.9" 时重算，三条（43–48%）逐字不动。
 *
 * 用法：`sh run.sh harness.RingShapeKtKt shortcut_bit [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "shortcut_bit")
  for (name in listOf("chat", "order", "one_click_search", "search_express")) {
    val f = dir.listFiles()!!.first { it.name.contains("xunmeng.pinduoduo_$name") }
    val img = ImageIO.read(f) ?: continue
    val px = rasterTo(img, edge, edge)
    val win = IconCropWindow.of(px, edge, edge) ?: continue
    // 生产：细环满幅的结论读**彩色格**（108/128），模型输入仍用 384 的 `winMeasured`。
    val (cw, ch) = IconCropWindow.scanSize(img.width, img.height, false)
    val cpx = rasterTo(img, cw, ch)
    val wc = IconCropWindow.of(cpx, cw, ch) ?: continue
    if (!IconCropWindow.shortcutFullBleed(wc, cpx, cw, ch)) {
      println("${f.name} 不是细环满幅，跳过"); continue
    }
    val inp = IconCropWindow.reframe(px, edge, edge, win)
    val base = MonochromeMask.build(inp, edge, edge, false)
    val clean = chromaClean(inp)
    val cleanOut = MonochromeMask.build(clean, edge, edge, false)
    println("%-16s 原=%-6s 去淡灰=%-6s".format(name, box(base.mask, edge), box(cleanOut.mask, edge)))
    if (name == "search_express") {
      println("── 原输入 ──"); ascii(base.mask, edge)
      println("── 去低彩度 ──"); ascii(cleanOut.mask, edge)
    }
  }
}

/** max-min < 30 的像素置透明（淡灰盘/灰环；红色线稿的彩度 ≈190）。 */
private fun chromaClean(px: IntArray): IntArray {
  val out = px.copyOf()
  for (i in out.indices) {
    val p = out[i]
    val a = (p ushr 24) and 0xff
    if (a == 0) continue
    val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
    if (max(r, max(g, b)) - min(r, min(g, b)) < 30) out[i] = 0
  }
  return out
}

private fun box(mask: IntArray?, edge: Int): String {
  if (mask == null) return "无"
  var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
  for (y in 0 until edge) for (x in 0 until edge) {
    if ((mask[y * edge + x] ushr 24) < 8) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x
    if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) return "空"
  return "%.0f%%".format(max(x1 - x0 + 1, y1 - y0 + 1) * 100f / edge)
}

private fun ascii(mask: IntArray?, edge: Int) {
  if (mask == null) { println("(无掩码)"); return }
  val cols = 40; val rows = 18
  for (ry in 0 until rows) {
    val sb = StringBuilder("  ")
    for (rx in 0 until cols) {
      val x0 = rx * edge / cols; val y0 = ry * edge / rows
      var hi = 0
      for (y in y0 until max(y0 + 1, (ry + 1) * edge / rows))
        for (x in x0 until max(x0 + 1, (rx + 1) * edge / cols))
          hi = max(hi, (mask[y * edge + x] ushr 24) and 0xff)
      sb.append(if (hi >= 200) '#' else if (hi >= 64) '+' else if (hi > 0) '·' else ' ')
    }
    println(sb)
  }
}

private fun rasterTo(img: java.awt.image.BufferedImage, w: Int, h: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val sy = (y.toLong() * img.height / h).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / w).toInt().coerceAtMost(img.width - 1)
    px[y * w + x] = s0[sy * img.width + sx]
  }
  return px
}
