package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **作者声明的盘色，到底能匹配上多少前景像素** —— 只量不改。
 *
 * 判据台六把尺子全在从合成图里猜"哪块是盘"；而自适应图标的**背景层就是设计者写下来的那块盘**
 * （`CorpusCapture` 新增的 `bgColor` 列：`flat` = ColorDrawable 直接给一个整数，
 * `med` = 渲染背景层取中位色）。这条不是第七个阈值，是换一种证据。
 *
 * 它要成立，必须两侧都验：
 *  · **盘型图标**匹配一大片 → 那一片可以**按作者的话**直接归零，不用猜；
 *  · **满幅插画**（feimo / browndust2 / 王者荣耀 那一类）匹配≈0 → 加了这条**一格都不伤**。
 * 第二侧是关键：它意味着这一条**不需要把两类图分开**就能universal，而那正是六把尺子做不到的。
 *
 * 用法：`harness.PlateMatchKtKt <语料目录> [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args.first { !it.startsWith("--") })
  val idx = File(dir, "index.tsv")
  if (!idx.exists()) { println("没有 $idx —— 这一版抓取没写索引"); return }
  val lines = idx.readLines()
  val head = lines[0].split("\t")
  val iFile = head.indexOf("file"); val iBg = head.indexOf("bgColor"); val iCls = head.indexOf("cls")
  // 已知类别：满幅插画（这一支的立身之本，绝不能被盘色伤到）/ 盘型（该被救的）
  val illus = listOf("astralparty", "browndust2", "sgame", "wisdomgame", "songpal")
  val plate = listOf("me.ele", "jiakao", "azure", "cainiao", "zhuanzhuan", "xunmeng", "jingdong")
  val buckets = HashMap<String, ArrayList<Pair<String, Int>>>()
  for (l in lines.drop(1)) {
    val c = l.split("\t")
    if (c.size <= iBg) continue
    val file = c[iFile]
    if (file.contains("_mono") || !file.endsWith(".png")) continue
    val bg = c[iBg]
    val cls = if (iCls >= 0) c[iCls] else "?"
    val tag = when {
      illus.any { file.contains(it) } -> "满幅插画"
      plate.any { file.contains(it) } -> "盘型(该救)"
      bg == "-" || bg == "null" || bg == "empty" -> "没有背景层"
      else -> "其余自适应"
    }
    val f = File(dir, file)
    if (!f.exists() || bg.length < 7) { buckets.getOrPut(tag) { ArrayList() }.add(file to -1); continue }
    // `flatRRGGBB` / `medRRGGBB/实心%` —— 先把 `/…` 后缀与前缀剥掉再解十六进制。
    val core = bg.substringBefore("/").removePrefix("flat").removePrefix("med")
    val plateRgb = ("FF" + core).toLong(16).toInt()
    val img = ImageIO.read(f)
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    var solid = 0; var match = 0
    val pr = (plateRgb shr 16) and 0xff; val pg = (plateRgb shr 8) and 0xff; val pb = plateRgb and 0xff
    val pl = MonochromeMask.labChannel(plateRgb, 0); val pa = MonochromeMask.labChannel(plateRgb, 1)
    val pbb = MonochromeMask.labChannel(plateRgb, 2)
    for (i in 0 until edge * edge) {
      val v = px[i]
      if (((v ushr 24) and 0xff) < 255) continue
      solid++
      // 两把都量：RGB 切比雪夫（快、粗）与模型自己要用的真 ΔE
      val rgbClose = kotlin.math.max(kotlin.math.abs(((v shr 16) and 0xff) - pr),
        kotlin.math.max(kotlin.math.abs(((v shr 8) and 0xff) - pg), kotlin.math.abs((v and 0xff) - pb))) <= 8
      val de = MonochromeMask.deltaE(MonochromeMask.labChannel(v, 0), MonochromeMask.labChannel(v, 1),
        MonochromeMask.labChannel(v, 2), pl, pa, pbb) shr 6
      if (rgbClose && de <= 3) match++
    }
    buckets.getOrPut(tag) { ArrayList() }.add(file to if (solid == 0) 0 else match * 100 / solid)
  }
  println("按「前景里有多少像素落在作者声明的盘色上」分组（ΔE≤3 且 RGB≤8）：")
  for ((k, v) in buckets.entries.sortedBy { it.key }) {
    val ok = v.filter { it.second >= 0 }.map { it.second }.sorted()
    if (ok.isEmpty()) { println("  ${k.padEnd(14)} 无可用样本"); continue }
    val med = ok[ok.size / 2]
    println("  ${k.padEnd(14)} n=${ok.size.toString().padStart(3)}  中位匹配 ${med}%  " +
      "最高 ${ok.last()}%  匹配<2% 的 ${ok.count { it < 2 }} 张   " +
      v.filter { it.second >= 25 }.take(3).joinToString(" ") { it.first.substringBefore("_ic").take(22) + "=" + it.second })
  }
}
