package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 把一张图标的**交付掩码**摊成 ASCII（本会话看不了图片，只能这么"看"）。
 *
 * 打三块：
 *  ① `源`   源像素的亮度（暗=@，亮=空格）—— 看画面本身长什么样；
 *  ② `交付` 交付 α（9=255、0=0）—— 看我们最后交出什么形状；
 *  ③ `底`    `plate` 判成底的格（`#`）—— 看归底判得对不对。
 *
 * 用法：`sh run.sh harness/AsciiKt rec_model/<某张>.png [列数]`
 */
object AsciiKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val f = File(args[0])
    val cols = args.getOrNull(1)?.toIntOrNull() ?: 48
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val px = IntArray(w * h)
    img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h)
    val mask = r.mask
    val rows = cols * h / w
    println("=== ${f.nameWithoutExtension}  ${w}x$h → $cols x $rows")
    println("--- 源亮度（@ 最暗，空格最亮，. 透明）")
    for (ry in 0 until rows) {
      val sb = StringBuilder()
      for (rx in 0 until cols) {
        val x = rx * w / cols; val y = ry * h / rows
        val p = px[y * w + x]
        if ((p ushr 24) < 128) { sb.append('.'); continue }
        val l = (0.299 * ((p shr 16) and 0xff) + 0.587 * ((p shr 8) and 0xff) + 0.114 * (p and 0xff)).toInt()
        sb.append("@%#*+=-:. "[ (l * 10 / 256).coerceIn(0, 9) ])
      }
      println(sb)
    }
    if (mask == null) { println("--- 没有掩码"); return }
    println("--- 交付 α（9=255，0=透明）")
    for (ry in 0 until rows) {
      val sb = StringBuilder()
      for (rx in 0 until cols) {
        val x = rx * w / cols; val y = ry * h / rows
        val a = (mask[y * w + x] ushr 24)
        sb.append(if (a == 0) '.' else ('0' + (a * 9 / 255).coerceIn(0, 9)))
      }
      println(sb)
    }
    // 上墨格的源色构成：按 RGB/24 分桶（与 plateKey 同档），报前 8
    val m = HashMap<Int, Int>(); var ink = 0
    for (i in 0 until w * h) {
      if ((mask[i] ushr 24) == 0) continue
      ink++
      val k = ((((px[i] shr 16) and 0xff) / 24) shl 10) or ((((px[i] shr 8) and 0xff) / 24) shl 5) or ((px[i] and 0xff) / 24)
      m[k] = (m[k] ?: 0) + 1
    }
    println("--- 上墨格 $ink 个，源色前 8（RGB 桶）")
    for ((k, v) in m.entries.sortedByDescending { it.value }.take(8)) {
      val r = ((k shr 10) and 0x1f) * 24; val g = ((k shr 5) and 0x1f) * 24; val b = (k and 0x1f) * 24
      println(String.format("   #%02X%02X%02X  %6d  %.1f%%", r, g, b, v, v * 100.0 / ink))
    }
    // 剩下上墨的格，离"环族色"（这里直接用纯白的 Lab）多远
    run {
      val wl = MonochromeMask.labChannel(0xFFFFFFFF.toInt(), 0)
      val wa = MonochromeMask.labChannel(0xFFFFFFFF.toInt(), 1)
      val wb = MonochromeMask.labChannel(0xFFFFFFFF.toInt(), 2)
      val hist = IntArray(9)
      for (i in 0 until w * h) {
        if ((mask[i] ushr 24) == 0) continue
        val l = MonochromeMask.labChannel(px[i], 0); val a = MonochromeMask.labChannel(px[i], 1); val b = MonochromeMask.labChannel(px[i], 2)
        val dl = (l - wl) / 64.0; val da = (a - wa) / 64.0; val db = (b - wb) / 64.0
        val d = Math.sqrt(dl * dl + da * da + db * db).toInt()
        hist[(d / 10).coerceIn(0, 8)]++
      }
      println("--- 剩余上墨格 离白 ΔE 分布：" + (0..8).joinToString(" ") { "${it * 10}-${it * 10 + 9}:${hist[it]}" })
    }
    println("--- 报告")
    println("   " + r.report.take(400))
  }
}
