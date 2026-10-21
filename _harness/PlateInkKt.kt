package harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **主人那张目标适用于几张？（只量，不改模型）** —— 2026-10-12。
 *
 * 他说 translate 要「右半边全黑、只有"文"是镂空的」。那句话翻成结构是：
 * **背景层是一块平色盘 + 盘上画着标记** → 盘交墨、标记镂空、前景那张卡压出空气。
 * 这与 `盘刀` 是**反的**（`盘刀` 2026-10-11 为他的另一句「半边黑了」把盘切成了空气），
 * 所以能不能做，取决于一件事：**这种"平盘 + 标记"的背景层在语料里有几张**。
 * 一张 = 那是个例外，值得为它翻极性；几十张 = 那是一族，翻错了就是 2026-10-08 那次真机炸。
 *
 * 判据（两条都要成立，缺一不算这一族）：
 *   ① **盘**：背景层不透明格里，最大那个 /24 色族盖到 ≥ `PLATE_MIN`% —— 有一块真正的平色底；
 *   ② **标记是平的**：把盘那一族拿掉之后，剩下的格**自己**也几乎只有一种颜色
 *      （次大族占剩下的 ≥ `MARK_MIN`%）—— 也就是"盘 + 字"，不是"盘 + 一张画"。
 * ②就是与满幅那一支的分界：插画盘的"剩下"是连续调子，任何一族都占不大。
 *
 * 用法：`harness.PlateInkKt <目录> [目录…] [--plate-min=40] [--mark-min=60] [--show=all]`
 */
fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }.filter { it.isDirectory }
  val plateMin = argf(args, "--plate-min=") ?: 40.0
  val markMin = argf(args, "--mark-min=") ?: 60.0
  val markFloor = argf(args, "--mark-floor=") ?: 1.0
  val showAll = args.any { it == "--show=all" }
  val edge = 384
  val hits = ArrayList<Array<Any>>()   // [名字, 盘%, 标记占盘外%, 标记亮度宽 p95−p5, 标记中位亮度]
  var withBg = 0; var n = 0
  for (d in dirs) for (f in d.listFiles { x -> x.extension == "png" }!!.sortedBy { it.name }) {
    if (f.name.endsWith("_modelbg.png")) continue
    n++
    val un = File(d, f.nameWithoutExtension + "_modelbg.png")
    if (!un.isFile) continue
    val u = grab(un, edge) ?: continue
    withBg++
    val fam = HashMap<Int, Int>()
    for (p in u) if (((p ushr 24) and 0xff) >= 128) fam[key(p)] = (fam[key(p)] ?: 0) + 1
    val opq = fam.values.sum()
    if (opq == 0) continue
    val top = fam.maxByOrNull { it.value }!!
    val platePct = top.value * 100.0 / opq
    if (platePct < plateMin) continue
    val rest = fam.filter { it.key != top.key }.values.sum()
    val restTop = (fam.filter { it.key != top.key }.maxByOrNull { it.value }?.value ?: 0)
    val markPct = if (rest == 0) 100.0 else restTop * 100.0 / rest
    val restPct = rest * 100.0 / opq
    // ③ **盘上得真有东西**。前 7 名那种 `盘=100% 标记=0%` 是一整张纯色背景层 ——
    //   对它翻极性 = 交出一整块黑方，那正是 2026-10-08 真机炸掉的那一批（第八闸钉着）。
    // ④ **标记自己也得是平的**。装机量出来的：支付宝那个「支」是一道**渐变**，
    //   按"次大族 ≥50%"照样过关，镂空出来是个带白边的灰影子。所以直接量标记那堆格的亮度分布。
    val mL = ArrayList<Int>()
    for (p in u) if (((p ushr 24) and 0xff) >= 128) {
      if (key(p) == top.key) continue
      mL.add((0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt())
    }
    mL.sort()
    fun q(f: Double) = if (mL.isEmpty()) 0 else mL[(mL.size * f).toInt().coerceAtMost(mL.size - 1)]
    if (markPct >= markMin && restPct >= markFloor)
      hits.add(arrayOf(short(f.name), platePct, restPct, (q(0.95) - q(0.05)).toDouble(), q(0.5).toDouble()))
  }
  println("== 背景层 =「一块平色盘 + 盘上标记」的有几张（翻极性的适用面）==")
  println("语料 $n 张，其中 **$withBg 张有背景层**（走叠加那一路才有 `under`）")
  println("盘 ≥${plateMin}% 且 剩下的次大族 ≥${markMin}% 且 剩下那堆 ≥${markFloor}% → **${hits.size} 张**")
  for (e in hits.sortedByDescending { it[1] as Double })
    println("  盘 ${"%.1f".format(e[1] as Double)}%  标记占盘外 ${"%.1f".format(e[2] as Double)}%" +
      "  标记亮度宽(p95−p5)=${"%.0f".format(e[3] as Double)} 中位=${"%.0f".format(e[4] as Double)}  ${e[0]}")
  if (!showAll) println("  （--show=all 另列「有平盘但盘上不是一块标记」的那些，即不能翻的那批）")
}

private fun argf(a: Array<String>, k: String) = a.filter { it.startsWith(k) }.firstOrNull()?.substringAfter(k)?.toDoubleOrNull()
private fun short(s: String) = s.removeSuffix(".png").substringBefore("_adaptive").substringBeforeLast("_")
private fun key(p: Int) = (((p shr 16) and 0xff) / 24 shl 10) or (((p shr 8) and 0xff) / 24 shl 5) or ((p and 0xff) / 24)
private fun grab(f: File, edge: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val out = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics(); g.drawImage(img, 0, 0, edge, edge, null); g.dispose()
  return IntArray(edge * edge).also { out.getRGB(0, 0, edge, edge, it, 0, edge) }
}
