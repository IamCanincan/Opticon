package harness

/**
 * **「删掉孤立实心件」这把补刀的射程**（2026-10-20，第十段留的合法入口）。
 *
 * 规则一次拍死，只挪一个数：候选 = 面积 < 最大实心块 **1/5** 的连通件；
 * "孤立"按**离其它实心格的切比雪夫间隙** 0 / 8 / 16 三档各量一遍（间隙 0 = 只要不连着就算）。
 *
 * ⚠ 这一台**只量不改**：它读的是 `MonochromeMask.build` 交出来的那张掩码，
 *   不往生产里塞任何东西。为什么不在自写的台子上量 —— `BurrKt` 第一版就是这么把 malody
 *   报成"没开火"的（自写的加载器 vs 真流水线 `drawImage`，两张图）。
 *
 * 用法：`harness.MaskBenchKt --edge=384 --feet <语料目录>`，每张多打一行 `〔补刀〕`。
 */
private const val SOLID = 250          // 与判据台的"实心"同一档
private const val MINUS = -1
private var selfDone = false           // 自检与图标无关，一次进程只跑一遍

fun feetLine(mask: IntArray?, edge: Int): String {
  if (mask == null) return "〔补刀〕交了空掩码"
  val n = edge * edge
  // ---- 自检（每台子都要有：正对照必须开火、金标准必须不火，缺一条这台的数就不许用）----
  if (!selfDone) { selfDone = true; run {
    val gold = IntArray(n)                      // 整张实心一块 —— 必须报"件=1、候选 0"
    val pos = IntArray(n)                       // 大饼 + 两块小件：一块离 6 格、一块离 31 格
    for (y in 40 until 300) for (x in 40 until 300) gold[y * edge + x] = -0x1000000
    for (y in 40 until 300) for (x in 40 until 300) pos[y * edge + x] = -0x1000000
    for (y in 330 until 350) for (x in 330 until 350) pos[y * edge + x] = -0x1000000   // 400 格，间隙 31
    for (y in 150 until 155) for (x in 305 until 310) pos[y * edge + x] = -0x1000000   //  25 格，间隙 6
    val g = feet(gold, edge); val p = feet(pos, edge)
    if (!g.startsWith("〔补刀〕件=1 ") || !g.contains("候选件=0"))
      println("!! 自检失败：整张实心一块被报成 `$g`")
    // 间隙 6 的那块只该被"隙0"抓到；间隙 31 的那块三档都该抓到。反了/恒 0 都在这条上响。
    val want = "〔补刀〕件=3 最大=67600 实心=68025 候选件=2 隙0=2件/425格(→67600) 隙8=1件/400格(→67625) " +
      "隙16=1件/400格(→67625) +400格@330,330-349,349"
    if (p != want) println("!! 正对照失败：应 `$want`，实得 `$p`")
  } }
  return feet(mask, edge)
}

private fun feet(mask: IntArray, edge: Int): String {
  val n = edge * edge
  val comp = IntArray(n) { MINUS }
  val areas = ArrayList<Int>()
  // 每件的外接框 —— **没有这一样，这张表只能靠看图猜"删的是哪块"**。
  //   malody 那两块到底是"碎屑"还是"M 两条腿的底"，只有 y 范围能回答。
  val box = ArrayList<IntArray>()
  val stack = IntArray(n)
  for (i in 0 until n) {
    if (comp[i] != MINUS || !solid(mask, i)) continue
    val id = areas.size
    var sp = 0; stack[sp++] = i; comp[i] = id; var area = 0
    var x0 = edge; var y0 = edge; var x1 = 0; var y1 = 0
    while (sp > 0) {
      val p = stack[--sp]; area++
      val x = p % edge; val y = p / edge
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
      if (x > 0) sp = push(mask, stack, sp, comp, p - 1, id)
      if (x < edge - 1) sp = push(mask, stack, sp, comp, p + 1, id)
      if (y > 0) sp = push(mask, stack, sp, comp, p - edge, id)
      if (y < edge - 1) sp = push(mask, stack, sp, comp, p + edge, id)
    }
    areas += area; box += intArrayOf(x0, y0, x1, y1)
  }
  if (areas.isEmpty()) return "〔补刀〕件=0"
  val largest = areas.max()!!
  val total = areas.sum()
  // 候选 = 面积不到最大块 1/5 的那些件（最大块自己永远不进候选）。
  val cand = ArrayList<Int>()
  for (id in areas.indices) if (areas[id] != largest && areas[id] * 5 < largest) cand += id
  val sb = StringBuilder("〔补刀〕件=${areas.size} 最大=$largest 实心=$total 候选件=${cand.size}")
  val where = StringBuilder()
  for (gap in intArrayOf(0, 8, 16)) {
    var cut = 0; var pieces = 0
    for (id in cand) {
      // **孤立 = 离其它实心件的最小间隙 > gap**（找不到"别件的格"才算孤立）。
      //   第一版把这两个布尔写反了（"找得到邻居"当成孤立），隙=0 那一档于是恒为 0 件 ——
      //   看着像"这把刀不开火"，其实是判据自己反了。这类反了不报错的尺，必须用正对照验。
      var near = false
      var p = 0
      while (p < n && !near) {
        if (comp[p] != id) { p++; continue }
        val x = p % edge; val y = p / edge
        val y0 = maxOf(0, y - gap); val y1 = minOf(edge - 1, y + gap)
        val x0 = maxOf(0, x - gap); val x1 = minOf(edge - 1, x + gap)
        outer@ for (yy in y0..y1) for (xx in x0..x1) {
          val q = yy * edge + xx
          if (comp[q] >= 0 && comp[q] != id) { near = true; break@outer }
        }
        p++
      }
      if (!near) {
        cut += areas[id]; pieces++
        // 删的是**图上哪一块**：只有面积没有位置，就还得靠看图猜（malody 那两块是"碎屑"
        //   还是"M 两条腿的底"，y 范围一句话就能分开）。
        if (gap == 16 && where.length < 140) {
          val b = box[id]
          where.append(" +${areas[id]}格@${b[0]},${b[1]}-${b[2]},${b[3]}")
        }
      }
    }
    // ⚠ 中文紧跟在 `$name` 后面时**必须写花括号**：Kotlin 认中文当标识符的一部分，
    //   `$cut格` 会被读成一个叫 `cut格` 的变量（这次就是这么编不过的）。
    sb.append(" 隙$gap=${pieces}件/${cut}格(→${total - cut})")
  }
  return sb.append(where).toString()
}

private fun solid(mask: IntArray, i: Int): Boolean = ((mask[i] ushr 24) and 0xff) >= SOLID

/** 只在**实心格**上扩张。第一版漏了这个条件，洪泛直接穿空气 —— malody 量出"整张画布一个件"，
 *  而同一张的 `实255=11172`。这类"数得出来、数的是错东西"的尺，靠正对照抓（见文件尾的自检）。 */
private fun push(mask: IntArray, stack: IntArray, sp: Int, comp: IntArray, at: Int, id: Int): Int {
  if (comp[at] != MINUS || !solid(mask, at)) return sp
  comp[at] = id; stack[sp] = at; return sp + 1
}
