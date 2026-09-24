package com.iamcanincan.opticon.circle

import android.graphics.Color
import android.graphics.Rect
import java.util.Locale
import kotlin.math.sqrt

/**
 * 强制取色的掩码模型：一张图标 → 一条 alpha 斜坡。
 *
 * 纯数组进、纯数组出 —— 不碰 Drawable、不碰 Bitmap，所以判据台（`_harness/`）跑的是**这一份**
 * 代码而不是它的副本；位图与 drawable 的事全在 [MonochromeSynth]。
 *
 * ## 为什么整条链只剩两支
 * 上一版这里四支在竞价（alpha 轮廓 / 细节 / 三层刻底 / 亮度 Otsu），每支一套阈值，谁赢由一串
 * `beatsAlpha` / `plateLike` / `boxFill` 比较决定。病根不是阈值没调好，是**四支量的本来就不是
 * 同一件事**：alpha 支照抄轮廓（底板原样留下）、刻底支按颜色删像素（删到哪一刀是一条全局线，
 * 柔光字形与渐变底两头都栽过）、Otsu 支只看亮度（底与字形一明一暗才切得开）。README 记的那
 * 七次裁定，六次是在决定这条全局线画在哪。
 *
 * 这一版只问一个问题：**这个像素，与它*周围*那块底，看起来差多少？**
 *
 * 1. **底 = 局部底色场**（[FIELD_GRID] 的格子，每格取该格底板像素的中位色，空格由邻格松弛补齐，
 *    取值时双线性插值）。不是全图一个中位色 —— 一道渐变底在每个格子里都有自己那一份，于是
 *   「渐变」不需要并层、不需要档内插值补偿，**它自己就是平的**。
 * 2. **差 = CIELAB 距离**，整数查表实现（见「感知色度学」）。不用 RGB 曼哈顿距离（同距离不同
 *    色相看起来差很远），也不只用亮度（同亮度的红与青量不出来）。
 * 3. **alpha = 差 ÷ 这张图自己的对比范围**（[INK_REF_PCT] 分位），再过一条单调曲线 [CURVE]。
 *
 * 于是「一整块色板」在这条路上**不是被闸门拦掉的，是产生不出来**：底板与自己比必然差 0，归一
 * 之后必然透明。要涂满整张，得先让整张都不是它自己的底 —— 定义上的矛盾。
 *
 * ## 唯一保留的另一支：透明底
 * 图标的底是「没有东西」时，色场没有可比的对象，差就退成覆盖率本身 = 图标自带的 alpha。走不走
 * 这一支只看两个事实：**内容外接框里有多少是不透明的**（[PLATE_MIN_BOX]），以及 alpha 轮廓自己
 * 有没有内部结构（[ALPHA_SOLID_TOP] + [PLATE_HOLE]）。
 * ⚠ 判据一律**先裁到内容外接框再量**：自适应前景层四周那圈安全区留白不是背景，带着它量会把满幅
 * 不透明的一块底误判成「透明底 + 图形」，压出来就是那块底的轮廓 = 一整块色。这是真机「淘宝变成
 * 大色块」的病根，口径不能动。
 *
 * ## 交出去的就是最终答案
 * 模型之外不再做任何浓度决定 —— 旧版 `MonochromeSynth.toAlphaMask` 里那套「内部一律钳实心 +
 * 边缘保底」一并删掉。判据算出来的斜坡就是要上屏的东西；两头各调一次必然对不上，上一版「日志
 * 说的和屏幕上不是一张图」就是这么来的。
 */
internal object MonochromeMask {

  // ───────────────────────────── 口径 ─────────────────────────────

  /** 内容外接框：alpha 到这一点就算「这里画了东西」。取 8 不取 128 —— 柔光与投影也是内容。 */
  private const val A_BOX = 8

  /** 「这块像素算不透明」，也是形状统计的口径 —— 与全仓 `MIN_SHAPE_ALPHA` 同一条线。 */
  private const val A_SOLID = 128

  /** 内容框内不透明占比到它，就认定「这张图有一整块底」，alpha 轮廓不再是答案。 */
  private const val PLATE_MIN_BOX = 0.85f

  /** 掩码占框比例的上下限：低于下限交出去是个看不见的洞，高于上限是一块色板。 */
  private const val MIN_SHAPE_RATIO = 0.02f
  private const val MAX_SHAPE_RATIO = 0.85f

  /** 「仍是一整块板」的两条线：填到 [PLATE_FILL] 而且**内部没有洞**。字形总有字腔。 */
  private const val PLATE_FILL = 0.55f
  private const val PLATE_HOLE = 0.03f

  /** alpha 轮廓交出来的东西像一块板：单个连通块占 ≥ 它，而且没有洞。 */
  private const val ALPHA_SOLID_TOP = 0.90f

  /** 「碎成一片」三条一起成立才算碎。块多 ≠ 碎：月亮加星点天生十几块各自成块的正形。 */
  private const val SHATTER_COMPONENTS = 8
  private const val SHATTER_TOP = 0.35f
  private const val SHATTER_SMALL_SHARE = 0.5f

  /** 碎渣下限：连通块小于 `总面积 / MIN_SPECK_DIVISOR`（且不少于 8 像素）就擦掉。
   *  按面积给比例而不是写死像素数 —— 出图尺寸跟着桌面要的大小变（本机 192 / 384 两档）。 */
  private const val MIN_SPECK_DIVISOR = 4096

  /** 边界取样带的宽度 = 框短边 / [RING_BAND_DIV]，夹在 2..8 像素。底只从这条带里找。 */
  private const val RING_BAND_DIV = 24

  /** 底色候选的个数上限，以及一个候选至少要占环带不透明像素的比例。 */
  private const val CANDIDATE_K = 4
  private const val CANDIDATE_MIN_SHARE = 0.05f

  /** 候选之间的互异线：两个候选差不到它，就只算一个颜色（ΔE76 真值）。 */
  private const val CANDIDATE_DISTINCT = 18

  /**
   * 洪泛容差，单位 ΔE76 真值。[TOL_COARSE] 是第一轮（还没有色场、只有一个候选色）用的；
   * [TOL_MAX] 夹住自适应上界。之后每轮改用**色场自己量出来的散度**（底板像素距离分布的
   * [TOL_PCT] 分位）—— 这才是「局部底」这个模型能吃掉渐变的原因：全局一个颜色要容下整道渐变
   * （ΔE 40+，顺带把相近颜色的字形也吃了），局部色场每格只容自己那点抖动（ΔE 个位数）。
   */
  private const val TOL_COARSE = 18
  private const val TOL_MAX = 45

  /**
   * 容差的下限。**必须有**：色场越细，每格中位色越能贴着自己那一片像素，量出来的"底板散度"
   * 就越是趋向 0 —— 于是下一轮洪泛只容得下逐像素相同的颜色，渐变尾端重新变成留在掩码里的
   * 一层淡色（判据台上把网格从 6 提到 12，steamlink 的 `淡` 从 0.16 涨到 0.34、底占从 0.57
   * 掉到 0.39，就是这么坏掉的）。这是所有"局部背景估计"模型的通病：模型太灵活就会把前景
   * 也解释成背景。6 个 ΔE 是"肉眼分不出"的量级，比它更紧的容差没有意义。
   */
  private const val TOL_MIN = 6

  /** 洪泛至少要摘掉框内这么多像素，才认为这张图真有一块底可刻。 */
  private const val PLATE_MIN_SHARE = 0.15f

  /**
   * 透明底那一支：主体（与背景连成一片、离中位主色够近的那些墨）至少要占图形像素这么多，
   * 才允许把"被它围住的不同颜色"当镂空。
   *
   * 不到它就没有主体可言 —— 几个色块拼出来的 logo 里，每一块都"与别的块差得远"，
   * 按镂空处理会把其中一整块擦掉。量的是**洪泛之后的实际占比**，不是色桶占比：
   * 立体渲染的机身满屏明暗渐变，最大的色桶只占十几个百分点，但连成一片的主体占九成。
   */
  private const val BODY_MIN_SHARE = 0.45f

  /** 透明底那一支：一块墨与**局部**主色差到它以上，才有资格被当成镂空候选（ΔE76 真值）。 */
  private const val FEATURE_MIN_DELTA = 20

  /**
   * 色场网格边长（框短边不足 [FIELD_MIN_SIDE] 时退到 [FIELD_GRID_MIN]）。
   *
   * ⚠ 这个数决定「底能弯到什么程度」，而且两头都会坏：
   *  · 太粗 —— 每格一个中位色 + 格间双线性 = 一张分片线性面，弯得过的极限就是网格密度。
   *    6 格时一道对角渐变底在格心之间弯不过去，残差留到归一之后就是浅浅一层灰
   *    （判据台上 小步点 那张黄底顶部的那片晕）。
   *  · 太细 —— 每格贴着自己那一片像素，"底板散度"被量成 0，洪泛随之只容得下逐像素相同的
   *    颜色，渐变尾端整个留在掩码里。12 格实测：steamlink 的 `淡` 0.16→0.34、底占 0.57→0.39。
   *    这条另由 [TOL_MIN] 兜住，但网格本身不能指望远到 12。
   * 8 格 @384 = 每格 48px、上千像素取中位数，是这两头之间量出来的位置。
   */
  private const val FIELD_GRID = 8
  private const val FIELD_MIN_SIDE = 96
  private const val FIELD_GRID_MIN = 3

  /** 「拟合色场 → 重算距离 → 重新洪泛」跑几轮。第二轮起容差已小得多。 */
  private const val FIELD_ITERS = 3

  /** 实心参照：形层像素距离的这一个分位映射到 alpha 255。 */
  private const val INK_REF_PCT = 66

  /** 底板散度的分位，用来定每一轮的洪泛容差。 */
  private const val TOL_PCT = 97

  /** 距离直方图的桶数（一桶一个 ΔE76 单位）。 */
  private const val DBUCKETS = 256


  /** 色桶的位数：4 bit/通道 = 4096 桶。 */
  private const val BUCKETS = 4096

  // ─────────────────────────── 感知色度学 ───────────────────────────
  //
  // 全整数查表：这条链跑在桌面加载图标的路上，每个图标十几万像素，不能按像素开幂函数。
  // 三个 Lab 通道存成「真值 ×64」，于是 ΔE76 的平方是纯整数，只在最后开一次方。
  //
  // ⚠ 输入是**非预乘** ARGB：`Bitmap.getPixels` 的 javadoc（本机
  // `sources/android-37.0/android/graphics/Bitmap.java:2450`）写的是 "The returned colors are
  // non-premultiplied ARGB values in the sRGB color space"。半透明像素的 RGB 是它自己的颜色、
  // 没被 alpha 缩过 —— 所以「这一像素在屏幕上值多少」必须自己合成上去（见 [distanceOf]）。

  /** sRGB → 线性：`LIN[v] = round(linear(v/255) × 4096)`。 */
  private val LIN = IntArray(256) {
    val c = it / 255.0
    val lin = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    Math.round(lin * 4096.0).toInt()
  }

  /** CIE 的那条 `f(t)`：`FTAB[i] = round(f(i/1024) × 4096)`，覆盖 t ∈ [0, 2]。 */
  private val FTAB = IntArray(2049) {
    val t = it / 1024.0
    val f = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
    Math.round(f * 4096.0).toInt()
  }

  /** `LIN` 已带 ×4096，再乘 ×10000 的 XYZ 矩阵；除回白点就是查表用的 t。白点 D65。 */
  private val DEN_X: Long = (4096.0 * 10000.0 * 0.95047).toLong()
  private val DEN_Y: Long = (4096.0 * 10000.0).toLong()
  private val DEN_Z: Long = (4096.0 * 10000.0 * 1.08883).toLong()

  /**
   * 传递曲线：`CURVE[t] = round(255 × (t/255)^0.75)`。
   *
   * 指数小于 1 是有理由的：桌面拿 alpha 当**不透明度**上色，线性斜坡会把大半图形留在六成浓度
   * 以下，观感就是「主题色怎么这么浅」（旧版为此专门加过一条边缘保底）。这条曲线抬中低段、
   * 饱和实心段，同时保持顺序 —— 细节不是被删掉的，是被压到看得见的浓度上。
   */
  private val CURVE = IntArray(256) {
    Math.round(255.0 * Math.pow(it / 255.0, 0.75)).toInt()
  }

  /** 一个 sRGB 像素的 Lab 某个通道（真值 ×64）。`which`：0=L 1=a 2=b。
   *  ⚠ internal 只为判据台能对着公开参考值核这两张表（`SelfTest` 第 0 条），不是给业务用的。 */
  internal fun labChannel(pixel: Int, which: Int): Int {
    val lr = LIN[(pixel shr 16) and 0xff]
    val lg = LIN[(pixel shr 8) and 0xff]
    val lb = LIN[pixel and 0xff]
    val fx = FTAB[((lr * 4124 + lg * 3576 + lb * 1805).toLong() * 1024 / DEN_X).toInt().coerceAtMost(2048)]
    val fy = FTAB[((lr * 2126 + lg * 7152 + lb * 722).toLong() * 1024 / DEN_Y).toInt().coerceAtMost(2048)]
    val fz = FTAB[((lr * 193 + lg * 1192 + lb * 9505).toLong() * 1024 / DEN_Z).toInt().coerceAtMost(2048)]
    return when (which) {
      0 -> 116 * fy / 64 - 1024
      1 -> 500 * (fx - fy) / 64
      else -> 200 * (fy - fz) / 64
    }
  }

  /** 两组 Lab（×64）之间的 ΔE76，返回真值 ×64。sRGB 全域上界约 130，Short 装得下。 */
  private fun deltaE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
    val dl = (l1 - l2).toLong()
    val da = (a1 - a2).toLong()
    val db = (b1 - b2).toLong()
    return sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
  }

  // ───────────────────────────── 产出 ─────────────────────────────

  /**
   * @param mask 白 + alpha 的掩码（连续斜坡）；null = 这张图交不出能用的形状。
   * @param boxFill 掩码（alpha ≥ [A_SOLID]）占**内容外接框**的比例。被拒时它就是致拒的那个数。
   * @param soft 只到 alpha < [A_SOLID] 的那一层占框比例 —— 「细节有没有留下来」看它。
   * @param report 一行判据数字。每个数都是**这一支自己决策吃的那一个**，不是事后复算 ——
   *   上一轮的教训是「数打了，打在错的分母上，看着完全健康」。
   */
  class Output(
    val mask: IntArray?,
    val boxFill: Float,
    val hole: Float,
    val largest: Float,
    val components: Int,
    val smallShare: Float,
    val soft: Float,
    val report: String,
  )

  private class Stats(
    val kept: Int,
    val soft: Int,
    val components: Int,
    val largest: Float,
    val smallShare: Float,
    val hole: Float,
  )

  /** 一次 [build] 的全部可复用数组。放在 [ThreadLocal] 里，所以整条链不需要锁。 */
  private class Scratch {
    var labL = ShortArray(0)
    var labA = ShortArray(0)
    var labB = ShortArray(0)
    var dist = ShortArray(0)
    var pass = ByteArray(0)
    var plate = ByteArray(0)
    var mark = ByteArray(0)
    var grown = ByteArray(0)
    var visited = ByteArray(0)
    var queue = IntArray(0)
    var hist = IntArray(DBUCKETS)
    var fieldHist = IntArray(0)
    var cellR = ShortArray(0)
    var cellG = ShortArray(0)
    var cellB = ShortArray(0)
    var cellN = IntArray(0)
    /** 每格中位色的 Lab（×64）—— 距离直接查它，不再每像素现算转换。 */
    var fL = IntArray(0)
    var fA = IntArray(0)
    var fB = IntArray(0)
    /** 每个像素"背后那块底"是哪个格子（[propagateField] 的产物）；-1 = 还没传到。 */
    var ref = IntArray(0)

    var grid = 0
    /** 底家族（≤4 个角上颜色）的 Lab 与原始色，以及实际个数。 */
    var famL = IntArray(CANDIDATE_K)
    var famA = IntArray(CANDIDATE_K)
    var famB = IntArray(CANDIDATE_K)
    var famColor = IntArray(CANDIDATE_K)
    var fams = 0

    fun ensure(total: Int, cells: Int) {
      if (labL.size < total) {
        labL = ShortArray(total); labA = ShortArray(total); labB = ShortArray(total)
        dist = ShortArray(total)
        pass = ByteArray(total); plate = ByteArray(total)
        mark = ByteArray(total); grown = ByteArray(total); visited = ByteArray(total)
        queue = IntArray(total); ref = IntArray(total)
      }
      if (cellR.size < cells) {
        cellR = ShortArray(cells); cellG = ShortArray(cells); cellB = ShortArray(cells)
        cellN = IntArray(cells)
        fL = IntArray(cells); fA = IntArray(cells); fB = IntArray(cells)
        fieldHist = IntArray(cells * 768)
      }
    }
  }

  private val pool = ThreadLocal.withInitial { Scratch() }

  private fun reject(report: String) = Output(null, 0f, 0f, 0f, 0, 0f, 0f, report)

  // ───────────────────────────── 主流程 ─────────────────────────────

  /**
   * 把一张光栅化的图标压成掩码。
   *
   * @param pixels ARGB（非预乘）整张画布，尺寸 = 图层 bounds。
   */
  fun build(pixels: IntArray, w: Int, h: Int): Output {
    if (w <= 0 || h <= 0) return reject("尺寸非法")
    val total = w * h
    val box = contentBounds(pixels, w, h) ?: return reject("整张透明")
    val boxArea = box.width().toLong() * box.height()
    val band = (minOf(box.width(), box.height()) / RING_BAND_DIV).coerceIn(2, 8)
    val s = pool.get()
    s.ensure(total, FIELD_GRID * FIELD_GRID)

    // 0) 每个像素的 Lab 只算一次：后面每一轮洪泛、每一次候选比较都只是查表 + 一次开方。
    for (i in 0 until total) {
      val p = pixels[i]
      s.labL[i] = labChannel(p, 0).toShort()
      s.labA[i] = labChannel(p, 1).toShort()
      s.labB[i] = labChannel(p, 2).toShort()
    }

    // ── 支一：透明底。图标自带的 alpha 就是形状，一个像素都不改。 ──
    // ⚠ 这一支不做任何形态学：那是设计者画好的轮廓，多做一次只会凭空造像素。
    val alphaBoxFill = opaqueShare(pixels, w, box)
    val alphaMask = IntArray(total)
    for (i in 0 until total) {
      val a = Color.alpha(pixels[i])
      // ⚠ 「哪些像素算图形」= `a >= A_SOLID`，「写出去的 alpha 是多少」= 原值。两件事不能混，
      //   混了就回归成那条老 bug：桌面图标的前景层常自带一层很淡的柔光 / 投影 / 半透明底板，
      //   实测能占画布 60% 以上（真机日志里就是 `a1=0.66` 那种），照收进掩码的话上色之后
      //   **整块图标被一层淡色铺满，看上去就是一个纯色块**。保留原值（不二值化到 255）是为了
      //   留住边缘那圈抗锯齿 —— 桌面图标渲染尺寸大，硬边放大就是一圈锯齿。
      if (a >= A_SOLID) alphaMask[i] = (a shl 24) or 0x00FFFFFF
    }
    // 但**紧贴实心的那一圈淡值**要照原样留着：桌面图标的渲染尺寸大，只留 ≥128 会把每条
    // 笔画的边变成一级硬台阶（观感就是一圈锯齿，旧版为此专门开过 `preserveAlpha`）。
    // 只补"贴着实心的那一圈"，所以柔光底板剩下的 60% 仍然被上面那道线挡在外面 ——
    // 边缘过渡与淡色铺满这两件事，靠"离实心多远"分开，不靠再画一条绝对浓度线。
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val a = Color.alpha(pixels[i])
        if (a in 1 until A_SOLID && hasSolidNeighbour(pixels, w, h, i, x, y)) {
          alphaMask[i] = (a shl 24) or 0x00FFFFFF
        }
      }
    }
    val alphaStats = measure(alphaMask, w, h, box, s, bridge = false, despeckle = false)
    val alphaFill = alphaStats.kept.toFloat() / boxArea
    // 「像一块板」= 一个连通块占满 + 里面没有洞。那种 alpha 描述的是**那块底的外轮廓**，
    // 不是图形 —— 照交出去就是一整块色（真机 元宝 `cc=1 top=1.00 空洞=0.00`）。
    val alphaIsPlate = alphaStats.largest >= ALPHA_SOLID_TOP && alphaStats.hole <= PLATE_HOLE
    val alphaUsable = alphaBoxFill > MIN_SHAPE_RATIO && alphaBoxFill < PLATE_MIN_BOX &&
      alphaFill < MAX_SHAPE_RATIO

    // ── 支二：有一整块不透明的底（或 alpha 轮廓自己像一块板）→ 按「离局部底多远」刻 ──
    // ⚠ carve 永远返回一个 Output（mask 可空）+ **它自己那一条拒绝理由**。它以前返回 `null`，
    //   于是真机上 23 个图标只留下一句「刻底不适用」—— 看不出是"没有候选"、"底摘不掉"、
    //   "整张没有对比"还是"被形状闸拦下"，而那四种要改的东西完全不同。这个项目已经在
    //   「提前 return 静默失效」上吃过两次亏，判据的每一个出口都必须带自己的数。
    val carved = if (!alphaUsable || alphaIsPlate) carve(pixels, w, h, box, band, s) else null
    // 换掉图标自带的形状，条件必须**两条一起**：占框更少（底真被摘掉了）**而且**洞更多
    //（刻出了字腔）。只少不多 = 把图形一起刻没了；只多不少 = 在底上又添一层。
    val carvedWins = carved?.mask != null &&
      (!alphaUsable || (alphaIsPlate && carved.boxFill < alphaBoxFill && carved.hole > alphaStats.hole))
    if (carvedWins) return carved!!

    // ⚠ **可用但像一块板 ≠ 不交**。上一版在这里判「alpha 连垫底都不配」，两支都不成立就返回
    //   null 让桌面自己画。合成用例台把这条打穿了：一张透明底上的实心圆盘、一张柔光加一个
    //   实心核（就是皎月连那一类），刻底找不到可刻的第二层 → 两支全灭 → 桌面回去按**亮度**
    //   重画 → 整块被涂成实心。回落从来不是中性的（它算的就是错的那个极性），而"这张图本来就
    //   只有一层"不是错误，是事实 —— 那种图的正确答案就是它自己的轮廓。
    //   真正该拦的是「满幅不透明的一块底」，那由 [PLATE_MIN_BOX] 挡在 alphaUsable 外面，
    //   不靠这一条。
    if (alphaUsable) {
      // 透明底 ≠ 细节已经在里面。纯 alpha 轮廓会把图形**内部**的镂空（字腔、白色 logo 上的
      // 洞、高光）全填平 —— 判据台上那个绿色机器人：机身、白立方体、两只眼睛一样浓。
      val knocked = carveInside(pixels, w, h, box, band, alphaMask, s)
      val st = measure(alphaMask, w, h, box, s, bridge = false, despeckle = false)
      val fill = st.kept.toFloat() / boxArea
      return Output(alphaMask, fill, st.hole, st.largest, st.components, st.smallShare,
        softShare(st, box),
        "支=透明底 框内=${pct(fill)} 空洞=${pct(st.hole)} top=${pct(st.largest)} " +
          "cc=${st.components} 镂空=${pct(knocked)} 淡=${pct(softShare(st, box))}" +
          if (carved != null) "（刻底换不过：${carved.report}）" else "")
    }
    return Output(null, alphaFill, alphaStats.hole, alphaStats.largest, alphaStats.components,
      alphaStats.smallShare, 0f,
      "支=无 透明底框内=${pct(alphaBoxFill)}不成立；刻底：${carved?.report ?: "未尝试"}")
  }

  // ─────────────────── 支二：局部底色场 + 距离斜坡 ───────────────────

  private fun carve(pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, s: Scratch): Output {
    val total = w * h
    val boxArea = box.width().toLong() * box.height()
    val g = if (minOf(box.width(), box.height()) >= FIELD_MIN_SIDE) FIELD_GRID else FIELD_GRID_MIN
    s.ensure(total, g * g)
    s.grid = g

    // 1) 底 = 内容框**四个角**上的颜色家族（「透明」也算一族，它在 pass 里恒过）。
    //    ⚠ 为什么按角、不按"哪个候选摘掉的面积多"：真机抓回来的 11 张里有 3 张是同一个病 ——
    //    高德（绿/白/蓝/灰四块拼底）、饿了么（上橙下蓝两段）、云闪付（上红下白两段）。
    //    **底不是一个颜色，是几块拼的**；按面积只选一个家族，剩下那块底整块变成墨，
    //    屏幕上就是他报的「还剩一块大色块」。角是判据：一块底铺满它所在那块区域，必然占住
    //    某个角；图形即使贴着边一般也占不到角（占到了的那种，本来就没有"底在它下面"这回事）。
    val famCount = cornerFamilies(pixels, w, box, band, s)
    if (famCount == 0) return reject("刻底=四个角都没有不透明的底")

    // 2) 一次洪泛穿过**所有**底家族 —— 底是这些家族的并集，不是冠军那一个。
    //    ⚠ 放行条件是「到某个家族色的距离」，不是「与相邻像素的色差」。后者是区域生长，
    //    两头都实测过：容差小跨不过分带的渐变底（底擦不掉），容差大能顺着柔光字形一路
    //    漂出去（图形整个被擦光）。局部连续性根本分不开「渐变底」与「柔光字形」。
    passFromFamilies(pixels, w, box, s, TOL_COARSE shl 6)
    val flooded0 = flood(s, pixels, w, box, band, atRim = true)
    // 连六分之一框都摘不掉，说明这张图没有「一块底」可谈（几个色块拼出来的 logo）。
    if (flooded0.toLong() * 6 < boxArea) {
      return reject("刻底=$famCount 族底只摘掉 ${pct(flooded0.toFloat() / boxArea)} 的框")
    }

    // 3) 拟合局部色场 → 重算距离 → 按新容差重新洪泛，跑 [FIELD_ITERS] 轮。
    //    第一轮只有一个全局候选色，容差必须给到能容下整道渐变（ΔE 18），代价是把与底相近的
    //    字形也圈进去；有色场之后每格只需容下自己那点抖动，容差自动掉到个位数，那些字形就
    //    留下来了。这就是「底是区域、不是颜色」的解法。
    var tol = TOL_COARSE shl 6
    var flooded = 0
    for (iter in 0 until FIELD_ITERS) {
      if (iter > 0) {
        for (y in box.top until box.bottom) {
          val row = y * w
          for (x in box.left until box.right) {
            val i = row + x
            s.pass[i] = if ((s.dist[i].toInt() and 0xffff) <= tol) 1 else 0
          }
        }
      }
      flooded = flood(s, pixels, w, box, band, atRim = true)
      if (flooded == 0) return reject("刻底=洪泛为空")
      fitField(pixels, w, box, g, s)
      propagateField(w, h, total, box, s)
      // 中缝要在**算距离之前**并进底：它借的是邻格所属的那块底，晚一步就会先被算成"差得远的墨"。
      absorbSeams(w, total, box, s)
      var spread = 0
      s.hist.fill(0)
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          val d = distanceOf(pixels[i], i, s)
          s.dist[i] = d.toShort()
          if (s.plate[i] != 0.toByte()) {
            s.hist[(d shr 6).coerceAtMost(DBUCKETS - 1)]++
            spread++
          }
        }
      }
      if (spread == 0) return reject("刻底=底板为空")
      tol = (percentile(s.hist, spread, TOL_PCT) shl 6)
        .coerceIn(TOL_MIN shl 6, TOL_MAX shl 6)
      // 底占就用这一轮**实际数出来**的底板像素，不再用洪泛返回的数：洪泛之后还有吸收中缝
      // 一步，两个数会不一致，而日志里的 `底占` 必须是交出去那张图的底。
      flooded = spread
    }
    val plateShare = flooded.toFloat() / boxArea
    if (plateShare < PLATE_MIN_SHARE) return reject("刻底=无主导底板 底占=${pct(plateShare)}")

    // 4) 归一：实心参照取**这张图自己的形层**分位，不是绝对值，也不是最大值。
    //    取最大值的话，渐变尾端、一条高光、JPEG 块效应就能把整段斜率压平 ——
    //   「渐变又被做成平色」就是这么来的。
    s.hist.fill(0)
    var ink = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte()) continue
        s.hist[((s.dist[i].toInt() and 0xffff) shr 6).coerceAtMost(DBUCKETS - 1)]++
        ink++
      }
    }
    if (ink == 0) return reject("刻底=底占满了整框")
    // 归一是**两点标定**，不是一条绝对线：底板自己散到多远（[tol]）= 0，这张图的形层
    // 占到 [INK_REF_PCT] 分位的那个距离（[ref]）= 实心。
    // ⚠ 只标一个点（零点摆在"离底 0"）会把底板噪声带里那些像素留在很淡的一层上 ——
    //   渐变底残下来的一片晕就是这么来的（判据台上 小步点 黄底左边那道影子）。
    //   这条也**不是**旧版那条绝对的 `TONE_FLOOR`：零点是这张图自己的底量出来的，
    //   只压到"与底不可分"的那一段；柔光字形只要比底可分一点就照旧留。
    val ref = percentile(s.hist, ink, INK_REF_PCT) shl 6
    // 形层与底散在同一个范围里 = 这张图本来就只有一种颜色，没有可刻的细节。
    if (ref <= tol) return reject("刻底=整张没有对比 容ΔE=${tol shr 6} 实ΔE=${ref shr 6}")
    val span = ref - tol

    val out = IntArray(total)
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte()) continue
        val a = CURVE[(((s.dist[i].toInt() and 0xffff) - tol) * 255 / span).coerceIn(0, 255)]
        if (a > 0) out[i] = (a shl 24) or 0x00FFFFFF
      }
    }

    // 5) 形状体检：桥 1 像素的缝 → 擦碎渣 → 三道闸。
    val st = measure(out, w, h, box, s, bridge = true, despeckle = true)
    val fill = st.kept.toFloat() / boxArea
    val note = "支=刻底 底占=${pct(plateShare)} 容ΔE=${tol shr 6} 实ΔE=${ref shr 6} 框内=${pct(fill)} " +
      "cc=${st.components} top=${pct(st.largest)} 小片=${pct(st.smallShare)} " +
      "空洞=${pct(st.hole)} 淡=${pct(st.soft.toFloat() / boxArea)}"
    // 闸门只看「交出去的东西能不能看」，不参与怎么算 —— 算法已经不会主动产出色板了。
    if (fill < MIN_SHAPE_RATIO) return reject("刻底=修整后几乎全空 框内=${pct(fill)}")
    if (fill > MAX_SHAPE_RATIO) return reject("刻底=仍是整块底 框内=${pct(fill)}")
    if (fill >= PLATE_FILL && st.hole <= PLATE_HOLE) return reject("刻底=填满却没有洞 框内=${pct(fill)} 空洞=${pct(st.hole)}")
    if (st.components >= SHATTER_COMPONENTS && st.largest <= SHATTER_TOP &&
      st.smallShare >= SHATTER_SMALL_SHARE
    ) return reject("刻底=桥接后仍碎成 ${st.components} 块 top=${pct(st.largest)} 小片=${pct(st.smallShare)}")
    return Output(out, fill, st.hole, st.largest, st.components, st.smallShare,
      st.soft.toFloat() / boxArea, note)
  }

  /**
   * 一个像素的「离底多远」，返回 ΔE76 × 64。
   *
   * 参照色来自 [propagateField]：**这一像素背后那块底是什么**，而不是"它落在哪个网格里"。
   *
   * alpha 按覆盖率收一道：半透明字形只贡献它真正显示出来的那点对比。这不是绝对的 alpha 线
   * （旧版 `MIN_SHAPE_ALPHA` 那种切法正是「柔光字形被切成骨架」的来源）—— 差为 0 才归 0，
   * 淡而可分的照旧留。
   */
  private fun distanceOf(pixel: Int, i: Int, s: Scratch): Int {
    val a = Color.alpha(pixel)
    if (a == 0) return 0
    val cell = if (s.ref[i] >= 0) s.ref[i] else 0
    val de = deltaE(
      s.labL[i].toInt(), s.labA[i].toInt(), s.labB[i].toInt(),
      s.fL[cell], s.fA[cell], s.fB[cell]
    )
    return if (a >= 255) de else de * a / 255
  }

  /**
   * 把每格的底色**传播**到整张画布：每个像素的参照色 = 离它最近的那块底的色。
   *
   * 为什么不能按格子取（上一版是"周围 2×2 格里取最近的一个"）：
   *  · **网格边界会留在掩码上**。一块字形跨了两个格子，两格的底色不同（比如四块拼底的中缝
   *    两侧），字形的浓度就在格界上跳一级 —— 高德那张预览里纸飞机右下角的灰色方块就是这么来的。
   *  · **两块底的中缝会被当成图形**。红底与白底之间那一圈抗锯齿混色，离两边都远，
   *    按格子算就是"差得远 = 墨"，于是云闪付那道红白分界线上留下一串虚线。
   * 按"最近的底"传播，这两件事同时消失：中缝那一圈是从某一侧的底被到达的，差为 0；
   * 字形内部每个像素拿到的是它**背后**那块底的延续，与网格无关。
   *
   * 洪泛从底像素出发四邻域扩散（与 [flood] 同一个道理：八邻域会顺着对角线钻过笔画之间的缝）。
   */
  private fun propagateField(w: Int, h: Int, total: Int, box: Rect, s: Scratch) {
    val ref = s.ref
    val q = s.queue
    val g = s.grid
    val bw = box.width()
    val bh = box.height()
    for (i in 0 until total) ref[i] = -1
    var head = 0
    var tail = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / bh).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] == 0.toByte()) continue
        val cx = ((x - box.left) * g / bw).coerceAtMost(g - 1)
        ref[i] = cy * g + cx
        q[tail++] = i
      }
    }
    while (head < tail) {
      val i = q[head++]
      val y = i / w
      val x = i - y * w
      val v = ref[i]
      if (x > box.left && ref[i - 1] < 0) { ref[i - 1] = v; q[tail++] = i - 1 }
      if (x < box.right - 1 && ref[i + 1] < 0) { ref[i + 1] = v; q[tail++] = i + 1 }
      if (y > box.top && ref[i - w] < 0) { ref[i - w] = v; q[tail++] = i - w }
      if (y < box.bottom - 1 && ref[i + w] < 0) { ref[i + w] = v; q[tail++] = i + w }
    }
  }

  /**
   * pass = 「这一像素与**某个**底家族差不到 [tol]」。透明像素一律算过 —— 那是「没有东西」，
   * 本来就该被底吃掉。
   */
  private fun passFromFamilies(pixels: IntArray, w: Int, box: Rect, s: Scratch, tol: Int) {
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (Color.alpha(pixels[i]) < A_SOLID) { s.pass[i] = 1; continue }
        val l = s.labL[i].toInt(); val a = s.labA[i].toInt(); val b = s.labB[i].toInt()
        var ok = false
        for (c in 0 until s.fams) {
          if (deltaE(l, a, b, s.famL[c], s.famA[c], s.famB[c]) <= tol) { ok = true; break }
        }
        s.pass[i] = if (ok) 1 else 0
      }
    }
  }

  /**
   * 内容框**四个角**上的底色家族（≤4，互异线合并）。
   *
   * 每个角取一个 `band*2` 窗口里不透明像素的中位色；那个角基本是空的（透明）就不算一族 ——
   * 透明由 [passFromFamilies] 里那条恒通过代表。
   *
   * 为什么用中位色而不是最大色桶：一块带渐变的底在 4 bit 桶上会散成好几桶，众数会挑到渐变的
   * 某一端、把另一端留在掩码里；中位数落在这一块的中间，容差负责铺满。
   * 为什么按角而不是按整条边界带：边界带是一条窄环，圆角图标的环上同时有底和留白，
   * 而"角"这个位置只有底会去占 —— 见 [carve] 里那条真机教训。
   */
  private fun cornerFamilies(pixels: IntArray, w: Int, box: Rect, band: Int, s: Scratch): Int {
    s.fams = 0
    val bw = box.width()
    val bh = box.height()
    val win = (band * 2).coerceAtMost(minOf(bw, bh))
    val limit = minOf(bw, bh) / 3
    for (cy in 0..1) for (cx in 0..1) {
      // ⚠ 窗口要**往里走**直到落在底上，不能钉在角点上：圆角方、圆形这些形状，内容框的角
      //   本来就是空的。上一版钉在角上，云闪付 / 12123 / 闲鱼 三张满幅圆角图标量出
      //   「四个角都没有不透明的底」，当场从"刻得很好"退化成"整个放弃合成"。
      var color: Int? = null
      var step = 0
      while (step <= limit && color == null) {
        val x0 = (if (cx == 0) box.left + step else box.right - win - step).coerceIn(box.left, box.right - win)
        val y0 = (if (cy == 0) box.top + step else box.bottom - win - step).coerceIn(box.top, box.bottom - win)
        color = medianColor(pixels, w, x0, y0, x0 + win, y0 + win, s)
        step++
      }
      if (color == null) continue
      var dup = false
      for (c in 0 until s.fams) {
        if (deltaE(
            labChannel(color, 0), labChannel(color, 1), labChannel(color, 2),
            s.famL[c], s.famA[c], s.famB[c]
          ) <= CANDIDATE_DISTINCT shl 6
        ) dup = true
      }
      if (dup) continue
      s.famL[s.fams] = labChannel(color, 0)
      s.famA[s.fams] = labChannel(color, 1)
      s.famB[s.fams] = labChannel(color, 2)
      s.famColor[s.fams] = color
      s.fams++
    }
    return s.fams
  }

  /** 一块矩形里不透明像素的逐通道中位色；不透明像素不到四分之一 → null（这块是空的）。 */
  private fun medianColor(
    pixels: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, s: Scratch,
  ): Int? {
    val hist = s.hist
    var n = 0
    for (y in y0 until y1) {
      val row = y * w
      for (x in x0 until x1) if (Color.alpha(pixels[row + x]) >= A_SOLID) n++
    }
    if (n * 4 < (x1 - x0) * (y1 - y0)) return null
    var color = 0
    for (c in 0 until 3) {
      hist.fill(0)
      for (y in y0 until y1) {
        val row = y * w
        for (x in x0 until x1) {
          val p = pixels[row + x]
          if (Color.alpha(p) >= A_SOLID) hist[(p shr (16 - 8 * c)) and 0xff]++
        }
      }
      color = color or (median(hist, 0, n) shl (16 - 8 * c))
    }
    return (0xff shl 24) or color
  }

  /**
   * 穿过 `pass==1` 的像素洪泛。结果写进 `plate`（1 = 通得过），返回像素数。
   *
   * 两种种子，对应两个问题：
   *  · [atRim]=true —— 种子放在**内容框边界带**里：「这块底与外面连成一片」= 它是背景。
   *    框外本就是留白，图形不可能从那里被围住，所以种子只能在带里。
   *  · [atRim]=false —— 种子放在**紧贴背景**的像素上：这一块的边缘就是图标的轮廓，
   *    它是图形的一个组成部分，不是被掏空的洞。透明底那一支用它区分「另一半 logo」与
   *    「logo 上的镂空」。
   *
   * ⚠ 四邻域，不是八邻域 —— 八邻域会顺着对角线从笔画之间的缝里钻到图形内部，
   * 把本该留下的字腔一起当底擦掉。
   */
  private fun flood(s: Scratch, pixels: IntArray, w: Int, box: Rect, band: Int, atRim: Boolean): Int {
    val pass = s.pass
    val plate = s.plate
    val queue = s.queue
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) plate[row + x] = 0
    }
    var tail = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (pass[i] == 0.toByte()) continue
        if (atRim) {
          if (!inBand(x, y, box, band)) continue
        } else if (!touchesBackground(pixels, w, box, x, y)) continue
        plate[i] = 1
        queue[tail++] = i
      }
    }
    var head = 0
    while (head < tail) {
      val i = queue[head++]
      val y = i / w
      val x = i - y * w
      if (x > box.left && plate[i - 1] == 0.toByte() && pass[i - 1] != 0.toByte()) {
        plate[i - 1] = 1; queue[tail++] = i - 1
      }
      if (x < box.right - 1 && plate[i + 1] == 0.toByte() && pass[i + 1] != 0.toByte()) {
        plate[i + 1] = 1; queue[tail++] = i + 1
      }
      if (y > box.top && plate[i - w] == 0.toByte() && pass[i - w] != 0.toByte()) {
        plate[i - w] = 1; queue[tail++] = i - w
      }
      if (y < box.bottom - 1 && plate[i + w] == 0.toByte() && pass[i + w] != 0.toByte()) {
        plate[i + w] = 1; queue[tail++] = i + w
      }
    }
    return tail
  }

  private fun inBand(x: Int, y: Int, box: Rect, band: Int): Boolean =
    x - box.left < band || box.right - x <= band || y - box.top < band || box.bottom - y <= band

  /**
   * 把「两块底之间的中缝」吸收进底。
   *
   * 两块不同颜色的底相接处有一圈抗锯齿混色：它离两边家族都远，洪泛过不去，于是留在掩码上
   * 成了一道灰色的接缝 —— 真机重放里看得很清楚（高德四块拼底之间那道十字线、云闪付红白两段
   * 之间那串虚线）。
   *
   * 判据**不是**"它离底多远"（那正是它骗过去的地方），而是**它处在什么位置**：
   * 某一对**相反方向**上都是底，而这两边的底色彼此差得开 → 它是两块底的交界，不可能是图形。
   * 用"相反两向"而不是"四邻全是底"：真实的中缝有 2px 宽，缝里那一格的左右两邻也是缝像素，
   * 四邻全底的条件永远不会成立（第一版就是这么写的，判据台上量出来缝上还有 89 的浓度）。
   * 这与 [measure] 里那条"只补 1 像素的缝"的桥接是同一个几何，方向相反：那里是两向有墨
   * 就把缝接上，这里是两向有底就把缝归底。
   *
   * ⚠ 反过来，一条画在均匀底上的 1px 细线也是"两向都是底"，但它两边所属的底色**彼此相同** →
   *   不吸收。这条区分是这一步的全部意义所在，`SelfTest` 第 9 条同时钉两个方向：
   *   「拼底的中缝要消失」与「细笔画不许被吃掉」必须同时成立，否则就是拿细节换干净。
   */
  private fun absorbSeams(w: Int, total: Int, box: Rect, s: Scratch): Int {
    val plate = s.plate
    val ref = s.ref
    val core = s.grown        // 真正的"两块底对脸"的那些格子
    val seen = s.visited
    val q = s.queue
    for (i in 0 until total) { core[i] = 0; seen[i] = 0 }
    var cores = 0
    if (box.width() <= SEAM_REACH * 2 + 2 || box.height() <= SEAM_REACH * 2 + 2) return 0
    for (y in box.top + SEAM_REACH until box.bottom - SEAM_REACH) {
      val row = y * w
      for (x in box.left + SEAM_REACH until box.right - SEAM_REACH) {
        val i = row + x
        if (plate[i] != 0.toByte()) continue
        // 四个方向上"隔着缝对脸看"：两边都是底、而且两边所属的底色差得开。
        // ⚠ 必须**沿射线看几步**，不能只看紧邻那一格：真实的中缝有 2~4px 宽，缝里任何一格的
        //   两侧都不是底（另一侧还是缝）—— 只看紧邻格的那一版，判据台上缝上还有 89 的浓度、
        //   一格都没吸收掉。看到底为止，但不超过 [SEAM_REACH]，再远就不是缝了。
        if (seamAcross(plate, ref, s, i, -1, 1) || seamAcross(plate, ref, s, i, -w, w) ||
          seamAcross(plate, ref, s, i, -w - 1, w + 1) || seamAcross(plate, ref, s, i, -w + 1, w - 1)
        ) {
          core[i] = 1
          cores++
        }
      }
    }
    if (cores == 0) return 0
    // 从缝边那一格出发，穿过"还不是底"的像素吞并整条缝。
    // ⚠ 只有一道闸：**这块够不够细**（≤ [SEAM_MAX_SHARE]）。原来还加了"碰到内容框边界就放弃"，
    //   那条是错的而且是致命的：横向两段拼底的分界线**天生横贯整张图**，一定碰到左右边界 ——
    //   判据台上红蓝两段那一格都没吸收掉，就是被这条闸挡的。
    //   真正的保护是"两侧底色差得开"：一条画在均匀底上的细线，两侧是同一个底色，
    //   根本进不了种子集，不需要靠边界来排除。
    val cap = (box.width().toLong() * box.height() * SEAM_MAX_SHARE).toInt()
    var absorbed = 0
    for (start in box.top * w + box.left..(box.bottom - 1) * w + box.right - 1) {
      if (core[start] == 0.toByte() || seen[start] != 0.toByte()) continue
      var head = 0
      var tail = 0
      seen[start] = 1
      q[tail++] = start
      while (head < tail && tail <= cap) {
        val i = q[head++]
        val y = i / w
        val x = i - y * w
        for (d in 0 until 4) {
          val jx = x + when (d) { 0 -> -1; 1 -> 1; else -> 0 }
          val jy = y + when (d) { 2 -> -1; 3 -> 1; else -> 0 }
          if (jx !in box.left until box.right || jy !in box.top until box.bottom) continue
          val j = jy * w + jx
          if (seen[j] != 0.toByte() || plate[j] != 0.toByte()) continue
          seen[j] = 1
          q[tail++] = j
        }
      }
      if (tail > cap) continue
      for (k in 0 until tail) { plate[q[k]] = 1; absorbed++ }
    }
    return absorbed
  }

  /** 沿这两个相反方向各看到底为止，两边都是底、而两边所属的底色差得开 → 压在两块底的交界上。 */
  private fun seamAcross(plate: ByteArray, ref: IntArray, s: Scratch, i: Int, back: Int, fwd: Int): Boolean {
    val a = rayPlate(plate, ref, i, back) ?: return false
    val b = rayPlate(plate, ref, i, fwd) ?: return false
    return deltaE(s.fL[a], s.fA[a], s.fB[a], s.fL[b], s.fA[b], s.fB[b]) >= SEAM_DIVERGE shl 6
  }

  /** 沿这一步长方向最多走 [SEAM_REACH] 步，取遇到的第一个底像素所属的格子。 */
  private fun rayPlate(plate: ByteArray, ref: IntArray, i: Int, step: Int): Int? {
    var j = i + step
    for (k in 0 until SEAM_REACH) {
      if (plate[j] != 0.toByte()) return if (ref[j] >= 0) ref[j] else null
      j += step
    }
    return null
  }

  /** 两块底之间那圈混色要差到它才算"不同的底"（ΔE76）。比 [TOL_COARSE] 宽一档。 */
  private const val SEAM_DIVERGE = 25

  /** 隔着缝对脸看的最长射线。超过它就说明中间不是一条缝，是一大片东西。 */
  private const val SEAM_REACH = 5

  /** 一条缝最多占框这么多。超过就不是缝，是色块，不许吞。 */
  private const val SEAM_MAX_SHARE = 0.02f

  /** 这一像素是不是紧贴着「没有东西」（含框边）。贴着 = 它是图标轮廓的一部分。 */
  private fun touchesBackground(pixels: IntArray, w: Int, box: Rect, x: Int, y: Int): Boolean {
    if (x <= box.left || y <= box.top || x >= box.right - 1 || y >= box.bottom - 1) return true
    val i = y * w + x
    return Color.alpha(pixels[i - 1]) < A_SOLID || Color.alpha(pixels[i + 1]) < A_SOLID ||
      Color.alpha(pixels[i - w]) < A_SOLID || Color.alpha(pixels[i + w]) < A_SOLID
  }

  /** 四邻域里有没有实心像素 —— 有，这一格就是某条笔画的抗锯齿边缘，不是铺开的柔光。 */
  private fun hasSolidNeighbour(pixels: IntArray, w: Int, h: Int, i: Int, x: Int, y: Int): Boolean {
    if (x > 0 && Color.alpha(pixels[i - 1]) >= A_SOLID) return true
    if (x < w - 1 && Color.alpha(pixels[i + 1]) >= A_SOLID) return true
    if (y > 0 && Color.alpha(pixels[i - w]) >= A_SOLID) return true
    return y < h - 1 && Color.alpha(pixels[i + w]) >= A_SOLID
  }

  /**
   * 图形自己的主色 = 不透明像素的**逐通道中位色**。
   *
   * 不用最大色桶：立体渲染的机身（判据台上那个绿色机器人）满屏都是明暗渐变，最大的
   * 4 bit 桶也只占十几个百分点，按"有没有主导色"问就会永远答"没有"，镂空那一步根本不会跑。
   * 中位数不会被骗走。**"这张图到底有没有主体"改到洪泛之后用实际结果判**（见
   * [carveInside] 里的 `bodyShare`）—— 量产物永远比量前提可靠。
   */
  private fun medianInkColor(pixels: IntArray, w: Int, box: Rect, s: Scratch): Int? {
    var ink = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) if (Color.alpha(pixels[row + x]) >= A_SOLID) ink++
    }
    if (ink == 0) return null
    val hist = s.hist
    var color = 0xff shl 24
    for (c in 0 until 3) {
      hist.fill(0)
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val p = pixels[row + x]
          if (Color.alpha(p) >= A_SOLID) hist[(p shr (16 - 8 * c)) and 0xff]++
        }
      }
      color = color or (median(hist, 0, ink) shl (16 - 8 * c))
    }
    return color
  }

  /**
   * **透明底那一支的内部镂空**。
   *
   * 这一支的底是「没有东西」，所以图标整个都是墨 —— 但照抄 alpha 会把图形**内部**的层次
   * 全填平：白立方体、两只眼睛、机身上的高光，在 alpha 里都是同一个「不透明」。判据台上
   * 那个绿色机器人交出去就是一整块实心，正是用户说的「不够细致」。
   *
   * 做法与刻底支同一条原理，只把参照物从「底」换成「图形自己的主色」，并且只动**被围住的**
   * 那部分：
   *  · 与背景连成一片的主色 = 主体，照旧实心（一个像素不动）；
   *  · 被主体围住、又与**局部**主色场差得多的那一块 = 镂空，按差多少把浓度降下来 ——
   *    桌面拿 alpha 当不透明度，降下去就是"透出系统底色"，正是主题图标该长的样子；
   *  · 与主色不同、但自己就贴着背景的那一块**不算镂空**（红蓝双色 logo 的另一半、
   *    两色拼接的图标）：那是图形的组成部分，降下去等于把一半 logo 擦了。
   *
   * ⚠ 这一支不做任何"淡档清理"：这里的淡值全是源图自带的抗锯齿，擦掉就是一圈锯齿。
   *
   * @return 被镂空的像素占内容框的比例，进日志（`镂空=`）。
   */
  private fun carveInside(
    pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, argb: IntArray, s: Scratch,
  ): Float {
    val body = medianInkColor(pixels, w, box, s) ?: return 0f
    val bl = labChannel(body, 0)
    val ba = labChannel(body, 1)
    val bb = labChannel(body, 2)
    // 1) 主体 = 从背景出发、穿过「与主色够近」的像素能到达的那些墨。
    var ink = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val opaque = Color.alpha(pixels[i]) >= A_SOLID
        if (opaque) ink++
        s.pass[i] = if (!opaque ||
          deltaE(s.labL[i].toInt(), s.labA[i].toInt(), s.labB[i].toInt(), bl, ba, bb) <= TOL_COARSE shl 6
        ) 1 else 0
      }
    }
    if (flood(s, pixels, w, box, band, atRim = true) == 0) return 0f
    // 「有没有主体」在这里量，不在色桶上量：主体要真的连成一片、占到图形的一大半，
    // 才谈得上"剩下的那些是被它围住的洞"。几个色块拼出来的 logo 过不了这一关 ——
    // 那种图的另一半贴着背景，第 3 步也会把它留下，但占比不够就不该动任何浓度。
    var bodyInk = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() && Color.alpha(pixels[i]) >= A_SOLID) bodyInk++
      }
    }
    if (ink == 0 || bodyInk.toFloat() / ink < BODY_MIN_SHARE) return 0f
    // 2) 局部主色场：机身自己带渐变/明暗时，"离主色多远"必须按所在位置量，与刻底支同一个理由。
    val g = if (minOf(box.width(), box.height()) >= FIELD_MIN_SIDE) FIELD_GRID else FIELD_GRID_MIN
    s.grid = g
    s.ensure(w * h, g * g)
    fitField(pixels, w, box, g, s)
    propagateField(w, h, w * h, box, s)
    var feats = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (Color.alpha(pixels[i]) < A_SOLID) { s.pass[i] = 0; continue }
        val d = distanceOf(pixels[i], i, s)
        s.dist[i] = d.toShort()
        val feature = s.plate[i] == 0.toByte() && d > FEATURE_MIN_DELTA shl 6
        s.pass[i] = if (feature) 1 else 0
        if (feature) feats++
      }
    }
    if (feats == 0) return 0f
    // 3) 贴着背景的那部分特征 = 图形的另一半，不是洞：先把它从"可镂空"里摘出去。
    flood(s, pixels, w, box, band, atRim = false)
    s.hist.fill(0)
    var n = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.pass[i] == 0.toByte() || s.plate[i] != 0.toByte()) continue
        s.hist[((s.dist[i].toInt() and 0xffff) shr 6).coerceAtMost(DBUCKETS - 1)]++
        n++
      }
    }
    if (n == 0) return 0f
    val ref = (percentile(s.hist, n, INK_REF_PCT) shl 6).coerceAtLeast(1)
    var knocked = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.pass[i] == 0.toByte() || s.plate[i] != 0.toByte()) continue
        val a = Color.alpha(argb[i])
        if (a == 0) continue
        val keep = (ref - (s.dist[i].toInt() and 0xffff).coerceAtMost(ref)) * a / ref
        argb[i] = if (keep <= 0) 0 else (keep shl 24) or 0x00FFFFFF
        knocked++
      }
    }
    return knocked.toFloat() / (box.width().toLong() * box.height())
  }

  /** 每格取「该格底像素」的中位 RGB。中位而不是均值：一两个刻错的字形像素不该拖动整格底色。 */
  private fun fitField(pixels: IntArray, w: Int, box: Rect, g: Int, s: Scratch) {
    val cells = g * g
    s.cellN.fill(0, 0, cells)
    s.fieldHist.fill(0, 0, cells * 768)
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] == 0.toByte()) continue
        val p = pixels[i]
        // 透明像素的 RGB 是垃圾（非预乘、未定义），不参与定色；它算底，但不算「底是什么颜色」。
        if (Color.alpha(p) < A_SOLID) continue
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val at = (cy * g + cx) * 768
        s.fieldHist[at + ((p shr 16) and 0xff)]++
        s.fieldHist[at + 256 + ((p shr 8) and 0xff)]++
        s.fieldHist[at + 512 + (p and 0xff)]++
        s.cellN[cy * g + cx]++
      }
    }
    for (k in 0 until cells) {
      val n = s.cellN[k]
      if (n == 0) continue
      s.cellR[k] = median(s.fieldHist, k * 768, n).toShort()
      s.cellG[k] = median(s.fieldHist, k * 768 + 256, n).toShort()
      s.cellB[k] = median(s.fieldHist, k * 768 + 512, n).toShort()
    }
    fillEmptyCells(g, s)
    // 格子色一旦定了，就把 Lab 也一次性算出来（每轮 ≤ cells 次转换）：距离是每像素都要查的，
    // 放在像素循环里转就是每像素多两次色度学转换。
    for (k in 0 until cells) {
      val rgb = (0xff shl 24) or
        ((s.cellR[k].toInt() and 0xff) shl 16) or
        ((s.cellG[k].toInt() and 0xff) shl 8) or
        (s.cellB[k].toInt() and 0xff)
      s.fL[k] = labChannel(rgb, 0)
      s.fA[k] = labChannel(rgb, 1)
      s.fB[k] = labChannel(rgb, 2)
    }
  }

  /** 没有底像素的格子（图形整个盖住那一格）由邻格平均补上，迭代到不再有新增。 */
  private fun fillEmptyCells(g: Int, s: Scratch) {
    val cells = g * g
    var pending = IntArray(cells)
    for (round in 0 until 8) {
      var empty = 0
      for (k in 0 until cells) if (s.cellN[k] == 0) pending[empty++] = k
      if (empty == 0) return
      var filled = 0
      for (idx in 0 until empty) {
        val k = pending[idx]
        val cx = k % g
        val cy = k / g
        var r = 0L; var gg = 0L; var b = 0L; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val nx = cx + dx
          val ny = cy + dy
          if (nx !in 0 until g || ny !in 0 until g) continue
          val j = ny * g + nx
          if (s.cellN[j] == 0) continue
          r += s.cellR[j].toInt() and 0xffff
          gg += s.cellG[j].toInt() and 0xffff
          b += s.cellB[j].toInt() and 0xffff
          n++
        }
        if (n == 0) continue
        s.cellR[k] = (r / n).toShort()
        s.cellG[k] = (gg / n).toShort()
        s.cellB[k] = (b / n).toShort()
        s.cellN[k] = -1 // 补出来的格：不再当「有实测数据」，也不再去补别人（避免整片漂移）
        filled++
      }
      if (filled == 0) break
    }
    // 还是空的格（整张同色这种极端情况）退回中灰：宁可差值偏大也不让采样出黑色空洞。
    for (k in 0 until cells) if (s.cellN[k] == 0) {
      s.cellN[k] = -1
      s.cellR[k] = 128; s.cellG[k] = 128; s.cellB[k] = 128
    }
  }

  // ─────────────────────────── 形状体检（两支共用） ───────────────────────────

  /**
   * 数出「这块掩码像不像一个图形」，并按需修整。**就地**改 [argb]（桥出来的像素要写进去）。
   *
   * @param bridge 只补 **1 像素**的缝：一个透明像素在相反两个方向上都夹着图形才算图形。
   *   不用闭运算（膨胀+腐蚀）—— 它连 2 像素的空隙一起填满，就是真机报的「缝隙小的部分粘连在
   *   一块」；不膨胀所以不变粗，不腐蚀所以不削尖角。桥出来的像素**继承邻格里最浓的那一个**，
   *   一律给实心等于在斜坡上又画一圈硬边。
   * @param despeckle 按连通块擦掉太小的孤立点（渐变底切出来的杂色）。透明底那一支不做：那是
   *   设计者画好的形状，不需要我们替它决定哪些笔算数。
   */
  private fun measure(
    argb: IntArray, w: Int, h: Int, box: Rect, s: Scratch, bridge: Boolean, despeckle: Boolean,
  ): Stats {
    val total = w * h
    val boxArea = box.width().toLong() * box.height()
    val mark = s.mark
    val queue = s.queue
    val visited = s.visited
    var kept = 0
    var soft = 0
    for (y in 0 until h) {
      val row = y * w
      val inBoxY = y in box.top until box.bottom
      for (x in 0 until w) {
        val i = row + x
        val a = Color.alpha(argb[i])
        if (a >= A_SOLID) {
          mark[i] = 1; kept++
        } else {
          mark[i] = 0
          // ⚠ `淡` 只按**内容外接框**算。自适应前景层四周那圈安全区留白里全是抗锯齿的淡值，
          //   按整张画布数会数出比 1 还大的比例 —— 真机上就打出过 `淡=1.47` 这种不可能的数。
          if (a > 0 && inBoxY && x in box.left until box.right) soft++
        }
      }
    }
    if (bridge) {
      val grown = s.grown
      System.arraycopy(mark, 0, grown, 0, total)
      for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        if (mark[i] != 0.toByte()) continue
        val joined = (mark[i - w] != 0.toByte() && mark[i + w] != 0.toByte()) ||
          (mark[i - 1] != 0.toByte() && mark[i + 1] != 0.toByte()) ||
          (mark[i - w - 1] != 0.toByte() && mark[i + w + 1] != 0.toByte()) ||
          (mark[i - w + 1] != 0.toByte() && mark[i + w - 1] != 0.toByte())
        if (!joined) continue
        grown[i] = 1
        var inherit = 0
        for (k in 0 until 8) {
          val v = Color.alpha(argb[(y + NB_DY[k]) * w + x + NB_DX[k]])
          if (v > inherit) inherit = v
        }
        if (inherit < A_SOLID) inherit = A_SOLID
        argb[i] = (inherit shl 24) or 0x00FFFFFF
        kept++
      }
      System.arraycopy(grown, 0, mark, 0, total)
    }

    var components = 0
    var largest = 0
    var smallPixels = 0
    visited.fill(0, 0, total)
    val minSpeck = maxOf(8, total / MIN_SPECK_DIVISOR)
    val substantial = minSpeck * 8
    for (start in 0 until total) {
      if (mark[start] == 0.toByte() || visited[start] != 0.toByte()) continue
      var head = 0
      var tail = 0
      visited[start] = 1
      queue[tail++] = start
      while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val y = i / w
        for (dy in -1..1) {
          val ny = y + dy
          if (ny !in 0 until h) continue
          for (dx in -1..1) {
            val nx = x + dx
            if (nx !in 0 until w) continue
            val j = ny * w + nx
            if (mark[j] != 0.toByte() && visited[j] == 0.toByte()) { visited[j] = 1; queue[tail++] = j }
          }
        }
      }
      components++
      if (tail >= minSpeck) {
        if (tail > largest) largest = tail
        if (tail < substantial) smallPixels += tail
      } else if (despeckle) {
        for (k in 0 until tail) {
          val j = queue[k]
          if (Color.alpha(argb[j]) >= A_SOLID) kept-- else soft--
          argb[j] = 0
          mark[j] = 0
          visited[j] = 0 // 已擦掉的格不再是任何一块的一部分
        }
        components--
      } else {
        if (tail > largest) largest = tail
      }
    }

    // 洞 = 内容框内、不接触框边的那片「非图形」区域。判「这不就是一整块板」只能用它：
    // 一整块板里一个洞都没有，而字形总有字腔。
    var holePixels = 0
    visited.fill(0, 0, total)
    for (y in box.top until box.bottom) for (x in box.left until box.right) {
      val start = y * w + x
      if (mark[start] != 0.toByte() || visited[start] != 0.toByte()) continue
      var head = 0
      var tail = 0
      var touchesBorder = false
      visited[start] = 1
      queue[tail++] = start
      while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val yy = i / w
        if (x == box.left || x == box.right - 1 || yy == box.top || yy == box.bottom - 1) touchesBorder = true
        for (dy in -1..1) {
          val ny = yy + dy
          if (ny !in box.top until box.bottom) continue
          for (dx in -1..1) {
            val nx = x + dx
            if (nx !in box.left until box.right) continue
            val j = ny * w + nx
            if (mark[j] == 0.toByte() && visited[j] == 0.toByte()) { visited[j] = 1; queue[tail++] = j }
          }
        }
      }
      if (!touchesBorder) holePixels += tail
    }
    return Stats(
      kept = kept,
      soft = soft,
      components = components,
      largest = if (kept > 0) largest.toFloat() / kept else 0f,
      smallShare = if (kept > 0) smallPixels.toFloat() / kept else 0f,
      hole = holePixels.toFloat() / boxArea,
    )
  }

  private val NB_DX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
  private val NB_DY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

  // ─────────────────────────── 几何与小工具 ───────────────────────────

  /** 非透明像素的外接矩形（阈值 [A_BOX]，比「算不算图形」的 [A_SOLID] 宽）。 */
  private fun contentBounds(pixels: IntArray, w: Int, h: Int): Rect? {
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if (Color.alpha(pixels[row + x]) >= A_BOX) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
  }

  /** 内容外接框里有多少是不透明的 —— 判「这张图有一整块底」的那一个数。 */
  private fun opaqueShare(pixels: IntArray, w: Int, box: Rect): Float {
    var solid = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        if (Color.alpha(pixels[row + x]) >= A_SOLID) solid++
      }
    }
    return solid.toFloat() / (box.width().toLong() * box.height())
  }

  private fun median(hist: IntArray, at: Int, n: Int): Int {
    val half = (n + 1) / 2
    var run = 0
    for (i in 0 until 256) {
      run += hist[at + i]
      if (run >= half) return i
    }
    return 255
  }

  private fun percentile(hist: IntArray, n: Int, pct: Int): Int {
    if (n <= 0) return 0
    val want = maxOf(1, (n.toLong() * pct / 100).toInt())
    var acc = 0
    for (i in hist.indices) {
      acc += hist[i]
      if (acc >= want) return i
    }
    return hist.lastIndex
  }

  /** ⚠ 必须显式 [Locale.ROOT]：不指定时 `String.format` 跟系统语言走，小数点会变逗号。 */
  private fun pct(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

  /** `淡` 那一层的占比：分母必须是内容框，不是整张画布（见 [measure] 里那条注释）。 */
  private fun softShare(st: Stats, box: Rect): Float =
    st.soft.toFloat() / (box.width().toLong() * box.height())
}
