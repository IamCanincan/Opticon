package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * **探针**（不动生产）：把"主体内部的封闭暗区交回底"这条候选规则，
 * 套在**已经交付的掩码**上，看它能不能只挖该挖的。
 *
 * 为什么先在台子上试：这是那堵墙的第五次（前四次全死）。生产里那条刻洞规则
 * 明写「第二遍只降**比主体亮**的那一侧 —— 比主体暗的那些是图形自己的暗部，降它等于把细节擦了」，
 * 而 reddit 的眼/嘴**就是比主体暗的**（白身体 + 灰眼 + 黑嘴，设计师把它们挖成底）。
 * 所以候选不是新发明，是给那句话补一条例外：
 *
 *   **一片墨如果 ① 离底比离主体近 ② 被主体围住（不碰内容框外沿）③ 相对主体小 → 它是洞，交回底。**
 *
 * ⚠ 本台子里的颜色距离是**探针自己的一份**（RGB 通道最大差），不是生产那把 `deltaE`。
 *   这在这里是合法的：这条规则生产里还不存在，没有"原式"可抄；
 *   真要把候选落进生产，距离必须换成 `MonochromeMask` 那一份，不能带这份近似进去。
 *
 * 打分看三样：`病`（设计师保住、我们抹平）应当↓、`过碎` 不许↑、
 * 以及**逐张**看 —— 总数变好而某一张被挖穿，本仓吃过两次这个亏。
 *
 * 用法：`harness.HoleProbeKtKt [yard_model] [--max=25] [--close=40]`
 */
private fun hLoad(p: File, side: Int): IntArray? {
  val img = runCatching { ImageIO.read(p) }.getOrNull() ?: return null
  val w = img.width; val h = img.height
  val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
  if (w == side && h == side) return src
  val out = IntArray(side * side)
  for (y in 0 until side) for (x in 0 until side) {
    val sy = (y.toLong() * h / side).toInt().coerceAtMost(h - 1)
    val sx = (x.toLong() * w / side).toInt().coerceAtMost(w - 1)
    out[y * side + x] = src[sy * w + sx]
  }
  return out
}
private fun a(p: Int) = (p ushr 24) and 0xff
private fun r(p: Int) = (p shr 16) and 0xff
private fun g(p: Int) = (p shr 8) and 0xff
private fun b(p: Int) = p and 0xff
private fun hDist(x: Int, y: Int) = maxOf(abs(r(x) - r(y)), maxOf(abs(g(x) - g(y)), abs(b(x) - b(y))))
private fun hLuma(p: Int) = (r(p) * 299 + g(p) * 587 + b(p) * 114) / 1000

/** 候选规则。返回挖掉的格数。 */
private fun cut(dbg: Boolean, tag: String, src: IntArray, mask: IntArray, edge: Int, maxPct: Int, close: Int,
                out: IntArray): Int {
  val n = edge * edge
  // ① 底色 = 画布四边上**我们没上墨**的那些格的主色；主体色 = 上了墨的格里最多的那个色档。
  val fam = HashMap<Int, Int>()
  for (i in 0 until n) if (a(mask[i]) >= 128 && a(src[i]) >= 128) {
    val k = (r(src[i]) / 32) * 1024 + (g(src[i]) / 32) * 32 + (b(src[i]) / 32)
    fam[k] = (fam[k] ?: 0) + 1
  }
  val body = fam.maxByOrNull { it.value }?.key ?: return 0
  fun keyOf(p: Int) = (r(p) / 32) * 1024 + (g(p) / 32) * 32 + (b(p) / 32)
  val bodyPx = IntArray(3)
  var bn = 0
  for (i in 0 until n) if (a(mask[i]) >= 128 && keyOf(src[i]) == body) {
    bodyPx[0] += r(src[i]); bodyPx[1] += g(src[i]); bodyPx[2] += b(src[i]); bn++
  }
  val bodyCol = (255 shl 24) or ((bodyPx[0] / bn.coerceAtLeast(1)) shl 16) or
    ((bodyPx[1] / bn.coerceAtLeast(1)) shl 8) or (bodyPx[2] / bn.coerceAtLeast(1))
  // 底色：四边不墨的格取均值
  var gr = 0; var gg = 0; var gb = 0; var gn = 0
  for (x in 0 until edge) for (y in intArrayOf(0, edge - 1)) {
    val i = y * edge + x
    if (a(mask[i]) < 128 && a(src[i]) >= 128) { gr += r(src[i]); gg += g(src[i]); gb += b(src[i]); gn++ }
  }
  for (y in 0 until edge) for (x in intArrayOf(0, edge - 1)) {
    val i = y * edge + x
    if (a(mask[i]) < 128 && a(src[i]) >= 128) { gr += r(src[i]); gg += g(src[i]); gb += b(src[i]); gn++ }
  }
  if (dbg) println("     dbg " + tag + " 边上实心底格 gn=" + gn + " 主体档格 bn=" + bn)
  if (gn == 0) return 0
  val groundCol = (255 shl 24) or ((gr / gn) shl 16) or ((gg / gn) shl 8) or (gb / gn)

  // ② 逐格判"离底比离主体近"，再按连通块要求**封闭**（不碰内容框外沿）
  val cand = BooleanArray(n)
  val inked = BooleanArray(n)
  for (i in 0 until n) {
    inked[i] = a(mask[i]) >= 128 && a(src[i]) >= 128
    cand[i] = inked[i] && hDist(src[i], groundCol) <= close &&
      hDist(src[i], groundCol) < hDist(src[i], bodyCol) &&
      hLuma(src[i]) < hLuma(bodyCol)            // 只看"比主体暗"的那一侧（亮侧生产已经刻了）
  }
  // 连通块（8 邻域）：碰内容框外沿的整块都不许挖
  var minX = edge; var minY = edge; var maxX = -1; var maxY = -1
  for (i in 0 until n) if (inked[i]) {
    val x = i % edge; val y = i / edge
    if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y
  }
  if (maxX < 0) return 0
  val seen = BooleanArray(n)
  val stack = IntArray(n)
  var cut = 0
  for (i in 0 until n) {
    if (!cand[i] || seen[i]) continue
    var sp = 0; stack[sp++] = i; seen[i] = true
    val members = ArrayList<Int>()
    var touchesEdge = false
    while (sp > 0) {
      val p = stack[--sp]; members += p
      val x = p % edge; val y = p / edge
      if (x <= minX || y <= minY || x >= maxX || y >= maxY) touchesEdge = true
      for (dy in -1..1) for (dx in -1..1) {
        val nx = x + dx; val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= edge || ny >= edge) continue
        val q = ny * edge + nx
        if (cand[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
      }
    }
    val inkTotal = (0 until n).count { inked[it] }
    if (touchesEdge) continue
    if (members.size * 100 > inkTotal * maxPct) continue      // 太大 = 不是洞，是另一块画
    for (p in members) out[p] = 0
    cut += members.size
  }
  return cut
}

private class HCell { var n = 0L; var sick = 0L; var over = 0L }
private fun hClassify(src: IntArray, ours: IntArray, theirs: IntArray, side: Int, c: HCell) {
  for (y in 0 until side) for (x in 0 until side) {
    val i = y * side + x
    for (j in intArrayOf(if (x + 1 < side) i + 1 else -1, if (y + 1 < side) i + side else -1)) {
      if (j < 0) continue
      if (a(src[i]) < 128 || a(src[j]) < 128) continue
      if (abs(hLuma(src[i]) - hLuma(src[j])) < 9) continue
      c.n++
      val o = abs(a(ours[i]) - a(ours[j])); val t = abs(a(theirs[i]) - a(theirs[j]))
      if (t >= 32 && o == 0) c.sick++ else if (t == 0 && o >= 32) c.over++
    }
  }
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "yard_model")
  val maxPct = args.firstOrNull { it.startsWith("--max=") }?.substringAfter("=")?.toInt() ?: 25
  val dbg = args.contains("--dbg")
  val tonal = args.contains("--tonal")
  val close = args.firstOrNull { it.startsWith("--close=") }?.substringAfter("=")?.toInt() ?: 40
  val index = File(dir, "index.tsv")
  if (!index.isFile) { println("!! 缺 ${index.path}"); exitProcess(2) }
  val rows = index.readText().replace("\r\n", "\n").split('\n').drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
  val before = HCell(); val after = HCell()
  var pairs = 0; var cutCells = 0L; var inkCells = 0L
  val changed = ArrayList<String>()
  for (r in rows) {
    if (r.size < 19) continue
    if (!(r[5].contains("Adaptive") && r[7] == "ColorDrawable")) continue
    val mono = File(dir, r[12]); val modelName = if (r[17] != "-" && r[17] != "err") r[17] else r[2]
    val model = File(dir, modelName)
    if (!mono.isFile || !model.isFile) continue
    val edge = r[3].toInt()
    val mp = hLoad(model, edge) ?: continue
    val raw = runCatching { MonochromeMask.build(mp, edge, edge, true, null) }.getOrNull()?.mask ?: continue
    val ours = toVisible(raw, edge) ?: continue
    val theirs = hLoad(mono, edge) ?: continue
    val src = toVisible(mp, edge) ?: continue
    pairs++
    val cand = ours.copyOf()
    val n = if (tonal) {
      // 主体亮度 = 墨格里最多的那一档的均值；按 L/body 比例重成交付（越暗越少墨）
      var bs = 0L; var bc = 0
      for (i in 0 until edge * edge) if (a(ours[i]) >= 128 && a(src[i]) >= 128) { bs += hLuma(src[i]); bc++ }
      val bodyL = (bs / bc.coerceAtLeast(1)).toInt().coerceAtLeast(1)
      for (i in 0 until edge * edge) {
        val keep = a(ours[i])
        cand[i] = if (keep >= 128) ((hLuma(src[i]) * 255 / bodyL).coerceIn(0, 255) shl 24) or 0x00FFFFFF else 0
      }
      (0 until edge * edge).count { a(ours[it]) >= 128 && a(cand[it]) < 128 }
    } else cut(dbg, r[0], src, cand, edge, maxPct, close, cand)
    cutCells += n; inkCells += (0 until edge * edge).count { a(ours[it]) >= 128 }
    val b0 = HCell(); val a0 = HCell()
    hClassify(src, ours, theirs, edge, b0); hClassify(src, cand, theirs, edge, a0)
    before.n += b0.n; before.sick += b0.sick; before.over += b0.over
    after.n += a0.n; after.sick += a0.sick; after.over += a0.over
    if (n > 0) changed += "   %11s 挖=%-6d 病 %4d→%-4d 过碎 %4d→%-4d  %s".format(
      "", n, b0.sick, a0.sick, b0.over, a0.over, r[0])
  }
  if (pairs == 0) { println("!! 甲类配对 0 张 —— 探针没开火"); exitProcess(1) }
  println("HP${if (tonal) "[按亮度比例交付]" else ""} n=$pairs max=$maxPct% close=$close  挖掉 $cutCells 格（占墨 ${"%.1f".format(cutCells * 100.0 / inkCells.coerceAtLeast(1))}%）")
  println("   病   ${before.sick} → ${after.sick}   (${if (after.sick < before.sick) "↓" else if (after.sick == before.sick) "不变" else "↑ 更坏"})")
  println("   过碎 ${before.over} → ${after.over}   (${if (after.over <= before.over) "没涨 ✓" else "↑ 涨了这个候选不能要"})")
  changed.sorted().forEach { println(it.replace(Regex("^\\s+"), "   ")) }
}
