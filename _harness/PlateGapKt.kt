package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **两块都在"背景"那一侧的相邻颜色，源图里差多少**（只量不改）。
 *
 * 为什么是这一件（2026-10-12 深夜 → 10-13 凌晨）：主人说易班「蓝色和白色没有分界」，而且**只有这一张**有问题
 * —— 1 个正例 + 26 个反例，只要找一条**只把易班挑出来**的轴。
 *
 * ⚠ 第一版按**精确颜色**归族，量出来易班「两块背景之间明度差 = 0」：那张的蓝是**渐变**，
 *   精确颜色在它上面碎成一条条细带，量到的最长边界成了两条几乎同色的蓝。**归族必须按色桶**
 *   （L 每 8、a/b 每 32 —— 与 `separation()` / `PartsKt` 同一套，也就是模型自己分"部分"的那一套）。
 * ⚠ 判"是不是背景"用模型自己的 `plate` 数组（反射进它的 scratch）：这正是"模型把这块让给了底"的定义。
 *
 * 每张报两段：**最长的那条底-底边界**，与**明度差最大的那条（≥minlen 格）** —— 后者才是"该看得出差别"的候选。
 *
 * 用法：`PlateGapKt <目录> [--minlen=N]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val minLen = (args.firstOrNull { it.startsWith("--minlen=") }?.substringAfter("="))?.toIntOrNull() ?: 30
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("_modelbg") && !it.name.contains("_mono") && !it.name.contains("_bg")
  }.sortedBy { it.name }
  val poolF = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
  println("=== 相邻两块「底色」之间的源明度差 · ${files.size} 张 · 归族按色桶（L/8, ab/32）===")
  println("%-40s | %-34s | %-30s".format("图标", "最长的那条底-底边界", "明度差最大的那条（≥$minLen 格）"))
  println("%-40s | %6s %6s %5s %6s %5s | %6s %6s %6s %5s".format(
    "", "大占框", "小占框", "ΔL", "边界格", "小/大", "ΔL", "边界格", "小占框", "小/大"))
  for (f in files) {
    val img = ImageIO.read(f) ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h)
    val out = r.mask ?: continue
    val tl = poolF.get(MonochromeMask) as ThreadLocal<*>
    val s = tl.get() ?: continue
    val plate = field(s, "plate") as ByteArray
    val labL = field(s, "labL") as ShortArray
    val labA = field(s, "labA") as ShortArray
    val labB = field(s, "labB") as ShortArray
    if (plate.size < w * h) continue
    val total = w.toLong() * h
    fun bucket(i: Int): Int {
      val l = (labL[i].toInt() and 0xffff) / 64
      val aa = (labA[i].toInt() / 64 + 128).coerceIn(0, 255)
      val bb = (labB[i].toInt() / 64 + 128).coerceIn(0, 255)
      return ((l / 8) * 8 + aa / 32) * 8 + bb / 32
    }
    // ① 每格按色桶归族，只数**模型判成底**的格（底就该交 0；交了墨就不算它是底）
    val area = HashMap<Int, Int>(); val lum = HashMap<Int, Int>()
    for (i in 0 until w * h) {
      if (plate[i].toInt() == 0) continue
      if (out.size > i && ((out[i] ushr 24) and 0xff) != 0) continue
      val k = bucket(i)
      area[k] = (area[k] ?: 0) + 1
      val l = (labL[i].toInt() and 0xffff) / 64
      val old = lum[k]
      if (old == null || l > old) lum[k] = l
    }
    // ② 扫相邻格对：两边都是底、族不同 → 记这一对的边界格数
    val pair = HashMap<Long, Int>()
    fun key(a: Int, b: Int) = if (a < b) a.toLong() shl 20 or b.toLong() else b.toLong() shl 20 or a.toLong()
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      if (plate[i].toInt() == 0) continue
      val ka = bucket(i)
      if (x + 1 < w) {
        val j = i + 1
        if (plate[j].toInt() != 0) {
          val kb = bucket(j); if (ka != kb) pair[key(ka, kb)] = (pair[key(ka, kb)] ?: 0) + 1
        }
      }
      if (y + 1 < h) {
        val j = i + w
        if (plate[j].toInt() != 0) {
          val kb = bucket(j); if (ka != kb) pair[key(ka, kb)] = (pair[key(ka, kb)] ?: 0) + 1
        }
      }
    }
    if (pair.isEmpty()) continue
    fun left(e: Map.Entry<Long, Int>): String {
      val a = (e.key ushr 20).toInt(); val b = (e.key and 0xFFFFF).toInt()
      val na = area[a] ?: 0; val nb = area[b] ?: 0
      val dl = Math.abs((lum[a] ?: 0) - (lum[b] ?: 0))
      val ratio = minOf(na, nb).toDouble() / maxOf(na, nb).coerceAtLeast(1)
      return "%6.1f%% %6.1f%% %5d %6d %5.2f".format(
        maxOf(na, nb) * 100.0 / total, minOf(na, nb) * 100.0 / total, dl, e.value, ratio)
    }
    fun right(e: Map.Entry<Long, Int>): String {
      val a = (e.key ushr 20).toInt(); val b = (e.key and 0xFFFFF).toInt()
      val na = area[a] ?: 0; val nb = area[b] ?: 0
      val dl = Math.abs((lum[a] ?: 0) - (lum[b] ?: 0))
      val ratio = minOf(na, nb).toDouble() / maxOf(na, nb).coerceAtLeast(1)
      return "%6d %6d %5.1f%% %5.2f".format(dl, e.value, minOf(na, nb) * 100.0 / total, ratio)
    }
    val longest = pair.entries.maxByOrNull { it.value }!!
    val wide = pair.entries.filter { it.value >= minLen }.maxByOrNull {
      val a = (it.key ushr 20).toInt(); val b = (it.key and 0xFFFFF).toInt()
      Math.abs((lum[a] ?: 0) - (lum[b] ?: 0))
    }
    println("%-40s | %s | %s".format(f.name.removeSuffix(".png").take(40), left(longest),
      if (wide == null) "（没有够长的边界）" else right(wide)))
    // ③ **甲方案的门槛**（只在台子上算，不改模型）：这条规则会挑中谁？
    //    ⚠ 第一版把条件写反了：易班的**浅色是大块**（白 40.8%），深色（渐变蓝）反被色桶打散成一堆
    //      各 2.8% 的小块 —— 所以"小的那块是浅色"这个前提根本不成立。按它的真实形状重摆：
    //      · 有一块**大面积的浅色底**（L ≥ 180、≥10% 画布）
    //      · 它周围贴着**成片的深色底**（L ≤ 浅−60，合计 ≥15% 画布）
    //      · 浅色底自己的边界格 ≥90% 都贴着那些深色
    //      · 浅色底**里面夹着墨**（交付 >0 的格，且它的邻居大多在浅色底里 —— 即被浅底围住）
    run {
      var best: String? = null
      val lights = area.keys.filter { (lum[it] ?: 0) >= 180 && (area[it] ?: 0) * 100.0 / total >= 10.0 }
      for (light in lights) {
        val lL = lum[light] ?: 0
        val darks = area.keys.filter { (lum[it] ?: 0) <= lL - 60 }
        val darkArea = darks.sumOf { area[it] ?: 0 }
        if (darkArea * 100.0 / total < 15.0) continue
        val lightArea = area[light] ?: 0
        var rimAll = 0; var rimOnDark = 0; var inkInside = 0
        for (y in 0 until h) for (x in 0 until w) {
          val i = y * w + x
          if (plate[i].toInt() == 0) continue
          val k = bucket(i)
          if (k == light) {
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
              val xx = x + dx; val yy = y + dy
              if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
              val j = yy * w + xx
              if (plate[j].toInt() == 0) continue
              rimAll++
              if ((lum[bucket(j)] ?: 0) <= lL - 60) rimOnDark++
            }
          }
          // 墨：交付 >0，而它的四邻大多是浅色底 → 这块墨是被浅底围住的
          if (out.size > i && ((out[i] ushr 24) and 0xff) > 0) {
            var n = 0; var inLight = 0
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
              val xx = x + dx; val yy = y + dy
              if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
              n++
              val j = yy * w + xx
              if (plate[j].toInt() != 0 && bucket(j) == light) inLight++
            }
            if (n > 0 && inLight * 2 >= n) inkInside++
          }
        }
        val encl = if (rimAll == 0) 0 else rimOnDark * 100 / rimAll
        val ok = encl >= 90 && inkInside >= 50
        if (best == null || (ok && best.indexOf("**命中**") < 0))
          best = "      ↳ 甲方案：浅底 %5.1f%%（L%3d）深底合计 %5.1f%% 围住%d%% 被浅底围住的墨 %d 格  %s".format(
            lightArea * 100.0 / total, lL, darkArea * 100.0 / total, encl, inkInside,
            if (ok) "**命中**" else "（不满足）")
      }
      if (best != null) println(best)
    }
  }
}

private fun field(o: Any, name: String): Any {
  val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(o)
}
