package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import java.util.Locale

/**
 * 只读诊断：**「切完背景之后碎成什么样」，以及"只留大块"能不能救。**
 *
 * 起因：主人 2026-10-17 22:4x 一句「王者荣耀碎了」。真机读数 `cc=67 top=0.61 小片=0.08`
 * —— 切之前 `cc=31 top=0.96`（一整块），切完最大的那块只占 61% 的墨。
 * 所以要分开两件事：① 碎是"切把主体切断了"还是"主体本来就是许多小元素"；
 * ② 若只保留最大的那几块，剩下的墨还够不够撑住这张图。
 *
 * 做法：对交付掩码做 4 连通成分分析（α>0 即算墨），按面积从大到小报前 5 块，
 * 再给三档"只留 ≥ 墨的 X%"之后的读数（块数 / 最大块占比 / 留下的墨占画布）。
 *
 * ⚠ 它**不改任何生产常量**，只回答"要不要加一道几何守卫、门槛在哪"。
 * 用法：`sh run.sh harness.FragKt rec_model/<某张>.png`
 */
object FragKt {
  @JvmStatic
  fun main(args: Array<String>) {
    for (path in args) {
      val f = File(path)
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      if (img.width != img.height) continue
      val w = img.width; val h = img.height
      val px = IntArray(w * h)
      img.getRGB(0, 0, w, h, px, 0, w)
      val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
      val mask = r.mask ?: continue
      val total = w * h
      val seen = BooleanArray(total)
      val q = IntArray(total)
      val sizes = ArrayList<Int>()
      var ink = 0
      for (i in 0 until total) if ((mask[i] ushr 24) > 0) ink++
      for (i in 0 until total) {
        if (seen[i] || (mask[i] ushr 24) == 0) continue
        var head = 0; var tail = 0
        q[tail++] = i; seen[i] = true
        while (head < tail) {
          val c = q[head++]
          val cy = c / w; val cx = c - cy * w
          val nb = intArrayOf(if (cx > 0) c - 1 else -1, if (cx < w - 1) c + 1 else -1,
            if (cy > 0) c - w else -1, if (cy < h - 1) c + w else -1)
          for (j in nb) {
            if (j < 0 || seen[j] || (mask[j] ushr 24) == 0) continue
            seen[j] = true; q[tail++] = j
          }
        }
        sizes.add(tail)
      }
      sizes.sortDescending()
      println("=== ${f.nameWithoutExtension}  墨=$ink (画布 ${ink * 100.0 / total}%)  块=${sizes.size}")
      println("   前 5 块：" + sizes.take(5).joinToString(" ") { "${it}(${String.format(Locale.ROOT, "%.2f", it * 1.0 / ink)})" })
      for (pct in intArrayOf(1, 3, 10, 20)) {
        val keep = sizes.filter { it * 100 >= ink * pct }
        val kept = keep.sum()
        val top = if (kept == 0) 0.0 else keep.max() * 1.0 / kept
        println(String.format(Locale.ROOT,
          "   只留 ≥墨的%2d%%：块 %3d→%3d，留下墨 %5.1f%% 画布，最大块占 %.2f", pct,
          sizes.size, keep.size, kept * 100.0 / total, top))
      }
    }
  }
}
