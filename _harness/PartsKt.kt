package harness

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 「不够细腻」的那一个数到底卡在哪一步。
 *
 * 模型按**源色**把画面归成块（一块 = 图上的一个部分），每块报三个量：
 *  · **ΔE** = 这一块的像素到"它背后那块参照"的平均 CIELAB 距离（**不截断**）；
 *  · **dep₁** = 截断并乘上覆盖率之后、真正进标定的那个量；
 *  · **墨** = 交出去的浓度。
 * 三个数一排列就知道信息在哪一步没的：ΔE 就分不开 → 参照本身不够细（色场）；
 * ΔE 分得开而 dep₁ 全顶在 255 → 是 `min(ΔE,EMAX)` 那道截断把差别剪掉了；
 * dep₁ 分得开而墨分不开 → 标定 / 降层把它压平了。三种修法完全不同，别看一张图猜。
 *
 * `--merge` 只问另一件事：模型数的"几块"里，**有几块是眼睛分得出的**。
 * 分块用的是 Lab 粗桶（L 每 8、a/b 每 32），一块真实区域横跨两条桶线就会被数成两块；
 * 而「让块数决定档距」那条设想吃的正是这个块数 —— 块数虚高，档距就得把浓度摊到看不见的差别上，
 * 那付出去的淡是**买了一张不存在的价格表**。这里先把 N（桶数）与 M（按源色 ΔE 合并后的数）量出来。
 *
 * 用法：`PartsKt [--edge=N] [--merge] 目录|图 …`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val merge = args.any { it == "--merge" }
  val partsOnly = args.any { it == "--parts" }
  val named = setOf("yiban", "cnspeedtest", "greenpoint", "unionpay", "googlequicksearchbox",
    "jingdong", "able.wisdomtree")
  val fld = MonochromeMask::class.java.getDeclaredField("pool")
  fld.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val pool = fld.get(MonochromeMask) as ThreadLocal<Any>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name } else listOf(f)
  }
  var sat = 0; var tot = 0
  val rows = ArrayList<String>()
  val hist = HashMap<Pair<Int, Int>, Int>()
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val (px, w, h) = if (merge) native(img) else resample(img, edge)
    val r = MonochromeMask.build(px, w, h)
    val s = pool.get()
    val parts = parts(px, w, h, s, r.mask ?: continue)
    if (parts.isEmpty()) continue
    tot++
    if (partsOnly) {
      // 机器可读的一行：面积:交付浓度:L:a:b，按块给。喂给 `_ladder_sim.cjs` 去模拟别的发档规则。
      println(f.nameWithoutExtension + ";" +
        parts.joinToString(";") { "%d:%d:%.1f,%.1f,%.1f".format(it.n, it.tone, it.l, it.a, it.b) })
    } else if (merge) {
      val n = parts.size
      val ms = intArrayOf(6, 10, 15, 25).map { clusters(parts, it) }
      var dmin = 1e9
      for (i in parts.indices) for (j in parts.indices.drop(i + 1)) {
        val dl = parts[i].l - parts[j].l; val da = parts[i].a - parts[j].a; val db = parts[i].b - parts[j].b
        dmin = minOf(dmin, sqrt(dl * dl + da * da + db * db))
      }
      hist[(n to ms[2])] = (hist[(n to ms[2])] ?: 0) + 1
      val hit = named.any { f.name.contains(it) }
      if (n >= 4 || hit) {
        val tone = parts.joinToString(",") { it.tone.toString() }
        val log = r.report.substringAfter("分档=", "?")
        rows += "%-2s%-46s N=%d  合并后M(ΔE<6/10/15/25)=%d/%d/%d/%d  最近两块ΔE=%4.1f  墨=($tone)  日志%s".format(
          if (hit) "◆" else " ", f.nameWithoutExtension.take(46), n, ms[0], ms[1], ms[2], ms[3],
          if (n >= 2) dmin else -1.0, log)
      }
    } else {
      val atCeil = parts.count { it.dep1 >= 250 }
      if (atCeil >= 2) sat++
      println("--- ${f.nameWithoutExtension}  块=${parts.size} 顶在dep=255的有 $atCeil 块")
      println("    ${r.report}")
      for (p in parts) {
        println("    n=%7d  ΔE=%5.1f  dep₁=%3d  墨=%3d   源(L,a,b)=(%.0f,%+.0f,%+.0f)".format(
          p.n, p.de, p.dep1, p.tone, p.l, p.a, p.b))
      }
    }
  }
  if (merge) {
    rows.sortedByDescending { it.substringAfter("N=").substringBefore(" ").toIntOrNull() ?: 0 }
      .forEach { println(it) }
    println()
    println("=== $tot 张。块数 N 与「按源色 ΔE<15 合并后还剩 M」的对照：")
    hist.keys.sortedWith(compareBy({ -it.first }, { it.second }))
      .forEach { println("   N=${it.first} → M=${it.second} 的有 ${hist[it]} 张") }
    val phantom = hist.filter { (k, _) -> k.first >= 5 && k.second <= 3 }.values.sum()
    println("块数≥5 但合并后≤3 块（=档距要给的是**看不见的差别**）= $phantom 张")
    return
  }
  println("=== $tot 张里有 $sat 张出现「≥2 块同时顶在 dep₁=255」")
}

/** 单链聚类：两块质心的 Lab 距离小于门槛就并成一块，返回合并后的块数。 */
private fun clusters(parts: List<P>, gap: Int): Int {
  val parent = IntArray(parts.size) { it }
  fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
  for (i in parts.indices) for (j in parts.indices.drop(i + 1)) {
    val dl = parts[i].l - parts[j].l; val da = parts[i].a - parts[j].a; val db = parts[i].b - parts[j].b
    if (sqrt(dl * dl + da * da + db * db) < gap) {
      val ra = find(i); val rb = find(j)
      if (ra != rb) parent[rb] = ra
    }
  }
  return parts.indices.count { find(it) == it }
}

/** 按原图尺寸量 —— 这样数出来的 N 与日志里 `分档=a/b` 的 b 才是同一个口径。 */
private fun native(img: java.awt.image.BufferedImage): Triple<IntArray, Int, Int> {
  val w = img.width; val h = img.height
  val a = IntArray(w * h)
  img.getRGB(0, 0, w, h, a, 0, w)
  return Triple(a, w, h)
}

private class P {
  var n = 0L; var de = 0.0; var dep1 = 0; var tone = 0
  var l = 0.0; var a = 0.0; var b = 0.0
}

/** 与模型同一套分块：源 Lab 分桶（L 每 8、a/b 每 32），面积取前 6，底板不参与。 */
private fun parts(px: IntArray, w: Int, h: Int, s: Any, out: IntArray): List<P> {
  val plate = field(s, "plate") as ByteArray
  val dist = field(s, "dist") as ShortArray
  val labL = field(s, "labL") as ShortArray
  val labA = field(s, "labA") as ShortArray
  val labB = field(s, "labB") as ShortArray
  val ref = field(s, "ref") as IntArray
  val fL = field(s, "fL") as IntArray
  val fA = field(s, "fA") as IntArray
  val fB = field(s, "fB") as IntArray
  // 内容框：与模型同一个门槛
  var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
  for (y in 0 until h) for (x in 0 until w) if (((px[y * w + x] ushr 24) and 0xff) >= 8) {
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  if (x1 < 0) return emptyList()
  val nb = 13 * 8 * 8
  val cnt = LongArray(nb)
  val de = DoubleArray(nb); val d1 = LongArray(nb); val tn = LongArray(nb)
  val ll = DoubleArray(nb); val la = DoubleArray(nb); val lb = DoubleArray(nb)
  var work = 0
  for (y in y0..y1) {
    val row = y * w
    for (x in x0..x1) {
      val i = row + x
      if (plate[i].toInt() != 0) continue
      val l = (labL[i].toInt() and 0xffff) / 64
      val aa = ((labA[i].toInt() / 64) + 128).coerceIn(0, 255)
      val bb = ((labB[i].toInt() / 64) + 128).coerceIn(0, 255)
      val k = ((l / 8) * 8 + aa / 32) * 8 + bb / 32
      val c = if (ref[i] >= 0) ref[i] else 0
      val dl = (labL[i].toInt() - fL[c]) / 64.0
      val da = (labA[i].toInt() - fA[c]) / 64.0
      val db = (labB[i].toInt() - fB[c]) / 64.0
      cnt[k]++; work++
      de[k] += sqrt(dl * dl + da * da + db * db)
      d1[k] += dist[i].toInt() and 0xffff
      tn[k] += (out[i] ushr 24)
      ll[k] += l; la[k] += aa - 128.0; lb[k] += bb - 128.0
    }
  }
  if (work < 64) return emptyList()
  val min = maxOf(64L, work.toLong() * 3 / 100)
  val res = ArrayList<P>()
  repeat(6) {
    var best = -1
    for (k in 0 until nb) if (cnt[k] >= min && (best < 0 || cnt[k] > cnt[best])) best = k
    if (best < 0) return@repeat
    val p = P()
    p.n = cnt[best]
    p.de = de[best] / cnt[best]
    p.dep1 = (d1[best] / cnt[best]).toInt()
    p.tone = (tn[best] / cnt[best]).toInt()
    p.l = ll[best] / cnt[best]; p.a = la[best] / cnt[best]; p.b = lb[best] / cnt[best]
    res.add(p)
    cnt[best] = 0
  }
  res.sortBy { it.tone }
  return res
}

private fun field(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun resample(img: java.awt.image.BufferedImage, edge: Int): Triple<IntArray, Int, Int> {
  val src = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, src, 0, img.width)
  val out = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
    out[y * edge + x] = src[sy * img.width + sx]
  }
  return Triple(out, edge, edge)
}
