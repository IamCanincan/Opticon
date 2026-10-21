package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **局部反常：一格和"它自己周围那一圈"差多少**（只量不改）。
 *
 * 为什么不能用全局中位色（`FieldKt` 就死在这上面）：它报 feimo 有 98% 的"底"离边界中位色 ≥10 ΔE ——
 * 那不是污染，那是**插画图标的底本来就多色**。全局尺子分不开"底本来就渐变"与"底被图形占了"。
 *
 * 换成局部尺子就分得开：
 *  · **底本来就渐变** → 任意一格的周围一圈和它自己差不多，**局部反常 ≈ 0**；
 *  · **一条其实是图形、却被吸进底**（globalspeed 那条续弧）→ 它两侧都是真背景，
 *    周围一圈的中位色就是背景色，于是**局部反常 = 续弧↔背景那 13.4**。
 * 半径取 18~24px：globalspeed 那条环宽 ~25px，半宽 12，这个半径落在环**外面**，取到的是背景；
 * 而一条 2px 的描边，同一圈取到的也是它两侧的东西。**两种都量得对。**
 *
 * 每张报：局部反常 ≥DE 的底侧格占多少、这些格连成的**最大一块**多大，并导一张热图。
 *
 * 用法：`harness.LocalKtKt [目录] [--edges=384] [--de=12] [--r=20] [--shot=DIR]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val DE = args.firstOrNull { it.startsWith("--de=") }?.substringAfter("=")?.toInt() ?: 12
  val R = args.firstOrNull { it.startsWith("--r=") }?.substringAfter("=")?.toInt() ?: 20
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let {
    File(it).apply { mkdirs() }
  }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  for (edge in edges) {
    val rows = ArrayList<LRow>()
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
      val out = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val m = out.mask ?: continue
      val s = pool.get() ?: continue
      val plate = cf(s, "plate") as ByteArray
      val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
      val labB = cf(s, "labB") as ShortArray
      val n = edge * edge
      val hot = BooleanArray(n)
      var groundN = 0
      val nl = ArrayList<Int>(24); val na = ArrayList<Int>(24); val nb = ArrayList<Int>(24)
      for (y in 1 until edge - 1) for (x in 1 until edge - 1) {
        val i = y * edge + x
        if (plate[i] == 0.toByte() || (px[i] ushr 24) < 250) continue
        groundN++
        nl.clear(); na.clear(); nb.clear()
        for (k in 0 until 24) {
          val ang = k * Math.PI / 12.0
          for (rr in intArrayOf(R - 2, R, R + 2)) {
            val nx = (x + cos(ang) * rr).toInt(); val ny = (y + sin(ang) * rr).toInt()
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (plate[j] == 0.toByte() || (px[j] ushr 24) < 250) continue   // 只拿**周围那些底**当参照
            nl += labL[j].toInt() and 0xffff; na += labA[j].toInt(); nb += labB[j].toInt()
          }
        }
        if (nl.size < 24) continue
        nl.sort(); na.sort(); nb.sort()
        val dl = ((labL[i].toInt() and 0xffff) - nl[nl.size / 2]) / 64.0
        val da = (labA[i].toInt() - na[na.size / 2]) / 64.0
        val db = (labB[i].toInt() - nb[nb.size / 2]) / 64.0
        if (sqrt(dl * dl + da * da + db * db) >= DE) hot[i] = true
      }
      var cnt = 0
      for (v in hot) if (v) cnt++
      val seen = BooleanArray(n); val q = IntArray(n)
      var best = 0
      for (i in 0 until n) {
        if (!hot[i] || seen[i]) continue
        var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
        while (head < tail) {
          val c = q[head++]; val cx = c % edge; val cy = c / edge
          for (k in 0 until 4) {
            val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (hot[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
          }
        }
        if (tail > best) best = tail
      }
      rows += LRow(f.name, cnt, best, groundN)
      if (shotDir != null && best >= edge * edge / 200) {
        val v = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until n) {
          val a = m[i] ushr 24
          v.setRGB(i % edge, i / edge, when {
            hot[i] -> 0xE03A2F
            plate[i] != 0.toByte() -> 0xE8E8E8
            else -> { val t = a / 255.0; (((31 * t + 237 * (1 - t)).toInt()) shl 16) or
              (((31 * t + 237 * (1 - t)).toInt()) shl 8) or ((31 * t + 237 * (1 - t)).toInt()) }
          })
        }
        ImageIO.write(v, "png", File(shotDir, f.nameWithoutExtension + "-local.png"))
      }
    }
    rows.sortByDescending { it.blob }
    println("=== edge=$edge · ${rows.size} 张 · 局部反常 ≥$DE 的底侧格（半径 $R）===")
    println("%-44s %8s %8s %6s".format("图标", "格数", "最大块", "占底"))
    rows.take(12).forEach { println(it.line()) }
    val share = rows.map { it.cells * 100 / maxOf(1, it.ground) }.sorted()
    println("占底 中位 ${share[share.size / 2]}%  p90 ${share[share.size * 9 / 10]}%   " +
      "有一大块（≥框 1%）的 = ${rows.count { it.blob >= edge * edge / 100 }} 张")
    println()
  }
}

private class LRow(val name: String, val cells: Int, val blob: Int, val ground: Int) {
  fun line() = "%-44s %8d %8d %5.1f%%".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(44), cells, blob,
    cells * 100.0 / maxOf(1, ground))
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
