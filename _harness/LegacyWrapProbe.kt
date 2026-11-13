import com.iamcanincan.opticon.circle.LegacyWrap

fun main() {
  val n = 128
  val c = (n - 1) / 2.0
  fun mk(f: (Int, Int) -> Boolean): IntArray {
    val px = IntArray(n * n)
    for (y in 0 until n) for (x in 0 until n) px[y * n + x] = if (f(x, y)) 0xFF3366CC.toInt() else 0
    return px
  }
  val square = mk { _, _ -> true }
  val circle = mk { x, y ->
    val dx = x - c; val dy = y - c; dx * dx + dy * dy <= (n / 2.0) * (n / 2.0)
  }
  val logo = mk { x, y ->
    val dx = x - c; val dy = y - c; dx * dx + dy * dy <= 30.0 * 30.0
  }
  for ((name, px) in listOf("square" to square, "circle" to circle, "logo" to logo)) {
    val m = LegacyWrap.measure(px, n, n) ?: run { println("$name null"); continue }
    println(
      "$name scale=%.3f shape=%s plate=#%06X aspect=%.2f".format(
        m.scale, m.maskShaped, m.plate and 0xFFFFFF, m.aspect,
      ),
    )
  }
}
