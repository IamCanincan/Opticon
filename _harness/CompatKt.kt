package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **兼容性回归台**：不问"这一张好不好看"，问"这一**类**图标模型有没有答案"。
 *
 * 为什么要另开一个台子：判据台那 113 张真图标全部来自一台机器、一个桌面（launcher3）、
 * 一档出图尺寸（本机实测 edge=384），而 `SelfTest` 那 46 条性质造的图，形状全是
 * "一块铺到边的底 + 上面的图形"。**测试面与算法的假设是同一个形状** —— 于是"通用吗"
 * 这个问题今天没有任何一个数能回答。换一类图标（照片底、长条快捷方式、细线字形、
 * 图形与底同色）、换一档尺寸（96 / 192 / 384 / 768），会发生什么，只能靠猜。
 *
 * 这里就是那一栏：每个**图标类别**一张合成图，各自声明"正确答案落在哪个区间"，
 * 再在**四档边长**上各跑一遍（尺寸那一维以前只有「占框比例不许漂」一条碰过）。
 *
 * ⚠ **只测不改**：这个台子没有权利改变任何一条判据。报 GAP 而不是 FAIL 的那些是
 * **已知没解**的一类（满幅插画：图上没有"底"这个区域），记成红会让整个台子失去
 * "绿=没有回归"的意义，删掉就等于把要求丢了 —— 与 `SelfTest.gap()` 同一套做法。
 *
 * 跑法：`java -Dstdout.encoding=UTF-8 -cp out;stubs_out;<stdlib> harness.CompatKt [边长，如 96,192,384]`
 */

private fun px(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

/** 0 = 这一格不写（留给透明）。 */
private const val NONE = 0

/** 比例写法：`x in rng(w, 0.18, 0.82)`。不这么写就得让 Int 去 `in` 一个 DoubleRange。 */
private fun rng(side: Int, lo: Double, hi: Double): IntRange =
  (side * lo).roundToInt()..(side * hi).roundToInt()

private fun paint(b: IntArray, w: Int, at: (Int, Int) -> Int) {
  val h = b.size / w
  for (y in 0 until h) for (x in 0 until w) {
    val c = at(x, y)
    if (c != NONE) b[y * w + x] = c
  }
}

private fun lerp(c1: Int, c2: Int, t: Double): Int {
  val k = t.coerceIn(0.0, 1.0)
  fun ch(sh: Int) = ((((c1 shr sh) and 0xff) * (1 - k) + ((c2 shr sh) and 0xff) * k)).roundToInt()
  return px(255, ch(16), ch(8), ch(0))
}

private val WHITE = px(255, 255, 255, 255)
private val NEARWHITE = px(255, 250, 250, 250)
private val BLUE = px(255, 30, 90, 200)
private val ORANGE = px(255, 240, 130, 20)
private val DARK = px(255, 24, 24, 28)
private val PAPER = px(255, 252, 252, 252)
private val TEAL = px(255, 20, 160, 120)
private val SLATE = px(255, 40, 60, 150)
private val GREEN = px(255, 60, 150, 90)
private val GLOW = px(60, 255, 255, 255)
private val SHADOW = px(70, 120, 120, 120)

/**
 * 一个图标类别：造图 + 声明"正确答案"的区间。
 *
 * ⚠ [mark] / [plate] 必须**按源色**分开量。判据台踩过的那条就叫 `字均`：它把"盘"和"字"
 * 混进同一个分母，于是白闪电出墨 253/255 的图标被读成"淡"。这里若也用"所有实心像素"，
 * 圆盘+白字那一类会永远报"顶=17%"（分母里 83% 是本来就该为 0 的盘）。
 */
private class Kind(
  val name: String,
  val why: String,
  /** **必须成墨的那些源色**。空 = 所有 α=255 的格。 */
  val mark: Set<Int> = emptySet(),
  /** **必须留空的那些源色**（那块底）。空 = 不量漏。 */
  val plate: Set<Int> = emptySet(),
  /** 非方形：只说明"这一类今天进不到模型"（桌面那边会先压成正方形），这里测的是模型本体。 */
  val wide: Boolean = false,
  /** 已知模型没有答案的一类：越过不了就报 GAP，不报 FAIL。 */
  val noAnswer: Boolean = false,
  val fillLo: Double = -1.0,
  val fillHi: Double = -1.0,
  /** 源色 ∈ [mark] 的格子里，交付到 ≥250 的比例下限。 */
  val topLo: Double = -1.0,
  /** 源色 ∈ [plate] 的格子里，交付 ≥128 的比例**上限**（盘漏上屏多少）。 */
  val leakHi: Double = -1.0,
  /** 底是**连续色**（渐变/照片）时没法列举颜色：这一类里"除 mark 之外所有实心像素"就是底。
   *  不写这条的话 [plate] 是空集，`盘漏%` 永远报 -1 —— 那是**检查从没生效**，不是通过。 */
  val plateRest: Boolean = false,
  /** 允许接手的那几支（空 = 不约束）。只作说明，不判失败。 */
  val hyps: Set<Int> = emptySet(),
  /** 不许交出一张全空的掩码。 */
  val mustHaveInk: Boolean = false,
  /** 钉着"就该交空掩码"的那两类。 */
  val wantsEmpty: Boolean = false,
)

private val kinds = listOf(
  Kind("圆盘+白字", "今天最好的一类：一块铺满的彩色盘 + 白色图形",
    mark = setOf(WHITE), plate = setOf(BLUE),
    fillLo = 5.0, fillHi = 45.0, topLo = 90.0, leakHi = 8.0, hyps = setOf(1, 2)),
  Kind("圆角方盘+白字", "承载面不是正圆的 ROM 桌面（华为/小米的圆角方很常见）",
    mark = setOf(WHITE), plate = setOf(ORANGE),
    fillLo = 5.0, fillHi = 45.0, topLo = 90.0, leakHi = 8.0),
  Kind("满幅照片渐变", "没有\"底\"这个区域 → H3 交出一块实心饼。已知无解，只量它有多实",
    noAnswer = true, mustHaveInk = true, leakHi = -1.0),
  Kind("空气+白剪影", "自带轮廓就是正确答案（框=剪影自己的外接框，所以占比高是对的）",
    mark = setOf(WHITE), fillLo = 60.0, fillHi = 95.0, topLo = 95.0),
  Kind("空气+细线", "低分辨率下最先丢的一类：2px 描边",
    mark = setOf(NEARWHITE), topLo = 40.0),
  Kind("白底黑字", "满幅白底 + 深色 wordmark，H1 的标准猎物",
    mark = setOf(DARK), plate = setOf(PAPER),
    fillLo = 3.0, fillHi = 40.0, topLo = 90.0, leakHi = 8.0),
  Kind("长条快捷方式", "3:1 非方形：模型本体许不许崩（桌面那边会先压成正方形）",
    wide = true, mark = setOf(WHITE), plate = setOf(TEAL), leakHi = 25.0),
  Kind("两块拼底+中缝白块", "角上是两块不同的底，中缝不许被抹平",
    mark = setOf(WHITE), plate = setOf(SLATE, GREEN),
    fillLo = 3.0, topLo = 90.0, leakHi = 12.0),
  // ⚠ 这两格是为了把**变量分开**才加的：上面两格（拼底 / 长条）报"白块被腾空"时，
  //  我第一反应是"因为白块不贴边"。但同一批里 `居中图形+投影`（白块同样被底围着）却是 PASS
  //  —— 所以"贴不贴边"根本不是那把刀。这两格只改**位置**，别的东西一律不动。
  Kind("拼底白块+贴底", "与`两块拼底`只差白块位置：贴到底边（挨着空气）",
    mark = setOf(WHITE), plate = setOf(SLATE, GREEN),
    fillLo = 3.0, topLo = 90.0, leakHi = 12.0),
  Kind("长条贴底白块", "与`长条快捷方式`只差白块位置：贴到下边缘",
    wide = true, mark = setOf(WHITE), plate = setOf(TEAL), leakHi = 25.0),
  Kind("渐变底+白字", "渐变两端都得归进底，字还得实心；底是连续色 → 用 plateRest 量\"除字以外的一切实心\"",
    mark = setOf(WHITE), plateRest = true, fillLo = 3.0, topLo = 90.0, leakHi = 20.0),
  Kind("柔光板+实心图形", "α=60 的柔光不许被画成一块实心墨",
    mark = setOf(WHITE), plate = setOf(GLOW), fillLo = 3.0, fillHi = 30.0, topLo = 90.0, leakHi = 15.0),
  Kind("居中图形+投影", "图形不贴边、角上取不到底 → 该退到\"没有底\"，交整体剪影",
    mark = setOf(WHITE), plate = setOf(SHADOW), fillLo = 1.0, leakHi = 25.0),
  Kind("图形与底同色", "契约：图形可以合法地就是底色。洪泛按连通走，同色的肚子会被一起腾空",
    noAnswer = true, mustHaveInk = true, mark = setOf(NEARWHITE)),
  Kind("整张纯色", "钉着的性质：交出一张空掩码，不是 null", wantsEmpty = true, mark = setOf(px(255, 200, 60, 60))),
  Kind("整张透明", "一个像素都没有", wantsEmpty = true),
  Kind("1px 横杠", "取整边界：极窄图形（各边长上都可能整条丢）",
    noAnswer = true, mark = setOf(WHITE)),
)

/** 边长归一：形状按 `min(w,h)` 画，所以同一类图在四档边长上是同一个构图。 */
private fun build(kind: Kind, w: Int, h: Int): IntArray {
  val s = min(w, h)
  val b = IntArray(w * h)
  val c = s / 384.0
  fun r(v: Int) = (v * c).roundToInt()
  val cx = w / 2
  val cy = h / 2
  when (kind.name) {
    "圆盘+白字" -> paint(b, w) { x, y ->
      val dx = x - cx; val dy = y - cy
      if (dx * dx + dy * dy > (s * 0.46) * (s * 0.46)) return@paint NONE
      val bar = y in (cy - r(26))..(cy + r(26)) && x in (cx - r(120))..(cx + r(120))
      val dot = x in (cx - r(34))..(cx - r(6)) && y in (cy - r(150))..(cy - r(96))
      if (bar || dot) px(255, 255, 255, 255) else px(255, 30, 90, 200)
    }

    "圆角方盘+白字" -> paint(b, w) { x, y ->
      val half = s * 0.46; val rad = s * 0.22
      val dx = abs(x - cx); val dy = abs(y - cy)
      val inside = if (dx > half - rad && dy > half - rad)
        (dx - (half - rad)) * (dx - (half - rad)) + (dy - (half - rad)) * (dy - (half - rad)) <= rad * rad
      else dx <= half && dy <= half
      if (!inside) return@paint NONE
      if (y in (cy - r(30))..(cy + r(30)) && x in (cx - r(110))..(cx + r(110)))
        px(255, 255, 255, 255) else px(255, 240, 130, 20)
    }

    "满幅照片渐变" -> paint(b, w) { x, y ->
      var col = lerp(px(255, 12, 18, 40), px(255, 250, 210, 160), (x + y).toDouble() / (w + h))
      if (x in rng(w, 0.30, 0.55) && y in rng(h, 0.25, 0.62)) col = px(255, 210, 60, 70)
      if (x in rng(w, 0.55, 0.78) && y in rng(h, 0.45, 0.80)) col = px(255, 40, 160, 140)
      col
    }

    "空气+白剪影" -> paint(b, w) { x, y ->
      val dx = x - cx; val dy = y - cy
      if (dx * dx + dy * dy <= (s * 0.34) * (s * 0.34)) px(255, 255, 255, 255) else NONE
    }

    "空气+细线" -> paint(b, w) { x, y ->
      val t = maxOf(r(2), 1)
      val lo = cx - r(120); val hi = cx + r(120)
      val top = cy - r(120); val bot = cy + r(120)
      val box = x in lo..hi && y in top..bot
      val edge = x in lo..(lo + t - 1) || x in (hi - t + 1)..hi ||
        y in top..(top + t - 1) || y in (bot - t + 1)..bot
      val bar = y in (cy - t / 2)..(cy + t / 2) && x in lo..hi
      if (box && (edge || bar)) px(255, 250, 250, 250) else NONE
    }

    "白底黑字" -> paint(b, w) { x, y ->
      if (x in rng(w, 0.18, 0.82) && y in rng(h, 0.42, 0.58)) px(255, 24, 24, 28)
      else px(255, 252, 252, 252)
    }

    "长条快捷方式" -> paint(b, w) { x, y ->
      val hw = w * 0.44; val hh = h * 0.44; val rad = s * 0.20
      val dx = abs(x - cx); val dy = abs(y - cy)
      val inside = dx <= hw && dy <= hh &&
        (dx <= hw - rad || dy <= hh - rad ||
          (dx - (hw - rad)) * (dx - (hw - rad)) + (dy - (hh - rad)) * (dy - (hh - rad)) <= rad * rad)
      if (!inside) return@paint NONE
      if (x in (cx - r(40))..(cx + r(40)) && y in (cy - r(40))..(cy + r(40)))
        px(255, 255, 255, 255) else px(255, 20, 160, 120)
    }

    "两块拼底+中缝白块" -> paint(b, w) { x, y ->
      if (y !in rng(h, 0.12, 0.88)) return@paint NONE
      if (x in rng(w, 0.42, 0.58) && y in rng(h, 0.36, 0.64)) px(255, 255, 255, 255)
      else if (x < cx) px(255, 40, 60, 150) else px(255, 60, 150, 90)
    }

    // 同一张图，只把白块**推到底边**（挨着空气那一条边）—— 单变量：图形贴不贴边
    "拼底白块+贴底" -> paint(b, w) { x, y ->
      if (y !in rng(h, 0.12, 0.88)) return@paint NONE
      if (x in rng(w, 0.42, 0.58) && y in rng(h, 0.52, 0.88)) px(255, 255, 255, 255)
      else if (x < cx) px(255, 40, 60, 150) else px(255, 60, 150, 90)
    }

    // 长条盘里的白块**贴着下边缘**：与"居中"那一格只差位置
    "长条贴底白块" -> paint(b, w) { x, y ->
      val hw = w * 0.44; val hh = h * 0.44; val rad = s * 0.20
      val dx = abs(x - cx); val dy = abs(y - cy)
      val inside = dx <= hw && dy <= hh &&
        (dx <= hw - rad || dy <= hh - rad ||
          (dx - (hw - rad)) * (dx - (hw - rad)) + (dy - (hh - rad)) * (dy - (hh - rad)) <= rad * rad)
      if (!inside) return@paint NONE
      if (x in (cx - r(40))..(cx + r(40)) && y >= h / 2 && y <= cy + hh)
        px(255, 255, 255, 255) else px(255, 20, 160, 120)
    }

    "渐变底+白字" -> paint(b, w) { x, y ->
      if (x !in rng(w, 0.10, 0.90) || y !in rng(h, 0.10, 0.90)) return@paint NONE
      if (y in rng(h, 0.44, 0.56) && x in rng(w, 0.22, 0.78)) px(255, 255, 255, 255)
      else lerp(px(255, 20, 40, 160), px(255, 120, 210, 245), x.toDouble() / w)
    }

    "柔光板+实心图形" -> paint(b, w) { x, y ->
      val dx = x - cx; val dy = y - cy
      if (x in rng(w, 0.36, 0.64) && y in rng(h, 0.36, 0.64)) px(255, 255, 255, 255)
      else if (dx * dx + dy * dy <= (s * 0.44) * (s * 0.44)) px(60, 255, 255, 255)
      else NONE
    }

    "居中图形+投影" -> paint(b, w) { x, y ->
      val dx = x - cx; val dy = y - cy
      val d2 = dx * dx + dy * dy
      when {
        d2 <= (s * 0.22) * (s * 0.22) -> px(255, 255, 255, 255)
        d2 <= (s * 0.30) * (s * 0.30) -> px(70, 120, 120, 120)
        else -> NONE
      }
    }

    "图形与底同色" -> paint(b, w) { x, y ->
      val dx = x - cx; val dy = y - cy
      if (dx * dx + dy * dy > (s * 0.44) * (s * 0.44)) NONE else px(255, 250, 250, 250)
    }

    "整张纯色" -> paint(b, w) { _, _ -> px(255, 200, 60, 60) }

    "整张透明" -> paint(b, w) { _, _ -> NONE }

    "1px 横杠" -> paint(b, w) { x, y ->
      if (y in (cy - 1)..(cy + 1) && x in rng(w, 0.10, 0.90)) px(255, 255, 255, 255) else NONE
    }

    else -> error("没有这个类别：${kind.name}")
  }
  return b
}

/**
 * 一个像素是不是"字形内部"：四方向各探 `d` 格都还是同一个源色。
 *
 * 为什么要这一维：`顶%` 把**边界那一圈**和**真正的内部**混在一个数里。合成的用例是硬边
 * （没有抗锯齿），可模型对边界像素本来就会给低一档 —— 于是"顶=48%"既可能是"半个字是灰的"
 * （真缺陷），也可能是"字的外沿一圈淡"（口径问题）。判据台为这种混算付过一次学费
 * （`字均` 把盘和字混在一个分母上），这里别再犯第二次。
 */
private fun interior(src: IntArray, w: Int, colour: Int, d: Int): BooleanArray {
  val h = src.size / w
  val out = BooleanArray(src.size)
  for (y in 0 until h) for (x in 0 until w) {
    val i = y * w + x
    if (src[i] != colour) continue
    if (x - d >= 0 && x + d < w && y - d >= 0 && y + d < h &&
      src[i - d] == colour && src[i + d] == colour &&
      src[i - d * w] == colour && src[i + d * w] == colour
    ) out[i] = true
  }
  return out
}

private class Row(
  val kind: Kind,
  val width: Int,
  val src: IntArray,
  val out: MonochromeMask.Output,
  val ms: Double,
) {
  /** 交付的墨总量（α≥128）。0 = 一张空掩码；-1 = 连掩码都没有。 */
  val ink: Int = out.mask?.count { (it ushr 24) >= 128 } ?: -1

  /** 源色 ∈ mark 的那些格，交付到 ≥250 的比例 —— **"字有没有上全"**。 */
  val top: Double = run {
    val mask = out.mask ?: return@run -1.0
    var n = 0L; var t = 0L
    for (i in mask.indices) {
      val c = src[i]
      if (if (kind.mark.isEmpty()) (c ushr 24) != 255 else c !in kind.mark) continue
      n++
      if ((mask[i] ushr 24) >= 250) t++
    }
    if (n == 0L) -1.0 else t * 100.0 / n
  }

  /**
   * 同上，但**只数字形内部**（离边界 ≥4 格）。与 [top] 一起才说得清"淡"在哪：
   * 内点也低 = 整块被压下去（真缺陷）；只有 [top] 低 = 外沿一圈（口径）。
   */
  val topInner: Double = run {
    val mask = out.mask ?: return@run -1.0
    if (kind.mark.size != 1) return@run -1.0
    val inner = interior(src, width, kind.mark.first(), 4)
    var n = 0L; var t = 0L
    for (i in mask.indices) {
      if (!inner[i]) continue
      n++
      if ((mask[i] ushr 24) >= 250) t++
    }
    if (n == 0L) -1.0 else t * 100.0 / n
  }

  /** 源色 ∈ plate 的格子里，交付 ≥128 的比例 —— **"盘有没有被画上屏"**。
   *  [Kind.plateRest] 时"盘"是除 mark 之外的一切实心（渐变底没有唯一色），
   *  没有这一条，连续渐变的用例里 `plate = 那个颜色` 永远匹配不到东西，"盘漏"会恒为 -1（没量）。*/
  val leak: Double = run {
    val mask = out.mask ?: return@run -1.0
    if (kind.plate.isEmpty() && !kind.plateRest) return@run -1.0
    var n = 0L; var t = 0L
    for (i in mask.indices) {
      val c = src[i]
      val isPlate = if (kind.plateRest) (c ushr 24) == 255 && c !in kind.mark else c in kind.plate
      if (!isPlate) continue
      n++
      if ((mask[i] ushr 24) >= 128) t++
    }
    if (n == 0L) -1.0 else t * 100.0 / n
  }
}

/** 判定：`PASS` / `GAP `（这一类没有答案）/ `FAIL`。 */
private fun verdict(r: Row): Pair<String, String> {
  val k = r.kind
  val fill = r.out.boxFill * 100
  val bad = ArrayList<String>()
  if (k.wantsEmpty) {
    if (r.ink != 0) bad += "该交空掩码，实际墨=${r.ink}"
  } else {
    if (r.ink <= 0) bad += "交出空掩码"
    if (k.mustHaveInk && r.ink <= 0) bad += "契约上这一类必须有墨"
    if (k.fillLo >= 0 && fill < k.fillLo) bad += "框内%.1f<%.0f".format(fill, k.fillLo)
    if (k.fillHi >= 0 && fill > k.fillHi) bad += "框内%.1f>%.0f".format(fill, k.fillHi)
    if (k.topLo >= 0) {
      val t = if (r.topInner >= 0) r.topInner else r.top
      if (t in 0.0..<k.topLo) bad += "字内${"%.0f".format(t)}<${"%.0f".format(k.topLo)}"
    }
    if (k.leakHi >= 0 && r.leak > k.leakHi) bad += "盘漏%.0f>%.0f".format(r.leak, k.leakHi)
  }
  var note = if (k.hyps.isNotEmpty() && r.out.hyp !in k.hyps) "H=${r.out.hyp}∉${k.hyps}" else ""
  if (bad.isEmpty() && k.wantsEmpty) note += " 空掩码=对"
  return when {
    bad.isEmpty() -> "PASS" to note
    k.noAnswer -> "GAP " to (bad.joinToString(" ") + (if (note.isEmpty()) "" else "  $note"))
    else -> "FAIL" to (bad.joinToString(" ") + (if (note.isEmpty()) "" else "  $note"))
  }
}

fun main(args: Array<String>) {
  val edges = (args.getOrNull(0) ?: "96,192,384,768").split(",").map { it.trim().toInt() }
  // `--shot=目录`：把每一格的源图与**交付的墨**（越黑=越实）画出来。**数字之前先看图** ——
  // 判据台为这条付过学费（`cc`/`空洞` 都当过假的工作项来源）。
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let {
    java.io.File(it).apply { mkdirs() }
  }
  println("=== 兼容性回归台：${kinds.size} 个图标类别 × 边长 $edges ===")
  println("    （只测不改：GAP = 这一类模型没有答案，不是回归）")
  println()
  println("  类别                边长   H    框内%    顶%    内%    盘漏%   空洞   cc       墨px      ms  判定  越界/说明")
  var pass = 0; var fails = 0; var gaps = 0
  for (k in kinds) {
    val fills = ArrayList<Double>()
    for (e in edges) {
      val w = if (k.wide) (e * 2.5).roundToInt() else e
      val h = e
      val src = build(k, w, h)
      val t0 = System.nanoTime()
      val out = MonochromeMask.build(src, w, h)
      val ms = (System.nanoTime() - t0) / 1e6
      val r = Row(k, w, src, out, ms)
      val (tag, note) = verdict(r)
      if (shotDir != null) {
        val ink = IntArray(src.size) { i ->
          val a = (out.mask?.get(i) ?: 0) ushr 24
          px(255, 255 - a, 255 - a, 255 - a)   // 越黑 = 交付的墨越实
        }
        png(java.io.File(shotDir, "${k.name}-${e}-src.png"), w, h, src)
        png(java.io.File(shotDir, "${k.name}-${e}-ink.png"), w, h, ink)
      }
      when (tag) { "PASS" -> pass++; "GAP " -> gaps++; else -> fails++ }
      fills += (out.boxFill * 100).toDouble()
      println(
        "  %-18s %4d %3d %8.2f %7.1f %6.1f %7.1f %6.2f %4d %9d %7.1f  %-4s %s".format(
          k.name, e, out.hyp, out.boxFill * 100, r.top, r.topInner, r.leak, out.hole, out.components, r.ink, ms, tag, note
        )
      )
      // 非 PASS 的那几行把**模型自己那串分项**原样带出来 —— 被哪一道门拦下，只有它自己知道。
      // 这一串与真机 `Mono: diag#` 行同源（[MonochromeMask.Output.report]），字段名一致。
      if (tag != "PASS") println("      └ ${out.report}")
    }
    println("  ${"└ " + k.name.padEnd(22)}框内随边长漂移 %.2f  (%.1f … %.1f)   —— ${k.why}"
      .format((fills.maxOrNull() ?: 0.0) - (fills.minOrNull() ?: 0.0), fills.first(), fills.last()))
    println()
  }
  println("  合计  PASS=$pass  GAP=$gaps  FAIL=$fails")
}

private fun png(f: java.io.File, w: Int, h: Int, argb: IntArray) {
  val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  img.setRGB(0, 0, w, h, argb, 0, w)
  ImageIO.write(img, "png", f)
}
