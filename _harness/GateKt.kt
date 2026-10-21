package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **门槛候选真值表**：H0（区域关系）到底该加哪一道门槛，不靠想，靠把候选量在
 * 「该接手」和「不该接手」两组上一次性摆出来。
 *
 * 为什么要这个驱动：`占比 / 厚度 / 围合 / 环散` 四个都是"这块参照长什么样"；H0 换成了
 * "有没有一块区域把别的围在里面"。但它在判据台上撞翻了「透明底+围住的镂空」，而上一轮给出的
 * 两个候选修法（区域要比洞里更**实**、洞里的要比区域更**亮**）都是**没量过就写进报告的** ——
 * 那条用例是"绿盘子(α=255) + 坐在正中的白圆(α=255)"，洞里的墨既不比区域更虚、也不比它更暗，
 * 和真图 小步点(白闪电坐在色盘上) 在这两个量上**同向**。所以这里把候选补齐、连**画布关系**
 * （内容框占整张画布多少、这块区域碰不碰画布自己的边）一起量。
 *
 * 用法：`GateKt [--edge=N] [--inset] 目录|文件 …`
 *   列：盒/布 = 内容框 ÷ 整幅；区/盒 = 最大区域 ÷ 内容框；触布 = 区域像素占画布四边的比例；
 *       紧实 = 区域 ÷ 它自己的包围盒；L / α 分别是区域与"洞里的墨"的平均值。
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val inset = args.contains("--inset")
  val flag = System.getProperty("skip", "preview")
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter {
      it.extension == "png" && !it.name.endsWith("_preview.png") && !it.name.contains(flag)
    }.sortedBy { it.name } else listOf(f)
  }
  println("%-46s %6s %6s %5s %5s %6s %5s %5s %5s %5s %5s %6s".format(
    "图", "盒/布", "区/盒", "挨盒", "触布", "洞墨/盒", "区L", "洞L", "区α", "洞α", "紧实", "H0"))
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val sw = img.width; val sh = img.height
    val src = IntArray(sw * sh)
    img.getRGB(0, 0, sw, sh, src, 0, sw)
    gate(edgeOf(src, sw, sh, edge, inset), edge, f.nameWithoutExtension.take(46))
  }
  // 合成用例：判据台那三张"盘子"直接照搬画法，量的是模型真正吃到的那张位图。
  val s = edge
  run {
    val p = IntArray(s * s)
    disc(p, s, s / 2, s / 2, s / 3) { _, _ -> px(255, 140, 200, 60) }
    disc(p, s, s / 2, s / 2, s / 10) { _, _ -> px(255, 255, 255, 255) }
    gate(p, s, "= 4 镂空(该拒)")
  }
  run {
    val p = IntArray(s * s)
    disc(p, s, s / 2, s / 2, s / 3) { x, y ->
      val d = sqrt(((x - s / 2) * (x - s / 2) + (y - s / 2) * (y - s / 2)).toDouble())
      px((255 - (d - (s / 3 - 2)) * 128).coerceIn(0.0, 255.0).toInt(), 30, 90, 200)
    }
    gate(p, s, "= 3 蓝圆AA(该拒)")
  }
  run {
    val p = IntArray(s * s)
    disc(p, s, s / 2, s / 2, s * 49 / 50) { _, _ -> px(255, 250, 200, 60) }
    disc(p, s, s / 2, s / 2, s / 9) { _, _ -> px(255, 255, 255, 255) }
    gate(p, s, "= 造 满幅盘+白核(该收)")
  }
}

private fun px(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

private fun disc(buf: IntArray, size: Int, cx: Int, cy: Int, r: Int, colorAt: (Int, Int) -> Int) {
  for (y in maxOf(0, cy - r)..minOf(size - 1, cy + r))
    for (x in maxOf(0, cx - r)..minOf(size - 1, cx + r)) {
      val dx = x - cx; val dy = y - cy
      if (dx * dx + dy * dy <= r * r) buf[y * size + x] = colorAt(x, y)
    }
}

/** 与 MaskBench 同一条缩放路径（drawImage），免得这里量到的几何和判据台吃的不是一张图。 */
private fun edgeOf(src: IntArray, sw: Int, sh: Int, edge: Int, inset: Boolean): IntArray {
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  val bmp = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
  bmp.setRGB(0, 0, sw, sh, src, 0, sw)
  val pad = if (inset) (edge * 0.195f).toInt() else 0
  g.drawImage(bmp, pad, pad, edge - 2 * pad, edge - 2 * pad, null)
  g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

private fun gate(px: IntArray, n: Int, name: String) {
  var x0 = n; var y0 = n; var x1 = -1; var y1 = -1
  for (y in 0 until n) for (x in 0 until n) if (((px[y * n + x] ushr 24) and 0xff) >= 8) {
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) { println("%-46s  空的".format(name)); return }
  val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
  val box = bw.toLong() * bh
  val air = BooleanArray(px.size) { ((px[it] ushr 24) and 0xff) < 128 }
  val bucket = IntArray(px.size) { -1 }
  for (y in y0..y1) for (x in x0..x1) {
    val i = y * n + x
    if (air[i]) continue
    val l = labOf(px[i])
    bucket[i] = ((l[0].toInt() / 8) * 64 + ((l[1] + 128).toInt() / 32) * 8 + ((l[2] + 128).toInt() / 32))
  }
  val id = IntArray(px.size) { -1 }
  val q = IntArray(px.size)
  var best = -1; var bestArea = 0L; var next = 0
  var rx0 = n; var rx1 = -1; var ry0 = n; var ry1 = -1
  var sr = 0L; var sg = 0L; var sb = 0L; var sa = 0L
  for (sy in y0..y1) for (sx in x0..x1) {
    val start = sy * n + sx
    if (id[start] >= 0 || bucket[start] < 0) continue
    val want = bucket[start]; val my = next++
    var head = 0; var tail = 0
    id[start] = my; q[tail++] = start
    var area = 0L
    var a0 = n; var a1 = -1; var b0 = n; var b1 = -1
    var r0 = 0L; var g0 = 0L; var b1s = 0L; var al = 0L
    while (head < tail) {
      val i = q[head++]
      val x = i % n; val y = i / n
      area++; r0 += (px[i] shr 16) and 0xff; g0 += (px[i] shr 8) and 0xff
      b1s += px[i] and 0xff; al += (px[i] ushr 24) and 0xff
      if (x < a0) a0 = x; if (x > a1) a1 = x; if (y < b0) b0 = y; if (y > b1) b1 = y
      for (d in intArrayOf(-1, 1, -n, n)) {
        if (d == -1 && x == 0) continue
        if (d == 1 && x == n - 1) continue
        val j = i + d
        if (j < 0 || j >= px.size || id[j] >= 0 || bucket[j] != want) continue
        id[j] = my; q[tail++] = j
      }
    }
    if (area > bestArea) {
      best = my; bestArea = area; rx0 = a0; rx1 = a1; ry0 = b0; ry1 = b1
      sr = r0; sg = g0; sb = b1s; sa = al
    }
  }
  if (best < 0) return
  var rim = false
  for (y in y0..y1) for (x in x0..x1) if (id[y * n + x] == best &&
    (x == x0 || x == x1 || y == y0 || y == y1)
  ) { rim = true; break }
  // 触布：这块区域碰到**整幅画布**四边的比例（不是内容框）
  var rimPix = 0L; var border = 0L
  for (x in 0 until n) { border += 2; if (id[x] == best) rimPix++; if (id[(n - 1) * n + x] == best) rimPix++ }
  for (y in 1 until n - 1) { border += 2; if (id[y * n] == best) rimPix++; if (id[y * n + n - 1] == best) rimPix++ }
  // 洞：区域的补集里碰不到内容框边的连通块
  val seen = BooleanArray(px.size)
  var holeInk = 0L; var hr = 0L; var hg = 0L; var hb = 0L; var ha = 0L
  for (sy in y0..y1) for (sx in x0..x1) {
    val start = sy * n + sx
    if (id[start] == best || seen[start]) continue
    seen[start] = true
    var head = 0; var tail = 0
    q[tail++] = start
    var touches = false; var ink = 0L; var r0 = 0L; var g0 = 0L; var b0 = 0L; var al = 0L
    while (head < tail) {
      val i = q[head++]
      val x = i % n; val y = i / n
      if (x <= x0 || y <= y0 || x >= x1 || y >= y1) touches = true
      if (!air[i]) {
        ink++; r0 += (px[i] shr 16) and 0xff; g0 += (px[i] shr 8) and 0xff
        b0 += px[i] and 0xff; al += (px[i] ushr 24) and 0xff
      }
      for (d in intArrayOf(-1, 1, -n, n)) {
        if (d == -1 && x == 0) continue
        if (d == 1 && x == n - 1) continue
        val j = i + d
        if (j < 0 || j >= px.size || seen[j] || id[j] == best) continue
        seen[j] = true; q[tail++] = j
      }
    }
    if (!touches) { holeInk += ink; hr += r0; hg += g0; hb += b0; ha += al }
  }
  fun lab(r: Long, g: Long, b: Long, cnt: Long): Double {
    if (cnt == 0L) return -1.0
    val color = (0xff shl 24) or ((r / cnt).toInt() shl 16) or
      ((g / cnt).toInt() shl 8) or (b / cnt).toInt()
    return labOf(color)[0]
  }
  val fill = bestArea * 100 / ((rx1 - rx0 + 1).toLong() * (ry1 - ry0 + 1)).coerceAtLeast(1)
  val h0 = bestArea * 100 >= box * 25 && rim && holeInk * 100 >= box * 2
  println("%-46s %5d%% %5d%% %5s %4d%% %6d.%d%% %5.1f %5.1f %5.0f %5.0f %5d%% %6s".format(
    name, box * 100 / (n.toLong() * n), bestArea * 100 / box, rim, rimPix * 100 / border,
    holeInk * 100 / box, (holeInk * 1000 / box) % 10,
    lab(sr, sg, sb, bestArea), lab(hr, hg, hb, holeInk),
    (if (bestArea == 0L) 0 else sa / bestArea).toFloat(),
    (if (holeInk == 0L) 0 else ha / holeInk).toFloat(),
    fill, if (h0) "接手" else "-")
  )
}

private fun labOf(p: Int): DoubleArray {
  fun srgb(v: Int): Double {
    val c = v / 255.0
    return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
  }
  val r = srgb((p shr 16) and 0xff); val g = srgb((p shr 8) and 0xff); val b = srgb(p and 0xff)
  var x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
  var y = 0.2126 * r + 0.7152 * g + 0.0722 * b
  var z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
  fun fw(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
  x = fw(x); y = fw(y); z = fw(z)
  return doubleArrayOf(116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z))
}
