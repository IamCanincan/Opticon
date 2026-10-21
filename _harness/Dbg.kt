package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

fun main() {
  val S = 192
  val p = IntArray(S * S)
  for (y in 0 until S) for (x in 0 until S) {
    val dx = x - S / 2; val dy = y - S / 2
    if (sqrt((dx * dx + dy * dy).toDouble()) <= S / 3) p[y * S + x] = argb(255, 140, 200, 60)
  }
  for (y in 0 until S) for (x in 0 until S) {
    val dx = x - S / 2; val dy = y - S / 2
    if (sqrt((dx * dx + dy * dy).toDouble()) <= S / 10) p[y * S + x] = argb(255, 255, 255, 255)
  }
  val r = MonochromeMask.build(p, S, S)
  println(r.report)
  val m = r.mask!!
  val step = 4
  for (y in 0 until S step step) {
    val sb = StringBuilder()
    for (x in 0 until S step step) {
      val a = (m[y * S + x] ushr 24) and 0xff
      sb.append(when { a == 0 -> ' '; a < 64 -> '.'; a < 128 -> ':'; a < 192 -> '+'; else -> '#' })
    }
    println(sb)
  }
  var hist = IntArray(16)
  for (v in m) hist[((v ushr 24) and 0xff) / 16]++
  println(hist.mapIndexed { i, n -> "${i * 16}-${i * 16 + 15}:$n" }.joinToString(" "))
}
