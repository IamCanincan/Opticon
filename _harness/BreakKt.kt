package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **同一种源色被"底/墨"这一刀切成了两半**（只量不改）。
 *
 * `MonoKt` 的 `同色起伏` 中位到了 75，`最野` 那一列给出的是颜色而不是原因：
 * 同一个精确色既交出 0 又交出 255，可以是「一部分格子被判成底」，也可以是
 * 「都在墨侧、但第二遍的镂空逐格压」。**这两种病的修法完全相反** ——
 * 前者要动归属（H0/H1/H2/H3、围住者），后者要动那一层的闸。所以现在就把它们分开数。
 *
 * 每个精确色报：`盘` 格数（`plate != 0`）与 `墨` 格数、墨侧 alpha 的 p5/p50/p95、
 * 墨侧 dep 的 min/max。只有**两边都够多**（各 ≥4%）的色才算"被切开"，
 * 否则就是一个色里混了抗锯齿边那种本来就只有一个像素的杂色。
 *
 * 用法：`harness.BreakKt [--min=64] 目录 …`
 */
fun main(args: Array<String>) {
  val minEdge = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 48
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < minEdge || h < minEdge) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = out?.mask ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val dist = fld(s, "dist") as ShortArray
    val off = fld(s, "off") as ShortArray

    class C { var n = 0; var pl = 0; val ink = ArrayList<Int>(); val dep = ArrayList<Int>() }
    val map = HashMap<Int, C>()
    for (i in 0 until w * h) {
      if ((src[i] ushr 24) != 255) continue
      val c = map.getOrPut(src[i] and 0x00FFFFFF) { C() }
      c.n++
      if (plate[i] != 0.toByte()) { c.pl++; continue }
      c.ink += mask[i] ushr 24
      c.dep += dist[i].toInt() and 0xffff
    }
    // 只在**有实体规模**的色里找被切开的
    val tot = map.values.sumOf { it.n }
    val cut = map.entries.filter { it.value.n * 100 >= tot * 4 && it.value.pl * 100 >= it.value.n * 4 &&
      it.value.ink.size * 100 >= it.value.n * 4 }
      .sortedByDescending { minOf(it.value.pl, it.value.ink.size) }
    var pressN = 0; var pressWorst = 0; var pressCol = -1
    for ((c, v) in map) {
      if (v.ink.size < 256) continue
      val a = v.ink.sorted()
      val sp = a[a.size * 95 / 100] - a[a.size * 5 / 100]
      if (sp > 40) { pressN++; if (sp > pressWorst) { pressWorst = sp; pressCol = c } }
    }
    println("%-40s 全色=%5d 切开=%2d 墨内起伏>40=%2d(最野 %d @%06x)  占总数=%d"
      .format(f.name.removeSuffix(".png").take(40), map.size, cut.size, pressN, pressWorst,
        if (pressCol >= 0) pressCol else 0, tot))
    for ((c, v) in cut.take(4)) {
      val a = v.ink.sorted(); val d = v.dep
      println("    %06x  n=%-6d 盘=%-6d(%.0f%%) 墨=%-6d alpha p5/50/95=%d/%d/%d  dep=%d..%d off跨度=%d"
        .format(c, v.n, v.pl, v.pl * 100.0 / v.n, v.ink.size,
          a[a.size * 5 / 100], a[a.size / 2], a[a.size * 95 / 100],
          d.min(), d.max(),
          (off.max().toInt() and 0xffff) - (off.min().toInt() and 0xffff)))
    }
  }
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
