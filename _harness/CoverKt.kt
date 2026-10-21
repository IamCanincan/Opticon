package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **同一块源色，交付覆盖率落在中间** = 「缺一块」与「粗出一截」的共同形状（只量不改）。
 *
 * 主人给了两个形状：「某条线粗出一截」和「部分缺一块」。这两个都不是"浓度对不对"的问题，
 * 是**同一块东西只交了一半**。所以判据不能问浓度，要问覆盖率：
 *  1. 在**源图**里按粗色桶（与模型同一套 L/8、a·b/32）取连通块（8 邻接），面积 ≥ 框的 0.3%；
 *  2. 每一块数它自己有多少格交付了墨（alpha ≥128）→ **覆盖率**；
 *  3. 覆盖率应该两头尖：底 ≈0、图形 ≈1。**落在 0.15~0.85 之间的那一块，就是"刻了一半"** ——
 *     一条线只交出一半长度 = 看上去细一截/粗一截；一个面少一块 = 缺角。
 * ⚠ 分母**绝不能用模型自己认的图形**（`StrokeKt` 就废在这上面：被归进底的那半条线根本不在分母里，
 *   于是恒报"没问题"）。这里只用源图的连通块，模型爱怎么判怎么判。
 *
 * 用法：`harness.CoverKtKt [目录] [--edges=384] [--lo=0.15] [--hi=0.85] [--shot=DIR]`
 */
fun main(args: Array<String>) {
  val edges = (args.firstOrNull { it.startsWith("--edges=") }?.substringAfter("=") ?: "384")
    .split(",").map { it.trim().toInt() }
  val lo = args.firstOrNull { it.startsWith("--lo=") }?.substringAfter("=")?.toDouble() ?: 0.15
  val hi = args.firstOrNull { it.startsWith("--hi=") }?.substringAfter("=")?.toDouble() ?: 0.85
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let {
    File(it).apply { mkdirs() }
  }
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec3")
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  for (edge in edges) {
    val rows = ArrayList<CRow>()
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
      val m = runCatching { MonochromeMask.build(px, edge, edge).mask }.getOrNull() ?: continue
      val n = edge * edge
      val scr = MonochromeMask::class.java.getDeclaredField("pool").also { it.isAccessible = true }
        .get(MonochromeMask) as ThreadLocal<*>
      val s = scr.get() ?: continue
      val labL = cf(s, "labL") as ShortArray; val labA = cf(s, "labA") as ShortArray
      val labB = cf(s, "labB") as ShortArray
      val bucket = IntArray(n)
      for (i in 0 until n) {
        val l = (labL[i].toInt() and 0xffff) shr 6
        val a = ((labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val b = ((labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
        bucket[i] = ((l / 8) * 8 + (a / 32)) * 8 + (b / 32)
      }
      val minArea = edge * edge * 3 / 1000
      val seen = BooleanArray(n)
      val q = IntArray(n)
      for (i in 0 until n) {
        if (seen[i] || (px[i] ushr 24) < 250) continue
        val bk = bucket[i]
        var head = 0; var tail = 0; q[tail++] = i; seen[i] = true
        var size = 0; var inked = 0; var x0 = edge; var y0 = edge; var x1 = -1; var y1 = -1
        while (head < tail) {
          val c = q[head++]; size++
          val cx = c % edge; val cy = c / edge
          if (cx < x0) x0 = cx; if (cy < y0) y0 = cy; if (cx > x1) x1 = cx; if (cy > y1) y1 = cy
          if ((m[c] ushr 24) >= 128) inked++
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
            val j = ny * edge + nx
            if (!seen[j] && (px[j] ushr 24) >= 250 && bucket[j] == bk) { seen[j] = true; q[tail++] = j }
          }
        }
        if (size < minArea) continue
        val cov = inked.toDouble() / size
        if (cov in lo..hi) {
          rows += CRow(f.name, size, inked, cov, x1 - x0 + 1, y1 - y0 + 1, edge)
          if (shotDir != null) {
            val cells = IntArray(tail)
            System.arraycopy(q, 0, cells, 0, tail)
            val v = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
            val inBlock = BooleanArray(n)
            for (c in cells) inBlock[c] = true
            for (i in 0 until n) {
              val rgb = when {
                !inBlock[i] -> {
                  val g = 255 - (m[i] ushr 24)
                  (g shl 16) or (g shl 8) or g
                }
                (m[i] ushr 24) >= 128 -> 0x2E9B3F                // 绿 = 这块刻上了墨
                else -> 0xE03A2F                                 // 红 = 同一块里漏掉的
              }
              v.setRGB(i % edge, i / edge, rgb)
            }
            ImageIO.write(v, "png", File(shotDir, f.nameWithoutExtension + "-half.png"))
          }
        }
      }
    }
    rows.sortByDescending { it.size }
    println("=== edge=$edge · 覆盖率落在 $lo~$hi 之间的源色连通块（= 刻了一半）· ${rows.size} 块 ===")
    println("%-44s %7s %7s %6s %8s".format("图标", "块格数", "刻了", "覆盖率", "块尺寸"))
    rows.take(14).forEach { println(it.line()) }
    if (shotDir != null) {
      println("（要导 PNG 请单目录跑，导前 6 张：${rows.take(6).map { it.name.take(28) }.joinToString(" ") }）")
    }
    println()
  }
}

private class CRow(val name: String, val size: Int, val inked: Int, val cov: Double,
                   val w: Int, val h: Int, val edge: Int) {
  fun line() = "%-44s %7d %7d %6.2f %4dx%-4d".format(
    name.removeSuffix("-src.png").removeSuffix(".png").take(44), size, inked, cov, w, h)
}

private fun cf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
