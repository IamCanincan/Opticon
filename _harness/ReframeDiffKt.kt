package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * 只回答一个问题：`rec_model` 里有多少张，**模型真吃的那张 ≠ `_model.png`**（即 reframe 真的动了它）。
 *
 * 为什么要先量这个再决定改不改抓取：把语料换成"搬之后那张"听着像修 bug，
 * 但它换不换分母，取决于**有多少张真的会动** —— 只有 3 张，那不值得重钉 16 道闸；
 * 有 40 张，那这是一次认真的口径变更，得单开一轮。
 *
 * ⚠ 这条链（`of → takeIf(zoom≤CAP) → reframe`）是**照 `MonochromeSynth` 重写的一份** ——
 *   那个文件不在判据台编译清单里，调不到它的私有常数。所以这个数只能用来判"值不值得动"，
 *   **不能用来当任何验收依据**（今天已经为"第二份实现"付过一次学费了）。
 *
 * 跑法：`harness.ReframeDiffKtKt <语料目录> [--edge=384]`
 */
// 上限读生产那一份，别在这儿再写一个 1.82f（本台子要分开报「超上限不搬」那一桶，所以不用 measure()）
private const val CAP = IconCropWindow.GEO_ZOOM_CAP

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png") &&
      !it.name.endsWith("_bg.png") && !it.name.endsWith("_fg.png") && !it.name.endsWith("_mono.png")
  }.sortedBy { it.name }

  var seen = 0; var noWin = 0; var moved = 0; var same = 0; var err = 0
  var worst = 0f; var worstName = ""
  val buckets = HashMap<String, Int>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    seen++
    val win = runCatching { IconCropWindow.of(px, edge, edge) }.getOrNull()
    if (win == null) { noWin++; continue }
    if (win.zoom > CAP) { buckets["超上限不搬"] = (buckets["超上限不搬"] ?: 0) + 1; same++; continue }
    // 非自适应：整张画布当可见区（off=0、side=edge），与 MonochromeSynth 那一路一致
    val pxIn = runCatching { IconCropWindow.reframe(px, edge, edge, win, 0, 0, edge, edge) }.getOrNull()
    if (pxIn == null) { err++; continue }
    var diff = 0
    for (i in 0 until edge * edge) if (pxIn[i] != px[i]) diff++
    val pct = diff * 100f / (edge * edge)
    val b = when { diff == 0 -> "逐位相同"; pct < 1f -> "<1% 动了"; pct < 10f -> "1~10% 动了"; pct < 50f -> "10~50% 动了"; else -> ">50% 动了" }
    buckets[b] = (buckets[b] ?: 0) + 1
    if (diff == 0) same++ else { moved++; if (pct > worst) { worst = pct; worstName = f.nameWithoutExtension } }
  }
  println("REFRAMEDIFF n=$seen  无窗口(真满幅)=$noWin  搬了=$moved  没搬=$same  出错=$err")
  for ((k, v) in buckets.toSortedMap()) println("   $k：$v 张")
  println("   动得最狠的一张：${worstName.take(40)} 有 ${"%.1f".format(worst)}% 的格不一样")
  if (seen == 0) { println("!! 一张都没读到 —— 这台子什么都没比"); kotlin.system.exitProcess(1) }
}
