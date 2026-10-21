package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 量这条闸（`plateIsSubject`）的**波及面与效果**：裁圆判"这块大色块要整块保住"时，
 * 染色不许把它当底摘掉（`MonochromeMask.build(..., plateIsSubject = true)`）。
 *
 * 与装机同一条算式：先按窗口把**输入**重排（自适应→可见区、非自适应→整张画布，最近邻），
 * 再让模型在重排后那张上判 —— 两个版本只差 `plateIsSubject` 一个参数，所以差异就是这一刀本身。
 *
 * ## ⚠ 这条闸有一条**入口**之分（2026-10-16 补，Kodi「还是整块上色了」）
 * 本条成立的原话是「彩色那支判这张是"裁不动、整幅缩进圆里、**裁了只剩这么点**" —— 这块大色块
 * 是**要被整块保住的图案**」。那个"裁了只剩"指的是 plate 档的 **keep 入口**（圆会咬掉一大块形状）；
 * 而 2026-10-15 给 plate 档加的**第二条入口 `spiky`**（"圆盘比外框小太多"，`COVER_BOX_FLOOR`）
 * 说的是**几何**、对像素归属一个主张都没有 —— 极性跟着它走就成了"这张图里没有底"。
 * 所以装机口径是 `plate && keepPct >= min && !spiky`，**本尺子按它量**。
 *
 * 用法：`harness.PlateSubKtKt <语料目录...> [--edge=384] [--min=50]`
 */
// （原来这里有一份 `CAP = 1.82f`，2026-10-17 删：上限已进 IconCropWindow.GEO_ZOOM_CAP）

fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  require(dirs.isNotEmpty() && dirs.all { it.isDirectory }) { "语料目录不存在：${dirs.joinToString()}" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val min = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt()
    ?: IconCropWindow.PLATE_SUBJECT_MIN
  val inset = (edge * 0.25f / 1.5f).toInt()

  fun fld(s: String, k: String) = Regex("$k=([0-9./+-]+)").find(s)?.groupValues?.get(1) ?: "-"
  fun levels(m: IntArray?): String {
    if (m == null) return "-"
    var zero = 0; var faint = 0; var mid = 0; var solid = 0
    for (p in m) when (val a = (p ushr 24) and 0xff) {
      0 -> zero++
      !in 1..127 -> if (a == 255) solid++ else mid++
      else -> faint++
    }
    return "0=$zero 淡=$faint 中=$mid 实=$solid"
  }

  var seen = 0; var fired = 0; var blast = 0; var blastMoved = 0
  // 这台子**自己看得见多少**：plate 档里 `keepPct` 落在阈值 ±5 带内的张数。
  // 为什么要钉它（2026-10-16）：把 `PLATE_SUBJECT_MIN` 从 50 改成 45，这道闸**照样绿** ——
  // 因为这份语料在 45~50 之间一张都没有，而真机在那儿压着三张（contacts 判剩 47/49/49）。
  // 也就是说"闸没响"不等于"改对了"，只等于"这条带子是空的"。把它钉进 pin，
  // 语料哪天变薄/变厚都会当场显形，不会 again 变成一句"全绿"。
  var nearBand = 0
  val firedSet = ArrayList<String>(); val blastSet = ArrayList<String>()
  // 名字去掉尾上那 12 位内容签名：换一次抓取签名就变，钉集合要的是**同一张图**的稳定身份。
  fun stem(f: File) = f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "")
  for (dir in dirs) {
    val cls = File(dir, "index.tsv").let { ix ->
      if (!ix.isFile) emptyMap() else ix.readLines().drop(1).mapNotNull { l ->
        val c = l.split("\t"); if (c.size > 5) c[2] to c[5] else null
      }.toMap()
    }
    val files = dir.listFiles()!!.filter {
      it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png") &&
        !it.name.endsWith("_bg.png") && !it.name.endsWith("_fg.png") && !it.name.endsWith("_mono.png")
    }.sortedBy { it.name }
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val sw = img.width; val sh = img.height
      val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
      val px = IntArray(edge * edge)
      for (y in 0 until edge) for (x in 0 until edge) {
        val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
        val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
        px[y * edge + x] = s0[sy * sw + sx]
      }
      seen++
      // ⚠ 走 `IconCropWindow.measure()` —— 装机那条链（量窗口 + 卡 `GEO_ZOOM_CAP`）的唯一入口。
      //   以前这里 `of(...).takeIf { zoom <= CAP }` 自带一份 `private const val CAP = 1.82f`，
      //   生产改上限时这台子不会跟着动 —— 与今天骂过的"闸测镜像"同一件事。
      val win = IconCropWindow.measure(px, edge) ?: continue
      val adaptive = (cls[f.name] ?: "").contains("Adaptive")
      val off = if (adaptive) inset else 0
      val side = if (adaptive) edge - 2 * inset else edge
      val pxIn = IconCropWindow.reframe(px, edge, edge, win, off, off, side, side)
      // ⚠ `on` **必须调生产那一份**（`Window.polaritySubject`），不许在这里重写式子 ——
      //   2026-10-16 全量检查抓到这道闸原先抄了一份：删掉生产里的 `!spiky`，pin 一个字都不动，
      //   而它当天还"变异测试通过"过一次。`oldOn` 是**故意**留的旧口径（守卫加之前长什么样），
      //   两个集合的差才是这道闸要钉的东西。
      if (win.plate && win.keepPct >= min - 5 && win.keepPct < min + 5) nearBand++
      val oldOn = win.plate && win.keepPct >= min
      val on = win.polaritySubject
      if (on) { fired++; firedSet += stem(f) }
      val differs = oldOn != on
      if (differs) { blast++; blastSet += stem(f) }
      val a = MonochromeMask.build(pxIn, edge, edge, false, null, oldOn)
      val b = MonochromeMask.build(pxIn, edge, edge, false, null, on)
      val same = (a.mask == null && b.mask == null) ||
        (a.mask != null && b.mask != null && a.mask.indices.all {
          ((a.mask[it] ushr 24) and 0xff) == ((b.mask[it] ushr 24) and 0xff) })
      if (!differs && (a.report == b.report)) continue
      if (differs) {
        if (!same) blastMoved++
        println("%-40s %s  波及 spiky=%s 档=%s剩%d%% zoom=%.3f rCover/rBox=%.2f".format(
          f.nameWithoutExtension.take(40), if (adaptive) "自适应" else "非自适应", win.spiky,
          if (win.plate) "plate" else "inscribe", win.keepPct, win.zoom, win.rCover / win.rBox))
        println("   旧(屁股=true ) H=${fld(a.report, "H")} 底占=${fld(a.report, "底占")} 框内=${fld(a.report, "框内")} 淡=${fld(a.report, "淡")} ${levels(a.mask)}")
        println("   新(屁股=false) H=${fld(b.report, "H")} 底占=${fld(b.report, "底占")} 框内=${fld(b.report, "框内")} 淡=${fld(b.report, "淡")} ${levels(b.mask)}")
        println("   产物${if (same) "**逐格相同**（这一张不受影响）" else "变了"}")
      } else {
        println("%-40s %s  开火=%-5s 档=%s剩%d%%\n   旧: H=%s 底占=%s 框内=%s\n   新: H=%s 底占=%s 框内=%s".format(
          f.nameWithoutExtension.take(42), if (adaptive) "自适应" else "非自适应", on.toString(),
          if (win.plate) "缩" else "切", win.keepPct,
          fld(a.report, "H"), fld(a.report, "底占"), fld(a.report, "框内"),
          fld(b.report, "H"), fld(b.report, "底占"), fld(b.report, "框内")))
      }
    }
  }
  println("扫过 $seen 张：新口径开火 $fired 张；**波及**（旧开火、新不开火）$blast 张，其中产物真的变了 $blastMoved 张")
  // 给闸门读的机器行：**钉的是集合，不是张数** —— 换进来/换出去一张，集合就变了。
  println("PS n=$seen fired=$fired blast=$blast moved=$blastMoved 近线带=$nearBand 开火集=[${firedSet.sorted().joinToString(",")}] 波及集=[${blastSet.sorted().joinToString(",")}]")
  if (fired == 0) { println("!! 一张都没开火 —— 这台子什么都没比"); kotlin.system.exitProcess(1) }
}
