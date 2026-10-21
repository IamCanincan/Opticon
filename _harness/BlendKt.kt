package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **这把灰是"混合像素"还是"第三种真颜色"？** —— 主人报「字符外圈有原本不存在的灰」，
 * 逐格量到那圈是 `#4989F9` = `#3079F8`(底蓝) 掺 10% 白(字)。也就是：**它是图上已有的两种色混出来的**。
 *
 * 这一台就只问这一件事，因为这两种病的修法相反：
 *  · 混合像素 = 边缘，本来不该自己成墨 → 可以清；
 *  · 第三种真颜色 = 源里真画了个东西（易班那只熊、知到那个"到"字）→ 动了就是又擦内容。
 *
 * 口径刻意**不新造颜色阈值**：族 = 源色按 4bit/通道量化后占面积前几名；
 *  "这格属于哪一族"用现成的 [SEP_TOL]；族自己是底还是墨，**问模型交出去的浓度**（均α<60=底、≥150=墨），
 *  不问我觉得它该是什么。
 *
 * 用法：`harness.BlendKtKt <目录…> [--edge=384] [--top=N] [--why=包名]`
 */
private fun load(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f) ?: return IntArray(0)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

private fun rgb(p: Int) = intArrayOf((p ushr 16) and 0xff, (p ushr 8) and 0xff, p and 0xff)

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toIntOrNull() ?: 384
  val top = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toIntOrNull() ?: 12
  val why = args.firstOrNull { it.startsWith("--why=") }?.substringAfter("=")
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u") }
  val tol = 26
  val rows = ArrayList<Pair<String, IntArray>>()
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    for (f in dir.listFiles { x -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
      val px = load(f, edge); if (px.isEmpty()) continue
      val out = MonochromeMask.build(px, edge, edge).mask ?: continue
      // 1) 族：实心格里按 4bit/通道量化取面积前 5 名，各算自己的均色与均α
      val cnt = HashMap<Int, Int>()
      for (p in px) {
        if (((p ushr 24) and 0xff) < 255) continue
        val k = ((p ushr 20) and 0xf) * 256 + ((p ushr 12) and 0xf) * 16 + ((p ushr 4) and 0xf)
        cnt[k] = (cnt[k] ?: 0) + 1
      }
      val fams = cnt.entries.sortedByDescending { it.value }.take(5).map { e ->
        val r = ((e.key shr 8) and 0xf) * 17; val g = ((e.key shr 4) and 0xf) * 17; val b = (e.key and 0xf) * 17
        var n = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var sa = 0L
        for (i in px.indices) {
          val p = px[i]
          if (((p ushr 24) and 0xff) < 255) continue
          if (maxOf(abs((p ushr 16) and 0xff - r), abs((p ushr 8) and 0xff - g), abs(p and 0xff - b)) > tol) continue
          n++; sr += (p ushr 16) and 0xff; sg += (p ushr 8) and 0xff; sb += p and 0xff; sa += (out[i] ushr 24) and 0xff
        }
        if (n == 0L) null else intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt(),
          (sa / n).toInt(), n.toInt())
      }.filterNotNull()
      val plate = fams.filter { it[3] < 60 }
      val ink = fams.filter { it[3] >= 150 }
      // 2) 外圈灰：交付 8..120、源实心、且**不属于任何一族**（离每族都 > tol）
      var grey = 0; var blend = 0; var third = 0
      val ex = ArrayList<String>()
      for (i in px.indices) {
        val p = px[i]
        if (((p ushr 24) and 0xff) < 255) continue
        val a = (out[i] ushr 24) and 0xff
        if (a < 8 || a > 120) continue
        val c = rgb(p)
        if (fams.any { maxOf(abs(c[0] - it[0]), abs(c[1] - it[1]), abs(c[2] - it[2])) <= tol }) continue
        grey++
        var onSeg = false
        for (u in plate) for (v in ink) {
          var best = 1 shl 30
          for (s in 0..20) {
            val e = maxOf(abs(c[0] - (u[0] + (v[0] - u[0]) * s / 20)),
              abs(c[1] - (u[1] + (v[1] - u[1]) * s / 20)), abs(c[2] - (u[2] + (v[2] - u[2]) * s / 20)))
            if (e < best) best = e
          }
          if (best <= tol) onSeg = true
        }
        if (onSeg) blend++ else {
          third++
          if (ex.size < 4) ex.add("#%02X%02X%02X α=%d".format(c[0], c[1], c[2], a))
        }
      }
      val nm = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
      if (why != null && nm.contains(why)) {
        println("  ${nm}：底族 ${plate.size} 个（均α ${plate.joinToString("/") { it[3].toString() }}）" +
          " 墨族 ${ink.size} 个（均α ${ink.joinToString("/") { it[3].toString() }}）")
        println("    不属于任何族的灰 $grey 格 = 混合 $blend + 第三种颜色 $third")
        println("    未解释的样本： ${ex.joinToString("  ")}")
      }
      rows.add(nm to intArrayOf(grey, blend, third))
    }
  }
  rows.sortByDescending { it.second[0] }
  println("灰=混合/第三种   共 ${rows.size} 张")
  for (r in rows.take(top)) if (r.second[0] > 60) println("  %-40s 灰%6d  混合%5d (%3d%%)  第三种%5d".format(
    r.first.take(40), r.second[0], r.second[1],
    if (r.second[0] == 0) 0 else r.second[1] * 100 / r.second[0], r.second[2]))
  val g = rows.sumOf { it.second[0] }; val b = rows.sumOf { it.second[1] }; val t = rows.sumOf { it.second[2] }
  println("  合计：灰 $g 格，混合解释 $b (${b * 100 / maxOf(1, g)}%)，第三种颜色 $t")
}

private fun abs(x: Int) = if (x < 0) -x else x
