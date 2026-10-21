package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 「底可以是**好几块**吗」的预演 —— 只测不改。
 *
 * 现场是 Google 翻译（`com.google.android.apps.translate`）：一张圆被一条对角线分成
 * 蓝盘与白盘，蓝盘上坐一只白色的 G、白盘上坐一个青色的"文"。现在模型挑**一块**当底
 * （蓝盘），于是白盘整块被画满；而那只 G 与白盘**本来就是同一个颜色**（#F5F5F5 vs #EEEEEE，
 * ΔE 2.5）—— 所以任何"按颜色把两块盘都认成底"的做法都会顺手把 G 也清空。
 *
 * 能分开它们的不是颜色，是**连通性**：两块盘都挨着内容框的边，而 G 被蓝盘围住、文被白盘围住。
 * 这一台就是把这条规则单独跑一遍：**从内容框的边出发，按颜色长区域，凡是挨到边的区域全算底，
 * 被围住的不算**。然后看两件事：
 *   ① 翻译那张，G 与文是不是都成了墨；
 *   ② 一批**现在就正确**的图标（红盘白字、单色剪影、字形贴边的）会不会被这条规则弄坏。
 * ②才是淘汰线。上一轮"颜色走向的延续"那一条就是靠这个预演量死在纸面上的（61 张里 16 张过线），
 * 省下一整次实现 —— 这条也照办。
 *
 * 用法：`harness.TwoPlateKtKt <目录> [--edge=384] [--tol=18] [--out=DIR]`
 *   每张报：底区数 / 底占 / 围住的墨占框 / 与模型现在的交付差多少格；
 *   `--out` 另存一张三联（源 | 模型现在 | 这条规则的二值交付）。
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
  val tol = args.firstOrNull { it.startsWith("--tol=") }?.substringAfter("=")?.toInt() ?: 18
  val refTol = args.firstOrNull { it.startsWith("--ref=") }?.substringAfter("=")?.toInt() ?: 40
  val outDir = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")?.let { File(it) }?.apply { mkdirs() }
  val dir = File(args.first { !it.startsWith("--") })
  var worse = 0
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }) {
    val px = load(f.absolutePath, edge)
    val n = edge * edge
    val lab = IntArray(n * 3)
    for (i in 0 until n) {
      lab[i] = MonochromeMask.labChannel(px[i], 0)
      lab[n + i] = MonochromeMask.labChannel(px[i], 1)
      lab[2 * n + i] = MonochromeMask.labChannel(px[i], 2)
    }
    fun de(i: Int, l: Int, a: Int, b: Int): Int {
      val dl = (lab[i] - l).toLong(); val da = (lab[n + i] - a).toLong(); val db = (lab[2 * n + i] - b).toLong()
      return kotlin.math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
    }
    // 区域生长：每个区域有自己的累计 Lab；像素加入"邻居的区域"当且仅当与该区域均值 ΔE ≤ tol。
    val reg = IntArray(n) { -1 }                       // -1 = 未分；否则区域号
    val air = IntArray(n) { 2 }                        // 0=底区域 1=内部区域 2=空气/未定
    val rl = ArrayList<Int>(); val ra = ArrayList<Int>(); val rb = ArrayList<Int>()
    val rn = ArrayList<Int>()
    val q = IntArray(n)
    var regions = 0
    for (i in 0 until n) {
      val a0 = (px[i] ushr 24) and 0xff
      if (a0 < 128) { reg[i] = -2; continue }          // 空气
      if (reg[i] >= 0) continue
      // 新开一个区域：从这个像素长出去
      val id = regions++; var head = 0; var tail = 0
      q[tail++] = i; reg[i] = id
      var sl = 0L; var sa = 0L; var sb = 0L; var cnt = 0
      rl.add(0); ra.add(0); rb.add(0); rn.add(0)
      while (head < tail) {
        val c = q[head++]
        sl += lab[c]; sa += lab[n + c]; sb += lab[2 * n + c]; cnt++
        rl[id] = (sl / cnt).toInt(); ra[id] = (sa / cnt).toInt(); rb[id] = (sb / cnt).toInt()
        val cy = c / edge; val cx = c - cy * edge
        for (d in 0 until 4) {
          val nx = cx + intArrayOf(-1, 1, 0, 0)[d]; val ny = cy + intArrayOf(0, 0, -1, 1)[d]
          if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
          val j = ny * edge + nx
          if (reg[j] != -1) continue
          if ((px[j] ushr 24 and 0xff) < 128) continue
          if (de(j, rl[id], ra[id], rb[id]) <= (tol shl 6)) { reg[j] = id; q[tail++] = j }
        }
      }
      rn[id] = cnt
    }
    // 哪些区域挨着内容框的边？
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) continue
    val touches = BooleanArray(regions)
    for (y in t..b) for (x in l..r) {
      if (x == l || x == r || y == t || y == b) { val id = reg[y * edge + x]; if (id >= 0) touches[id] = true }
    }
    val boxArea = (r - l + 1).toLong() * (b - t + 1)
    var groundCells = 0L; var inkCells = 0L; var nGround = 0
    for (id in 0 until regions) if (touches[id]) { groundCells += rn[id]; nGround++ } else inkCells += rn[id]
    // ── 嵌套深度那一版 ──
    // 上一版"围住的就算墨"在 azure 上输了：白圆(0) → 盾(1=墨) → 盾里的**人**(2) 也算墨，
    // 于是整块糊成一个饼，人反而没了。**深度奇偶**天然处理嵌套：从所有挨边的区域（含空气）
    // BFS，奇数层算墨 —— 被字形围住的那个"洞"落在偶数层 = 空 ✓，字形本身在奇数层 = 墨 ✓。
    val AIR = regions
    fun idOf(rr: Int) = if (rr == -2) AIR else rr
    val adj = Array(regions + 1) { HashSet<Int>() }
    for (y in 0 until edge) for (x in 0 until edge - 1) {
      val a = reg[y * edge + x]; val bb = reg[y * edge + x + 1]
      if (a != bb) { adj[idOf(a)].add(idOf(bb)); adj[idOf(bb)].add(idOf(a)) }
    }
    for (y in 0 until edge - 1) for (x in 0 until edge) {
      val a = reg[y * edge + x]; val bb = reg[(y + 1) * edge + x]
      if (a != bb) { adj[idOf(a)].add(idOf(bb)); adj[idOf(bb)].add(idOf(a)) }
    }
    val depth = IntArray(regions + 1) { -1 }
    val dq = IntArray(regions + 2); var dh = 0; var dt = 0
    for (id in 0 until regions) if (touches[id]) { depth[id] = 0; dq[dt++] = id }
    depth[AIR] = 0; dq[dt++] = AIR                 // 空气永远是外面
    while (dh < dt) {
      val c = dq[dh++]
      for (nb in adj[c]) if (depth[nb] < 0) { depth[nb] = depth[c] + 1; dq[dt++] = nb }
    }
    var parityInk = 0L
    for (id in 0 until regions) if (depth[id] % 2 == 1) parityInk += rn[id]
    // ── 第三版：**被围住的区域要不要成墨，跟"围住它的那一块"比** ──
    // 前两版的毛病都在"兄弟区域"上：浙江在线的波浪与"潮"字都挨着同一块蓝底，
    // 连通性把它们一起判成墨（糊住字），奇偶又靠 BFS 到达顺序碰运气 —— 都不对。
    // 但这两块其实一眼分得出：波浪是**浅一点的蓝**（离蓝底近），字是**白**（离蓝底远）。
    // 所以判据应该是"这一块离**围住它的那块**够不够远"，而不是"离所有底够不够远"：
    //   · 翻译的 G 被**蓝盘**围住 → 离蓝盘远 → 墨 ✓（它离白盘近，但白盘不是围住它的那块）；
    //   · azure 的人被**盾**围住 → 浅蓝离盾近 → 空 ✓（那本来就该是个镂空）；
    //   · 浙江在线的波浪被**蓝底**围住 → 近 → 空 ✓；"潮"字白 → 远 → 墨 ✓。
    // 递推：一个区域成了墨之后，它围住的下一层就拿**它**当参照比（人的父是盾）。
    val parent = IntArray(regions + 1) { -1 }
    val ref = IntArray(regions + 1) { -1 }                  // 该区域拿来比色的那一块
    val inkByRef = BooleanArray(regions + 1)
    fun mL(id: Int) = if (id == AIR) 0 else rl[id]
    fun mA(id: Int) = if (id == AIR) 0 else ra[id]
    fun mB(id: Int) = if (id == AIR) 0 else rb[id]
    fun d2(x: Int, y: Int): Int {
      val dl = (mL(x) - mL(y)).toLong(); val da = (mA(x) - mA(y)).toLong(); val db = (mB(x) - mB(y)).toLong()
      return kotlin.math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
    }
    val pq = IntArray(regions + 2); var ph = 0; var pt = 0
    for (id in 0 until regions) if (touches[id]) { ref[id] = id; parent[id] = id; pq[pt++] = id }
    ref[AIR] = AIR; parent[AIR] = AIR; pq[pt++] = AIR       // 空气也是底
    while (ph < pt) {
      val c = pq[ph++]
      for (nb in adj[c]) {
        if (nb == AIR || nb >= regions || parent[nb] >= 0) continue
        parent[nb] = c
        // 拿"围住它的那一块"比色；成了墨之后，它围住的下一层再拿**它**当参照。
        inkByRef[nb] = d2(nb, ref[c]) >= (refTol shl 6)
        ref[nb] = nb
        pq[pt++] = nb
      }
    }
    var refInk = 0L
    for (id in 0 until regions) if (inkByRef[id]) refInk += rn[id]
    // 与模型现在的交付比：模型交出的墨里，有多少落在"这条规则说是底"的格子上
    val m = MonochromeMask.build(px, edge, edge).mask ?: IntArray(n)
    var modelInkOnGround = 0L; var modelInk = 0L
    for (i in 0 until n) {
      if (((px[i] ushr 24) and 0xff) < 128) continue
      if ((m[i] ushr 24 and 0xff) >= 128) {
        modelInk++
        val id = reg[i]
        if (id >= 0 && touches[id]) modelInkOnGround++
      }
    }
    val flag = if (modelInk > 0 && modelInkOnGround * 100 / modelInk > 25) { worse++; "⚠" } else ""
    // 第三版与模型现在的差：模型画的墨里有多少被这版判成空、模型判成空的实心格里有多少被这版判成墨
    var refDrop = 0L; var refAdd = 0L
    for (i in 0 until n) {
      if (((px[i] ushr 24) and 0xff) < 128) continue
      val id = reg[i]; if (id < 0) continue
      val solid = (m[i] ushr 24 and 0xff) >= 128
      if (solid && !inkByRef[id]) refDrop++
      if (!solid && inkByRef[id]) refAdd++
    }
    println("%-38s 区域=%4d 挨边底=%d(框%4.1f%%) 围住墨=%4.1f%% 奇偶墨=%4.1f%% **比围住者墨=%4.1f%%**  这版:擦%4.1f%%/补%4.1f%% %s"
      .format(f.nameWithoutExtension.take(38), regions, nGround,
        groundCells * 100.0 / boxArea, inkCells * 100.0 / boxArea, parityInk * 100.0 / boxArea,
        refInk * 100.0 / boxArea,
        if (boxArea == 0L) 0.0 else refDrop * 100.0 / boxArea,
        if (boxArea == 0L) 0.0 else refAdd * 100.0 / boxArea, flag))
    if (outDir != null) {
      val sim = IntArray(n) { i -> val id = reg[i]; if (id >= 0 && !touches[id]) (0xff shl 24) or 0xFFFFFF else 0 }
      val par = IntArray(n) { i -> val id = idOf(reg[i]); if (depth[id] % 2 == 1) (0xff shl 24) or 0xFFFFFF else 0 }
      val rf = IntArray(n) { i -> val id = reg[i]; if (id >= 0 && inkByRef[id]) (0xff shl 24) or 0xFFFFFF else 0 }
      val img = BufferedImage(edge * 5 + 32, edge, BufferedImage.TYPE_INT_RGB)
      fun prev(mask: IntArray, plate: Int, ink: Int): IntArray = IntArray(mask.size) { i ->
        val v = (mask[i] ushr 24) and 0xff
        val rr = (((plate shr 16 and 0xff) * (255 - v) + (ink shr 16 and 0xff) * v) / 255) shl 16
        val gg = (((plate shr 8 and 0xff) * (255 - v) + (ink shr 8 and 0xff) * v) / 255) shl 8
        val bb = ((plate and 0xff) * (255 - v) + (ink and 0xff) * v) / 255
        (0xff shl 24) or rr or gg or bb
      }
      img.setRGB(0, 0, edge, edge, IntArray(n) { (0xff shl 24) or (px[it] and 0xFFFFFF) }, 0, edge)
      img.setRGB(edge + 8, 0, edge, edge, prev(m, 0xEDEDED, 0x1F1F1F), 0, edge)
      img.setRGB(edge * 2 + 16, 0, edge, edge, prev(sim, 0xEDEDED, 0x1F1F1F), 0, edge)
      img.setRGB(edge * 3 + 24, 0, edge, edge, prev(par, 0xEDEDED, 0x1F1F1F), 0, edge)
      img.setRGB(edge * 4 + 32, 0, edge, edge, prev(rf, 0xEDEDED, 0x1F1F1F), 0, edge)
      ImageIO.write(img, "png", File(outDir, "${f.nameWithoutExtension}.png"))
    }
  }
  println("—— 会把模型现在画的墨擦掉大半（只看「挨边即底」那一版）的张数 = $worse")
  println("列序：源 | 模型现在 | 围住即墨 | 深度奇偶 | **比围住者够远才算墨**（--ref=$refTol）")
}
