package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 量「甲」这一趟**多花的钱**：窗口(`of`) + 把交付掩码按窗口重排(`reframe`)，
 * 分母用同机同图上的合成主路 [MonochromeMask.build]（真机上那一条判据行报的是几十毫秒那一档）。
 *
 * 为什么要在台子上量：装机那侧只有彩色一支有 `量一张` 计时，染色这一趟**一行计时都没有** ——
 * 而它是图标加载的热路径。台子上量不出绝对毫秒（JVM 与手机不等），能量出的是**比值**：
 * 这一刀给合成加了几成成本。比值要能落地成一句"占 X%"，绝对数反而别拿去当真机读数。
 *
 * ⚠ 自检三道，缺一条就非零退出（本仓吃过"永远不响的尺"的亏）：
 *   ① 一张都没开窗口的语料 → 这条路径压根没被走到；② 计时全是 0 → 被测代码被 JIT 抬走了；
 *   ③ 结果没落进 sink → 同样会被抬走。
 *
 * 用法：`harness.CostKtKt <语料目录> [--edge=384] [--repeat=7]`
 */
private val sink = LongArray(1)

fun main(args: Array<String>) {
  val dir = File(args[0])
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val repeat = args.firstOrNull { it.startsWith("--repeat=") }?.substringAfter("=")?.toInt() ?: 7

  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "语料目录里没有 PNG —— 这台子量不到任何东西" }

  fun load(f: File): IntArray? {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    return px
  }

  val imgs = files.mapNotNull { load(it) }
  require(imgs.isNotEmpty()) { "一张都解不开 —— 别拿空表当结论" }

  // 预热：让 JIT 把三条路都编译掉，第一轮的数不能算账
  repeat(3) { k -> imgs.forEach { it.hashCode(); sink[0] += k.toLong() } }

  fun best(body: () -> Long): Long {
    var m = Long.MAX_VALUE
    repeat(repeat) { m = minOf(m, body()) }
    return m
  }

  val tOf = ArrayList<Long>(); val tRef = ArrayList<Long>(); val tBuild = ArrayList<Long>()
  var windows = 0
  for (px in imgs) {
    val t1 = best {
      val n0 = System.nanoTime()
      val win = IconCropWindow.of(px, edge, edge)
      val dt = System.nanoTime() - n0
      sink[0] += if (win == null) 1 else win.zoom.toRawBits().toLong()
      dt
    }
    val win = IconCropWindow.of(px, edge, edge)
    tOf += t1
    if (win != null) {
      windows++
      tRef += best {
        val n0 = System.nanoTime()
        val out = IconCropWindow.reframe(px, edge, edge, win)
        val dt = System.nanoTime() - n0
        sink[0] += out[0].toLong()
        dt
      }
    }
    tBuild += best {
      val n0 = System.nanoTime()
      val o = MonochromeMask.build(px, edge, edge, false, null)
      val dt = System.nanoTime() - n0
      sink[0] += (o.mask?.get(0) ?: 0).toLong()
      dt
    }
  }

  fun stat(v: List<Long>): Triple<Double, Double, Double> {
    val s = v.sorted()
    return Triple(s[s.size / 2] / 1e6, s[(s.size * 9 / 10).coerceAtMost(s.size - 1)] / 1e6, s.last() / 1e6)
  }

  if (windows == 0) { println("!! 一张都没开窗口 —— 这条路径没被走到，结论是空的"); kotlin.system.exitProcess(1) }
  val all = tOf + tRef + tBuild
  if (all.all { it == 0L }) { println("!! 计时全是 0 —— 被测代码被抬走了，这不是「快」"); kotlin.system.exitProcess(1) }

  val (ofM, ofP90, ofMax) = stat(tOf)
  val (rfM, rfP90, rfMax) = if (tRef.isEmpty()) Triple(0.0, 0.0, 0.0) else stat(tRef)
  val (bdM, bdP90, bdMax) = stat(tBuild)
  println("%-10s %-28s %-9s %-9s %-9s".format("这一趟", "说明", "中位ms", "p90", "最狠"))
  println("%-10s %-28s %-9.2f %-9.2f %-9.2f".format("窗口", "IconCropWindow.of（每图一次）", ofM, ofP90, ofMax))
  println("%-10s %-28s %-9.2f %-9.2f %-9.2f".format("重排", "reframe @edge=$edge（只有开窗口的）", rfM, rfP90, rfMax))
  println("%-10s %-28s %-9.2f %-9.2f %-9.2f".format("合成", "MonochromeMask.build（分母）", bdM, bdP90, bdMax))
  val extra = ofM + rfM
  println("语料 ${imgs.size} 张，开窗口 $windows 张（%.1f%%）".format(100.0 * windows / imgs.size))
  println("每图多花：窗口+重排 = %.2f ms，占合成(%.2f ms)的 %.1f%%".format(extra, bdM, 100.0 * extra / bdM))
  println("只有窗口这一趟是**每张都跑**（%.2f ms，占 %.1f%%）；重排只在开了窗口的图上跑。".format(
    ofM, 100.0 * ofM / bdM))
  println("[sink] ${sink[0]}")
}
