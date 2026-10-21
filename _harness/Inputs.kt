package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **一台判据台的「输入构造」只有一份。**
 *
 * 一个 `File` 要变成喂给 `MonochromeMask.build` 的那四样（`src` / `plateIsLayer` / `under`，
 * 加上按 index 决定"这一张是不是自适应前景层"），这段路本来抄在 [MaskBench] 的 main 里。
 * 别的台子想要同一条路就只能抄一遍抄本 —— 本仓已经为此交过两次假结论：
 *  - 2026-09-29：真机 vs 判据台逐字段对齐 4 张对不上，查下来是那 4 张语料是 192²，
 *    两台各自缩放的方式不同（见 [loadArgb] 上面那段）。
 *  - 2026-10-19：`FaintShotKt` 用 `build(px,w,h)` 默认参数，于是贴吧 / UU 遥控器在像素比对里
 *    **一格没变**，而判据行 `顶占比 14→34 / 10→49` —— 差的就是 `plateLayer` 与 `under`。
 *
 * ⚠ `rec_model` 的装机那条路里有 **10/62 张 `plateIsLayer=true`**（盘在背景层）。任何用 3 参
 *   `build(src, w, h)` 起步的新台子，从定义上看就没在跑那 10 张 —— 而它们恰好是"空气上墨"
 *   最容易发生的那一批（地板那一刀就住在这个分支里，见 `MonochromeMask` 的 `plateLayer`）。
 *
 * 用法：`for (c in cases(File(dir), edge, inset = false, fgAll = false)) … c.src …`
 */
class Case(
  val file: File,
  val src: IntArray,
  /** 语料文件自己的尺寸 —— **不等于 edge 时这一张的数字不能和真机对照**（见 [loadArgb]）。 */
  val srcSize: Pair<Int, Int>,
  /** index 里这一张是不是自适应前景层（`MonochromeSynth` 在真机上按 `source is AdaptiveIconDrawable` 传）。 */
  val isFg: Boolean,
  /** 这一张的承载面在另一层（自适应 + 背景是 ColorDrawable）→ 真机把它当 `plateIsLayer` 传给 build。 */
  val plateLayer: Boolean,
  private val edge: Int
) {
  /**
   * `盘刀` 要的"背景层单独那一张"，找不到就是 null（非自适应图本来就没有另一层）。
   *
   * ⚠ **惰性**不是性能考虑，是为了保住 `量一张` 那根 ms 尺的口径：[MaskBench] 把 `t0` 打在
   *   `src` 解码之后、`under` 解码之前，第一次读这一位才进计时窗口。构造时就解掉会把读盘算进
   *   合成里，那一列整体变大 —— 而它正是"往 build 加只报数的列要先量 ms/张"那条闸用的数。
   */
  val under: IntArray? by lazy { underOf(file, edge) }
}

/** 语料文件 → `edge × edge` 的 ARGB。`inset` 把内容缩到中央 61%（自适应前景层在真机上的画法）。 */
fun loadArgb(path: String, edge: Int, inset: Boolean): IntArray {
  val src = ImageIO.read(File(path))
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  val pad = if (inset) (edge * 0.195f).toInt() else 0
  g.drawImage(src, pad, pad, edge - 2 * pad, edge - 2 * pad, null)
  g.dispose()
  val argb = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, argb, 0, edge)
  return argb
}

/**
 * `盘刀` 要的那张"背景层"。真机上它是 `MonochromeSynth` 现画的；判据台按同名找兄弟文件。
 *
 * ⚠ **优先 `_modelbg.png`**：那是与模型输入同一分支、同一几何（满幅）的一张。老的 `_bg.png`
 *   是**可见区**（子层按 1.5× 铺进去后取中间 2/3），拿它当 `under` 就是让 `盘刀` 比错位的两张
 *   —— 2026-10-11 那条"translate 总灰 39.5%"的假账就是这么来的。
 * ⚠ `_bg` / `_fg` 自己也是语料目录里的文件，但它们不是"图标的另一层"，不能再当 under。
 */
fun underOf(f: File, edge: Int): IntArray? {
  val modelSib = File(f.parentFile, f.nameWithoutExtension + "_modelbg.png")
  val bgSib = if (modelSib.isFile) modelSib else File(f.parentFile, f.nameWithoutExtension + "_bg.png")
  if (!bgSib.isFile || f.name.endsWith("_bg.png") || f.name.endsWith("_fg.png")) return null
  return runCatching {
    val bi = ImageIO.read(bgSib)
    val be = minOf(bi.width, bi.height)
    val bs = if (bi.width == bi.height) bi
    else bi.getSubimage((bi.width - be) / 2, (bi.height - be) / 2, be, be)
    val bb = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val gg = bb.createGraphics(); gg.drawImage(bs, 0, 0, edge, edge, null); gg.dispose()
    IntArray(edge * edge).also { bb.getRGB(0, 0, edge, edge, it, 0, edge) }
  }.getOrNull()
}

/**
 * 读语料目录的 `index.tsv`，回两张表：文件名 → 「自适应前景层」「盘在另一层」。
 *
 * ⚠ 必须**逐张**决定，不能一刀切也不能全不切：`rec_model` 62 张里只有一部分是自适应。
 *   index 缺列时对应那张表就是空的 → 全部按"非自适应"跑，那是**旧口径**，不是故障，
 *   但要用它下装机结论就得先说清这一位没读到。
 */
fun indexFlags(dir: File): Pair<HashMap<String, Boolean>, HashMap<String, Boolean>> {
  val fg = HashMap<String, Boolean>(); val plate = HashMap<String, Boolean>()
  File(dir, "index.tsv").let { ix ->
    if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
      val c = l.split('\t')
      if (c.size >= 6) fg[c[2]] = c[5].contains("Adaptive")
      if (c.size >= 8) plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    }
  }
  return fg to plate
}

/**
 * 一个语料目录 → 一串 [Case]（与 [MaskBench] 装机那条路逐字同一套构造）。
 *
 * ⚠ 分母纪律：跳过 `*_preview.png`（判据台自己 `--shot` 出来的对照图，落进语料就会把 155 行
 *   掺成 42 行输出）和 `*_modelbg.png`（那是别人的 `under`，不是图标 —— 漏过这条 `rec_model`
 *   的 62 张会被数成 74 张）。
 * ⚠ 2026-10-22 同一族**又长出来一次**：`adapted_model` 这一份里带着 `*_mono.png`（应用自画的单色层，
 *   第 8 闸那把尺要的）与 `*_fg.png`/`*_bg.png`（子层），67 张图标被数成 **134** ——
 *   分母翻倍而退码仍是 0。凡是"另一把尺要的附属图"存在同一个目录里的语料，都必须在这一处一起挡掉；
 *   挡在 `cases()` 这一处（只有一份）而不是各台子自己 filter，否则下一份语料又漏。
 *
 * ⚠ 给的是 [Sequence]：**逐张现取**，一台跑到第 30 张崩了，前 29 行的输出还在；一次性 `List`
 *   会在打开任何输出之前把整份语料读完。
 */
fun cases(dir: File, edge: Int, inset: Boolean, fgAll: Boolean): Sequence<Case> {
  val (fgByFile, plateByLayer) = indexFlags(dir)
  return dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_mono.png") &&
    !it.name.endsWith("_fg.png") && !it.name.endsWith("_bg.png") }.sortedBy { it.name }.asSequence().map { f ->
    Case(f, loadArgb(f.absolutePath, edge, inset), ImageIO.read(f).let { it.width to it.height },
      fgAll || fgByFile[f.name] == true, plateByLayer[f.name] == true, edge)
  }
}
