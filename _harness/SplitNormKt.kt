package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * **探针（只量不改）**：「这块色是不是被一条**直线**切在盘上」——岛的边界法线方向集中度。
 *
 * 为什么是这一根轴（接 KNOWN-LIMITS 末尾 📌 那条）：
 *  `SplitColorKt` 已经把「同族跨内外」这一族数出来（10 条读数进档），候选判据
 *  「圈族贴边 ≥1% + ΔE>40」**射程对、疗效零**（淘宝 `极性 99.8/57.7 → 99.8/67.6 不翻`）；
 *  下一根「一族贴内容框几条边」当场被自己的数否掉（淘宝 `岛3 圈2` == 转转 `岛3 圈2`，没有量程）。
 *  没量过的只剩**几何**这一根：淘宝的白带与橙盘之间压着一条黄缎带 —— 那是**层序**在单图层位图上
 *  留下的唯一痕迹，形如一段**近似直线**的边界；转转的箭头四周是连续的熊身，边界是**包围式**弯轮廓。
 *  颜色尺撞过八次墙，这一根不问"哪块是底"，只问"边界直不直"。
 *
 * 口径：岛/圈划分**逐字复用** [SplitColorKt]（族 = RGB 各降 4 位；内容框 = α>0 外接框；只数 α=255；
 *  同族四邻连通，碰框边 = 敞开、否则 = 岛；命中 = 敞开 ≥[--minE=1]% 且 围住 ≥[--minI=2]% 框；
 *  圈 = 岛四邻里不属于该族的实心格，每格归到**最近的**主族（ΔE≤40），最多那族 = 主圈族）。
 *  它的 10 条输出口径不在这个文件里，本文件不改动它。
 *
 * 新轴（本文件唯一新增的读数）：沿岛的边界向外 1..[--band=3]px 取环带（8 邻 BFS 的切比雪夫深度），
 *  带内**归到主圈族**的那些格，逐格取"指向岛"的方向 = 半径 [--R=4] 窗内岛格重心相对该格的偏移
 *  （岛指示场在那一格的梯度，等价写法），量化成 **8 个 45° 扇区**并保留正反：
 *  - `峰` = 最大扇区占比%（方向完全散开时的均匀下界是 12.5%）；
 *  - `轴` = 4 个 90° **轴**扇区的最大占比%（把相反方向并起来：一条直线的两端算同一个轴，均匀=25%）；
 *  - `熵` = 归一化方向熵（0 = 全朝一个方向，1 = 完全散开）；`n` = 参与计数的格数。
 *  同一条尺另外量两处作对照：
 *  - `带1px` = 只取紧贴岛的第 1px（切痕是近场现象，远的会被岛的整体形状带偏）；
 *  - `开侧带` = 把岛换成**该族敞开的那一块**（淘宝 = 促销白带本体，转转 = 铺到边的奶油底）再量一次，
 *    圈族**重新一遍自动取**（敞开那块旁边压着的主圈族可能与岛的圈族不是同一族 —— 淘宝白带旁边是
 *    黄缎带、橙盘要隔一条缎带才够得着；第一版沿用了岛那侧的 `rc`，那一列直接 `n=0`，是**没牙的量具**）。
 *  `--calib` 打三块合成形状（半平面 = 纯直线切痕 / 长条 = 笔画直边 / 圆盘 = 包围轮廓），
 *  先给出这根尺自己的量程在哪，再看语料的数落在哪里。
 *  `--dump` 把每条命中的 岛/环带/参与计数的格 画成 PNG（`_snorm_<名字>.png`），用眼睛验量具有没有牙。
 *
 * ⚠ 这台子读的是**岛自己的轮廓形状**，不是"谁压着谁"这件事本身 —— 汉字笔画的边本来就横平竖直，
 *  `开侧带` 那一列就是为这一处对照放的。别看错主列。
 *
 * 跑法：`sh run.sh harness.SplitNormKtKt <图标目录> [--edge=384] [--minE=1] [--minI=2] [--R=4] [--band=3] [--only=子串] [--calib] [--hist]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  if (!dir.isDirectory) { println("!! 目录不存在：${dir.path}"); exitProcess(2) }
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=") ?: "384").toInt()
  val minE = (args.firstOrNull { it.startsWith("--minE=") }?.substringAfter("=") ?: "1").toInt()
  val minI = (args.firstOrNull { it.startsWith("--minI=") }?.substringAfter("=") ?: "2").toInt()
  val rad = (args.firstOrNull { it.startsWith("--R=") }?.substringAfter("=") ?: "4").toInt()
  val band = (args.firstOrNull { it.startsWith("--band=") }?.substringAfter("=") ?: "3").toInt()
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=") ?: ""
  val dump = args.any { it == "--dump" }

  if (args.any { it == "--calib" }) calib(rad, band)

  val rows = ArrayList<SnormRow>()
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

    // ===== 以下三遍（族计数 / 四邻连通标敞开与围住 / 圈格归到最近主族）与 SplitColorKt 同口径 =====
    val cnt = HashMap<Int, Int>()
    val exact = HashMap<Int, HashMap<Int, Int>>()
    for (y in t..b) for (x in l..rr) {
      val p = src[y * edge + x]
      if (((p ushr 24) and 0xff) != 255) continue
      val k = snormFam(p)
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

    val lab = ByteArray(edge * edge)
    val q = IntArray(edge * edge)
    val open = HashMap<Int, Int>()
    val encl = HashMap<Int, Int>()
    for (y in t..b) for (x in l..rr) {
      val i0 = y * edge + x
      val p0 = src[i0]
      if (((p0 ushr 24) and 0xff) != 255) continue
      val k = snormFam(p0)
      if (!cand.contains(k) || lab[i0] != 0.toByte()) continue
      var head = 0; var tail = 0
      q[tail++] = i0; lab[i0] = 1
      var n = 0; var touches = false
      val comp = ArrayList<Int>()
      while (head < tail) {
        val j = q[head++]
        val jy = j / edge; val jx = j - jy * edge
        comp += j; n++
        if (jx == l || jx == rr || jy == t || jy == b) touches = true
        if (jx > l) { val d = j - 1; if (lab[d] == 0.toByte() && snormFam(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jx < rr) { val d = j + 1; if (lab[d] == 0.toByte() && snormFam(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jy > t) { val d = j - edge; if (lab[d] == 0.toByte() && snormFam(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
        if (jy < b) { val d = j + edge; if (lab[d] == 0.toByte() && snormFam(src[d]) == k) { lab[d] = 1; q[tail++] = d } }
      }
      if (!touches) {
        for (j in comp) lab[j] = 2
        encl[k] = (encl[k] ?: 0) + n
      } else open[k] = (open[k] ?: 0) + n
    }

    val majors = ArrayList<Pair<Int, Int>>()
    for (k2 in cand) majors += k2 to (repExact[k2] ?: -1)

    for ((k, en) in encl) {
      val op = open[k] ?: 0
      if (op * 100L < boxN * minE || en * 100L < boxN * minI) continue
      if (only.isNotEmpty() && !c.file.name.contains(only)) continue

      val islFlag = BooleanArray(edge * edge) { lab[it] == 2.toByte() && snormFam(src[it]) == k }
      val opnFlag = BooleanArray(edge * edge) { lab[it] == 1.toByte() && snormFam(src[it]) == k }
      val rc = snormRing(islFlag, src, k, l, t, rr, b, edge, majors).maxByOrNull { it.value }?.key ?: -1
      var ink = 0
      for (i in 0 until edge * edge) {
        if (lab[i] != 2.toByte() || snormFam(src[i]) != k) continue
        if (((mask[i] ushr 24) and 0xff) >= 128) ink++
      }

      // ===== 新轴 =====
      val rcOpen = snormRing(opnFlag, src, k, l, t, rr, b, edge, majors).maxByOrNull { it.value }?.key ?: -1
      val main = snormDir(islFlag, src, rc, k, l, t, rr, b, edge, rad, band, majors)
      val inner = snormDir(islFlag, src, rc, k, l, t, rr, b, edge, rad, 1, majors)
      val openSide = snormDir(opnFlag, src, rcOpen, k, l, t, rr, b, edge, rad, band, majors)
      if (dump) snormDump(c.file.name, islFlag, opnFlag, src, main, edge)
      rows += SnormRow(c.file.name, repExact[k] ?: -1, en, boxN, ink,
        repExact[rc] ?: -1, rc, repExact[rcOpen] ?: -1, rcOpen, main, inner, openSide)
    }
  }

  println("扫了 $icons 张；命中「同族跨内外」${rows.size} 条（同族：敞开≥$minE% 框 且 围住≥$minI% 框）")
  println("尺 = 岛边界外 1..$band px 环带里主圈族格子的法线方向（8 个 45° 扇区，保留正反；R=$rad 窗内岛格重心偏移）")
  println("峰 = 最大扇区占比%（方向全散时均匀下界 12.5）｜轴 = 4 个 90° 轴扇区最大占比%（均匀 25，直线两端同轴）｜熵 = 归一化方向熵（0 一个方向 / 1 全散）｜n = 计数格数")
  for (x in rows.sortedByDescending { it.encPct }) println(x.line())
  println()
  println("按「岛圈带 峰」从高到低（看这根尺有没有量程用这一张）：")
  for (x in rows.sortedByDescending { it.main.peak }) println("  " + String.format(
    "%6.1f%% 轴%6.1f%% 熵%.3f n=%5d  %-34s 岛#%06X 圈#%06X ΔE=%5.1f  开侧峰%6.1f%%",
    x.main.peak, x.main.axis, x.main.ent, x.main.n, x.name.removeSuffix(".png").take(34),
    x.islColor, x.ringColor, x.dE, x.openSide.peak))
  println()
  println("按「开侧带 峰」从高到低（这一列量的才是**缎带那条边**：淘宝的白带 ↔ 黄缎带）：")
  for (x in rows.sortedByDescending { it.openSide.peak }) println("  " + String.format(
    "%6.1f%% 轴%6.1f%% 熵%.3f n=%5d  %-34s 开侧圈#%06X  ΔE(岛,开侧圈)=%5.1f  岛圈带峰%6.1f%%",
    x.openSide.peak, x.openSide.axis, x.openSide.ent, x.openSide.n, x.name.removeSuffix(".png").take(34),
    x.openRingColor, if (x.openRingColor < 0) -1.0 else snormDeltaE(x.islColor, x.openRingColor), x.main.peak))
  println("量程摊开（同一根尺在两处边界上的读数；两个样本之间不许拍线，先看这一串有没有断点）：")
  println("  岛圈带峰 = " + rows.map { String.format("%.1f", it.main.peak) }.sorted().joinToString(" "))
  println("  开侧带峰 = " + rows.map { String.format("%.1f", it.openSide.peak) }.sorted().joinToString(" "))
  println("  岛圈带熵 = " + rows.map { String.format("%.3f", it.main.ent) }.sorted().joinToString(" "))
  if (args.any { it == "--hist" }) for (x in rows) {
    println()
    println("${x.name}  岛#%06X 圈#%06X".format(x.islColor, x.ringColor))
    println("  扇区（8×45°，0=+x 向右，顺时针）  岛圈带 " + x.main.histLine() + " n=" + x.main.n)
    println("                                岛带1px " + x.inner.histLine() + " n=" + x.inner.n)
    println("                                开侧带 " + x.openSide.histLine() + " n=" + x.openSide.n)
  }
}

/** 一条命中：前 6 个字段沿用 SplitColorKt 的口径，后面是本文件新增的方向读数。 */
private class SnormRow(
  val name: String, val islColor: Int, val enc: Int, val boxN: Long, val ink: Int,
  val ringColor: Int, val ringFam: Int,
  /** 敞开那一块的圈族（淘宝 = 压在白带与橙盘之间的那条缎带）—— 与岛的圈族可以不是同一族。 */
  val openRingColor: Int, val openRingFam: Int,
  val main: SnormBand, val inner: SnormBand, val openSide: SnormBand,
) {
  val encPct get() = enc * 100.0 / boxN
  val dE get() = if (ringColor < 0) -1.0 else snormDeltaE(islColor, ringColor)
  fun line(): String = String.format(
    "%-34s 岛#%06X 围%5.1f%% 现墨%3d%% | 圈#%06X ΔE=%5.1f | 岛圈带 n=%5d 峰%5.1f%% 轴%5.1f%% 熵%.3f | 带1px n=%5d 峰%5.1f%% 熵%.3f | 开侧(圈#%06X) n=%5d 峰%5.1f%% 熵%.3f",
    name.removeSuffix(".png").take(34), islColor, encPct, ink * 100 / if (enc == 0) 1 else enc,
    ringColor, dE, main.n, main.peak, main.axis, main.ent,
    inner.n, inner.peak, inner.ent, openRingColor, openSide.n, openSide.peak, openSide.ent)
}

/** 一处环带的方向分布。`took` = 真进了分布的格，`bandAll` = 环带里属于主圈族的全部格（给 --dump 用）。 */
private class SnormBand(val sec: IntArray, val n: Int, val took: IntArray, val bandAll: IntArray) {
  val peak get() = if (n == 0) -1.0 else sec.maxOrNull()!!.toDouble() * 100.0 / n
  val axis get() = if (n == 0) -1.0 else {
    var mx = 0
    for (i in 0 until 4) { val v = sec[i] + sec[i + 4]; if (v > mx) mx = v }
    mx.toDouble() * 100.0 / n
  }
  val ent get() = if (n == 0) -1.0 else {
    var h = 0.0
    for (v in sec) if (v > 0) { val p = v.toDouble() / n; h -= p * ln(p) / ln(2.0) }
    h / 3.0
  }
  fun histLine(): String = sec.joinToString(" ") { String.format("%5d", it) }
}

/**
 * 沿 `flag` 那块色的边界向外取 1..`band`px 环带（8 邻 BFS 的切比雪夫深度），带内**归到主圈族 `rc`**
 * 的实心格逐格取"指向 `flag`"的方向并量化成 8 个 45° 扇区。
 * 方向 = 半径 `rad` 窗内 `flag` 格的重心相对该格的偏移；偏移模长 <1px 的格（被岛包住/窗里没有岛）丢掉。
 */
private fun snormDir(flag: BooleanArray, src: IntArray, rc: Int, fam: Int,
  l: Int, t: Int, rr: Int, b: Int, edge: Int, rad: Int, band: Int,
  majors: List<Pair<Int, Int>>): SnormBand {
  val sec = IntArray(8)
  val took = ArrayList<Int>(4096)
  val bandAll = ArrayList<Int>(4096)
  var n = 0
  val w = rr - l + 1; val h = b - t + 1
  // 三条前缀和（格数 / Σx / Σy，只在内容框内）⇒ 任意窗内岛格重心 O(1)。
  val ps = LongArray((w + 1) * (h + 1))
  val pxs = LongArray((w + 1) * (h + 1))
  val pys = LongArray((w + 1) * (h + 1))
  for (y in 0 until h) for (x in 0 until w) {
    val o = (y + 1) * (w + 1) + (x + 1)
    val up = y * (w + 1) + (x + 1)
    val lf = (y + 1) * (w + 1) + x
    val ul = y * (w + 1) + x
    val v = if (flag[(t + y) * edge + (l + x)]) 1 else 0
    ps[o] = ps[up] + ps[lf] - ps[ul] + v
    pxs[o] = pxs[up] + pxs[lf] - pxs[ul] + v.toLong() * x
    pys[o] = pys[up] + pys[lf] - pys[ul] + v.toLong() * y
  }
  fun sum(a: LongArray, x0: Int, y0: Int, x1: Int, y1: Int): Long {
    val X0 = x0.coerceIn(0, w); val X1 = (x1 + 1).coerceIn(0, w)
    val Y0 = y0.coerceIn(0, h); val Y1 = (y1 + 1).coerceIn(0, h)
    if (X1 <= X0 || Y1 <= Y0) return 0L
    return a[Y1 * (w + 1) + X1] - a[Y0 * (w + 1) + X1] - a[Y1 * (w + 1) + X0] + a[Y0 * (w + 1) + X0]
  }
  val dist = IntArray(edge * edge) { -1 }
  val qq = IntArray(edge * edge); var head = 0; var tail = 0
  for (y in t..b) for (x in l..rr) if (flag[y * edge + x]) { dist[y * edge + x] = 0; qq[tail++] = y * edge + x }
  while (head < tail) {
    val j = qq[head++]
    val jy = j / edge; val jx = j - jy * edge
    if (dist[j] >= band) continue
    for (dy in -1..1) for (dx in -1..1) {
      if (dx == 0 && dy == 0) continue
      val nx = jx + dx; val ny = jy + dy
      if (nx < l || nx > rr || ny < t || ny > b) continue
      val d = ny * edge + nx
      if (dist[d] != -1) continue
      dist[d] = dist[j] + 1; qq[tail++] = d
    }
  }
  for (y in t..b) for (x in l..rr) {
    val i = y * edge + x
    val dd = dist[i]
    if (dd < 1 || dd > band) continue
    if (((src[i] ushr 24) and 0xff) != 255) continue
    val own = snormFam(src[i])
    if (own == fam) continue
    if (rc >= 0) {                                    // 只数归到主圈族那一族的那些格；混色格不进分布
      if (snormAttrib(src[i] and 0x00FFFFFF, fam, majors) != rc) continue
    }
    bandAll += i
    val lx = x - l; val ly = y - t
    val cc = sum(ps, lx - rad, ly - rad, lx + rad, ly + rad)
    if (cc == 0L) continue
    val ox = sum(pxs, lx - rad, ly - rad, lx + rad, ly + rad).toDouble() / cc - lx
    val oy = sum(pys, lx - rad, ly - rad, lx + rad, ly + rad).toDouble() / cc - ly
    val m = sqrt(ox * ox + oy * oy)
    if (m < 1.0) continue                              // 窗被岛填满 ⇒ 这一格给不出边界方向
    var ang = atan2(oy, ox)
    if (ang < 0) ang += 2.0 * kotlin.math.PI
    sec[((ang / (2.0 * kotlin.math.PI)) * 8).toInt().coerceIn(0, 7)]++
    took += i
    n++
  }
  return SnormBand(sec, n, took.toIntArray(), bandAll.toIntArray())
}

/** 一格的色归到哪个主族（ΔE≤40 且不是 `fam` 那族），否则 -1。与 SplitColorKt 的圈那一步一致。 */
private fun snormAttrib(rgb: Int, fam: Int, majors: List<Pair<Int, Int>>): Int {
  var best = -1; var bestD = 999.0
  for ((f2, rep) in majors) {
    if (f2 == fam) continue
    val g = snormDeltaE(rgb, rep)
    if (g < bestD) { bestD = g; best = f2 }
  }
  return if (bestD > 40.0) -1 else best
}

/**
 * 圈 = `region` 那块色四邻里不属于 `fam` 的实心格，每格归到**最近的**主族（ΔE≤40）。
 * 逐字是 [SplitColorKt] 的那一步，只是把"哪一块是岛"换成传进来的 `region`，
 * 这样岛的圈族与"敞开那一块"的圈族能用同一套算法各数一遍。
 */
private fun snormRing(region: BooleanArray, src: IntArray, fam: Int,
  l: Int, t: Int, rr: Int, b: Int, edge: Int, majors: List<Pair<Int, Int>>): HashMap<Int, Int> {
  val ring = HashMap<Int, Int>()
  for (i in 0 until edge * edge) {
    if (!region[i]) continue
    val y = i / edge; val x = i - y * edge
    for (d in 0 until 4) {
      val nx = x + when (d) { 0 -> -1; 1 -> 1; else -> 0 }
      val ny = y + when (d) { 2 -> -1; 3 -> 1; else -> 0 }
      if (nx < l || nx > rr || ny < t || ny > b) continue
      val j = ny * edge + nx
      if (snormFam(src[j]) == fam) continue
      if (((src[j] ushr 24) and 0xff) != 255) continue
      val pair = snormAttrib(src[j] and 0x00FFFFFF, fam, majors)
      if (pair < 0) continue
      ring[pair] = (ring[pair] ?: 0) + 1
    }
  }
  return ring
}

/** `--dump`：把岛画成品红、敞开那块画蓝、主列真正参与计数的环带格画绿、在环带但被丢掉方向模长的画黄。 */
private fun snormDump(name: String, isl: BooleanArray, opn: BooleanArray, src: IntArray, main: SnormBand, edge: Int) {
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
  val picked = BooleanArray(edge * edge)
  for (i in main.took) picked[i] = true
  val inBand = BooleanArray(edge * edge)
  for (i in main.bandAll) inBand[i] = true
  for (y in 0 until edge) for (x in 0 until edge) {
    val i = y * edge + x
    img.setRGB(x, y, when {
      picked[i] -> 0x00CC00                              // 进了主列方向分布的格
      inBand[i] -> 0xFFFF66                              // 环带里属于主圈族、但方向模长太短被丢
      isl[i] -> 0xFF00CC                                 // 岛（被围住的那一块）
      opn[i] -> 0x6699FF                                 // 同族敞开的那一块
      (src[i] ushr 24) and 0xff == 0 -> 0x333333         // 透明
      else -> 0xFFFFFF
    })
  }
  ImageIO.write(img, "png", File("_snorm_" + name.removeSuffix(".png") + ".png"))
}

/** 合成标定：三块已知形状给这根尺自己的量程（半平面=直线切痕 / 长条=笔画直边 / 圆盘=包围轮廓）。 */
private fun calib(rad: Int, band: Int) {
  println("--calib 合成标定（R=$rad band=$band，均匀下界：8 扇区峰 12.5% / 4 轴扇区 25% / 熵 1.000）")
  val e = 200
  for ((nm, f) in listOf<Pair<String, (Int, Int) -> Boolean>>(
    "半平面（一条直线切开整张）" to { x, y -> x >= e / 2 },
    "长条 8x120（笔画那样的直边）" to { x, y -> x in 96..103 && y in 40..160 },
    "方块 90x90（两块直边）" to { x, y -> x in 55..145 && y in 55..145 },
    "圆盘 r=45（包围轮廓）" to { x, y -> val dx = x - 100; val dy = y - 100; dx * dx + dy * dy <= 45 * 45 },
    "斜线带 45°（真直线切痕，斜的）" to { x, y -> x + y >= 200 },
  )) {
    val src = IntArray(e * e) { 0xFFFFFFFF.toInt() }
    val flag = BooleanArray(e * e)
    for (y in 0 until e) for (x in 0 until e) if (f(x, y)) { flag[y * e + x] = true; src[y * e + x] = 0xFF000000.toInt() }
    val black = snormFam(0xFF000000.toInt()); val white = snormFam(0xFFFFFFFF.toInt())
    val ms = listOf<Pair<Int, Int>>(black to 0x000000, white to 0xFFFFFF)
    val bd = snormDir(flag, src, white, black, 0, 0, e - 1, e - 1, e, rad, band, ms)
    println("  %-34s n=%5d 峰%5.1f%% 轴%5.1f%% 熵%.3f  [%s]".format(nm, bd.n, bd.peak, bd.axis, bd.ent, bd.histLine()))
  }
  println()
}

private fun snormFam(p: Int): Int =
  ((((p shr 16) and 0xff) shr 4) shl 8) or ((((p shr 8) and 0xff) shr 4) shl 4) or ((p and 0xff) shr 4)

private fun snormDeltaE(a: Int, b: Int): Double {
  if (a < 0 || b < 0) return 999.0
  val la = snormLab(a); val lb = snormLab(b)
  return sqrt((la[0] - lb[0]) * (la[0] - lb[0]) + (la[1] - lb[1]) * (la[1] - lb[1]) + (la[2] - lb[2]) * (la[2] - lb[2]))
}

private fun snormLab(p: Int): DoubleArray {
  fun s(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
  val r = s((p shr 16) and 0xff); val g = s((p shr 8) and 0xff); val bl = s(p and 0xff)
  var x = (r * 0.4124 + g * 0.3576 + bl * 0.1805) / 0.95047
  var y = (r * 0.2126 + g * 0.7152 + bl * 0.0722)
  var z = (r * 0.0193 + g * 0.1192 + bl * 0.9505) / 1.08883
  fun f(v: Double) = if (v > 0.008856) Math.cbrt(v) else 7.787 * v + 16.0 / 116.0
  x = f(x); y = f(y); z = f(z)
  return doubleArrayOf(116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z))
}
