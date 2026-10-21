package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **把"灰"分成两段量** —— 现有那把尺（`FilmAreaKt` 的 `淡膜`）只数 α 1..127，
 * 而 α 128..254 那一段同样是"看得见的灰"，只是它比盘亮一点而已。
 *
 * 为什么加这台（2026-10-11）：主人让查 `com.yiban.app`。图上那只熊是**一片中灰**，
 * 可尺子报 `淡=0.02`、判"达标" —— 因为交出去的 30236 格里 α 1..127 只有 3636 格，
 * **其余 30235 格全在 128..254**（`分档=6(100,128,165,195,225,255)`）。
 * 也就是说：**"38/62 达标"这个数可能只是量程的假象。** 这一台把两段分开报，
 * 并且**先拿设计师自己画的那批当对照** —— 线该画在哪，看他们的分布，不看我们的直觉。
 *
 * 用法：`Band2Kt [--model] 目录 [--edge=384]`（与 `FilmAreaKt` 同一口径：内切方框、同一分母）
 */
fun main(args: Array<String>) {
  val model = args.contains("--model")
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val sfx = args.firstOrNull { it.startsWith("--suffix=") }?.substringAfter("=")
  // ⚠ 这一把尺**天生要选窗口**（"哪一段 α 算一片灰"）。所以判它之前先问：名单随窗口漂不漂？
  //   `--win=lo..hi` 就是为了把这件事摊开，而不是偷偷钉一个 64..200。
  val win = (args.firstOrNull { it.startsWith("--win=") }?.substringAfter("=")) ?: "64..200"
  val winLo = win.substringBefore("..").toInt(); val winHi = win.substringAfter("..").toInt()
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_audit3")
  val ix = File(dir, "index.tsv")
  val plate = HashMap<String, Boolean>(); val adapted = HashSet<String>(); val listed = HashSet<String>()
  // `--input=model`：吃**装机那一张**（`_model.png` + `_modelbg.png`，抓取通道按 `MonochromeSynth`
  //  的分支逐句存的那两张），而不是合成图 + 可见区单层。合成图对 34 张"只给前景层"的图标是
  //  模型从没见过的输入，而可见区 `_bg.png` 与模型输入差 1.5 倍构图 —— 拿它当 `under` 喂 `盘刀`
  //  比的是错位的两张。这一轮之前所有 `--model` 的账都带着这两位错。
  val modelInput = args.contains("--input=model")
  val underOf = HashMap<String, File>()
  if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 9) {
      listed.add(c[2]); if (c[8] == "有") adapted.add(c[2])
      plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
      if (c.size >= 19 && modelInput) {
        val mf = if (c[17] == "same") c[2] else c[17]
        if (File(dir, mf).isFile && !adapted.contains(c[2])) {
          plate[mf] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
          underOf[mf] = if (c[18] == "-" || c[18] == "null" || c[18] == "err") File("") else File(dir, c[18])
        }
      }
    }
  }
  val rows = ArrayList<Triple<String, Double, Double>>()
  val tones = ArrayList<Int>(); val tops = ArrayList<Double>()
  // ⚠ 上面那把"最大单档（非实心）"当场被自己的正对照判死：feimo（金标准）a38/16%、
  //   美团 a252/11%、QQ a225/13% 全挤进前四 —— 它把**接近实心**（225..253，"墨差一点满"）
  //   和**很淡的膜/天空**（α29..38）都当成"一片灰"。所以再来一把：**只数中间那一段**（α 64..200）。
  val mids = ArrayList<Double>(); val midAt = ArrayList<Int>()
  val files: List<File> = if (modelInput) underOf.keys.map { File(dir, it) }.sortedBy { it.name }
  else dir.listFiles { x -> x.extension.equals("png", true) && !x.name.endsWith("_bg.png") &&
      !x.name.endsWith("_fg.png") && !x.name.contains("preview") &&
      !x.name.endsWith("_model.png") && !x.name.endsWith("_modelbg.png") &&
      (sfx == null || x.name.contains(sfx)) &&
      (sfx != null || listed.isEmpty() || (listed.contains(x.name) && !adapted.contains(x.name))) }!!
      .sortedBy { it.name }
  for (f in files) {
    val px = read(f, edge) ?: continue
    val ink = if (model) {
      val sib = underOf[f.name] ?: File(dir, f.nameWithoutExtension + "_bg.png")
      val under = if (sib.isFile) read(sib, edge) else null
      MonochromeMask.build(px, edge, edge, plate[f.name] == true, under).mask ?: px
    } else px
    val b = edge * 3 / 4; val o = (edge - b) / 2
    var lo = 0; var mid = 0
    val hist = IntArray(256)
    for (y in o until o + b) for (x in o until o + b) {
      val a = (ink[y * edge + x] ushr 24) and 0xff
      hist[a]++
      if (a in 1..127) lo++ else if (a in 128..254) mid++
    }
    val cells = (b.toLong() * b).toDouble()
    // 分档口径与 PickKt 一致：占框 ≥0.5% 的那一档才算"交出去的一档"。
    val tn = (1..255).count { hist[it] * 200L >= cells.toLong() }
    var topTone = 0.0; var topAt = 0
    for (v in 1..254) if (hist[v] * 100.0 / cells > topTone) { topTone = hist[v] * 100.0 / cells; topAt = v }
    tones.add(tn); tops.add(topTone)
    var topMid = 0.0; var midAt2 = 0
    for (v in winLo..winHi) if (hist[v] * 100.0 / cells > topMid) { topMid = hist[v] * 100.0 / cells; midAt2 = v }
    mids.add(topMid); midAt.add(midAt2)
    rows.add(Triple(f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "")
      .replace(Regex("_.*$"), "").take(38) + " 档$tn/单档${"%.0f".format(topTone)}%a$topAt",
      lo * 100 / cells, mid * 100 / cells))
  }
  val l = rows.map { it.second }.sorted(); val m = rows.map { it.third }.sorted()
  val u = rows.map { it.second + it.third }.sorted()
  fun q(v: List<Double>, p: Double) = v[minOf(v.size - 1, (v.size * p).toInt())]
  println("${if (model) "我们交出去的" else "设计师自己画的"}  ${dir.name}  ${rows.size} 张")
  println("   α 1..127   中位 ${"%.1f".format(q(l, 0.5))}%  p90 ${"%.1f".format(q(l, 0.9))}%  最大 ${"%.1f".format(l.last())}%")
  println("   α 128..254 中位 ${"%.1f".format(q(m, 0.5))}%  p90 ${"%.1f".format(q(m, 0.9))}%  最大 ${"%.1f".format(m.last())}%")
  println("   α 1..254   中位 ${"%.1f".format(q(u, 0.5))}%  p90 ${"%.1f".format(q(u, 0.9))}%  最大 ${"%.1f".format(u.last())}%")
  println("   两段都 ≤3.5% 的：${rows.count { it.second <= 3.5 && it.third <= 3.5 }} / ${rows.size}")
  println("   合段 ≤${"%.1f".format(q(u, 0.9))}%（本批 p90）的：${rows.count { it.second + it.third <= q(u, 0.9) }} / ${rows.size}")
  for (line in listOf(2.0, 3.5, 5.0, 7.0, 10.0))
    println("   合段 ≤${line}% 的：${rows.count { it.second + it.third <= line }} / ${rows.size}")
  val ts = tones.map { it.toDouble() }.sorted(); val ps = tops.sorted()
  if (rows.isNotEmpty()) println("   交出去的档数 中位 ${q(ts, 0.5).toInt()} p90 ${q(ts, 0.9).toInt()} 最多 ${ts.last().toInt()} ｜ " +
    "最大单档（非实心那一档）占框 中位 ${"%.1f".format(q(ps, 0.5))}% p90 ${"%.1f".format(q(ps, 0.9))}% 最狠 ${"%.1f".format(ps.last())}%")
  // **一片"看得见的灰"到底该多大**：设计师那批的非实心档只承担抗锯齿（中位 0.1%），
  // 而我们 p90 已经有 12.6% 的一整块。这条比"灰区总面积"更贴眼睛，先把它变成数。
  for (line in listOf(0.5, 1.0, 2.0, 3.0, 5.0))
    println("   最大单档 ≤${line}% 的：${tops.count { it <= line }} / ${tops.size}")
  val ms = mids.sorted()
  if (rows.isNotEmpty()) println("   **最大单档（只数 α $winLo..$winHi 那一档）** 中位 ${"%.1f".format(q(ms, 0.5))}% " +
    "p90 ${"%.1f".format(q(ms, 0.9))}% 最狠 ${"%.1f".format(ms.last())}% ｜ " +
    listOf(1.0, 2.0, 3.0, 5.0).joinToString("  ") { "≤$it}% ${mids.count { v -> v <= it }}/${mids.size}" })
  println("   按 α $winLo..$winHi 那一段最厚的一档降序 12 张（这才是'一片灰'）：")
  for (i in rows.indices.sortedByDescending { mids[it] }.take(12))
    println("      中段单档 %5.1f%% @a%-3d 档%-3d 总灰 %5.1f%%  %s"
      .format(mids[i], midAt[i], tones[i], rows[i].second + rows[i].third, rows[i].first))
  // 主人点名的那几张要用**这张表**逐张读，不是读中位数（`--show=子串1,子串2`）。
  val show = (args.firstOrNull { it.startsWith("--show=") }?.substringAfter("="))?.split(',') ?: emptyList()
  for (s in show.filter { it.isNotBlank() }) for (i in rows.indices.filter { rows[it].first.contains(s) })
    println("      点名「$s」：中段单档 %5.1f%% @a%-3d 档%-3d 总灰 %5.1f%%  %s"
      .format(mids[i], midAt[i], tones[i], rows[i].second + rows[i].third, rows[i].first))
  println("   只看 128..254 最灰的 8 张：")
  for (r in rows.sortedByDescending { it.third }.take(8))
    println("      %5.1f%%  (1..127 %5.1f%%)   %s".format(r.third, r.second, r.first))
  // **正负对照**：这把新尺要能同时「放过他明确要留的那几张连续调」和「点名他报过的那几张一片灰」。
  println("   按最大单档降序 14 张（判'一片灰'用这一列，别用上一列）：")
  for (i in rows.indices.sortedByDescending { tops[it] }.take(14))
    println("      单档 %5.1f%%  总灰 %5.1f%%  %s".format(tops[i], rows[i].second + rows[i].third, rows[i].first))
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
