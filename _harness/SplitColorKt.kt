package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * **探针（只量不改）**：同一个**色族**，既贴着内容框外沿、又有一整块被围在内部 —— 全语料有几张？
 * 每张再问一句：**围住那块岛的那一圈，自己是什么身份。**
 *
 * 为什么要它：淘宝新桌面图标那张（促销白带与白「淘」同色）判反了，前五次改法全部死在
 * "只有两个样本"上 —— 淘宝该翻、易班不该翻，n=2 的分布上拍不出任何能推广的线。
 * 主人 2026-11-05 那句「有可能别的图标也这样呢，单单挑这个特例没有用的」是要我把这一族**数出来**：
 * 几张、每张现在交成了墨还是底、以及**为什么这张该这样**。数出来才有标签，有标签才谈判据。
 *
 * 口径：
 *  - **族** = RGB 各降 4 位（4096 个键）。⚠ 第一版按精确色分，淘宝的「淘」是 `#FFFFFF` 而它外面那圈是
 *    `#FFFEFE` —— 同一种白被拆成两块，圈数出来是"另一块白"，判据当场废掉。按族是这台子的**前提**，
 *    不是精度问题。
 *  - 内容框 = α>0 的外接框；只数框内**实心**（α=255）的格子。
 *  - 同族四邻连通成组件；组件有一格落在内容框四条边上 ⇒ "敞开着"，否则 ⇒ "被围住（岛）"。
 *  - 命中 = 该族敞开格 ≥ [--minE]（默认框的 1%）**且**围住格 ≥ [--minI]（默认 2%）。
 *  - 圈 = 岛的四邻里**不属于该族**的实心格。主圈色 = 圈里最多的那一族；
 *    `圈族贴边%` = 与主圈色 ΔE≤40 的那些族的敞开格合计占框多少；
 *    `圈交付墨%` = 圈那一层成品掩码上 α≥128 的占比。
 *  - 岛的 `现交付墨%` 吃 [MonochromeMask.build] 的**成品掩码**（装机同一份输入构造，见 [cases]）。
 *
 * 跑法：`java -cp out;stubs_out;<stdlib> harness.SplitColorKtKt <图标目录> [--edge=384] [--minE=1] [--minI=2]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  if (!dir.isDirectory) { println("!! 目录不存在：${dir.path}"); exitProcess(2) }
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=") ?: "384").toInt()
  val minE = (args.firstOrNull { it.startsWith("--minE=") }?.substringAfter("=") ?: "1").toInt()
  val minI = (args.firstOrNull { it.startsWith("--minI=") }?.substringAfter("=") ?: "2").toInt()

  val rows = ArrayList<SplitRow>()
  var icons = 0
  for (c in cases(dir, edge, inset = false, fgAll = false)) {
    val src = c.src
    if (src.size != edge * edge) continue
    icons++
    val r = MonochromeMask.build(src, edge, edge, c.plateLayer, c.under)
    val mask = r.mask ?: continue

    var l = edge; var t = edge; var rr = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if ((src[y * edge + x] ushr 24) and 0xff != 0) {
      if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > b) b = y
    }
    if (rr < l) continue
    val boxN = (rr - l + 1).toLong() * (b - t + 1)

    // 第一遍：逐族计数 + 每族记下"出现最多的那个精确色"当代表（ΔE 要拿真色问，不能拿族号问）
    val cnt = HashMap<Int, Int>()
    val exact = HashMap<Int, HashMap<Int, Int>>()
    for (y in t..b) for (x in l..rr) {
      val p = src[y * edge + x]
      if (((p ushr 24) and 0xff) != 255) continue
      val k = famOf(p)
      cnt[k] = (cnt[k] ?: 0) + 1
      val m = exact.getOrPut(k) { HashMap() }
      val v = p and 0x00FFFFFF
      m[v] = (m[v] ?: 0) + 1
    }
    val repExact = HashMap<Int, Int>()
    for ((k, m) in exact) repExact[k] = m.maxByOrNull { it.value }?.key ?: -1
    val cand = HashSet<Int>()
    for ((k, v) in cnt) if (v * 100L >= boxN) cand += k
    if (cand.isEmpty()) continue

    // 第二遍：同族四邻连通，lab=1 敞开 / 2 被围住
    val lab = ByteArray(edge * edge)
    val q = IntArray(edge * edge)
    val open = HashMap<Int, Int>()
    val encl = HashMap<Int, Int>()
    val bigN = HashMap<Int, Int>()
    val sides = HashMap<Int, Int>()          // 这一族贴内容框几条边（bit0 左 bit1 右 bit2 上 bit3 下）
    for (y in t..b) for (x in l..rr) {
      val i0 = y * edge + x
      val p0 = src[i0]
      if (((p0 ushr 24) and 0xff) != 255) continue
      val k = famOf(p0)
      if (!cand.contains(k) || lab[i0] != 0.toByte()) continue
      var head = 0; var tail = 0
      q[tail++] = i0; lab[i0] = 1
      var n = 0; var touches = false; var sb = 0
      val comp = ArrayList<Int>()
      while (head < tail) {
        val j = q[head++]
        val jy = j / edge; val jx = j - jy * edge
        comp += j; n++
        if (jx == l) sb = sb or 1
        if (jx == rr) sb = sb or 2
        if (jy == t) sb = sb or 4
        if (jy == b) sb = sb or 8
        if (jx == l || jx == rr || jy == t || jy == b) touches = true
        if (jx > l) { val d = j - 1; if (lab[d] == 0.toByte() && famOf(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jx < rr) { val d = j + 1; if (lab[d] == 0.toByte() && famOf(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jy > t) { val d = j - edge; if (lab[d] == 0.toByte() && famOf(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jy < b) { val d = j + edge; if (lab[d] == 0.toByte() && famOf(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
      }
      if (!touches) {
        for (j in comp) lab[j] = 2
        encl[k] = (encl[k] ?: 0) + n
        if (n > (bigN[k] ?: 0)) bigN[k] = n
      } else { open[k] = (open[k] ?: 0) + n; sides[k] = (sides[k] ?: 0) or sb }
    }

    val bw = rr - l + 1; val bh = b - t + 1
    for ((k, en) in encl) {
      val op = open[k] ?: 0
      if (op * 100L < boxN * minE || en * 100L < boxN * minI) continue
      var ink = 0; var air = 0; var sum = 0L
      var islL = 0L; var islN = 0
      for (i in 0 until edge * edge) {
        if (lab[i] != 2.toByte() || famOf(src[i]) != k) continue
        val a = (mask[i] ushr 24) and 0xff
        sum += a
        if (a >= 128) ink++
        if (a < 32) air++
        islL += labOf(src[i] and 0x00FFFFFF)[0].toLong(); islN++
      }
      // 圈：岛四邻里不属于该族的实心格。⚠ **每一格归到最近的"主族"**（≥1% 框的那些族），
      //   而不是自己当一个族 —— 第一版直接数精确色，淘宝的圈数出来是白字与橙底之间那 1~2px 的
      //   混色 `#FFE5E1`（ΔE 到白只有 12），于是"圈是橙底"这件事被混色环遮掉了。
      //   离任何主族都 >40 的格算"混色"，不进圈。
      val majors = ArrayList<Pair<Int, Int>>()               // (族号, 代表色)
      for (k2 in cand) majors += k2 to (repExact[k2] ?: -1)
      val ring = HashMap<Int, Int>()
      var ringInk = 0; var ringAir = 0; var ringAll = 0
      for (i in 0 until edge * edge) {
        if (lab[i] != 2.toByte() || famOf(src[i]) != k) continue
        val y = i / edge; val x = i - y * edge
        for (d in 0 until 4) {
          val nx = x + when (d) { 0 -> -1; 1 -> 1; else -> 0 }
          val ny = y + when (d) { 2 -> -1; 3 -> 1; else -> 0 }
          if (nx < l || nx > rr || ny < t || ny > b) continue
          val j = ny * edge + nx
          val rf = famOf(src[j])
          if (rf == k) continue
          if (((src[j] ushr 24) and 0xff) != 255) continue
          val cj = src[j] and 0x00FFFFFF
          var best = -1; var bestD = 999.0
          for ((f2, rep) in majors) {
            if (f2 == k) continue
            val dd = deltaE76(cj, rep)
            if (dd < bestD) { bestD = dd; best = f2 }
          }
          if (best < 0 || bestD > 40.0) continue
          ring[best] = (ring[best] ?: 0) + 1
          val a = (mask[j] ushr 24) and 0xff
          ringAll++
          if (a >= 128) ringInk++
          if (a < 32) ringAir++
        }
      }
      val top = ring.maxByOrNull { it.value }
      val rc = top?.key ?: -1
      val rcRep = repExact[rc] ?: -1
      var famOpen = 0
      if (rc >= 0) for ((k2, v) in open) {
        if (k2 == k) continue
        if (deltaE76(rcRep, repExact[k2] ?: -1) <= 40.0) famOpen += v      // 按族合，不按 4 位键
      }
      // 圈那一族**自己**在成品掩码上交成了什么（整族，不只是贴着岛的那一圈）——
      // 这才是"圈是底还是墨"的读数；贴不贴边只用来区分"盘"与"贴在边上的主体"。
      var famInk = 0; var famAll = 0
      if (rc >= 0) for (i in 0 until edge * edge) {
        if (((src[i] ushr 24) and 0xff) != 255 || famOf(src[i]) != rc) continue
        famAll++
        if (((mask[i] ushr 24) and 0xff) >= 128) famInk++
      }
      // **这把刀上屏之前唯一还悬着的那件事**：生产里满幅支的极性问的是 `ground.iL > ground.gL`
      //   （墨那一半比底那一半亮吗）。台子读不到 `ground`，但成品掩码就是它的外化 ——
      //   交了空(α<32)的格 = 底那一侧，交了墨(α≥128)的格 = 墨那一侧。
      //   把岛从"底"挪进"墨"，重算两边的平均 L，看这一句成不成立。
      var gS = 0L; var gC = 0; var iS = 0L; var iC = 0
      // 第二个反事实（2026-11-06）：不是"把岛挪进墨"，而是**整族退出底** —— 敞开的那块（促销带）
      // 和被围的那块（字形）**一起**算墨。上一列证明"只挪岛"救不了淘宝：底那一侧剩下的还是白带自己。
      // 那么真正的病因（档里 2026-11-06 那条换的问题）就是"白带也算一族底"这一步，动它才有疗效可谈。
      var gS2 = 0L; var gC2 = 0; var iS2 = 0L; var iC2 = 0
      var famN = 0; var famInkN = 0; var famAirN = 0
      for (y in t..b) for (x in l..rr) {
        val i = y * edge + x
        if (((src[i] ushr 24) and 0xff) != 255) continue
        val a = (mask[i] ushr 24) and 0xff
        val isIsl = lab[i] == 2.toByte() && famOf(src[i]) == k
        val isFam = famOf(src[i]) == k
        val ll = labOf(src[i] and 0x00FFFFFF)[0].toLong()
        if (isFam) {
          famN++
          if (a >= 128) famInkN++ else if (a < 32) famAirN++
          iS2 += ll; iC2++
        } else if (a < 32) { gS2 += ll; gC2++ } else if (a >= 128) { iS2 += ll; iC2++ }
        if (isIsl) { iS += ll; iC++ }
        else if (a < 32) { gS += ll; gC++ }
        else if (a >= 128) { iS += ll; iC++ }
      }
      val gL0 = if (gC + islN == 0) 0.0 else (gS + islL).toDouble() / (gC + islN)
      val iL0 = if (iC - islN <= 0) 0.0 else (iS - islL).toDouble() / (iC - islN)
      val gL1 = if (gC == 0) 0.0 else gS.toDouble() / gC
      val iL1 = if (iC == 0) 0.0 else iS.toDouble() / iC
      val gL2 = if (gC2 == 0) 0.0 else gS2.toDouble() / gC2
      val iL2 = if (iC2 == 0) 0.0 else iS2.toDouble() / iC2
      rows += SplitRow(c.file.name, repExact[k] ?: -1, k, op, en, bigN[k] ?: 0, bw, bh,
        ink * 100 / en, air * 100 / en, sum / en, rcRep, top?.value ?: 0, famOpen,
        ringInk, ringAir, ringAll, famInk, famAll, gL0, iL0, gL1, iL1,
        Integer.bitCount(sides[k] ?: 0), Integer.bitCount(if (rc >= 0) sides[rc] ?: 0 else 0),
        famInkN, famAirN, famN, gL2, iL2)
    }
  }

  println("扫了 $icons 张；命中「同族跨内外」${rows.size} 条（同族：敞开≥${minE}% 框 且 围住≥${minI}% 框）")
  for (x in rows.sortedByDescending { it.enclosed }) {
    println(String.format("%-34s 岛#%06X 围%5.1f%% 现墨%3d%% | 圈#%06X 圈族贴边%5.1f%% ΔE=%5.1f ⇒ %-26s 极性 %.1f/%.1f → %.1f/%.1f %s 贴边条数 岛%d 圈%d │ 全族(墨%d 空%d) %.1f/%.1f → %.1f/%.1f %s",
      x.name.removeSuffix(".png").take(34), x.color, x.encPct, x.inkPct,
      x.ringColor, x.famOpenPct, x.dE, x.verdict,
      x.gL0, x.iL0, x.gL1, x.iL1, if (x.flips) "会翻" else "不翻", x.islSides, x.ringSides,
      x.famInkN, x.famAirN, x.gL0, x.iL0, x.gL2, x.iL2, if (x.flips2) "会翻" else "不翻"))
  }
  val fire = rows.filter { it.verdict.startsWith("圈贴边且不同族") }
  println()
  val names = fire.joinToString(", ") { it.name.removeSuffix(".png").take(26) }
  println("候选判据开火的：${fire.size} / ${rows.size} 条 —— $names（空名单 = 一条都没开火）")
  println("开火且极性真的会翻（`iL>gL` 由假变真）的：${fire.count { it.flips }} 条")
  // 疗效那一列问的是**这个动作**：整族退出底。只有 `族空 > 0`（这一族现在确实被交成了底）才谈得上有效。
  val demote = rows.filter { it.flips2 }
  println("整族退出底 ⇒ 极性会翻的：${demote.size} / ${rows.size} 条 —— " +
    (if (demote.isEmpty()) "（0 条 = 这个动作在整份语料上没有疗效）"
    else demote.joinToString(", ") { it.name.removeSuffix(".png").take(26) }))
  println("其中『这一族现在多半被交成底』（实心格里 ≥70% 交了空）的：${demote.count { it.famAirN * 10 >= it.famTot * 7 }} 条")
  println("其中判据开火的：${demote.count { it.verdict.startsWith("圈贴边且不同族") }} 条")
}

private fun famOf(p: Int): Int =
  ((((p shr 16) and 0xff) shr 4) shl 8) or ((((p shr 8) and 0xff) shr 4) shl 4) or ((p and 0xff) shr 4)

private class SplitRow(
  val name: String, val color: Int, val fam: Int,
  val open: Int, val enclosed: Int, val big: Int, val bw: Int, val bh: Int,
  val inkPct: Int, val airPct: Int, val meanA: Long,
  /** 围住岛的那一圈里最多的那一族（代表色；-1 = 一圈没数到实心格）。 */
  val ringColor: Int, val ringN: Int,
  /** 主圈族自己贴内容框边的格数。 */
  val famOpen: Int,
  val ringInk: Int, val ringAir: Int, val ringAll: Int,
  /** 主圈族**整族**在成品掩码上的交付（墨格数 / 该族实心格数）—— "圈是底还是墨"就读在这里。 */
  val famInk: Int, val famAll: Int,
  /** 极性那一句（`墨侧平均 L > 底侧平均 L`）在"岛还算底"与"岛挪进墨"两种算法下的读数。 */
  val gL0: Double, val iL0: Double, val gL1: Double, val iL1: Double,
  /** 新轴（2026-11-05 深夜）：岛那一族 / 圈那一族**各贴内容框几条边**。
   *  盘是铺满整张的东西 ⇒ 4 条；促销带只占下角 ⇒ 1~2 条。 */
  val islSides: Int, val ringSides: Int,
  /** 整族退出底那一列：这一族自己（敞开 + 围住）现在交付成墨 / 成空的格数、总实心格数，与挪完之后的两边均值。 */
  val famInkN: Int, val famAirN: Int, val famTot: Int,
  val gL2: Double, val iL2: Double,
) {
  val boxN get() = bw.toLong() * bh
  val encPct get() = enclosed * 100.0 / boxN
  val ringOnEdge get() = famOpen.toLong() * 100 >= boxN
  val dE get() = if (ringColor < 0) -1.0 else deltaE76(color, ringColor)
  val ringInkPct get() = if (ringAll == 0) -1 else ringInk * 100 / ringAll
  val famInkPct get() = if (famAll == 0) -1 else famInk * 100 / famAll
  val famOpenPct get() = famOpen * 100.0 / boxN
  /** 候选判据（只问源，不问成品）：**圈那一族自己也贴内容框边（≥1% 框）且与岛 ΔE>40**
   *  ⇒ 圈是铺在整张下面的盘、岛不是盘的深浅 ⇒ 岛是坐在盘上的主体。 */
  val verdict get() = when {
    ringColor < 0 -> "圈没数到（不动）"
    dE <= 40.0 -> "圈与岛同族 ⇒ 盘的深浅（不动）"
    !ringOnEdge -> "圈不贴边 ⇒ 圈是主体，岛是洞（不动）"
    else -> "圈贴边且不同族 ⇒ 岛是主体（开火）"
  }
  /** 岛从底挪进墨之后，`iL > gL` 这一句由不成立变成立吗 —— 这一条才是"能不能真的修好"。 */
  val flips get() = iL0 <= gL0 && iL1 > gL1
  /** 同上，但动的是**整族**（促销带 + 字形一起退出底）。上一列量的是"只挪岛"，档里已经证明它疗效为零。 */
  val flips2 get() = iL0 <= gL0 && iL2 > gL2
}

private fun deltaE76(a: Int, b: Int): Double {
  if (a < 0 || b < 0) return 999.0
  val la = labOf(a); val lb = labOf(b)
  return sqrt((la[0] - lb[0]) * (la[0] - lb[0]) +
    (la[1] - lb[1]) * (la[1] - lb[1]) + (la[2] - lb[2]) * (la[2] - lb[2]))
}

private fun labOf(p: Int): DoubleArray {
  fun s(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
  val r = s((p shr 16) and 0xff); val g = s((p shr 8) and 0xff); val b = s(p and 0xff)
  var x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
  var y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
  var z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
  fun f(v: Double) = if (v > 0.008856) Math.cbrt(v) else 7.787 * v + 16.0 / 116.0
  x = f(x); y = f(y); z = f(z)
  return doubleArrayOf(116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z))
}
