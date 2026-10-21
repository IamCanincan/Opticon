package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * 两点之间到底差多少 —— 判"这是颜色墙还是我的门槛"用的最小工具。
 * 用法：`GapKt <png> --a=x,y --b=x,y [--edge=384]`
 */
fun main(args: Array<String>) {
  val f = File(args.first { !it.startsWith("--") })
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val a = args.first { it.startsWith("--a=") }.substringAfter("=").split(",").map { it.toInt() }.toIntArray()
  val b = args.first { it.startsWith("--b=") }.substringAfter("=").split(",").map { it.toInt() }.toIntArray()
  val img = ImageIO.read(f)
  val sw = img.width; val sh = img.height
  val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
    val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
    px[y * edge + x] = s0[sy * sw + sx]
  }
  val r = MonochromeMask.build(px, edge, edge)
  val m = r.mask!!
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val s = pool.get()!!
  val plate = cf(s, "plate") as ByteArray
  val dist = cf(s, "dist") as ShortArray
  val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
  val labB = cf(s, "labB") as ShortArray
  fun at(n: String, p: IntArray): Triple<Int, Int, Int> {
    val i = p[1] * edge + p[0]
    val c = px[i]
    println("  %s (%d,%d)  #%06X α=%d  L=%.1f a=%.1f b=%.1f  dep=%d  %s  交付=%d".format(n, p[0], p[1],
      c and 0xFFFFFF, c ushr 24,
      (labL[i].toInt() and 0xffff) / 64.0, labA[i].toInt() / 64.0, labB[i].toInt() / 64.0,
      dist[i].toInt() and 0xffff, if (plate[i] == 0.toByte()) "墨侧" else "底侧", m[i] ushr 24))
    return Triple(labL[i].toInt() and 0xffff, labA[i].toInt(), labB[i].toInt())
  }
  println("=== ${f.name} @edge=$edge ===")
  val pa = at("A", a); val pb = at("B", b)
  val dl = (pa.first - pb.first) / 64.0; val da = (pa.second - pb.second) / 64.0; val db = (pa.third - pb.third) / 64.0
  println("  **A↔B 的 ΔE = %.1f**   (ΔL %.1f, Δa %.1f, Δb %.1f)".format(
    sqrt(dl * dl + da * da + db * db), dl, da, db))
  println("  报告：${r.report.substringAfter("零=").substringBefore(" 实=").let { "零=$it" } }" +
    " 实=${r.report.substringAfter("实=").substringBefore(" ")}   框内=${r.report.substringAfter("框内=").substringBefore(" ")}")
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
