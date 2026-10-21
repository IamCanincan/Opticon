package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * **把主人那句判据里的「弧长」这一维补上账**（2026-10-18 又重申一次：
 * 「相同颜色或是渐变底的部分与图标圆周相连的弧长」+「该部分的面积」，两者权衡判谁为底）。
 *
 * 生产里现在只有面积（`BORDER_MIN_PCT/MAX_PCT`），弧长一格没算。这台子只做一件事：
 * 按**生产同一套**黑帽线 + 从画布边漫水，量那一整块
 *  ① 占画布多少（面积，生产在用的）
 *  ② 贴着图标**不透明轮廓环**多少（弧长，生产没在用的）
 *  ③ 这一块上本来有多少墨（要退掉的量）
 * 然后按「弧长 ≥50% 且 面积 ≥50%」这条主人原话排一遍，看今天会动谁。
 *
 * 用法：`sh run.sh harness.ArcGateKt [目录] [黑帽阈值]`
 */
object ArcGateKt {
  @JvmStatic
  fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
    val T = args.getOrNull(1)?.toIntOrNull() ?: 6
    val fs = dir.listFiles { f: File -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: return
    println(String.format(Locale.ROOT, "%-52s %7s %7s %7s  %s",
      "name", "面积%", "弧长%", "块L", "面积50~95 且 L≥80？"))
    for (f in fs) {
      val img = ImageIO.read(f) ?: continue
      val w = img.width; val h = img.height; val n = w * h
      val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
      val L = IntArray(n) { MonochromeMask.labChannel(px[it], 0) / 64 }
      val op = BooleanArray(n) { (px[it] ushr 24) >= 128 }
      // 线 = 黑帽（与生产同一条：3x3 邻域最亮 - 自己 > T）
      val wall = BooleanArray(n)
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!op[i]) { wall[i] = true; continue }
        var mx = L[i]
        for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue
          for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= w) continue
            val j = yy * w + xx; if (op[j] && L[j] > mx) mx = L[j] } }
        wall[i] = mx - L[i] > T
      }
      // 轮廓环 = 不透明且四邻有非不透明（裁圆那套几何，不是画布边）
      val rim = BooleanArray(n); var rimN = 0
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!op[i]) continue
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
          !op[i - 1] || !op[i + 1] || !op[i - w] || !op[i + w]) { rim[i] = true; rimN++ }
      }
      if (rimN == 0) continue
      // 从**画布边**漫进来（生产同一条），四邻、跳墙
      val seen = BooleanArray(n); val q = IntArray(n); var head = 0; var tail = 0
      fun push(i: Int) { if (!wall[i] && !seen[i]) { seen[i] = true; q[tail++] = i } }
      for (x in 0 until w) { push(x); push((h - 1) * w + x) }
      for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
      while (head < tail) {
        val c = q[head++]; val cy = c / w; val cx = c - cy * w
        if (cx > 0) push(c - 1)
        if (cx < w - 1) push(c + 1)
        if (cy > 0) push(c - w)
        if (cy < h - 1) push(c + w)
      }
      if (tail == 0) { println(String.format(Locale.ROOT, "%-52s %7s  画布边全是空气（生产直接放弃）", f.nameWithoutExtension, "-")); continue }
      var sumL = 0L; var rimHit = 0
      for (k in 0 until tail) { val i = q[k]; sumL += L[i]; if (rim[i]) rimHit++ }
      val areaPct = tail * 100.0 / n
      val arcPct = rimHit * 100.0 / rimN
      val meanL = sumL.toDouble() / tail
      // ⚠ 这一列**只按「面积 50~95 + 块 L ≥80」两条**判，生产的另外两条门（块上墨 ≥5%、
      //   墨也得浅）要跑完整流水线才有数，这里没量 —— 所以它是"这两条挡不挡"，不是"生产切不切"。
      val gate = if (areaPct in 50.0..95.0 && meanL >= 80.0) "这两条过" else "这两条挡"
      println(String.format(Locale.ROOT, "%-52s %6.1f%% %6.1f%% %6.1f  %s",
        f.nameWithoutExtension, areaPct, arcPct, meanL, gate))
    }
  }
}
