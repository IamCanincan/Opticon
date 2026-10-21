package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **一把看完"这一刀擦掉了哪一块"**：每张图标一格，源图上把 [MonochromeMask] 那一刀
 * 擦掉的格子涂成品红，全部拼成一张网格图。
 *
 * 为什么要拼成一张：`嵌=13770` 这种数只说"擦了多少"，不说**擦的是不是本来就该留的东西**。
 * 而一张一张开全尺寸图看，十二张就吃掉一整轮 —— 这一刀碰了 39/113 张，只能先一眼扫完，
 * 再把可疑的那几张单独放大。
 *
 * 用法：`harness.CutKtKt <目录> … [--out=DIR/cut.png] [--cell=192] [--cols=5] [--min=1]`
 *   `--min` = 只看擦掉格数 ≥ 这个数的张（默认 1，即全部擦过的）。
 */
fun main(args: Array<String>) {
  val cell = args.firstOrNull { it.startsWith("--cell=") }?.substringAfter("=")?.toInt() ?: 192
  val cols = args.firstOrNull { it.startsWith("--cols=") }?.substringAfter("=")?.toInt() ?: 5
  val out = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/cut.png")
  val minCut = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 1
  val erase = args.contains("--erase")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  val tiles = ArrayList<Pair<String, IntArray>>()
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = runCatching { MonochromeMask.build(px, w, h) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val hw = runCatching { fld(s, "hollowed") as ByteArray }.getOrNull() ?: ByteArray(w * h)
    val plate = fld(s, "plate") as ByteArray
    var n = 0
    for (i in 0 until w * h) if (hw[i] != 0.toByte()) n++
    if (n < minCut) continue
    val t = IntArray(cell * cell)
    for (y in 0 until cell) for (x in 0 until cell) {
      val sy = y * h / cell; val sx = x * w / cell
      val i = sy * w + sx
      val p = px[i]
      t[y * cell + x] = when {
        // 默认看**交付的墨**（白底压黑墨 = 桌面深色主题反过来的样子），一张能看完一批图标；
        // 加 --erase 才回到原来那版"品红 = 被这一刀擦掉的格"。
        !erase -> {
          val a = m[i] ushr 24
          val v = 255 - a
          0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        hw[i] != 0.toByte() -> 0xFFFF00FF.toInt()
        (p ushr 24) == 0 -> 0xFF202020.toInt()
        plate[i] != 0.toByte() -> 0xFF000000.toInt() or ((p and 0x00FFFFFF) / 3 or 0x00A0A0A0)
        else -> 0xFF000000.toInt() or (p and 0x00FFFFFF)
      }
    }
    tiles += (f.name.substringBefore("_").take(20) + " 擦$n 墨" +
      (0 until w * h).count { m[it] ushr 24 > 0 }) to t
  }
  val rows = (tiles.size + cols - 1) / cols
  val canvas = BufferedImage(cols * (cell + 4), rows * (cell + 4), BufferedImage.TYPE_INT_RGB)
  val g = canvas.createGraphics()
  g.color = java.awt.Color(40, 40, 40); g.fillRect(0, 0, canvas.width, canvas.height)
  g.dispose()
  // 格子上**不写字**：这台机器上没有可靠的字体路径，而"哪一格是谁"由下面那份按同样顺序
  // 打印的清单给 —— 半吊子的点阵字只会让人读错。
  tiles.forEachIndexed { idx, (name, t) ->
    val cx = (idx % cols) * (cell + 4); val cy = (idx / cols) * (cell + 4)
    canvas.setRGB(cx, cy, cell, cell, t, 0, cell)
  }
  out.parentFile?.mkdirs()
  ImageIO.write(canvas, "png", out)
  println("${out.path}  ${tiles.size} 格  每格 ${cell}px")
  for ((k, _) in tiles) println("   $k")
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}
