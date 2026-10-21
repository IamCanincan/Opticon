package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * **离线三联对照图**（2026-10-16）：把产品那条**真链路**在语料上跑一遍，逐张出
 *
 *      [彩色内容]  |  [单色·改后（窗口置空）]  |  [单色·改前（窗口照搬）]
 *
 * 三格并排，每格都按**屏幕口径**摆好（层按 `1+2f` 铺开、只留中间 2/3），再叠一圈圆盘轮廓，
 * 于是"单色和彩色一不一样"**看图就能判**，不必等真机 —— 这正是主人要的那把尺子。
 *
 * ## 三格各自是什么
 * · **彩色内容** = 屏幕上那个彩色图标里的**图案**：自适应图标由桌面把子层按 1.5 铺开再裁四边
 *   （`AdaptiveIconDrawable.draw()`），所以 = `expandLayer(层)`。盘是主题给的，两路共有，不画。
 * · **单色·改后** = `MonochromeMask.build(层)` 的真输出（窗口置空 = 不搬）→ 交付掩码仍是**层画布**，
 *   桌上按 1.5 铺开 → `expandLayer(掩码)`。
 * · **单色·改前** = HEAD 那行代码（`of(层) ≤1.82` 才搬、`reframe` 到可见区）产出的掩码，同样铺开。
 *
 * ⚠ **只看几何**：掩码是模型从图案里抽的剪影，形状本来就比图案"瘦"；这一页比的是
 *   **位置与跨度**（墨的外接框），不是像素是否逐点相同。跨度读数打在下面那张表里。
 * ⚠ 非自适应不做 —— 两路量的是同一张位图、真机逐张相等（`webapk 1.19/1.21`、`kodi 0.85/0.83`）。
 *
 * 用法：`sh run.sh harness.MonoShotKt <目录...> [--edge=384]` → `_shot/<图标>_tri.png`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  val cls = HashMap<String, String>(); val flat = HashSet<String>()
  for (d in dirs) {
    val ix = File(d, "index.tsv"); if (!ix.isFile) continue
    for (l in ix.readLines().drop(1)) {
      val c = l.split("\t")
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
  }
  val f = 0.25f
  val inset = (edge * f / (1f + 2f * f)).toInt(); val vis = edge - 2 * inset
  val outDir = File("_shot"); outDir.mkdirs()
  println("%-46s %-9s %-8s %-8s %s".format("图标", "彩色层墨", "改后墨", "改前墨", "改前−改后"))
  var n = 0; var bad = 0
  for (d in dirs) {
    val files = d.listFiles()?.filter {
      it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") && !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
    for (file in files) {
      val img = runCatching { ImageIO.read(file) }.getOrNull() ?: continue
      val px = raster(img, edge)
      val adaptive = (cls[file.name] ?: "").contains("Adaptive")
      if (!adaptive) continue
      n++
      val fgOnly = !flat.contains(file.name)                  // 只给前景层那支（背景是纯色盘）
      val plateLayer = fgOnly
      // ① 彩色内容（屏幕上那张）= 层按 1.5 铺开
      val color = expandLayer(px, edge)
      // 窗口：**改前**对所有分支都算（HEAD 那行）；**改后**只给非「只给前景层」的分支
      val wOld = IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= 1.82f }
      val wNew = if (fgOnly) null else wOld
      // ③ 改前：窗口照搬，搬到可见区
      val pxOld = if (wOld == null) px else IconCropWindow.reframe(px, edge, edge, wOld, inset, inset, vis, vis)
      val mOld = MonochromeMask.build(pxOld, edge, edge, plateLayer).mask
      // ② 改后：只给前景层那支窗口置空 → 不搬；其余分支与改前逐字节同路
      val pxNew = if (wNew == null) px else IconCropWindow.reframe(px, edge, edge, wNew, inset, inset, vis, vis)
      val mNew = MonochromeMask.build(pxNew, edge, edge, plateLayer).mask
      val sheet = BufferedImage(edge * 3 + 12, edge, BufferedImage.TYPE_INT_ARGB)
      val g = sheet.createGraphics()
      g.drawImage(toImg(overPlate(color), edge), 0, 0, null)
      g.drawImage(toImg(monoOverPlate(mNew, edge), edge), edge + 6, 0, null)
      g.drawImage(toImg(monoOverPlate(mOld, edge), edge), 2 * (edge + 6), 0, null)
      g.dispose()
      ImageIO.write(sheet, "png", File(outDir, file.name.replace(".png", "") + "_tri.png"))
      val sc = inkSpan(color, edge)
      val sn = if (mNew == null) -1f else inkSpan(expandLayer(mNew, edge), edge)
      val so = if (mOld == null) -1f else inkSpan(expandLayer(mOld, edge), edge)
      val d = if (sn < 0 || so < 0) 0f else so - sn
      if (kotlin.math.abs(d) > 0.10f) bad++
      println("%-46s %-9s %-8s %-8s %s".format(file.name.take(46),
        "%.0f%%".format(sc * 100),
        if (sn < 0) "无掩码" else "%.0f%%".format(sn * 100),
        if (so < 0) "无掩码" else "%.0f%%".format(so * 100),
        when {
          so < 0 || sn < 0 -> "—"
          kotlin.math.abs(d) <= 0.01f -> "不受影响（逐字节同路）"
          else -> "%+.0f 点".format(d * 100)
        }))
    }
  }
  println("共 $n 张自适应 → _shot/*_tri.png（左=彩色内容 / 中=单色改后 / 右=单色改前）；" +
    "改前改后墨跨差 >10 点的 $bad 张")
}

/** 墨的外接框最长边 ÷ 画布边长（**alpha ≥ 64 = 肉眼可见**；`≥8` 会把看不见的淡晕也算进去、
 *  读数虚高，2026-10-16 当场栽过一次）。 */
private fun inkSpan(px: IntArray, edge: Int): Float {
  var x0 = edge; var x1 = -1; var y0 = edge; var y1 = -1
  for (y in 0 until edge) for (x in 0 until edge) {
    if ((px[y * edge + x] ushr 24) < 64) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  return if (x1 < 0) 0f else max(x1 - x0, y1 - y0).toFloat() / edge
}

/** 彩色内容叠在一层浅底上（只为看得见；盘是主题给的，不参与判断）。 */
private fun overPlate(px: IntArray): IntArray {
  val out = IntArray(px.size)
  for (i in px.indices) {
    val a = (px[i] ushr 24) and 0xff
    val r = (((0xe8 shr 16 and 0xff) * (255 - a) + (px[i] shr 16 and 0xff) * a) / 255) shl 16
    val g = (((0xe8 shr 8 and 0xff) * (255 - a) + (px[i] shr 8 and 0xff) * a) / 255) shl 8
    val b = ((0xe8 and 0xff) * (255 - a) + (px[i] and 0xff) * a) / 255
    out[i] = (0xff shl 24) or r or g or b
  }
  return out
}

/** 交付掩码按桌面的上色法（盘×(1-a) + 墨×a）画出来：深盘 + 浅墨。 */
private fun monoOverPlate(mask: IntArray?, edge: Int): IntArray {
  val out = IntArray(edge * edge)
  val plate = 0x2b2f36; val ink = 0xf2f4f7
  if (mask == null) { out.fill((0xff shl 24) or plate); return out }
  for (i in out.indices) {
    val a = (mask[i] ushr 24) and 0xff
    val r = (((plate shr 16 and 0xff) * (255 - a) + (ink shr 16 and 0xff) * a) / 255) shl 16
    val g = (((plate shr 8 and 0xff) * (255 - a) + (ink shr 8 and 0xff) * a) / 255) shl 8
    val b = ((plate and 0xff) * (255 - a) + (ink and 0xff) * a) / 255
    out[i] = (0xff shl 24) or r or g or b
  }
  return out
}

private fun toImg(px: IntArray, edge: Int): BufferedImage {
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  img.setRGB(0, 0, edge, edge, px, 0, edge)
  return img
}

private fun raster(img: java.awt.image.BufferedImage, edge: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
    px[y * edge + x] = s0[sy * img.width + sx]
  }
  return px
}

/** 把「层」按 `AdaptiveIconDrawable.draw()` 的摆法铺成屏幕上那张（与 `DeckKt` 同源）。 */
private fun expandLayer(px: IntArray, edge: Int): IntArray {
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
