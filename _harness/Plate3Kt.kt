package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **区域图长在模型自己的拟合色场上** —— 上一台（`Plate2Kt`）两种粒度都是死数之后唯一的一条路。
 *
 * 要答的还是那一个问题：**一块大的平色区域，该不该当底。** 而上一轮立起来的分隔量
 * "它挨不挨画布边" 之所以报了恒 0 的数，是因为我拿**内容框**当"边" —— 圆形图标的框是圆的
 * **外接方**，圆只在四个切点上碰框，于是谁都"不挨边"。
 * 这一台换成**图标自己的轮廓**：实心像素里挨着透明像素的那一圈（外扩 [RIM] 像素）。
 * 底部那条带子一定压在轮廓上；一枚坐在盘子中间的圆徽一定整圈都不碰轮廓。
 *
 * 区域 = 模型的 8×8 拟合格（`s.grid`，每格中位色已经算好在 `fL/fA/fB`）里，
 * **底占不足一半**的那些格，按 4 连通、格色 ΔE ≤ [MERGE] 并起来。
 * ⚠ 为什么用格不用像素：渐变带在像素层（精确色 / `bucketOf`）会被切成几千片，
 *   而那一层就是为"底可以是渐变"造的 —— 每格的中位色本来就跟着渐变走。
 *
 * 用法：`harness.Plate3KtKt <目录> … [--top=1] [--min=0.06] [--shot=DIR]`
 *   `--shot` 另出一张图：**品红 = 这条规则会擦掉的格**。左边摆"现在交出去的墨"，
 *   右边摆"擦完之后"。为什么要它：`字均净` 在开火的两张上**往下掉**（翻译 209→168、
 *   驾考 178→157），可那条均值是把**带子自己**当"字"算进去的 —— 带子浓，把它拿走
 *   均值当然降。这正是本仓反复踩的那一类：**指标的定义里，有没有把我要做的动作算成错误。**
 *   数分不清这件事，图能。
 */
fun main(args: Array<String>) {
  val topN = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 3
  val minShare = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toDouble() ?: 0.04
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name } else listOf(f)
  }
  println("%-34s %-6s %s".format("图标", "底占", "前几块「墨」区域：占框% / 压轮廓% / 围住的远色格数"))
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull() ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val g = fld(s, "grid") as Int
    val fL = fld(s, "fL") as IntArray
    val fA = fld(s, "fA") as IntArray
    val fB = fld(s, "fB") as IntArray
    val cells = g * g
    // 内容框：台子上拿不到模型那份 Rect，就自己按实心像素圈一个（同一条定义）
    var bl = w; var bt = h; var br = -1; var bb = -1
    for (y in 0 until h) for (x in 0 until w) if ((src[y * w + x] ushr 24) >= 128) {
      if (x < bl) bl = x; if (x > br) br = x; if (y < bt) bt = y; if (y > bb) bb = y
    }
    if (br < 0) continue
    val boxArea = (br - bl + 1).toLong() * (bb - bt + 1)
    // 1) 轮廓那一圈：实心 ↔ 透明 的边界，外扩 RIM 像素
    val rim = BooleanArray(w * h)
    val solidA = BooleanArray(w * h) { (src[it] ushr 24) >= 128 }
    val edgePx = ArrayList<Int>()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      if (!solidA[i]) continue
      if (!solidA[i - 1] || !solidA[i + 1] || !solidA[i - w] || !solidA[i + w]) edgePx += i
    }
    var e2 = ArrayList(edgePx)
    for (step in 0 until RIM) {
      val nxt = ArrayList<Int>()
      for (i in e2) {
        if (rim[i]) continue
        rim[i] = true
        val y = i / w; val x = i - y * w
        if (x > 0 && solidA[i - 1] && !rim[i - 1]) nxt += i - 1
        if (x < w - 1 && solidA[i + 1] && !rim[i + 1]) nxt += i + 1
        if (y > 0 && solidA[i - w] && !rim[i - w]) nxt += i - w
        if (y < h - 1 && solidA[i + w] && !rim[i + w]) nxt += i + w
      }
      e2 = nxt
    }
    // 2) 每格：底占多少、有多少"非底实心"格、其中多少压在轮廓上
    val cPlate = IntArray(cells); val cInk = IntArray(cells); val cRim = IntArray(cells)
    val lab = Array(3) { IntArray(w * h) }
    for (i in 0 until w * h) {
      lab[0][i] = MonochromeMask.labChannel(src[i], 0)
      lab[1][i] = MonochromeMask.labChannel(src[i], 1)
      lab[2][i] = MonochromeMask.labChannel(src[i], 2)
    }
    val cellOf = IntArray(w * h) { -1 }
    for (y in bt..bb) for (x in bl..br) {
      val cx = ((x - bl) * g / (br - bl + 1)).coerceAtMost(g - 1)
      val cy = ((y - bt) * g / (bb - bt + 1)).coerceAtMost(g - 1)
      val k = cy * g + cx
      cellOf[y * w + x] = k
      if ((src[y * w + x] ushr 24) != 255) continue
      if (plate[y * w + x] != 0.toByte()) { cPlate[k]++; continue }
      cInk[k]++
      if (rim[y * w + x]) cRim[k]++
    }
    // 3) 区域：非底占大头的格，按格色 ΔE ≤ MERGE 并 4 连通
    val inkCell = BooleanArray(cells) { cPlate[it] * 2 < (cPlate[it] + cInk[it]) && cInk[it] > 0 }
    val reg = IntArray(cells) { -1 }
    var nr = 0
    for (k in 0 until cells) {
      if (!inkCell[k] || reg[k] >= 0) continue
      val id = nr++; var hd = 0
      val q = IntArray(cells); q[0] = k; hd = 0; var tl = 1; reg[k] = id
      while (hd < tl) {
        val c = q[hd++]; val cy = c / g; val cx = c - cy * g
        for (d in 0 until 4) {
          val nx = cx + DX[d]; val ny = cy + DY[d]
          if (nx < 0 || ny < 0 || nx >= g || ny >= g) continue
          val j = ny * g + nx
          if (reg[j] >= 0 || !inkCell[j]) continue
          if (de(fL[c], fA[c], fB[c], fL[j], fA[j], fB[j]) > MERGE) continue
          reg[j] = id; q[tl++] = j
        }
      }
    }
    val rArea = LongArray(nr); val rRim = LongArray(nr)
    val rL = LongArray(nr); val rA = LongArray(nr); val rB = LongArray(nr)
    for (i in 0 until w * h) {
      val k = cellOf[i]; if (k < 0 || reg[k] < 0) continue
      if ((src[i] ushr 24) != 255 || plate[i] != 0.toByte()) continue
      rArea[reg[k]]++
      rL[reg[k]] += lab[0][i]; rA[reg[k]] += lab[1][i]; rB[reg[k]] += lab[2][i]
      if (rim[i]) rRim[reg[k]]++
    }
    // 4) 这块区域"围住"了多少小块：与它色差远的邻格里的非底像素
    val rCarry = LongArray(nr)
    for (k in 0 until cells) {
      if (!inkCell[k] || reg[k] < 0) continue
      for (d in 0 until 4) {
        val cy = k / g; val cx = k - cy * g
        val nx = cx + DX[d]; val ny = cy + DY[d]
        if (nx < 0 || ny < 0 || nx >= g || ny >= g) continue
        val j = ny * g + nx
        if (!inkCell[j] || reg[j] < 0 || reg[j] == reg[k]) continue
        // 邻格里那一小撮"离这块区域的格色很远"的像素 —— 带子上的白字就是这个
        if (de(fL[k], fA[k], fB[k], fL[j], fA[j], fB[j]) >= CARRY_DE) rCarry[reg[k]] += cInk[j]
      }
    }
    // ⚠ **不能报"这块区域里有多少格压在轮廓上"** —— 那个数由区域的**大小**决定（周长/面积），
    //   一条带子摊到 2 万像素里只有 3px 宽的轮廓接触，恒读出 3~5%，分不开任何东西。
    //   要问的是反过来的一句：**图标那一圈轮廓里，有多少被这块区域占着。**
    val rimAll = rRim.sum().coerceAtLeast(1L)
    val rimTotal = (0 until w * h).count { rim[it] }.coerceAtLeast(1)
    // **区域的代表色 = 它自己最多数那一峰，不是所有像素的算术均值。**
    // 上一版取均值，于是 B 站那块"粉壳 + 白底 + 黑字"平均成一个**图上不存在的灰粉色**，
    // 拿它挑出来的像素糊成一片、圈不闭合，三张图的"实心度"全报 1.00（`a829489`）。
    // 这是当天第三次栽在同一件事上：跨峰的均值不是一个颜色（前两次：`fitField` 每格三通道
    // 各取中位、"平色区域"用精确色数）。桶用模型自己那套口径：L 每 8、a/b 每 32。
    val NB = 832
    val rCnt = Array(nr) { IntArray(NB) }
    val rBSL = Array(nr) { LongArray(NB) }; val rBSA = Array(nr) { LongArray(NB) }
    val rBSB = Array(nr) { LongArray(NB) }
    fun bucketOf(i: Int): Int {
      val li = (lab[0][i] shr 6) / 8
      val ai = (((lab[1][i] shr 6) + 128).coerceIn(0, 255)) / 32
      val bi = (((lab[2][i] shr 6) + 128).coerceIn(0, 255)) / 32
      return (li * 8 + ai) * 8 + bi
    }
    for (y in bt..bb) for (x in bl..br) {
      val i = y * w + x
      val k = cellOf[i]; if (k < 0) continue
      val id = reg[k]
      if (id < 0 || (src[i] ushr 24) != 255 || plate[i] != 0.toByte()) continue
      val bk = bucketOf(i)
      rCnt[id][bk]++
      rBSL[id][bk] += lab[0][i]; rBSA[id][bk] += lab[1][i]; rBSB[id][bk] += lab[2][i]
    }
    fun rMean(id: Int): IntArray? {
      if (id < 0 || id >= nr) return null
      var best = -1; var bestN = 0
      for (b in 0 until NB) if (rCnt[id][b] > bestN) { bestN = rCnt[id][b]; best = b }
      if (best < 0 || bestN == 0) return null
      return intArrayOf(
        (rBSL[id][best] / bestN).toInt(), (rBSA[id][best] / bestN).toInt(),
        (rBSB[id][best] / bestN).toInt())
    }
    // ⚠ **"身上坐着多少字"这一条按格子挨着格子数会把 B 站擦了**（电视机是 logo 自己，
    //   白色的 bilibili 坐在**洞里**、不是坐在壳上）。分开两者的是这块区域**自己实不实**：
    //   翻译那块灰盘是**一片实心**，字是它身上的小洞；B 站的电视机是**一圈框**，洞比框还大。
    //   所以填一次洞再比面积：`实心度 = 这块区域的格数 / (格数 + 它围住的空洞格数)`。
    var thick = 0
    fun fillRatio(id: Int): Double {
      val m = rMean(id) ?: return 0.0
      val on = BooleanArray(w * h)
      for (i in 0 until w * h) {
        // ⚠ **不能拿"这一格属不属于这块区域"当条件** —— 字所在的那一格按定义不属于盘子区域，
        //   于是那一整格 48×48 全不是 `on`，洪泛从它身上直接漏进洞里，实心度恒 1.00
        //   （上一版三张全 1.00 就是这个，不是颜色的锅）。掩码只按**颜色**长。
        if ((src[i] ushr 24) != 255 || plate[i] != 0.toByte()) continue
        val m0 = m
        val dl = lab[0][i] - m0[0]; val da = lab[1][i] - m0[1]; val db = lab[2][i] - m0[2]
        if (sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() <= (25 shl 6))
          on[i] = true
      }
      if (on.count { it } == 0) return 0.0
      // 从框外洪泛"不是这块区域"的格；走不到的就是被它围住的洞
      val seen = BooleanArray(w * h)
      val qq = IntArray(w * h); var hh = 0; var tt = 0
      for (y in bt..bb) for (x in bl..br) {
        if (x != bl && x != br && y != bt && y != bb) continue
        if (!on[y * w + x] && !seen[y * w + x]) { seen[y * w + x] = true; qq[tt++] = y * w + x }
      }
      while (hh < tt) {
        val c = qq[hh++]; val cy = c / w; val cx = c - cy * w
        for (d in 0 until 4) {
          val nx = cx + DX[d]; val ny = cy + DY[d]
          if (nx < bl || ny < bt || nx > br || ny > bb) continue
          val j = ny * w + nx
          if (on[j] || seen[j]) continue
          seen[j] = true; qq[tt++] = j
        }
      }
      var holes = 0
      for (y in bt..bb) for (x in bl..br) {
        val i = y * w + x
        if (on[i] || seen[i]) continue
        holes++
      }
      val mine = on.count { it }
      // ⚠ **"洞"在这批像素上数不出来**（掩码不闭合，洪泛顺着抗锯齿/投影的缝把洞全走完，
      //   三张候选全报 1.00）。换**不需要闭合**的那个量：这块区域自己有多**厚** ——
      //   反复腐蚀，能撑过几轮。一片大实心撑十几轮，一圈 20px 的壳只撑三五轮。
      var rounds = 0
      var cur = BooleanArray(w * h) { on[it] }
      var nCur = mine
      while (nCur > 0 && rounds < 40) {
        val nxt = BooleanArray(w * h)
        var n2 = 0
        for (y in bt..bb) for (x in bl..br) {
          val i = y * w + x
          if (!cur[i]) continue
          var full = true
          for (d in 0 until 4) {
            val nx = x + DX[d]; val ny = y + DY[d]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h || !cur[ny * w + nx]) { full = false; break }
          }
          if (full) { nxt[i] = true; n2++ }
        }
        rounds++; cur = nxt; nCur = n2
      }
      thick = rounds
      return mine * 1.0 / (mine + holes)
    }
    val fired = ArrayList<Int>()
    // ⚠ 这一台现在的用途：**把过了"面积 + 远色"两道的区域全列出来，按占轮廓排序**，
    //   这样"把门从 30 放宽到 X 会带进谁"是看得见的，而不是调完再回头看。
    //   （今天已经因为"先在门里挑数"撤过五版。）
    val rows = ArrayList<Quad>()
    for (id in 0 until nr) {
      if (rArea[id] * 100.0 / boxArea < minShare * 100) continue
      if (rCarry[id] < 300) continue
      val rp = rRim[id] * 100.0 / rimTotal
      if (rp < 1.0) continue
      fillRatio(id)
      // 形状轴的第二根：**长短边比**。厚度分不开"一条带"与"一圈壳" —— 带在一个方向薄、
      // 另一个方向长；壳在两个方向都薄。驾考那块红带厚度只有 15 轮（门是 25），
      // 但它占轮廓 58.4%、坐着 3860 格远色，三道门过得干干净净，只死在这一根上。
      var lo = 1e9; var hi = -1e9; var lo2 = 1e9; var hi2 = -1e9
      for (k in 0 until reg.size) if (reg[k] == id) {
        val gy = k / g; val gx = k - gy * g
        if (gy < lo) lo = gy.toDouble(); if (gy > hi) hi = gy.toDouble()
        if (gx < lo2) lo2 = gx.toDouble(); if (gx > hi2) hi2 = gx.toDouble()
      }
      val short = minOf(hi - lo + 1, hi2 - lo2 + 1).coerceAtLeast(1.0)
      val aspect = maxOf(hi - lo + 1, hi2 - lo2 + 1) / short
      rows += Quad(id, rp, thick, aspect)
    }
    rows.sortByDescending { it.rim }
    for ((id, rp, th, asp) in rows.take(3))
      println("%-34s 块%-3d 占框=%-5.1f%% **占轮廓=%-5.1f%%** 围住远色=%-6d 厚度=%d 轮 长短边比=%.1f"
        .format(f.name.substringBefore("_"), id, rArea[id] * 100.0 / boxArea, rp, rCarry[id], th, asp))
    for (r in rows) if (r.rim >= 30 && r.thick >= 25) fired += r.id
    if (shotDir != null && fired.isNotEmpty()) {
      val ml = HashMap<Int, IntArray>()
      for (id in fired) ml[id] = intArrayOf(
        (rL[id] / rArea[id]).toInt(), (rA[id] / rArea[id]).toInt(), (rB[id] / rArea[id]).toInt())
      val mask = out.mask ?: IntArray(w * h)
      val before = IntArray(w * h); val after = IntArray(w * h)
      var cut = 0
      for (i in 0 until w * h) {
        val a = mask[i] ushr 24
        before[i] = 0xFF000000.toInt() or (((255 - a) shl 16) or ((255 - a) shl 8) or (255 - a))
        val k = cellOf[i]
        val id = if (k >= 0) reg[k] else -1
        var gone = false
        if (id >= 0 && fired.contains(id) && (src[i] ushr 24) == 255 && plate[i] == 0.toByte()) {
          val m = ml[id]!!
          val dl = lab[0][i] - m[0]; val da = lab[1][i] - m[1]; val db = lab[2][i] - m[2]
          if (sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() <= (25 shl 6))
            gone = true
        }
        if (gone) { cut++; after[i] = 0xFFFF00FF.toInt() }
        else after[i] = before[i]
      }
      val im = BufferedImage(w * 2 + 8, h, BufferedImage.TYPE_INT_RGB)
      im.setRGB(0, 0, w, h, before, 0, w); im.setRGB(w + 8, 0, w, h, after, 0, w)
      val od = File(shotDir); od.mkdirs()
      val of = File(od, f.name)
      ImageIO.write(im, "png", of)
      println("   → ${of.name}  会擦 $cut 格（占框 ${"%.1f".format(cut * 100.0 / boxArea)}%）")
    }
    val order = (0 until nr).filter { rArea[it] * 1.0 / boxArea >= minShare }.sortedByDescending { rArea[it] }
    val rep = out.report
    val share = Regex("底占=(\\S+)").find(rep)?.groupValues?.get(1) ?: "?"
    if (order.isEmpty()) { println("%-34s %-6s  （没有 ≥%.0f%% 的墨区域）".format(f.name.substringBefore("_"), share, minShare * 100)); continue }
    val sb = StringBuilder()
    for (id in order.take(topN))
      sb.append("\n   %-14s 占框=%-5.1f%%  **占轮廓=%-5.1f%%**  围住远色=%-6d".format(
        "块$id", rArea[id] * 100.0 / boxArea, rRim[id] * 100.0 / rimAll, rCarry[id]))
    println("%-34s %-6s %s".format(f.name.substringBefore("_"), share, sb.toString().trim().ifEmpty { "—" }))
  }
}

private const val RIM = 3
private const val MERGE = 25
private const val CARRY_DE = 40
private val DX = intArrayOf(-1, 1, 0, 0)
private val DY = intArrayOf(0, 0, -1, 1)

private fun de(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2).toLong(); val da = (a1 - a2).toLong(); val db = (b1 - b2).toLong()
  return sqrt((dl * dl + da * da + db * db).toDouble()).toInt() shr 6
}

private fun fld(o: Any, name: String): Any {
  for (c in generateSequence(o::class.java) { it.superclass })
    for (f in c.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

/** 一块候选区域的四笔账：占轮廓% / 腐蚀厚度 / 长短边比。 */
private data class Quad(val id: Int, val rim: Double, val thick: Int, val aspect: Double)
