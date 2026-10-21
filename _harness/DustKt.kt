package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **屏幕上这张是不是一把碎点？**（只量不改）
 *
 * 起因：`bench/montage/montage_01.png` 全量扫的时候，跳出来的第一类坏法不是"颜色反了"，
 * 是**交出来的东西读不出来** —— 有的整张就是一把散点（`icon30`、`icon47` 那种）。
 * 这类病判据数字看不见：`字均净` 只看"交出的墨有多实"，不看"连不连着"；
 * `cc`（连通块数）会报警，但一张本来就该有很多小笔画的 logo（文字图标）也是高 `cc`。
 *
 * 所以这里量的是**形状本身**：把交付 α≥12 的实心格做 4 连通，报
 *   · `墨格` 一共交了多少格；
 *   · `最大块%` 最大那一块占**墨**的比例（不是占画布 —— 占画布会把"本来就细"的 logo 冤枉成碎）；
 *   · `≥25格的块数` 有多少块够 25 格（屏幕上一个像素点都不该单独存在，25 格 = 5×5）。
 * 一张"读得出来"的图标，最大块应当占墨的很大头；`最大块%` 低而 `墨格` 不小 = 碎点。
 *
 * 用法：`harness.DustKtKt <目录> [目录…] [--min-ink=800]`
 */
fun main(args: Array<String>) {
  val minInk = (args.firstOrNull { it.startsWith("--min-ink=") }?.substringAfter("=") ?: "800").toInt()
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val rows = ArrayList<Array<Comparable<*>>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val on = BooleanArray(w * h) { (px[it] ushr 24) == 255 && (m[it] ushr 24) >= 12 }
    val seen = BooleanArray(w * h)
    val stack = IntArray(w * h)
    var ink = 0; var big = 0; var blocks = 0; var ncc = 0
    for (i in 0 until w * h) if (on[i]) ink++
    if (ink == 0) continue
    for (start in 0 until w * h) {
      if (!on[start] || seen[start]) continue
      ncc++
      var sp = 0; stack[sp++] = start; seen[start] = true
      var n = 0
      while (sp > 0) {
        val c = stack[--sp]; n++
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
          if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)) {
          if (j < 0 || !on[j] || seen[j]) continue
          seen[j] = true; stack[sp++] = j
        }
      }
      if (n > big) big = n
      if (n >= 25) blocks++
    }
    rows += arrayOf(f.name.substringAfter("_").substringBefore("-src").take(44),
      ink, big * 100.0 / ink, ncc, blocks)
  }
  val dust = rows.filter { (it[1] as Int) >= minInk }.sortedBy { it[2] as Double }
  println("墨格 ≥$minInk 的 ${dust.size} 张里，按「最大块占墨%」升序（低 = 屏幕上是一把碎点）：")
  for (row in dust.take(14))
    println("  最大块=%5.1f%%  墨格=%-7d 连通块=%-4d 够25格的块=%-3d  %s"
      .format(row[2] as Double, row[1] as Int, row[3] as Int, row[4] as Int, row[0]))
  println("  —— 参考：最大块占墨 ≥60% 的有 ${dust.count { (it[2] as Double) >= 60.0 }} 张，" +
    "<30% 的有 ${dust.count { (it[2] as Double) < 30.0 }} 张")
}
