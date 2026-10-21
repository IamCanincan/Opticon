package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **A′ 演算台：不拆输入，只把「背景层里那块确定的盘」标给模型。**
 *
 * 为什么不是"分层各算一次"（`LayerRefKt` 演算过，不安全）：azure 的背景层是**整张纯白**，
 * 盾牌画在前景层。分层各算等于把"白盘托着盾牌"这层关系拆掉 —— 前景层里没有空气，
 * 模型只能从"平蓝盾牌"和"浅蓝小人"里挑一个当盘，它挑了盾牌，**盾牌整个被丢**（65%→21% 的墨）。
 * translate 恰好相反：它的两块盘分别在两层里，合成之后模型只认一块，另一块被交成墨 = 「半边黑」。
 *
 * A′ 只借图层结构回答一个问题：**这一格在屏幕上是不是"背景层自己铺出来的那块平色"？**
 * 是 → 那是盘，**不许交成墨**。三条同时成立才算：
 *   ① 背景层在这一格不透明；② 这一格属于背景层的**最大色族**（所以画在背景上的字不会被误标 ——
 *   translate 的「文」是深灰，不在那块浅灰大盘的族里）；③ 前景层在这一格**不透明就不算**
 *   （被前景盖住的地方，屏幕上看到的不是背景 —— 这条保住 azure 的盾牌）。
 *
 * 用法：`LayerPlateKt <语料目录> [--edge=384] [--ab=出图目录] [--min=85]`
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val abDir = args.firstOrNull { it.startsWith("--ab=") }?.substringAfter("=")
  val minFam = (args.firstOrNull { it.startsWith("--min=") }?.substringAfter("="))?.toIntOrNull() ?: 85
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "/d/tmp/corpus_new")
  val bgs = dir.listFiles { f -> f.name.endsWith("_bg.png") }!!.sortedBy { it.name }
  var marked = 0; var hit = 0; var cleared = 0; var adapted = 0
  val rows = ArrayList<Triple<String, Int, Int>>()
  val shots = ArrayList<Pair<String, BufferedImage>>()
  for (bg in bgs) {
    val stem = bg.name.removeSuffix("_bg.png")
    val fg = File(dir, stem + "_fg.png"); val whole = File(dir, stem + ".png")
    if (!fg.isFile || !whole.isFile) continue
    // ⚠ **先过「会上屏吗」这一关**（主人 2026-10-11 点名 translate 之后，我把 deskclock / audioplayer
    //   也当成病例演算了一遍 —— 两张都带 `_mono`，**是已适配直通的，装机根本不经过模型**）。
    //   语料里躺着 `_mono` 的 = 桌面用它自己那张，我们一行代码都不碰它。
    if (File(dir, stem + "_mono.png").isFile) { adapted++; continue }
    val bgPx = read(bg, edge) ?: continue
    val fgPx = read(fg, edge) ?: continue
    val whPx = read(whole, edge) ?: continue
    val fam = dominant(bgPx, edge, minFam) ?: continue
    // `--viaColor`：**不传掩码，只传一个"背景层那块盘的颜色"**。合成图上被前景盖住的那些格，
    // 屏幕上看到的本来就是前景的颜色 —— 所以 azure 的盾牌天然不会被标成盘，连"前景不透明就不算"
    // 那一条都不需要写。这一档如果和掩码档结果一样，要接的就只是一个 Int 参数，不是一张掩码。
    val viaColor = args.contains("--viaColor")
    // 标盘：背景层这一格不透明 · 属于背景层的最大色族 · 前景在这里不盖东西
    val plate = BooleanArray(edge * edge) { i ->
      if (viaColor) (whPx[i] ushr 24 and 0xff) >= 128 && key(whPx[i]) == fam
      else (bgPx[i] ushr 24 and 0xff) >= 128 && key(bgPx[i]) == fam && (fgPx[i] ushr 24 and 0xff) < 128
    }
    val n = plate.count { it }
    if (n == 0) continue
    marked++
    val w = MonochromeMask.build(whPx, edge, edge, false)
    val m = w.mask?.copyOf() ?: IntArray(edge * edge)
    var zeroed = 0
    for (i in 0 until edge * edge) if (plate[i] && (m[i] ushr 24 and 0xff) > 0) { m[i] = 0; zeroed++ }
    // **擦完不许擦空**：deskclock 的极性本来就是错的（暗盘=墨、白针=空气），把盘擦掉之后什么都不剩 ——
    // 那种情况下留着"能看见但不对"的旧交付，比交一张空掩码（桌面会自己补、极性还会算反）好。
    // 判据：擦完剩下的墨至少要占**原来**的 1/4，否则这一张整张不擦。
    if (zeroed > 0 && ink(m, edge) * 4 >= ink(w.mask, edge)) { hit++; cleared += zeroed }
    else { zeroed = 0; for (i in m.indices) m[i] = w.mask?.get(i) ?: 0 }
    rows.add(Triple(stem.take(44), ink(w.mask, edge), ink(m, edge)))
    if (abDir != null) {
      File(abDir).mkdirs()
      val im = sheet(whPx, w.mask, m, edge)
      ImageIO.write(im, "png", File("$abDir/${if (zeroed > 0) "擦" else "无"}_$stem.png"))
      if (zeroed > edge * edge / 400) shots.add(Pair(stem, im))
    }
  }
  if (abDir != null) {
    shots.chunked(10).forEachIndexed { g, part ->
      val gw = part[0].second.width; val gh = part[0].second.height
      val grid = BufferedImage(gw, gh * part.size + 2 * (part.size - 1), BufferedImage.TYPE_INT_RGB)
      val gg = grid.createGraphics()
      gg.color = java.awt.Color.WHITE; gg.fillRect(0, 0, grid.width, grid.height)
      part.forEachIndexed { i, p -> gg.drawImage(p.second, 0, i * (gh + 2), null) }
      gg.dispose()
      ImageIO.write(grid, "png", File("$abDir/_grid" + "%02d".format(g) + ".png"))
    }
  }
  println("已适配直通、跳过 $adapted 张   背景层能认出主色盘的： 张   其中**模型真的把盘交成了墨**、被 A′ 擦掉的：$hit 张")
  println("擦掉的格数合计 $cleared")
  for ((n, o, s) in rows.sortedBy { it.first }) if (o != s)
    println("     %-46s 墨 %3d%% → %3d%%".format(n, o, s))
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}

/** 背景层的主色族（占实心格 ≥minFam% 才算"这一层就是一块盘"）；不够格返回 null。 */
private fun dominant(px: IntArray, e: Int, minFam: Int): Int? {
  val fam = HashMap<Int, Int>(); var solid = 0
  for (i in 0 until e * e) {
    if ((px[i] ushr 24 and 0xff) < 128) continue
    solid++; fam.merge(key(px[i]), 1, Int::plus)
  }
  if (solid == 0) return null
  val top = fam.maxByOrNull { it.value }!!
  return if (top.value * 100 / solid >= minFam) top.key else null
}
private fun key(p: Int) = ((((p shr 16) and 0xff) / 24) shl 10) or
  ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)

private fun ink(mask: IntArray?, e: Int): Int {
  if (mask == null) return 0
  val b = e * 3 / 4; val o = (e - b) / 2; var n = 0
  for (y in o until o + b) for (x in o until o + b) if ((mask[y * e + x] ushr 24 and 0xff) > 0) n++
  return n * 100 / (b * b)
}

private fun sheet(srcPx: IntArray, old: IntArray?, nu: IntArray?, e: Int): BufferedImage {
  val t = 132
  val out = BufferedImage(t * 3 + 8, t, BufferedImage.TYPE_INT_RGB)
  val g = out.createGraphics()
  val src = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB); src.setRGB(0, 0, e, e, srcPx, 0, e)
  g.drawImage(src, 0, 0, t, t, null)
  g.drawImage(preview(old, e), t + 4, 0, t, t, null)
  g.drawImage(preview(nu, e), t * 2 + 4, 0, t, t, null)
  g.dispose(); return out
}

private fun preview(mask: IntArray?, e: Int): BufferedImage {
  val img = BufferedImage(e, e, BufferedImage.TYPE_INT_RGB)
  val px = IntArray(e * e) { i ->
    val a = if (mask == null) 0 else mask[i] ushr 24 and 0xff
    val c = (0xED * (255 - a) + 0x1F * a) / 255
    0xff000000.toInt() or (c shl 16) or (c shl 8) or c
  }
  img.setRGB(0, 0, e, e, px, 0, e); return img
}
