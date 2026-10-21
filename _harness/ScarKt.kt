package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **交付浓度发生阶跃的那条边界，源图里那道对应的色阶有多大？**（只量不改）
 *
 * 为什么换这个问题。`SeamKt` 那四档补法全死在同一个前提上 —— 「离底近 = 是底吗」，
 * 而这批图上的真答案是**"离底近 = 坐在底上的东西"**。前面五把尺子也一样，全在问
 * "那块参照长什么样"。这一把**压根不找底、不量距离、不做连通**，只问一件事：
 *
 *   模型在相邻两格之间把浓度从「几乎不交」跳到「交出一段墨」，**源图在这道边上差多少？**
 *
 * 起初的猜想是「洪泛断在半路 ⇒ 浓度有硬边而源图没硬边」。**这个猜想死了，而死法是量准了的：**
 * 按真 ΔE 分档，地板带边界集中在 **4~16（61.0%）**，真墨边界集中在 **16~64（67.3%）** ——
 * 两组真分开，但分开的是"色差中等"与"色差大"，不是"有色差"与"没色差"。
 * 于是这一把作为探测器仍然不能用，三条独立的理由（`rec3` 113 + `dev1001` 35 = 148 张）：
 *   ① 换阈值换人（ΔE<8 榜首 `icon541` 1.19%、<16 换 12306 1.96%、<32 还是 12306 3.12%），
 *      而点名的浙江在线、易班一次都没进过前 20；
 *   ② 榜首是好人 —— 华为应用市场三档都在榜（README 里它记着"结果是对的"）；
 *   ③ 没有回报 —— 这一族全语料只占交付 0.13% / 0.39% / 0.53%。
 * 唯一真站得住的观察是微博：它的地板边 **41.9% 是真 ΔE<1**（zjol / yiban 是 0.0%），
 * 那半截黄盘两侧确实几乎同色 —— 但"与底同色 ⇒ 不是墨"已被 `propagateField` 头上那段反论按死，
 * 而且它只占这张图交付的 0.54%（832 格）。
 *
 * ⚠ **两处量具自己的错都在这台子上翻出来过**：① `deE` 返回的是 **×64 的定点值**
 *    （`SeamKt` 的 `lim = T shl 6` 就是现证），第一版把分档标签印成了真 ΔE，于是两组被压进
 *    同一格、报成"完全同分布"，写成了一条错的结论（`a9c4d39`，已在 README 更正）；
 *    ② 第一版没限制台阶**低那一侧**的 α，把"墨与墨之间"的大色差也数进了地板带。
 *
 * 两类台阶分开数：
 *   · `实墨边` = 台阶高的一侧 α≥120（真墨，对照组）。
 *   · `地板边` = 台阶高的一侧 α<80（微博底部那条 α=21.9、zjol 的 51.0、yiban 的 100.6 都在这附近）。
 *
 * 用法：`harness.ScarKt <目录> [目录…] [--jump=12] [--lo=4] [--hi=80] [--real=120] [--scarde=16] [--show=子串,子串]`
 */
fun main(args: Array<String>) {
  val jump = (args.firstOrNull { it.startsWith("--jump=") }?.substringAfter("=") ?: "12").toInt()
  val hiBand = (args.firstOrNull { it.startsWith("--hi=") }?.substringAfter("=") ?: "80").toInt()
  val realBand = (args.firstOrNull { it.startsWith("--real=") }?.substringAfter("=") ?: "120").toInt()
  // ⚠ 台阶的**低那一侧必须真的没交出去**（α≤loMax），否则量的是"墨与墨之间的大色差"，
  //   根本不是"底与漏过去那半截盘之间"的那道题 —— 第一版就这么错过一次（96.8% 落在 ΔE≥161）。
  val loMax = (args.firstOrNull { it.startsWith("--lo=") }?.substringAfter("=") ?: "4").toInt()
  // 「中小色差」的界：**这是这一把尺子唯一一根新常数**，所以扫三档一起报，看结论换不换人。
  val scarDe = (args.firstOrNull { it.startsWith("--scarde=") }?.substringAfter("=") ?: "16").toInt()
  val flood = args.contains("--flood")   // 量区域级代价（慢一些：反复扫整幅直到不再变化）
  val dump = args.firstOrNull { it.startsWith("--dump=") }?.substringAfter("=")
  // ⚠ **单位**：模型里那道 `deE` 返回的是 **×64 的定点值**（`SeamKt` 头上 `val lim = T shl 6` 就是
  //   这件事的现证 —— 它拿真 ΔE 左移 6 位去比 deE 的输出）。所以分档写的是**真 ΔE**，进桶前除以 64。
  //   第一版直接把定点值当真值印，报出来的"ΔE≥161"其实是真 ΔE>2.5 —— 单位错在这一族里
  //   不是无害的：它会把"几乎没色差"和"色差极大"印成同一格。
  val BINS = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64)        // **真 ΔE** 分档下界（第一档是"不足 1"）
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }

  class Row(val name: String, val delivered: Int) {
    val floor = IntArray(BINS.size)   // 地板边的源 ΔE 分布
    val ink = IntArray(BINS.size)     // 实墨边（对照）的源 ΔE 分布
    val floorInk = IntArray(BINS.size) // 这些地板边格交出去的墨（按档累计）
    var floorEdges = 0; var inkEdges = 0
    var floorCells = 0; var floorCellInk = 0   // 地板带格总数与它们交的墨
    var scarCells = 0; var scarInk = 0         // 其中「与没交出去的格之间只有中小色差」的那些格
    var regionCells = 0; var regionInk = 0     // 从那些格按同色在地板带内蔓延，整块吞掉多少
    var largest = 0                            // 其中最大连通块（区分"一整块盘"与"一圈抗锯齿毛边"）
    var aw: IntArray? = null                   // --dump 时才留：交付的 α 图
    var goneArr: BooleanArray? = null          // --dump 时才留：这条规则要抹掉的格
    var iw = 0; var ih = 0
  }
  val rows = ArrayList<Row>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val labL = pf(s, "labL") as ShortArray
    val labA = pf(s, "labA") as ShortArray
    val labB = pf(s, "labB") as ShortArray
    val solid = BooleanArray(w * h) { (px[it] ushr 24) == 255 }
    val scar = BooleanArray(w * h)
    val al = IntArray(w * h) { m[it] ushr 24 }
    var all = 0
    for (i in 0 until w * h) all += al[i]
    val row = Row(f.name.substringBeforeLast(".").take(46), all)

    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      if (!solid[i]) continue
      for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
        if (!solid[j] || j <= i) continue
        val hi = maxOf(al[i], al[j]); val lo = minOf(al[i], al[j])
        if (lo > loMax || hi - lo < jump) continue
        val de = deE(labL[i].toInt() and 0xffff, labA[i].toInt() and 0xffff, labB[i].toInt() and 0xffff,
          labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff)
        val det = de / 64                       // 定点值 → 真 ΔE
        var b = BINS.size - 1
        while (b > 0 && det < BINS[b]) b--
        if (hi >= realBand) { row.ink[b]++; row.inkEdges++ }
        else if (hi < hiBand) {
          row.floor[b]++; row.floorInk[b] += hi; row.floorEdges++
          if (det < scarDe) scar[if (al[i] > al[j]) i else j] = true   // 高那一侧那格
        }
      }
    }
    // 按**格**而不是按边数收口：一格可能有好几条这样的边，抹的时候是整格一起抹
    for (i in 0 until w * h) if (al[i] >= jump && al[i] < hiBand && solid[i]) {
      row.floorCells++; row.floorCellInk += al[i]
      if (scar[i]) { row.scarCells++; row.scarInk += al[i] }
    }
    // **区域级**的代价：真要改，改的是"并块的决策"而不是边界那一格 —— 从疤痕格出发，
    // 只在地板带内部、且相邻两格源 ΔE<scarDe 时才蔓延，看整块吞掉多少。
    if (flood) {
      // ⚠ **锚定，不是链式**：蔓延的条件是"这一块与**当初那个没交出去的邻居**同色"，
      //   而不是"与上一格 ΔE<scarDe"。链式那一版从一圈真抗锯齿毛边出发，能一路链穿整个车头
      //   （驾考宝典 2201 格 = 车身本身，抹掉就是伤人）—— 与 SeamKt ④「带容差、步数封顶」同一种死法。
      val gone = BooleanArray(w * h)
      val seed = IntArray(w * h)          // 每格锚定的那个"没交出去的邻居"
      val q = ArrayList<Int>(1024)
      for (i in 0 until w * h) {
        if (!scar[i]) continue
        val y = i / w; val x = i - y * w
        if (x == 0 || x == w - 1 || y == 0 || y == h - 1) continue
        for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
          if (!solid[j] || al[j] > loMax) continue
          val det = deE(labL[i].toInt() and 0xffff, labA[i].toInt() and 0xffff, labB[i].toInt() and 0xffff,
            labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff) / 64
          if (det < scarDe) { if (!gone[i]) { gone[i] = true; seed[i] = j; q.add(i) }; break }
        }
      }
      var head = 0
      while (head < q.size) {
        val c = q[head++]
        val g = seed[c]
        val cy = c / w; val cx = c - cy * w
        if (cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1) continue
        for (j in intArrayOf(c - 1, c + 1, c - w, c + w)) {
          if (gone[j] || !solid[j] || al[j] < jump || al[j] >= hiBand) continue
          val det = deE(labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff,
            labL[g].toInt() and 0xffff, labA[g].toInt() and 0xffff, labB[g].toInt() and 0xffff) / 64
          if (det < scarDe) { gone[j] = true; seed[j] = g; q.add(j) }
        }
      }
      for (i in 0 until w * h) if (gone[i]) { row.regionCells++; row.regionInk += al[i] }
      // **这一块自己有多大**：抗锯齿毛边是一堆 1~3 格的小片，漏出去的盘是一整块。
      // 这是"形状"那一侧的量，不问参照 —— 4 连通取最大块。
      run {
        val comp = IntArray(w * h)
        var best = 0
        val st = ArrayList<Int>(64)
        for (i in 0 until w * h) {
          if (!gone[i] || comp[i] != 0) continue
          var size = 0
          st.add(i); comp[i] = 1
          while (st.isNotEmpty()) {
            val c = st.removeAt(st.size - 1); size++
            val cy = c / w; val cx = c - cy * w
            for (j in intArrayOf(c - 1, c + 1, c - w, c + w)) {
              val jy = j / w; val jx = j - jy * w
              if (!gone[j] || comp[j] != 0 || jx < 0 || jx >= w || jy < 0 || jy >= h) continue
              if (cx == 0 && jx == w - 1) continue; if (cx == w - 1 && jx == 0) continue
              comp[j] = 1; st.add(j)
            }
          }
          if (size > best) best = size
        }
        row.largest = best
      }
      if (dump != null) { row.aw = al; row.goneArr = gone; row.iw = w; row.ih = h }
    }
    rows += row
  }

  fun line(tag: String, a: IntArray, tot: Int) =
    "  $tag 共$tot 道边  " + (0 until BINS.size).filter { a[it] > 0 }.joinToString(" ") { i ->
      val lab = if (i == 0) "ΔE<1" else "ΔE≥%2d".format(BINS[i])
      "$lab:${a[i]}(${"%.1f".format(a[i] * 100.0 / maxOf(tot, 1))}%)"
    }

  val fTot = IntArray(BINS.size); val iTot = IntArray(BINS.size)
  var fAll = 0; var iAll = 0
  for (r in rows) {
    for (b in 0 until BINS.size) { fTot[b] += r.floor[b]; iTot[b] += r.ink[b] }
    fAll += r.floorEdges; iAll += r.inkEdges
  }
  // `--dump=<目录>`：把**要抹的那块**画出来自己看。灰=交付的浓度，红=这条规则要抹掉的格。
  // 写代码之前先看图的理由在本仓是硬的：合计指标看不出"把物体本身抹掉了"。
  if (dump != null) {
    java.io.File(dump).mkdirs()
    for ((idx, r) in rows.withIndex()) {
      val a = r.aw; val g = r.goneArr
      if (r.regionCells == 0 || a == null || g == null) continue
      val img = java.awt.image.BufferedImage(r.iw, r.ih, java.awt.image.BufferedImage.TYPE_INT_ARGB)
      for (i in 0 until r.iw * r.ih) {
        // 不透明灰底：**越黑=交出去的墨越浓**（原图交的是透明底上的黑，直接写 α 在图片浏览器里几乎看不见）
        val v = 255 - a[i]
        img.setRGB(i % r.iw, i / r.iw, if (g[i]) 0xffff3030.toInt() else (0xff000000.toInt() or (v shl 16) or (v shl 8) or v))
      }
      ImageIO.write(img, "png", java.io.File("$dump/${idx}_${r.name.take(40)}.png"))
    }
    println("  图写到 $dump/ （灰=交付浓度，红=这条规则要抹掉的格）：${rows.count { it.regionCells > 0 }} 张")
  }
  println("n=${rows.size}   实心↔实心、交付浓度跳变≥$jump 的边界，按**源图在这道边上的 ΔE** 分档")
  println(line("地板边(α<$hiBand)", fTot, fAll))
  println(line("实墨边(α≥$realBand, 对照)", iTot, iAll))
  println("  按**格**收口（α 在 $jump..$hiBand 的实心格）：全语料 地板格=${rows.sumOf { it.floorCells }} " +
    "其中中小色差(ΔE<$scarDe)边上的=${rows.sumOf { it.scarCells }}，" +
    "这些格交的墨占全语料交付 ${"%.2f".format(rows.sumOf { it.scarInk } * 100.0 / maxOf(rows.sumOf { it.delivered }, 1))}%")
  println("  按「ΔE<$scarDe 的地板格交的墨 / 该图全部交付」排前 20（这就是**误伤面**：真要按这条抹，抹掉的就是它）：")
  for (r in rows.sortedByDescending { it.scarInk.toDouble() / maxOf(it.delivered, 1) }.take(20)) {
    println("    %-48s 地板格=%-6d 其中=%-6d 墨占交付=%5.2f%%  (全图交付 %d)"
      .format(r.name, r.floorCells, r.scarCells, r.scarInk * 100.0 / maxOf(r.delivered, 1), r.delivered))
  }
  if (flood) {
    println("  --flood 区域级（从疤痕格按同色在地板带内蔓延，合并的是**并块的决策**不是边界那一格）：")
    println("    全语料吞掉 ${rows.sumOf { it.regionCells }} 格，占交付 ${
      "%.2f".format(rows.sumOf { it.regionInk } * 100.0 / maxOf(rows.sumOf { it.delivered }, 1))}%")
    for (r in rows.filter { it.regionCells > 0 }.sortedByDescending { it.regionInk.toDouble() / maxOf(it.delivered, 1) }.take(12)) {
      println("    %-48s 疤痕格=%-6d 整块=%-7d 占交付=%5.2f%%"
        .format(r.name, r.scarCells, r.regionCells, r.regionInk * 100.0 / maxOf(r.delivered, 1)))
    }
  }
  if (flood) {
    println("  按**最大连通块**排（这是「这一块自己有多大」，不问参照；384² 的 1% = 1475 格）：")
    for (r in rows.filter { it.largest > 0 }.sortedByDescending { it.largest }.take(14)) {
      println("    %-48s 最大块=%-7d =画布 %5.2f%%  整块=%-7d 占交付=%5.2f%%"
        .format(r.name, r.largest, r.largest * 100.0 / 147456, r.regionCells,
          r.regionInk * 100.0 / maxOf(r.delivered, 1)))
    }
    val big = rows.count { it.largest >= 500 }
    println("    最大块 ≥500 格的图：$big/${rows.size}；≥1475 格(1% 画布)：${rows.count { it.largest >= 1475 }}")
  }
  val named = (args.firstOrNull { it.startsWith("--show=") }?.substringAfter("=") ?: "").split(",").filter { it.isNotBlank() }
  if (named.isNotEmpty()) {
    println("  点名（子串匹配）：")
    for (k in named) for (r in rows.filter { it.name.contains(k) }.distinctBy { it.name }) {
      println("    " + "%-48s 交付=%9d 地板格=%-6d ΔE<%d 的=%-6d 占交付=%5.2f%%  地板边=%-6d 实墨边=%d"
        .format(r.name, r.delivered, r.floorCells, scarDe, r.scarCells,
          r.scarInk * 100.0 / maxOf(r.delivered, 1), r.floorEdges, r.inkEdges))
      println(line("      地板", r.floor, r.floorEdges))
      println(line("      实墨", r.ink, r.inkEdges))
    }
  }
}

private fun deE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2) / 64.0; val da = (a1 - a2) / 64.0; val db = (b1 - b2) / 64.0
  return (Math.sqrt(dl * dl + da * da + db * db) * 64).toInt()
}

private fun pf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
