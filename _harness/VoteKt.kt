package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **「同一块平色被给了两个答案」有多少、按多数统一要付多少？**（只量不改）
 *
 * 由来：第八刀（`_harness/cand/MonochromeMask.kt` 的 `plateLeak`）真机上只开火微博一张
 * （2866 格，与 `ScarKt --flood` 逐位相同），却被钉着的性质
 * 「最近那段是淡的（不是整块底）」当场拦下 —— 那块"偏一点的红"与微博那条带**是同一件事**：
 * 一块平色被交付成两个浓度，区别只在**哪一半是多数**（微博：底是多数 ⇒ 该抹；
 * 合成用例：墨是多数 ⇒ 该抬）。所以问法从"这一格是不是底"换成"这一块自己给的是几个答案"。
 *
 * 这一台量三件事：
 *   ① 这样的块有多少、多大（按真 ΔE<2 的 4 连通图分块，块内源色几乎一种）；
 *   ② 每块被交付成几套浓度：`底`(α≤4) / `地板带`(12..80) / `实墨`(≥120)，各占多少格；
 *   ③ 按**多数那一套**统一，要**抹**多少格、要**抬**多少格（抬 = 把少数侧的 0 抬到多数侧的均值）。
 * ⚠ 抬与抹**不是一回事**：抹守"只减不加"的老规矩，抬破它。这一台把两半分开报，
 *   就是为了先看清"破规矩"这一侧到底多大 —— 别拿合计当结论。
 *
 * 用法：`harness.VoteKt <目录> [目录…] [--de=128] [--min=500] [--synthetic]`
 *   （`--de` 是**模型口径**，真 ΔE 2 = 128；`--min` 只数这么大的块，抗锯齿小片一律不看）
 */
fun main(args: Array<String>) {
  val deLimit = (args.firstOrNull { it.startsWith("--de=") }?.substringAfter("=") ?: "128").toInt()
  val minCells = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=") ?: "500").toInt()
  // 一块平色被交付成两半，少数侧要占多少才算"真给了两个答案"。10% 那版把合成用例卡掉了，
  // 所以这一档要能扫：结论换不换人，是判这把尺子死活的第一件事。
  val why = args.contains("--why")
  val whyMin = (args.firstOrNull { it.startsWith("--whymin=") }?.substringAfter("=") ?: "150").toInt()
  val share = (args.firstOrNull { it.startsWith("--share=") }?.substringAfter("=") ?: "10").toInt()
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>

  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val shots = ArrayList<Triple<String, IntArray, Int>>()
  val ink = HashMap<String, IntArray>()   // 每张：抹掉的墨 / 抬起来的墨（×1000 存整数，够用）   // 名字 / [块数,双答案块,抹格,抬格,最大双答案块] / 交付总量

  fun measure(name: String, px: IntArray, w: Int, h: Int) {
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: return
    val m = r.mask ?: return
    val s = pool.get() ?: return
    val labL = pf(s, "labL") as ShortArray
    val labA = pf(s, "labA") as ShortArray
    val labB = pf(s, "labB") as ShortArray
    val total = w * h
    val solid = BooleanArray(total) { (px[it] ushr 24) == 255 }
    val al = IntArray(total) { m[it] ushr 24 }
    var delivered = 0
    for (i in 0 until total) delivered += al[i]

    val comp = IntArray(total) { -1 }
    val stack = IntArray(total)
    var nc = 0
    var blocks = 0; var two = 0; var erase = 0; var raise = 0; var biggest = 0
    var eraseInk = 0L; var raiseInk = 0L
    for (start in 0 until total) {
      if (!solid[start] || comp[start] >= 0) continue
      val sl = labL[start].toInt() and 0xffff; val sa = labA[start].toInt() and 0xffff
      val sb = labB[start].toInt() and 0xffff
      var top = 0
      stack[top++] = start; comp[start] = nc
      var n = 0; var n0 = 0; var nf = 0; var ns = 0
      var af = 0L; var as_ = 0L        // 两半各自交出的墨（按 α 累计，报「抬/抹多少墨」要用它）
      while (top > 0) {
        val c = stack[--top]; n++
        val a = al[c]
        if (a <= 4) n0++ else if (a < 80) { nf++; af += a } else { ns++; as_ += a }
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
          if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)) {
          if (j < 0 || !solid[j] || comp[j] >= 0) continue
          if (deE(labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff, sl, sa, sb) >= deLimit) continue
          comp[j] = nc; stack[top++] = j
        }
      }
      if (why && n >= whyMin) {
        // 逐块打三关：尺寸 / 少数侧材料性 / 多数是哪一侧
        val side = intArrayOf(n0, nf, ns)
        val maxSide = side.max()!!
        val maj = if (maxSide == ns) "实墨" else if (maxSide == nf) "地板带" else "底"
        println("    块 start=$start 格=$n (${"%.2f".format(n * 100.0 / total)}% 画布)  " +
          "底=$n0 地板带=$nf 实墨=$ns  多数=$maj  " +
          "尺寸关=${if (n >= minCells) "过" else "挡"} 材料性关=${if (side.count { it * 100 >= n * share } >= 2) "过" else "挡"}")
      }
      if (n >= minCells) {
        val side = intArrayOf(n0, nf, ns)
        val maxSide = side.max()!!
        val twoAnswers = side.count { it * 100 >= n * share } >= 2      // 两半各占 ≥10% 才算真给了两个答案
        blocks++
        if (twoAnswers) {
          two++
          if (maxSide == n0) { erase += n - n0; eraseInk += af + as_ }   // 多数是底 ⇒ 统一=抹
          else {                                                           // 多数是墨 ⇒ 统一=抬（破"只减不加"）
            raise += n0
            val maj = if (maxSide == ns) as_ / maxOf(ns, 1) else af / maxOf(nf, 1)
            raiseInk += maj.toLong() * n0
          }
          if (n > biggest) biggest = n
        }
      }
      nc++
    }
    // 七个数一起放：[块数, 双答案块, 抹格, 抬格, 最大块, 抹墨/1k, 抬墨/1k]
    shots += Triple(name, intArrayOf(blocks, two, erase, raise, biggest,
      (eraseInk / 1000).toInt(), (raiseInk / 1000).toInt()), delivered)
  }

  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    measure(f.name.substringBeforeLast(".").take(44), px, w, h)
  }
  if (args.contains("--synthetic")) {
    // 「三段距离」原样复刻（SelfTest 第 11 段）：红底 + 一块"偏一点的红"
    val S = 384
    val p = IntArray(S * S) { (255 shl 24) or (200 shl 16) or (20 shl 8) or 20 }
    fill(p, S, S / 10, S / 10, 70, 70, (255 shl 24) or (214 shl 16) or (60 shl 8) or 60)
    fill(p, S, S / 2 - 35, S / 2 - 35, 70, 70, (255 shl 24) or (250 shl 16) or (190 shl 8) or 60)
    fill(p, S, S * 8 / 10, S * 8 / 10, 70, 70, (255 shl 24) or (20 shl 16) or (20 shl 8) or 90)
    measure("★合成 三段距离", p, S, S)
  }

  println("n=${shots.size}   按真 ΔE<${deLimit / 64} 的 4 连通分「同一块平色」，只数 ≥$minCells 格的块")
  println("  合计：抹 ${shots.sumOf { it.second[5] }}k 墨、抬 ${shots.sumOf { it.second[6] }}k 墨")
  println("  给了两个答案的块：${shots.sumOf { it.second[1] }} 块   多数是底→要抹 ${shots.sumOf { it.second[2] }} 格" +
    "   多数是墨→要抬 ${shots.sumOf { it.second[3] }} 格（抬这一侧破「只减不加」，与已死的七把不是一回事）")
  println("  按「抹+抬」占该图交付排前 12：")
  for ((name, c, del) in shots.filter { it.second[1] > 0 }.sortedByDescending {
      (it.second[2] + it.second[3]) * 100.0 / maxOf(it.second[1], 1) }.take(12)) {
    val k = intArrayOf(c[5], c[6])
    println("    %-46s 双答案块=%-3d 最大块=%-6d 抹=%-6d格/%-6dk 抬=%-6d格/%-6dk  抹占交付=%5.2f%%"
      .format(name, c[1], c[4], c[2], k[0], c[3], k[1], k[0] * 1000.0 * 100 / maxOf(del, 1)))
  }
}

private fun fill(p: IntArray, w: Int, x0: Int, y0: Int, ww: Int, hh: Int, c: Int) {
  for (y in y0 until y0 + hh) for (x in x0 until x0 + ww) p[y * w + x] = c
}

private fun deE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2).toLong(); val da = (a1 - a2).toLong(); val db = (b1 - b2).toLong()
  return Math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
}

private fun pf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
