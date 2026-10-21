package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **一把尺量两边**：同一张图上「交出去的墨有多少落在半透明那一段（α 1..127）」——
 * 屏幕上那就是一层看得见的灰膜。
 *
 * 为什么要一台子量两边而不是各量各的：设计师那一层是**现成的 PNG**（直接读像素），
 * 我们交出去的是**模型跑出来的**（要先过 `MonochromeMask.build`）。两边分母必须一模一样 ——
 * 之前拿判据日志里的 `浓度[...]` 和设计师的图比，那个数分母是**整幅画布**、这里是**内切方框**，
 * 量出来"134%"才看见错（跨台子不数分母，这条已经写过一次，又犯在我自己头上）。
 *
 * 用法：`FilmAreaKt [--model] 目录 [--suffix=X]` —— `--model` = 把每张喂给模型再量（我们的），
 * 不加 = 直接量这张文件自己的 alpha（设计师的）。两条走**同一个** `box` 与同一套分档。
 */
fun main(args: Array<String>) {
  val model = args.contains("--model")
  val sfx = args.firstOrNull { it.startsWith("--suffix=") }?.substringAfter("=")
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_audit3")
  // ⚠ **有 `index.tsv` 就按它办**，两件事：① 已适配那批（`mono=有`）**根本不上我们的模型**
  //   （桌面用它自带的遮罩），把它们算进分母 = 给一条永远不会上的路打分；
  //   ② `plateIsLayer` 这一位真机是 `MonochromeSynth` 传的，不传就等于没在跑装机那条路
  //   （`MaskBench` 里 2026-10-09 那条教训，同一个坑）。
  val ix = File(dir, "index.tsv")
  val plateByFile = HashMap<String, Boolean>()
  val adapted = HashSet<String>()
  // ⚠ 有 index 就**只量它列出的那些文件** —— 同一目录里躺着 `_bg` / `_fg` / `_mono` 兄弟，
  //   它们不是模型自己会吃的那张输入（`_bg` 是给 `盘刀` 用的另一层）。不限制的话 61 张会数成 152 张。
  val listed = HashSet<String>()
  if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 9) {
      listed.add(c[2])
      if (c[8] == "有") adapted.add(c[2])
      plateByFile[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    }
  }
  val rows = ArrayList<Triple<String, Double, Double>>()
  val branch = HashMap<String, Int>()
  var skipped = 0
  for (f in dir.listFiles { x -> x.extension.equals("png", true) && (sfx == null || x.name.contains(sfx)) &&
      (listed.isEmpty() || listed.contains(x.name)) }!!
      .sortedBy { it.name }) {
    if (adapted.remove(f.name)) { skipped++; continue }
    val src = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val w = src.width; val h = src.height
    val e = minOf(w, h)
    val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
    val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = big.createGraphics()
    g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
    val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge)
    var who = 0   // 0 = 参照那一支（本来就走这条，入场没开火）1 = 满幅那一支 2 = 选支搬过去的
    val ink = if (model) {
      // `盘刀` 要的另一层：与 MaskBench 同一件事 —— 按同名找 `_bg.png` 兄弟文件，找不到就是 null。
      val bgSib = File(f.parentFile, f.nameWithoutExtension + "_bg.png")
      val under = if (bgSib.isFile && !f.name.endsWith("_bg.png") && !f.name.endsWith("_fg.png"))
        runCatching {
          val bi = ImageIO.read(bgSib); val be = minOf(bi.width, bi.height)
          val bs = if (bi.width == bi.height) bi
            else bi.getSubimage((bi.width - be) / 2, (bi.height - be) / 2, be, be)
          val bb = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
          val gg = bb.createGraphics(); gg.drawImage(bs, 0, 0, edge, edge, null); gg.dispose()
          IntArray(edge * edge).also { bb.getRGB(0, 0, edge, edge, it, 0, edge) }
        }.getOrNull() else null
      val o = MonochromeMask.build(px, edge, edge, plateByFile[f.name] == true, under)
      // ⚠ **不能只看交付报告里有没有 `调=1`**：被选支搬去参照的那张，交出来的就是参照那份报告，
      //   只看它会把它算成"本来就走参照" —— 而这两件事的下一刀完全不在一处
      //   （没开火的 = 参照那一支自己的膜；搬过去的 = 选支判过之后仍然有的膜）。
      val tc = MonochromeMask.tonalClass(px, edge, edge, android.graphics.Rect(0, 0, edge, edge))
      who = if (!o.report.contains("调=1")) { if (tc != null && tc.on) 2 else 0 } else 1
      o.mask ?: IntArray(edge * edge)
    } else px
    val b = edge * 3 / 4; val o = (edge - b) / 2
    var soft = 0; var all = 0
    for (y in o until o + b) for (x in o until o + b) {
      val a = (ink[y * edge + x] ushr 24) and 0xff
      if (a > 0) { all++; if (a < 128) soft++ }
    }
    val cells = b.toLong() * b
    // 包名为空的那几张（无名 drawable）留哈希尾巴 —— 否则三行都印成空串，指着名单就没法指。
    val bare = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").replace(Regex("_.*$"), "")
    val nm = if (bare.isEmpty()) "〔无包名〕" + f.nameWithoutExtension.takeLast(12)
      else bare.take(40)
    branch[nm] = who
    rows.add(Triple(nm, soft * 100.0 / cells, all * 100.0 / cells))
  }
  rows.sortBy { it.second }
  println((if (model) "我们交出去的" else "设计师自己画的") + "  " + dir.name +
    "  " + rows.size + " 张   淡膜占内切框%（升序尾 12 张）")
  val show = if (args.contains("--all")) rows.reversed() else rows.takeLast(12)
  for (r in show) println("   %5.1f%%  总墨 %5.1f%%   %s".format(r.second, r.third, r.first))
  val v = rows.map { it.second }
  val q = { p: Double -> v[minOf(v.size - 1, (v.size * p).toInt())] }
  println("   中位 %.1f%%   p90 %.1f%%   最大 %.1f%%   ｜墨量>0 的中位 %.1f%%".format(
    q(0.5), q(0.9), v.maxOrNull() ?: 0.0,
    rows.filter { it.third > 0 }.map { it.third }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }))
  // **全覆盖那本账**（主人 2026-10-11 定成的验收线）：一条尺、一个数 —— 达标的有几张。
  // 线画在 3.5% = **设计师自己那批的 p90**，不是我们够得着的平均值。
  if (args.contains("--score")) {
    val lim = (args.firstOrNull { it.startsWith("--lim=") }?.substringAfter("="))?.toDoubleOrNull() ?: 3.5
    val ok = rows.filter { it.second <= lim }
    println("   ── 淡膜 ≤" + "%.1f".format(lim) + "%（设计师那批 p90）的：${ok.size} / ${rows.size} 张" +
      (if (skipped > 0) "   ｜已适配直通、不进分母：$skipped 张" else ""))
    val tag = arrayOf("参", "满", "搬")
    println("   ── 未达的 ${rows.size - ok.size} 张（降序；满=满幅那一支交付，搬=选支搬去参照，参=本来就走参照）：")
    for (r in rows.filter { it.second > lim }.sortedByDescending { it.second })
      println("      %5.1f%%  总墨 %5.1f%%  %s  %s".format(r.second, r.third,
        tag[branch[r.first] ?: 0], r.first))
    val over = rows.filter { it.second > lim }
    println("   ── 未达里：满幅交付 ${over.count { branch[it.first] == 1 }} 张 · " +
      "选支搬过去仍未达 ${over.count { branch[it.first] == 2 }} 张 · " +
      "本来就走参照 ${over.count { (branch[it.first] ?: 0) == 0 }} 张")
  }
}
