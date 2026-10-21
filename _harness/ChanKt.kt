package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **通道台（只量，不改模型）** —— 2026-10-09，接 `_yarddir.cjs` 拆出"互补型 8 张"之后。
 *
 * 对表榜首那几张 `IoU 0.00` 的，怀疑不是模型判错，而是**前景层本身就不带图形信息**：
 * 最实的那一块是四周（蒙版/底），图形反而是半透明的那部分，颜色靠背景层透出来。
 * 这一台问的就是这一句：**我们交出去的墨，有多少落在"前景层本来就全不透明"的那些格上？**
 *
 *  · `onSolid` 接近 1 且 `fgSolid` 很大 → **通道账**：模型只是照着"哪块最实"涂的，
 *    前景层里根本没有"图形 vs 承载面"这条信息可挖。
 *  · `onSolid` 明显低 → 模型自己把位置挑错了，那是**模型的账**，可以往下修。
 *
 * 跑法：`java -cp … harness.ChanKtKt <语料目录> [--only=子串]`
 */
private fun loadArgb(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null)
  g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_audit3")
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")
  val index = File(dir, "index.tsv")
  if (!index.isFile) { println("缺 ${index.path}"); return }
  val rows = index.readText().trim().split('\n').drop(1).map { it.split('\t') }
    .filter { it.size >= 14 && it[12] != "-" && it[12] != "写失败" }
  println("%-38s %6s %6s %6s %6s %7s %s".format(
    "包名", "fg实%", "我们%", "人画%", "IoU", "墨在实上%", "账"))
  var chan = 0; var model = 0
  var tnAll = 0; var tn = 0; var tnUnder = 0.0
  var sil = 0; var silUnder = 0.0
  for (r in rows) {
    val pkg = r[0]
    if (only != null && !pkg.contains(only)) continue
    val srcName = if (r.size > 15 && r[15] != "-" && r[15].isNotEmpty() && File(dir, r[15]).isFile) r[15] else r[2]
    val src = File(dir, srcName); val mono = File(dir, r[12])
    if (!src.isFile || !mono.isFile) continue
    val edge = r[3].toInt()
    val inPx = loadArgb(src, edge)
    if ((0 until edge * edge).none { ((inPx[it] ushr 24) and 0xff) > 0 }) continue
    val res = MonochromeMask.build(inPx, edge, edge)
    val ours = res.mask ?: continue
    val theirs = loadArgb(mono, edge)
    val n = edge * edge
    var fgSolid = 0; var ourInk = 0; var theirInk = 0; var both = 0; var either = 0; var onSolid = 0
    for (i in 0 until n) {
      val sa = (inPx[i] ushr 24) and 0xff
      val a = (ours[i] ushr 24) and 0xff
      val b = (theirs[i] ushr 24) and 0xff
      if (sa == 255) fgSolid++
      if (a >= 128) { ourInk++; if (sa == 255) onSolid++ }
      if (b >= 128) theirInk++
      if (a >= 128 || b >= 128) either++
      if (a >= 128 && b >= 128) both++
    }
    val iou = if (either == 0) 1.0 else both.toDouble() / either
    val fs = fgSolid * 100.0 / n; val us = ourInk * 100.0 / n; val ts = theirInk * 100.0 / n
    val onS = if (ourInk == 0) 0.0 else onSolid * 100.0 / ourInk
    // 判据：**墨几乎全落在前景层本来就全不透明的那些格上，且那一块本身就盖过大半画布**
    // → 前景层没有"图形/承载面"这条信息，模型只能照最实的涂。这是通道的账。
    val tonal = res.report.contains(" 调=1")
    if (tonal && us < ts * 0.6) { tn++; tnUnder += ts - us }
    if (tonal) tnAll++
    val isChan = onS >= 97.0 && fs >= 60.0 && iou < 0.35
    if (isChan) chan++ else if (iou < 0.35) model++
    // 另一族：设计师交的基本就是「前景层里所有不透明的格」（一张实心剪影），而我们只交了一小条。
    // 这一族不是位置不同意，是**我们把图形擦掉了大半** —— 与前两种病不同源。
    if (Math.abs(ts - fs) <= 6.0 && us < ts * 0.6) { sil++; silUnder += ts - us }
    if (iou < 0.55 || isChan)
      println("%-38s %6.1f %6.1f %6.1f %7.3f %7.1f %s".format(
        pkg.take(38), fs, us, ts, iou, onS,
        if (isChan) "通道" else if (iou < 0.35) "模型?" else ""))
  }
  println("走满幅那一支的 $tnAll 张，其中「我们交的墨 < 人画的 60%」的 $tn 张，平均少交 " + "%.1f".format(tnUnder / Math.max(tn,1)) + " 点")
  println("分家：通道账 $chan 张、位置不同意但前景层有信息可挖的 $model 张")
  println("「人画=整块前景剪影、我们只交一小条」这一族：$sil 张，平均少交 " +
    "%.1f".format(silUnder / Math.max(sil, 1)) + " 点墨")
  // —— 第二问（改模型之前的前置检查）：**`层比` 这一列分不分得开两堆。**
  //  A 堆 = 人画≈整块前景剪影（设计师把容器也当墨）；B 堆 = 人画远小于前景实心（设计师只留图形、
  //  真有一块盘坐在后面）。若 A 的 `剥/留` 明显比 B 大，`层比` 就是一把有牙的尺子；
  //  分不开就说明"knockInk≫keepInk 时那块底其实是图形"这句是我编的，下一刀不许开。
  val ka = ArrayList<Double>(); val kb = ArrayList<Double>()
  // —— 第三问（这一格真正要答的）：**设计师保留了多少前景？**
  //  `保留% = 人画墨 / 前景实心`。若它普遍接近 100，那"参考答案"基本等于「前景层里实心的都上墨」，
  //  我们那套切底机器就是在跟标准反着走；若它散布很开，才谈得上"哪块该切"是真问题。
  val keep = ArrayList<Double>(); val ourKeep = ArrayList<Double>()
  val keepSolid = ArrayList<Double>()
  val bothLow = ArrayList<String>()
  val weCut = ArrayList<String>(); val theyCut = ArrayList<String>()
  fun Double.round() = Math.round(this)
  for (r in rows) {
    val srcName = if (r.size > 15 && r[15] != "-" && r[15].isNotEmpty() && File(dir, r[15]).isFile) r[15] else r[2]
    val src = File(dir, srcName); val mono = File(dir, r[12])
    if (!src.isFile || !mono.isFile) continue
    val edge = r[3].toInt()
    val inPx = loadArgb(src, edge)
    val n = edge * edge
    if ((0 until n).none { ((inPx[it] ushr 24) and 0xff) > 0 }) continue
    val res = MonochromeMask.build(inPx, edge, edge)
    val ours = res.mask ?: continue
    val theirs = loadArgb(mono, edge)
    var solid = 0; var tk = 0; var ok = 0
    for (i in 0 until n) {
      val sa = (inPx[i] ushr 24) and 0xff
      if (sa == 255) solid++
      if (sa == 255 && ((theirs[i] ushr 24) and 0xff) >= 128) tk++
      if (sa == 255 && ((ours[i] ushr 24) and 0xff) >= 128) ok++
    }
    if (solid < n / 40) continue   // 前景几乎全透明（chrome 那一族），不参与"保留率"的比较
    val kp = tk * 100.0 / solid; val okp = ok * 100.0 / solid
    keep.add(kp); ourKeep.add(okp); keepSolid.add(solid * 100.0 / n)
    if (kp <= 50.0 && okp <= 50.0) bothLow.add(r[0])
    // 双向不同意：我们切了而设计师没切（`weCut`），与设计师切了我们没切（`theyCut`）。
    if (okp <= 50.0 && kp > 50.0) weCut.add("${r[0]}(我${okp.round()}% 人${kp.round()}%)")
    if (kp <= 50.0 && okp > 50.0) theyCut.add("${r[0]}(人${kp.round()}% 我${okp.round()}%)")
  }
  fun pct(v: ArrayList<Double>, p: Double): Double {
    if (v.isEmpty()) return -1.0
    val s = v.sorted(); return s[(s.size * p).toInt().coerceAtMost(s.size - 1)]
  }
  println("\n设计师保留了多少前景（人画墨 ÷ 前景实心，n=${keep.size}）：")
  println("  p10=%.0f%% p25=%.0f%% 中位=%.0f%% p75=%.0f%% p90=%.0f%%".format(
    pct(keep, 0.1), pct(keep, 0.25), pct(keep, 0.5), pct(keep, 0.75), pct(keep, 0.9)))
  println("  保留≥90%% 的 ${keep.count { it >= 90.0 }} 张；≤50%% 的 ${keep.count { it <= 50.0 }} 张")
  println("我们保留了多少前景（同一分母）：")
  println("  p10=%.0f%% p25=%.0f%% 中位=%.0f%% p75=%.0f%% p90=%.0f%%".format(
    pct(ourKeep, 0.1), pct(ourKeep, 0.25), pct(ourKeep, 0.5), pct(ourKeep, 0.75), pct(ourKeep, 0.9)))
  println("  保留≥90%% 的 ${ourKeep.count { it >= 90.0 }} 张；≤50%% 的 ${ourKeep.count { it <= 50.0 }} 张")
  println("  我们切了而设计师没切 (${weCut.size} 张)：${weCut.joinToString(" ")}")
  println("  设计师切了我们没切 (${theyCut.size} 张)：${theyCut.joinToString(" ")}")
  // —— 第四问（写"告诉模型这是前景层"之前的闸门）：**对表分低是不是只在自适应那批上成立。**
  //  `index.tsv` 的 `cls` 列就是运行时那一张的类型；`_mono` 只有自适应图标才有意义
  //  （非自适应的那张是整张画布，底在图里）。两堆若表现差很多，分工才成立。
  val byCls = HashMap<String, ArrayList<Pair<Double, Double>>>()   // cls → (墨量差, IoU)
  for (r in rows) {
    val srcName = if (r.size > 15 && r[15] != "-" && r[15].isNotEmpty() && File(dir, r[15]).isFile) r[15] else r[2]
    val src = File(dir, srcName); val mono = File(dir, r[12])
    if (!src.isFile || !mono.isFile) continue
    val edge = r[3].toInt()
    val inPx = loadArgb(src, edge)
    val n = edge * edge
    if ((0 until n).none { ((inPx[it] ushr 24) and 0xff) > 0 }) continue
    val ours = MonochromeMask.build(inPx, edge, edge).mask ?: continue
    val theirs = loadArgb(mono, edge)
    var oi = 0; var ti = 0; var both = 0; var either = 0
    for (i in 0 until n) {
      val a = (ours[i] ushr 24) and 0xff; val b = (theirs[i] ushr 24) and 0xff
      if (a >= 128) oi++; if (b >= 128) ti++
      if (a >= 128 || b >= 128) either++; if (a >= 128 && b >= 128) both++
    }
    val dev = Math.abs(oi - ti) * 100.0 / n
    val iou = if (either == 0) 1.0 else both.toDouble() / either
    byCls.getOrPut(r[5]) { ArrayList() }.add(dev to iou)
  }
  println("\n按运行时那一张的类型分开（墨量差 / IoU 的中位）：")
  for ((cls, v) in byCls.toList().sortedByDescending { it.second.size }) {
    val ds = v.map { it.first }.sorted(); val is2 = v.map { it.second }.sorted()
    println("  %-24s n=%-3d 墨量差中位=%5.1f 点   IoU 中位=%.3f".format(
      cls.take(24), v.size, ds[v.size / 2], is2[v.size / 2]))
  }
  // —— 第五问（通用规则的验收）：**地板该不该生效，看"前景层自己铺满整框了吗"。**
  //  装机那一版把 淘宝/微博/贴吧/饿了么/kazumi/vending/browndust 涂成了整块方 —— 它们 `盒布=100`，
  //  即前景层本来就是一整块不透明方，"实心一律上墨"必然涂满。所以通用规则是：
  //  **只有当前景层自己的不透明区成了形状（没铺满）时，地板才生效。**
  //  这里拿 63 对参考答案验它：铺满那批 vs 有形状那批，设计师的保留率是不是两回事。
  val fullKeep = ArrayList<Double>(); val shapeKeep = ArrayList<Double>()
  for (i in keep.indices) { if (keepSolid[i] >= 95.0) fullKeep.add(keep[i]) else shapeKeep.add(keep[i]) }
  println("\n地板生效判据的验收（按「前景层实心占画布」分两批，看设计师保留率）：")
  for ((nm, v) in listOf("铺满整框（实心≥95%）= 里面确实有盘" to fullKeep, "自己成了形状（<95%）= 没有盘要切" to shapeKeep))
    if (v.isNotEmpty()) println("  %-34s n=%-3d 设计师保留 中位=%.0f%%  ≥90%% 的 %d 张  ≤50%% 的 %d 张".format(
      nm, v.size, v.sorted()[v.size / 2], v.count { it >= 90.0 }, v.count { it <= 50.0 }))
  println("  两边都 ≤50% 的 ${bothLow.size} 张（同一张图两个答案都说该切）：${bothLow.take(10).joinToString(" ")}")
  for (r in rows) {
    val pkg = r[0]
    val srcName = if (r.size > 15 && r[15] != "-" && r[15].isNotEmpty() && File(dir, r[15]).isFile) r[15] else r[2]
    val src = File(dir, srcName); val mono = File(dir, r[12])
    if (!src.isFile || !mono.isFile) continue
    val edge = r[3].toInt()
    val inPx = loadArgb(src, edge)
    if ((0 until edge * edge).none { ((inPx[it] ushr 24) and 0xff) > 0 }) continue
    val res = MonochromeMask.build(inPx, edge, edge)
    val ours = res.mask ?: continue
    val theirs = loadArgb(mono, edge)
    val n = edge * edge
    var fgSolid = 0; var theirInk = 0
    for (i in 0 until n) {
      if (((inPx[i] ushr 24) and 0xff) == 255) fgSolid++
      if (((theirs[i] ushr 24) and 0xff) >= 128) theirInk++
    }
    val fs = fgSolid * 100.0 / n; val ts = theirInk * 100.0 / n
    val m = Regex(" 层比=([0-9]+)/([0-9]+)").find(res.report) ?: continue
    val keep = m.groupValues[1].toDouble(); val knock = m.groupValues[2].toDouble()
    if (knock + keep < 20) continue   // 两堆都没什么可剥的，不参与比较
    val ratio = knock / Math.max(keep, 1.0)
    if (Math.abs(ts - fs) <= 6.0) ka.add(ratio) else if (ts < fs * 0.6) kb.add(ratio)
  }
  fun qv(v: ArrayList<Double>, p: Double): Double {
    if (v.isEmpty()) return -1.0
    val s = v.sorted(); return s[(s.size * p).toInt().coerceAtMost(s.size - 1)]
  }
  println("\n`层比=剥/留` 这一列能不能分开两堆（比值越大 = 模型剥掉的候选墨越多）：")
  println("  A 堆「人画≈整块前景剪影」（容器也算墨）：n=${ka.size}  p25=%.1f 中位=%.1f p75=%.1f".format(
    qv(ka, 0.25), qv(ka, 0.5), qv(ka, 0.75)))
  println("  B 堆「人画远小于前景实心」（真有盘坐在后面）：n=${kb.size}  p25=%.1f 中位=%.1f p75=%.1f".format(
    qv(kb, 0.25), qv(kb, 0.5), qv(kb, 0.75)))
  val sep = qv(kb, 0.75) < qv(ka, 0.25)
  // 分位数在这里会把尾巴藏掉（两堆都是大半为 0），所以把尾部单独数出来。
  val aBig = ka.count { it >= 3.0 }; val bBig = kb.count { it >= 3.0 }
  println("  尾巴（剥掉≥留的 3 倍）：A 堆 $aBig/${ka.size} 张、B 堆 $bBig/${kb.size} 张" +
    "；A 堆最大 %.0f、B 堆最大 %.0f".format(qv(ka, 1.0), qv(kb, 1.0)))
  println(if (qv(ka, 0.5) < 0 || qv(kb, 0.5) < 0) "  ⚠ 某一堆是空的，这个比较没做"
  else if (sep) "  → 分得开（B 的 p75 还在 A 的 p25 以下）：`层比` 可以有牙，下一刀可以开。"
  else "  → **分不开**：`剥得多` 在两堆里都常见，`层比` 不能当否决的判据，下一刀不许开。")
}
