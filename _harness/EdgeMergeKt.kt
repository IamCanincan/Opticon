package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * 「相邻像素颜色不同要有区分」的**尺**（2026-10-17，主人提的方向；先造尺再动常数）。
 *
 * 他要的东西很具体：两格挨着、颜色不一样，就不许被算成"同一块平色"。
 * 现在这件事由 [MonochromeMask.plateKey] 决定 —— **每通道 24 一档**。
 * 所以"被并掉的相邻边"= 相邻两格 RGB 有差、却落进同一个 24 档。
 *
 * 本台子**不改判据**，只把三件事数清楚：
 *  ① 被并的相邻对占多少（全语料 + 每张一张表）；
 *  ② 这些被并的差值**分布在哪个量级**（Δ≤2 / 3..8 / 9..23）——
 *    这决定"要区分到什么程度"：如果绝大多数是 Δ≤2（抗锯齿/渐变的一格斜坡），
 *    把 24 改成 8 只是把噪声分开、并不会救回任何一张主体；
 *  ③ 换成更细的档（8 / 4）之后，被并率降到多少、**分块数会涨多少**
 *    （块数暴涨 = `flatVote` 那套"一块一票"的票箱变碎，是另一笔代价，看得见才谈改）。
 *
 * ⚠ ②③ 里的"细档"是**假设扫描**，不是生产式子 —— 生产只有 `plateKey` 那一份（24）。
 * 用法：`harness.EdgeMergeKtKt <语料目录>`
 */
private fun key(p: Int, step: Int): Int =
  ((((p shr 16) and 0xff) / step) shl 10) or ((((p shr 8) and 0xff) / step) shl 5) or ((p and 0xff) / step)

private fun delta(a: Int, b: Int): Int {
  val dr = Math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
  val dg = Math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
  val db = Math.abs((a and 0xff) - (b and 0xff))
  return maxOf(dr, dg, db)
}

/** 相邻对被并的张数、总对数、按 Δ 分档、以及"块数"（4 连通同档区域数，粗算）。 */
private class Agg {
  var pairs = 0L
  var merged = 0L
  var d2 = 0L; var d8 = 0L; var d23 = 0L
  var m8 = 0L; var m4 = 0L
  var worst = 0.0; var worstName = ""
  var regions24 = 0L; var regions8 = 0L; var regions4 = 0L
}

private fun regions(px: IntArray, w: Int, h: Int, step: Int): Long {
  // 一格 = 一个"新块"，当且仅当它与自己**左**、**上**两格不同档（4 连通、按档判同）。
  var n = 0L
  for (y in 0 until h) for (x in 0 until w) {
    val k = key(px[y * w + x], step)
    val left = if (x > 0) key(px[y * w + x - 1], step) else -1
    val up = if (y > 0) key(px[(y - 1) * w + x], step) else -1
    if (k != left && k != up) n++
  }
  return n
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "")
  if (!dir.isDirectory) { println("!! 语料目录不存在：$dir"); exitProcess(2) }
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  if (files.isEmpty()) { println("!! $dir 里没有 png"); exitProcess(2) }
  val a = Agg()
  val rows = ArrayList<Pair<String, Double>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h)
    img.getRGB(0, 0, w, h, px, 0, w)
    var pairs = 0L; var merged = 0L; var n2 = 0L; var n8 = 0L; var n23 = 0L
    var m8 = 0L; var m4 = 0L      // 换成 8 档 / 4 档之后还剩多少对被并
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      for (j in intArrayOf(if (x + 1 < w) i + 1 else -1, if (y + 1 < h) i + w else -1)) {
        if (j < 0) continue
        // 只看两格**都真的画了东西**的相邻对：空气↔实物的边不是这一问的对象
        if (Math.abs(((px[i] shr 24) and 0xff) - ((px[j] shr 24) and 0xff)) > 8) continue
        pairs++
        val d = delta(px[i], px[j])
        if (d == 0) continue
        if (MonochromeMask.plateKey(px[i]) == MonochromeMask.plateKey(px[j])) {
          merged++
          if (d <= 2) n2++ else if (d <= 8) n8++ else n23++
          if (key(px[i], 8) == key(px[j], 8)) m8++
          if (key(px[i], 4) == key(px[j], 4)) m4++
        }
      }
    }
    a.pairs += pairs; a.merged += merged; a.d2 += n2; a.d8 += n8; a.d23 += n23
    a.m8 += m8; a.m4 += m4
    val rate = if (pairs > 0) merged.toDouble() / pairs else 0.0
    if (rate > a.worst) { a.worst = rate; a.worstName = f.name.substringBeforeLast('.') }
    if (rate > 0.02) rows += f.name.substringBeforeLast('.') to rate
    a.regions24 += regions(px, w, h, 24); a.regions8 += regions(px, w, h, 8); a.regions4 += regions(px, w, h, 4)
  }
  // ⚠ 百分号一律**先算成变量**再拼：写成 `"${"%.2f".format(x)}%"` 会让 Kotlin 把紧跟的 `%`
  //   当成 String 上的取余运算符，报一串 "unresolved reference 'rem'"（本轮真撞上）。
  val pc = f1(a.merged * 100.0 / a.pairs)
  val s2 = f1(a.d2 * 100.0 / a.merged)
  val s8 = f1(a.d8 * 100.0 / a.merged)
  val s23 = f1(a.d23 * 100.0 / a.merged)
  val r8 = f1(a.regions8 * 1.0 / a.regions24)
  val r4 = f1(a.regions4 * 1.0 / a.regions24)
  println("EM n=${files.size} 相邻对=${a.pairs} 被并=${a.merged}(${pc}%) " +
    "Δ≤2 ${s2}% Δ3-8 ${s8}% Δ9-23 ${s23}% " +
    "被并后再细：8档仍并=${a.m8}(${f1(a.m8 * 100.0 / a.pairs)}%) 4档仍并=${a.m4}(${f1(a.m4 * 100.0 / a.pairs)}%) " +
    "块数 24档=${a.regions24} 8档=${a.regions8}(${r8}x) 4档=${a.regions4}(${r4}x) " +
    "最狠=${f1(a.worst * 100)}%(${a.worstName})")
  rows.sortedByDescending { it.second }.take(12).forEach { (nm, r) -> println("   ${f1(r * 100)}%  $nm") }
}

private fun f1(v: Double): String = "%.1f".format(v)
