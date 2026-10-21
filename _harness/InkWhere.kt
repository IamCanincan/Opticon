package harness

import java.io.File
import javax.imageio.ImageIO
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 「这条痕到底踩在哪些源像素上」—— 逐行打印出墨的 x 区间，以及该区间里源 α 的 min/max/均值。
 *
 * 为什么单独一个驱动：ASCII 缩略图只能看出"有一条痕"，看不出**痕的源 α 是多少**。
 * 而 α=255 与 α=45 是两种完全不同的病（前者是"底格子被自己的 β 卖掉"，后者是
 * "抗锯齿边本来就淡"），修法不同。判据行里的 `最窄一条` 只给全局最小值。
 *
 * 用法: `InkWhereKt <src.png> [--min=40] [--cols=...]`
 */
fun main(args: Array<String>) {
  val minInk = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 40
  val f = File(args.first { !it.startsWith("--") })
  val img = ImageIO.read(f)
  val w = img.width; val h = img.height
  val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
  val r = MonochromeMask.build(px, w, h)
  val mask = r.mask ?: run { println("没有掩码：${r.report}"); return }
  println("=== ${f.name}  ${w}x$h  墨>$minInk 的像素 ===")
  var total = 0
  for (y in 0 until h) {
    val runs = ArrayList<IntArray>()   // [x0, x1, minA, maxA, sumA, n, minM, maxM, sumM]
    var cur: IntArray? = null
    for (x in 0 until w) {
      val i = y * w + x
      val ma = (mask[i] ushr 24) and 0xff
      if (ma > minInk) {
        val sa = (px[i] ushr 24) and 0xff
        if (cur == null) {
          cur = intArrayOf(x, x, sa, sa, sa, 1, ma, ma, ma); runs.add(cur)
        } else {
          cur[1] = x; if (sa < cur[2]) cur[2] = sa; if (sa > cur[3]) cur[3] = sa
          cur[4] += sa; cur[5]++
          if (ma < cur[6]) cur[6] = ma; if (ma > cur[7]) cur[7] = ma; cur[8] += ma
        }
      } else cur = null
    }
    if (runs.isEmpty()) continue
    val sb = StringBuilder("  y=$y ")
    for (q in runs) {
      total += q[5]
      sb.append("[x ${q[0]}..${q[1]} n=${q[5]} srcα ${q[2]}..${q[3]} avg=${q[4] / q[5]} 墨 ${q[6]}..${q[7]} 均=${q[8] / q[5]}] ")
    }
    println(sb)
  }
  println("  合计 $total 像素")
}
