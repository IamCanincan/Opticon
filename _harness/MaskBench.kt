package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 判据台：跑**真**的 MonochromeMask，出三样东西 ——
 *   1. 一行判据数字（模型自己决策吃的那几个数）；
 *   2. ASCII 形状（# 实心 / + 中浓 / · 淡 / 空格 透明）；
 *   3. 一张**上屏预览** PNG：原图 | 浅色主题 | 深色主题。
 *      桌面拿 alpha 当不透明度上色，所以预览就是 `plate×(1-a) + ink×a` ——
 *      用户报的「白块 / 发糊 / 粘连」在这张图上是可以直接看出来的，
 *      不需要等他真机看一眼再回来说症状。
 */

private fun preview(mask: IntArray?, edge: Int, plate: Int, ink: Int): IntArray {
  val out = IntArray(edge * edge)
  if (mask == null) { out.fill((0xff shl 24) or plate); return out }
  for (i in out.indices) {
    val a = (mask[i] ushr 24) and 0xff
    val r = (((plate shr 16 and 0xff) * (255 - a) + (ink shr 16 and 0xff) * a) / 255) shl 16
    val g = (((plate shr 8 and 0xff) * (255 - a) + (ink shr 8 and 0xff) * a) / 255) shl 8
    val b = ((plate and 0xff) * (255 - a) + (ink and 0xff) * a) / 255
    out[i] = (0xff shl 24) or r or g or b
  }
  return out
}

private fun ascii(mask: IntArray?, w: Int, h: Int) {
  if (mask == null) { println("      | 没有掩码 |"); return }
  val cols = 48
  val rows = 28
  for (ry in 0 until rows) {
    val sb = StringBuilder()
    val y0 = ry * h / rows
    for (rx in 0 until cols) {
      val x0 = rx * w / cols
      var hi = 0
      var sum = 0
      var n = 0
      val y1 = maxOf(y0 + 1, (ry + 1) * h / rows)
      val x1 = maxOf(x0 + 1, (rx + 1) * w / cols)
      for (y in y0 until y1) for (x in x0 until x1) {
        val a = (mask[y * w + x] ushr 24) and 0xff
        if (a > hi) hi = a
        sum += a; n++
      }
      sb.append(when {
        hi >= 250 -> '#'
        hi >= 128 -> '+'
        hi > 0 -> '·'
        else -> ' '
      })
    }
    println("      |$sb|")
  }
}

private fun levels(mask: IntArray?): String {
  if (mask == null) return "-"
  var zero = 0; var faint = 0; var mid = 0; var solid = 0
  for (p in mask) when (val a = (p ushr 24) and 0xff) {
    0 -> zero++
    !in 1..127 -> if (a == 255) solid++ else mid++
    else -> faint++
  }
  return "浓度[0=$zero 淡1-127=$faint 中128-254=$mid 实255=$solid]"
}

/** 判据行里取一个 `键=值`（值取到下一个空格）。没有就返回 "-"。 */
private fun fld(report: String, key: String): String =
  Regex("(?:^| )$key=([^ ]*)").find(report)?.groupValues?.get(1) ?: "-"

/** 判据行里 `分档=a/b(t1,t2,…)` 括号里的那些浓度。 */
private fun String.tones(): List<String> =
  Regex("\\(([-0-9,]*)\\)\\s*$").find(this)?.groupValues?.get(1)?.split(",")
    ?.filter { it.isNotEmpty() } ?: emptyList()

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val inset = args.contains("--inset")
  val shot = args.contains("--shot")
  // `--far`：摊「离底有多远 × 离实墨有多远」这张二维表（**两把尺都是生产自己的数**：
  //   横轴 = `Scratch.distT` 按这张的 `零..实` 量程归一，纵轴 = 交付遮码里离 α>=200 那批几格）。
  //   造它的理由：malody 那几块"没切干净"的灰，单问颜色（=上一轮那把生长刀）或单问距离（=场洗）都分不开，
  //   要的是两者的**合取**；而合取的门槛不许我拍，得先把全语料这张表摊出来。
  val far = args.contains("--far")
  val farRows = ArrayList<String>()
  // `--ink=目录`：把**交付遮罩**按"白底、值 = 255 − α"各写一张 PNG。
  //   为什么要它而不是另起一台渲染器：`src` / `under` / `plateLayer` 这三样输入是这一台**算出来的**，
  //   另起一台就得抄一遍抄本 —— 2026-10-19 就撞上：`FaintShotKt` 用 `build(px,w,h)` 默认参数，
  //   于是贴吧 / UU 遥控器两张在像素比对里**一格没变**，而判据行 `顶占比 14→34 / 10→49`。
  //   差的就是 `plateLayer` 与 `under`。像素比对必须走判据台同一份输入构造，否则两边比的不是同一件事。
  val inkDir = args.firstOrNull { it.startsWith("--ink=") }?.substringAfter("=")?.let { File(it).apply { mkdirs() } }
  val outDir = File(if (shot) "bench/preview" else ".").apply { mkdirs() }
  // 逐张判据另写一份**机器可读**的 TSV。为什么：上一轮的改前/改后对照用 shell `join` 拼，
  // 判据行里有空格、两个版本的字段数还不一样，拼出来的表错位了（steamlink 那行就是证据），
  // 而我拿它换过一次判据。键对齐必须由代码做，不能由 shell 猜列。
  val tsv = args.firstOrNull { it.startsWith("--tsv=") }?.substringAfter("=")?.let { File(it) }
  tsv?.writeText("")
  val fgAll = args.contains("--fg")
  // **输入构造（`src` / `isFg` / `plateLayer` / `under`）只有 [Inputs.kt] 那一份。** 以前它抄在
  //   这个 main 里，于是任何想吃同一条路的台子都得再抄一遍 —— 2026-10-19 的 `FaintShotKt` 就是
  //   抄漏了 `plateLayer` 与 `under`，两张图在像素比对里"一格没变"，而判据行 `顶占比 14→34`。
  //   逐张口径的三条理由（index 决定前景层 / `_modelbg` 优先 / 分母剔 preview 与 modelbg）都写在那里。
  val dir = File(args.first { !it.startsWith("--") })
  println("=== ${if (inset) "自适应前景（内容缩到中央 61%）" else "非自适应（满幅）"} · edge=$edge ===")
  for (k in cases(dir, edge, inset, fgAll)) {
    val f = k.file
    val src = k.src
    val srcSize = k.srcSize
    val t0 = System.nanoTime()
    val isFg = k.isFg
    val plateLayer = k.plateLayer
    val under = k.under
    // `--trim`：把【主形】按生产的口径开一遍 —— 不带 `=all` 时只开名单里那张（与 MonochromeSynth
    //   同一个判法），带 `=all` 则每张都开（用来看那批对照）。**不开这旗时判据台与改动前逐字同口径。**
    val trimArg = args.firstOrNull { it == "--trim" || it.startsWith("--trim=") }
    val trimPkg = f.nameWithoutExtension.substringBefore('_')
    val trimOn = trimArg != null &&
      (trimArg == "--trim=all" || MonochromeMask.TRIM_PKGS.any { it == trimPkg })
    val r = MonochromeMask.build(src, edge, edge, plateLayer, under, trimSubject = trimOn)
    // `--raise`：**台子上的模拟，生产一行没动** —— 把"源平色、我们铺了坡"的块抬到它自己的 p90，
    //   然后照常出浓度行 / 〔平块〕行 / `--shot` 预览。用途只有一个：拿眼睛判这 5 张该不该抬。
    if (args.contains("--raise") && r.mask != null) {
      val ch = raiseFlat(src, r.mask!!, edge)
      if (ch > 0) println("    〔抬平〕改 $ch 格（模拟）")
    }
    // 临时：`盘刀` 一声不响时，先证明 `under` 真的到了、以及那块盘占了几成。
    if (under != null) {
      val fam = HashMap<Int, Int>(); var sol = 0
      for (i in 0 until edge * edge) {
        if (((under[i] ushr 24) and 0xff) < 128) continue
        sol++
        val k = ((((under[i] shr 16) and 0xff) / 24) shl 10) or
          ((((under[i] shr 8) and 0xff) / 24) shl 5) or ((under[i] and 0xff) / 24)
        fam[k] = (fam[k] ?: 0) + 1
      }
      println("    〔under〕实心=${sol * 100 / (edge * edge)}% 最大族=${(fam.maxByOrNull { it.value }?.value
        ?: 0) * 100 / maxOf(sol, 1)}%")
    } else if (File(f.parentFile, f.nameWithoutExtension + "_bg.png").isFile &&
      !f.name.endsWith("_bg.png") && !f.name.endsWith("_fg.png"))
      println("    〔under〕兄弟 _bg 在，但没读到")
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("--- ${f.nameWithoutExtension}  ${ms}ms" + (if (isFg) "  〔前景层〕" else ""))
    if (srcSize.first != edge || srcSize.second != edge)
      println("    ⚠ 源图 ${srcSize.first}x${srcSize.second} ≠ edge=$edge —— 本张数字**不可与真机对照**")
    println("    ${r.report}")
    // `--probe=列,起行,止行,步长`：把那一列上「源 α / 源明度 → 我们交的 α」逐格打出来。
    //   用途：判"断开"到底是**真断了**还是只是淡 —— 连通块数说"两块"，可屏幕上也许根本没缝。
    //   （malody 那两块"脚"就是被这个区别绊了一整轮：`--feet` 数到 2 块，不等于看得见 2 块。）
    if (args.any { it.startsWith("--probe=") }) {
      val p = args.first { it.startsWith("--probe=") }.substringAfter("=").split(",").map { it.toInt() }
      val sb = StringBuilder("    〔探针〕x=${p[0]} ")
      var y = p[2]
      while (y >= p[1]) {
        val i = y * edge + p[0]
        val sp = src[i]; val sa = (sp ushr 24) and 0xff
        val sl = ((0.2126 * ((sp shr 16) and 0xff) + 0.7152 * ((sp shr 8) and 0xff) + 0.0722 * (sp and 0xff))).toInt()
        sb.append("${y}:源${sa}/${sl}→墨${if (r.mask == null) -1 else (r.mask!![i] ushr 24) and 0xff}  ")
        y -= p[3]
      }
      println(sb.toString())
    }
    // `--feet`：只量不改 —— 把"删掉 <1/5 最大块的孤立实心件"这把补刀在**真流水线产物**上量一遍射程。
    if (args.any { it.startsWith("--feet") }) println("    " + feetLine(r.mask, edge))
    // `--flatin`：没有对岸的那本账 —— 源是平色、我们却铺了一道坡的块有几块、几格。
    if (args.contains("--flatin")) println("    " + flatInkLine(src, r.mask, edge))
    tsv?.appendText(listOf(
      f.nameWithoutExtension, fld(r.report, "H"), fld(r.report, "底占"), fld(r.report, "底厚"),
      fld(r.report, "围"), fld(r.report, "框内"), fld(r.report, "分档"), r.report.tones().maxOrNull() ?: "0"
    ).joinToString("\t") + "\n")
    println("    ${levels(r.mask)}")
    ascii(r.mask, edge, edge)
    if (inkDir != null) {
      // 掩码为 null 也要写一张全白 —— 不然两版文件名对不上，比对器会**静默跳过**那一张，
      // 而"这一版压根没交掩码"恰恰是最不能漏过去的那种变化。
      val v = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
      val mk = r.mask
      val px = IntArray(edge * edge) { i ->
        val a = if (mk == null) 0 else ((mk[i] ushr 24) and 0xff)
        val g = 255 - a
        (0xff shl 24) or (g shl 16) or (g shl 8) or g
      }
      v.setRGB(0, 0, edge, edge, px, 0, edge)
      ImageIO.write(v, "png", File(inkDir, "${f.nameWithoutExtension}.png"))
    }
    if (far) {
      val mk = r.mask
      val z = Regex(" 零=(-?\\d+)").find(r.report)?.groupValues?.get(1)?.toIntOrNull()
      val so = Regex(" 实=(-?\\d+)").find(r.report)?.groupValues?.get(1)?.toIntOrNull()
      val s = if (mk != null && z != null && so != null && so > z) farScratch() else null
      val dt = s?.let { runCatching { fld(it, "distT") as ShortArray }.getOrNull() }
      if (mk != null && z != null && so != null && dt != null && dt.size == mk.size && so > z) {
        val n = mk.size
        val al = IntArray(n) { (mk[it] ushr 24) and 0xff }
        val dist = IntArray(n) { -1 }
        val qq = IntArray(n); var hd = 0; var tl = 0
        for (i in 0 until n) if (al[i] >= 200) { dist[i] = 0; qq[tl++] = i }
        val ew = edge
        while (hd < tl) {
          val c = qq[hd++]; val cy = c / ew; val cx = c - cy * ew
          for (dy in -1..1) { val ny = cy + dy; if (ny < 0 || ny >= ew) continue
            for (dx in -1..1) { if (dx == 0 && dy == 0) continue; val nx = cx + dx
              if (nx < 0 || nx >= ew) continue; val j = ny * ew + nx
              if (dist[j] < 0) { dist[j] = dist[c] + 1; qq[tl++] = j } } }
        }
        // 横轴 = 这张自己的量程归一（<=0 就是"生产已经承认它是底"那一档），纵轴 = 离实墨几格
        val qub = intArrayOf(0, 15, 30, 50, 100, 100000)
        val qubLb = listOf("<=0", "<=15", "<=30", "<=50", "<=100", ">100")
        val cnt = Array(qub.size) { IntArray(4) }
        var faint = 0
        for (i in 0 until n) {
          if (al[i] <= 0 || al[i] >= 200) continue
          faint++
          val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
          var bi = qub.size - 1
          for (k in qub.indices) if (dep <= qub[k]) { bi = k; break }
          val d = if (dist[i] < 0) 9999 else dist[i]
          cnt[bi][if (d <= 2) 0 else if (d <= 5) 1 else if (d <= 10) 2 else 3]++
        }
        val sb = StringBuilder()
        for (k in qub.indices) sb.append(" ${qubLb[k]}:").append(cnt[k][0]).append("/").append(cnt[k][1])
          .append("/").append(cnt[k][2]).append("/").append(cnt[k][3])
        farRows.add("${f.nameWithoutExtension.take(30).padEnd(31)} 零=$z 实=$so 淡=$faint$sb")
        // 第二张表：**离底的方向**（色相角差），不是距离。
        //   上一轮 malody 那张 bucket 图说明"离底多远"这一根轴分不开底的纹与主体的面（两样都在
        //   dep 0..15% 那一档），但它漏了一个事实：底的纹是**同色相、只比底亮**，主体的面是**另一个色相**。
        //   底色取生产自己判成底那批格的平均 Lab（`Scratch.plate` + `labL/A/B`，不是我再猜一个参照）。
        val pl = fld(s, "plate") as ByteArray
        val ll = fld(s, "labL") as ShortArray; val la = fld(s, "labA") as ShortArray; val lb = fld(s, "labB") as ShortArray
        var pn = 0L; var pL = 0L; var pA = 0L; var pB = 0L
        for (i in 0 until n) if (pl[i] != 0.toByte()) {
          if ((src[i] ushr 24) and 0xff != 255) continue
          pn++; pL += ll[i]; pA += la[i]; pB += lb[i]
        }
        val nearHue = BooleanArray(n)   // 离**底色相** <=6°（低彩度那格直接算同色相）
        val depLe15 = BooleanArray(n)   // 离底 <=15% 量程
        if (pn > 0) {
          val msl = pL / pn / 64.0; val msa = pA / pn / 64.0; val msb = pB / pn / 64.0
          val plateAng = Math.toDegrees(Math.atan2(msb, msa))
          val plateC = Math.sqrt(msa * msa + msb * msb)
          val hq = intArrayOf(6, 12, 20, 35, 60, 1000)
          val hqLb = listOf("<=6", "<=12", "<=20", "<=35", "<=60", ">60")
          val hc = Array(hq.size) { IntArray(2) }   // [色相角差档][dep<=15? 0:1]，只数 >=3 格的淡墨
          for (i in 0 until n) {
            val a0 = (mk[i] ushr 24) and 0xff
            if (a0 <= 0 || a0 >= 200) continue
            val d = if (dist[i] < 0) 9999 else dist[i]
            if (d < 3) continue
            val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
            var ang = Math.toDegrees(Math.atan2(lb[i] / 64.0, la[i] / 64.0))
            if (Math.sqrt((la[i] / 64.0) * (la[i] / 64.0) + (lb[i] / 64.0) * (lb[i] / 64.0)) < 2.0) ang = plateAng
            var dh = Math.abs(ang - plateAng); if (dh > 180) dh = 360 - dh
            var bi = hq.size - 1
            for (k in hq.indices) if (dh <= hq[k]) { bi = k; break }
            hc[bi][if (dep <= 15) 0 else 1]++
            nearHue[i] = dh <= 6
            depLe15[i] = dep <= 15
          }
          val hb = StringBuilder()
          for (k in hq.indices) hb.append(" Δ色相").append(hqLb[k]).append(":").append(hc[k][0]).append("/").append(hc[k][1])
          farRows.add("    ${f.nameWithoutExtension.take(30).padEnd(31)} 底色相=${"%.1f".format(plateAng)}° 彩度=${"%.1f".format(plateC)} → $hb")
        }
        // `--farshot=目录`：把这张表画出来 —— 黑 = 实墨，白 = 空，灰 = 其余淡墨；
        //   红 = 颜色已在底的容差内（dep<=0）且不贴实墨；
        //   绿 = dep<=15% 且**与底同色相**（候选要擦的那片）；
        //   橙 = dep<=15% 但**色相不同**（那是主体自己的面，不许擦）。
        //   眼睛判的就是一件事：malody 下方那两道楔形是不是绿的，M 两条腿是不是橙/灰。
        // `--globall=K`：**换参照，不换尺**。今天前八条都栽在同一个地方 —— 底是**逐格拟合的色场**
        //   （`FIELD_GRID` 8×8），底的纹会把**自己那一格**的参照抬上去，所以在"离本地场多远"这把尺上
        //   它和主体的面长得一样。这一条改问**离整块底的全局平均色多远**（拿生产自己判成底那批格的
        //   平均 L 当参照，主体那批的平均 L 当另一端），纹是"只比底亮一点点"，M 的面是"亮得多"。
        val fs = args.firstOrNull { it.startsWith("--farshot=") }?.substringAfter("=")
        val gl = args.firstOrNull { it.startsWith("--globall=") }?.substringAfter("=")?.toFloatOrNull()
        val globKeep = if (gl != null && fs != null) BooleanArray(n) else null
        if (globKeep != null && gl != null) {
          var pn2 = 0L; var pL2 = 0L; var in2 = 0L; var ni = 0L
          for (i in 0 until n) {
            if ((src[i] ushr 24) and 0xff != 255) continue
            val a0 = (mk[i] ushr 24) and 0xff
            if (pl[i] != 0.toByte()) { pn2++; pL2 += ll[i] } else if (a0 >= 200) { ni++; in2 += ll[i] }
          }
          if (pn2 > 0 && ni > 0) {
            val pLm = pL2 / pn2 / 64.0; val iLm = in2 / ni / 64.0
            val span = (iLm - pLm).coerceAtLeast(1.0)
          // ⚠ 这里用 **|ΔL|**，不是 (L−底L)：这张图的墨**两头都跑**（M 的青蓝面很亮、M 的黑身体很暗），
            //   有符号那一版会把黑身体算成"比底还暗 = 不像墨"，整条尺直接废（第一版就是这么读出
            //   malody 留下 92 / 可擦 4,756 的假账）。阈值 `--globall=K` 现在是**绝对 Lab 光度差**。
            val gTh = gl
            for (i in 0 until n) {
              val a0 = (mk[i] ushr 24) and 0xff
              if (a0 <= 0 || a0 >= 200) continue
              val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
              val d = if (dist[i] < 0) 9999 else dist[i]
              if (dep > 15 || d < 3) continue
              globKeep[i] = Math.abs(ll[i] / 64.0 - pLm) > gTh
            }
            var kp = 0; var wp = 0
            for (i in 0 until n) {
              val a0 = (mk[i] ushr 24) and 0xff
              if (a0 <= 0 || a0 >= 200) continue
              val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
              val d = if (dist[i] < 0) 9999 else dist[i]
              if (dep > 15 || d < 3) continue
              if (globKeep[i]) kp++ else wp++
            }
            farRows.add("    ${f.nameWithoutExtension.take(30).padEnd(31)} 全局底L=${"%.1f".format(pLm)} 墨L=${"%.1f".format(iLm)} 阈值=$gl → 亮得多(留下) $kp / 只比底亮一点(可擦) $wp")
          }
        }
        // `--groundconnect`：换一个**墙**再洪泛一次。之前那台洪泛用的是"α<200 都能穿"，于是从边界
        //   一路走进 M 的面（漏）。这一台只准穿**离底 <=15% 量程**那些格 —— 主体的轮廓自己就是一圈
        //   "不像底"的格，它应当当墙；底的纹本来就长在底里，一定连得到边界。
        //   极性对了：连得到边界 = 底的纹（可擦），被"不像底"围住 = 主体（留下）。
        val gc = args.contains("--groundconnect")
        val reached = if (gc && fs != null) BooleanArray(n) else null
        if (reached != null) {
          val q2 = IntArray(n); var h2 = 0; var t2 = 0
          val ok = BooleanArray(n) { ((dt[it].toInt() and 0xffff) - z) * 100 / (so - z) <= 15 }
          for (i in 0 until n) {
            val x = i - (i / ew) * ew; val y = i / ew
            if ((x == 0 || y == 0 || x == ew - 1 || y == ew - 1) && ok[i]) { reached[i] = true; q2[t2++] = i }
          }
          while (h2 < t2) {
            val c = q2[h2++]; val cy = c / ew; val cx = c - cy * ew
            for (dy in -1..1) { val ny = cy + dy; if (ny < 0 || ny >= ew) continue
              for (dx in -1..1) { if (dx == 0 && dy == 0) continue; val nx = cx + dx
                if (nx < 0 || nx >= ew) continue; val j = ny * ew + nx
                if (!reached[j] && ok[j]) { reached[j] = true; q2[t2++] = j } } }
          }
        }
        // `--edgewash=R,T`：把上面那堆"颜色上分不开"的候选，改用**形状那边的一条边**再切一刀 ——
        //   主体的面**紧挨着一条真边**（M 的黑身体，源里 ΔL 很大），底的纹**泡在平色场里**（旁边没有强边）。
        //   问的不是"离实墨几格"（那条量过了，腿和楔形重叠），而是"身边那条边**有多强**"。
        //   gmax = 半径 R 内最大的源 |ΔL|；gmax<T → 擦（绿），gmax>=T → 留（蓝）。
        val ew2 = args.firstOrNull { it.startsWith("--edgewash=") }?.substringAfter("=")?.split(",")
          ?.map { it.trim().toInt() }
        val gmax = if (ew2 != null && fs != null) IntArray(n) else null
        if (gmax != null && ew2 != null) {
          val R = ew2[0]
          val grad = IntArray(n)
          for (jy in 0 until ew) for (jx in 0 until ew) {
            val i = jy * ew + jx; var g = 0
            for (dy in -1..1) { val ny = jy + dy; if (ny < 0 || ny >= ew) continue
              for (dx in -1..1) { if (dx == 0 && dy == 0) continue; val nx = jx + dx
                if (nx < 0 || nx >= ew) continue
                val t = Math.abs(ll[i] - ll[ny * ew + nx]) / 64; if (t > g) g = t } }
            grad[i] = g
          }
          for (jy in 0 until ew) for (jx in 0 until ew) {
            var g = 0
            for (dy in -R..R) { val ny = jy + dy; if (ny < 0 || ny >= ew) continue
              for (dx in -R..R) { val nx = jx + dx; if (nx < 0 || nx >= ew) continue
                val t = grad[ny * ew + nx]; if (t > g) g = t } }
            gmax[jy * ew + jx] = g
          }
        }
        if (fs != null) {
          val T = ew2?.get(1) ?: 0
          var washN = 0; var keepN = 0
          val fd = File(fs).apply { mkdirs() }
          val v = java.awt.image.BufferedImage(ew, ew, java.awt.image.BufferedImage.TYPE_INT_RGB)
          for (i in 0 until n) {
            val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
            val d = if (dist[i] < 0) 9999 else dist[i]
            val x = i - (i / ew) * ew; val y = i / ew
            val cand = dep in 1..15 && d >= 3
            if (gmax != null && cand && nearHue[i]) { if (gmax[i] < T) washN++ else keepN++ }
            if (reached != null && cand && nearHue[i]) { if (reached[i]) washN++ else keepN++ }
            v.setRGB(x, y, when {
              al[i] >= 200 -> 0x000000
              al[i] == 0 -> 0xFFFFFF
              globKeep != null && globKeep[i] -> 0x2060CC
              globKeep != null && cand -> 0x18A030
              reached != null && cand && nearHue[i] && !reached[i] -> 0x8020A0
              gmax != null && cand && nearHue[i] && gmax[i] >= T -> 0x2060CC
              dep <= 0 && d >= 3 -> 0xCC2222
              dep <= 15 && d >= 3 && nearHue[i] -> 0x18A030
              dep <= 15 && d >= 3 -> 0xE09020
              else -> { val g = 255 - al[i]; (g shl 16) or (g shl 8) or g }
            })
          }
          javax.imageio.ImageIO.write(v, "png", File(fd, "${f.nameWithoutExtension}.png"))
          if (gmax != null) farRows.add("    ${f.nameWithoutExtension.take(30).padEnd(31)} 身边强边 R=${ew2?.get(0)} T=$T → 可擦 ${washN} / 因挨着真边而留下 ${keepN}")
          if (reached != null) farRows.add("    ${f.nameWithoutExtension.take(30).padEnd(31)} 连着底(穿过 dep<=15% 洪泛) → 可擦 ${washN} / 被不像底围住而留下 ${keepN}")
        }
        // `--embedcut=目录[,TH]`：最后那把尺问的不是颜色、不是距离，是**这块淡墨有没有被底包着**。
        //   环 = 半径 6..9 的方环，环上"交付 α==0（模型已认成底）"的比例 >=0.75 才算包着。
        //   底的灰片长在底场里 → 环几乎全空；主体的面长在主体里 → 环一半是淡墨/实墨。
        //   出图三栏：现交付 / 擦掉灰片后 / 灰片本体（红）。
        val ec = args.firstOrNull { it.startsWith("--embedcut=") }?.substringAfter("=")?.split(",")
        if (ec != null) {
          val outD = File(ec[0]).apply { mkdirs() }
          val th = ec.getOrNull(1)?.trim()?.toIntOrNull() ?: 15
          val ring = DoubleArray(n) { -1.0 }
          for (y in 0 until ew) for (x in 0 until ew) {
            var tp = 0; var tt = 0
            for (dy in -9..9) for (dx in -9..9) {
              val rr = Math.hypot(dx.toDouble(), dy.toDouble()); if (rr < 6 || rr > 9) continue
              val ny = y + dy; val nx = x + dx; if (ny < 0 || ny >= ew || nx < 0 || nx >= ew) continue
              tt++; if (al[ny * ew + nx] == 0) tp++
            }
            if (tt >= 12) ring[y * ew + x] = tp.toDouble() / tt
          }
          val cutB = BooleanArray(n)
          var byColor = 0; var byEmbed = 0; var ft = 0
          for (i in 0 until n) {
            if (al[i] <= 0 || al[i] >= 200) continue
            ft++
            val dep = ((dt[i].toInt() and 0xffff) - z) * 100 / (so - z)
            val d = if (dist[i] < 0) 9999 else dist[i]
            if (dep > th || d < 3 || !nearHue[i]) continue
            byColor++
            if (ring[i] >= 0.75) { cutB[i] = true; byEmbed++ }
          }
          val img = BufferedImage(ew * 3 + 16, ew, BufferedImage.TYPE_INT_RGB)
          val after = IntArray(n) { if (cutB[it]) 0 else mk[it] }
          val mark = IntArray(n) { i -> if (cutB[i]) (0xff shl 24) or 0xFF0000 else (0xff shl 24) or 0xFFFFFF }
          img.setRGB(0, 0, ew, ew, preview(mk, ew, 0xEDEDED, 0x1F1F1F), 0, ew)
          img.setRGB(ew + 8, 0, ew, ew, preview(after, ew, 0xEDEDED, 0x1F1F1F), 0, ew)
          img.setRGB(ew * 2 + 16, 0, ew, ew, mark, 0, ew)
          ImageIO.write(img, "png", File(outD, "${f.nameWithoutExtension}.png"))
          farRows.add("    ${f.nameWithoutExtension.take(30).padEnd(31)} 淡墨=$ft 离底<=${th}+同色相+离墨>=3 = $byColor，其中被底包着 = $byEmbed（${"%.2f".format(if (ft > 0) byEmbed * 100.0 / ft else 0.0)}%）")
        }
      } else farRows.add("${f.nameWithoutExtension.take(30).padEnd(31)} 跳过（掩码空 / 零实量程无效 / distT 没填）")
    }
    if (shot) {
      // `--flip`：把交出去的遮罩按位取反再画 —— 只用来回答"反一下是不是更好看"，
      // 不改模型。反相在真机上有一条不改代码的路：图标页「按应用合成（调试）」把那张标成
      // 不合成，桌面会按它自己的"亮=墨"压一张，屏幕上就是反的。
      val msk = if (args.contains("--flip") && r.mask != null)
        IntArray(r.mask.size) { i -> val a = 255 - ((r.mask[i] ushr 24) and 0xff); (a shl 24) or 0x00FFFFFF }
      else r.mask
      val img = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_RGB)
      val srcPrev = IntArray(edge * edge) {
        val p = src[it]
        val a = (p ushr 24) and 0xff
        val over = (0xff shl 24) or 0x808080
        if (a == 255) p or (0xff shl 24) else {
          val r2 = ((((p shr 16) and 0xff) * a + 0x80 * (255 - a)) / 255) shl 16
          val g2 = ((((p shr 8) and 0xff) * a + 0x80 * (255 - a)) / 255) shl 8
          val b2 = ((p and 0xff) * a + 0x80 * (255 - a)) / 255
          (0xff shl 24) or r2 or g2 or b2
        }
      }
      img.setRGB(0, 0, edge, edge, srcPrev, 0, edge)
      img.setRGB(edge + 8, 0, edge, edge, preview(msk, edge, 0xEDEDED, 0x1F1F1F), 0, edge)
      img.setRGB(edge * 2 + 8, 0, edge, edge, preview(msk, edge, 0x2B2B2B, 0xE8E8E8), 0, edge)
      ImageIO.write(img, "png", File(outDir, "${f.nameWithoutExtension}.png"))
    }
  }
  if (far) {
    println("== 离底多远 × 离实墨多远（每格 = 淡墨格数，四段依次是 离实墨 <=2 / 3-5 / 6-10 / >10）==")
    println("横轴是这张自己的量程归一：dep=(distT-零)/(实-零)*100，<=0 那档 = 生产自己就承认它是底")
    for (row in farRows) println("  $row")
  }
}

private fun fld(o: Any, name: String): Any {
  val c = o.javaClass.getDeclaredField(name); c.isAccessible = true; return c.get(o)
}

/** 生产那本账（`Scratch`）就在 `MonochromeMask.pool` 里，取刚算完这张的那一份 —— 不另抄一份实现。 */
private fun farScratch(): Any? {
  val fl = MonochromeMask::class.java.getDeclaredField("pool"); fl.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  return (fl.get(MonochromeMask) as ThreadLocal<Any>).get()
}
