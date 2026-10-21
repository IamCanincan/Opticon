package harness

import java.io.File
import javax.imageio.ImageIO
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 把图打成三张 ASCII 图：**A = 源不透明度**、**L = 源亮度**、**M = 模型交出的墨**。
 *
 * 为什么三张一起看：「灰框」这类毛病只有两种来路 —— 源图那里本来就有淡东西（A 上看得到，
 * 那是设计者的柔光/阴影，模型只是照实上墨），或者源图那里**全透明**却仍然被上了墨
 * （A 是空的、M 有框 → 病灶在模型里）。两种修法完全不同，只看一张分不出来。
 *
 * 用法：`MapDumpKt 图1 图2 …`（`-src.png` 会顺手跑一遍模型画 M；给 mask/预览图就只画它自己）。
 */
fun main(args: Array<String>) {
  val files = args.toList().ifEmpty {
    listOf("cap3/icon46_com.azure.authenticator_mipmap_ic_launcher-src.png")
  }
  for (f in files) {
    val file = File(f)
    if (!file.exists()) { println("跳过（不存在）：$f"); continue }
    val img = ImageIO.read(file)
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    println("=== ${file.name}  (${w}x$h) ===")
    grid("A 不透明度", px, w, h) { _, a, _, _ -> a }
    grid("L 亮度（透明的算黑）", px, w, h) { _, a, l, _ -> if (a < 0.02) 0.0 else l / a }
    if (f.endsWith("-src.png")) {
      val r = MonochromeMask.build(px, w, h)
      val mask = r.mask
      if (mask == null) println("  M 模型没有出图：${r.report}")
      else {
        grid("M 模型交出的墨", mask, w, h) { _, a, _, _ -> a }
        // 一句总账：模型在**源图完全透明**的地方上了多少墨。
        // 这一条不为零就不是"设计者的柔光"，是模型自己造出来的东西（灰框就是它的矩形版）。
        var ghost = 0; var ghostMax = 0; var inked = 0
        var nearAir = 0; var nearAirInk = 0.0; var nearAirMax = 0
        var srcMin = 255
        for (i in 0 until w * h) {
          val sa = (px[i] ushr 24) and 0xff
          val ma = (mask[i] ushr 24) and 0xff
          if (ma > 0) {
            inked++
            if (sa == 0) { ghost++; if (ma > ghostMax) ghostMax = ma }
            if (sa in 1..40) { nearAir++; nearAirInk += ma; if (ma > nearAirMax) nearAirMax = ma }
          }
          if (ma > 40 && sa < srcMin) srcMin = sa
        }
        println("  鬼影：上了墨的 %d 像素里 源α=0 的 %d（最浓 %d）；源α∈1..40 的 %d（平均墨 %.0f，最浓 %d）"
          .format(inked, ghost, ghostMax, nearAir, nearAirInk / nearAir.coerceAtLeast(1), nearAirMax))
        println("  最窄一条：墨 >40 的像素里，源 α 最小 = $srcMin")
        println("  ${r.report}")
      }
    }
  }
}

/** 28×28 分块打一张图。[of] 拿到的是这一格的 (像素, α 之和, α·亮度之和, 像素数)。 */
private fun grid(title: String, px: IntArray, w: Int, h: Int, of: (Int, Double, Double, Int) -> Double) {
  val ramp = " .:-=+*#%@"
  val n = 28
  println("  $title")
  for (j in 0 until n) {
    val sb = StringBuilder("   ")
    for (i in 0 until n) {
      var lum = 0.0; var a = 0.0; var c = 0; var sample = 0
      for (y in (j * h / n) until ((j + 1) * h / n).coerceAtMost(h))
        for (x in (i * w / n) until ((i + 1) * w / n).coerceAtMost(w)) {
          val p = px[y * w + x]
          val al = ((p ushr 24) and 0xff) / 255.0
          a += al
          lum += al * ((p ushr 16 and 0xff) * 0.2126 + (p ushr 8 and 0xff) * 0.7152 + (p and 0xff) * 0.0722)
          if (sample == 0) sample = p
          c++
        }
      val v = of(sample, a / c, lum / c, c).coerceIn(0.0, 1.0)
      sb.append(ramp[(v * 9.999).toInt().coerceIn(0, 9)])
    }
    println(sb)
  }
}
