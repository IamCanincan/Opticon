package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **把"按作者声明的盘色归零"这一条直接画出来**，一张图回答三问：
 * 盘色匹配到哪些格、这些格是不是**背景**、以及按这条交出去还剩什么。
 *
 * 为什么要它：`bgColor` 那条规则成不成立，不看平均匹配率（68% 这种数谁都能编），
 * 看的是**匹配到的那一片在不在该在的地方**。反例候选就长这样：
 * `com.neowizgames.game.browndust2` —— 自适应 + 有声明盘色（`medD499AB`）的**满幅插画**。
 * 它的盘色如果落在角色的皮肤/头发上，这条规则就会把角色啃掉一块；如果只在画底，就无害。
 *
 * 用法：`harness.PlateShowKtKt <png> <RRGGBB> <out.png> [--edge=384] [--de=3]`
 * 三栏：源图 | 匹配盘色的格（白=匹配）| 按这条交出去的墨（盘→0，其余以盘色为零点亮度斜坡）
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val deMax = args.firstOrNull { it.startsWith("--de=") }?.substringAfter("=")?.toInt() ?: 3
  val src = ImageIO.read(File(args[0])) ?: error("读不到 ${args[0]}")
  val plate = ("FF" + args[1]).toLong(16).toInt()
  val out = File(args[2]).apply { parentFile?.mkdirs() }
  val sw = src.width; val sh = src.height
  val s0 = IntArray(sw * sh); src.getRGB(0, 0, sw, sh, s0, 0, sw)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
    val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
    px[y * edge + x] = s0[sy * sw + sx]
  }
  val pl = MonochromeMask.labChannel(plate, 0); val pa = MonochromeMask.labChannel(plate, 1)
  val pb = MonochromeMask.labChannel(plate, 2)
  // 盘与内容的亮度：决定斜坡往哪边走（内容比盘亮 → 亮=墨；反之暗=墨）
  var ps = 0L; var pn = 0; var os = 0L; var on = 0
  val match = BooleanArray(edge * edge)
  for (i in 0 until edge * edge) {
    val v = px[i]
    if (((v ushr 24) and 0xff) < 255) continue
    val de = MonochromeMask.deltaE(MonochromeMask.labChannel(v, 0), MonochromeMask.labChannel(v, 1),
      MonochromeMask.labChannel(v, 2), pl, pa, pb) shr 6
    val l = MonochromeMask.labChannel(v, 0) shr 6
    if (de <= deMax) { match[i] = true; ps += l; pn++ } else { os += l; on++ }
  }
  val lightInk = on > 0 && pn > 0 && (os / on) > (ps / pn)
  val img = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_RGB)
  for (y in 0 until edge) for (x in 0 until edge) {
    val i = y * edge + x
    val v = px[i]
    val srcPx = if (((v ushr 24) and 0xff) < 8) 0xFF808080.toInt() else (0xFF000000.toInt() or (v and 0xFFFFFF))
    img.setRGB(x, y, srcPx)
    img.setRGB(edge + 8 + x, y, if (match[i]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
    // 交付：匹配盘色 → 0；其余以盘亮度为零点，往内容那一端线性抬到 255
    val a = if (match[i] || ((v ushr 24) and 0xff) < 255) 0 else {
      val l = MonochromeMask.labChannel(v, 0)
      val t = if (lightInk) l - (ps / pn).toInt() * 64 else (ps / pn).toInt() * 64 - l
      val d = if (lightInk) 255 - (ps / pn).toInt() * 64 else (ps / pn).toInt() * 64
      if (d <= 0) 255 else (t * 255 / d).coerceIn(0, 255)
    }
    img.setRGB(edge * 2 + 16 + x, y, 0xFF000000.toInt() or (0xFFFFFF - a * 0x010101))
  }
  ImageIO.write(img, "png", out)
  println("${out.path}  盘色匹配 ${pn * 100 / (edge * edge)}% 实心格  方向=${if (lightInk) "亮=墨" else "暗=墨"}")
}
