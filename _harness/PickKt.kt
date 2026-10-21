package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * **选支探子台**：同一张图上把两条都交付一遍，并排量「淡膜%/墨%/浓度种数」，
 * 再加满幅那一支自己的三条入场数（纹理/主色/跨度）。
 *
 * 为什么要它：`Both` 按成品选支的判据只有「淡膜≤4」+「墨≥满幅的 60%」。
 * 而**淡膜就是中间调那一段**（α 1..127）—— 拿它当裁判天生偏心量化那一支
 * （发档/接带/抬顶会把中间格吸成整档，淡膜自然就小）。`SelfTest` 里那张连续调
 * photo fixture（纹 68、跨度 69）就是这么被搬去参照、浓度种数从连续掉到 5 种的
 * = 主人早就报过的那个「渐变被做成色块」。
 * 这一台子把「搬走的」与「留下的」摊开，再决定第三条判据长什么样。
 *
 * 用法：`PickKt [--edge=384] [--guard=N] [--flip] 目录…`
 *   `--guard=N`  候选第三条：满幅那一支自己交出的种数 > N 就不搬（连续调不许量化）
 *   `--flip`     每张在 96/192/288/384 四档各判一次，数「结论随出图尺寸翻」的有几张
 */
fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val guard = (args.firstOrNull { it.startsWith("--guard=") }?.substringAfter("="))?.toIntOrNull()
  val flip = args.contains("--flip")
  val abDir = args.firstOrNull { it.startsWith("--ab=") }?.substringAfter("=")
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u") }
  for (d in dirs) {
    val dir = File(d)
    // ⚠ **按 `index.tsv` 传 `plateIsLayer`** —— 真机上这一位是 `MonochromeSynth` 给的
    //   （自适应 + 背景是 `ColorDrawable` = 只把前景层交进来，盘由桌面自己补）。
    //   不传就等于对 10/62 张量的是**另一条路上会发生什么**。
    val plateByFile = HashMap<String, Boolean>()
    val passthrough = HashSet<String>()
    // `--input=model` = 吃装机那一张（`_model.png` / `_modelbg.png`）。合成图 + 可见区 `_bg.png`
    //  这两位错位会把 `盘刀` 比成错位的两张，见 Band2Kt 同名参数那段。
    val modelInput = args.contains("--input=model")
    val underOf = HashMap<String, File>()
    File(dir, "index.tsv").let { ix ->
      if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
        val c = l.split('\t')
        if (c.size >= 8) plateByFile[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
        // ⚠ **自带 `_mono` 的那批是直通，根本不上模型的图** —— 数进"搬了几张"的分母里就是假账
        //   （rec_fresh 123 行里 62 行是这种）。今天在这条上翻过两次，别再第三次。
        if (c.size >= 9 && c[8] == "有") passthrough.add(c[2])
        if (c.size >= 19 && modelInput && !(c.size >= 9 && c[8] == "有")) {
          val mf = if (c[17] == "same") c[2] else c[17]
          if (File(dir, mf).isFile) {
            plateByFile[mf] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
            underOf[mf] = if (c[18] == "-" || c[18] == "null" || c[18] == "err") File("") else File(dir, c[18])
          }
        }
      }
    }
    val rows = ArrayList<PickRow>()
    val sheets = ArrayList<Pair<String, BufferedImage>>()
    val files: List<File> = if (modelInput) underOf.keys.map { File(dir, it) }.sortedBy { it.name }
    else dir.listFiles { x -> x.extension.equals("png", true) &&
        !x.name.endsWith("_bg.png") && !x.name.endsWith("_fg.png") &&
        !x.name.endsWith("_mono.png") && !x.name.endsWith("_model.png") &&
        !x.name.endsWith("_modelbg.png") && !x.name.contains("preview") &&
        !passthrough.contains(x.name) }!!.sortedBy { it.name }
    var noFire = 0
    val skipPt = if (modelInput) 0 else dir.listFiles { x -> x.extension.equals("png", true) &&
        (x.name.endsWith("_bg.png") || x.name.endsWith("_fg.png") ||
          x.name.endsWith("_mono.png") || x.name.contains("preview") ||
          passthrough.contains(x.name)) }!!.size
    for (f in files) {
      val px = load(f, edge) ?: continue
      val layer = plateByFile[f.name] == true
      val bgSib = underOf[f.name] ?: File(dir, f.nameWithoutExtension + "_bg.png")
      val tc = MonochromeMask.tonalClass(px, edge, edge, android.graphics.Rect(0, 0, edge, edge))
      if (tc == null || !tc.on) { noFire++; continue }
      val ramp = MonochromeMask.probeRamp(px, edge, edge, layer,
        if (bgSib.isFile) load(bgSib, edge) else null)
      val ref = MonochromeMask.probeRef(px, edge, edge, layer,
        if (bgSib.isFile) load(bgSib, edge) else null)
      val r = measure(ramp.mask, edge); val k = measure(ref.mask, edge)
      val guarded = MonochromeMask.probePick(edge, edge, ramp, ref) &&
        (guard == null || r.tones <= guard)
      var fp = ""
      if (flip) {
        val per = intArrayOf(96, 192, 288, 384).map { e ->
          val q = load(f, e)
          if (q == null) false
          else {
            val t = MonochromeMask.tonalClass(q, e, e, android.graphics.Rect(0, 0, e, e))
            if (t == null || !t.on) false
            else {
              val u = if (bgSib.isFile) load(bgSib, e) else null
              val a = MonochromeMask.probeRamp(q, e, e, layer, u); val b = MonochromeMask.probeRef(q, e, e, layer, u)
              MonochromeMask.probePick(e, e, a, b) &&
                (guard == null || measure(a.mask, e).tones <= guard)
            }
          }
        }
        fp = per.joinToString("/") + (if (per.distinct().size > 1) "  ←翻" else "")
      }
      rows.add(PickRow(short(f.nameWithoutExtension), tc.tex, tc.flat, tc.span,
        r.soft, r.wide, r.ink, r.tones, k.soft, k.wide, k.ink, k.tones, guarded, fp))
      if (abDir != null) {
        val tag = (if (guarded) "搬" else "留") + "_" + short(f.nameWithoutExtension) +
          "_" + "%02d".format(r.tones)
        runCatching {
          File(abDir).mkdirs()
          val img = strip(px, ramp.mask, ref.mask, edge)
          ImageIO.write(img, "png", File("$abDir/$tag.png"))
          sheets.add(Pair(tag, img))
        }
      }
    }
    if (abDir != null) {
      val byTones = sheets.sortedByDescending { it.first.substringAfterLast("_").toIntOrNull() ?: 0 }
      byTones.chunked(12).forEachIndexed { n, part ->
        val w = part[0].second.width; val hgt = part[0].second.height
        val s = BufferedImage(w, hgt * part.size + 2 * (part.size - 1), BufferedImage.TYPE_INT_ARGB)
        val g = s.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, s.width, s.height)
        part.forEachIndexed { i, p -> g.drawImage(p.second, 0, i * (hgt + 2), null) }
        g.dispose()
        ImageIO.write(s, "png", File("$abDir/_sheet${"%02d".format(n)}.png"))
      }
      println("  出图 $abDir：每张 = 源图｜满幅那一支｜参照那一支（都铺在 ${"#%06X".format(PLATE)} 盘上），" +
        "拼好的 _sheetNN 按「满幅交出的种数」降序 —— 前几张是最容易被做成色块的")
    }
    val moved = rows.filter { it.pick }; val kept = rows.filter { !it.pick }
    println("=== $d  edge=$edge  开火 ${rows.size} 张（未开火 $noFire，直通跳过 $skipPt）  " +
      "搬去参照 ${moved.size} 张   guard=${guard ?: "无"} ===")
    println("                                        满幅那一支              参照那一支")
    println("  纹/主/跨            淡/灰  墨   种  ｜  淡/灰  墨   种   搬   名字               96/192/288/384")
    for (x in (moved + kept).sortedBy { -it.rTones }) println(fmt(x))
    println("  ── 分布  搬走的 种数 ${(moved.map { it.rTones }.sorted().stats())}" +
      "  纹理 ${(moved.map { it.tex }.sorted().stats())}")
    println("     留下的 种数 ${(kept.map { it.rTones }.sorted().stats())}" +
      "  纹理 ${(kept.map { it.tex }.sorted().stats())}")
    if (flip) println("  ── 尺寸翻结论的张数： ${rows.count { it.flips.contains("←翻") }} / ${rows.size}")
  }
}

class PickRow(val name: String, val tex: Int, val flat: Int, val span: Int,
  val rSoft: Int, val rWide: Int, val rInk: Int, val rTones: Int,
  val kSoft: Int, val kWide: Int, val kInk: Int, val kTones: Int,
  val pick: Boolean, val flips: String = "")

private fun load(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w0 = src.width; val h0 = src.height
  val e0 = minOf(w0, h0)
  val sq = if (w0 == h0) src else src.getSubimage((w0 - e0) / 2, (h0 - e0) / 2, e0, e0)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge)
  return px
}

/** 包名为空的那几张（无名 drawable）留哈希尾巴，不然三行都印成空串。 */
private fun short(s: String): String {
  val pkg = s.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
  return if (pkg.isEmpty()) "〔无包名〕" + s.takeLast(12) else pkg.take(22)
}

private class Mval(val soft: Int, val wide: Int, val ink: Int, val tones: Int)
private fun measure(mask: IntArray?, e: Int): Mval {
  if (mask == null) return Mval(0, 0, 0, 0)
  val b = e * 3 / 4; val o = (e - b) / 2
  var soft = 0; var wide = 0; var all = 0
  val h = IntArray(256)
  for (y in o until o + b) for (x in o until o + b) {
    val a = (mask[y * e + x] ushr 24) and 0xff
    h[a]++
    if (a > 0) { all++; if (a < 128) soft++; if (a < 255) wide++ }
  }
  val cells = b.toLong() * b
  return Mval((soft * 100 / cells).toInt(), (wide * 100 / cells).toInt(),
    (all * 100 / cells).toInt(), (1..255).count { h[it] * 200L >= cells })
}

private fun fmt(x: PickRow) =
  "  %3d/%3d/%3d  %4d/%4d %4d %4d ｜ %4d/%4d %4d %4d  %s   %-22s %s".format(
    x.tex, x.flat, x.span, x.rSoft, x.rWide, x.rInk, x.rTones,
    x.kSoft, x.kWide, x.kInk, x.kTones, if (x.pick) "搬" else "·", x.name, x.flips)

private fun List<Int>.stats(): String {
  if (isEmpty()) return "空"
  val s = sorted()
  return "中位 ${s[size / 2]} p10 ${s[(size * 0.1).toInt()]} p90 ${s[minOf(size - 1, (size * 0.9).toInt())]}" +
    " 范围 ${s.first()}..${s.last()}"
}

// ───────────────── 出图：源图 ｜ 满幅那一支 ｜ 参照那一支 ─────────────────
// 上色**照 MaskBench `--shot` 那一套**：桌面拿 alpha 当不透明度，白天盘 = 0xEDEDED、墨 = 0x1F1F1F。
// （第一版这里拿掩码自己的 RGB 去压盘，交出去的墨是白的，画出来一片白 —— 那不是屏幕上任何东西。）
private const val PLATE = 0xEDEDED
private const val INK = 0x1F1F1F

private fun strip(srcPx: IntArray, ramp: IntArray?, ref: IntArray?, e: Int): BufferedImage {
  val t = 132
  val out = BufferedImage(t * 3 + 8, t, BufferedImage.TYPE_INT_RGB)
  val g = out.createGraphics()
  val src = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
  src.setRGB(0, 0, e, e, srcPx, 0, e)
  g.drawImage(src, 0, 0, t, t, null)
  g.drawImage(preview(ramp, e), t + 4, 0, t, t, null)
  g.drawImage(preview(ref, e), t * 2 + 4, 0, t, t, null)
  g.dispose(); return out
}

private fun preview(mask: IntArray?, e: Int): BufferedImage {
  val img = BufferedImage(e, e, BufferedImage.TYPE_INT_RGB)
  val px = IntArray(e * e) { i ->
    val a = if (mask == null) 0 else (mask[i] ushr 24) and 0xff
    0xff000000.toInt() or
      (((PLATE shr 16 and 0xff) * (255 - a) + (INK shr 16 and 0xff) * a) / 255 shl 16) or
      (((PLATE shr 8 and 0xff) * (255 - a) + (INK shr 8 and 0xff) * a) / 255 shl 8) or
      (((PLATE and 0xff) * (255 - a) + (INK and 0xff) * a) / 255)
  }
  img.setRGB(0, 0, e, e, px, 0, e); return img
}
