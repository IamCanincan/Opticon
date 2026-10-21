package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * **只读探针：「把底切干净之后，形状还在不在」**（主人 2026-10-18：这一类要拿**翻译**那种更极限的图标当标尺）。
 *
 * 假想的"底"就按主人那句判据取：**与图标不透明轮廓相连的那一整片同色/渐变** ——
 * 沿轮廓环收像素，取占环最多的那一族（ΔE≤18 定族，再用 ΔE≤40 放宽吃渐变），
 * 然后把交付掩码里属于这一族/渐变连通的那一片**全部交 0**，量切前切后：
 *   墨占画布、块数 cc、最大块占墨比 top、淡（α<64 的墨占比）。
 * "碎"的判词沿用生产那条（不引新阈值）：**最大块占比掉超过 15 个点，或块数翻倍还多出 4 块**。
 *
 * ⚠ 不改任何生产常量、不动像素、不装机。
 * 用法：`sh run.sh harness.PlateCutProbeKt [目录]`
 */
object PlateCutProbeKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val fs = dir.listFiles { f: File -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: return
    println(String.format(Locale.ROOT, "%-50s %6s %6s %6s %6s %6s %6s  %s",
      "name", "墨%前", "墨%后", "cc前", "cc后", "top前", "top后", "判"))
    for (f in fs) {
      val img = ImageIO.read(f) ?: continue
      val w = img.width; val h = img.height; val n = w * h
      val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
      val r = MonochromeMask.build(px, w, h)
      val mask = r.mask ?: continue
      val op = BooleanArray(n) { (px[it] ushr 24) >= 128 }
      // 轮廓环 = 不透明且四邻有非不透明（裁圆那套几何）
      val rim = ArrayList<Int>()
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!op[i]) continue
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
          !op[i - 1] || !op[i + 1] || !op[i - w] || !op[i + w]) rim.add(i)
      }
      if (rim.size < 64) { println(String.format(Locale.ROOT, "%-50s  轮廓环太小(%d)，跳过", f.nameWithoutExtension, rim.size)); continue }
      // 环上按 ΔE≤18 聚族，取占环最多的那一族的颜色
      val famL = ArrayList<Int>(); val famA = ArrayList<Int>(); val famB = ArrayList<Int>(); val famN = ArrayList<Int>()
      for (i in rim) {
        val l = MonochromeMask.labChannel(px[i], 0); val a = MonochromeMask.labChannel(px[i], 1)
        val b = MonochromeMask.labChannel(px[i], 2)
        var k = -1
        for (j in famL.indices) {
          if (MonochromeMask.deltaE(l, a, b, famL[j], famA[j], famB[j]) <= 18 shl 6) { k = j; break }
        }
        if (k < 0) { famL.add(l); famA.add(a); famB.add(b); famN.add(1) } else famN[k] = famN[k] + 1
      }
      var best = 0
      for (j in famN.indices) if (famN[j] > famN[best]) best = j
      val domShare = famN[best] * 100.0 / rim.size
      // 交付里属于"这一族（放宽 ΔE≤40）"的墨格 → 交 0
      val cut = mask.copyOf()
      for (i in 0 until n) {
        if ((cut[i] ushr 24) == 0) continue
        val l = MonochromeMask.labChannel(px[i], 0); val a = MonochromeMask.labChannel(px[i], 1)
        val b = MonochromeMask.labChannel(px[i], 2)
        if (MonochromeMask.deltaE(l, a, b, famL[best], famA[best], famB[best]) <= 40 shl 6) cut[i] = 0
      }
      val (ink0, cc0, top0) = stats(mask, w, h, n)
      val (ink1, cc1, top1) = stats(cut, w, h, n)
      val broken = (top0 - top1) > 0.15 || (cc1 > cc0 * 2 + 4)
      val gone = ink1 == 0
      val verdict = if (gone) "切空了" else if (broken) "碎" else "站得住"
      println(String.format(Locale.ROOT, "%-50s %5.1f%% %5.1f%% %6d %6d %5.2f %5.2f  %s（环上主导族占 %5.1f%%，Lab %d/%d/%d）",
        f.nameWithoutExtension, ink0 * 100.0 / n, ink1 * 100.0 / n, cc0, cc1, top0, top1, verdict,
        domShare, famL[best] / 64, famA[best] / 64, famB[best] / 64))
    }
  }

  private fun stats(mask: IntArray, w: Int, h: Int, n: Int): Triple<Int, Int, Double> {
    val seen = BooleanArray(n); val q = IntArray(n)
    var ink = 0; var cc = 0; var biggest = 0
    for (i in 0 until n) if ((mask[i] ushr 24) > 0) ink++
    for (s in 0 until n) {
      if ((mask[s] ushr 24) == 0 || seen[s]) continue
      cc++; var head = 0; var tail = 0; q[tail++] = s; seen[s] = true; var a = 0
      while (head < tail) {
        val c = q[head++]; a++
        val cy = c / w; val cx = c - cy * w
        fun push(j: Int) { if ((mask[j] ushr 24) > 0 && !seen[j]) { seen[j] = true; q[tail++] = j } }
        if (cx > 0) push(c - 1); if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w); if (cy < h - 1) push(c + w)
      }
      if (a > biggest) biggest = a
    }
    return Triple(ink, cc, if (ink == 0) 0.0 else biggest.toDouble() / ink)
  }
}
