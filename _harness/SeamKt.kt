package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **洪泛断在半路的那半截盘：从底出发按"每步只跨一小段色差"接续、步数封顶，能接回多少？**（只量不改）
 *
 * 为什么是这一种判据。`EdgeKt --cols` 在真机 dump 像素上数出来，「背景奇怪线条 / 线条莫名突起和
 *   中断」这一族的形状是**同一个颜色被切成两半、两半交出去的量差一百倍**：
 *     · 浙江在线  `#004CE5` 底侧 10110 格交 **0.0**，墨侧 7247 格交 **51.0**
 *       `#0027D5` 底侧 1741 格交 0.0，墨侧 17949 格交 **156.5** ← 那条被报的波浪就是它
 *     · 易班      `#FFFFFF` 底侧 58354 格交 0.0，墨侧 2921 格交 **100.6** ← 气泡那一圈
 *   底是**渐变**，洪泛一定断在半路（模型注释里"贪心并块在渐变上链不通"记的就是这件事），
 *   断点之后那半截盘子按墨交出去了 —— 屏幕上就是他自己说的那条"莫名其妙的线"。
 *
 * 三种补法已经量过、都不行，别再走：
 *   ① **"离底主色 ΔE ≤ T 就算底"**：T=20 就碰到 67/71 张，unionpay 掉 9% 的墨 —— 它会去擦
 *      "恰好和底一个颜色的图形"，太钝。
 *   ② **"同一个精确色大半已是底"**：unionpay 仍排第一（要抹它自己交付墨量的 **34%**），因为这条
 *      没区分"漏过去的那半截"和"另一件同色的东西"。
 *   ③ 加**连通**（只算接着同色底的格）：误伤全消（unionpay 出列），可它自己也就剩 **0.04%** 的墨量 ——
 *      因为漏过去的那半截**和底不是同一个精确色**，渐变两侧各是一种蓝。
 *   → 所以只剩"带容差、但**步数封顶**"这一种：容差接得上渐变的断口，封顶不让它把整座渐变吃掉。
 *
 * 用法：`harness.SeamKt <目录> … [--ct=6,10,16,24] [--cd=2,4,8,16]`
 * 每格报：`接回格`=被这条接续重新判成底的墨侧实心格；`墨量`=这些格现在交出去的 α 之和；
 * `占交付%`=墨量 / 全图交付 α 之和；后面点名按占比最大的几张。
 */
fun main(args: Array<String>) {
  val ts = (args.firstOrNull { it.startsWith("--ct=") }?.substringAfter("=") ?: "6,10,16,24")
    .split(",").map { it.toInt() }
  val ds = (args.firstOrNull { it.startsWith("--cd=") }?.substringAfter("=") ?: "2,4,8,16")
    .split(",").map { it.toInt() }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  // rows[T][D] = 每张图一条 [名字, 接回格, 墨量, 全图墨量]
  val out = ArrayList<Triple<String, Array<IntArray>, Int>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val plate = pfld(s, "plate") as ByteArray
    val labL = pfld(s, "labL") as ShortArray
    val labA = pfld(s, "labA") as ShortArray
    val labB = pfld(s, "labB") as ShortArray
    val solid = BooleanArray(w * h) { (px[it] ushr 24) == 255 }
    var allAlpha = 0
    for (i in 0 until w * h) allAlpha += m[i] ushr 24
    val acc = Array(ts.size * ds.size) { IntArray(2) }
    for ((ti, T) in ts.withIndex()) {
      val lim = T shl 6
      var reach = BooleanArray(w * h) { plate[it] != 0.toByte() && solid[it] }
      for ((di, D) in ds.withIndex()) {
        repeat(D - if (di == 0) 0 else ds[di - 1]) {
          val nx = reach.copyOf()
          for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (reach[i] || !solid[i]) continue
            val l = labL[i].toInt() and 0xffff; val a = labA[i].toInt() and 0xffff; val b = labB[i].toInt() and 0xffff
            if (reach[i - 1] && deE(l, a, b, labL[i - 1].toInt() and 0xffff, labA[i - 1].toInt() and 0xffff, labB[i - 1].toInt() and 0xffff) <= lim ||
              reach[i + 1] && deE(l, a, b, labL[i + 1].toInt() and 0xffff, labA[i + 1].toInt() and 0xffff, labB[i + 1].toInt() and 0xffff) <= lim ||
              reach[i - w] && deE(l, a, b, labL[i - w].toInt() and 0xffff, labA[i - w].toInt() and 0xffff, labB[i - w].toInt() and 0xffff) <= lim ||
              reach[i + w] && deE(l, a, b, labL[i + w].toInt() and 0xffff, labA[i + w].toInt() and 0xffff, labB[i + w].toInt() and 0xffff) <= lim
            ) nx[i] = true
          }
          reach = nx
        }
        if (di == 0) continue
        var cells = 0; var alpha = 0
        for (i in 0 until w * h) {
          if (!reach[i] || plate[i] != 0.toByte() || !solid[i]) continue
          cells++; alpha += m[i] ushr 24
        }
        acc[ti * ds.size + di] = intArrayOf(cells, alpha)
      }
    }
    out += Triple(f.name.substringBeforeLast(".").take(38), acc, allAlpha)
  }
  println("n=${out.size}   从底按 ΔE≤T 一步步接续、最多 D 步，能接回多少**现在正交成墨的盘**")
  for ((ti, T) in ts.withIndex()) for ((di, D) in ds.withIndex()) {
    if (di == 0) continue
    val k = ti * ds.size + di
    var cells = 0; var alpha = 0L; var tot = 0L; var touched = 0
    for ((_, acc, all) in out) { cells += acc[k][0]; alpha += acc[k][1]; tot += all; if (acc[k][1] > 0) touched++ }
    val hit = out.filter { it.second[k][1] > 0 }
      .sortedByDescending { it.second[k][1].toDouble() / maxOf(it.third, 1) }.take(4)
    println("  ΔE≤${"%2d".format(T)} 走${"%2d".format(D)}步  接回=${"%7d".format(cells)}格" +
      "  这些格交的墨=${"%9d".format(alpha)}（占全图交付 ${"%.2f".format(if (tot == 0L) 0.0 else alpha * 100.0 / tot)}%）" +
      "  碰到 $touched/${out.size} 张  " + hit.joinToString(" ") {
        "${it.first}(${it.second[k][0]}格/${"%.0f".format(it.second[k][1] * 100.0 / maxOf(it.third, 1))}%)" })
  }
}

/** 模型里的 deltaE 是 private，按同一口径重算（真值 ×64 进、×64 出）。 */
private fun deE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2) / 64.0; val da = (a1 - a2) / 64.0; val db = (b1 - b2) / 64.0
  return (Math.sqrt(dl * dl + da * da + db * db) * 64).toInt()
}

private fun pfld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
