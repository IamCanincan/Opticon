package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 沿一条水平线把**模型自己用的每一个量**并排打出来：源色、它背后那格参照、ΔE、dep、交付墨。
 * 用途：当"同一块实心色为什么交出两种浓度"这种问题出现时，只有把这一排数放在一行才看得出
 * 是哪一环散的（参照？截断？标定？）。
 *
 * 用法：`LineDbg <png> [--row=192] [--step=8]`
 */
fun main(args: Array<String>) {
  val path = args.firstOrNull { !it.startsWith("--") } ?: error("用法: LineDbg <png>")
  val row = (args.firstOrNull { it.startsWith("--row=") }?.substringAfter("=") ?: "192").toInt()
  val st = (args.firstOrNull { it.startsWith("--step=") }?.substringAfter("=") ?: "12").toInt()
  val img = ImageIO.read(File(path))
  val w = img.width; val h = img.height
  val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
  val out = MonochromeMask.build(src, w, h)
  val mask = out.mask ?: error("没有掩码")
  val s = (MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask)) as ThreadLocal<*>
  val sc = s.get()!!
  fun <T> f(name: String): T {
    for (x in sc.javaClass.declaredFields) if (x.name == name) { x.isAccessible = true; @Suppress("UNCHECKED_CAST") return x.get(sc) as T }
    throw NoSuchFieldException(name)
  }
  val plate = f<ByteArray>("plate"); val dist = f<ShortArray>("dist")
  val distT = f<ShortArray>("distT")
  val off = f<ShortArray>("off")
  val ref = f<IntArray>("ref")
  val labL = f<ShortArray>("labL"); val labA = f<ShortArray>("labA"); val labB = f<ShortArray>("labB")
  val fL = f<IntArray>("fL"); val fA = f<IntArray>("fA"); val fB = f<IntArray>("fB")
  println("${"x".padStart(4)}  源L   own  dep   墨 |  离体  格子  参照L  差u   底")
  for (x in 0 until w step st) {
    val i = row * w + x
    val c = ref[i]
    val rl = if (c >= 0) fL[c] / 64 else -1
    println("%4d  %4d %5d %4d %4d | %5d %5d %5d %5d %3d".format(
      x, labL[i].toInt() / 64, distT[i].toInt() and 0xffff, dist[i].toInt() and 0xffff, mask[i] ushr 24,
      off[i].toInt() and 0xffff, c, rl,
      labL[i].toInt() / 64 - rl, if (plate[i] != 0.toByte()) 1 else 0))
  }
  println("报告：" + out.report.take(200))
}
