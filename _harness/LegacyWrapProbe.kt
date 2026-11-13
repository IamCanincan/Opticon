import com.iamcanincan.opticon.circle.LegacyWrap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val N = 128

private fun synth(f: (Int, Int) -> Boolean): IntArray {
  val px = IntArray(N * N)
  for (y in 0 until N) for (x in 0 until N) px[y * N + x] = if (f(x, y)) 0xFF3366CC.toInt() else 0
  return px
}

private fun load(path: String): Pair<IntArray, Int>? {
  val img: BufferedImage = ImageIO.read(File(path)) ?: return null
  val w = img.width
  val px = IntArray(w * w)
  // 只取正方形：语料里的图标都是方图，非方图这里不测
  for (y in 0 until w) for (x in 0 until w) px[y * w + x] = img.getRGB(x, y)
  return px to w
}

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    val c = (N - 1) / 2.0
    val cases = listOf(
      "square" to synth { _, _ -> true },
      "circle" to synth { x, y -> val dx = x - c; val dy = y - c; dx * dx + dy * dy <= (N / 2.0) * (N / 2.0) },
      "logo" to synth { x, y -> val dx = x - c; val dy = y - c; dx * dx + dy * dy <= 30.0 * 30.0 },
    )
    for ((name, px) in cases) {
      val m = LegacyWrap.measure(px, N, N) ?: run { println("$name null"); continue }
      println("%-6s scale=%.3f shape=%s plate=#%06X".format(name, m.scale, m.maskShaped, m.plate and 0xFFFFFF))
    }
    return
  }
  for (path in args) {
    val (px, w) = load(path) ?: run { println("$path 读不出"); continue }
    val m = LegacyWrap.measure(px, w, w) ?: run { println("$path 没有可见像素"); continue }
    println(
      "%-10s w=%d scale=%.3f 图案=%.2f×圆 shape=%-5s plate=#%06X aspect=%.2f".format(
        File(path).name, w, m.scale, 0.7f * m.scale, m.maskShaped, m.plate and 0xFFFFFF, m.aspect,
      ),
    )
  }
}
