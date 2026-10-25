package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * 「单色几何改用彩色那一本窗口」的**掩码级 A/B**（2026-10-25）。
 *
 * 现状：单色几何 `winMeasured` 在 **384 判据格**上量；彩色在 `scanSize`（≤128）上量。
 * 语料上两者档位/跨度一致，但快捷方式的细环判据已证明两格会 **2/45 相反** —— 想从结构上
 * 消掉，就得让非 fgOnly 的单色几何也读彩色窗口（`colourWindow.win`）。这一台只回答：
 * **换成彩色窗口后，模型交出的掩码会不会变、变多少。**
 *
 * 每张：A = 384 窗口（现状）；B = 彩色格窗口（拟改）；窗口完全相同时不跑模型（必为同一张）。
 * 模型输入/under/plateLayer 与 `MaskBench` 同一套（`Inputs`）；`plateSubject` 两边用同一个
 * （它本来就只读彩色窗），`trimSubject` 不参与这一维。
 *
 * 用法：`sh run.sh harness.WinABKtKt rec_model corpus_runtime [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val inset = (edge * 0.25f / 1.5f).toInt()
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  println("edge=$edge 可见区 inset=$inset；A=384 窗（现状） B=彩色格窗（拟改）")
  println("%-46s %-7s %-10s %-10s %-9s %-8s".format("图", "类别", "A 窗", "B 窗", "窗同?", "掩码变化"))
  var n = 0; var sameWin = 0; var built = 0; var changed = 0; var worst = -1; var worstName = ""
  for (d in dirs) {
    val cls = HashMap<String, String>()
    val flat = HashSet<String>()
    File(d, "index.tsv").takeIf { it.isFile }?.readLines()?.drop(1)?.forEach { l ->
      val c = l.split('\t')
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
    for (c in cases(d, edge, inset = false, fgAll = false)) {
      val name = c.file.name
      val adaptive = (cls[name] ?: "").contains("Adaptive")
      val flatten = flat.contains(name)
      val fgOnly = adaptive && !flatten
      if (fgOnly) continue
      val px = c.src
      val winA = IconCropWindow.measure(px, edge)
      val img = runCatching { ImageIO.read(c.file) }.getOrNull() ?: continue
      val (cw, ch) = IconCropWindow.scanSize(img.width, img.height, false)
      val cpx = rasterC(img, cw, ch)
      val probe = if (adaptive) expandC(cpx, cw) else cpx
      val winB = runCatching { IconCropWindow.of(probe, cw, ch) }.getOrNull()
      n++
      val same = winA == null && winB == null ||
        (winA != null && winB != null && abs(winA.l - winB.l) < 1e-4f && abs(winA.t - winB.t) < 1e-4f &&
          abs(winA.r - winB.r) < 1e-4f && abs(winA.b - winB.b) < 1e-4f)
      if (same) { sameWin++ }
      var delta = ""
      if (!same) {
        built++
        val tOff = if (adaptive) inset else 0
        val tSide = if (adaptive) edge - 2 * inset else edge
        val under = c.under
        val pxA = if (winA == null) px else IconCropWindow.reframe(px, edge, edge, winA, tOff, tOff, tSide, tSide)
        val pxB = if (winB == null) px else IconCropWindow.reframe(px, edge, edge, winB, tOff, tOff, tSide, tSide)
        val uA = if (under == null || winA == null) under
          else IconCropWindow.reframe(under, edge, edge, winA, tOff, tOff, tSide, tSide)
        val uB = if (under == null || winB == null) under
          else IconCropWindow.reframe(under, edge, edge, winB, tOff, tOff, tSide, tSide)
        val subj = IconCropWindow.polarityOf(winB, winA)
        val a = MonochromeMask.build(pxA, edge, edge, c.plateLayer, uA, subj).mask
        val b = MonochromeMask.build(pxB, edge, edge, c.plateLayer, uB, subj).mask
        if (a == null || b == null) {
          delta = if ((a == null) != (b == null)) "**空/非空相反**" else "都空"
          if ((a == null) != (b == null)) { changed++; worst = 255; worstName = name }
        } else {
          var cnt = 0; var mx = 0
          for (i in a.indices) {
            val da = abs(((a[i] ushr 24) and 0xff) - ((b[i] ushr 24) and 0xff))
            if (da > 8) cnt++
            if (da > mx) mx = da
          }
          delta = if (cnt == 0) "逐格同" else "差 $cnt 格 / 最大 $mx"
          if (cnt > 0) { changed++; if (mx > worst) { worst = mx; worstName = name } }
        }
      }
      println("%-46s %-7s %-10s %-10s %-9s %-8s".format(
        name.take(46), if (adaptive) (if (flatten) "flat" else "fgOnly") else "非自适应",
        winA?.let { fmtW(it) } ?: "满幅", winB?.let { fmtW(it) } ?: "满幅",
        if (same) "是" else "否", delta))
    }
  }
  println("共 $n 张（fgOnly 不参与）；窗口相同 $sameWin 张；跑了模型 $built 张；掩码有差 $changed 张" +
    (if (worst >= 0) "；最大差 $worst（$worstName）" else ""))
}

private fun fmtW(w: IconCropWindow.Window) =
  "%.0f%%".format(max(w.r - w.l, w.b - w.t) * 100)

private fun rasterC(img: java.awt.image.BufferedImage, w: Int, h: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val sy = (y.toLong() * img.height / h).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / w).toInt().coerceAtMost(img.width - 1)
    px[y * w + x] = s0[sy * img.width + sx]
  }
  return px
}

private fun expandC(px: IntArray, edge: Int): IntArray {
  val f = 0.25f; val scale = 1f + 2f * f
  val out = IntArray(px.size)
  for (y in 0 until edge) for (x in 0 until edge) {
    val u = (x.toFloat() / edge + f) / scale
    val v = (y.toFloat() / edge + f) / scale
    if (u > 1f || v > 1f) continue
    val sx = (u * (edge - 1)).toInt().coerceIn(0, edge - 1)
    val sy = (v * (edge - 1)).toInt().coerceIn(0, edge - 1)
    out[y * edge + x] = px[sy * edge + sx]
  }
  return out
}
