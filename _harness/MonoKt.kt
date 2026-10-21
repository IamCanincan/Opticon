package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **dep→浓度这一层，信息还剩多少**（只量不改）。
 *
 * 主人说"还是不够细节"。已经量死的那几层（发档地板、档距、块数、形状三维度）都不是凶手 ——
 * 候选 C（档距跟着源色差走）在 155 张上**逐字节全同**，证明 `assignLevels` 对易班根本没动手。
 * 剩下的嫌疑只有一个：**dep（0..255 那个标量）→ 交出去的 alpha** 这条标定。
 *
 * 这一层该满足的性质很弱、也很硬：**同一张图里，dep 更大的图形格，alpha 不许更小**。
 * dep 已经是模型自己算出来的"离它背后那块底有多远"，是它唯一肯承认的细节信号；
 * 如果连这个都排不出序，那"细节"两个字就没有可修的对象。
 *
 * 每张报四个数：
 *  · **ρ** = dep 与 alpha 的 Spearman 秩相关（1.00 = 完全同序）；
 *  · **逆% = 逆序对占所有对的比例**（ρ 太抽象，这个是直接可数的错）；
 *  · **顶平% = alpha 已经贴到 255 的那些图形格里，dep 还散开多少**（贴顶之后 dep 再大也没处显示 ——
 *    这就是"标定上限吃掉的细节"，也是 `实=` 那个锚的代价）；
 *  · **可用跨度** = 图形格 alpha 的 p5..p95 有多宽（细节要落在里面）。
 *
 * 跑法：`harness.MonoKt [--min=48] 目录 …`
 */
fun main(args: Array<String>) {
  val minEdge = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 48
  // 正对照先跑：**造一条必定该被看见的**同色违约，量不出来就说明这把尺子是钝的，
  //   后面那串 `jitterBad=0` 一条都不算证据。（2026-10-16：四份语料 jitterMax 只有 7，
  //   看着像阈值 40 在量程外 —— 但没有这个自检，"恒 0"永远分不清是干净还是瞎。）
  val st = jitterSelfTest()
  if (st == null) { println("!! 同色起伏自检**没报出违约** —— 这把尺子是钝的，本轮 jitterBad=0 不算通过"); kotlin.system.exitProcess(1) }
  println("自检：造一条同色跨 ${st.first} 档的违约 → 这一栏报 ${st.second}（阈值 40）✓")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") }.sortedBy { it.name }
    else listOf(f)
  }
  val rows = ArrayList<MRow>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < minEdge || h < minEdge) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = out?.mask ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val dist = fld(s, "dist") as ShortArray
    val distT = fld(s, "distT") as ShortArray
    // 只取**图形格**（非底、源实心）：底格 alpha 该为 0，把它混进来会让 ρ 好看而没用。
    // 两种单位各算一遍：
    //  · **格** = 逐像素 dep vs 逐像素 alpha；
    //  · **色** = 按精确源色归并（dep 取该色的最大值 = `cDep` 那张表真正用的那个数）。
    // ⚠ 交付 alpha 是**按色**给的（"同一种源色交付同一种浓度"是 `70b21bf` 之前刻意定的），
    //   所以逐像素那一栏天生会逆序 —— 它只能当参照，判"标定链有没有吃掉细节"要用按色那一栏。
    // ⚠ **两把尺子一起量。** 第一版只有 `dist` 那一把，而模型真正花出去的是 `distT`
    //   （见 MonochromeMask 里 `distT` 那段注释：`propagateField` 之后**只换最后写出去的那一个数**，
    //   `dist` 照旧当第二遍/标定/`cDep` 的输入）。拿 `dist` 去和 alpha 排同序，量的就不是
    //   模型承诺过的那条链 —— 所以我一开始那组"12 张 ρ色 0.33~0.89"里有多少是真的，
    //   得看 `ρ色T` 才知道。**别默认旧的这把是对的。**
    val dep = ArrayList<Int>(); val ink = ArrayList<Int>()
    val tdep = ArrayList<Int>()
    //  · **格** = 逐像素 dep vs 逐像素 alpha；
    //  · **色** = 按精确源色归并（dep 取该色的最大值 = `cDep` 那张表真正用的那个数）。
    // ⚠ 交付 alpha 是**按色**给的（"同一种源色交付同一种浓度"是 `70b21bf` 之前刻意定的），
    //   所以逐像素那一栏天生会逆序 —— 它只能当参照，判"标定链有没有吃掉细节"要用按色那一栏。
    val cdep = HashMap<Int, Int>(); val ctdep = HashMap<Int, Int>()
    val cink = HashMap<Int, Int>(); val cn = HashMap<Int, Int>()
    for (i in 0 until w * h) {
      if (plate[i] != 0.toByte() || (src[i] ushr 24) != 255) continue
      val a = mask[i] ushr 24
      val d = dist[i].toInt() and 0xffff
      dep += d; ink += a
      val k = src[i] and 0x00FFFFFF
      ctdep[k] = maxOf(ctdep[k] ?: 0, (distT[i].toInt() and 0xffff))
      cdep[k] = maxOf(cdep[k] ?: 0, d); cink[k] = maxOf(cink[k] ?: 0, a); cn[k] = (cn[k] ?: 0) + 1
    }
    if (dep.size < 128) continue
    // **同色起伏**：模型自己承诺"同一种源色交付同一种浓度"（`cDep` 那张表），所以**同一种精确色
    // 内部**的墨度起伏就是那条承诺被破坏的程度 —— 它不需要"哪张图算好看"的判断，是硬故障。
    // 全球网测那张 384 的 fixture 上量到纯白一条色交出 83..255（`LineDbg` 那一排），就是这一列。
    // ⚠ 算法只有一份（`jitterOf`）—— 语料循环和下面的正对照 `jitterSelfTest()` 都调它。
    val j = jitterOf(src, mask, plate, w, h, dep.size)
    val jitter = j.jitter; val worstSpan = j.worstSpan; val worstCol = j.worstCol; val worstN = j.worstN
    val cs = cdep.keys.filter { (cn[it] ?: 0) >= 16 }.sortedBy { cdep[it] }
    val cd = cs.map { cdep[it]!! }; val ci = cs.map { cink[it]!! }
    val ts = ctdep.keys.filter { (cn[it] ?: 0) >= 16 }.sortedBy { ctdep[it] }
    val td = ts.map { ctdep[it]!! }; val ti = ts.map { cink[it]!! }
    rows += MRow(f.name, dep, ink, cd, ci, td, ti, jitter, worstSpan, worstCol, worstN)
  }
  rows.sortBy { it.rhoT }
  println("%-40s %6s %5s %6s %6s %6s %6s %6s %5s %s".format(
    "图标", "图形格", "色数", "ρ色T", "逆%T", "ρ色", "逆%旧", "顶平%", "同色起伏", "最野 那一种色"))
  // 默认只打印最差的 18 行，而合计是按全体算的 —— 于是「ρ色总什么涨了、涨在哪三张上」这台子答不出来（淡有 _abdiff.cjs 点名，这一栏没有）。
  // --all 打印全部，专门用来做两个构建之间的逐张对齐。
  (if (args.contains("--all")) rows else rows.take(18)).forEach { println(it.line()) }
  println()
  val n = rows.size
  fun med(g: (MRow) -> Double) = rows.map { g(it) }.sorted()[n / 2]
  println("=== $n 张：ρ色T 中位=${"%.2f".format(med { it.rhoT })}  逆%T 中位=${"%.2f".format(med { it.invertT })}  " +
    "ρ色(旧,按 dist) 中位=${"%.2f".format(med { it.rho })}  " +
    "顶平% 中位=${"%.1f".format(med { it.capped })}  **同色起伏 中位=${med { it.jitter.toDouble() }.toInt()}**  " +
    "(ρ格 中位=${"%.2f".format(med { it.rhoCell })}，那一栏只能当参照，见注释)")
  println("同色起伏>40（同一种源色交出跨 40 档的浓度 —— 这是**违约**，不是取舍）的张数 = ${rows.count { it.jitter > 40 }}" +
    "   >80 = ${rows.count { it.jitter > 80 }}   =0 的张数 = ${rows.count { it.jitter == 0 }}")
  println("ρ色T<0.95 的张数 = ${rows.count { it.rhoT < 0.95 }}   ρ色T<0.8 = ${rows.count { it.rhoT < 0.8 }}   " +
    "逆%T>5 = ${rows.count { it.invertT > 5 }}   顶平%>25 = ${rows.count { it.capped > 25 }}")
  println("ρ色<0.95 的张数 = ${rows.count { it.rho < 0.95 }}   ρ色<0.8 = ${rows.count { it.rho < 0.8 }}   " +
    "逆%色>5 = ${rows.count { it.invert > 5 }}   顶平%>25 = ${rows.count { it.capped > 25 }}")
  println("点名的那两张：")
  rows.filter { it.name.contains("yiban") || it.name.contains("globalspeed") }.distinctBy { it.name }.forEach { println("   ${it.line()}") }
  // 机器可读的一行，给 `regress.sh` 当闸门用（它只认这一行，不解析上面那些对齐列）。
  println("AUDIT n=$n rho=${"%.3f".format(med { it.rho })} rhoBad=${rows.count { it.rho < 0.95 }} " +
    "rhoT=${"%.3f".format(med { it.rhoT })} rhoTBad=${rows.count { it.rhoT < 0.95 }} " +
    "cap=${"%.1f".format(med { it.capped })} jitter=${med { it.jitter.toDouble() }.toInt()} " +
    "jitterBad=${rows.count { it.jitter > 40 }} " +
    // 阈值 40 是**拍出来的**。不加 max 就永远看不出这一栏是不是根本没在量程内：
    //   2026-10-16 现量四份语料，`jitterBad` 恒 0、历史唯一一次命中随分母变更消失 ——
    //   删掉被检的那截代码，这一栏也不会响。打出 max 才能当场判断"0 是干净"还是"0 是瞎"。
    "jitterMax=${if (rows.isEmpty()) -1 else rows.maxOf { it.jitter }}")
}

private class MRow(
  val name: String, dep: List<Int>, ink: List<Int>, cdep: List<Int>, cink: List<Int>,
  tdep: List<Int>, tink: List<Int>,
  val jitter: Int, val worstSpan: Int, val worstCol: Int, val worstN: Int,
) {
  val m = dep.size
  val nc = cdep.size
  val rhoCell: Double
  val rho: Double
  val rhoT: Double
  val invert: Double
  val invertT: Double
  val capped: Double
  val span: Int
  val depSpread: Int

  init {
    rhoCell = spearman(dep, ink)
    rho = if (nc < 4) 1.0 else spearman(cdep, cink)
    rhoT = if (tdep.size < 4) 1.0 else spearman(tdep, tink)
    // 逆序对占比（按色那一层）：全对是 nc²，一般几千色，直接数完，不用抽样
    fun inv(x: List<Int>, y: List<Int>): Double {
      var pairs = 0L; var bad = 0L
      for (i in x.indices) for (j in i + 1 until x.size) {
        if (x[i] == x[j]) continue
        pairs++
        if ((x[i] - x[j]).toLong() * (y[i] - y[j]) < 0) bad++
      }
      return if (pairs == 0L) 0.0 else bad * 100.0 / pairs
    }
    invert = inv(cdep, cink); invertT = inv(tdep, tink)
    // **顶平**：alpha 已经贴到 255 的那些图形格占多少，以及它们的 dep 还散开多远 ——
    // 那一段 dep 差是模型算出来了、但**没有地方显示**的细节（`实=` 那个锚的代价）。
    val atTop = ArrayList<Int>()
    for (i in 0 until m) if (ink[i] >= 255) atTop += dep[i]
    capped = atTop.size * 100.0 / m
    atTop.sort()
    depSpread = if (atTop.size > 8) atTop[atTop.size * 95 / 100] - atTop[atTop.size * 5 / 100] else 0
    val a = ink.sorted()
    span = a[a.size * 95 / 100] - a[a.size * 5 / 100]
  }

  fun line() = "%-40s %6d %6d %6.2f %6.2f %6.2f %6.2f %6.1f %6d %5d %06x".format(
    name.removeSuffix(".png").take(40), m, nc, rhoT, invertT, rho, invert, capped, jitter, worstSpan,
    if (worstCol >= 0) worstCol else 0)
}

/** Spearman 秩相关：并列取平均秩 —— 贴顶/贴底那一大堆否则会被当成"乱序"。 */
private fun spearman(x: List<Int>, y: List<Int>): Double {
  val rx = ranks(x); val ry = ranks(y)
  val n = x.size
  var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0; var syy = 0.0
  for (i in 0 until n) { sx += rx[i]; sy += ry[i]; sxy += rx[i] * ry[i]; sxx += rx[i] * rx[i]; syy += ry[i] * ry[i] }
  val den = sqrt(maxOf(0.0, (n * sxx - sx * sx)) * (n * syy - sy * sy))
  return if (den == 0.0) 1.0 else (n * sxy - sx * sy) / den
}

private fun ranks(v: List<Int>): DoubleArray {
  val n = v.size
  val idx = (0 until n).sortedBy { v[it] }
  val r = DoubleArray(n)
  var i = 0
  while (i < n) {
    var j = i
    while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++
    val avg = (i + j) / 2.0 + 1
    for (k in i..j) r[idx[k]] = avg
    i = j + 1
  }
  return r
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

/** 同色起伏的**唯一一份**算法。语料循环与 [jitterSelfTest] 都调它 —— 分成两份写，
 *  自检测的就是副本，生产坏了自检照样绿（2026-10-16 第十六闸刚栽过一次）。 */
private class Jit(val jitter: Int, val worstSpan: Int, val worstCol: Int, val worstN: Int)

private fun jitterOf(src: IntArray, mask: IntArray, plate: ByteArray, w: Int, h: Int, denom: Int): Jit {
  val byCol = HashMap<Int, ArrayList<Int>>()
  for (i in 0 until w * h) {
    if (plate[i] != 0.toByte() || (src[i] ushr 24) != 255) continue
    byCol.getOrPut(src[i] and 0xffffff) { ArrayList() }.add(mask[i] ushr 24)
  }
  var worstN = 0; var worstSpan = 0; var worstCol = -1; var weighted = 0L
  for ((c, v) in byCol) {
    if (v.size < 256) continue            // 样本太少的色不算：p95/p5 在这种样本上是噪声
    v.sort()
    val sp = v[v.size * 95 / 100] - v[v.size * 5 / 100]
    weighted += sp.toLong() * v.size
    if (sp > worstSpan) { worstSpan = sp; worstN = v.size; worstCol = c }
  }
  return Jit(if (denom == 0) 0 else (weighted / denom).toInt(), worstSpan, worstCol, worstN)
}

/**
 * 正对照：亲手造一条"同一种源色交出两种差很远的浓度"的假数据，看这一栏报不报。
 * 返回 (造出来的跨度, 这一栏报出的 jitter)；**没报出 >40 就返回 null**。
 *
 * 为什么要它：`jitterBad` 在现有多份语料上恒 0，而 `jitterMax` 只有 7 —— 单看数会判成
 * "阈值 40 在量程外，这栏是死的"。但这一列的阈值不是拍的：注释里记着全球网测那张 fixture
 * 上真量到一条纯白交出 83..255（跨 172）。**所以不是尺子钝，是那张会触发的图已经不在
 * 任何语料里了。** 这个自检把"钝"与"干净"分开：它响 = 尺子好的、这批确实没违约；
 * 它不响 = 后面所有 `jitterBad=0` 全部作废。
 */
private fun jitterSelfTest(): Pair<Int, Int>? {
  val n = 400
  val src = IntArray(n); val mask = IntArray(n); val plate = ByteArray(n)
  val white = (0xff shl 24) or 0x010101
  for (i in 0 until n) {
    src[i] = white
    plate[i] = 0   // ⚠ `plate` 非 0 = 这一格在**那块盘**上，量同色起伏时是要跳过的（上面那行
                  //   `if (plate[i] != 0 …) continue`）。第一次写成 1，自检当场不响 —— 它连自己
                  //   的造例都验了一遍，这正是正对照该干的事。
    mask[i] = (if (i < n / 2) 100 else 240) shl 24
  }
  val j = jitterOf(src, mask, plate, n, 1, n)
  return if (j.worstSpan > 40 && j.jitter > 40) Pair(j.worstSpan, j.jitter) else null
}
