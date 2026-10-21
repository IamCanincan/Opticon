package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **A 路演算台（只演算，不动仓）**：自适应图标的两层**各过一次模型**，再把两张掩码按格取大并起来。
 *
 * 为什么这么设计：主人点名 `com.google.android.apps.translate`「半边黑了」。今天重抓才看清 ——
 * 那张**每一层自己都是"一块盘 + 一个字"**：背景层 = 浅灰盘 + 「文」，前景层 = 蓝楔形盘 + 白 G。
 * 装机那条路把两层合成一张交给模型，模型只认一块底，另一块盘就被极性"亮=墨"整片交成墨。
 * 分层各算，等于**给每一层自己的盘各自归属** —— 这是图外信息（图层结构），不是第七根颜色尺子。
 *
 * ⚠ 波及面不能一把翻：`BgPlateKt` 量过，背景是真 drawable 的 57 张里只有 **28 张**背景自己就是一块盘，
 *   另外 **29 张**背景上画的是内容 —— 那些按层算会把"整幅画"里那块被认成盘的色**丢掉**。
 *   所以这一台子按 `--gate=plate` 只对"背景是盘"的那批走分层，其余照旧走合成。
 *
 * 用法：`LayerKt <语料目录> [--edge=384] [--gate=plate|all|none] [--ab=出图目录]`
 *   目录里要有 `<x>.png`（合成）与 `<x>_fg.png` / `<x>_bg.png`（CorpusCapture 同一轮抓的）
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val gate = args.firstOrNull { it.startsWith("--gate=") }?.substringAfter("=") ?: "plate"
  val abDir = args.firstOrNull { it.startsWith("--ab=") }?.substringAfter("=")
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "/d/tmp/corpus_new")
  val bgs = dir.listFiles { f -> f.name.endsWith("_bg.png") }!!.sortedBy { it.name }
  var plate = 0; var art = 0; var split = 0; var changed = 0
  val rows = ArrayList<Triple<String, Int, Int>>()
  val shots = ArrayList<Pair<String, BufferedImage>>()
  for (bg in bgs) {
    val stem = bg.name.removeSuffix("_bg.png")
    val fg = File(dir, stem + "_fg.png")
    val whole = File(dir, stem + ".png")
    if (!fg.isFile || !whole.isFile) continue
    val bgPx = read(bg, edge) ?: continue
    val fgPx = read(fg, edge) ?: continue
    val whPx = read(whole, edge) ?: continue
    val fl = flatness(bgPx, edge)
    // 「背景是一块盘」要三条一起成立，少一条就会把 logo 当盘丢掉：
    //   最大色族占实心格 ≥85%（平）**且** 这一族贴到上下边 ≥90%（铺到边）**且**
    //   实心格占整张画布 ≥90%（**铺满** —— azure 的盾牌是平蓝，但它只盖住六成画布，那是 logo）。
    val isPlate = fl.first >= 85 && fl.second >= 90 && fl.third >= 90
    if (isPlate) plate++ else art++
    val useSplit = gate == "all" || (gate == "plate" && isPlate)
    if (!useSplit) continue
    split++
    // 分层：各算一次，按格取大（谁认出字来谁说话；两块盘各自被自己那层丢掉）
    val a = MonochromeMask.probeRef(fgPx, edge, edge, false)
    val b = MonochromeMask.probeRef(bgPx, edge, edge, false)
    val u = IntArray(edge * edge) { i ->
      maxOf((a.mask?.get(i) ?: 0) ushr 24 and 0xff, (b.mask?.get(i) ?: 0) ushr 24 and 0xff) shl 24 or 0x00FFFFFF
    }
    val w = MonochromeMask.build(whPx, edge, edge, false)
    var diff = 0
    for (i in 0 until edge * edge)
      if (((u[i] ushr 24) and 0xff) / 16 != ((w.mask?.get(i) ?: 0) ushr 24 and 0xff) / 16) diff++
    if (diff > 0) changed++
    rows.add(Triple(stem.take(44),
      ink(w.mask, edge), ink(u, edge)))
    if (abDir != null) {
      File(abDir).mkdirs()
      val im = sheet(whPx, w.mask, u, edge)
      ImageIO.write(im, "png", File("$abDir/${if (isPlate) "盘" else "画"}_$stem.png"))
      shots.add(Pair(stem, im))
    }
  }
  println("背景层文件 ${bgs.size} 张 → 其中盘 $plate 张 / 画 $art 张   gate=$gate 走了分层 $split 张")
  println("分层与合成**成品不一样**的：$changed / $split 张")
  println("  包名（截断）· 合成墨% → 分层墨%···················· 合成墨% → 分层墨%")
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
    println("  出图 $abDir：源图 ｜ 现在（合成输入） ｜ 分层（各走参照），_gridNN 每页 10 张")
  }
  for ((n, o, s) in rows.sortedBy { it.first }) println("     %-46s %3d%% → %3d%%%s".format(
    n, o, s, if (o != s && o > 0 && kotlin.math.abs(s - o) >= 8) "   ←变了" else ""))
}

/** 读一张并按 edge 居中取方（与 FilmAreaKt 同一口径）。 */
private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}

/** 粗量化归族：返回（最大色族占实心格%、这一族贴到上下边的%、实心格占整张画布%）。 */
private fun flatness(px: IntArray, e: Int): Triple<Int, Int, Int> {
  val fam = HashMap<Int, Int>(); var solid = 0
  for (i in 0 until e * e) {
    if ((px[i] ushr 24 and 0xff) < 128) continue
    solid++
    val k = key(px[i])
    fam.merge(k, 1, Int::plus)
  }
  if (solid == 0) return Triple(0, 0, 0)
  val top = fam.maxByOrNull { it.value }!!.key
  var onEdge = 0; var edgeN = 0
  for (y in intArrayOf(1, e - 2)) for (x in 0 until e) {
    val p = px[y * e + x]
    if ((p ushr 24 and 0xff) < 128) continue
    edgeN++; if (key(p) == top) onEdge++
  }
  return Triple(fam[top]!! * 100 / solid, if (edgeN == 0) 0 else onEdge * 100 / edgeN,
    solid * 100 / (e * e))
}
private fun key(p: Int) = ((((p shr 16) and 0xff) / 24) shl 10) or
  ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)

/** 交出去的墨占内切框%。 */
private fun ink(mask: IntArray?, e: Int): Int {
  if (mask == null) return 0
  val b = e * 3 / 4; val o = (e - b) / 2; var n = 0
  for (y in o until o + b) for (x in o until o + b) if ((mask[y * e + x] ushr 24 and 0xff) > 0) n++
  return n * 100 / (b * b)
}

/** 出图：源图 ｜ 现在（合成输入） ｜ 分层。上色照 `MaskBench --shot` 白天那一联。 */
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
    0xff000000.toInt() or
      (((0xED * (255 - a) + 0x1F * a) / 255) shl 16) or
      (((0xED * (255 - a) + 0x1F * a) / 255) shl 8) or
      ((0xED * (255 - a) + 0x1F * a) / 255)
  }
  img.setRGB(0, 0, e, e, px, 0, e); return img
}
