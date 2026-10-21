package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **过渡宽度定线台（只量，不改模型）** —— 2026-10-08。
 *
 * 前面被否掉的那些尺子（五把颜色尺 + 今天的 ΔE 轴）量的都是**色差有多大**：
 * 这块离参照多远、比主色亮还是暗。而"shading 与实体"的分别根本不在差的大小上 ——
 * **实体之间必须有边界，shading 恰恰是没有边界的那部分变化**。
 * 所以这一台量的是**变化怎么发生**：
 *
 *   在一个窗口里：`Δ` = 窗口内最大−最小亮度（这里要变多少），
 *                 `g` = 窗口内单像素最大梯度（每像素能变多少）。
 *   **过渡宽度 `tw = Δ / g`** —— 这个变化摊在多少个像素上。
 *     硬边界（白字压红盘、鸟压底）：Δ 大、g 也大 → **tw ≈ 1~3**；
 *     连续 shading（脸的明暗、车身反光、渐变盘）：Δ 大、g 小 → **tw ≥ 8**。
 *
 * ⚠ **这不是本仓 2026-09 量过的「局部厚度」**（`ThinKt`：底厚≥20 的 44 张里 40 张有 ≥3% 内薄，
 * 那次否的是"这块底自己有多厚"）。这里量的是**两块之间那条变化摊多宽**，对象不同。
 *
 * 跑法：`java -cp … harness.TransKtKt <目录> [目录…] [--map=输出目录]`
 * 判据（**先说死，免得我事后找常数**）：拿已知答案的四张对 ——
 *   钉钉（白鸟=实体）、feimo（脸的明暗=shading）、globalspeed（续弧=底的一部分）、
 *   驾考（车身反光=shading + 车灯轮廓=实体）。
 * **要的是同一张图里两类都存在、且它们的 `tw` 分布不重叠**；
 * 只要"实体"和"shading"的 tw 直方图大面积重叠，这条就当场作废，不找第二个常数。
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }
  val mapDir = args.firstOrNull { it.startsWith("--map=") }?.substringAfter("=")?.let { File(it).apply { mkdirs() } }
  if (dirs.isEmpty()) { println("用法：TransKtKt <目录> [目录…] [--map=输出目录]"); return }
  val WIN = 9                       // 窗口边长（像素）：384 口径下 9px ≈ 画面里 2.3%
  val DMIN = 10                     // 只看"这里确实变了 ≥10 个 L*"的位置，平坦区不参与
  println("%-34s %6s %6s %6s %6s %6s %6s".format("图标", "Δ≥10格", "tw p25", "p50", "p75", "硬%≤3", "软%≥8"))
  for (d in dirs) {
    val fs = File(d).listFiles { f: File -> f.extension == "png" &&
        !f.name.contains("preview") && !f.name.contains("_mono") } ?: continue
    for (f in fs.sortedBy { it.name }) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val w = im.width; val h = im.height
      val px = IntArray(w * h).also { im.getRGB(0, 0, w, h, it, 0, w) }
      // L* 图（0..100），非实心记 -1
      val L = FloatArray(w * h) { -1f }
      for (i in px.indices) {
        if (((px[i] ushr 24) and 0xff) < 128) continue
        val r = ((px[i] shr 16) and 0xff) / 255.0; val g = ((px[i] shr 8) and 0xff) / 255.0
        val b = (px[i] and 0xff) / 255.0
        val fo = { v: Double -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        val yy = 0.2126 * fo(r) + 0.7152 * fo(g) + 0.0722 * fo(b)
        L[i] = (if (yy > 0.008856) 116.0 * Math.cbrt(yy) - 16.0 else 903.3 * yy).toFloat()
      }
      // 每像素单像素最大梯度
      val grad = FloatArray(w * h)
      for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x; val c = L[i]
        if (c < 0f) continue
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val n = L[(y + dy) * w + (x + dx)]
          if (n >= 0f) { val dd = Math.abs(c - n); if (dd > m) m = dd }
        }
        grad[i] = m
      }
      val tws = ArrayList<Float>(w * h / 8)
      val twMap = mapDir?.let { FloatArray(w * h) { -1f } }
      for (y in WIN / 2 until h - WIN / 2) for (x in WIN / 2 until w - WIN / 2) {
        val c = L[y * w + x]; if (c < 0f) continue
        var lo = 1e9f; var hi = -1e9f; var gmax = 0f
        for (dy in -(WIN / 2)..(WIN / 2)) for (dx in -(WIN / 2)..(WIN / 2)) {
          val v = L[(y + dy) * w + (x + dx)]
          if (v < 0f) continue
          if (v < lo) lo = v; if (v > hi) hi = v
          val gg = grad[(y + dy) * w + (x + dx)]; if (gg > gmax) gmax = gg
        }
        val delta = hi - lo
        if (delta < DMIN) continue
        val tw = if (gmax < 0.5f) 999f else delta / gmax
        tws.add(tw)
        if (twMap != null) twMap[y * w + x] = tw
      }
      if (tws.size < 200) { println("%-34s 有变化的位置太少（%d）".format(f.nameWithoutExtension.take(34), tws.size)); continue }
      val s = tws.toFloatArray().sortedArray()
      fun q(p: Float): Float = s[(s.size * p).toInt().coerceIn(0, s.size - 1)]
      val hard = s.count { it <= 3f } * 100.0 / s.size
      val soft = s.count { it >= 8f } * 100.0 / s.size
      println("%-34s %6d %6.1f %6.1f %6.1f %5.0f%% %5.0f%%".format(
        f.nameWithoutExtension.take(34), s.size, q(0.25f), q(0.5f), q(0.75f), hard, soft))
      if (twMap != null) {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
          val v = twMap[i]
          out.setRGB(i % w, i / w, if (v < 0f) 0x101010
            else if (v <= 3f) 0xFF3030                       // 红 = 硬边界（实体）
            else if (v >= 8f) 0x30FF30                       // 绿 = 连续过渡（shading）
            else 0xFFFF30)                                   // 黄 = 中间
        }
        ImageIO.write(out, "png", File(mapDir, f.nameWithoutExtension + "_tw.png"))
      }
    }
  }
  println("\n图例（--map 出的那张）：红 = 硬边界(tw≤3，实体) · 黄 = 3~8 · 绿 = 连续过渡(tw≥8，shading)")
}
