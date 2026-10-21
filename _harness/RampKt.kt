package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **只读模拟：「顺着颜色走向继续洪泛」这一维会误吃多少底**（2026-09-29，为 globalspeed 那道弧立的判据台）。
 *
 * 现场是这样的：仪表盘的弧是 蓝→绿→深蓝 的渐变，**深色尾段的颜色就等于背景**，所以它被判成底、
 * 整段淡掉。已经量死过两条形状路（局部厚度、被图形包住的连通块 —— 见 `ThinKt`）：弧**不薄**，
 * 而且与背景是**同一块连通底**。图上剩下的唯一证据是"它是那道亮弧的延续"。
 *
 * 于是这一维要做的事情是：**从已经出墨的地方出发，只要颜色还顺着同一条走向走，就继续往前吃**。
 * 这一版模拟的规则就三条，全部可解释：
 *  · 种子 = 交付浓度 ≥ 200 的实心墨（不是抗锯齿那一圈）；
 *  · 走一步 = 邻格是底、且与当前格 ΔE ≤ [step]；
 *  · **必须继续走** = 这一格相对上一格 L 还在**同一个方向**变（且 |ΔL| ≥ 1）。
 * 第三条是唯一的刹车：**平底的 ΔL=0，一步就走不动**；能走远的只有"底自己也是渐变"的图 ——
 * 而那正是这一维会不会值得开的那个数。
 *
 * 报的是**误吃率**：每张被吃掉的底占它自己那块底的百分之几。淘汰线（跑之前写下的）：
 * 语料中位 >2% 就直接否这条维，不进实现。
 *
 * 跑法：`harness.RampKt [--step=12] [--shot=目录] 目录 …`
 */
fun main(args: Array<String>) {
  val step = args.firstOrNull { it.startsWith("--step=") }?.substringAfter("=")?.toInt() ?: 12
  val lmin = args.firstOrNull { it.startsWith("--lmin=") }?.substringAfter("=")?.toInt() ?: 1
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val rows = ArrayList<Pair<String, IntArray>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < 48 || h < 48) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = out?.mask ?: continue
    val s = pool.get() ?: continue
    val plate = field(s, "plate") as ByteArray
    val r = ramp(src, mask, plate, w, h, step, lmin) ?: continue
    val deepPct = r.deep * 100 / r.plateN
    rows += f.name to intArrayOf(r.plateN, r.fringe, r.deep, deepPct, r.vis, r.eatenN)
    if (shotDir != null && (r.deep >= 200 || f.name.contains("globalspeed"))) {
      File(shotDir).mkdirs()
      ImageIO.write(overlay(src, r, w, h), "png", File("$shotDir/${f.name.removeSuffix(".png")}_ramp.png"))
    }
  }
  val ps = rows.map { it.second[3] }.sorted()
  println("step=ΔE$step   有底的张数=${rows.size}")
  println("**深吃率**（离墨 ≥3 步还在往底肚子里吃 / 它自己的底）：中位=${ps[ps.size / 2]}%  p75=${ps[ps.size * 3 / 4]}%  p90=${ps[ps.size * 9 / 10]}%  最大=${ps.last()}%")
  println("超过淘汰线的张数：>2% = ${ps.count { it > 2 }}   >5% = ${ps.count { it > 5 }}   >10% = ${ps.count { it > 10 }}   =0% = ${ps.count { it == 0 }}")
  println("（另外那一圈挨着墨的种子不计入淘汰线：任何按颜色下的判据都会先碰到它，中位=${rows.map { it.second[1] * 100 / it.second[0] }.sorted()[rows.size / 2]}% 的底）")
  println()
  println("深吃最多的 14 张：")
  rows.sortedByDescending { it.second[3] }.take(14).forEach {
    println("   深吃%3d%%  底=%-7d 边=%-6d 深=%-6d 走=%-7d %s".format(
      it.second[3], it.second[0], it.second[1], it.second[2], it.second[4], it.first.removeSuffix(".png").take(48)))
  }
  println()
  println("点名的那两张：")
  rows.filter { it.first.contains("globalspeed") || it.first.contains("yiban") }.forEach {
    println("   深吃%3d%%  底=%-7d 边=%-6d 深=%-6d 走=%-7d %s".format(
      it.second[3], it.second[0], it.second[1], it.second[2], it.second[4], it.first.removeSuffix(".png").take(48)))
  }
  if (shotDir != null) println("--shot 已写（红=被这一维吃掉的底，含种子那一圈）→ $shotDir")
}

/**
 * 洪泛。**第一版死在这里，值得记着**：最初写的是"从实心墨出发，第一跳也要 ≤ΔE12"——
 * 那个条件**恒不成立**：一块颜色能变成墨，就是因为它离底够远，于是墨↔底那一步天生就是大跳。
 * 结果 61 张全部 `被吃=0`，包括要救的那张 —— 一个跑不起来的检查报"零误吃"，和没跑一样（本仓第二次栽在
 * "条件恒不满足的判据"上，第一次是 `plate = setOf(一个颜色)` 对渐变底恒不匹配）。
 *
 * 现在改成两截：**种子 = 挨着实心墨的那一圈底格**（不看颜色，这就是"贴着图形的底"），
 * 然后**从种子起**才要求"每跳 ≤ΔE[step] 且 L 继续朝同一方向变"。于是两个数分开才说明问题：
 *  · `边` = 种子那一圈（挨着图形的 1~2px，任何按颜色下的判据都会先碰到它）；
 *  · `深吃` = 离墨 ≥3 步还在往里吃的 —— **这一维真正的代价是这个数**：平底走到第二步就 ΔL=0 死掉，
 *    只有"底自己也是渐变"的图才会被吃进肚子里。globalspeed 那道弧要的是它被救，代价看别的图的它。
 */
private class RR(val plateN: Int, val claimed: BooleanArray, val eatenN: Int, val fringe: Int, val deep: Int, val vis: Int)

private fun ramp(src: IntArray, mask: IntArray, plate: ByteArray, w: Int, h: Int, step: Int, lmin: Int): RR? {
  val n = w * h
  var plateN = 0
  for (i in 0 until n) if (plate[i] != 0.toByte() && (src[i] ushr 24) == 255) plateN++
  if (plateN < 256) return null
  val L = IntArray(n)
  val A = IntArray(n)
  val B = IntArray(n)
  for (i in 0 until n) {
    L[i] = MonochromeMask.labChannel(src[i], 0); A[i] = MonochromeMask.labChannel(src[i], 1); B[i] = MonochromeMask.labChannel(src[i], 2)
  }
  val eaten = BooleanArray(n)
  val claimed = BooleanArray(n)   // 只数"被这一维claim到的底格"，画回去时用这一块
  val dir = IntArray(n)          // 0 = 还没方向；±1 = 这一格是被 L 朝这个方向带进来的
  val hop = IntArray(n)          // 离种子几步
  val q = IntArray(n); var head = 0; var tail = 0
  // 种子 = **模型已经认成图形的全部格子**（非底、实心），不是"实心墨"。
  // ⚠ 这一条是三轮全 0 换来的：先前两版拿"交付浓度≥200 的墨"当种子，而**墨与底相邻的那一步
  //   按构造就是一个大跳**（一块颜色之所以变成墨，就是因为它离底够远），于是第一跳恒不成立；
  //   第二版改成"挨着墨的底格"当种子，又被那圈抗锯齿挡住（它既不是底也不是墨）。
  //   真正要问的是"从图形这一堆出发，顺着颜色的连续性能淌进底里多远" —— 种子就得摆在整个图形集上，
  //   让弧的**中段**（本来就是连续的那一段）去接触它的尾段。
  for (i in 0 until n) {
    if (plate[i] != 0.toByte() || (src[i] ushr 24) != 255) continue
    eaten[i] = true; q[tail++] = i
  }
  if (tail == 0) return null
  var fringe = 0; var deep = 0; var plateN2 = 0; var vis = 0
  // 调试用：把"墨↔邻格"这一跳卡在哪个门上，数出来。（第一版整轮全 0，不数就是猜。）
  var dPairs = 0; var dOpaque = 0; var dE12 = 0; var dL = 0
  while (head < tail) {
    val c = q[head++]
    val x = c % w; val y = c / w
    for ((dx, dy) in RNB) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      val j = ny * w + nx
      if (eaten[j]) continue
      val boundary = dir[c] == 0 && hop[c] == 0
      if (boundary) dPairs++
      if ((src[j] ushr 24) != 255) continue                 // 透明的那部分不参与
      if (boundary) dOpaque++
      val dl = L[j] - L[c]
      val da = A[j] - A[c]
      val db = B[j] - B[c]
      if (sqrt((dl * dl + da * da + db * db).toDouble()) > step) continue
      if (boundary) dE12++
      // 种子还没方向：第一跳只要求 L 真的在动（**平底 ΔL=0 → 一步就走不动**，这一条是唯一的刹车）
      if (dir[c] != 0) { if (lmin > 0 && (dl == 0 || (dl > 0) != (dir[c] > 0))) continue }
      else if (abs(dl) < lmin) continue
      if (boundary) dL++
      eaten[j] = true; dir[j] = if (dir[c] != 0) dir[c] else if (dl > 0) 1 else -1
      hop[j] = hop[c] + 1; vis++
      if (plate[j] != 0.toByte()) {
        claimed[j] = true
        plateN2++
        if (hop[j] <= 2) fringe++ else deep++
      }
      q[tail++] = j
    }
  }
  println("      调试 边界对=$dPairs 实心后=$dOpaque ΔE≤$step 后=$dE12 L在动后=$dL  走=$vis")
  return RR(plateN, claimed, plateN2, fringe, deep, vis)
}

/** 红 = 被这一维吃掉的底格子，铺在源图上。 */
private fun overlay(src: IntArray, r: RR, w: Int, h: Int): BufferedImage {
  val o = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until h) for (x in 0 until w) {
    val i = y * w + x
    if (r.claimed[i]) { o.setRGB(x, y, -0x10000); continue }
    o.setRGB(x, y, if (((src[i] ushr 24) and 0xff) < 128) -0x1000001 else (0xff shl 24) or (src[i] and 0xffffff))
  }
  return o
}

private val RNB = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

private fun field(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
