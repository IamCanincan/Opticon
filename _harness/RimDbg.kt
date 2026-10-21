package harness

import android.graphics.Rect
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **两遍参照各自把哪些像素认成了底，以及"同一份 α=255 里的两种颜色"各自得到什么** ——
 * 反射读模型内部的 `plate` / `ref` / `dist`，不靠外层掩码猜。
 *
 * 为什么要自己驱动两遍：`build` 里 `groundPass` 最多跑四次 [fitGround]（H1/H2/H0/H3），
 * 只有**被接受那一支**的 `plate` 会留下来（每一支都 `return`，见 [MonochromeMask] 里 groundPass
 * 的早退），所以 build 返回后读 `plate` 读的其实就是一遍的。这一版把它变成**显式**的：
 * 一遍完就拷一份、二遍完再拷一份，两列并排打出来，免得又有人（今晚的我）以为二遍会盖掉它。
 * 顺带打两遍各自的标定值 —— 二遍只往 `body` 里写，压根不改 `plate`，所以两列逐带相同，
 * "实心：只一遍算 0 ｜ 只二遍算 0"就是这件事的证据。
 *
 * ⚠ 但**自己驱动必须自己搭前置**：`propagateField` / `fitField` 读的是 `s.grid` 而不是参数，
 *   新建的 scratch 上 `grid=0` 会让 `ref` 永远填不上、洪泛把自己挤爆队列（IndexOutOfBounds
 *   in `q[tail++]`）。所以这里 reflect `grid` / `ensure` 都补上，Lab 预算也照 build 抄一份。
 *
 * 用法：`RimDbgKt`（无参数 = 两张合成图：圆角渐变底板、6b 的"白字在彩盘上"）
 *       `RimDbgKt <png|目录> …`（真图标，走同一套量法）
 */
private val MM = MonochromeMask::class.java
private val SCRATCH: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Scratch")
private val PASS: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Pass")

private fun method(owner: Class<*>, name: String): Method =
  owner.declaredMethods.first { it.name == name && !it.isSynthetic }.also { it.isAccessible = true }

private fun field(owner: Class<*>, name: String): java.lang.reflect.Field =
  owner.getDeclaredField(name).also { it.isAccessible = true }

private fun const(name: String): Int = field(MM, name).getInt(null)

/** 一块**全新**的 scratch。⚠ 必须是新的：`ensure` 只在扩容时清零，复用旧块会带着上一张的脏数据。 */
private fun freshScratch(): Any =
  SCRATCH.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

private class Snap(val s: Any) {
  fun b(name: String): ByteArray = (field(SCRATCH, name).get(s) as ByteArray).copyOf()
  fun i(name: String): IntArray = (field(SCRATCH, name).get(s) as IntArray).copyOf()
  fun sh(name: String): ShortArray = (field(SCRATCH, name).get(s) as ShortArray).copyOf()
}

private class Run(
  val title: String,
  val box: Rect,
  val plate1: ByteArray,
  val plate2: ByteArray,
  val dist1: ShortArray,
  val ref1: IntArray,
  val cov1: IntArray,
  val g1: String,
  val g2: String,
  /** 一遍是哪一支接的手、它手上的"底"是什么成色 —— 逐张只打一行时靠这几个数分堆。 */
  val hyp: Int,
  val fams: Int,
  val beta: Int,
  val share: Float,
  val bandSolid: Int,
  val h0why: Int,
  /** 模型自己那道验收的数：**墨那一侧有多大比例与底本色分不开**（≥50 就退这一支）。
   *  判据台读它，不另算一套 —— 两边算的不是同一个东西时，这里看到的"命中"和真机上的不是一回事。 */
  val inkSame: Int,
  /** 这一张被那道验收退掉过哪几支（位：1=H1、2=H2、4=H0）。接手人换没换，看的就是这个。 */
  val veto: Int,
  /** 被退那一支自己的 `inkSame` —— 分门槛该画在哪，只能看这一列的分布，不能凭手感。 */
  val vetoSame: Int,
  /** 色场本身：每格的颜色与覆盖率、格子边长，以及一遍的容差 `zero`。`--res` 的反事实要拿它们重算。 */
  val grid: Int,
  val fL: IntArray,
  val fA: IntArray,
  val fB: IntArray,
  val fCov: IntArray,
  val zero: Int,
  val gL: Int,
  val gA: Int,
  val gB: Int,
)

private fun pfield(p: Any?, name: String): Int? {
  if (p == null) return null
  return runCatching { field(PASS, name).get(p) as Int }.getOrNull()
}

private fun pfloat(p: Any?, name: String): Float =
  if (p == null) 0f else runCatching { field(PASS, name).get(p) as Float }.getOrDefault(0f)

private fun passDesc(p: Any?): String {
  if (p == null) return "?"
  fun f(name: String) = field(PASS, name).get(p)
  val zero = f("zero") as Int
  val solid = f("solid") as Int
  val span = f("span") as Int
  val share = f("share") as Float
  val meanCov = f("meanCov") as Int
  val ring = runCatching { f("ring") as Int }.getOrDefault(0)
  val families = runCatching { f("families") as Int }.getOrDefault(0)
  return "zero=$zero solid=$solid span=$span 底占=%.2f β均=$meanCov 族=$families 角=$ring".format(share)
}

/** 跑两遍参照，各自留一份 plate。全程不碰 [MonochromeMask.build]，免得第二遍盖掉第一遍。 */
private fun run(title: String, px: IntArray, n: Int): Run? {
  val box = method(MM, "contentBounds").invoke(MonochromeMask, px, n, n) as? Rect ?: return null
  val short = min(box.width(), box.height())
  val band = (short / const("RING_BAND_DIV")).coerceIn(2, 8)
  val g = if (short >= const("FIELD_MIN_SIDE")) const("FIELD_GRID") else const("FIELD_GRID_MIN")
  val s = freshScratch()
  // ⚠ `grid` 要设：propagateField / fitField 不看参数、看 `s.grid`（build 也是这么给的）。
  //    留 0 会让 `ref` 永远填不上，洪泛自己把自己挤爆队列。
  field(SCRATCH, "grid").setInt(s, g)
  method(SCRATCH, "ensure").invoke(s, n * n, g * g)
  // build 的第 0 步：Lab 预算。labChannel 是 internal，判据台与它同一次编译，直接调。
  val labL = field(SCRATCH, "labL").get(s) as ShortArray
  val labA = field(SCRATCH, "labA").get(s) as ShortArray
  val labB = field(SCRATCH, "labB").get(s) as ShortArray
  for (i in px.indices) {
    val p = px[i]
    labL[i] = MonochromeMask.labChannel(p, 0).toShort()
    labA[i] = MonochromeMask.labChannel(p, 1).toShort()
    labB[i] = MonochromeMask.labChannel(p, 2).toShort()
  }
  val sn = Snap(s)
  val ground = method(MM, "groundPass").invoke(MonochromeMask, px, n, n, box, band, g, s)
  val plate1 = sn.b("plate")
  val dist1 = sn.sh("dist")
  val ref1 = sn.i("ref")
  val cov1 = sn.i("fCov")
  val fl1 = sn.i("fL")
  val fa1 = sn.i("fA")
  val fb1 = sn.i("fB")
  val d1 = passDesc(ground)
  val body = method(MM, "bodyPass").invoke(MonochromeMask, px, n, n, box, g, s)
  return Run(
    title, box, plate1, sn.b("plate"), dist1, ref1, cov1, d1, passDesc(body),
    hyp = pfield(ground, "hyp") ?: 0,
    fams = pfield(ground, "families") ?: 0,
    beta = pfield(ground, "meanCov") ?: 0,
    share = pfloat(ground, "share"),
    grid = g, fL = fl1, fA = fa1, fB = fb1, fCov = cov1,
    zero = pfield(ground, "zero") ?: 0,
    gL = pfield(ground, "gL") ?: 0, gA = pfield(ground, "gA") ?: 0, gB = pfield(ground, "gB") ?: 0,
    bandSolid = runCatching { field(SCRATCH, "bandSolid").getInt(s) }.getOrDefault(0),
    h0why = runCatching { field(SCRATCH, "h0why").getInt(s) }.getOrDefault(0),
    inkSame = pfield(ground, "inkSame") ?: -1,
    veto = runCatching { field(SCRATCH, "veto").getInt(s) }.getOrDefault(0),
    vetoSame = runCatching { field(SCRATCH, "vetoSame").getInt(s) }.getOrDefault(-1),
  )
}

/** 实心按**源色**分组（32 级桶）：这一堆里每一种颜色各自被当成底几分、出多少墨。 */
private class Bucket(val color: Int, val n: Int, val platePct: Double, val ink: Double, val dep: Int)

private fun solidBuckets(px: IntArray, plate1: ByteArray, dist1: ShortArray, mask: IntArray?): List<Bucket> {
  val by = HashMap<Int, IntArray>()
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255) continue
    val c = (((px[i] shr 16) and 0xff) / 32 shl 10) or
      (((px[i] shr 8) and 0xff) / 32 shl 5) or ((px[i] and 0xff) / 32)
    val v = by.getOrPut(c) { IntArray(4) }
    v[0]++
    if (plate1[i].toInt() != 0) v[1]++
    if (mask != null) v[2] += (mask[i] ushr 24) and 0xff
    v[3] += (dist1[i].toInt() and 0xffff)
  }
  val tot = by.values.sumOf { it[0] }.coerceAtLeast(1)
  val list = by.entries.sortedByDescending { it.value[0] }.map { (c, v) ->
    val m = v[0].coerceAtLeast(1)
    Bucket(c, v[0], v[1] * 100.0 / m, v[2].toDouble() / m, v[3] / m)
  }
  // 只留够得着 2% 的那几组：抗锯齿杂色不配占一行（全被滤掉时留最大那一组）
  val keep = list.filter { it.n * 1000 >= tot * 5 }
  return if (keep.isEmpty()) list.take(1) else keep
}

/**
 * 三联图：**源 | 一遍判的底 | 交付的墨**。数字能指出"同一种色被劈成两半"，但**劈在哪儿**只有
 * 看得境 —— 而这一眼分掉两种病因：中屏整块白的是底（判对了，剩下的墨淡是交付的事），
 * 还是字形自己缺了一角（判错了）。写进 `<目录>/_rim/<名>.png`。
 */
private fun dumpTriple(title: String, px: IntArray, n: Int, plate1: ByteArray, mask: IntArray?, outDir: File) {
  val img = java.awt.image.BufferedImage(n * 3, n, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  /** 把源色画到 96 灰上：透明处才看得见是透明的。 */
  fun put(x0: Int, i: Int) {
    val src = px[i]
    val sa = ((src ushr 24) and 0xff) / 255.0
    fun ch(k: Int) = (((src shr k) and 0xff) * sa + 96 * (1 - sa)).roundToInt().coerceIn(0, 255)
    img.setRGB(x0 + i % n, i / n, (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
  }
  for (i in px.indices) put(0, i)
  for (i in px.indices) {
    val v = if (plate1[i].toInt() != 0) 0x20FF20 else 0x202020
    img.setRGB(n + i % n, i / n, (max((px[i] ushr 24) and 0xff, 40) shl 24) or v)
  }
  for (i in px.indices) {
    val d = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
    val g2 = 255 - d
    img.setRGB(2 * n + i % n, i / n, (0xff shl 24) or (g2 shl 16) or (g2 shl 8) or g2)
  }
  outDir.mkdirs()
  val f = File(outDir, "$title.png")
  runCatching { ImageIO.write(img, "png", f) }.onFailure { println("  写不出 ${f.name}: $it") }
}

/**
 * 残盘像素按**形状**分两类，看颜色之外还有没有别的东西能把它们分开：
 *  · **贴边**：半径 R 内碰得到空气（α<64）或内容框的边 —— 盘自己的外沿就是这一类；
 *  · **围住**：四面都被底接着 —— 那是字形轮廓圈住的背景，屏幕上盘会被丢掉，所以它"本该是盘"；
 *    但 `四块四档` 里那四块图形**也是**这一类，而它们必须上墨。所以这两类能不能分，
 *    决定了"按形状抹残盘"这条路存不存在。
 */
private fun residueShape(px: IntArray, n: Int, box: Rect, plate1: ByteArray, gL: Int, gA: Int, gB: Int,
  mask: IntArray?,
): IntArray {
  val out = IntArray(6)   // [贴边格数, 贴边墨均×100, 围住格数, 围住墨均×100, 贴边有墨, 围住有墨]
  val jnd = 12L * 64
  val R = 6
  fun nearAir(x: Int, y: Int): Boolean {
    if (x - R < box.left || x + R >= box.right || y - R < box.top || y + R >= box.bottom) return true
    for (dy in -R..R step 2) for (dx in -R..R step 2) {
      if (((px[(y + dy) * n + x + dx] ushr 24) and 0xff) < 64) return true
    }
    return false
  }
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255 || plate1[i].toInt() != 0) continue
    val dl = MonochromeMask.labChannel(px[i], 0) - gL
    val da = MonochromeMask.labChannel(px[i], 1) - gA
    val db = MonochromeMask.labChannel(px[i], 2) - gB
    if (dl * dl + da * da + db * db >= jnd * jnd) continue
    val y = i / n; val x = i - y * n
    val ink = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
    if (nearAir(x, y)) { out[0]++; out[1] += ink * 100; if (ink >= 40) out[4]++ }
    else { out[2]++; out[3] += ink * 100; if (ink >= 40) out[5]++ }
  }
  return out
}

/**
 * 残盘的**连通块**按"有没有缝"分堆 —— 这是前两次擦错之后剩下的那一个条件。
 *
 * 第 1 次拿全局均值当尺，擦掉渐变盘上的白方块；第 2 次换成最近的全覆盖格，擦掉四块里的两块。
 * 两次擦掉的都不是"随便一块同色的东西"，而是**自己有一条边界的同色块**。所以"与底同色"从来
 * 不是充分条件，还差一句：**它和四周的底之间有没有一条缝**。
 *  · 高德/菜鸟那些被切断的背景：与四周的底是**同一笔漆**，走过去 ΔE 连续 → 该归底；
 *  · 白方块、四块：与盘之间有一条 AA 边界，那是"另一个物体恰好同色" → 必须上墨。
 *
 * 每块算两个数：周长上相邻像素 ΔE76 的 **p95**（缝不缝）与**是否被底围住**（从内容框边界出发、
 * 只走非底的格子，到不到得了它）。
 */
private fun seamCensus(
  px: IntArray, n: Int, box: Rect, plate1: ByteArray, mask: IntArray?,
  gL: Int, gA: Int, gB: Int, beta: Int,
): IntArray {
  // out = [无缝格数, 无缝墨和, 无缝块数, 有缝格数, 有缝墨和, 有缝块数, 围住格数, 贴边格数]
  val out = IntArray(8)
  if (beta < 128) return out
  val total = n * n
  val jnd = 12L * 64
  val res = ByteArray(total)
  for (i in 0 until total) {
    if (((px[i] ushr 24) and 0xff) != 255 || plate1[i].toInt() != 0) continue
    val dl = MonochromeMask.labChannel(px[i], 0) - gL
    val da = MonochromeMask.labChannel(px[i], 1) - gA
    val db = MonochromeMask.labChannel(px[i], 2) - gB
    if (dl * dl + da * da + db * db < jnd * jnd) res[i] = 1
  }
  // 「从边界出发走得到吗」：只在**非底**的格子里走（残盘自己也是非底，所以走得过去）
  val reach = ByteArray(total)
  val q = IntArray(total)
  var head = 0; var tail = 0
  fun push(i: Int) { if (reach[i] == 0.toByte() && plate1[i] == 0.toByte()) { reach[i] = 1; q[tail++] = i } }
  for (x in box.left until box.right) { push(box.top * n + x); push((box.bottom - 1) * n + x) }
  for (y in box.top until box.bottom) { push(y * n + box.left); push(y * n + box.right - 1) }
  while (head < tail) {
    val i = q[head++]; val y = i / n; val x = i - y * n
    if (x > box.left) push(i - 1)
    if (x < box.right - 1) push(i + 1)
    if (y > box.top) push(i - n)
    if (y < box.bottom - 1) push(i + n)
  }
  fun de76(a: Int, b: Int): Int {
    val dl = MonochromeMask.labChannel(px[a], 0) - MonochromeMask.labChannel(px[b], 0)
    val da = MonochromeMask.labChannel(px[a], 1) - MonochromeMask.labChannel(px[b], 1)
    val db = MonochromeMask.labChannel(px[a], 2) - MonochromeMask.labChannel(px[b], 2)
    return sqrt((dl * dl + da * da + db * db).toDouble()).toInt() / 64
  }
  val seen = ByteArray(total)
  val comp = IntArray(total)
  val seam = ArrayList<Int>()
  for (i in 0 until total) {
    if (res[i] == 0.toByte() || seen[i] != 0.toByte()) continue
    var ch = 0; var ct = 0
    comp[ct++] = i; seen[i] = 1
    var enclosed = 1
    val edges = ArrayList<Int>()
    while (ch < ct) {
      val j = comp[ch++]; val y = j / n; val x = j - y * n
      if (reach[j] != 0.toByte()) enclosed = 0
      val nb = intArrayOf(
        if (x > 0) j - 1 else -1, if (x < n - 1) j + 1 else -1,
        if (y > 0) j - n else -1, if (y < n - 1) j + n else -1
      )
      for (k in nb) {
        if (k < 0) continue
        if (plate1[k] != 0.toByte()) { edges.add(de76(j, k)); continue }
        if (res[k] != 0.toByte() && seen[k] == 0.toByte()) { seen[k] = 1; comp[ct++] = k }
      }
    }
    edges.sort()
    val p95 = if (edges.isEmpty()) 0 else edges[((edges.size * 95).toInt() / 100).coerceAtMost(edges.size - 1)]
    // ⚠ `comp` 里存的是这一块的像素下标，取墨必须按 `comp[k]` —— 直接写 `it` 会把
    //   "第 0..ct 个像素"当成"这一块的像素"，量出来的是图的左上角。
    val inkSum = (0 until ct).sumOf { k -> if (mask == null) 0 else (mask[comp[k]] ushr 24) and 0xff }
    if (p95 < 8) { out[0] += ct; out[1] += inkSum; out[2]++ } else { out[3] += ct; out[4] += inkSum; out[5]++ }
    if (enclosed == 1) out[6] += ct else out[7] += ct
    seam.add(p95)
  }
  return out
}

/**
 * **盘外沿那 8 个楔口**：把"实心、没进底、且 6 格内碰得到空气"的像素按**它们背后那一格**摊开。
 * 用户点名 run.xbud / 中国移动"外圈还是有瑕疵"，两张图上都是同一形状 —— 沿 8×8 网格排开的
 * 暗色小楔子。它们只有几百格，所以 `边上=`、`残盘=` 这类按面积数的数**全都看不见它**，
 * 但它们落在盘的轮廓外面，屏幕上极扎眼。这一列问的是：它们为什么在底外。
 */
private fun rimProbe(title: String, px: IntArray, n: Int, r: Run, mask: IntArray?) {
  val byCell = HashMap<Int, IntArray>()
  // 为什么"四邻贴不贴空气"那条（变体②）对这两张完全惰性：楔子像素**在盘的抗锯齿坡里面**，
  // 四邻全是实心 —— 贴不到空气，所以那个条件永远不成立。数一下坐实。
  val nbMin = IntArray(5)
  val dHist = IntArray(13)   // 1 步内最小 α 落在哪一档：<16 / <64 / <128 / <200 / >=200
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255 || r.plate1[i].toInt() != 0) continue
    val y = i / n; val x = i - y * n
    var air = false
    for (dy in -6..6) for (dx in -6..6) {
      val yy = y + dy; val xx = x + dx
      if (yy < 0 || xx < 0 || yy >= n || xx >= n) continue
      if (((px[yy * n + xx] ushr 24) and 0xff) < 64) { air = true; break }
    }
    if (!air) continue
    // 到最近一个空气像素的**切比雪夫距离**（半径 12 内找不到就记 13）。④"贴底"能把楔子
    // 清零但把贴盘的图形一起吸了，收紧成"内侧是底、外侧 R 步内是空气"时，R 必须从这里量出来。
    var dAir = 13
    search@ for (rad in 1..12) {
      for (dy in -rad..rad) for (dx in -rad..rad) {
        if (maxOf(abs(dx), abs(dy)) != rad) continue
        val yy = y + dy; val xx = x + dx
        if (yy < 0 || xx < 0 || yy >= n || xx >= n) continue
        if (((px[yy * n + xx] ushr 24) and 0xff) < 64) { dAir = rad; break@search }
      }
    }
    dHist[dAir.coerceAtMost(12)]++
    // 楔子像素自己**离空气有多近**（1 步内的最小 α）。变体②"四邻贴不贴空气"对这两张惰性，
    // 这里就是它的原因：这些格子在盘的抗锯齿坡**里面**，四邻全是实心。
    run {
      var mn = 999
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val yy = y + dy; val xx = x + dx
        if (yy < 0 || xx < 0 || yy >= n || xx >= n) continue
        val a2 = (px[yy * n + xx] ushr 24) and 0xff
        if (a2 < mn) mn = a2
      }
      when {
        mn < 16 -> nbMin[0]++
        mn < 64 -> nbMin[1]++
        mn < 128 -> nbMin[2]++
        mn < 200 -> nbMin[3]++
        else -> nbMin[4]++
      }
    }
    val c = if (r.ref1[i] >= 0) r.ref1[i] else -1
    val v = byCell.getOrPut(c) { IntArray(6) }
    v[0]++
    v[1] += (r.dist1[i].toInt() and 0xffff)
    v[2] += if (c >= 0) r.cov1[c] else 0
    v[3] += if (mask == null) 0 else (mask[i] ushr 24) and 0xff
    v[4] += (px[i] shr 16) and 0xff; v[5] += (px[i] and 0xff)
  }
  var tot = 0; byCell.values.forEach { tot += it[0] }
  println("  贴边楔口 $tot 格，按背后那一格分组（零=${r.zero}）；" +
    "1步内邻居最小α：<16=${nbMin[0]} <64=${nbMin[1]} <128=${nbMin[2]} <200=${nbMin[3]} ≥200=${nbMin[4]}")
  println("  到空气的切比雪夫距离分布：" +
    (1..12).joinToString(" ") { "${it}步=${dHist[it]}" })
  for ((c, v) in byCell.entries.sortedByDescending { it.value[0] }.take(8)) {
    val m = v[0]
    println("    cell=%-4s n=%-5d dep均=%-4d 那格β=%-4d 出墨=%-4d RGB≈(%d,%d)".format(
      c, m, v[1] / m, v[2] / m, v[3] / m, v[4] / m, v[5] / m))
  }
}

/**
 * **「淡青那一圈」到底在哪** —— 把出墨落在中间值（40..200）的实心格画在一张 ASCII 图上，
 * 并量它离**最近的强墨格**有多远。
 *
 * 为什么要位置而不是又一个统计量：中国移动这张上，我连着两次把探针定义在"我已经想到的那批
 * 像素"上（先是按面积数、后是"α=255 且与盘同色"），结果真正的痕迹是**第三桶** ——
 * (208,240,240) 淡青 1085 格、出墨 62。它离盘沿还是离笔画，决定修法是两回事：
 * 挨着自己的笔画 = 笔画的 AA，该并进去；脱离笔画贴在盘沿 = 幽灵，该归底。
 */
private fun whereMid(px: IntArray, n: Int, mask: IntArray?, plate1: ByteArray) {
  val mid = BooleanArray(px.size)
  val strong = BooleanArray(px.size)
  var nMid = 0
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) != 255) continue
    val a = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
    if (a in 40..200) { mid[i] = true; nMid++ }
    if (a >= 200) strong[i] = true
  }
  val side = 48
  println("  中间浓度(40..200)的实心格 $nMid 格。图：# 强墨 · 中间浓度 o 底但出墨<40 空格=空气")
  for (j in 0 until side) {
    val sb = StringBuilder("   ")
    for (i in 0 until side) {
      var midHit = false; var strongHit = false; var plateHit = false; var any = false
      for (y in (j * n / side) until ((j + 1) * n / side).coerceAtMost(n)) {
        val row = y * n
        for (x in (i * n / side) until ((i + 1) * n / side).coerceAtMost(n)) {
          val k = row + x
          if (((px[k] ushr 24) and 0xff) < 8) continue
          any = true
          if (mid[k]) midHit = true
          if (strong[k]) strongHit = true
          if (plate1[k].toInt() != 0) plateHit = true
        }
      }
      sb.append(if (strongHit) '#' else if (midHit) '·' else if (plateHit) 'o' else if (any) '.' else ' ')
    }
    println(sb)
  }
  // 每一格中间浓度到最近强墨格的**城市块距离**：两趟扫描，4 邻域，精确且 O(n)。
  val INF = 1 shl 20
  val d = IntArray(px.size) { if (strong[it]) 0 else INF }
  for (y in 0 until n) {
    val row = y * n
    for (x in 0 until n) {
      val i = row + x
      var v = d[i]
      if (x > 0 && d[i - 1] + 1 < v) v = d[i - 1] + 1
      if (y > 0 && d[i - n] + 1 < v) v = d[i - n] + 1
      d[i] = v
    }
  }
  for (y in n - 1 downTo 0) {
    val row = y * n
    for (x in n - 1 downTo 0) {
      val i = row + x
      var v = d[i]
      if (x < n - 1 && d[i + 1] + 1 < v) v = d[i + 1] + 1
      if (y < n - 1 && d[i + n] + 1 < v) v = d[i + n] + 1
      d[i] = v
    }
  }
  val hist = IntArray(9)
  var far = 0
  for (i in px.indices) {
    if (!mid[i]) continue
    val k = (d[i] / 4).coerceAtMost(8)
    hist[k]++
    if (d[i] > 40) far++
  }
  println("  这些格到最近强墨格的距离（4 像素一档）：" +
    (0..8).joinToString(" ") { "${it * 4}${if (it == 8) "+" else "·"}=${hist[it]}" })
  println("  离笔画超过 40 像素的：$far 格")
}

fun main(args: Array<String>) {
  val s = 384
  val jobs = ArrayList<Pair<String, Pair<IntArray, Int>>>()
  val brief = args.contains("--brief")
  val res = args.contains("--res")
  val shape = args.contains("--shape")
  val late = args.contains("--late")
  val seam = args.contains("--seam")
  val files = args.filter { !it.startsWith("--") }
  // `--dump=目录` 与 `--brief` 同用：每张写一幅三联图，边看边核。
  val dumpDir = args.firstOrNull { it.startsWith("--dump=") }?.substringAfter("=")?.let { File(it) }
  // 只给了开关、没给文件 = 跑那两张合成用例。上一版这里判 `args.isEmpty()`，于是
  // `--brief` 一条就把合成分支关掉了，而黑字那张副本又和 SelfTest 的白字 6b 不是同一张图 ——
  // 两次"我复现了它"都是假的。
  if (args.all { it.startsWith("--") } && !args.contains("--fixtures")) {
    run {
      val p = IntArray(s * s)
      val half = s / 2; val rad = s / 6
      for (y in 0 until s) for (x in 0 until s) {
        val qx = abs(x - half).toDouble() - (half - rad)
        val qy = abs(y - half).toDouble() - (half - rad)
        val d = sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0)) +
          min(max(qx, qy), 0.0) - rad
        val cov = (0.5 - d).coerceIn(0.0, 1.0)
        if (cov <= 0.0) continue
        val t = (x + y) / (2.0 * s)
        p[y * s + x] = (((255 * cov).roundToInt()) shl 24) or
          (((10 + 120 * t).roundToInt()) shl 16) or (((40 + 120 * t).roundToInt()) shl 8) or
          (90 + 120 * t).roundToInt()
      }
      for (y in s / 3 until 2 * s / 3) for (x in s / 3 until 2 * s / 3) p[y * s + x] = -0x1000000
      jobs += "圆角渐变底板 + 白方块" to (p to s)
    }
    run {
      val p = IntArray(s * s)
      for (y in 0 until s) for (x in 0 until s) {
        val dx = x - s / 2; val dy = y - s / 2
        if (dx * dx + dy * dy <= (s / 2 - 4) * (s / 2 - 4)) p[y * s + x] = (255 shl 24) or 0x1E5AC8
      }
      // 与 SelfTest 6b **同一张图**：白色粗字（不是黑字），位置也照它。上一次这里写成黑字，
      // 于是"我复现了 6b"是假的 —— 两道验收的差别正好在白字这一侧才看得见。
      for (y in s / 2 + 6 until s / 2 + 34) for (x in s / 2 - 22 until s / 2 + 22) p[y * s + x] = -0x1
      for (y in s / 6 until s / 2 + 6) for (x in s / 2 - 22 until s / 2 + 22) p[y * s + x] = -0x1
      jobs += "满幅蓝盘 + 白字（6b）" to (p to s)
    }
  } else if (args.contains("--fixtures")) {
    // **这两张是判据台里"必须留住墨"的那两个反例** —— 前两次按颜色抹残盘就是擦在它们身上翻的车。
    // 如果"有没有缝"把它们分到「有缝」那一侧、而高德/菜鸟那些被切断的背景分到「无缝」那一侧，
    // 这条判据才立得住；分不开，就没有第三条路，残盘只能留着。
    run {
      val p = IntArray(s * s)
      for (y in 0 until s) for (x in 0 until s)
        p[y * s + x] = -0x1000000 or ((30 + 200 * (x + y) / (2.0 * s)).roundToInt() shl 16) or
          ((40 + 200 * (x + y) / (2.0 * s)).roundToInt() shl 8) or
          (120 + 100 * (x + y) / (2.0 * s)).roundToInt()
      // 白方块：与盘同色？不 —— 但它**完全被盘围着**，正是"围住"那一类
      for (y in s * 30 / 100 until s * 42 / 100) for (x in s * 30 / 100 until s * 42 / 100)
        p[y * s + x] = -0x1000000 or (245 shl 16) or (245 shl 8) or 245
      jobs += "对角渐变底+白方块（必须留住墨）" to (p to s)
    }
    run {
      val p = IntArray(s * s)
      for (i in p.indices) p[i] = -0x1000000 or (30 shl 16) or (40 shl 8) or 120
      val a0 = s * 5 / 100; val a1 = s * 42 / 100; val b0 = s * 55 / 100; val b1 = s * 92 / 100
      for (y in a0 until a1) for (x in a0 until a1) p[y * s + x] = -0x1000000 or (245 shl 16) or (245 shl 8) or 245
      for (y in a0 until a1) for (x in b0 until b1) p[y * s + x] = -0x1000000 or (250 shl 16) or (200 shl 8) or 40
      for (y in b0 until b1) for (x in a0 until a1) p[y * s + x] = -0x1000000 or (40 shl 16) or (190 shl 8) or 90
      for (y in b0 until b1) for (x in b0 until b1) p[y * s + x] = -0x1000000 or (130 shl 16) or (60 shl 8) or 210
      jobs += "四块四档（必须留住墨）" to (p to s)
    }
  } else {
    for (a in files) {
      val f = File(a)
      // 目录里通常两种都有：**只有 `-src` 是桌面真正交给模块的那张位图**。判据台的预览合成图
      // 是喂回去的输入，拿它当图标会虚增分母（这个坑踩过一次，见项目记忆「corpus denominators lie」）。
      val all = (if (f.isDirectory) f.listFiles { x -> x.isFile && x.extension.lowercase() in setOf("png", "jpg") }
        else arrayOf(f)).orEmpty().toList()
      val list = all.filter { it.name.endsWith("-src.png") }.ifEmpty { all }
      var bad = 0
      for (g in list) {
        val img = runCatching { ImageIO.read(g) }.getOrNull()
        if (img == null) { bad++; continue }
        val w = img.width; val h = img.height
        if (w != h) continue
        val p = IntArray(w * h)
        val src = IntArray(w * h)
        img.getRGB(0, 0, w, h, src, 0, w)
        for (i in src.indices) p[i] = src[i]
        jobs += g.nameWithoutExtension.take(44) to (p to w)
      }
      if (bad > 0) println("  (${f.name}：$bad 张 PNG 读不出来，跳过)")
    }
  }
  val rows = ArrayList<Triple<String, Bucket, Double>>()
  for ((title, job) in jobs) {
    val (px, n) = job
    val r = run(title, px, n)
    if (r == null) { if (!brief) println("=== $title  整张透明"); continue }
    val mask = MonochromeMask.build(px.copyOf(), n, n).mask
    if (brief) {
      // 一行一张：哪一支接的手、它认为的底有多实，然后**每一种主色各自**被当成底几分、出多少墨。
      // 「(r,g,b) n 在底% 墨均」里 在底% 既不是 0 也不是 100 的那一行，就是"同一种颜色被劈成
      // 底和墨两半" —— 用户说的"少一块一块"在数上一长这样。
      val bs = solidBuckets(px, r.plate1, r.dist1, mask)
      // 「墨那一侧自己拿到多少墨」才是症状：底侧出 0 是天经地义，墨侧出 0 才是少了一块。
      var inkSum = 0L; var inkN = 0; var hole = 0
      var gndSolid = 0
      for (i in px.indices) {
        if (((px[i] ushr 24) and 0xff) != 255) continue
        if (r.plate1[i].toInt() != 0) { gndSolid++; continue }
        inkN++
        val d = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
        inkSum += d
        if (d < 64) hole++
      }
      val inkMean = if (inkN == 0) 0.0 else inkSum.toDouble() / inkN
      println("%-46s H%d 族%d 底占=%.2f **盘实=%-6d 墨实=%-6d** β=%3d 带占=%3d 同色=%4d/%4d 拦=%d 墨格=%-6d 墨均=%3.0f 洞=%4.1f%% │ %s".format(
        title, r.hyp, r.fams, r.share, gndSolid, inkN, r.beta, r.bandSolid * 100 / 255, r.inkSame, r.vetoSame, r.veto, inkN, inkMean,
        if (inkN == 0) 0.0 else hole * 100.0 / inkN,
        bs.joinToString("  ") {
          "(%3d,%3d,%3d) %-6d %5.1f%% %3.0f".format(
            (it.color shr 10) * 32 + 16, ((it.color shr 5) and 31) * 32 + 16, (it.color and 31) * 32 + 16,
            it.n, it.platePct, it.ink)
        }))
      // ── `--res`：反事实。残盘到底是"参照取错了"还是"它本来就不是底"？──
      // 假设（2026-09-28 从小步点量到的）：盘外那一圈格子 **β≈150**，一格跨在"盘"与"空气"之间，
      // 于是盘自己的边是拿半空气的混合色当参照 → dep≈96 而容差 15。把参照换成**最近的一格
      // 全覆盖**（β≥250）重算 dep'（β=1 时 dep'=255×min(ΔE',100)/100）：掉进容差的那些，才是
      // 参照的锅；掉不进去的那些，本来就不是底，别去动 membership。
      if (args.contains("--where")) { whereMid(px, n, mask, r.plate1); continue }
      if (res) {
        // 与仓里那条 `残盘=` 同一个门槛：底自己 β 不到一半时"底的本色"是透明像素的 RGB 均值，
        // 那是噪声，不能拿它当尺（见 MonochromeMask 里 plateReal 那段）。
        if (r.beta < 128 || r.gL == 0 && r.gA == 0) {
          println("%-46s H%d 残盘=不适用（底 β=%d，没有一块实心底可谈）".format(title, r.hyp, r.beta))
          continue
        }
        val jnd = 12L * 64
        var cnt = 0; var depNow = 0L; var depWas = 0L; var fixed = 0; var beta = 0L; var nocell = 0
        for (i in px.indices) {
          if (((px[i] ushr 24) and 0xff) != 255 || r.plate1[i].toInt() != 0) continue
          val dl = MonochromeMask.labChannel(px[i], 0) - r.gL
          val da = MonochromeMask.labChannel(px[i], 1) - r.gA
          val db = MonochromeMask.labChannel(px[i], 2) - r.gB
          if (dl * dl + da * da + db * db >= jnd * jnd) continue   // 不是盘的本色 → 是字形，不管
          cnt++
          depNow += (r.dist1[i].toInt() and 0xffff)
          val c = if (r.ref1[i] >= 0) r.ref1[i] else 0
          beta += r.fCov[c]
          val cx = c % r.grid; val cy = c / r.grid
          var bc = -1; var bd = Int.MAX_VALUE
          for (j in 0 until r.grid * r.grid) {
            if (r.fCov[j] < 250) continue
            val d2 = (j % r.grid - cx) * (j % r.grid - cx) + (j / r.grid - cy) * (j / r.grid - cy)
            if (d2 < bd) { bd = d2; bc = j }
          }
          if (bc < 0) { nocell++; depWas += 9999; continue }
          val e = sqrt(
            Math.pow((MonochromeMask.labChannel(px[i], 0) - r.fL[bc]).toDouble(), 2.0) +
              Math.pow((MonochromeMask.labChannel(px[i], 1) - r.fA[bc]).toDouble(), 2.0) +
              Math.pow((MonochromeMask.labChannel(px[i], 2) - r.fB[bc]).toDouble(), 2.0)
          ).toInt() shr 6
          val cf = if (e >= 100) 255 else e * 255 / 100
          depWas += cf
          if (cf <= r.zero) fixed++
        }
        val m = cnt.coerceAtLeast(1)
        println("%-46s H%d 残盘=%-6d β均=%3d dep现在=%3d 换全覆盖格=%3d 可救=%-6d 容差=%d 可救占=%4.1f%% 无全覆盖格=%d"
          .format(title, r.hyp, cnt, beta / m, depNow / m, depWas / m, fixed, r.zero,
            fixed * 100.0 / m, nocell))
        continue
      }
      if (args.contains("--rim")) {
        rimProbe(title, px, n, r, mask)
        continue
      }
      if (late) {
        // 「该进底而没进底」用模型自己的量，不用底的全局均值：盘子是渐变/多色的时候，
        // 均值落在中间，会把一种**真实存在的、离自己那一格参照差 40 多个 dep 的颜色**判成
        // "与底同色"（浙江在线 22038 格就是这么来的）。dep ≤ 容差 才是那件事。
        var cnt = 0; var inkSum = 0L; var near = 0
        for (i in px.indices) {
          if (((px[i] ushr 24) and 0xff) != 255 || r.plate1[i].toInt() != 0) continue
          val d = (r.dist1[i].toInt() and 0xffff)
          val ink = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
          if (d <= r.zero) { cnt++; inkSum += ink }
          else if (d <= r.zero * 2) near++
        }
        println("%-46s H%d 零=%3d ｜ dep≤容差却没进底 %-6d 格（墨均 %3d）｜ 容差~2倍 %d 格"
          .format(title, r.hyp, r.zero, cnt,
            if (cnt == 0) 0 else (inkSum / cnt).toInt(), near))
        continue
      }
      if (args.contains("--block")) {
        // **块状度**：交付掩码在"跨色场格"那两道之间跳多少，对比"同一格内"相邻像素跳多少。
        // 参照是 8×8 逐格拟合的（`ref[i]` 就是这一格），所以渐变铺满整张的插画会在格子边界
        // 出现台阶 —— wisdomgame 那张中屏一眼看见一块块矩形。比值 ≈1 说明台阶不存在；
        // 明显 >1 才是"参照的格子边界被画进图里了"。
        var bSum = 0L; var bN = 0; var iSum = 0L; var iN = 0
        val bx = r.box
        for (y in bx.top until bx.bottom) {
          val row = y * n
          for (x in bx.left + 1 until bx.right) {
            val i = row + x
            if (mask == null) continue
            val a0 = (mask[i - 1] ushr 24) and 0xff
            val a1 = (mask[i] ushr 24) and 0xff
            val d = kotlin.math.abs(a1 - a0)
            if (r.ref1[i] != r.ref1[i - 1]) { bSum += d; bN++ } else { iSum += d; iN++ }
          }
        }
        val bm = if (bN == 0) 0.0 else bSum.toDouble() / bN
        val im = if (iN == 0) 0.0 else iSum.toDouble() / iN
        println("%-46s 块状比=%4.1f  跨格均跳=%5.1f 格内均跳=%5.1f  跨格对=%d".format(
          title, if (im <= 0.01) 99.0 else bm / im, bm, im, bN))
        continue
      }
      if (args.contains("--cells")) {
        // **同一种源色为什么交付成好几种浓度？** 按它背后的那一格色场分组各量一遍。
        // 抖音（2026-09-28）：白音符是墨、8736 格全同色，交付却有 4994 格 255 与 4473 格
        // 128~254 两堆 —— 猜是"参照逐格不同"（那块"底"其实是青+红两层错位影，本身多色）。
        // 这一列要么证实它（dep 随格子散开），要么否掉（dep 一致，那散的是后面某一层）。
        val byc = HashMap<Int, IntArray>()   // cell → n, depSum, depMin, depMax, inkSum, covSum
        var target = -1; var targetN = 0
        val cntC = HashMap<Int, IntArray>()
        for (i in px.indices) {
          if (((px[i] ushr 24) and 0xff) != 255 || r.plate1[i].toInt() != 0) continue
          val k = (((px[i] shr 16) and 0xff) / 24 * 64 + (((px[i] shr 8) and 0xff) / 24) * 8 +
            ((px[i] and 0xff) / 24))
          val v = cntC.getOrPut(k) { IntArray(2) }
          v[0]++; v[1] = px[i] and 0x00FFFFFF
        }
        for ((k, v) in cntC) if (v[0] > targetN) { targetN = v[0]; target = k }
        val rgb = cntC[target]!![1]
        println("$title 最大墨色桶 RGB≈(${(rgb shr 16) and 0xff},${(rgb shr 8) and 0xff},${rgb and 0xff}) " +
          "n=$targetN，按背后那一格分组：")
        for (i in px.indices) {
          if (((px[i] ushr 24) and 0xff) != 255 || r.plate1[i].toInt() != 0) continue
          val k = (((px[i] shr 16) and 0xff) / 24 * 64 + (((px[i] shr 8) and 0xff) / 24) * 8 +
            ((px[i] and 0xff) / 24))
          if (k != target) continue
          val d = (r.dist1[i].toInt() and 0xffff)
          val ink = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
          val c = if (r.ref1[i] >= 0) r.ref1[i] else -1
          val a = byc.getOrPut(c) { IntArray(6) }
          a[0]++; a[1] += d; a[2] = maxOf(a[2], d); a[3] = if (a[3] == 0) d else minOf(a[3], d)
          a[4] += ink; a[5] += r.cov1[c.coerceAtLeast(0)]
        }
        for ((c, a) in byc.entries.sortedByDescending { it.value[0] }.take(12)) {
          val m = a[0]
          println("   cell=%-5s n=%-6d dep均=%-4d [%d..%d] 出墨均=%-4d 那格β=%d".format(
            c, m, a[1] / m, a[3], a[2], a[4] / m, a[5] / m))
        }
        continue
      }
      if (shape) {
        val sh = residueShape(px, n, r.box, r.plate1, r.gL, r.gA, r.gB, mask)
        fun tok(k: String, cnt: Int, ink: Int, vis: Int) =
          "$k=$cnt/${if (cnt == 0) 0 else ink / cnt / 100}/${if (cnt == 0) 0 else vis * 100 / cnt}"
        println("%-46s H%d β=%3d 底占=%.2f 残盘 %d 格 %s %s".format(
          title, r.hyp, r.beta, r.share, sh[0] + sh[2],
          // 每个 token = 格数/墨均/看得见(墨>=40)的百分比
          tok("贴边", sh[0], sh[1], sh[4]), tok("围住", sh[2], sh[3], sh[5])
        ))
        continue
      }
      if (seam) {
        val c = seamCensus(px, n, r.box, r.plate1, mask, r.gL, r.gA, r.gB, r.beta)
        fun m(a: Int, b: Int) = if (a == 0) 0 else b / a
        println("%-46s H%d β=%3d 残盘 %d 块：%s ｜ %s".format(
          title, r.hyp, r.beta, c[0] + c[3],
          "无缝 n=%-6d 墨均=%3d 块=%d".format(c[0], m(c[0], c[1]), c[2]),
          "有缝 n=%-6d 墨均=%3d 块=%d".format(c[3], m(c[3], c[4]), c[5])
        ) + "  [围住=%d 通边=%d]".format(c[6], c[7]))
        continue
      }
      // 接手人换没换，看 `拦` 那一位；换了之后墨侧拿到多少，看 墨均/洞。
      if (r.veto != 0 && inkN > 400) rows += Triple(title, Bucket(0, inkN, r.inkSame.toDouble(), inkMean, r.veto), inkMean)
      dumpDir?.let { dumpTriple(title.replace(Regex("[^A-Za-z0-9._-]"), "_"), px, n, r.plate1, mask, it) }
      continue
    }
    println("=== $title  ${n}x$n")
    println("  第一遍（参照=背后的底）  ${r.g1}")
    println("  第二遍（参照=压上的壳）  ${r.g2}")
    println("   源α 带        n    一遍在底  二遍在底   交付墨均")
    for ((lo, hi) in listOf(1 to 32, 33 to 64, 65 to 96, 97 to 127, 128 to 199, 200 to 254, 255 to 255)) {
      var cnt = 0; var p1 = 0; var p2 = 0; var ink = 0L
      for (i in px.indices) {
        val a = (px[i] ushr 24) and 0xff
        if (a !in lo..hi) continue
        cnt++
        if (r.plate1[i].toInt() != 0) p1++
        if (r.plate2[i].toInt() != 0) p2++
        if (mask != null) ink += (mask[i] ushr 24) and 0xff
      }
      if (cnt == 0) continue
      println("   %3d..%-4d %8d  %6.1f%%   %6.1f%%   %6.0f".format(
        lo, hi, cnt, p1 * 100.0 / cnt, p2 * 100.0 / cnt, ink.toDouble() / cnt))
    }
    // 实心（α=255）那一格单独拆开：它到底进了底（那它就该出墨为 0）、还是被当成墨？
    var tot = 0; var inBoth = 0; var only1 = 0; var only2 = 0; var inNeither = 0
    var leaked = 0; var leakInk = 0L
    var lo1 = 0; var loInk = 0L
    for (i in px.indices) {
      if (((px[i] ushr 24) and 0xff) != 255) continue
      tot++
      val a = r.plate1[i].toInt() != 0
      val b = r.plate2[i].toInt() != 0
      if (a && b) inBoth++ else if (a) only1++ else if (b) only2++ else inNeither++
      if (!a && mask != null) {
        val d = (mask[i] ushr 24) and 0xff
        if (d < 40) { leaked++; leakInk += d }
      }
      // 一遍认底、二遍改口没认，且**几乎没上墨**：这就是"少一块"的长相
      if (a && !b && mask != null) {
        val d = (mask[i] ushr 24) and 0xff
        if (d < 40) { lo1++; loInk += d }
      }
    }
    println("   实心 %d 格：两遍都算底 %d ｜ 只一遍算 %d ｜ 只二遍算 %d ｜ 都不算 %d"
      .format(tot, inBoth, only1, only2, inNeither))
    println("   一遍就没认成底、出图又几乎没墨：%d 格（均 %d）".format(leaked, if (leaked == 0) 0 else leakInk.toInt() / leaked))
    println("   一遍认了、二遍改口、又没墨（＝被壳那一遍抠掉的）：%d 格（均 %d）".format(
      lo1, if (lo1 == 0) 0 else loInk.toInt() / lo1))
    // 病灶那一带背后的参照：用**第一遍**留下的 ref / dist / fCov
    val byCell = HashMap<Int, IntArray>()
    for (i in px.indices) {
      val a = (px[i] ushr 24) and 0xff
      if (a !in 200..254) continue
      if (r.plate1[i].toInt() != 0) continue
      val c = if (r.ref1[i] >= 0) r.ref1[i] else 0
      val v = byCell.getOrPut(r.ref1[i]) { IntArray(7) }
      v[0]++; v[1] += (r.dist1[i].toInt() and 0xffff); v[2] += r.cov1[c]
      v[3] += a
      v[4] += (px[i] shr 16) and 0xff; v[5] += (px[i] shr 8) and 0xff; v[6] += px[i] and 0xff
    }
    if (byCell.isNotEmpty()) {
      println("   没进一遍底的那一带，按「背后的格子」分组（cell=-1 = 参照没传到）：")
      for ((cell, v) in byCell.entries.sortedByDescending { it.value[0] }.take(5)) {
        val m = v[0]
        println("     cell=%-4s n=%-5d 一遍dep均=%-5d β=%-4d α均=%-4d 像素RGB均=(%d,%d,%d)".format(
          cell, m, v[1] / m, v[2] / m, v[3] / m, v[4] / m, v[5] / m, v[6] / m))
      }
    }
    // **分色的那一表才是"少一块一块"的判据。** 上一段那个 α 分带把"实心"当成一类，可钉钉的
    // 病灶是**同一种 α=255 里两种颜色**：白鸟与白留白该同属墨，蓝盘该是底。按源色分开各量一遍，
    // "白被划进底"（假设 A）与"白在底外但出墨本来就低"（假设 B）在这张表上是两行不同的数，
    // 不需要先看图。
    println("   实心按源色分组：色(R,G,B)          n     在底%   出墨均  dep均")
    for (it in solidBuckets(px, r.plate1, r.dist1, mask)) {
      println("     (%3d,%3d,%3d) %8d  %6.1f%%  %6.0f   %5d".format(
        (it.color shr 10) * 32 + 16, ((it.color shr 5) and 31) * 32 + 16, (it.color and 31) * 32 + 16,
        it.n, it.platePct, it.ink, it.dep))
    }
  }
  if (brief) {
    println()
    println("=== 被「墨大半与底同色」退掉过接手人的：%d / %d 张"
      .format(rows.size, jobs.size))
    // 按"少掉的那块面积"排：墨格数 × 它们缺的墨 —— 用户说的"少一块一块"就是这个乘积
    for ((t, b, ink) in rows.sortedByDescending { it.second.n * (255 - it.second.ink) }) {
      println("   %-46s 拦=%d 同色=%3d 墨格=%-6d 墨均=%3.0f 少掉≈%d 格".format(
        t, b.dep, b.platePct.toInt(), b.n, ink, (b.n * (255 - ink)).toLong()))
    }
  }
}
