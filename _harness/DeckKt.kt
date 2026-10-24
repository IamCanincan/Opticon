package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * **第二把尺子（2026-10-16 修订）：两路「最终几何」的离线对表。**
 *
 * ## 第一版为什么废掉（两条口径都不对齐，读数全是伪影）
 *  · **口径①**：染色侧跑 `MonochromeMask.build` 的**默认参数**（没喂 `under` / `plateIsSubject`），
 *    与产品那条路不同口径；
 *  · **口径②**：彩色侧量「**图标的墨**」（含盘 / 满幅内容），染色侧量「**掩码的墨**」——
 *    两者**不是同一个东西**，于是占盘差大半来自"盘也算墨"这件事，不是几何。
 *
 * ## 这一版的关键结论（把两处口径一次消掉）
 * 两路的最终几何**是同一个函数**：都 = 「参考内容盒的跨度」÷「各自窗口的跨度」。
 * 而**参考盒在两路里是同一个对象**（层画布上的那块内容），所以它**整个约掉** ——
 * 尺子根本不该去量"墨"，只该比**两个窗口换算到同一套口径之后的跨度**：
 *
 *     spanScreen(彩) = spanScreen(染)  ⟺  spanLayer(wc) = spanLayer(wm)
 *
 * 于是这把尺子**不跑模型**，只出窗口，速度与 `GeomPairKt` 同阶。
 *
 * ## 换算关系（1.5 倍那两条，别混）
 *  · **彩色**：量的是**整图标**（`AdaptiveIconDrawable.draw()` 的画法：层按 `1+2f` 铺开、裁四边）
 *    → 窗口跨度在「整图标」口径；折回层口径要 **÷1.5**。
 *  · **染色**：量的是**层本身**（`pixels`）→ 窗口跨度天然是层口径。
 *  · **满幅（窗口 = null）** 在两路里代表同一件事：内容**按原位交付**，屏幕上就是从
 *    「可见区」那张画布 1.5 倍铺出去 —— 占盘 = `1.5 × cbSpan`。
 *    所以「null」换算成跨度的等价值是 **2/3**（可见区占层画布的 2/3），不是 1.0。
 *
 * ## 三个分支，只有一支天然不一致
 *  · **非自适应**：两路量的是**同一张位图** → 窗口逐字相同 → 天然一致（真机逐包对表已证实）。
 *  · **自适应 + flatten**（`index.tsv` 的 `modelBgFile` 非 `-`，背景是张真 drawable）：
 *    染色量合成图、彩色量同一张铺开的整图标 → 是同一块图案的两套坐标 → 一致
 *    （真机 `me.ele`「彩色满幅 / 染色切1.49」正是这个：层内容占 0.667，×1.5 = 1.0 → 彩色判满幅）。
 *  · **自适应 + 只给前景层**（背景是纯色盘）→ **唯一不一致的那一支**：
 *    彩色的整图标 = **盘 + 前景**，盘铺满 → **满幅**；染色量的是**前景层本身**（只占 ~0.61）
 *    → 拿到一个 ~1.6× 的窗口 → 屏上把前景撑到**满盘**。占盘：彩 `1.5×0.61 = 0.92`／染 `1.0`。
 *
 * 只给前景层那一支（背景是纯色盘）的修法 2026-10-16 就已进生产：染色也**不搬**（窗口置空）
 * → 与彩色逐字一致。本表自 2026-10-25 起直接按**生产口径**量（以前"现状"列还按旧口径，
 * 会印出 83 张假差；"拟改"列才是生产 —— 那一列已删）。
 * ⚠ 这把尺子只判「几何」，不判形状/极性（那是 `MonochromeMask` 那批尺子的事）。
 *
 * 用法：`sh run.sh harness.DeckKtKt <目录...> [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  // index.tsv（0 起）：2=file / 5=cls / 18=modelBgFile（非 `-` = flatten）
  val cls = HashMap<String, String>()
  val flat = HashSet<String>()
  for (d in dirs) {
    val ix = File(d, "index.tsv"); if (!ix.isFile) continue
    for (l in ix.readLines().drop(1)) {
      val c = l.split("\t")
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
  }
  println("%-44s %-4s %-6s %-11s %-11s %-7s %-7s %s".format(
    "图标", "自适应", "flatten", "彩色窗(层跨)", "生产染色窗", "占盘彩", "占盘染", "差/备注"))
  var n = 0; var badNow = 0; var capHold = 0
  for (d in dirs) {
    val files = d.listFiles()?.filter {
      it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") && !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
    for (file in files) {
      val img = runCatching { ImageIO.read(file) }.getOrNull() ?: continue
      val px = raster(img, edge)
      val adaptive = (cls[file.name] ?: "").contains("Adaptive")
      val flatten = flat.contains(file.name)
      val fgOnly = adaptive && !flatten          // 只给前景层那一支（背景是纯色盘）
      n++
      val cb = inkBox(px, edge) ?: continue
      // 参考内容盒：**层口径**（层画布 0..1）。两路用的是同一个盒，所以它会约掉 —— 只当共同缩放。
      val cbSpan = max(cb[2] - cb[0], cb[3] - cb[1]).toFloat() / edge

      // ── 彩色侧：窗口换算成「层口径」的等值跨度 ──
      //  非自适应 → 量原位图，窗口跨度就是层口径、满幅 = 原位（跨度 1.0）；
      //  自适应 flatten → 量铺开的整图标，折回层要 ÷1.5、满幅同样 = 原位；
      //  自适应 只给前景层 → 盘铺满，整图标恒满幅。
      val wc = if (adaptive && !fgOnly) IconCropWindow.of(if (adaptive) expandLayer(px, edge) else px, edge, edge)
        else if (!adaptive) IconCropWindow.of(px, edge, edge) else null
      // 彩色窗口跨度的等价值（屏幕口径：null 在自适应下 = 1.5×cb，在非自适应下 = cb）
      val colorFrac = if (adaptive) {
        if (wc == null || fgOnly) cbSpan * 1.5f else cbSpan * 1.5f / winSpan(wc)
      } else {
        if (wc == null) cbSpan else cbSpan / winSpan(wc)
      }

      // ── 染色侧（**生产口径**）：量层本身，`takeIf zoom<=1.82`；只给前景层那一支恒不搬
      //   （`fgOnly` 的窗口置空 2026-10-16 就在生产里；本表 2026-10-25 才补上 —— 以前这里
      //   还按旧口径印 fgOnly 的差，于是出现 83 张假差，"拟改"列才是生产）──
      val wmNow = if (fgOnly) null else IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= 1.82f }
      val capHit = !adaptive && wmNow == null && IconCropWindow.of(px, edge, edge) != null
      if (capHit) capHold++
      val monoFracNow = if (adaptive) {
        if (wmNow == null) cbSpan * 1.5f else cbSpan / winSpan(wmNow)
      } else {
        if (wmNow == null) cbSpan else cbSpan / winSpan(wmNow)
      }

      val dNow = colorFrac - monoFracNow
      if (abs(dNow) > 0.04f) badNow++
      val note = when {
        capHit -> "染色压 1.82 退回（彩无上限 → 已知不一致）"
        fgOnly -> "只给前景层"
        adaptive && flatten -> "flatten"
        adaptive -> "自适应"
        else -> "非自适应"
      }
      println("%-44s %-4s %-6s %-11s %-11s %-7s %-7s %s".format(
        file.name.take(44), if (adaptive) "是" else "否", if (flatten) "是" else "否",
        when {
          !adaptive -> wc?.let { fmtPct(winSpan(it)) } ?: "满幅"
          fgOnly -> "满幅(盘)"
          else -> wc?.let { fmtPct(winSpan(it) / 1.5f) } ?: "满幅"
        },
        wmNow?.let { fmtPct(winSpan(it)) } ?: "满幅",
        "%.0f%%".format(colorFrac * 100), "%.0f%%".format(monoFracNow * 100),
        "%+.0f".format(dNow * 100), note))
    }
  }
  println("共 $n 张；**生产两路几何差 >4 个点的 $badNow 张**；染色压上限退回的 $capHold 张")
}

/** 窗口跨度（取最长边）：`max(r-l, b-t)`，单位 = 量它的那张画布 0..1。null 不在这里。 */
private fun winSpan(w: IconCropWindow.Window): Float = max(w.r - w.l, w.b - w.t)

private fun fmtPct(v: Float) = "%.0f%%".format(v * 100)

private fun raster(img: java.awt.image.BufferedImage, edge: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
    px[y * edge + x] = s0[sy * img.width + sx]
  }
  return px
}

/** 参考内容盒：墨的外接框（alpha ≥ 8，与判据同口径）；null = 一个像素都没有。 */
private fun inkBox(px: IntArray, edge: Int): IntArray? {
  var x0 = edge; var x1 = -1; var y0 = edge; var y1 = -1
  for (y in 0 until edge) for (x in 0 until edge) {
    if ((px[y * edge + x] ushr 24) < 8) continue
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
  }
  return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
}

/** 把「层」按 `AdaptiveIconDrawable.draw()` 的摆法铺成整图标（与 `GeomKt` / `GeomPairKt` 同源）。 */
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
