package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 判据搬到**调整后的输入**上，到底动了哪几张（只量不改）。
 *
 * 为什么必须另立一把尺：14 道闸量的是 `MonochromeMask`，而这一刀动的是**喂给它的那张图**
 * （在 `MonochromeSynth` 里，Android 那一侧）—— 那些闸门**结构上看不见它**，绿不绿都不算证据。
 * 所以这里把两边都在台子上跑一遍：`build(原件)` 对 `build(裁好的)`，逐张比判据字段。
 *
 * 落点两档与 `MonochromeSynth` 同一条算式：自适应映射到可见区（中间 2/3），
 * 非自适应映射到整张画布（判据必须吃满幅口径，见那边那段注释）。
 *
 * 用法：`harness.TintInKtKt <语料目录> [--edge=384]`
 */
private const val GEO_ZOOM_CAP = 1.82f

fun main(args: Array<String>) {
  val dir = File(args[0])
  require(dir.isDirectory) { "语料目录不存在：$dir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val inset = (edge * 0.25f / 1.5f).toInt()   // 与 MonochromeSynth.visibleInset 同口径（f=1/4）
  val cls = File(dir, "index.tsv").let { ix ->
    if (!ix.isFile) emptyMap() else ix.readLines().drop(1).mapNotNull { l ->
      val c = l.split("\t"); if (c.size > 5) c[2] to c[5] else null
    }.toMap()
  }
  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "没有 PNG" }

  fun fld(rep: String, k: String) = Regex("$k=([0-9./+-]+)").find(rep)?.groupValues?.get(1) ?: "-"
  var flipped = 0; var moved = 0; var same = 0
  val lines = ArrayList<String>()
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
    val win = IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= GEO_ZOOM_CAP }
    if (win == null) { same++; continue }
    moved++
    val adaptive = (cls[f.name] ?: "").contains("Adaptive")
    val off = if (adaptive) inset else 0
    val side = if (adaptive) edge - 2 * inset else edge
    val pxIn = IconCropWindow.reframe(px, edge, edge, win, off, off, side, side)
    val a = MonochromeMask.build(px, edge, edge, false, null).report
    val b = MonochromeMask.build(pxIn, edge, edge, false, null).report
    val keys = listOf("H", "底占", "框内", "分档", "盘刀", "反相", "顶占比", "带占", "族")
    val diff = keys.filter { fld(a, it) != fld(b, it) }
    if (diff.isEmpty()) { same++; continue }
    flipped++
    lines += "%-40s %-8s %s".format(f.nameWithoutExtension.take(40), if (adaptive) "自适应" else "非自适应",
      diff.joinToString(" ") { "$it ${fld(a, it)}→${fld(b, it)}" })
  }
  println("%-40s %-8s %s".format("图标", "档", "变了哪些判据字段（原件 → 裁好的）"))
  lines.forEach { println(it) }
  println("语料 ${files.size} 张：开窗口的 $moved 张，其中判据字段变了 $flipped 张，逐字段相同 $same 张")
  if (moved == 0) { println("!! 一张都没开窗口 —— 这台子什么都没比"); kotlin.system.exitProcess(1) }
}
