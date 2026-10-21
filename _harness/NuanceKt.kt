package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **丢了多少层次**：只数"源色真的看得出差别、交付出去却并成一片"的那一对对。
 *
 * 为什么不用 `分档`：那一列按 Lab 桶（L 每 8、a/b 每 32）数"几块"，**相邻桶可以肉眼是同一个色**
 * （ΔE 只有 8~15），把它们并到同一档不是缺陷。判据台那条「相邻部分必须有差别」因此会虚报：
 * 它数的是桶的差别，不是眼睛的差别。
 *
 * 这里的口径：两块**源色的 ΔE ≥ [VISIBLE_DE]** 才算"设计者画了两个色"；它们在交付掩码里
 * 的平均浓度差 < [MERGED_GAP] 才算"这一对层次被丢了"。
 *
 * 用法：`NuanceKt [--edge=N] 目录 …`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
      .sortedBy { it.name } else listOf(f)
  }
  var pairs = 0; var lost = 0; var icons = 0; var worst = ""
  var worstN = 0
  println("%-46s %6s %6s %s".format("图", "看得出", "丢了", "丢的那几对的源ΔE→交付差"))
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val sw = img.width; val sh = img.height
    val src = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, src, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = src[sy * sw + sx]
    }
    val out = MonochromeMask.build(px, edge, edge)
    val mask = out.mask ?: continue
    // 源色分桶（与模型同一套边长），只留占墨 ≥3% 的前 6 块
    val key = HashMap<Int, ArrayList<Int>>()
    var ink = 0
    for (i in px.indices) {
      if (((px[i] ushr 24) and 0xff) < 128) continue
      ink++
      val l = labOf(px[i])
      val k = ((l[0].toInt() / 8) * 64 + ((l[1] + 128).toInt() / 32) * 8 + ((l[2] + 128).toInt() / 32))
      key.getOrPut(k) { ArrayList() }.add(i)
    }
    if (ink < 256) continue
    val parts = key.entries.filter { it.value.size * 100 >= ink * 3 }
      .sortedByDescending { it.value.size }.take(6)
    // 只比"真的上了墨"的那些块：底盘两块都被正确擦成 0，它们之间的 ΔE 差别不算"丢层次"
    val rendered = parts.filter { e ->
      var s2 = 0L
      for (i in e.value) s2 += (mask[i] ushr 24) and 0xff
      s2 * 4 >= e.value.size * 32L            // 平均浓度 ≥ 32/255
    }
    if (rendered.size < 2) continue
    val rec = rendered.map { e ->
      var lr = 0.0; var la = 0.0; var lb = 0.0; var al = 0.0
      for (i in e.value) { val l = labOf(px[i]); lr += l[0]; la += l[1]; lb += l[2]; al += (mask[i] ushr 24) and 0xff }
      Triple(doubleArrayOf(lr / e.value.size, la / e.value.size, lb / e.value.size),
        al / e.value.size, e.value.size)
    }.sortedBy { it.second }
    val lostPairs = ArrayList<String>()
    for (j in 1 until rec.size) {
      val de = de(rec[j - 1].first, rec[j].first)
      if (de < VISIBLE_DE) continue
      pairs++
      val gap = rec[j].second - rec[j - 1].second
      if (gap < MERGED_GAP) { lost++; lostPairs.add("%.0f→%.0f".format(de, gap)) }
    }
    if (rec.size >= 2) {
      icons++
      if (lostPairs.size > worstN) { worstN = lostPairs.size; worst = f.nameWithoutExtension }
    }
    if (lostPairs.isNotEmpty())
      println("%-46s %6d %6d  %s".format(f.nameWithoutExtension.take(46), pairs, lost, lostPairs.joinToString(" ")))
  }
  println("=== 看得出差别的部分对 $pairs 对，其中交付后被并掉的 $lost 对（$icons 张图参与统计）；最狠的一张 $worst 丢 $worstN 对")
}

/** 源色"看得出是两个色"的下限（ΔE）。15 ≈ 同一色系里肉眼分得出深浅的一步。 */
private const val VISIBLE_DE = 15

/** 交付后差多少才算"还分得开"（0..255 的浓度单位）。与判据台那条 25 同口径。 */
private const val MERGED_GAP = 25

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

private fun de(a: DoubleArray, b: DoubleArray): Double {
  val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
  return sqrt(dl * dl + da * da + db * db)
}
