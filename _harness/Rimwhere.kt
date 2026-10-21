package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 盘沿外圈那些**强墨**到底是什么身份。
 *
 * 起因：用户两次报"外圈还是有痕迹"。`--rim` 报 0 格，可 ASCII 图上盘沿明明有 8 处强墨。
 * 这一支问的是我先前没问的那一句：**它们是不是已经在底里了** —— 因为如果在底里，
 * 那痕迹就不是"没进底"，而是底自己的出图规则 `α×(1−β)` 把它们又画回去了：
 * 我上一次修的是 membership，修完它们进了底，可交付那一半根本没动。
 *
 * 读 plate 是安全的：`bodyPass` 只**读** plate、不重新拟合（今晚验过两次）。
 *
 * 用法：`RimwhereKt <png>`
 */
fun main(a: Array<String>) {
  val f = File(a.first { !it.startsWith("--") })
  val img = ImageIO.read(f) ?: return
  val n = img.width
  val px = IntArray(n * n); img.getRGB(0, 0, n, n, px, 0, n)
  val r = MonochromeMask.build(px, n, n)
  val m = r.mask ?: return
  val fld = MonochromeMask::class.java.getDeclaredField("pool")
  fld.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val s = (fld.get(MonochromeMask) as ThreadLocal<Any>).get()
  fun arr(name: String): Any {
    val fl = s.javaClass.getDeclaredField(name); fl.isAccessible = true; return fl.get(s)
  }
  val plate = arr("plate") as ByteArray
  val ref = arr("ref") as IntArray
  val cov = arr("fCov") as IntArray
  val c = n / 2
  val by = HashMap<Int, IntArray>()
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255) continue
    val ink = (m[i] ushr 24) and 0xff
    if (ink < 120) continue
    val y = i / n; val x = i - y * n
    val rad = Math.hypot((x - c).toDouble(), (y - c).toDouble()) / (n / 2.0)
    if (rad < 0.72) continue
    // 键：在底里？× 那一格的 β 落在哪一档
    val b = if (ref[i] >= 0) cov[ref[i]] else -1
    val k = (if (plate[i].toInt() != 0) 1 else 0) * 10 + (b / 64).coerceIn(-1, 4) + 1
    val v = by.getOrPut(k) { IntArray(3) }
    v[0]++; v[1] += ink; v[2] += b.coerceAtLeast(0)
  }
  println("盘沿外圈（半径>0.72）出墨>=120 的实心格，按「在不在底里 × 那一格β」分组：")
  for ((k, v) in by.entries.sortedByDescending { it.value[0] }.take(6)) {
    val mm = v[0].coerceAtLeast(1)
    println("  在底里=%s 那格β档=%d  n=%-5d 出墨均=%-4d β均=%d".format(
      if (k >= 10) "是" else "否", (k % 10) - 1, v[0], v[1] / mm, v[2] / mm))
  }
  // 它们离空气有多远？`PLATE_EDGE_R=6` 那道判据只管得到 6 步以内的格子 ——
  // 盘沿是一条**有几像素宽**的带，外沿那侧离透明远，所以 R=6 收不到它们。
  val air = IntArray(px.size) { if (((px[it] ushr 24) and 0xff) < 64) 0 else 1 shl 20 }
  for (y in 0 until n) for (x in 0 until n) {
    val i = y * n + x
    if (x > 0 && air[i - 1] + 1 < air[i]) air[i] = air[i - 1] + 1
    if (y > 0 && air[i - n] + 1 < air[i]) air[i] = air[i - n] + 1
  }
  for (y in n - 1 downTo 0) for (x in n - 1 downTo 0) {
    val i = y * n + x
    if (x < n - 1 && air[i + 1] + 1 < air[i]) air[i] = air[i + 1] + 1
    if (y < n - 1 && air[i + n] + 1 < air[i]) air[i] = air[i + n] + 1
  }
  val h = IntArray(9)
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255 || plate[i].toInt() != 0) continue
    val ink = (m[i] ushr 24) and 0xff
    if (ink < 120) continue
    val y = i / n; val x = i - y * n
    if (Math.hypot((x - c).toDouble(), (y - c).toDouble()) / (n / 2.0) < 0.72) continue
    h[(air[i] / 4).coerceAtMost(8)]++
  }
  println("  这些底外强墨格到最近空气的距离（4 像素一档）：" +
    (0..8).joinToString(" ") { "${it * 4}${if (it == 8) "+" else "·"}=${h[it]}" })
  // 盘沿还是笔画之间的白缝？缝一定贴着笔画（强墨），盘沿离它远。
  // 这里把"强墨"换成"除自己这一圈之外的墨"，否则它们互相把距离都算成 0。
  val strong2 = IntArray(px.size) { if (((px[it] ushr 24) and 0xff) != 255) 1 shl 20
    else if (((m[it] ushr 24) and 0xff) >= 200 && Math.hypot(
        ((it % n) - c).toDouble(), ((it / n) - c).toDouble()) / (n / 2.0) <= 0.72) 0
    else 1 shl 20 }
  for (y in 0 until n) for (x in 0 until n) {
    val i = y * n + x
    if (x > 0 && strong2[i - 1] + 1 < strong2[i]) strong2[i] = strong2[i - 1] + 1
    if (y > 0 && strong2[i - n] + 1 < strong2[i]) strong2[i] = strong2[i - n] + 1
  }
  for (y in n - 1 downTo 0) for (x in n - 1 downTo 0) {
    val i = y * n + x
    if (x < n - 1 && strong2[i + 1] + 1 < strong2[i]) strong2[i] = strong2[i + 1] + 1
    if (y < n - 1 && strong2[i + n] + 1 < strong2[i]) strong2[i] = strong2[i + n] + 1
  }
  val h2 = IntArray(9)
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255 || plate[i].toInt() != 0) continue
    if (((m[i] ushr 24) and 0xff) < 120) continue
    val y = i / n; val x = i - y * n
    if (Math.hypot((x - c).toDouble(), (y - c).toDouble()) / (n / 2.0) < 0.72) continue
    h2[(strong2[i] / 8).coerceAtMost(8)]++
  }
  println("  它们到**盘心那团真墨**的距离（8 像素一档）：" +
    (0..8).joinToString(" ") { "${it * 8}${if (it == 8) "+" else "·"}=${h2[it]}" })
}
