package com.iamcanincan.opticon.circle

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 「图案实际占的那块」= 裁圆的裁剪窗口，也是取色那一路要照着重排的那个窗口。
 *
 * ## 为什么单独一个文件、而且是**纯函数**
 * 这条判据有**两个消费方**：
 *  - [MaskedDrawable]：彩色那支，桌面上那个圆里的内容就是它裁出来的；
 *  - [MonochromeSynth]：染色那支，把已经算好的剪影按**同一个窗口**搬到位（只搬位置，不动浓度）。
 * 两边量的是**同一个对象** —— 整个图标光栅化到 [SCAN_EDGE]（自适应图标的子层按 1.5 倍铺出去、
 * 裁掉四边，这就是 `AdaptiveIconDrawable.draw()` 的画法）。量错对象（去量前景层本身）会把倍数
 * 整体放大一截，2026-10-14 第一版就是这么错的 —— 见 `MonochromeSynth.geometryWindow`。
 * 这里刻意不碰 `Bitmap` / `Drawable`：判据台（`_harness/`）编不动 Android 那两个类，
 * 只有纯函数才能被台子直接拿去量 —— 一条判据能不能改，取决于有没有台子能量它。
 *
 * ## 三条出口，语义完全不同（别当成"两种"）
 *  - `null` —— 满幅（外框占到画布 [BOX_FULL_RATIO] 以上）或任何"宁可不动"的情形。
 *    彩色那支据此**逐位不变**；染色那支据此**不重排**。
 *  - [plate] = false —— 裁得动的：按**不透明圆盘**内切，填满、圆外透明，不收边、不铺白底。
 *  - [plate] = true —— 裁不动的（按圆裁下去只剩 [KEEP_RATIO] 以下）：彩色那支不裁、缩进圆里
 *    并补白底；染色那支同样按这个窗口重排，但**那块"白"是桌面的主题盘，不该由我们画**。
 *
 * ⚠ **窗口就是窗口，圆不在这里。** 圆是彩色那支自己 `DST_OUT` 出来的；染色那支的形状由桌面的
 *   mask 决定。把圆烘进取色的输入，模型会把圆边当成一块底去判极性 —— 那是另一族 bug 的成因。
 */
internal object IconCropWindow {

  /**
   * 量测格子边长。**彩色那支要按它自己光栅化**（自适应图标的固有尺寸是 -1，必须先把小画布
   * 撑到这个尺寸，[of] 内部只会降采样、不会放大），所以在这里露出来 —— 别让第二个地方再抄一遍 128。
   */
  internal val scanEdge: Int get() = SCAN_EDGE

  /** 那条线换算成百分数，只给日志用（同理：别让调用方自己写 60）。 */
  internal val keepRatioPercent: Int get() = (KEEP_RATIO * 100f).roundToInt()

  /**
   * 裁剪窗口，坐标是**被量那张图的 0..1 比例**（与像素尺寸无关，所以换边长可比）。
   *
   * @param plate true = 这一档"裁不动"（不裁、缩进、彩色那支补白底）。
   * @param keepPct 按圆裁下去还剩几成图案（判据自己报出来，供日志与语料账用）。
   * @param zoom 这个窗口换算成的放大/缩小倍数（>1 = 放大填满，<1 = 缩进）。
   *
   * ⚠ 后面那几项**只进日志**，不参与判断。留着它们是因为这两行日志是装机后的唯一读数
   *   （`_harness` 的语料账与 README 记录的排查路径都按字段 grep）—— 抽成共用之后，
   *   格式一个字都不能变，否则"哪一档开火几张"那种账当场就点不成了。
   */
  class Window(
    val l: Float, val t: Float, val r: Float, val b: Float,
    val plate: Boolean, val keepPct: Int, val zoom: Float,
    val boxW: Int, val boxH: Int, val rBox: Float, val rCover: Float,
    val scanW: Int, val scanH: Int, val bodyOverFar: Float,
  ) {
    /**
     * 把窗口从**视口空间**换算到**图层空间**（108dp 画布，可见区只占中间那 `1/(1+2f)`）。
     *
     * 为什么需要：[of] 量的是"整个图标画进视口"那张图（彩色那支看到的就是那个），而单色掩码活在
     * 图层画布里 —— 桌面画图层时按 `1+2f` 倍铺出去再裁边（`AdaptiveIconDrawable.draw()`）。
     * 不换算就把视口空间的窗口套到图层空间的掩码上，两个 1.5 正好互相抵消，**重排等于没做**
     * （2026-10-14 第二版就是这么错的：元宝块头 53.5% → 52.7%）。
     *
     * [zoom] 原样保留 —— 它是彩色那支那个放大倍数，日志与语料账要报的是"桌面上动了多少"。
     *
     * 现在装机那两路都不需要它（彩色自己在视口空间里画，染色量与搬都在图层空间里），留着是给
     * 判据台复算用的：它从 `_model.png`（前景层）起算，而那**不是**设备上喂进去的那张 ——
     * 离线拿它量元宝 2.26×、真机同一条路 `几何=满幅`。**以真机的数为准。**
     */
    fun inLayer(f: Float): Window = Window(
      (l + f) / (1f + 2f * f), (t + f) / (1f + 2f * f),
      (r + f) / (1f + 2f * f), (b + f) / (1f + 2f * f),
      plate, keepPct, zoom, boxW, boxH, rBox, rCover, scanW, scanH, bodyOverFar,
    )
  }

  /**
   * 量一张 ARGB 图，返回裁剪窗口；`null` = 不动。
   *
   * [px] 是**行主序**的 `w*h` 个 ARGB 像素（`Bitmap.getPixels` 的原样，非预乘）。
   * 边长超过 [SCAN_EDGE] 的会先就近降采样到它 —— 这一趟的成本与像素数同阶，而两个消费方给的
   * 尺寸不一定相同（彩色那支自己就画到 128；染色那支拿到的是图层尺寸），降采样让两边跑在同一套格子上。
   */
  fun of(px: IntArray, w: Int, h: Int): Window? {
    if (w <= 0 || h <= 0 || px.size < w * h) return null
    val shrink = minOf(1f, SCAN_EDGE.toFloat() / maxOf(w, h))
    val sw = maxOf(1, (w * shrink).roundToInt())
    val sh = maxOf(1, (h * shrink).roundToInt())
    val s = if (sw == w && sh == h) px else downsample(px, w, h, sw, sh)
    return scan(s, sw, sh)
  }

  /** 就近降采样（不平均：这一趟要的是"哪里有没有像素"，平均会把抗锯齿毛边抬过门槛）。 */
  private fun downsample(px: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray {
    val out = IntArray(sw * sh)
    for (y in 0 until sh) {
      val sy = minOf(h - 1, (y * h.toFloat() / sh).roundToInt().coerceAtLeast(0))
      for (x in 0 until sw) {
        val sx = minOf(w - 1, (x * w.toFloat() / sw).roundToInt().coerceAtLeast(0))
        out[y * sw + x] = px[sy * w + sx]
      }
    }
    return out
  }

  /** 在已经 ≤[SCAN_EDGE] 的格子上跑三条出口。 */
  private fun scan(px: IntArray, sw: Int, sh: Int): Window? {
    // ① 图案在哪（外框）：一行/一列要有足够像素才算数，挡掉孤立噪点把框撑开
    var x0 = sw; var x1 = -1; var y0 = sh; var y1 = -1
    val rowMin = maxOf(BOX_ROW_MIN_PIXELS, sw / BOX_ROW_DIVISOR)
    val colMin = maxOf(BOX_ROW_MIN_PIXELS, sh / BOX_ROW_DIVISOR)
    for (y in 0 until sh) {
      var n = 0
      for (x in 0 until sw) if ((px[y * sw + x] ushr 24) >= BOX_ALPHA_MIN) n++
      if (n >= rowMin) { if (y < y0) y0 = y; y1 = y }
    }
    for (x in 0 until sw) {
      var n = 0
      for (y in 0 until sh) if ((px[y * sw + x] ushr 24) >= BOX_ALPHA_MIN) n++
      if (n >= colMin) { if (x < x0) x0 = x; x1 = x }
    }
    if (x1 < 0 || y1 < 0) return null
    val bw = x1 - x0 + 1
    val bh = y1 - y0 + 1

    // ② 圆能填到多大：以图案中心为心，圆盘整个落在不透明区里的最大半径
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    val rBox = minOf(bw, bh) / 2f
    val rCover = coveredRadius(px, sw, sh, cx, cy)
    val counts = keepCounts(px, sw, sh, cx, cy, rCover)
    val keep = counts[0]
    val keepTotal = counts[1]
    val keepPct = if (keepTotal > 0) (keep * 100f / keepTotal).roundToInt() else 0

    // ③ 裁下去会丢掉太多形状（宽幅、铃铛、十字、环…）→ 不裁：整幅缩进圆里。
    //    判据是"圆里还能留下几成图案"，基准是正方形按圆形裁的 π/4 = 78.5%。
    if (keepTotal > 0 && keep < keepTotal * KEEP_RATIO) {
      val ext = extents(px, sw, sh, cx, cy)
      val far = ext[0]
      val body = ext[1]
      if (far < 1f) return null
      // 三条约束取最保守（= 图案最小）的那个：主体进安全区 / 尖角不贴边 / 墨量对齐。
      // ⚠ 取 max 意味着墨量对齐只能往小里对齐，永远不许把外缘切掉（主人 2026-10-14：
      //   "图案就是得完整的，不能光看占比"）。所以"外缘先顶到圆边"的那几种不会被放大。
      val rBox = maxOf(far / TIP_FLOOR, body / SAFE_ZONE)
      val rInk = sqrt(keepTotal / (4f * PLATE_INK))
      val rWin = maxOf(rBox, rInk) * SHRINK_EXTRA
      return window(cx, cy, rWin, sw, sh, plate = true, keepPct = keepPct,
        boxW = bw, boxH = bh, rBox = rBox, rCover = rCover, bodyOverFar = body / far)
    }

    if (rCover < 1f) return null
    // ④ 裁得好的：按不透明圆盘内切、填满圆。不收边、不铺白底。
    //    满幅的那些直接不动（逐位不变）—— 它的内切圆本来就被自己填满，再放大那一两个百分点
    //    会让整屏图标都悄悄长大。
    if (bw >= sw * BOX_FULL_RATIO && bh >= sh * BOX_FULL_RATIO) return null
    return window(cx, cy, rCover, sw, sh, plate = false, keepPct = keepPct,
      boxW = bw, boxH = bh, rBox = rBox, rCover = rCover, bodyOverFar = 0f)
  }

  private fun window(
    cx: Int, cy: Int, r: Float, sw: Int, sh: Int, plate: Boolean, keepPct: Int,
    boxW: Int, boxH: Int, rBox: Float, rCover: Float, bodyOverFar: Float,
  ): Window {
    val l = cx + 0.5f - r
    val t = cy + 0.5f - r
    val right = cx + 0.5f + r
    val bottom = cy + 0.5f + r
    val zoom = maxOf(sw / (right - l), sh / (bottom - t))
    return Window(l / sw, t / sh, right / sw, bottom / sh, plate, keepPct, zoom,
      boxW, boxH, rBox, rCover, sw, sh, bodyOverFar)
  }

  /**
   * 交付剪影的**归一缩放**（只缩不放，返回 ≤1）：让墨的主体落在盘的 [SAFE_ZONE] 以内。
   *
   * 这条**不是**"跟裁圆走" —— 裁圆对绝大多数图标是空操作（它们的背景本来就是一块实心盘，
   * 整图标看就是满幅，没什么可调）。这里量的是**交付出来的墨自己**：28 张真机剪影实测占盘
   * 从 55% 一路铺到 150%（150% = 顶到图层四边、被盘切一圈），这条散布没有任何现成尺子在管。
   *
   * 用的尺子是现成的两条：[SAFE_ZONE]（内容本该落在可见区里 66dp 的安全区内）与 [TIP_TAIL]
   * （尖角可以探出去 —— 按"体量"而不是最远那个像素算，否则一个尖角就把整幅压小）。
   * **只缩不放**是主人那句「别的一堆图标又放大了」的落实：本来就在区里的一个像素都不动。
   *
   * ⚠ [plateSide] 必须是**盘的边长**（可见区），不是整张图层的画布边长 —— 分母用错会把一堆
   *   读成"100% 占满"，那张"大小不一"的账当场就是假的（本仓第一版错在这里）。
   * @param alpha 交付掩码的 alpha（0..255），行主序 `w*h`。
   */
  fun silhouetteScale(alpha: IntArray, w: Int, h: Int, plateSide: Int): Float {
    if (plateSide <= 0) return 1f
    val ext = extents(alpha, w, h, w / 2, h / 2, shift = 0)
    val body = ext[1]
    if (body < 1f) return 1f
    return minOf(1f, SAFE_ZONE * (plateSide / 2f) / body)
  }

  /** 以画布中心为锚把 [px] 整体缩到 [s]（最近邻，理由同 [reframe]）。[s] ≥1 时原样返回。 */
  fun scaleAbout(px: IntArray, w: Int, h: Int, s: Float): IntArray {
    if (s >= 0.999f) return px
    val out = IntArray(px.size)
    val cx = (w - 1) / 2f
    val cy = (h - 1) / 2f
    for (y in 0 until h) {
      val sy = ((y - cy) / s + cy).toInt()
      if (sy !in 0 until h) continue
      val row = sy * w
      for (x in 0 until w) {
        val sx = ((x - cx) / s + cx).toInt()
        if (sx in 0 until w) out[y * w + x] = px[row + sx]
      }
    }
    return out
  }

  /**
   * 把 [px] 按 [win] 重排成"窗口铺满目标矩形"的那张图（cover + 窗口中心对齐目标中心），
   * 与 [MaskedDrawable] 画彩色那支时用的**同一个仿射**。双线性采样，返回新数组。
   *
   * ⚠ **染色那一路的目标矩形就是整张图层画布（默认值），不要传可见区。** 单色那一层本身是
   *   108dp 的画布，桌面按 1.5× 铺进去、只画中间那 2/3 —— 铺满画布落到屏幕上正好就是铺满可见区。
   *   这里再缩一遍 = 缩两遍。非自适应那张该缩进可见区，由 [MonochromeSynth] 在掩码算完**之后**
   *   调 `intoVisibleArea` 做，不在这儿。
   *
   * ## 采样：搬**掩码**用双线性，搬**输入**必须用最近邻 —— 两者不是一回事
   * 这条几何现在作用在算完之后的交付掩码上（只改像素排布，不改任何判据数字），
   * 用双线性（只对 alpha 插值，颜色取最近邻）—— 主人 2026-10-14 逐张判"没问题"的就是这一版。
   *
   * ⚠ 但如果哪天把它挪到**模型之前**（让模型吃重排后的图），采样必须换成最近邻：双线性会造出
   *   源图里没有的中间浓度，而模型的底色场/分档吃的就是浓度分布。那条路量过 —— 双线性让
   *   `com.microsoft.xboxone.smartglass` 换支、`底占 0.45→0.00`（整张交成墨），源尺寸漂移面 81%→84%；
   *   最近邻则 `branch`/`platecut` 与基线逐字相同。见分支 `wip/tint-silhouette` 的提交说明。
   */
  fun reframe(
    px: IntArray, w: Int, h: Int, win: Window,
    tx: Int = 0, ty: Int = 0, tw: Int = w, th: Int = h,
  ): IntArray {
    val boxW = ((win.r - win.l) * w).coerceAtLeast(1f)
    val boxH = ((win.b - win.t) * h).coerceAtLeast(1f)
    val scale = maxOf(tw / boxW, th / boxH)
    val cxSrc = (win.l + win.r) / 2f * w
    val cySrc = (win.t + win.b) / 2f * h
    val cxDst = tx + tw / 2f
    val cyDst = ty + th / 2f
    val out = IntArray(px.size)
    for (y in 0 until h) {
      // 目标像素 → 源坐标（draw() 那一步的逆变换：先减目标心、除 scale、再加回窗口心）
      val sy = (y + 0.5f - cyDst) / scale + cySrc
      for (x in 0 until w) {
        val sx = (x + 0.5f - cxDst) / scale + cxSrc
        out[y * w + x] = sample(px, w, h, sx, sy)
      }
    }
    return out
  }

  /**
   * ⚠ **只对 alpha 双线性插值，颜色取最近的那个源像素。** 直接对打包的 ARGB 求平均会跨字节进位
   *   （`0x00FFFFFF` 与 `0xFFFFFFFF` 的平均是 `0x80EFFFFB` —— 蓝通道被算坏了）。
   */
  private fun sample(px: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
    val x0 = sx.toInt()
    val y0 = sy.toInt()
    fun alphaAt(x: Int, y: Int): Int =
      if (x < 0 || y < 0 || x >= w || y >= h) 0 else px[y * w + x] ushr 24
    val fx = sx - x0
    val fy = sy - y0
    val top = alphaAt(x0, y0) + ((alphaAt(x0 + 1, y0) - alphaAt(x0, y0)) * fx)
    val bot = alphaAt(x0, y0 + 1) + ((alphaAt(x0 + 1, y0 + 1) - alphaAt(x0, y0 + 1)) * fx)
    val a = (top + (bot - top) * fy).roundToInt().coerceIn(0, 255)
    val nearest = if (x0 < 0 || y0 < 0 || x0 >= w || y0 >= h) 0 else px[y0 * w + x0]
    return (a shl 24) or (nearest and 0x00FFFFFF)
  }

  /**
   * [0] = 半径 r 的圆盘里还剩多少"有像素"的格；[1] = 图案总共有多少格。
   * 两者之比就是"按这个圆裁下去，图案还剩几成" —— 判"裁不裁得动"用的就是它，
   * 比"半径比"诚实：细胳膊的十字半径不小，但裁完只剩中间那一根。
   */
  private fun keepCounts(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Float): IntArray {
    var keep = 0
    var total = 0
    val rr = r * r
    for (y in 0 until h) for (x in 0 until w) {
      if ((px[y * w + x] ushr 24) < BOX_ALPHA_MIN) continue
      total++
      val dx = x - cx
      val dy = y - cy
      if (dx * dx + dy * dy <= rr) keep++
    }
    return intArrayOf(keep, total)
  }

  /**
   * 图案的"体量"有多远：[0] = 最远像素的半径；[1] = 外侧只占 ≤[TIP_TAIL] 的那些尖角之外的半径。
   *
   * 为什么要两个：只取最远像素的话，一个尖角就把整幅图往里推（三角/星形被压小）。
   * 用**像素**而不是外框：三角的外框角是空的，按外框算会白缩一圈。
   * 一趟扫完（半径直方图），与像素数同阶。
   *
   * [shift] = 24 时吃的是 ARGB 数组；= 0 时吃的是"alpha 单独一行一个数"的数组
   * （交付掩码归一那条尺子 [silhouetteScale] 用的就是后者）。**只这一份实现**，两处共用。
   */
  private fun extents(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, shift: Int = 24): FloatArray {
    var far = 0f
    val hist = IntArray(minOf(w + h, 4096))
    var total = 0
    for (y in 0 until h) for (x in 0 until w) {
      if ((px[y * w + x] ushr shift) < BOX_ALPHA_MIN) continue
      val dx = (x - cx).toFloat()
      val dy = (y - cy).toFloat()
      val r = sqrt(dx * dx + dy * dy)
      if (r > far) far = r
      hist[minOf(hist.size - 1, r.toInt())]++
      total++
    }
    var tail = 0
    var body = far
    for (r in hist.indices.reversed()) {
      tail += hist[r]
      if (tail > total * TIP_TAIL) { body = r + 1f; break }
    }
    return floatArrayOf(far, body)
  }

  /**
   * 以 (cx,cy) 为心，圆盘能撑到多大仍然**整个落在不透明区里**（画布外算不透明区之外）。
   *
   * 这就是"内切圆"的半径：拿它当裁剪窗口，放大后圆内不会剩透明格 —— 主人要的"圆要完整，
   * 中间不要有空隙"就是它。距离用两遍 chamfer（近似欧氏，够用），复杂度与像素数同阶。
   *
   * ⚠ 返回 0 说明**中心自己就是空的**（环/甜圈那类镂空图案）—— 调用方会退回按外框内切。
   */
  private fun coveredRadius(px: IntArray, w: Int, h: Int, cx: Int, cy: Int): Float {
    val inf = (w + h).toFloat()
    val d = FloatArray(px.size)
    for (i in d.indices) d[i] = if ((px[i] ushr 24) >= BOX_COVER_ALPHA) inf else 0f
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      if (d[i] == 0f) continue
      var v = d[i]
      if (x > 0) v = minOf(v, d[i - 1] + 1f)
      if (y > 0) v = minOf(v, d[i - w] + 1f)
      if (x > 0 && y > 0) v = minOf(v, d[i - w - 1] + 1.414f)
      if (x < w - 1 && y > 0) v = minOf(v, d[i - w + 1] + 1.414f)
      d[i] = v
    }
    for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
      val i = y * w + x
      if (d[i] == 0f) continue
      var v = d[i]
      if (x < w - 1) v = minOf(v, d[i + 1] + 1f)
      if (y < h - 1) v = minOf(v, d[i + w] + 1f)
      if (x < w - 1 && y < h - 1) v = minOf(v, d[i + w + 1] + 1.414f)
      if (x > 0 && y < h - 1) v = minOf(v, d[i + w - 1] + 1.414f)
      d[i] = v
    }
    // 画布外按"不透明区之外"算：离边框的距离就是半径上限
    val edge = minOf(cx, cy, w - 1 - cx, h - 1 - cy).toFloat()
    return minOf(d[cy * w + cx], edge) - 1f
  }
}

/**
 * 量图案窗口时把图降采样到的边长：**两条路都用这一个数**。
 *  · 彩色那支本来就光栅化到它（`AdaptiveIconDrawable` 的固有尺寸是 -1，量到固定边长才能与
 *    "这次画多大"解耦 —— 真机同一张图标会以 79/95/96/192/283 几种尺寸被画，尺寸一飘，
 *    "裁完剩几成"这种比例数就在边界上瞎切）；
 *  · 染色那支吃的是 384 的一层，[IconCropWindow.of] 自己降下来。
 *
 * ⚠ 从 192/256 降到 128（2026-10-14）：这一趟的成本与像素数同阶，而它对每个图标都是
 *   净多出来的（真机 `量一张` 中位 2.64ms → 0.60ms）。128 的坐标精度是 1/128 ≈ 0.8%，
 *   对"留 4%~8% 的边"这件事没有可感影响。
 */
private const val SCAN_EDGE = 128

/** 算"有像素"的 alpha 门槛：把抗锯齿毛边（真机上量到 1..7）排除在外。 */
private const val BOX_ALPHA_MIN = 8

/** 一行/一列至少要有这么多个像素才算数 —— 挡掉孤立噪点把外框撑到整块画布。 */
private const val BOX_ROW_MIN_PIXELS = 2

/** 上面那道门槛也随分辨率走（本仓吃过绝对格数门槛在低分辨率上不开火的亏）。 */
private const val BOX_ROW_DIVISOR = 64

/** 外框占到画布这个比例以上就当成满幅：不动缩放，与改前逐位一致。 */
private const val BOX_FULL_RATIO = 0.99f

/**
 * 判"圆内还有没有空隙"的 alpha 门槛：**过半不透明**才算填上了。
 * 低于它的像素在屏幕上就是半透明的 —— 那正是"圆不完整"看上去的样子。
 */
private const val BOX_COVER_ALPHA = 128

/**
 * **裁下去会丢掉几成图案**才判定"不适合裁"。
 *
 * 凭什么定这个数：正方形图案按圆形裁（内切）留下 π/4 = **78.5%** —— 那就是所有正常图标
 * 的样子，也是"观感协调"的基准。留不下 [KEEP_RATIO] 的，说明圆会咬掉一大块形状
 * （宽幅 banner 只留 ~20%、铃铛 ~57%、十字/环更少），那就别裁：缩一缩装进圆里。
 * 真机空档：这一档最高 56%，下一档最低 72%（收版那档样本 78%）—— 线摆在中间有余量。
 */
private const val KEEP_RATIO = 0.6f

/**
 * "裁不动"那类留多少边 —— 从**平台自己的安全区**推出来的，不是拍脑袋：
 * 自适应图标的内容本应落在可见区里那个 **66dp 的安全区圆**内，可见区是 72dp，
 * 所以安全区直径 = 可见区的 **66/72 = 91.7%**，即半径要留 8.3%。
 */
private const val SAFE_ZONE = 66f / 72f

/**
 * 绑的是"图案的体量"而不是"最远那一个像素"：外侧占比 ≤ 这个数的像素（尖角、细刺）可以探出安全区。
 * 为什么：只按最远像素算的话，**一个尖角就把整幅图往里推** —— 三角被压成小一号的三角，
 * 而星形/三角这类本来就该"主体收进来、尖角对外透气"（主人："每个图标之间也可能不太一样"）。
 */
private const val TIP_TAIL = 0.02f

/**
 * **不贴边**：图案最远的那一像素只到半径的这个比例。只用于"裁不动"那类 ——
 * 防止它们的尖角顶到圆上（"最好不要贴着图标的边缘，挺难看的"）。
 */
private const val TIP_FLOOR = 0.93f

/**
 * "裁不动"那类的**总缩量**（在 [TIP_FLOOR] / [SAFE_ZONE] / [PLATE_INK] 之上再乘一刀）。
 * 口径来自真机量测（2026-10-14）：乘 1.03 时图案占白圆直径 **81%**，主人仍判"大了"；
 * 这一档乘 1.19 → 约 **70%**（主人判"这个尺寸差不多"）。每 +0.1 大约再小 5 个点。
 * ⚠ 只管这一类；裁得好的图标不受它影响。
 */
private const val SHRINK_EXTRA = 1.19f

/**
 * "裁不动"那类的**墨量目标**：把图案的不透明面积对齐到面板的这个比例。
 *
 * 为什么除了外缘还要管墨量（主人："不规则图标大小不一，也应该有大概的判断再决定缩小多少"）：
 * 只把外缘套在同一个圈上，**块头**差得很远，挤在一起看就是大小不一。
 *
 * ⚠ 它**只能把胖的收下来，不能把瘦的放大**（窗口取三条尺子的最大值）—— 所以那句
 *   "对齐之后它们才是一个视觉尺寸"对宽幅 / 星形那一类不成立。
 * ⚠ **真机上这条尺子从没绑过**：装机那几张开火的图标，外缘那条都比它严（16:45 那一版
 *   缩量与上一版逐张相同）。而 `_circab.cjs` 现在跑出的墨量（20/22/23/50）与当初记在
 *   注释里的 18/29/38 **不是同一次量测**，两组别混用。见 docs/KNOWN-LIMITS.md。
 */
private const val PLATE_INK = 0.5f
