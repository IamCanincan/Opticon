package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **主调定线台（只量，不改模型）** —— 主人 2026-10-08 定了口径「非适配图标全走 feimo 那套广色度」，
 * 而广色度要逐张判极性（暗=墨 / 亮=墨），判极性就得先知道**哪一块是这张图的主调**。
 *
 * 那三轮留下的尸体，这台子一次都不许重犯：
 *  · **精确色计数**会被渐变盘骗（每格平均色都不同 → 主调落到中间色 → 全图"离主调很远"）；
 *  · **亮度众数**丢色度（两个离盘一样亮、颜色不同的物体被涂成同一浓度），而且它把混元那种
 *    两半都要的图只留下一半（`全开+V` 实测：混元退回「只有半个太极」）；
 *  · **外圈均值**会把满幅插画误判（feimo +7）。
 * 记录留的活路是**在 Lab 上真聚类**。所以这里数的是簇，不是第 N 个阈值。
 *
 * 网格与 `tonalClass` 同一套（内容框上固定 96×96 格、每格取平均色）——
 * **换边长不许换人**，这是这条线上第一次定入场判据学到的规矩。
 *
 * 跑法：`java -cp … harness.PlateKtKt <目录> [目录…]`
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }
  if (dirs.isEmpty()) { println("用法：PlateKtKt <目录> [目录…]"); return }
  val k = (args.firstOrNull { it.startsWith("--k=") }?.substringAfter("=")?.toInt()) ?: 3
  println("主调 = Lab 上 k=$k 聚类（确定性 farthest-point 初始化）里**占格最多**的那一簇")
  println("%-42s %-30s %-14s %s".format("图标", "主调簇 L/a/b", "它占格%", "其余簇按 L 排序（谁是亮侧）"))
  for (d in dirs) {
    val dir = File(d); val fs = dir.listFiles { f: File -> f.extension == "png" &&
        !f.name.contains("preview") && !f.name.contains("_mono") } ?: continue
    for (f in fs.sortedBy { it.name }) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val w = im.width; val h = im.height
      val px = IntArray(w * h).also { im.getRGB(0, 0, w, h, it, 0, w) }
      // 内容框（α≥128），与模型同口径
      var l = w; var t = h; var r = -1; var b = -1
      for (y in 0 until h) for (x in 0 until w) if (((px[y * w + x] ushr 24) and 0xff) >= 128) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
      }
      if (r < 0) { println("%-42s 整张透明".format(f.nameWithoutExtension.take(42))); continue }
      val n = 96
      val cl = ArrayList<IntArray>(); val cells = ArrayList<IntArray>()
      val acc = HashMap<Triple<Int, Int, Int>, Int>()
      for (cy in 0 until n) for (cx in 0 until n) {
        val x0 = l + (r - l + 1) * cx / n; val x1 = l + (r - l + 1) * (cx + 1) / n
        val y0 = t + (b - t + 1) * cy / n; val y1 = t + (b - t + 1) * (cy + 1) / n
        var sr = 0L; var sg = 0L; var sb = 0L; var m = 0
        for (y in y0 until maxOf(y1, y0 + 1)) for (x in x0 until maxOf(x1, x0 + 1)) {
          val p = px[y * w + x]
          if (((p ushr 24) and 0xff) < 128) continue
          m++; sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
        }
        if (m == 0) continue
        val c = (intArrayOf((sr / m).toInt(), (sg / m).toInt(), (sb / m).toInt()))
        cells.add(intArrayOf(c[0], c[1], c[2], if (cy == 0 || cx == 0 || cy == n - 1 || cx == n - 1) 1 else 0))
        acc[Triple(c[0], c[1], c[2])] = (acc[Triple(c[0], c[1], c[2])] ?: 0) + 1
      }
      if (cells.size < k) { println("%-42s 只有 %d 格".format(f.nameWithoutExtension.take(42), cells.size)); continue }
      val lab = cells.map { Triple(MonochromeMask.labChannel(it[0] shl 16 or (it[1] shl 8) or it[2], 0),
                                   MonochromeMask.labChannel(it[0] shl 16 or (it[1] shl 8) or it[2], 1),
                                   MonochromeMask.labChannel(it[0] shl 16 or (it[1] shl 8) or it[2], 2)) }
      // 确定性初始化：平均色 → 离它最远 → 离这两者都最远
      val mean = Triple((lab.sumOf { it.first } / lab.size), (lab.sumOf { it.second } / lab.size), (lab.sumOf { it.third } / lab.size))
      val d2 = fun(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>) =
        (a.first - b.first).let { it * it } + (a.second - b.second).let { it * it } + (a.third - b.third).let { it * it }
      var c0 = lab[0]; var c1 = lab[0]; var c2 = lab[0]
      for (p in lab) if (d2(p, mean) > d2(c0, mean)) c0 = p
      for (p in lab) if (d2(p, c0) > d2(c1, c0)) c1 = p
      for (p in lab) if (minOf(d2(p, c0), d2(p, c1)) > minOf(d2(c2, c0), d2(c2, c1))) c2 = p
      var cen = ArrayList(listOf(c0, c1, c2))
      repeat(12) {
        val sum = Array(k) { IntArray(4) }   // L,a,b,格数
        for (i in lab.indices) {
          var bi = 0; var bd = Int.MAX_VALUE
          for (j in 0 until k) { if (d2(lab[i], cen[j]) < bd) { bd = d2(lab[i], cen[j]); bi = j } }
          sum[bi][0] += lab[i].first; sum[bi][1] += lab[i].second; sum[bi][2] += lab[i].third; sum[bi][3]++
        }
        cen = ArrayList((0 until k).map { j -> if (sum[j][3] == 0) cen[j]
          else Triple(sum[j][0] / sum[j][3], sum[j][1] / sum[j][3], sum[j][2] / sum[j][3]) })
      }
      // 每簇：格数、平均 Lab、触边格占比
      val stat = Array(k) { IntArray(3) }   // 格数, 触边格, 累加L
      for (i in lab.indices) {
        var bi = 0; var bd = Int.MAX_VALUE
        for (j in 0 until k) { if (d2(lab[i], cen[j]) < bd) { bd = d2(lab[i], cen[j]); bi = j } }
        stat[bi][0]++; stat[bi][1] += cells[i][3]; stat[bi][2] += lab[i].first
      }
      val tot = lab.size
      val plate = stat.indices.maxByOrNull { stat[it][0] } ?: 0
      val others = stat.indices.filter { it != plate }.sortedByDescending { stat[it][2] / maxOf(stat[it][0], 1) }
      val pL = cen[plate].first
      val desc = others.joinToString(" ") {
        val mL = stat[it][2] / maxOf(stat[it][0], 1)
        "${if (mL > pL) "亮" else "暗"}${mL / 64}(占${stat[it][0] * 100 / tot}%·触边${stat[it][1] * 100 / maxOf(stat[it][0], 1)}%)"
      }
      println("%-42s L%d/a%d/b%-4d %5.1f%% 触边%3d%%  %s".format(
        f.nameWithoutExtension.take(42), cen[plate].first / 64, cen[plate].second / 64 - 128,
        cen[plate].third / 64 - 128, stat[plate][0] * 100.0 / tot,
        stat[plate][1] * 100 / maxOf(stat[plate][0], 1), desc))
    }
  }
}
