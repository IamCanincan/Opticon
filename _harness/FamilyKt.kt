package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **「对档」为什么在真图上一张都不开火** —— 把漏斗拆到判据之前：装机交出去的那张墨里，
 * 到底有几块"够大的源色族"？
 *
 * 这一台不判好坏，只回答一件事：0 张开火是**判据链断了**，还是**墨侧本来就只有一块东西**。
 * 后者如果成立，丙那条的唯一出路就是去把**盘**也铺成灰 —— 那正是本仓量死过好几轮的"整片交成墨/灰"那一族。
 *
 * 用法：`harness.FamilyKt <目录> [--edge=384] [--min=2]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val minPct = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("="))?.toIntOrNull() ?: 2
  val dir = File(args.first { !it.startsWith("--") })
  val plate = HashMap<String, Boolean>(); val underOf = HashMap<String, String>(); val adapted = HashSet<String>()
  File(dir, "index.tsv").let { ix -> if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 9) {
      plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
      if (c[8] == "有") adapted.add(c[2])
      if (c.size >= 19 && c[18] != "-" && c[18] != "null") underOf[c[2]] = c[18]
    } } }
  var one = 0; var two = 0; var three = 0; var none = 0; var refSide = 0
  val rows = ArrayList<Pair<String, Int>>()
  for (f in dir.listFiles { x -> x.extension.equals("png", true) && !x.name.endsWith("_modelbg.png") &&
      !x.name.endsWith("_bg.png") && !x.name.endsWith("_fg.png") && !x.name.contains("preview") &&
      !adapted.contains(x.name) }!!.sortedBy { it.name }) {
    val px = read(f, edge) ?: continue
    val u = underOf[f.name]?.let { read(File(dir, it), edge) }
    val r = MonochromeMask.build(px, edge, edge, plate[f.name] == true, u)
    if (r.report.contains("调=1")) continue            // 只看走参照那一支的（对档只管这一半）
    refSide++
    val out = r.mask ?: continue
    val b = edge * 3 / 4; val o = (edge - b) / 2
    val area = HashMap<Int, Int>()
    for (y in o until o + b) for (x in o until o + b) {
      val i = y * edge + x
      if (((out[i] ushr 24) and 0xff) < 128) continue
      if (((px[i] ushr 24) and 0xff) < 250) continue
      val k = ((((px[i] shr 16) and 0xff) / 24) shl 10) or ((((px[i] shr 8) and 0xff) / 24) shl 5) or ((px[i] and 0xff) / 24)
      area[k] = (area[k] ?: 0) + 1
    }
    val big = area.filter { it.value.toLong() * 100 >= b.toLong() * b * minPct }.size
    rows.add((f.nameWithoutExtension.split('_').getOrNull(if (f.name.startsWith("icon")) 1 else 0)
      ?: f.nameWithoutExtension).take(30) to big)
    when { big == 0 -> none++; big == 1 -> one++; big == 2 -> two++; else -> three++ }
  }
  println("走参照那一支的 ${refSide} 张里，墨侧「够大的源色族」（各 ≥${minPct}% 内切框）：")
  println("   0 块 $none   1 块 $one   2 块 $two   ≥3 块 $three")
  for (r in rows.sortedByDescending { it.second }.take(10)) println("   ${r.second} 块  ${r.first}")
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
