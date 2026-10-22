package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **两本账对表（2026-10-21）**：`PLATE_SUBJECT_MIN = 50` 这条线在**彩色那本账**上标定
 * （wenku8 真机 `裁了只剩 55%`），却长期由**判据那一本**去比（量喂进模型那张）——
 * 同一张图两本账装机现量差 **3 个点**（wenku8 55→52、contacts 彩色 50 / 判据 49），
 * **只差 1 个点就两边各给一个答案**。生产已改成"极性读彩色那一本"
 * （`MonochromeSynth.colourWindow`），这台子就是那把尺。
 *
 * ## 两本账各自的输入怎么离线复现
 *  · **判据（几何）那一本** = `IconCropWindow.measure(喂进模型那一张)` —— 语料主 PNG 就是那一张
 *    （`index.tsv` 的 `modelFile`），过 `GEO_ZOOM_CAP`。
 *  · **彩色（极性）那一本** = 量 `icon.draw()` 落到视口那一张 = 图层按 `1+2f` 铺出去、裁四边，
 *    离线用 [expandLayer] 复现（与 [DeckKt] 同源，f=0.25），**不过上限**（彩色自己走 `of`）。
 *    自适应两支的**背景**不同：`under` 在（flatten）→ 合成图整张铺开；背景是纯色盘（只给前景层）→
 *    盘把整张画布**铺满**，那一张每一格都不透明（[plateFill]）→ 按构造恒判满幅，所以那一支**不画不量**。
 *    ⚠ 真机上彩色那支是**原生画到 128**（`IconCropWindow.scanSize`），离线只有 384 那张可以降采样复现 ——
 *    残余的**采样漂移**（1~3 个点）这台子量不到，装机由 `_twobook.cjs` 逐包核（两行不等就非零退出）。
 *
 * ## 看什么
 *  · `极性翻` = 两本账的 `polaritySubject` 不一样（这就是这一刀的波及面）；
 *  · `档翻` = `plate` 与否不一样（内切 ↔ 缩进圆里）；`差` = 两本账 `keepPct` 差几个点（都在线的哪一侧）。
 *  · `--acct=mono` 把"钉的开火集"换回**老口径**算一遍 —— 这是这台子的**自证**：
 *   两口径印出来的名单一样，就说明这台子的彩色重建没在做任何事（空跑）。
 *
 * 用法：`sh run.sh harness.TwoAcctKtKt <语料目录...> [--edge=384] [--all] [--acct=colour|mono]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val all = args.contains("--all")
  val acct = args.firstOrNull { it.startsWith("--acct=") }?.substringAfter("=") ?: "colour"
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  require(dirs.isNotEmpty() && dirs.all { it.isDirectory }) { "语料目录不存在：${dirs.joinToString()}" }

  println("%-40s %-10s %-7s %-7s %-8s %-8s %-5s %-5s %-4s".format(
    "图标", "支", "判据剩%", "彩色剩%", "判据档", "彩色档", "老极性", "新极性", "差"))
  var n = 0
  val b = HashMap<String, IntArray>()   // 每支：[张数, 差≥3, 差≥5, 极性翻, 档翻, 最大差]
  var flip = 0; var entryFlip = 0; var colourNull = 0; var bothWin = 0; var fgNear = 0; var gridSplit = 0
  var nativeN = 0; var nativeSplit = 0; var nativeSkip = 0
  var inviolations = 0
  val firedNew = ArrayList<String>(); val firedOld = ArrayList<String>(); val flips = ArrayList<String>()
  for (d in dirs) {
    val underByFile = HashMap<String, Boolean>()
    File(d, "index.tsv").let { ix ->
      if (ix.isFile) ix.readLines().drop(1).forEach { l ->
        val c = l.split("\t"); if (c.size > 18) underByFile[c[2]] = c[18] != "-"
      }
    }
    for (c in cases(d, edge, inset = false, fgAll = false)) {
      val hasUnder = underByFile[c.file.name] == true || c.under != null
      val fgOnly = c.isFg && !hasUnder
      val branch = when {
        !c.isFg -> "非自适应"
        fgOnly -> "只给前景层"
        else -> "flatten"
      }
      val raw = b.getOrPut(branch) { IntArray(6) }
      // 判据（几何）那一本：过上限；彩色（极性）那一本：不过上限
      val wm = IconCropWindow.measure(c.src, edge)
      val rawM = IconCropWindow.of(c.src, edge, edge)
      val colourPx = when {
        !c.isFg -> c.src
        fgOnly -> null                  // 盘铺满整张 → 按构造恒满幅，不画也不量
        else -> expandLayer(c.src, edge)
      }
      // **过生产同一个 [IconCropWindow.scanSize]**（彩色与染色共用那一个格子算式）。
      // ⚠ 这一位**只对 `canonical` 那一个参数敏感**（`isFg=false` 的图标两次调用同一个式子 ⇒ 恒不分岔）：
      //   2026-10-22 加进 45 张 `bitmap=` 快捷方式（全在"非自适应"那一支）之后 `格子分岔` 一格没动，
      //   不是它们"不会分岔"，而是**这一位压根不看那一支**。那一支对不对齐，看 `原生分岔=` 那一位。
      val (sw, sh) = IconCropWindow.scanSize(edge, edge, c.isFg)
      val (swAlt, shAlt) = IconCropWindow.scanSize(edge, edge, false)
      if (sw != swAlt || sh != shAlt) gridSplit++
      // 🔴 **非自适应那一支按生产的几何算彩色那本**（2026-10-22 补）：生产那边是
      //   `MaskedDrawable.measureWindow` 量 `icon.draw()` 落到视口那一张，格子 = `scanSize(intrinsic, false)` ——
      //   intrinsic ≤ `SCAN_EDGE` 时**根本不缩**，拿的就是那张小原图。台子以前把源先 resample 到 `edge`
      //   再算，于是 `shortcut_bit` 那种 110² 的小源被"长到 384 再量"，两本对表的几何与装机不一致。
      //   读不到/非方形 ⇒ 退回旧的近似，并**数出来**（`原生退回=`），不许静默降级。
      val nat = if (!c.isFg) nativeArgb(c.file) else null
      val wc: IconCropWindow.Window? = if (nat != null) {
        val (nw, nh) = IconCropWindow.scanSize(nat.second, nat.third, false)
        nativeN++
        if (nw != sw || nh != sh) nativeSplit++
        IconCropWindow.of(resize(nat.first, nat.second, nw, nh), nw, nh)
      } else {
        if (!c.isFg) nativeSkip++
        colourPx?.let { IconCropWindow.of(resize(it, edge, sw, sh), sw, sh) }
      }
      n++; raw[0]++
      if (wc == null) colourNull++
      if (wm != null && wc != null) bothWin++
      val dm = wm?.keepPct ?: -1
      val dc = wc?.keepPct ?: -1
      val gap = if (dm >= 0 && dc >= 0) abs(dm - dc) else 0
      raw[5] = max(raw[5], gap)
      if (gap >= 3) raw[1]++
      if (gap >= 5) raw[2]++
      if (rawM != null && wc != null && rawM.plate != wc.plate) { entryFlip++; raw[4]++ }
      // **两本账都从生产那一个取数口过**（`IconCropWindow.polarityOf` 读第一参数）：
      //  `new` = 装机现在的顺序（彩色在前）；`old` = 把两个参数换过来 = **把这一刀退回老口径**。
      //  这么写台子才真的有牙：`twobook_ctrl/` 那一张两本账判定本来就不同，
      //  谁把 `polarityOf` 的第二参数读进式子（或把调用点换顺序），`开火集` 与 `翻名单` 当场动。
      val old = IconCropWindow.polarityOf(wm, wc)
      val new = IconCropWindow.polarityOf(wc, wm)
      // 🔴 **语义不变量（不靠 pin，靠构造）**：`只给前景层` 那一支彩色那本账**按构造恒 null**
      //   （纯色盘铺满整张画布），所以那一张的 `new` 必须是 false。它要是 true，只有两种可能：
      //   ① 取数口读的不是第一参数（生产被改坏）；② **`out/` 是变异后台留下的旧字节码**。
      //   ②今天真发生过：`_semanticmut --case=19` 只还原源码不重编，我随后重钉基线量的就是变异后的类，
      //   钉出来的方向整个反过来 —— 下一次回归台当场判红（闸是对的，基线是错的）。
      //   所以这条**先于**任何基线比较：它红了就不许拿这一轮的数去钉任何东西。
      if (branch == "只给前景层" && new) {
        inviolations++
        println("!! 不变量破了：${stem(c.file)} 在「只给前景层」这一支拿到了彩色口径的极性" +
          " —— 取数口读错参数，或 `out/` 是变异后的旧类（先 sh build.sh 再跑）")
      }
      // **这台子自己看得见多少**：只有前景层那一支是两本账**结构性**分岔的地方（彩色那本恒满幅），
      // 所以报"离开口径还差几张"—— 老口径在那一支上 plate&&!spiky 且 `keepPct ≥ 50−10` 的张数。
      // 没有这一位，"极性翻 0 张"就分不清是**不会翻**还是**语料里根本没有候选**（本仓那一族：空跑当通过）。
      if (branch == "只给前景层" && wm != null && wm.plate && !wm.spiky && wm.keepPct >= 40) fgNear++
      val who = stem(c.file)
      if (old) firedOld += who
      if (new) firedNew += who
      if (old != new) {
        flip++; raw[3]++
        flips += "$who:$branch" + (if (old) " 老开→新关" else " 老关→新开")
      }
      if (all || old != new || gap >= 3 || (rawM != null && wc != null && rawM.plate != wc.plate)) {
        println("%-40s %-10s %-7s %-7s %-8s %-8s %-5s %-5s %-4s".format(
          who.take(40), branch,
          if (dm < 0) kind(rawM, wm) else "$dm%", if (dc < 0) "满幅" else "$dc%",
          kind(rawM, wm), kind(wc, wc),
          if (old) "开" else "关", if (new) "开" else "关", if (gap == 0) "" else "$gap"))
      }
    }
  }
  println("%-10s %-4s %-6s %-6s %-7s %-5s %s".format("支", "张", "差≥3", "差≥5", "极性翻", "档翻", "最大差"))
  for ((k, v) in b.toList().sortedByDescending { it.second[0] }) {
    println("%-10s %-4s %-6s %-6s %-7s %-5s %d".format(k, v[0], v[1], v[2], v[3], v[4], v[5]))
  }
  val fired = if (acct == "mono") firedOld else firedNew
  println("扫过 $n 张｜极性翻 $flip 张｜彩色判满幅 $colourNull 张（两本都有窗口 $bothWin 张）｜档翻 $entryFlip 张｜" +
    "只给前景层那支里 老口径 plate&&!spiky 且 ≥40% 的 $fgNear 张（= 这台子离能翻还差几张）｜" +
    "格子分岔 $gridSplit 张（canonical 与不 canonical 给出不同格子的张数，**只看 `isFg` 那一支**）｜" +
    "非自适应那本按**生产几何**算：原生格子 $nativeN 张（与判据那本不同格子 $nativeSplit 张）" +
    "、退回 edge 近似 $nativeSkip 张（非方形或读不到）")
  println("钉的是 **$acct 口径的开火集**（老=$firedOld 张 / 新=$firedNew 张）：${firedNew.sorted()}")
  if (flips.isNotEmpty()) println("翻的名单：${flips.joinToString(" / ")}")
  println("2A n=$n acct=$acct flip=$flip colourNull=$colourNull bothWin=$bothWin entryFlip=$entryFlip fgNear=$fgNear 格子分岔=$gridSplit " +
    "原生=$nativeN 原生分岔=$nativeSplit 原生退回=$nativeSkip 不变量=$inviolations " +
    "开火集=[${fired.sorted().joinToString(",")}] 老开火集=[${firedOld.sorted().joinToString(",")}] " +
    "分支=" + b.toList().sortedBy { it.first }.joinToString(",") { (k, v) ->
      "$k:${v[0]}张/差≥3=${v[1]}/差≥5=${v[2]}/极性翻=${v[3]}/档翻=${v[4]}/最大差=${v[5]}" } +
    " 翻名单=[${flips.joinToString(",")}]")
  // 自证：这台子若一张都没开火，它就没在比任何东西（同 PlateSubKt 那条纪律）。
  if (inviolations > 0) {
    println("!! 语义不变量破了 $inviolations 处 —— 这一轮的数**不许拿去钉基线**（先 `sh build.sh` 重编再跑）")
    kotlin.system.exitProcess(1)
  }
  if (fired.isEmpty()) { println("!! $acct 口径一张都没开火 —— 这台子什么都没比"); kotlin.system.exitProcess(1) }
}

/** 分档词：`measure` 返回 null 有两种意思（真满幅 / 超上限被退回），日志与表里必须分开。 */
private fun kind(raw: IconCropWindow.Window?, win: IconCropWindow.Window?): String = when {
  win != null -> if (win.plate) "缩(plate)" else "切(内切)"
  raw == null -> "满幅"
  else -> "退上限"
}

/** 去掉尾上那 12 位内容签名 —— 钉名单要的是同一张图的稳定身份（同 [PlateSubKt]）。 */
private fun stem(f: File) = f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "")

/**
 * 读文件**原生尺寸**的 ARGB（非自适应那本要用它 = 生产那边的 intrinsic）。
 * 非方形 / 读不到 ⇒ 回 null，调用方退回 edge 近似并把这一张计入 `原生退回`（不许静默降级）。
 */
private fun nativeArgb(f: File): Triple<IntArray, Int, Int>? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val iw = img.width; val ih = img.height
  if (iw <= 0 || iw != ih) return null
  val px = IntArray(iw * ih)
  img.getRGB(0, 0, iw, ih, px, 0, iw)
  return Triple(px, iw, ih)
}

/** 就近降采样到 `sw×sh`（与 `IconCropWindow.of` 内部那一趟同一个式子，先降再交给判据）。 */private fun resize(px: IntArray, edge: Int, sw: Int, sh: Int): IntArray {
  if (sw == edge && sh == edge) return px
  val out = IntArray(sw * sh)
  for (y in 0 until sh) {
    val sy = minOf(edge - 1, (y * edge.toFloat() / sh).roundToInt().coerceAtLeast(0))
    for (x in 0 until sw) {
      val sx = minOf(edge - 1, (x * edge.toFloat() / sw).roundToInt().coerceAtLeast(0))
      out[y * sw + x] = px[sy * edge + sx]
    }
  }
  return out
}

/** 把「层」按 `AdaptiveIconDrawable.draw()` 的摆法铺成整图标（与 [DeckKt] / [GeomKt] 同源）。 */
private fun expandLayer(px: IntArray, edge: Int): IntArray {
  val f = 0.25f; val scale = 1f + 2f * f
  val out = IntArray(px.size)
  for (y in 0 until edge) for (x in 0 until edge) {
    val u = (x.toFloat() / edge + f) / scale
    val v = (y.toFloat() / edge + f) / scale
    if (u > 1f || v > 1f) continue
    val sx = (u * (edge - 1)).toInt().coerceIn(0, edge - 1)
    val sy = (v * (edge - 1)).toInt().coerceIn(0, edge - 1)
    out[y * edge + x] = px[sy * edge + sx]
  }
  return out
}

/** 纯色盘那一支不在这台子上复现：盘把整张画布铺满 → 每一格都不透明 → 按构造恒判满幅（`colourPx = null`）。 */
