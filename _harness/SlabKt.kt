package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **交付出来的每一大块，自己是什么形状？**（只量不改）
 *
 * 为什么要这一台。`secondPlate` 的四道门里，把 Google 翻译那块灰盘与 B 站那圈电视机壳分开的
 * 是**腐蚀厚度**（翻译 40 轮 / B 站 14 轮）。驾考宝典的红底带卡在**厚度 15 轮**上 ——
 * 与那圈壳只差一格，而它另外三道门过得干干净净（占轮廓 58.4%、身上坐着 3860 格远色、占框 10.1%）。
 * 所以要么这道门本来就不该管"带"与"壳"的分，要么得换一根真的分得开的轴。
 *
 * 猜的是：**带在一个方向薄、另一个方向长；壳在两个方向都薄**。于是量三样：
 *   · `长短边比` = 包围盒长边 / 短边；
 *   · `填盒` = 块格数 / 包围盒格数（带 ≈ 1，一圈壳 ≈ π/4 以下）；
 *   · `厚度` = 腐蚀轮数（与那道门同一个口径，好对照）。
 * ⚠ 不在 `Plate3Kt` 那套**粗格**区域上量：那边"块0"横跨整幅（长短边比算出来 1.3），
 *   因为它按拟合色场长成，不是一条带。这一台在**像素级**上取墨做连通块。
 *
 * 用法：`harness.SlabKtKt <目录> [目录…] [--min=6] [--top=3]`（`--min` 是占画布的百分比）
 */
fun main(args: Array<String>) {
  val minPct = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=") ?: "6").toDouble()
  val topN = (args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=") ?: "3").toInt()
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val printSeeds = args.contains("--seeds")
  val out = ArrayList<Array<Comparable<*>>>()
  var nImg = 0; var nSeeded = 0
  var rimTotal = 0
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val plate = pf(s, "plate") as ByteArray
    val labL = pf(s, "labL") as ShortArray
    val labA = pf(s, "labA") as ShortArray
    val labB = pf(s, "labB") as ShortArray
    // 候选 = 实心、非底、且真交出了墨的格
    val on = BooleanArray(w * h) { (px[it] ushr 24) == 255 && plate[it] == 0.toByte() && (m[it] ushr 24) >= 12 }
    // secondPlate 当年的第一道早期返回：轮廓 = 实心↔透明那一圈断点，`seeds < 64` 直接 return 0。
    // 满幅自适应图标整张不透明，框内可能一个断点都没有 —— 顺手数一下它到底给多少。
    var seeds = 0
    for (y in 0 until h) for (x in 1 until w - 1) {
      val i = y * w + x
      if ((px[i] ushr 24) == 255 && ((px[i - 1] ushr 24) != 255 || (px[i + 1] ushr 24) != 255)) seeds++
    }
    for (y in 1 until h - 1) for (x in 0 until w) {
      val i = y * w + x
      if ((px[i] ushr 24) == 255 && ((px[i - w] ushr 24) != 255 || (px[i + w] ushr 24) != 255)) seeds++
    }
    nImg++; if (seeds >= 64) nSeeded++
    if (printSeeds) println("  %-40s 轮廓种子=%d%s".format(f.name.take(40), seeds,
      if (seeds < 64) "   ← 当年在这里直接 return 0" else ""))
    var imgInk = 0; var imgBig = 0.0; var imgBlocks = 0
    val rim = BooleanArray(w * h)
    run {
      val q = IntArray(w * h); var t = 0
      for (y in 0 until h) for (x in 1 until w - 1) {
        val i = y * w + x
        if ((px[i] ushr 24) == 255 && ((px[i - 1] ushr 24) != 255 || (px[i + 1] ushr 24) != 255)) { rim[i] = true; q[t++] = i }
      }
      for (y in 1 until h - 1) for (x in 0 until w) {
        val i = y * w + x
        if ((px[i] ushr 24) == 255 && !rim[i] && ((px[i - w] ushr 24) != 255 || (px[i + w] ushr 24) != 255)) { rim[i] = true; q[t++] = i }
      }
      var from = 0
      for (step in 0 until 3) {
        val to = t
        for (u in from until to) {
          val c = q[u]; val cy = c / w; val cx = c - cy * w
          for (j in intArrayOf(c - 1, c + 1, c - w, c + w)) {
            if (j < 0 || j >= w * h || rim[j] || (px[j] ushr 24) != 255) continue
            val jy = j / w; val jx = j - jy * w
            if (jx < 0 || jx >= w) continue
            rim[j] = true; q[t++] = j
          }
        }
        from = to
      }
      rimTotal = t
    }
    val seen = BooleanArray(w * h)
    val stack = IntArray(w * h)
    var cid = 0
    for (start in 0 until w * h) {
      if (!on[start] || seen[start]) continue
      cid++
      var sp = 0; stack[sp++] = start; seen[start] = true
      var n = 0
      var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
      val cells = ArrayList<Int>(1024)
      while (sp > 0) {
        val c = stack[--sp]; n++; cells.add(c)
        val cy = c / w; val cx = c - cy * w
        if (cx < x0) x0 = cx; if (cx > x1) x1 = cx
        if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
        for (j in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
          if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)) {
          if (j < 0 || !on[j] || seen[j]) continue
          seen[j] = true; stack[sp++] = j
        }
      }
      var onRim = 0; var carry = 0
      var ml = 0L; var ma = 0L; var mb = 0L
      for (c in cells) { ml += (labL[c].toInt() and 0xffff); ma += (labA[c].toInt() and 0xffff); mb += (labB[c].toInt() and 0xffff) }
      val msl = (ml / n).toInt(); val msa = (ma / n).toInt(); val msb = (mb / n).toInt()
      for (c in cells) {
        if (rim[c]) onRim++
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(c - 1, c + 1, c - w, c + w)) {
          if (j < 0 || j >= w * h || seen[j]) continue
          if ((px[j] ushr 24) != 255 || (m[j] ushr 24) < 12) continue
          if (deE(labL[j].toInt() and 0xffff, labA[j].toInt() and 0xffff, labB[j].toInt() and 0xffff, msl, msa, msb) >= (40 shl 6)) carry++
        }
      }
      val pct = n * 100.0 / (w * h)
      imgBlocks++
      if (pct > imgBig) imgBig = pct
      if (pct < minPct) continue
      val bw = x1 - x0 + 1.0; val bh = y1 - y0 + 1.0
      // 腐蚀厚度：与 plateThickness 同口径，一层层剥到空
      var cur = BooleanArray(w * h)
      for (c in cells) cur[c] = true
      val set = cells
      var rounds = 0
      while (true) {
        val nxt = BooleanArray(w * h)
        var any = 0
        for (c in set) {
          if (!cur[c]) continue
          val cy = c / w; val cx = c - cy * w
          if (cx == 0 || cy == 0 || cx == w - 1 || cy == h - 1) continue
          if (cur[c - 1] && cur[c + 1] && cur[c - w] && cur[c + w]) { nxt[c] = true; any++ }
        }
        if (any == 0) break
        rounds++
        cur = nxt
        if (rounds > 200) break
      }
      out += arrayOf(f.name.substringAfter("_").substringBefore("-src").take(30), pct, onRim * 100.0 / maxOf(rimTotal, 1), carry, maxOf(bw, bh) / maxOf(1.0, minOf(bw, bh)),
        n / (bw * bh), rounds, cells.size)
    }
  }
  println("轮廓种子 ≥64 的图：$nSeeded/$nImg —— 这就是 secondPlate 恒 0 的那道门吗？")
  println("每块（只列 占轮廓≥25% 且 远色≥300 的）：占画布% / 长短边比 / 填盒 / 腐蚀厚度轮（候选=实心·非底·α≥12，4 连通）")
  for (row in out.filter { (it[2] as Double) >= 25.0 && (it[3] as Int) >= 300 }.sortedByDescending { it[1] as Double }.take(topN * 8)) {
    println("  %-30s 占画布=%5.1f%% 占轮廓=%5.1f%% 远色=%-6d 比=%4.1f 填盒=%4.2f 厚度=%3d 轮"
      .format(row[0], row[1] as Double, row[2] as Double, row[3] as Int, row[4] as Double, row[5] as Double, row[6] as Int))
  }
}

/** 模型口径：输入 Lab 是 ×64，输出也是真 ΔE ×64（与 MonochromeMask.deltaE 同一口径）。 */
private fun deE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2).toLong(); val da = (a1 - a2).toLong(); val db = (b1 - b2).toLong()
  return Math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
}

private fun pf(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
