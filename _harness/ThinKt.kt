package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **底像素自己的厚度分布**（只量不改）。
 *
 * 模型已经报了 `底厚=` —— 那是**整块底到自身边界距离的 p85**，一个数。一个数只能回答
 * "这块底是圆盘还是描边"，回答不了 globalspeed（全球网测）那类现场：
 * 仪表盘那道弧是蓝→绿→深蓝的渐变，**深色尾段的颜色就等于背景**，于是整段被判成底、直接淡掉；
 * 包围判据（`PartAreaKt --enclosed`）也看不见它 —— 那道弧一直顶到内容框的边，
 * 洪泛从框边出发第一个就把它收进"连着边的底"里了。
 *
 * 能分开"整块底"与"穿着底色的窄弧"的只有**局部厚度**：弧两头都贴着图形，
 * 局部厚度就是弧的宽度（几像素）；底盘再怎么写意，肚子里也有一大块离边很远的地方。
 * 这里就把 `plateThickness` 那张距离场按像素取出来，分两堆数：
 *  · **内薄** = 薄、而且离空气远（在画面肚子里）→ 那才是"穿着底色的图形"的候选；
 *  · **外薄** = 薄、但就挨着空气 → 那是底自己的外沿/羽化，动它就是把盘边啃掉。
 * 一条按厚度下的判据能不能立项，看的就是这两堆各有多大 —— 外薄大就等于误伤。
 *
 * 跑法：`harness.ThinKt [--min=N] [--shot=目录] [--slab] 目录 …`
 *   `--shot` 给候选图标叠一张红：红 = 内薄的那些格子，直接看图。
 *   `--slab` 换成问"这块底有没有被图形包住"（厚度那条量死之后剩下的形状判据）。
 */
fun main(args: Array<String>) {
  val minEdge = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 32
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")
  val slab = args.any { it == "--slab" }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val rows = ArrayList<ThinRow>()
  val slabRun = ArrayList<Pair<String, String>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    if (w < minEdge || h < minEdge) continue
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val r = runCatching { MonochromeMask.build(src, w, h) }.getOrNull()
    val mask = r?.mask ?: continue
    val s = pool.get() ?: continue
    val plate = field(s, "plate") as ByteArray
    val td = field(s, "thickDist") as IntArray
    val box = contentBounds(src, w, h) ?: continue
    val bw = box[2] - box[0] + 1; val bh = box[3] - box[1] + 1
    // ⚠ [thickDist] 是模型复用的那块数组：只有这一张**确实跑过** plateThickness 才是新鲜的，
    //   判据就是报告里的 `底厚=`（没跑过是 -1）。不这么卡一下，读到的就是上一张图留下的距离场。
    val 底厚 = pctOf(r.report, "底厚")
    if (底厚 < 0 || td.size < bw * bh) continue
    // 空气距离场：从 α<128 的格子（含框外）多源 BFS，用来把"底自己的外沿"摘出去
    val air = airDistance(src, w, h)
    var nPlate = 0
    var t3 = 0; var t6 = 0; var inner6 = 0; var outer6 = 0
    var sumT = 0
    for (y in 0 until bh) {
      val row = (y + box[1]) * w + box[0]
      for (x in 0 until bw) {
        val i = row + x
        if (plate[i] == 0.toByte()) continue
        val d = td[y * bw + x]
        if (d < 0) continue
        nPlate++; sumT += d
        if (d <= 3) t3++
        if (d <= 6) {
          t6++
          // 框外也算空气，所以顶到框边的弧会被判成"外薄" —— 这正是 globalspeed 的形状，
          // 于是这里额外记一笔：离空气 >6 的才叫内薄。
          if (air[i] > 6) inner6++ else outer6++
        }
      }
    }
    if (nPlate < 64) continue
    val report = r.report
    if (slab) slabRun += slabLine(f.name, plate, mask, src, w, h, box, 底厚, intOf(report, "字均净"))
    val row = ThinRow(
      f.name, nPlate, sumT / nPlate, 底厚, intOf(report, "字均净"),
      100.0 * t3 / nPlate, 100.0 * t6 / nPlate, 100.0 * inner6 / nPlate, 100.0 * outer6 / nPlate,
      inner6, mask, src, w, h, box, td, bw
    )
    rows += row
  }
  if (slab) {
    slabRun.sortByDescending { it.second.substringAfter("占框").substringBefore("%").toDoubleOrNull() ?: 0.0 }
    slabRun.take(20).forEach { println("%-46s %s".format(it.first.take(46), it.second)) }
    println()
    println("点名的那两张：")
    slabRun.filter { it.first.contains("globalspeed") || it.first.contains("yiban") }
      .forEach { println("%-46s %s".format(it.first.take(46), it.second)) }
    val n = slabRun.count { it.second.contains("有") }
    println()
    println("=== ${slabRun.size} 张里有 $n 张存在『被图形包住的底色块』（面积≥框的 0.5%、边界≥80% 贴图形）")
    return
  }
  val thick = rows.filter { it.底厚 >= 20 }
  val cand = thick.filter { it.内薄pct >= 3 }
  val risk = thick.filter { it.外薄pct >= 15 }
  println("有厚度数据的图标 = ${rows.size}   底厚>=20（真是块盘）= ${thick.size}   其中内薄>=3%（有穿着底色的东西）= ${cand.size}")
  println("同一批里外薄>=15%（底自己的外沿就有这么厚一片 → 按厚度下判据会啃到盘边）= ${risk.size}")
  println()
  println("%-46s %4s %5s %6s %6s %6s %6s %5s".format("图标", "底厚", "均厚", "薄≤3%", "薄≤6%", "内薄%", "外薄%", "字均净"))
  rows.sortedByDescending { it.内薄pct }.take(22).forEach { println(it.line()) }
  println()
  println("内薄绝对格数最多的 10 张：")
  rows.sortedByDescending { it.内薄格 }.take(10).forEach { println("   ${it.line()}") }
  if (shotDir != null) {
    File(shotDir).mkdirs()
    val set = (cand + rows.filter { it.name.contains("globalspeed") }).distinctBy { it.name }
      .sortedByDescending { it.内薄pct }.take(12)
    for (row in set) {
      val out = row.shot()
      ImageIO.write(out, "png", File("$shotDir/${row.name.removeSuffix(".png")}_thin.png"))
    }
    println("--shot 写了 ${set.size} 张到 $shotDir（红 = 内薄格子）")
  }
}

private class ThinRow(
  val name: String, val plateN: Int, val 均厚: Int, val 底厚: Int, val 字均净: Int,
  val 薄3pct: Double, val 薄6pct: Double, val 内薄pct: Double, val 外薄pct: Double,
  val 内薄格: Int, val mask: IntArray, val src: IntArray, val w: Int, val h: Int,
  val box: IntArray, val td: IntArray, val bw: Int,
) {
  fun line() = "%-46s %4d %5d %6.1f %6.1f %6.2f %6.1f %5d".format(
    name.removeSuffix(".png").take(46), 底厚, 均厚, 薄3pct, 薄6pct, 内薄pct, 外薄pct, 字均净)

  /** 源图铺底，内薄（薄且离空气 >6px）涂红。 */
  fun shot(): java.awt.image.BufferedImage {
    val o = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val bh = box[3] - box[1] + 1
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      val a = (src[i] ushr 24) and 0xff
      o.setRGB(x, y, if (a < 128) -0x1000001 else (0xff shl 24) or (src[i] and 0xffffff))
    }
    for (y in 0 until bh) for (x in 0 until bw) {
      val i = (y + box[1]) * w + box[0] + x
      val d = td[y * bw + x]
      if (mask[i] ushr 24 >= 128) continue
      if (d in 0..6) o.setRGB(box[0] + x, box[1] + y, -0x10000)     // 红：底里的薄格子
    }
    return o
  }
}

private fun pctOf(rep: String, key: String): Int =
  rep.split(" ").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.toIntOrNull() ?: -1

private fun intOf(rep: String, key: String): Int =
  rep.split(" ").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.toIntOrNull() ?: -1

private fun field(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

/** 与模型同一个内容框：α>=8 的外接矩形，返回 [x0,y0,x1,y1]。 */
private fun contentBounds(px: IntArray, w: Int, h: Int): IntArray? {
  var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
  for (y in 0 until h) for (x in 0 until w) if (((px[y * w + x] ushr 24) and 0xff) >= 8) {
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
}

/** 每个格子到最近的**空气**（α<128 或框外）有几步；框外的空气也算，所以顶到边的弧会是"外薄"。 */
private fun airDistance(px: IntArray, w: Int, h: Int): IntArray {
  val d = IntArray(px.size) { -1 }
  val q = IntArray(px.size)
  var head = 0; var tail = 0
  fun push(i: Int) { if (d[i] < 0) { d[i] = 0; q[tail++] = i } }
  for (y in 0 until h) for (x in 0 until w) {
    val i = y * w + x
    if (((px[i] ushr 24) and 0xff) < 128) { push(i); continue }
    var air = false
    for ((dx, dy) in NB) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h || ((px[ny * w + nx] ushr 24) and 0xff) < 128) { air = true; break }
    }
    if (air) push(i)
  }
  while (head < tail) {
    val i = q[head++]; val x = i % w; val y = i / w
    for ((dx, dy) in NB) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      val j = ny * w + nx
      if (d[j] < 0) { d[j] = d[i] + 1; q[tail++] = j }
    }
  }
  return d
}

private val NB = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

/**
 * `--slab`：找**被图形包住的底色块**。
 *
 * 局部厚度那条路量死了（见下面那组对照：globalspeed 丢的那道弧**不薄**，饿了么的"内薄"是噪点）。
 * 剩下的形状差别是：真的底**连着画布的边**，穿着底色的图形**四面都贴着图形**。
 * 这里就按这个数：把底像素连成块，每块数它的边界压在什么上面 ——
 *  · `图` = 挨着非底的实心格（图形）；
 *  · `空` = 挨着 α<128 或框外（那就是这块底自己露在外面）；
 * 一块要成为候选，得**面积≥框的 0.5%**（挡掉噪点）且边界的 ≥80% 压在图形上。
 * 同时报这块**现在交出去多少墨**：`墨<64` 才是真的丢了。
 */
private fun slabLine(
  name: String, plate: ByteArray, mask: IntArray, src: IntArray,
  w: Int, h: Int, box: IntArray, 底厚: Int, 字均净: Int,
): Pair<String, String> {
  val x0 = box[0]; val y0 = box[1]
  val bw = box[2] - x0 + 1; val bh = box[3] - y0 + 1
  val boxArea = bw.toLong() * bh
  val seen = BooleanArray(w * h)
  val q = IntArray(bw * bh)
  var bestArea = 0; var bestFig = 0; var bestAir = 0; var bestInk = 0; var bestThick = 0
  var bigArea = 0; var bigFig = 0; var bigAir = 0; var bigInk = 0; var bigThick = 0
  var totalArea = 0L; var hits = 0
  val td = thickOf(plate, w, h)
  for (y in y0..box[3]) for (x in x0..box[2]) {
    val i = y * w + x
    if (plate[i] == 0.toByte() || seen[i]) continue
    var head = 0; var tail = 0
    q[tail++] = i; seen[i] = true
    var area = 0; var fig = 0; var air = 0; var ink = 0L; var th = 0
    while (head < tail) {
      val c = q[head++]; val cx = c % w; val cy = c / w
      area++; ink += (mask[c] ushr 24); if (td[c] > th) th = td[c]
      for ((dx, dy) in NB) {
        val nx = cx + dx; val ny = cy + dy
        if (nx < x0 || ny < y0 || nx > box[2] || ny > box[3]) { air++; continue }
        val j = ny * w + nx
        if (plate[j] != 0.toByte()) { if (!seen[j]) { seen[j] = true; q[tail++] = j }; continue }
        if (((src[j] ushr 24) and 0xff) < 128) air++ else fig++
      }
    }
    if (area * 200L < boxArea) continue                       // 小于框的 0.5%：噪点
    // 先记下"这张图上最大的那块底"，**不管它够不够 slab 的条件** —— globalspeed 那道弧
    // 到底是被条件挡了还是根本没连成一块，只有对着这一列才看得出来。
    if (area > bigArea) {
      bigArea = area; bigFig = fig; bigAir = air; bigInk = (ink / area).toInt(); bigThick = th
    }
    val per = fig + air
    if (per == 0 || fig * 100 / per < 80) continue             // 边界没压在图形上：那是露在外面的底
    hits++; totalArea += area
    if (area > bestArea) {
      bestArea = area; bestFig = fig; bestAir = air; bestInk = (ink / area).toInt(); bestThick = th
    }
  }
  val verdict = if (hits == 0) "无" else "有"
  return name to "$verdict 块=$hits 占框${String.format("%.2f", totalArea * 100.0 / boxArea)}%" +
    (if (bigArea == 0) "" else String.format("  最大底块=%d格 最厚=%d 边界图:空=%d:%d 交付墨=%d", bigArea, bigThick, bigFig, bigAir, bigInk)) +
    (if (bestArea == 0) "" else String.format("  ｜最大slab=%d格 厚=%d 墨=%d", bestArea, bestThick, bestInk)) +
    "  底厚=$底厚 字均净=$字均净"
}

/** 底像素到自己边界的距离场（与模型 plateThickness 同一个算法，但数组是探针自己的，不借它的）。 */
private fun thickOf(plate: ByteArray, w: Int, h: Int): IntArray {
  val n = plate.size
  val d = IntArray(n) { -1 }
  val q = IntArray(n); var head = 0; var tail = 0
  for (y in 0 until h) for (x in 0 until w) {
    val i = y * w + x
    if (plate[i] == 0.toByte()) continue
    var edge = false
    for ((dx, dy) in NB) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h || plate[ny * w + nx] == 0.toByte()) { edge = true; break }
    }
    if (edge) { d[i] = 0; q[tail++] = i }
  }
  while (head < tail) {
    val c = q[head++]; val x = c % w; val y = c / w
    for ((dx, dy) in NB) {
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      val j = ny * w + nx
      if (plate[j] == 0.toByte() || d[j] >= 0) continue
      d[j] = d[c] + 1; q[tail++] = j
    }
  }
  return d
}
