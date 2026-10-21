package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 「围住它的那一块」到底分不分得开 —— 先量，不动模型。
 *
 * 主人点名 12 张之后，归属那条路选出了第三条规则（见 `MonochromeMask` 里 [TONAL_*] 那一段旁边的
 * 记录）：**一个被围住的区域要不要成墨，拿它和围住它的那一块比色**。
 * 前两条（连通性 / 深度奇偶）都败在"兄弟区域"上：浙江在线的波浪与"潮"字都挨着同一块蓝底，
 * 连通性把它们一起收，奇偶靠 BFS 到达顺序碰运气。
 *
 * 这一台要答的问题只有一个，而且必须在上模型之前答：
 *   **在模型自己的色桶上长出来的区域图里，"该擦掉的装饰"与"该留下的字形"，
 *    它们各自与围住者的 ΔE 分不分得开？**
 * 分得开 → 定一条线，落地；分不开 → 这条路和前五把尺子一样死在"没有能分开两组信息的量"上。
 *
 * 为什么用**色桶**而不是自己写洪泛：`_harness/TwoPlateKt` 那台跑动均值 + 一个 ΔE 容差的
 * 分割，把驾考宝典的车身与底盘并成一块（两个蓝相差不到 18）、把一条笔画切成十几块 ——
 * **分割质量主导了结论**。色桶是模型自己分"部分"用的那一套词汇（`bucketOf`，
 * L 每 8、a/b 每 32，共 832 格），拿它长区域才是"用模型的词问模型的问题"。
 *
 * 用法：`harness.EncloseKtKt <目录> [--edge=384] [--top=6] [--shot=DIR]`
 *   每张打印它**当前被模型画上墨**的那些区域，按格数降序：
 *     格数(占框%) / ΔE到围住者 / 围住者是谁(占框%) / 该区域平均交付浓度
 *   —— 一眼能看出"字形"和"装饰"在 ΔE 那一列上是不是两堆。
 */
private fun load(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null)
  g.dispose()
  val px = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, px, 0, edge)
  return px
}

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val mergeTol = args.firstOrNull { it.startsWith("--merge=") }?.substringAfter("=")?.toInt() ?: 12
  val topN = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 6
  val dir = File(args.first { !it.startsWith("--") })
  val sepL = args.firstOrNull { it.startsWith("--sl=") }?.substringAfter("=")?.toInt() ?: 8
  val sepAb = args.firstOrNull { it.startsWith("--sa=") }?.substringAfter("=")?.toInt() ?: 32
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    val n = edge * edge
    val labL = IntArray(n); val labA = IntArray(n); val labB = IntArray(n)
    val buck = IntArray(n)
    for (i in 0 until n) {
      labL[i] = MonochromeMask.labChannel(px[i], 0); labA[i] = MonochromeMask.labChannel(px[i], 1)
      labB[i] = MonochromeMask.labChannel(px[i], 2)
      val l = labL[i] shr 6
      val a = ((labA[i] shr 6) + 128).coerceIn(0, 255)
      val b = ((labB[i] shr 6) + 128).coerceIn(0, 255)
      val nb2 = 256 / sepAb
      buck[i] = ((l / sepL) * nb2 + a / sepAb) * nb2 + b / sepAb
    }
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) continue
    val boxArea = (r - l + 1).toLong() * (b - t + 1)
    // 1) 同色桶的 4 连通块
    val comp = IntArray(n) { -1 }
    val q = IntArray(n)
    var nc = 0
    for (y in t..b) for (x in l..r) {
      val i = y * edge + x
      if (comp[i] >= 0 || ((px[i] ushr 24) and 0xff) < 128) continue
      val id = nc++; var h = 0; var tl = 0
      q[tl++] = i; comp[i] = id
      val bk = buck[i]
      while (h < tl) {
        val c = q[h++]
        val cy = c / edge; val cx = c - cy * edge
        for (d in 0 until 4) {
          val nx = cx + intArrayOf(-1, 1, 0, 0)[d]; val ny = cy + intArrayOf(0, 0, -1, 1)[d]
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (comp[j] >= 0 || ((px[j] ushr 24) and 0xff) < 128 || buck[j] != bk) continue
          comp[j] = id; q[tl++] = j
        }
      }
    }
    if (nc == 0) continue
    // 2) 每块的格数与均值 Lab；哪些块挨着内容框的边
    val size = LongArray(nc); val sl = LongArray(nc); val sa = LongArray(nc); val sb = LongArray(nc)
    for (i in 0 until n) { val id = comp[i]; if (id < 0) continue; size[id]++; sl[id] += labL[i]; sa[id] += labA[i]; sb[id] += labB[i] }
    val border = BooleanArray(nc)
    for (y in t..b) for (x in l..r) if (x == l || x == r || y == t || y == b) {
      val id = comp[y * edge + x]; if (id >= 0) border[id] = true
    }
    // 3) 邻接（带共边数）
    val nbr = Array(nc) { HashMap<Int, Int>() }
    fun link(a: Int, bb: Int) {
      if (a < 0 || bb < 0 || a == bb) return
      nbr[a][bb] = (nbr[a][bb] ?: 0) + 1; nbr[bb][a] = (nbr[bb][a] ?: 0) + 1
    }
    for (y in 0 until edge) for (x in 0 until edge - 1) link(comp[y * edge + x], comp[y * edge + x + 1])
    for (y in 0 until edge - 1) for (x in 0 until edge) link(comp[y * edge + x], comp[(y + 1) * edge + x])
    // 3.5) **合并过分割**：色桶会把一块平涂切成十几个相邻桶（每条笔画、每圈抗锯齿各一块），
    //    于是"共边最多的邻居"往往是**它自己的另一块**（ΔE 3~8），围住者那一列整个废掉。
    //    所以先把相邻且色差的共边超过半数的块并起来 —— 这一步就是"真正的分割"要做的事，
    //    量的是：**加上这一步之后，字形与装饰在 ΔE 那一列还分不分得开。**
    val par = IntArray(nc) { it }
    fun find(x: Int): Int { var a = x; while (par[a] != a) { par[a] = par[par[a]]; a = par[a] }; return a }
    fun mean(i: Int): Triple<Long, Long, Long> = Triple(sl[i] / size[i], sa[i] / size[i], sb[i] / size[i])
    fun dePair(i: Int, j: Int): Int {
      val (li, ai, bi) = mean(i); val (lj, aj, bj) = mean(j)
      return sqrt(((li - lj).toDouble().pow(2) + (ai - aj).toDouble().pow(2) +
        (bi - bj).toDouble().pow(2))).toInt() / 64
    }
    var merged = true
    var rounds = 0
    while (merged && rounds++ < 12) {
      merged = false
      for (a in 0 until nc) {
        val ra = find(a)
        for ((b, w) in nbr[a].toList()) {
          val rb = find(b)
          if (rb == ra) continue
          if (size[rb] * 100 <= boxArea) continue          // 太小的不主动并
          if (dePair(ra, rb) <= mergeTol) {
            // 并到大的那一块上
            val (keep, gone) = if (size[ra] >= size[rb]) intArrayOf(ra, rb) else intArrayOf(rb, ra)
            par[gone] = keep
            size[keep] += size[gone]; sl[keep] += sl[gone]; sa[keep] += sa[gone]; sb[keep] += sb[gone]
            for ((k2, w2) in nbr[gone]) nbr[keep][k2] = (nbr[keep][k2] ?: 0) + w2
            merged = true
          }
        }
      }
    }
    // 合并之后重算共边与"围住者"
    val mSize = HashMap<Int, Long>(); val mNbr = HashMap<Int, HashMap<Int, Int>>()
    for (i in 0 until n) { val id = comp[i]; if (id < 0) continue; val r = find(id); mSize[r] = (mSize[r] ?: 0L) + 1 }
    fun link2(a: Int, bb: Int) {
      if (a < 0 || bb < 0 || a == bb) return
      mNbr.getOrPut(a) { HashMap() }.let { it[bb] = (it[bb] ?: 0) + 1 }
      mNbr.getOrPut(bb) { HashMap() }.let { it[a] = (it[a] ?: 0) + 1 }
    }
    fun root(i: Int): Int { val id = comp[i]; return if (id < 0) -1 else find(id) }
    for (y in 0 until edge) for (x in 0 until edge - 1) link2(root(y * edge + x), root(y * edge + x + 1))
    for (y in 0 until edge - 1) for (x in 0 until edge) link2(root(y * edge + x), root((y + 1) * edge + x))
    val mIds = mSize.keys.sortedByDescending { mSize[it] }
    val mBorder = HashSet<Int>()
    for (y in t..b) for (x in l..r) if (x == l || x == r || y == t || y == b) {
      val id = comp[y * edge + x]; if (id >= 0) mBorder.add(find(id))
    }
    val encM = HashMap<Int, Int>()
    for (id in mIds) {
      var best = -1; var bw = 0
      for ((k, v) in (mNbr[id] ?: mapOf())) if (v > bw) { bw = v; best = k }
      encM[id] = best
    }
    // 5) 模型现在给每一块（合并后的块）交了多少墨
    val m = MonochromeMask.build(px, edge, edge).mask ?: IntArray(n)
    val inkSum = HashMap<Int, Long>(); val inkN = HashMap<Int, Long>()
    for (i in 0 until n) {
      val id = comp[i]; if (id < 0) continue
      val r = find(id)
      val a = (m[i] ushr 24) and 0xff
      if (a >= 128) {
        inkSum[r] = (inkSum[r] ?: 0L) + a; inkN[r] = (inkN[r] ?: 0L) + 1
      }
    }
    println("--- ${f.nameWithoutExtension.take(44)}  块 $nc → 合并后 ${mIds.size}（mergeTol=$mergeTol）")
    val rows = mIds.filter { (inkN[it] ?: 0L) * 100 >= boxArea / 100 }
      .sortedByDescending { inkN[it] }
    for (id in rows.take(topN)) {
      val e = encM[id] ?: -1
      val ik = inkN[id] ?: 0L; val isum = inkSum[id] ?: 0L
      val eShare = if (e < 0) 0.0 else (mSize[e] ?: 0L) * 100.0 / boxArea
      // ⚠ **围住者只有自己占到框面一定比例时，它的"围住"才算数**：渐变底会被任何量化切成几十块，
      //  字共边最多的邻居往往只是其中一块碎片（占框 0.0%），拿它算出来的 ΔE 随档跳（3/19/23/35）。
      //  这条约束既是台子的卫生，也是**将来上模型时的安全阀**：围住者太小就不判。
      val ok = eShare >= 5.0
      println("    格%5.1f%%  墨均=%3d  %s  ΔE到围住者=%3d  围住者占框=%5.1f%%  %s"
        .format(ik * 100.0 / boxArea, if (ik == 0L) 0 else isum / ik,
          if (mBorder.contains(id)) "挨边 " else "围住",
          if (e < 0) -1 else dePair(id, e), eShare,
          if (mBorder.contains(id)) "—— 它自己就是底" else if (ok) "可信" else "围住者太小，这条不可信"))
    }
  }
}
