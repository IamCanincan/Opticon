package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **不依赖参照的极性**：内容框**外圈**那一层就是盘，**内圈**是内容 —— 比两者的平均亮度。
 *
 * 为什么要它：满幅调子那一支把"暗=墨"**写死**了（`tonalClass` 那一段的注释自己写着"极性固定"）。
 * 内容比盘暗的那批（feimo / 驾考宝典 / 微博 / 菜鸟）于是对，内容比盘**亮**的那批
 * （淘宝闪购、云闪付：白字压在橙/蓝盘上）就被掏空 —— 正是主人最早报的「颜色反一下」。
 * 而现有那个真机验过的 [MonochromeMask.polarity] 要拿 `ground` 当参照，**这一支存在的理由
 * 恰恰是"参照不可信"**，所以不能直接借它。
 *
 * 这台子只量不改：先看这一条分不分得开那四个已知答案，再决定要不要写进模型。
 *
 * 用法：`harness.TonalPolarKtKt <目录> [--ring=8] [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val ring = args.firstOrNull { it.startsWith("--ring=") }?.substringAfter("=")?.toInt() ?: 8
  val dir = File(args.first { !it.startsWith("--") })
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.contains("_mono") }.sortedBy { it.name }
  println("图标\t盘L\t内容L\t差\t建议")
  for (f in files) {
    val src = ImageIO.read(File(f.absolutePath))
    val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics(); g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
    val px = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, px, 0, edge)
    var l = edge; var t = edge; var r = -1; var b = -1
    for (y in 0 until edge) for (x in 0 until edge) if (((px[y * edge + x] ushr 24) and 0xff) >= 128) {
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) continue
    val box = android.graphics.Rect(l, t, r + 1, b + 1)
    var bs = 0L; var bn = 0; var is2 = 0L; var in2 = 0
    for (y in box.top until box.bottom) for (x in box.left until box.right) {
      val i = y * edge + x
      if (((px[i] ushr 24) and 0xff) < 128) continue
      val onRing = x - box.left < ring || box.right - 1 - x < ring ||
        y - box.top < ring || box.bottom - 1 - y < ring
      val L = MonochromeMask.labChannel(px[i], 0)
      if (onRing) { bs += L; bn++ } else { is2 += L; in2++ }
    }
    if (bn == 0 || in2 == 0) { println("${f.nameWithoutExtension.take(40)}\t量不出来"); continue }
    val bl = (bs / bn).toInt(); val il = (is2 / in2).toInt()
    // **外圈散不散** = "外圈是一块平涂（真盘）"还是"外圈只是连续调画面的一条边"。
    // 算在 96×96 格上（与纹理同一口径，天生不随边长漂）：取外圈格的中位色，再量外圈格到它的 p90。
    val n = 96
    val cr = IntArray(n * n); val cg = IntArray(n * n); val cb = IntArray(n * n); val cn = IntArray(n * n)
    for (y in box.top until box.bottom) for (x in box.left until box.right) {
      val i = y * edge + x
      val pa = (px[i] ushr 24) and 0xff
      if (pa < 128) continue
      val k = ((y - box.top) * n / box.height()).coerceAtMost(n - 1) * n +
        ((x - box.left) * n / box.width()).coerceAtMost(n - 1)
      cr[k] += (px[i] shr 16) and 0xff; cg[k] += (px[i] shr 8) and 0xff; cb[k] += px[i] and 0xff; cn[k]++
    }
    val ring = ArrayList<Int>(); val rr = ArrayList<Int>(); val rg = ArrayList<Int>(); val rb = ArrayList<Int>()
    for (cy in 0 until n) for (cx in 0 until n) {
      if (cx >= 3 && n - 1 - cx >= 3 && cy >= 3 && n - 1 - cy >= 3) continue
      val k = cy * n + cx
      if (cn[k] == 0) continue
      ring.add(k); rr.add(cr[k] / cn[k]); rg.add(cg[k] / cn[k]); rb.add(cb[k] / cn[k])
    }
    var spread = -1
    if (ring.size >= 4) {
      val mr = rr.sorted()[rr.size / 2]; val mg = rg.sorted()[rg.size / 2]; val mb = rb.sorted()[rb.size / 2]
      val d = IntArray(ring.size) { j ->
        val a = rr[j] - mr; val b2 = rg[j] - mg; val c2 = rb[j] - mb
        kotlin.math.sqrt((a * a + b2 * b2 + c2 * c2).toDouble()).toInt()
      }
      d.sort()
      spread = d[(d.size * 90 / 100) - 1]
    }
    // 再问一遍**仓里那个真机验过的** polarity()：它拿 ground 当参照，与"外圈=盘"这条互相独立。
    // 两边都说翻才翻 —— 与 [build] 里 `agree` 那道豁免同一个套路。
    val pol = MonochromeMask.polarity(px, edge, edge)
    println("${f.nameWithoutExtension.take(40)}\t${bl shr 6}\t${il shr 6}\t${(il - bl) shr 6}\t$spread\t" +
      (if (il > bl) "亮=墨（该翻）" else "暗=墨（保持）") +
      "\t参照那条路=${if (pol.known) (if (pol.invert) "翻" else "不翻") else "判不出"}")
  }
}
