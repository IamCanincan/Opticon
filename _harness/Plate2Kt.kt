package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * **「第二块盘」那条问法的第一次数**（只量不改）。
 *
 * 2026-10-01 五版零点全撤之后收敛出来的问法：**一块图上第二大的平色区域，只要它上面坐着高对比的
 * 小图形，它就该当底。** 主人报的五条「线条不全」里 智慧树 / 饿了么 / 驾考宝典 是同一个形状
 * （一条色带 + 带上的白字），而它的天然反例是**彩色圆徽 + 白色 logo**（徽是墨、字是洞 ——
 * 照那条判据会把徽降成底，屏幕上只剩一个字的轮廓）。
 *
 * 所以这一台要答的只有一个问题：**这两类分不分得开**，候选的分隔量是
 * **"这块区域挨不挨内容框的边"** —— 带子总有一边贴着画布，圆徽整圈都在里面。
 * 挨边就报 `边=1`，同时把"它围住了多少高对比的小块"报出来，两列并排看。
 *
 * 区域长在**模型自己的词**上：非底、源实心的格子，按 `bucketOf` 同一档口径
 * （L 每 8、a/b 每 32）做 4 连通。⚠ 这是"用模型的词问模型"，不是另起一套洪泛。
 *
 * 用法：`harness.Plate2KtKt <目录> … [--min=0.03] [--top=3]`
 */
fun main(args: Array<String>) {
  val minShare = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toFloat()?.toFloat() ?: 0.03f
  val topN = args.firstOrNull { it.startsWith("--top=") }?.substringAfter("=")?.toInt() ?: 3
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name } else listOf(f)
  }
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    val out = runCatching { MonochromeMask.build(src, w, h) }.getOrNull() ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val rep = out.report
    var l = w; var t = h; var r = -1; var b2 = -1
    for (y in 0 until h) for (x in 0 until w) if ((src[y * w + x] ushr 24) >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b2) b2 = y
    }
    if (r < 0) continue
    val boxArea = (r - l + 1).toLong() * (b2 - t + 1)
    val lab = Array(3) { IntArray(w * h) }
    val buck = IntArray(w * h)
    for (i in 0 until w * h) {
      lab[0][i] = MonochromeMask.labChannel(src[i], 0)
      lab[1][i] = MonochromeMask.labChannel(src[i], 1)
      lab[2][i] = MonochromeMask.labChannel(src[i], 2)
      val li = (lab[0][i] shr 6)
      val ai = ((lab[1][i] shr 6) + 128).coerceIn(0, 255) / 32
      val bi = ((lab[2][i] shr 6) + 128).coerceIn(0, 255) / 32
      buck[i] = (li / 8) * 64 + ai * 8 + bi
    }
    val comp = IntArray(w * h) { -1 }
    val q = IntArray(w * h)
    var nc = 0
    for (y in t..b2) for (x in l..r) {
      val i = y * w + x
      if (comp[i] >= 0 || plate[i] != 0.toByte() || (src[i] ushr 24) < 255) continue
      val id = nc++; var hd = 0; var tl = 0
      comp[i] = id; q[tl++] = i
      val bk = buck[i]
      while (hd < tl) {
        val c = q[hd++]; val cy = c / w; val cx = c - cy * w
        if (cx > l) { val j = c - 1; if (comp[j] < 0 && plate[j] == 0.toByte() && (src[j] ushr 24) == 255 && buck[j] == bk) { comp[j] = id; q[tl++] = j } }
        if (cx < r) { val j = c + 1; if (comp[j] < 0 && plate[j] == 0.toByte() && (src[j] ushr 24) == 255 && buck[j] == bk) { comp[j] = id; q[tl++] = j } }
        if (cy > t) { val j = c - w; if (comp[j] < 0 && plate[j] == 0.toByte() && (src[j] ushr 24) == 255 && buck[j] == bk) { comp[j] = id; q[tl++] = j } }
        if (cy < b2) { val j = c + w; if (comp[j] < 0 && plate[j] == 0.toByte() && (src[j] ushr 24) == 255 && buck[j] == bk) { comp[j] = id; q[tl++] = j } }
      }
    }
    if (nc == 0) continue
    val sz = LongArray(nc); val sL = LongArray(nc); val sA = LongArray(nc); val sB = LongArray(nc)
    val edge = BooleanArray(nc)
    for (i in 0 until w * h) {
      val id = comp[i]; if (id < 0) continue
      sz[id]++; sL[id] += lab[0][i]; sA[id] += lab[1][i]; sB[id] += lab[2][i]
    }
    for (y in t..b2) for (x in l..r) if (x == l || x == r || y == t || y == b2) {
      val id = comp[y * w + x]; if (id >= 0) edge[id] = true
    }
    // 邻接：共边数 + 邻居的 Lab 累加（只为算"围住了多少高对比的小块"）
    val nb = Array(nc) { HashMap<Int, Int>() }
    fun link(a: Int, c: Int) {
      if (a < 0 || c < 0 || a == c) return
      nb[a][c] = (nb[a][c] ?: 0) + 1; nb[c][a] = (nb[c][a] ?: 0) + 1
    }
    for (y in t..b2) for (x in l until r) link(comp[y * w + x], comp[y * w + x + 1])
    for (y in t until b2) for (x in l..r) link(comp[y * w + x], comp[(y + 1) * w + x])
    fun mean(i: Int, k: Int): Int =
      ((if (k == 0) sL[i] else if (k == 1) sA[i] else sB[i]) / sz[i]).toInt()
    fun de(i: Int, j: Int): Int {
      val dl = mean(i, 0) - mean(j, 0); val da = mean(i, 1) - mean(j, 1); val db = mean(i, 2) - mean(j, 2)
      return sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt()
    }
    val order = (0 until nc).filter { sz[it] * 1.0 / boxArea >= minShare }.sortedByDescending { sz[it] }
    // ⚠ **连通块这一层在这类图上没有意义**（第一版就死在这）：`bucketOf` 把一条渐变带切成
    //   几千片，于是"这块区域挨不挨框边"恒报 0（没有一片能一路走到框），"围住了多少高对比小块"
    //   数的是抗锯齿碎屑（一条带报 551 / 1185 个"被它围住的字"）。**这是一把没牙的量具。**
    // 换**精确色**：平色区域本来就是一整块同一个 RGB，不会因渐变而碎；挨不挨框、围没围字，
    // 问的是"这种颜色的像素"，不是"这一块连通域"。
    val byCol = HashMap<Int, ArrayList<Int>>()
    for (y in t..b2) for (x in l..r) {
      val i = y * w + x
      if ((src[i] ushr 24) != 255) continue
      byCol.getOrPut(src[i] and 0x00FFFFFF) { ArrayList() }.add(i)
    }
    val cols = byCol.entries.filter { it.value.size * 1.0 / boxArea >= minShare }
      .sortedByDescending { it.value.size }
    if (cols.isEmpty()) continue
    println("${f.name.substringBefore("_")}  底占=${Regex("底占=(\\S+)").find(rep)?.groupValues?.get(1)}  框=${boxArea}")
    for ((col, px) in cols.take(topN)) {
      var onEdge = 0
      for (i in px) {
        val y = i / w; val x = i - y * w
        if (x <= l + 1 || x >= r - 1 || y <= t + 1 || y >= b2 - 1) onEdge++
      }
      // 被这种颜色"围住"的、离它够远的其它颜色：取每种远色的像素，看它四邻是不是几乎全是这一色
      val cl = MonochromeMask.labChannel(col or (0xff shl 24), 0)
      val ca = MonochromeMask.labChannel(col or (0xff shl 24), 1)
      val cb = MonochromeMask.labChannel(col or (0xff shl 24), 2)
      var far = 0L; var farEnc = 0L; var kinds = 0
      for ((c2, p2) in byCol) {
        if (c2 == col) continue
        val dl = MonochromeMask.labChannel(c2 or (0xff shl 24), 0) - cl
        val da = MonochromeMask.labChannel(c2 or (0xff shl 24), 1) - ca
        val db = MonochromeMask.labChannel(c2 or (0xff shl 24), 2) - cb
        val d = sqrt((dl.toLong() * dl + da.toLong() * da + db.toLong() * db).toDouble()).toInt() shr 6
        if (d < 40 || p2.size * 8 > px.size) continue      // 要"高对比"且"明显更小"
        kinds++; far += p2.size
        var enc = 0
        for (i in p2) {
          val y = i / w; val x = i - y * w
          var n = 0
          for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            if ((src[ny * w + nx] and 0x00FFFFFF) == col) n++
          }
          if (n >= 5) enc++
        }
        farEnc += enc.toLong()
      }
      println("   %06x 占框=%-5.1f%%  **挨框=%-5.1f%%**  远而小的色=%-2d种 其中贴在这一色上的格=%-5.1f%%".format(
        col, px.size * 100.0 / boxArea, onEdge * 100.0 / px.size, kinds,
        if (far == 0L) 0.0 else farEnc * 100.0 / far))
    }
  }
}

private fun fld(o: Any, name: String): Any {
  for (c in generateSequence(o::class.java) { it.superclass })
    for (f in c.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
