package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import java.util.Locale

/**
 * 只读诊断：**两支交付出来的"墨"是不是同一种画风。**
 *
 * 起因：主人一句「其实更大的问题是一张纯图片图标」。量下来 62 张里两支的画风差得很远 ——
 * 参照支 `淡` 中位 0.02、`字均` 中位 238（实心剪影），满幅插画支（`调=1`）`淡` 中位 0.19、
 * `字均` 中位 119（一片中间调）。这两支的图标在桌面上是**挨着摆的**，画风不一致本身就是缺陷。
 *
 * 本台子只回答一件事：**交出去的墨，按浓度分三段各占多少**（只数 α>0 的格）：
 *   · `低` α<64   —— 屏幕上几乎看不见（壁纸透上来）
 *   · `中` 64..191 —— 一片灰
 *   · `高` α≥192 —— 实心
 * 分段按**非零格**归一，所以 `低` 高不代表"墨少"，代表"这块墨很虚"。
 *
 * 用法：`sh run.sh harness.InkMixKt rec_model [--pure]`
 *   `--pure` 只跑非自适应那 32 张（`corpus_runtime/index.tsv` 里 cls 是 BitmapDrawable 的）。
 */
object InkMixKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val pureOnly = args.any { it == "--pure" }
    val pure = if (!pureOnly) emptySet() else {
      val idx = File("corpus_runtime/index.tsv")
      if (!idx.exists()) error("要 --pure 但没有 corpus_runtime/index.tsv")
      idx.readLines().drop(1).mapNotNull { l ->
        val c = l.split('\t')
        if (c.size > 5 && c[5].endsWith("BitmapDrawable")) c[0] else null
      }.toSet()
    }
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
    }.filter { !pureOnly || pure.any { p -> it.name.startsWith(p + "_") } }.sortedBy { it.name }

    println("name\tbranch\tnonZero\t低α<64\t中64..191\t高α≥192\t均值")
    val acc = HashMap<String, MutableList<DoubleArray>>()
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val edge = img.width
      val px = IntArray(edge * edge)
      img.getRGB(0, 0, edge, edge, px, 0, edge)
      val r = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      val branch = if (r.report.contains("调=1")) "调=1" else "参照"
      var n = 0; var lo = 0; var mid = 0; var hi = 0; var sum = 0L
      for (i in 0 until edge * edge) {
        val a = mask[i] ushr 24
        if (a == 0) continue
        n++; sum += a
        if (a < 64) lo++ else if (a < 192) mid++ else hi++
      }
      if (n == 0) continue
      val p = doubleArrayOf(lo * 1.0 / n, mid * 1.0 / n, hi * 1.0 / n, sum * 1.0 / n)
      acc.getOrPut(branch) { ArrayList() }.add(p)
      println(String.format(Locale.ROOT, "%s\t%s\t%d\t%.2f\t%.2f\t%.2f\t%.0f",
        f.nameWithoutExtension, branch, n, p[0], p[1], p[2], p[3]))
    }
    for (b in listOf("参照", "调=1")) {
      val xs = acc[b] ?: continue
      val m = (0..3).map { k -> xs.map { it[k] }.sorted()[xs.size / 2] }
      println(String.format(Locale.ROOT,
        "# %s n=%d  中位：低%.2f 中%.2f 高%.2f 均值α%.0f", b, xs.size, m[0], m[1], m[2], m[3]))
    }
  }
}
