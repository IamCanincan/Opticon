package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **两个色调里，哪一侧才是"画"？** 满幅插画的候选判据，先在纸上量一遍再决定要不要写进模型。
 *
 * 用户的话：智慧游戏「取色有点问题」→ 看到结果之后「感觉反下色更好」——
 * 也就是说这一类要的是**亮=墨**，而现在交出去的是暗=墨。
 *
 * 按 H0 那条"按区域认主体"已经量死（连续渐变量化不出连通区域，`6a98296`）。这里量的是
 * 另一条：**先把不透明像素按亮度切成两个色调（Otsu），再问哪一侧像"被另一侧承载的东西"**。
 * 三个候选信号，逐个打印，看它们能不能把六张插画分成一致的一侧：
 *   ① 面积：少数侧是不是画？（线稿／主体通常比背景小）
 *   ② 贴边：多数侧通常铺到画布边（背景），少数侧被围在中间
 *   ③ 触到内容框边的比例（与②同一件事，但按内容框而不是画布）
 *
 * 用法：`ToneSplitKt <目录|png> …`
 */
fun main(args: Array<String>) {
  val files = args.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
      .sortedBy { it.name } else listOf(f)
  }
  println("%-40s %6s %6s %6s %6s %6s  %s".format("图", "亮侧%", "亮贴边%", "暗贴边%", "OtsuL", "中位L", "少数侧"))
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
    // 只看不透明的像素
    val lum = ArrayList<Int>(w * h)
    for (i in src.indices) if (((src[i] ushr 24) and 0xff) >= 250) lum.add(MonochromeMask.labChannel(src[i], 0) ushr 6)
    if (lum.size < 256) { println("${f.nameWithoutExtension.take(40)}  实心像素太少"); continue }
    val hist = IntArray(256)
    for (v in lum) hist[v.coerceIn(0, 255)]++
    // Otsu：最大化类间方差
    val total = lum.size
    var sum = 0L; for (v in 0..255) sum += v.toLong() * hist[v]
    var sumB = 0.0; var wB = 0; var best = 0.0; var thr = 128
    for (v in 0..255) {
      wB += hist[v]; if (wB == 0) continue
      val wF = total - wB; if (wF == 0) break
      sumB += v.toDouble() * hist[v]
      val mB = sumB / wB; val mF = (sum - sumB.toLong()) / wF
      val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
      if (between > best) { best = between; thr = v }
    }
    // 两侧面积与"贴内容框边"的比例（内容框自己算：模型那道是 private，不为了探针去改可见性）
    var bx0 = w; var by0 = h; var bx1 = 0; var by1 = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) if (((src[row + x] ushr 24) and 0xff) >= 250) {
        if (x < bx0) bx0 = x; if (x >= bx1) bx1 = x + 1
        if (y < by0) by0 = y; if (y >= by1) by1 = y + 1
      }
    }
    if (bx1 <= bx0 || by1 <= by0) { println("${f.nameWithoutExtension.take(40)}  没有内容框"); continue }
    var nL = 0; var nD = 0; var lEdge = 0; var dEdge = 0
    for (y in by0 until by1) {
      val row = y * w
      for (x in bx0 until bx1) {
        val i = row + x
        if (((src[i] ushr 24) and 0xff) < 250) continue
        val l = MonochromeMask.labChannel(src[i], 0) ushr 6
        val light = l > thr
        if (light) nL++ else nD++
        val onEdge = x == bx0 || y == by0 || x == bx1 - 1 || y == by1 - 1
        if (onEdge) { if (light) lEdge++ else dEdge++ }
      }
    }
    val ink = nL + nD
    val med = lum.sorted()[lum.size / 2]
    println("%-40s %6.1f %6.1f %6.1f %6d %6d  %s".format(
      f.nameWithoutExtension.take(40),
      nL * 100.0 / ink,
      if (nL == 0) 0.0 else lEdge * 100.0 / nL,
      if (nD == 0) 0.0 else dEdge * 100.0 / nD,
      thr, med,
      if (nL < nD) "亮" else "暗"
    ))
  }
}
