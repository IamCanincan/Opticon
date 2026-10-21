package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **三联对照：源图 | 交出去的墨压在白底 | 浓度落在哪一段**（桌面深色主题就是中间那张）。
 *
 * 为什么要它：这一轮先拿 `bench/n12f/` 那批量了一遍 ρ色，量完才发现那是**三联拼图**
 *   （1168x384 = 源|深|浅），不是源图。按 384 的真源图（`live61/`）重量：点名的十二张
 *   ρ色 中位 0.88，而全部 61 张中位 0.94 —— 差得根本不多，"相关性"这条路到此为止。
 *   所以这一轮**从图上看**，一张一张判。
 *
 * 中间那张叠两层标记：**红** = 模型自己判为墨、源实心却交 0 = 整块丢了；
 * **黄** = 判为墨、源实心而交付 1..127 = 丢了一半以上。这两类正是"某某没了"的形状。
 * 右边那张按浓度分四段上色：深灰=底、**蓝=贴顶(≥250)**、绿=中段、青=浅墨、白=判为墨却交 0。
 * 蓝占多大一块 = 标定上限吃掉了多少细节（`顶平%` 那一列的形状版）。
 *
 * 用法：`harness.SheetKtKt <目录|png> … [--out=DIR] [--edge=384] [--zoom]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val outDir = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/sheet"
  val zoom = args.contains("--zoom")
  File(outDir).mkdirs()
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  for (f in files) {
    val src = load(f.absolutePath, edge)
    val r = MonochromeMask.build(src, edge, edge)
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val cw = if (zoom) edge * 2 else edge
    val img = BufferedImage(cw * 4 + 24, cw, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(90, 90, 90); g.fillRect(0, 0, img.width, img.height)
    g.dispose()
    val left = IntArray(cw * cw)
    val right = IntArray(cw * cw)
    val band = IntArray(cw * cw)
    val cutP = IntArray(cw * cw)
    // `hollowed` 只有"围住者"那一刀在的时候才有（那一版撤掉之后仓里就没这个字段了）。
    // 量具**不许因为被测对象少一个字段就整个跑不动** —— 缺就当没擦。
    val hw = runCatching { fld(s, "hollowed") as ByteArray }.getOrNull() ?: ByteArray(edge * edge)
    for (y in 0 until cw) for (x in 0 until cw) {
      // 放大时按最近邻取源像素（要看的是"哪一块没了"，不是要好看的缩放）
      val sx = if (zoom) x / 2 else x; val sy = if (zoom) y / 2 else y
      val i = sy * edge + sx
      val p = src[i]
      left[y * cw + x] = if ((p ushr 24) == 0) 0xFF202020.toInt() else (0xFF000000.toInt() or (p and 0x00FFFFFF))
      val a = m[i] ushr 24
      val v = 255 - a
      var c = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
      // ⚠ 红/黄只标**模型自己判为墨**的那些格子。第一版把"源实心且交 0"一律标红，于是
      //   白盘子整圈被涂红 —— 那是**判对了的底**，不是丢了的图形，看的人只会跟着看错。
      if (plate[i] == 0.toByte() && (p ushr 24) == 255) {
        if (a == 0) c = 0xFFFF2020.toInt()
        else if (a < 128) c = 0xFFE0C000.toInt()
      }
      right[y * cw + x] = c
      band[y * cw + x] = when {
        plate[i] != 0.toByte() -> 0xFF303030.toInt()      // 底 = 深灰（不参与"贴顶"的账）
        a >= 250 -> 0xFF0060FF.toInt()                    // 贴顶 = 蓝：这一段 dep 再大也没处显示
        a >= 128 -> 0xFF00C864.toInt()                    // 中段 = 绿：真正在用的标定范围
        a > 0 -> 0xFF00E5E5.toInt()                       // 浅墨 = 青
        else -> 0xFFE0E0E0.toInt()                        // 判为墨却交 0 = 白
      }
      // 第四屏：**「围住者」那一刀擦掉了哪一块** —— 品红 = 被这一刀擦掉的格，其余是源图。
      cutP[y * cw + x] = if (hw[i] != 0.toByte()) 0xFFFF00FF.toInt() else left[y * cw + x]
    }
    img.setRGB(0, 0, cw, cw, left, 0, cw)
    img.setRGB(cw + 8, 0, cw, cw, right, 0, cw)
    img.setRGB(2 * cw + 16, 0, cw, cw, band, 0, cw)
    img.setRGB(3 * cw + 24, 0, cw, cw, cutP, 0, cw)
    val o = File(outDir, f.name.removeSuffix(".png") + ".png")
    ImageIO.write(img, "png", o)
    println("${o.name}")
  }
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun load(path: String, edge: Int): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null)
  g.dispose()
  val px = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, px, 0, edge)
  return px
}
