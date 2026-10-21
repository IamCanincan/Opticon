package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * **出口 B 的前置检查：「只留最大块 + 填洞」这一刀，输入到底存不存在**（主人 2026-10-18「继续」）。
 *
 * 上一轮出口 A 翻车的教训写进了记忆：**探针必须和生产用同一个原语**。所以这里的"底"
 * 不再自己按颜色算 —— 直接**反射读生产那块 `s.plate`**（`PlateDump.kt` 同一套反射，只读、不改生产一行）。
 *
 * 三个版本并排量（同一份交付掩码）：
 *   V0 = 现在屏上那张（守卫退回 = 不切）
 *   V1 = 把 `plate` 的格交 0（= 切背景，被守卫判碎的那一版）
 *   V2 = V1 只留**面积 ≥ 墨的 X%** 的那些块，其余丢掉；再把**被留下的块围住的洞**按 V0 的浓度补回来
 * 报：墨占画布 / cc / top / 留下的最大块占 V0 的墨比（= "主体还剩多少"）。
 *
 * 用法：`sh run.sh harness.KeepBigKt 图1.png [图2 …] [--min=2] [--out=目录]`
 */
object KeepBigKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val fld = MonochromeMask::class.java.getDeclaredField("pool"); fld.isAccessible = true
    val pool = fld.get(MonochromeMask) as ThreadLocal<*>
    val minPct = args.firstOrNull { it.startsWith("--min=") }?.substring(6)?.toDoubleOrNull() ?: 2.0
    val outDir = args.firstOrNull { it.startsWith("--out=") }?.substring(6)?.let { File(it) }
    outDir?.mkdirs()
    println(String.format(Locale.ROOT, "%-44s %-12s %6s %5s %5s %7s", "name", "版本", "墨%", "cc", "top", "留主体%"))
    for (path in args.filter { !it.startsWith("--") }) {
      val f = File(path); val img = ImageIO.read(f) ?: continue
      val w = img.width; val h = img.height; val n = w * h
      val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
      val v0 = MonochromeMask.build(px, w, h).mask ?: continue
      val plate = (fieldOf(pool.get()!!, "plate") as ByteArray).copyOf()
      val v1 = v0.copyOf(); for (i in 0 until n) if (plate[i] != 0.toByte()) v1[i] = 0
      val ink0 = count(v0, n); val ink1 = count(v1, n)
      val (cc0, top0) = shape(v0, w, h, n); val (cc1, top1) = shape(v1, w, h, n)
      // V2：丢掉小块的墨，再补洞
      val v2 = dropSmall(v1, w, h, n, ink1 * minPct / 100.0)
      val filled = fillHoles(v2, v0, w, h, n)
      val (cc2, top2) = shape(v2, w, h, n)
      val keptBig = count(v2, n)
      // **渣率** = V1 的墨里，落在"占墨 <1% 的小块"中的那部分。
      //   假设（从两张图上来的，不是拍的）：云闪付切完剩的是**字**，一块 4~19%，块多但不小；
      //   王者荣耀切完剩的是**渣**，最大块 54%，其余 46% 散在 58 块里（平均 0.8%）。
      val dust = dustInk(v1, w, h, n, ink1 * 0.01)
      // 「留下的墨实不实」α≥200 那一列**已证不成立**（云闪付 58.8 < 王者荣耀 62.4，排序反的），
      //   留着只为别再有人试它。
      val solid1 = solidCount(v1, n)
      println(String.format(Locale.ROOT, "%-44s %-12s %5.1f%% %5d %5.2f %6.1f%%  渣率 %.1f%%  实心 %.1f%%%n",
        f.nameWithoutExtension, "V0 现在", ink0 * 100.0 / n, cc0, top0, 100.0,
        dust * 100.0 / maxOf(ink1, 1), solid1 * 100.0 / maxOf(ink1, 1)))
      println(String.format(Locale.ROOT, "%-44s %-12s %5.1f%% %5d %5.2f %6.1f%%",
        "", "V1 切底", ink1 * 100.0 / n, cc1, top1, ink1 * 100.0 / maxOf(ink0, 1)))
      println(String.format(Locale.ROOT, "%-44s %-12s %5.1f%% %5d %5.2f %6.1f%%  (min=%.1f%% 补 %d 格)%n",
        "", "V2 留大块", keptBig * 100.0 / n, cc2, top2, keptBig * 100.0 / maxOf(ink0, 1), minPct, filled))
      if (outDir != null) for ((tag, m) in listOf("V0" to v0, "V1" to v1, "V2" to v2)) {
        val o = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (i in 0 until n) { val g = 255 - (m[i] ushr 24); o.setRGB(i % w, i / w, (g shl 16) or (g shl 8) or g) }
        ImageIO.write(o, "png", File(outDir, "${f.nameWithoutExtension}_$tag.png"))
      }
    }
  }

  private fun fieldOf(o: Any, name: String): Any {
    var c: Class<*>? = o.javaClass
    while (c != null) {
      runCatching { val x = c.getDeclaredField(name); x.isAccessible = true; return x.get(o) }
      c = c.superclass
    }
    throw NoSuchFieldException(name)
  }

  private fun count(m: IntArray, n: Int): Int { var c = 0; for (i in 0 until n) if ((m[i] ushr 24) > 0) c++; return c }

  private fun solidCount(m: IntArray, n: Int): Int {
    var c = 0; for (i in 0 until n) if ((m[i] ushr 24) >= 200) c++; return c
  }

  private fun shape(m: IntArray, w: Int, h: Int, n: Int): Pair<Int, Double> {
    val seen = BooleanArray(n); val q = IntArray(n)
    var cc = 0; var biggest = 0
    for (s in 0 until n) {
      if ((m[s] ushr 24) == 0 || seen[s]) continue
      cc++; var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
      while (head < tail) {
        val c = q[head++]
        val cy = c / w; val cx = c - cy * w
        fun push(j: Int) { if ((m[j] ushr 24) > 0 && !seen[j]) { seen[j] = true; q[tail++] = j } }
        if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
      }
      if (tail > biggest) biggest = tail
    }
    val ink = count(m, n)
    return Pair(cc, if (ink == 0) 0.0 else biggest.toDouble() / ink)
  }

  /** 渣率：面积 < `minArea` 的那些块，一共占了多少墨。 */
  private fun dustInk(m: IntArray, w: Int, h: Int, n: Int, minArea: Double): Int {
    val seen = BooleanArray(n); val q = IntArray(n); var dust = 0
    for (s in 0 until n) {
      if ((m[s] ushr 24) == 0 || seen[s]) continue
      var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
      while (head < tail) {
        val c = q[head++]
        val cy = c / w; val cx = c - cy * w
        fun push(j: Int) { if ((m[j] ushr 24) > 0 && !seen[j]) { seen[j] = true; q[tail++] = j } }
        if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
      }
      if (tail < minArea) dust += tail
    }
    return dust
  }

  /** 把面积 < `minArea` 的块的墨丢掉（4 连通成分，逐块判）。 */
  private fun dropSmall(m: IntArray, w: Int, h: Int, n: Int, minArea: Double): IntArray {
    val out = m.copyOf(); val seen = BooleanArray(n); val q = IntArray(n); val comp = ArrayList<Int>()
    for (s in 0 until n) {
      if ((m[s] ushr 24) == 0 || seen[s]) continue
      comp.clear(); var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
      while (head < tail) {
        val c = q[head++]; comp.add(c)
        val cy = c / w; val cx = c - cy * w
        fun push(j: Int) { if ((m[j] ushr 24) > 0 && !seen[j]) { seen[j] = true; q[tail++] = j } }
        if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
      }
      if (comp.size < minArea) for (c in comp) out[c] = 0
    }
    return out
  }

  /** 洞 = 从画布边漫不到的空格；按 V0（未切）的浓度补回来。返回补了几格。 */
  private fun fillHoles(m: IntArray, base: IntArray, w: Int, h: Int, n: Int): Int {
    val reach = BooleanArray(n); val q = IntArray(n); var head = 0; var tail = 0
    fun push(i: Int) { if ((m[i] ushr 24) == 0 && !reach[i]) { reach[i] = true; q[tail++] = i } }
    for (x in 0 until w) { push(x); push((h - 1) * w + x) }
    for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
    while (head < tail) {
      val c = q[head++]; val cy = c / w; val cx = c - cy * w
      if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
      if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
    }
    var filled = 0
    for (i in 0 until n) if ((m[i] ushr 24) == 0 && !reach[i] && (base[i] ushr 24) > 0) { m[i] = base[i]; filled++ }
    return filled
  }
}
