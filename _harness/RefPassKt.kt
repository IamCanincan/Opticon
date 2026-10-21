package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Method
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * **只跑"参照那一支"**（`buildOnce(forceRef = true)`）—— 把它的判据行与淡/墨两个计数打出来。
 *
 * 为什么需要它（2026-10-13）：知到在 `cand/FamRef`（每族参照）下 `零` 从 115 掉到 20，
 * 但 `build()` 最终**没走参照那一支**（报告里出现 `调=1` = 交的是满幅那一支），
 * 于是看不出来"场修好之后参照那一支到底交成什么样"。`build()` 先跑满幅（`forceRef=false`），
 * 非满幅才跑参照并按 `picksRef` 选 —— 这一把绕开选支，直接看参照那一支。
 *
 * 用法：`RefPassKt <图 …>`
 */
private val MM = MonochromeMask::class.java

private fun fieldOf(o: Class<*>, n: String) = o.getDeclaredField(n).also { it.isAccessible = true }

fun main(args: Array<String>) {
  val buildOnce: Method = MM.declaredMethods.first { it.name == "buildOnce" }.also { it.isAccessible = true }
  for (a in args.filter { !it.startsWith("--") }) {
    val f = File(a)
    if (!f.exists()) continue
    val img = ImageIO.read(f) ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val out = buildOnce.invoke(MonochromeMask, px, w, h, false, true, null, false, false, false,
      true, true, false) ?: continue
    // 顺手把参照那一支**画出来**（白底、值=255−α）——「这张该不该走另一支」是看图题，不是读数题。
    run {
      val mk = fieldOf(out.javaClass, "mask").get(out) as IntArray?
      if (mk != null && mk.size >= w * h) {
        val od = File("D:/tmp/refpass").apply { mkdirs() }
        val v = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
          val g = 255 - ((mk[y * w + x] ushr 24) and 0xff)
          v.setRGB(x, y, (g shl 16) or (g shl 8) or g)
        }
        ImageIO.write(v, "png", File(od, f.nameWithoutExtension + ".png"))
      }
    }
    val cls = out.javaClass
    val report = fieldOf(cls, "report").get(out) as String
    val mask = fieldOf(cls, "mask").get(out) as IntArray?
    val b = minOf(w, h) * 3 / 4; val ox = (w - b) / 2; val oy = (h - b) / 2
    var soft = 0; var ink = 0
    if (mask != null && mask.size >= w * h) for (y in oy until oy + b) for (x in ox until ox + b) {
      val al = (mask[y * w + x] ushr 24) and 0xff
      if (al > 0) { ink++; if (al < 128) soft++ }
    }
    val n = (b.toLong() * b).coerceAtLeast(1)
    println("%-42s 参照支：淡=%d%% 墨=%d%%（选支门槛 淡≤4 / 墨 60..60）".format(
      f.nameWithoutExtension.take(42), (soft * 100 / n).toInt(), (ink * 100 / n).toInt()))
    println("    $report")
  }
}
