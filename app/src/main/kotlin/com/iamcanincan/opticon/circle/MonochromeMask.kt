package com.iamcanincan.opticon.circle

import android.graphics.Color
import android.graphics.Rect
import java.util.Locale
import kotlin.math.sqrt

/**
 * 强制取色的合成模型：一张图标 → 一条 alpha 斜坡。
 *
 * 纯数组进、纯数组出 —— 不碰 Drawable、不碰 Bitmap，所以判据台（`_harness/`）跑的是**这一份**
 * 代码而不是它的副本；位图与 drawable 的事全在 [MonochromeSynth]。
 *
 * ## 为什么只剩一个量
 * 这里先后有过四支竞价（alpha 轮廓 / 细节 / 三层刻底 / 亮度 Otsu），后来收成两支（刻底 /
 * 透明底）再加一级明度兜底。病根不是阈值没调好，是**每一支量的本来就不是同一件事**，
 * 于是要靠一串比较来决定谁赢：`beatsAlpha` / `plateLike` / `boxFill` / `alphaIsPlate`……
 * README 记的那七次裁定，六次是在决定这条全局线画在哪、哪一支先发言。
 *
 * 现在整条链只有**一个浓度量** [departure]、**一个原语应用两遍**（[groundPass] / [bodyPass]），
 * 两支与那一级兜底都不是被"删掉"的，是**同一个式子在三个 β 上的样子**：
 *
 *     dep = a × [ (1−β) + β × min(ΔE, EMAX) / EMAX ]        0..255
 *
 *  a = 这一像素自己的覆盖率，β = **参照自己的**覆盖率，ΔE = 与参照色的 CIELAB 距离。
 *  · β=1 参照是一整块实心底 → dep 就是「离底多远」—— 原来的「刻底」；
 *  · β=0 参照是「没有东西」→ dep 退成覆盖率 —— 原来的「透明底」，但**是被算出来的**，
 *    不是照抄图标自带的 alpha（所以柔光底板那类不再需要一条绝对 alpha 线去拦）；
 *  · 0<β<1 → 连续过渡，一张带半透明底板的前景层两头都不误判；
 *  · 一整块底把全框都吞了（整张插画，根本没有"底"这个区域）→ 参照仍然是**局部的** 8×8 色场，
 *    量出来就是「离自己周围多远」= 局部对比 = 它自己的明暗层次 —— 原来的第三支。
 *
 * 两遍之外的三件事也都只有一份：**底色场**（[FIELD_GRID] 的格子，每格取该格参照像素的中位色，
 * 空格由邻格松弛补齐，取值按「离它最近的那块参照」传播而不是按格子 —— 见 [propagateField]）、
 * **两点标定**（每一遍各自的零点与实心点，全从这张图自己的分布量）、**明度序**（同一距离上
 * 一暗一亮两块被 |ΔE| 撞成一个值，按「比参照暗还是亮」排开，参照色按 β 在两遍之间加权）。
 *
 * 于是「一整块色板」在这条路上**不是被闸门拦掉的，是产生不出来**：底板与自己比必然差 0，归一
 * 之后必然透明。要涂满整张，得先让整张都不是它自己的底 —— 定义上的矛盾。
 *
 * ⚠ 判据一律**先裁到内容外接框再量**：自适应前景层四周那圈安全区留白不是背景，带着它量会把满幅
 * 不透明的一块底误判成「透明底 + 图形」，压出来就是那块底的轮廓 = 一整块色。这是真机「淘宝变成
 * 大色块」的病根，口径不能动。
 *
 * ## 交出去的就是最终答案
 * 模型之外不再做任何浓度决定 —— 旧版 `MonochromeSynth.toAlphaMask` 里那套「内部一律钳实心 +
 * 边缘保底」一并删掉。判据算出来的斜坡就是要上屏的东西；两头各调一次必然对不上，上一版「日志
 * 说的和屏幕上不是一张图」就是这么来的。同理**永不为 null**：返回 null 是把这张图交给桌面自己
 * 那张算错极性的遮罩（`MonochromeIconFactory`），而"这张图只有一层"不是错误，是事实。
 */
internal object MonochromeMask {

  // ───────────────────────────── 口径 ─────────────────────────────

  /** 内容外接框：alpha 到这一点就算「这里画了东西」。取 8 不取 128 —— 柔光与投影也是内容。 */
  private const val A_BOX = 8

  /** 「这块像素算不透明」，也是形状统计的口径 —— 与全仓 `MIN_SHAPE_ALPHA` 同一条线。 */
  private const val A_SOLID = 128

  /** 碎渣下限：连通块小于 `总面积 / MIN_SPECK_DIVISOR`（且不少于 8 像素）就擦掉。
   *  按面积给比例而不是写死像素数 —— 出图尺寸跟着桌面要的大小变（本机 192 / 384 两档）。 */
  private const val MIN_SPECK_DIVISOR = 4096

  /** 边界取样带的宽度 = 框短边 / [RING_BAND_DIV]，夹在 2..8 像素。底只从这条带里找。 */
  private const val RING_BAND_DIV = 24

  /** 底色家族的个数上限：内容框四个角，最多四块拼底。 */
  private const val CANDIDATE_K = 4

  /** 候选之间的互异线：两个候选差不到它，就只算一个颜色（ΔE76 真值）。 */
  private const val CANDIDATE_DISTINCT = 18

  /** 边界带上至少有这么多是实心的，才谈得上"四个角上那块底"（见 [cornerFamilies]）。 */
  private const val BAND_SOLID_MIN = 85

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

  /** 实心参照：形层像素距离的这一个分位映射到 alpha 255（镂空那一步还在用）。 */
  private const val INK_REF_PCT = 85

  /**
   * 明度序的幅度（占原浓度的百分比）与归一跨度（形层自身 L\\* 的这两个分位之差）。
   *
   * 比底暗的往上、比底亮的往下，**按这张图自己的明度跨度成比例**，所以同一距离上有多少
   * 层就能分出多少层，不是"暗/亮"两档。上限不对称是有理由的：白字在彩色底上是最常见的
   * "亮侧实心"，往下压狠了就是他报过的「主题色发浅」。乘法保住了"底必为 0"这条性质。
   */
  private const val SPLIT_UP = 20
  private const val SPLIT_DOWN = 8

  /** 明度那一条斜坡的端点分位，与"底占低到多少 / 高到多少时明度整个接管浓度"（见 [build] 里
   *  的那一个 q：低到没多少东西是底、高到洪泛把整框都吞了，两种都是"没有可用的参照"）。 */
  private const val LUM_LOW_PCT = 5
  private const val LUM_HIGH_PCT = 95
  private const val LUM_TAKEOVER = 0.5f
  private const val LUM_SWALLOW = 0.97f

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
   * @param mask 白 + alpha 的掩码（连续斜坡）。**只有「这张图压根没有像素」两种情况才会交空白**，
   *   其余一律交出一张图 —— null 的语义不是"没做"，是把这张图交给桌面那张算错极性的遮罩。
   * @param boxFill 掩码（alpha ≥ [A_SOLID]）占**内容外接框**的比例。
   * @param soft 只到 alpha < [A_SOLID] 的那一层占框比例 —— 「细节有没有留下来」看它。
   * @param report 一行判据数字。每个数都是**模型自己决策吃的那一个**，不是事后复算 ——
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
    /** 第二块直方图：距离与明度要同时统计（明度序的跨度归一用它）。 */
    var hist2 = IntArray(DBUCKETS)
    var fieldHist = IntArray(0)
    var cellR = ShortArray(0)
    var cellG = ShortArray(0)
    var cellB = ShortArray(0)
    var cellN = IntArray(0)
    /** 每格参照像素的个数与 alpha 之和 —— 出 [fCov]（原语里的那一个 β）。 */
    var cellTot = IntArray(0)
    var cellSum = IntArray(0)
    /** 每格参照色的 Lab（×64）与覆盖率 β —— 距离与浓度都直接查它们，不每像素现算转换。 */
    var fL = IntArray(0)
    var fA = IntArray(0)
    var fB = IntArray(0)
    var fCov = IntArray(0)
    /** 每个像素"背后那块底"是哪个格子（[propagateField] 的产物）；-1 = 还没传到。 */
    var ref = IntArray(0)
    /** 第一遍量完就固化的三样：这一像素的参照覆盖率 β、参照亮度（×64）、第二遍的离主体距离。
     *  固化是因为第二遍要复用 [fL] / [ref] —— 不复制出来就没有"背后的底"可查了。 */
    var beta = ShortArray(0)
    var gRefL = ShortArray(0)
    var off = ShortArray(0)
    /** 第二遍的参照集：墨里压在底上的那一块 = 图形的外壳。 */
    var body = ByteArray(0)

    var grid = 0
    /** 内容框边界带里实心占多少（0..255）——「这张图到底有没有一块底」的那一个数，进日志。 */
    var bandSolid = 0
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
        body = ByteArray(total)
        beta = ShortArray(total); gRefL = ShortArray(total); off = ShortArray(total)
      }
      if (cellR.size < cells) {
        cellR = ShortArray(cells); cellG = ShortArray(cells); cellB = ShortArray(cells)
        cellN = IntArray(cells); cellTot = IntArray(cells); cellSum = IntArray(cells)
        fL = IntArray(cells); fA = IntArray(cells); fB = IntArray(cells); fCov = IntArray(cells)
        fieldHist = IntArray(cells * 768)
      }
    }
  }

  private val pool = ThreadLocal.withInitial { Scratch() }

  // ───────────────────────────── 主流程 ─────────────────────────────

  /**
   * 一遍参照的产物：**两点标定**（都按这张图自己的分布量，不是绝对值）与几个进日志的数。
   *
   * @param zero dep 的零点 = 参照自己的散度（p[TOL_PCT]）→ 浓度 0
   * @param solid dep 的实心点 = 工作集另一侧的 p[INK_REF_PCT] → 浓度 255
   * @param span solid − zero，夹在 [DEP_MIN_SPAN] 以上（整张没有对比时它就是除数）
   * @param share 参照占内容框的比例
   * @param meanCov 参照在工作集上的平均覆盖率 β —— 「这一张的浓度是颜色给的还是覆盖率给的」
   */
  private class Pass(
    var zero: Int = 0,
    var solid: Int = 255,
    var span: Int = 255,
    var share: Float = 0f,
    var meanCov: Int = 0,
    /** 这一遍的结果最多能起作用多少（0..255）。第二遍用它管住"能降多少"，见 [bodyPass]。 */
    var weight: Int = 255,
    var families: Int = 0,
  )

  /**
   * 把一张光栅化的图标**合成**成单色层。
   *
   * 只有一个量、应用两遍 —— 这就是"没有分支"的意思。
   *
   * **量**：[departure] —— `dep = a × [ (1−β) + β × min(ΔE,EMAX)/EMAX ]`，0..255。
   *  a = 这一像素自己的覆盖率；β = **参照自己的**覆盖率；ΔE = 与参照色的 CIELAB 距离。
   *  参照是透明的（β=0）→ dep 退成覆盖率；参照是一整块实心底（β=1）→ dep 就是「离参照多远」；
   *  中间连续过渡。所以「这张图属于哪一类」不再是模型要问的问题：
   *  · 一张透明底上的白色剪影 → β=0 → 轮廓**被算出来**，不是照抄图标自带的 alpha；
   *  · 一张带柔光底板的（β 介于两者之间）→ 底板按它自己的透明度压回去，不留一条绝对 alpha 线；
   *  · 一张整幅插画（根本没有"底"这个区域，四角也是画的一部分）→ 参照仍然是**局部的**
   *    8×8 色场，量出来的就是「离自己周围多远」= 局部对比 = 它自己的明暗层次。
   *  过去这三支各自一支、各一套阈值、谁赢由一串比较决定；现在它们是同一个式子的三个 β。
   *
   * **两遍**：同一个式子，只是换参照，而两次的参照都是同一句话问出来的 ——
   *  「与这一遍的工作集**边界连通**的那块色与覆盖率」：
   *  · 第一遍参照 = 与内容框边界带连成一片的那块 = **底** → dep 定「该有多实」（[groundPass]）；
   *  · 第二遍参照 = 第一遍剩下的那一半里、压在底上的那块 = **主体** → dep 定「图形内部的
   *    第二层降多少」（[bodyPass]）。贴着底的那一半不算第二层，它是图形的组成部分
   *    （红蓝双色 logo 的另一半、两色拼接的图标）—— 这个区分只有连通性能给，颜色给不了。
   *
   * 两遍之外只剩三件配套的事，都不引入新判据：底色场（[fitField] + [propagateField] +
   * [absorbSeams]）、每遍各自的两点标定、明度序（同一距离上一暗一亮两块撞成一个值，按「比参照
   * 暗还是亮」把它们排开；参照色取两遍按 β 加权后的那一个，见 [refLight]）。
   *
   * ⚠ **永不为 null**。返回 null 在功能上不是"什么都不做"，是把这张图交给桌面那张自己生成的
   *   遮罩（`MonochromeIconFactory`，极性还会算反的那条路）—— 而"这张图本来就只有一层"不是
   *   错误，是事实，那种图的正确答案就是它自己该有的样子：一块平色上屏本来就是一块平色。
   *   所以这里没有"够不够格被合成"这种闸，只有**量出来是多少就是多少**。
   */
  fun build(pixels: IntArray, w: Int, h: Int): Output {
    val total = w * h
    if (total <= 0) return Output(IntArray(0), 0f, 0f, 0f, 0, 0f, 0f, "尺寸非法 → 空层")
    val box = contentBounds(pixels, w, h)
      ?: return Output(IntArray(total), 0f, 0f, 0f, 0, 0f, 0f, "整张透明 → 空层")
    val boxArea = box.width().toLong() * box.height()
    val band = (minOf(box.width(), box.height()) / RING_BAND_DIV).coerceIn(2, 8)
    val g = if (minOf(box.width(), box.height()) >= FIELD_MIN_SIDE) FIELD_GRID else FIELD_GRID_MIN
    val s = pool.get()
    s.grid = g
    s.ensure(total, g * g)

    // 0) 每个像素的 Lab 只算一次：后面每一轮洪泛、每一次距离都只是查表 + 一次开方。
    for (i in 0 until total) {
      val p = pixels[i]
      s.labL[i] = labChannel(p, 0).toShort()
      s.labA[i] = labChannel(p, 1).toShort()
      s.labB[i] = labChannel(p, 2).toShort()
    }

    // 1) 两遍参照。顺序固定：第二遍要用第一遍剩下的那一半当工作集。
    val ground = groundPass(pixels, w, h, box, band, g, s)
    val body = bodyPass(pixels, w, h, box, g, s)

    // 2) 明度序的两个刻度，都从这张图自己的分布量：
    //    · `amp` = |ΔL| 的 p[LAYER_AMP_PCT] —— 同一距离上把两侧排开时要按多大跨度归一；
    //    · `lumLo/lumHi` = **有符号** ΔL 的 p[LUM_LOW_PCT]/p[LUM_HIGH_PCT] —— 一整张没有底的图
    //      （插画、照片底）唯一还有信息的轴就是"比周围暗还是亮"，这两个分位就是那条斜坡的端点。
    //      端点取分位而不是最值：一条 JPEG 噪点线、一块高光不该决定整张图的明暗标尺。
    s.hist.fill(0)
    s.hist2.fill(0)
    var ampN = 0
    var lo = 0
    var hi = 0
    var spread = 1
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte()) continue
        val dev = refLight(i, s) - (s.labL[i].toInt() and 0xffff)
        s.hist[(if (dev < 0) -dev else dev).shr(6).coerceAtMost(DBUCKETS - 1)]++
        s.hist2[(dev.shr(6) + 128).coerceIn(0, DBUCKETS - 1)]++
        ampN++
      }
    }
    val amp = maxOf(
      if (ampN > 0) percentile(s.hist, ampN, LAYER_AMP_PCT) shl 6 else 0,
      LAYER_MIN_AMP shl 6
    )
    if (ampN > 0) {
      lo = (percentile(s.hist2, ampN, LUM_LOW_PCT) - 128) shl 6
      hi = (percentile(s.hist2, ampN, LUM_HIGH_PCT) - 128) shl 6
      spread = (hi - lo).coerceAtLeast(1 shl 6)
    }
    // 浓度由哪一条给，只看「有多少东西真的是底」这一个数，而且它**两头都要退**：
    //  · 底占高（红底白方块 0.89）→ 参照就是一块板，距离项是主信息，明暗只在同一个距离上把
    //    两侧排开一点（±[SPLIT_UP]/−[SPLIT_DOWN]%），q=0，一切与过去按 ΔE 标定等价；
    //  · 底占太低（整张插画，四角也是画，参照只占 7%）→ 每个像素离参照都远，距离项整个饱和，
    //    能把画面排开的只剩明暗；
    //  · 底占太高（≈1.00，洪泛把整框都吞了 —— 王者荣耀那种满幅插画每一块都跟邻域像）→
    //    参照是"整张图"，等于没有参照，距离项给不出任何形状（真机重放里那张脸整个消失了，
    //    屏幕上就是他报的「一块白」）。
    // 两端都是"没有可用的底"，所以同一个 q 从两头往上走。中间连续。
    // ⚠ 还要乘上 β（参照自己的覆盖率 [Pass.meanCov]）：透明底图标的"底占"也低，但那是
    //  **参照本来就没有东西**，覆盖率恰恰是唯一有信息的轴（不乘就会把剪影洗成一块灰）；
    //  区分这两者的就是 β —— 透明底 β≈0，插画 β=255。
    val q = (maxOf(
      1f - ground.share / LUM_TAKEOVER,
      (ground.share - LUM_SWALLOW) / (1f - LUM_SWALLOW)
    ).coerceIn(0f, 1f) * ground.meanCov / 255f * 100).toInt()
    // 试过并否掉的第三条路（别再走一遍）：饱和段里有 ≥2 块竞争时，把明暗这条轴的
    // ±up/down 拉宽。实测反而更糟：四块从 201/221/227 掉到 143/157/162 —— 整段一起被压淡，
    // 间隔一点没开。原因是这条轴根本分不开它们：**白、黄、绿相对深蓝底都是"更亮"**，
    // u 的符号与大小都差不多，真正的区别在色相，而单色墨没有色相这一维。
    // 要在饱和段里排序只能另找一维（候选：按第二遍的 `off` —— 离主体的距离 —— 在段内全排序，
    // 而不是像现在这样只降档）。
    val up = SPLIT_UP * (100 - q) / 100
    val down = SPLIT_DOWN * (100 - q) / 100

    // 3) 出图：距离与明暗两条按 q 混成浓度，第二遍降层次。
    //    **第二遍能降多狠，只问一句：被降的那一层，有没有比留下来的那层更大？**
    //    第二遍的前提是"壳是主体、比它亮的只是第二层（高光、镂空）"。这个前提会破：
    //    云闪付与淘宝闪购的白色主字相对深蓝/橙底就是"更亮"，于是**主体自己被当成第二层**
    //    压掉四成 —— 实测主字只落在 89~143/255，他报的「细节不足、过渡不自然」就是这个。
    //    判据不看"壳占画面多少"（试过：小洞本来就该刻掉，按面积一刀切正好把它否了），
    //    只看这两层的相对大小：谁大谁才是主体。留不下多少墨的一层，就不该去降别人。
    var knockCand = 0
    var keep = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) < A_SOLID) continue
        if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) > 0 &&
          (s.off[i].toInt() and 0xffff) > body.zero
        ) knockCand++ else keep++
      }
    }
    val hollow = (HOLLOW * body.weight / 255 *
      (if (knockCand == 0) 1f else (keep.toFloat() / knockCand).coerceAtMost(1f))).toInt()
    val out = IntArray(total)
    var knocked = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val dep = s.dist[i].toInt() and 0xffff
        val ref = refLight(i, s)
        // ⚠ 感知曲线只加在**距离**那一条上，混完不再过一次。桌面拿 alpha 当不透明度，ΔE 距离
        //   线性排会把大半图形留在六成浓度以下（观感就是「主题色怎么这么浅」），所以要抬中低段；
        //   而 L\\* 本身就是感知量，再抬一次就把一张插画洗成一块实色（判据台上 多色插画 量出来
        //   框内 0.91 —— 中间调全被推到实心那一侧去了）。
        val td = CURVE[((dep - ground.zero) * 255 / ground.span).coerceIn(0, 255)]
        val u = ref - (s.labL[i].toInt() and 0xffff)
        val tl = ((u - lo) * 255 / spread).coerceIn(0, 255) * Color.alpha(pixels[i]) / 255
        // ⚠ 明暗这一条**必须乘上覆盖率**，距离那条本来就是 `a × […]`（见 departure）。
        //   透明的像素没有"亮度"可言：它的 RGB 未定义，读出来是黑，于是 `u = 参照L − 0` 直接顶格，
        //   按 q 混进去就是**在空气上上墨**。真机抖音量出来：模型上墨的像素里 51.4% 的源 alpha
        //   恰好是 0（最浓 68/255），屏幕上就是他报的那块灰框 —— 而它的形状是矩形，因为那是
        //   内容外接框。判据台新增一条「全透明的像素不许上墨」钉住它。
        var a = (td * (100 - q) + tl * q) / 100
        if (a == 0) continue
        // 比参照暗的往上、亮的往下，**按这张图自己的跨度成比例** —— 不是"暗/亮"两档。
        // ⚠ 上限不对称是有理由的：白字在彩色底上是最常见的"亮侧实心"，往下压狠了就是他报过的
        //   「主题色怎么这么浅」。q 上去之后这两侧自动退场：那时明暗已经是浓度本身了。
        a = (a + a * (u * 100 / amp).coerceIn(-down, up) / 100).coerceIn(0, 255)
        // 第二遍只降**比主体亮**的那一侧（镂空、高光、白 logo 上的洞）。比主体暗的那些是图形
        // 自己的暗部，降它等于把细节擦了 —— 与上面那条明度序同一个极性。
        if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) > 0) {
          val off = s.off[i].toInt() and 0xffff
          val k = CURVE[((off - body.zero) * 255 / body.span).coerceIn(0, 255)]
          if (k > 0) {
            val b = a * (25500 - hollow * k) / 25500
            if (b != a) knocked++
            a = b
          }
        }
        if (a > 0) out[i] = (a shl 24) or 0x00FFFFFF
      }
    }

    // 4) 交出去之前把墨量抬到满阶：最深的必须真的最深（他要「图标上必须有系统最深和最浅的
    //    取色」）。抬法是"只往上、锚在 0"的一条直线，理由与两次失败都在 [stretchLut] 上。
    val stretch = stretchLut(out, total)
    stretch?.let { s2 ->
      for (i in 0 until total) {
        val a = out[i] ushr 24
        if (a != 0) out[i] = (s2.first[a] shl 24) or 0x00FFFFFF
      }
    }

    // 5) 形状体检。**修不修形状看浓度是谁给的**：颜色给的（β 高）才可能有"切底切出来的杂点"，
    //    覆盖率给的（β≈0）那些淡值全是设计者自己画的柔光与抗锯齿，擦掉就是一圈锯齿、桥起来
    //    就是给一条笔画凭空加粗。（旧版这里是两支各写死的 `bridge=/despeckle=` 常量。）
    val colourDriven = ground.meanCov >= 128
    // 量的是**交出去那一张**：分档 = 几块 / 落在几档，括号里是每块的浓度。
    // ⚠ 这个数现在**只是诊断**，没有配套的修法 —— 曾按它铺过两趟单调重排（dep 轴 + 浓度轴），
    // 平均档数 2.66→2.94、完全分开的 8/32→12/32，看着是进步；但同一改动在真图上画出过
    // **一块看得见的灰方框**（azure：盾牌外面那圈柔光被当成"一个部分"，重排就发给它一整格
    // 浓度，于是留白变成实心方块，还带一条横带）。A/B 对过：撤掉保底仍在、撤掉重排才消失。
    // 一笔买卖换不来一个方框，所以修法撤了、数留着 —— 下一步的锚点必须先把"柔光/留白"
    // 排除在"部分"之外（按面积占比与离底距离双重门槛），而不是先铺匀再找补。
    val sep = separation(out, w, h, box, s)
    val st = measure(out, w, h, box, s, bridge = colourDriven, despeckle = colourDriven)
    val fill = st.kept.toFloat() / boxArea
    return Output(
      out, fill, st.hole, st.largest, st.components, st.smallShare, softShare(st, box),
      "带占=${s.bandSolid * 100 / 255} 底占=${pct(ground.share)} 族=${ground.families} 零=${ground.zero} 实=${ground.solid} 端=${lo shr 6}/${hi shr 6} " +
        "壳占=${pct(body.share)} 层比=$keep/$knockCand 降=${pct(knocked.toFloat() / boxArea)} 降力=${body.weight} 力度=$hollow 明暗=${q} " +
        "框内=${pct(fill)} cc=${st.components} top=${pct(st.largest)} 小片=${pct(st.smallShare)} " +
        "空洞=${pct(st.hole)} 淡=${pct(softShare(st, box))} 色驱=${ground.meanCov} " +
        "色阶=${stretch?.second ?: "已满"} " +
        "分档=${sep.second}/${sep.first}" +
        (if (sep.third.isEmpty()) "" else "(${sep.third.joinToString(",")})")
    )
  }

  /** 浓度里"颜色给的那一段"最多占多少 β：低于它就不做形状修整（见 [build] 第 4 步）。 */
  private const val COLOUR_DRIVEN = 128

  // ───────────────────── 相邻部分的差别（量的那半边） ─────────────────────
  //
  // 他要的是「相邻部分颜色必须有差别」。这句话现在只是一个观感，改不动 —— 先把它变成一个
  // 会掉下去的数：**这张图上有几个部分，它们被画进了几档浓度**。4 个部分落进 2 档，
  // 就是"糊成一片"；4 个部分 4 档，才是有差别。
  //
  // 为什么按"源色分桶"而不是按像素邻边去量：相邻像素的 ΔE 在抗锯齿的那几像素上本来就大，
  // 把它们算进来，一条真实的边缘斜坡就能把数字填满，看不出任何"部分被合并"。
  // 所以先在源色上归成块（一块 = 图上的一个部分），每块取自己那一片的平均浓度，
  // 再看**块与块**之间分不分得开。底板不参与 —— 底板之间本来就该没有差别（那是
  // 「板-板接缝必须消失」那条性质，判据台钉着它，两件事不能混成一个数）。

  /** 源色分桶的边长（L 每 8、a 与 b 每 32 —— 粗于 ΔE≈12，同一块色的抖动落进同一格）。 */
  private const val SEP_L = 8
  private const val SEP_AB = 32
  private const val SEP_BUCKETS = 13 * 8 * 8

  /** 一块色至少占工作集百分之几才算"图上的一个部分"；少于它的是一圈抗锯齿或一个噪点。 */
  private const val SEP_MIN_SHARE_PCT = 3

  /** 最多看几个部分（按面积取前几），再多就不是"相邻有没有差别"而是数颜色了。 */
  private const val SEP_MAX_PARTS = 6

  /** 两档之间至少差这么多浓度才算看得出是两个部分（255 的 ~12%）。 */
  private const val SEP_MIN_GAP = 30


  /**
   * 把画面按**源色**归成块（一块 = 图上的一个部分），返回每块的平均值，按值升序。
   *
   * [of] 取的是这个像素的哪个量。现在只有一个调用者，取的是**交出去的浓度**（`分档`）；
   * 留着这个参数是因为下一版修法可能要按离底距离重新归块 —— 到那时分块与面积门槛只写一处。
   *
   * 底板不参与：底板之间本来就该没有差别（「板-板接缝必须消失」那条性质钉着它），
   * 把它算进来就等于要求接缝起皱，两件事会互相打架。
   */
  private inline fun partMeans(w: Int, h: Int, box: Rect, s: Scratch, of: (Int) -> Int): IntArray {
    val cnt = IntArray(SEP_BUCKETS)
    val sum = LongArray(SEP_BUCKETS)
    var work = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte()) continue
        work++
        val l = (s.labL[i].toInt() and 0xffff) shr 6
        val a = ((s.labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val b = ((s.labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
        val k = ((l / SEP_L) * 8 + (a / SEP_AB)) * 8 + (b / SEP_AB)
        cnt[k]++
        sum[k] += of(i)
      }
    }
    if (work < 64) return IntArray(0)
    val min = maxOf(64, work * SEP_MIN_SHARE_PCT / 100)
    // 按面积取前 SEP_MAX_PARTS 块：小过这个的既看不见、也不该拿它去要求差别。
    val means = ArrayList<Int>(SEP_MAX_PARTS)
    repeat(SEP_MAX_PARTS) {
      var best = -1
      for (k in 0 until SEP_BUCKETS) {
        if (cnt[k] >= min && (best < 0 || cnt[k] > cnt[best])) best = k
      }
      if (best < 0) return@repeat
      means.add((sum[best] / cnt[best]).toInt())
      cnt[best] = 0
    }
    means.sort()
    return means.toIntArray()
  }

  /**
   * 量「这张图上的部分被画进了几档浓度」。返回 **（部分数, 分开的档数, 每块的浓度升序)** ——
   * 前两个相等才叫"相邻部分有差别"，`4/2` 就是有两次合并。
   */
  private fun separation(out: IntArray, w: Int, h: Int, box: Rect, s: Scratch): Triple<Int, Int, IntArray> {
    val parts = partMeans(w, h, box, s) { (out[it] ushr 24) }
    if (parts.size < 2) return Triple(parts.size, parts.size, parts)
    var levels = 1
    for (j in parts.indices.drop(1)) if (parts[j] - parts[j - 1] >= SEP_MIN_GAP) levels++
    // 每一块的浓度值一起带回来：`2/6` 只说"合并了"，不说**在哪一端**合并的。
    // 全挤在 0 = 底下那道 clamp 把部分擦掉了；全挤在 255 = 顶上那道饱和了。修法不同。
    return Triple(parts.size, levels, parts)
  }

  /** 交付前把墨量拉到满阶用的分位（见 [stretchLut]）。取分位不取最值：
   *  一圈抗锯齿的极值不该决定整张图的色阶。 */
  private const val STRETCH_HI_PCT = 99

  /**
   * 把墨量**只往上抬**的一条直线：`t → t·255/p99`（封顶 255）。这就是"必须有系统最深的取色"。
   *
   * 只抬、不压、不重排，锚在 0 上 —— 这三条都是踩过坑换来的：
   *  · 分段折线按块重排：把"0 → 第一个锚点"整段抬起来，真图上多出一块灰方框（已撤，见 `分档` 那节）；
   *  · 连低端一起拉（`[p1..p99] → [22..255]`）：判据台四条同时红 —— 一条 2px 细线本来就占不到
   *    墨量的 1%，按 p1 归一等于把它当成噪点擦掉。**"最浅的一档"不能靠压别的档来给。**
   *  所以这里只保证最深那一头，浅的那一头保持原样：抬不动的（已经铺满）一个像素都不动。
   */
  private fun stretchLut(out: IntArray, total: Int): Pair<IntArray, String>? {
    val hist = IntArray(256)
    var n = 0
    for (i in 0 until total) {
      val a = out[i] ushr 24
      if (a > 0) { hist[a]++; n++ }
    }
    if (n < 64) return null
    val hi = percentile(hist, n, STRETCH_HI_PCT)
    if (hi >= 250) return null                              // 本来就铺满了，一个像素都不许动
    val lut = IntArray(256)
    for (t in 1..255) lut[t] = (t * 255 / hi).coerceIn(t, 255)
    return lut to "p99=$hi→255"
  }

  // ───────────────────────────── 极性判据 ─────────────────────────────
  //
  // 遮罩模式下桌面自己出剪影，它的算法是**亮度当 alpha**（min→max 归一，再叠一条以 128 为中心的
  // 对比度曲线）—— 也就是它假定「亮的就是墨」。深色字形 + 浅色底的图标因此被画成
  // "字形是洞、底色是形"，这就是用户报的那一句「颜色反一下」。
  //
  // 这条假定 2026-09-24 在真机上量定了（`_harness/PolarityCheck` + `_harness/LumaCurve`，
  // 抓 4 对 (原图, 桌面遮罩)：翻译 / azure / 驾考宝典 / 华为应用市场）：
  //   1. 墨量随原图亮度**单调上升**，4 张全中 —— 翻译按 src 亮度分 8 桶，墨依次是
  //      −1/−1/−1/18/79/214/237/255。
  //   2. **透明像素按黑参加归一**，所以"什么都没有"落在最暗端 = 空：azure 圆盘内、盾牌外那片
  //      空气只有 53/255，盾牌本身 113，盾牌里最亮那块 191。
  //   3. 形状外的四个角是 0 —— 桌面在出遮罩**之后**才裁形。所以我们翻的那张 alpha 已经裁过形，
  //      翻它不会把四角填成一坨（这条否掉了"翻不得"的那个顾虑）。
  //
  // 要不要翻，只有一个问题要答：**墨比它的参照亮还是暗**。
  // 暗过一截 → 桌面的假定错了 → 翻；亮着或差不多 → 不翻（不翻 = 桌面的原始行为，安全默认）。
  //
  // "墨"与"参照"都不是新的线：墨 = [groundPass] 算出的 dep 加权（dep 为 0 的底板与留白自动
  // 不投票）；参照 = 底像素的平均亮度，按底的覆盖率 β 与"没有东西"（中灰）混合 —— 与浓度那条
  // 用的同一个 β。透明底上的单色剪影因此也能判：那时参照就是中灰，黑剪影落在暗侧 → 翻。
  //
  // ⚠ **已知判不了的一类**：β≈0（透明底、没有可当参照的底板）时参照退化成中灰 L=50，
  // 于是墨只要正好压在 50 附近就是抛硬币 —— azure 量出来 墨L=51 / 死线=42 → 判"不翻"，
  // 而他要的是翻。这一类要的是"参照换成什么"，不是再加一条分支：见下面那条被试过并否掉的写法。
  //
  // **2026-09-25：这条判据不再接线。**功能回到纯合成 —— 单色层由 [build] 直接生成，
  // 桌面自己那张遮罩压根不产生，所以"翻不翻"这个问题没有了对象。留着 [polarity] 是因为
  // 上面那三条量出来的事实（亮=墨、透明按黑归一、遮罩已裁形）是真机换来的，而判据台
  // （`_harness/SelfTest`）那 6 条极性性质还在钉着它 —— 哪天再走遮罩路，别从看图重新猜一遍。
  //
  // 试过并否掉的替代判据（别再走一遍）：不比"墨 vs 参照"，改量"这张图自己的暗部有多少"
  // （按自身明暗跨度归一的平均离顶距离，阈值 38/255）。它在真机那 4 对上比现在这条对得多，
  // 但**白底黑字判成不翻** —— 白底占九成面积，暗部量自然小，而那恰恰是最该翻的形状。
  // 面积加权的暗部量把"底"和"墨"混成了一个数，等于没答"哪边是图形"。

  /** [polarity] 的产物。[known] = false 时**不要动遮罩**，[report] 是那一句进日志的数。 */
  class Polarity(val invert: Boolean, val known: Boolean, val report: String)

  /** 判据用的降采样边长。384 全幅跑一遍要 20~170ms（真机实测），而极性只需要知道"墨偏哪一侧"，
   *  96 就够 —— 尺寸不变性由判据台那条性质钉着，降采样不会换结论。 */
  private const val POLAR_EDGE = 96

  /** 差多少 L\\* 才算"落在暗侧"。**留死区是因为中间调不该翻**：墨与参照一样亮的图，
   *  桌面的归一化本来就把它排在前面，硬翻只是换一种错法。判不了也走不翻（= 桌面的原始行为）。 */
  private const val POLAR_DEADBAND = 8

  /** "没有东西"的代表亮度（L\\* 50 = 中灰）：透明底那部分参照按它算，见 [polarity]。 */
  private const val L_MID = 50 shl 6

  fun polarity(pixels: IntArray, w: Int, h: Int): Polarity {
    val small = downsample(pixels, w, h, POLAR_EDGE)
      ?: return Polarity(false, false, "极性=降采样不成立，不动遮罩")
    val n = POLAR_EDGE
    val total = n * n
    val box = contentBounds(small, n, n)
      ?: return Polarity(false, false, "极性=整张透明，不动遮罩")
    val s = pool.get()
    val g = if (minOf(box.width(), box.height()) >= FIELD_MIN_SIDE) FIELD_GRID else FIELD_GRID_MIN
    s.grid = g
    s.ensure(total, g * g)
    for (i in 0 until total) {
      val p = small[i]
      s.labL[i] = labChannel(p, 0).toShort()
      s.labA[i] = labChannel(p, 1).toShort()
      s.labB[i] = labChannel(p, 2).toShort()
    }
    val band = (minOf(box.width(), box.height()) / RING_BAND_DIV).coerceIn(2, 8)
    val ground = groundPass(small, n, n, box, band, g, s)

    // 墨的平均亮度 = 按 dep 加权（dep 为 0 的底板与留白自动不投票，不需要再判"哪些算图形"）。
    // 参照的平均亮度同理按**底**加权。两者一比就是"墨落在底的亮侧还是暗侧"。
    var wsum = 0L
    var lsum = 0L
    var gsum = 0L
    var gn = 0
    var visible = 0
    for (y in box.top until box.bottom) {
      val row = y * n
      for (x in box.left until box.right) {
        val i = row + x
        val l = s.labL[i].toInt() and 0xffff
        if (Color.alpha(small[i]) >= A_SOLID) visible++
        if (s.plate[i] != 0.toByte()) {
          gsum += l; gn++
          continue
        }
        val dep = s.dist[i].toInt() and 0xffff
        wsum += dep
        lsum += dep.toLong() * l
      }
    }
    if (visible < total / 64 || wsum == 0L) {
      return Polarity(
        false, false,
        "极性=没有可判的墨(可见 $visible 底 $gn w=$wsum 族=${ground.families} " +
          "带占=${s.bandSolid * 100 / 255} 框=${box.width()}x${box.height()})，不动遮罩"
      )
    }
    // **参照亮度**：底有几分实，就用几分底的亮度；剩下的那份是"没有东西"，
    // 而"没有东西"在桌面上就是那块底板 —— 它既不算亮也不算暗，取中灰 L=50 作代表。
    // 于是透明底上的单色剪影也能判（旧写法按可见像素自己的 p5..p95 量范围，一张纯黑剪影
    // 量出范围为 0 → "判不了" → 不翻 → 屏幕上整个图标消失，正是他报的那一类）。
    val beta = ground.meanCov
    val groundL = if (gn > 0) (gsum / gn).toInt() else L_MID
    val refL = (beta * groundL + (255 - beta) * L_MID) / 255
    val figL = (lsum / wsum).toInt()
    val cut = refL - (POLAR_DEADBAND shl 6)
    val invert = figL < cut
    return Polarity(
      invert, true,
      "极性=${if (invert) "翻" else "不翻"} 墨L=${figL shr 6} 参照L=${refL shr 6} " +
        "(底L=${groundL shr 6} β=$beta) 死线=${cut shr 6} " +
        "底占=${pct(ground.share)} 族=${ground.families}"
    )
  }

  /** 面积平均降采样，**按 alpha 加权**（`getPixels` 给的是非预乘值，直接平均 RGB 会把透明的
   *  垃圾色算进去）。任一通道装不下或尺寸不合法 → null。 */
  private fun downsample(pixels: IntArray, w: Int, h: Int, n: Int): IntArray? {
    if (w <= 0 || h <= 0 || n <= 0) return null
    if (w <= n && h <= n) return if (w == n && h == n) pixels else null
    val out = IntArray(n * n)
    for (j in 0 until n) {
      val y0 = (j.toLong() * h / n).toInt()
      val y1 = (((j + 1).toLong() * h + n - 1) / n).toInt().coerceAtLeast(y0 + 1).coerceAtMost(h)
      for (i in 0 until n) {
        val x0 = (i.toLong() * w / n).toInt()
        val x1 = (((i + 1).toLong() * w + n - 1) / n).toInt().coerceAtLeast(x0 + 1).coerceAtMost(w)
        var a = 0L; var r = 0L; var gg = 0L; var b = 0L; var cnt = 0
        for (y in y0 until y1) {
          val row = y * w
          for (x in x0 until x1) {
            val p = pixels[row + x]
            val pa = Color.alpha(p)
            a += pa; r += pa * ((p shr 16) and 0xff); gg += pa * ((p shr 8) and 0xff)
            b += pa * (p and 0xff); cnt++
          }
        }
        if (a == 0L) { out[j * n + i] = 0; continue }
        val av = (a / cnt).toInt().coerceIn(0, 255)
        // 非预乘回去：均值的是 a·RGB，所以除 a 而不是除 cnt。
        out[j * n + i] = (av shl 24) or
          ((r / a).toInt().coerceIn(0, 255) shl 16) or
          ((gg / a).toInt().coerceIn(0, 255) shl 8) or
          (b / a).toInt().coerceIn(0, 255)
      }
    }
    return out
  }

  private fun fitGround(
    pixels: IntArray, w: Int, h: Int, box: Rect, g: Int, s: Scratch, fams: Int, seed: ByteArray,
  ): Pass {
    val total = w * h
    val boxArea = box.width().toLong() * box.height()


    // 「拟合色场 → 重算距离 → 重新洪泛」跑 [FIELD_ITERS] 轮。第一轮只有一个全局候选色，容差
    // 必须给到能容下整道渐变（ΔE [TOL_COARSE]），代价是把与底相近的字形也圈进去；有色场之后
    // 每格只需容下自己那点抖动，容差自动掉到个位数，那些字形就留下来了。
    // 「拟合色场 → 用同一个 dep 重判连通 → 重新洪泛」跑 [FIELD_ITERS] 轮。
    // ⚠ 洪泛与浓度**必须是同一把尺**。dep 带 β（参照到底有没有颜色），ΔE 不带：用 ΔE 判连通时，
    //   [fillEmptyCells] 给零参照的格补的那个中灰会变成一个"真实的参照色"，而离它 45 个 ΔE 的
    //   **白色剪影正好落在容差上界以内** → 整块图形被当成底吸收（判据台上 透明底白剪影 量出
    //   底占=1.00，极性判据跟着找不到墨）。换成 dep 之后 β=0 的格上 dep 退成覆盖率，实心白像素
    //   恒为 255，容差怎么夹都吸不进去。
    var tol = DEP_COARSE
    passFromFamilies(pixels, w, box, s, TOL_COARSE shl 6)
    // 第一轮之前先洪泛一次：色场是从「与边界连通的底」上拟合的，没有这一步就没有种子像素。
    flood(s, w, box, seed)
    var nGround = 0
    var nInk = 0
    var covSum = 0L
    for (iter in 0 until FIELD_ITERS) {
      if (iter > 0) {
        for (y in box.top until box.bottom) {
          val row = y * w
          for (x in box.left until box.right) {
            val i = row + x
            // 「没有颜色」永远算参照的一部分 —— 参照本来就是"透明"时它当然在参照里。
            s.pass[i] = if (Color.alpha(pixels[row + x]) < A_SOLID ||
              (s.dist[i].toInt() and 0xffff) <= tol
            ) 1 else 0
          }
        }
        flood(s, w, box, seed)
      }
      fitField(pixels, w, box, g, s, s.plate, solidRef = false)
      propagateField(w, h, total, box, s, s.plate)
      // 中缝要在**算距离之前**并进底：它借的是邻格所属的那块底，晚一步就会先被算成"差得远的墨"。
      absorbSeams(w, total, box, s)
      s.hist.fill(0)
      s.hist2.fill(0)
      nGround = 0
      nInk = 0
      covSum = 0L
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          val cell = if (s.ref[i] >= 0) s.ref[i] else 0
          val b = s.fCov[cell]
          // ⚠ 这里存的是**原始 dep**，每一轮的重分类都读它。底的那条"只按覆盖算"的规则不能在这里
          //   应用 —— 一旦把已归入底的像素写成 0，下一轮的 `dist <= tol` 就恒成立，被误吸收的
          //   像素再也回不来（判据台上 三段距离 最近那一档 ΔE 16，第一轮被 TOL_COARSE=18 收进去，
          //   之后每一轮都自己给自己投赞成票，整块就此消失）。那条规则是**出图时**的事。
          val d = departure(pixels[i], i, s)
          s.dist[i] = d.toShort()
          s.beta[i] = b.toShort()
          s.gRefL[i] = s.fL[cell].toShort()
          if (s.plate[i] != 0.toByte()) {
            // 零点只量参照**自己颜色的散度**，不量它本身的透明度：参照是透明的时候颜色那一段
            // 就不存在，把覆盖率算进散度会把零点抬到 76（判据台上 白底柔光 那一圈光晕），
            // 于是淡的那一层整个被当成噪声扣掉，剩下的断环变成 7 个连通块。
            s.hist[departure(pixels[i], i, s, withCoverage = false).coerceAtMost(DBUCKETS - 1)]++
            nGround++
          }
        }
      }
      tol = percentile(s.hist, nGround, TOL_PCT).coerceIn(DEP_TOL_MIN, DEP_TOL_MAX)
    }

    // 出图：底按覆盖重算（实心板与自己比必然差 0，按颜色量它量到的都是噪声与拼底的中缝），
    // 而且只有**贴着墨**的那一圈才算数 —— 淡的像素要么是某条笔画的抗锯齿边，要么是背景的一部分，
    // 这两件事靠"离墨多远"分开，不靠再画一条绝对 alpha 线。（旧版在"透明底"那一支里用
    // `hasSolidNeighbour` 干这件同样的事；两支合一，规则也合一。）
    s.hist2.fill(0)
    nInk = 0
    covSum = 0L
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte()) {
          val b = s.beta[i].toInt() and 0xffff
          s.dist[i] = (if (touchesInk(s.plate, w, box, x, y))
            Color.alpha(pixels[i]) * (255 - b) / 255 else 0).toShort()
          continue
        }
        val d = s.dist[i].toInt() and 0xffff
        s.hist2[d.coerceAtMost(DBUCKETS - 1)]++
        covSum += s.beta[i].toInt() and 0xffff
        nInk++
      }
    }
    val p = Pass()
    p.share = nGround.toFloat() / boxArea
    p.families = fams
    p.zero = (if (nGround > 0) percentile(s.hist, nGround, TOL_PCT) else 0)
      .coerceIn(DEP_TOL_MIN, DEP_TOL_MAX)
    p.solid = if (nInk > 0) percentile(s.hist2, nInk, INK_REF_PCT) else p.zero
    p.span = (p.solid - p.zero).coerceAtLeast(DEP_MIN_SPAN)
    p.meanCov = if (nInk > 0) (covSum / nInk).toInt() else 0
    return p
  }
  /**
   * **第一遍：参照 = 这一像素背后那块底。**
   *
   * 三个假设按顺序问，取**第一个留下真图形**的 —— figure/ground 本来就不是适定的，任何做法
   * 都得验一次结论，区别只在于验的是数字还是手感：
   *  · **H1 内容框四角上的颜色** —— 老式满幅图、以及底板画到边的自适应图标；
   *  · **H2 内容自己轮廓上的颜色** —— 圆盘 / 圆角方**整块画在前景层里**的那种：空气在内容框
   *    之外，角上必然量不到东西，于是 H1 直接判"没有这块底"、参照退成空气，而那块浅色圆盘
   *    才是这张图真正的底。真机点名那一批（"浅盘 + 深字"的 azure / 翻译 / 驾考宝典 / 华为市场
   *    …）全栽在这条上：判据比的是"图形 vs 空气"，底根本不在比较里，于是七张全判成不翻。
   *  · **H3 参照 = 没有东西**（透明）—— 图形浮在空气上时的正确答案。H2 在这种图上会失败：
   *    薄字形的轮廓就是字形自己，洪泛吃掉整框，验收（[PLATE_ALL_SHARE]）把它挡回这里。
   *
   * 洪泛**穿过所有底家族的并集**，不是冠军那一个：底常常是几块拼的（高德四块、云闪付红白两段、
   * 饿了么橙蓝两段），只选一个家族，剩下那块底整块变成墨 = 「还剩一块大色块」。
   */
  private fun groundPass(
    pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, g: Int, s: Scratch,
  ): Pass {
    val seed = s.mark      // 洪泛的种子掩码（measure 之前没人用这块）
    val fams = cornerFamilies(pixels, w, box, band, s)
    if (fams > 0) {
      bandSeed(s, w, box, band, seed)
      return fitGround(pixels, w, h, box, g, s, fams, seed)
    }
    val ring = outlineFamilies(pixels, w, box, s, seed)
    if (ring > 0) {
      val alt = fitGround(pixels, w, h, box, g, s, ring, seed)
      if (alt.share < PLATE_ALL_SHARE) return alt
    }
    bandSeed(s, w, box, band, seed)
    return fitGround(pixels, w, h, box, g, s, 0, seed)
  }

  /** 参照吃掉整框到这个比例，就说明"这块底"其实是图形自己（薄字形的轮廓=字形）→ 假设不成立。 */
  private const val PLATE_ALL_SHARE = 0.92f

  /** 种子掩码 = 内容框的边界带（H1 / H3）。 */
  private fun bandSeed(s: Scratch, w: Int, box: Rect, band: Int, seed: ByteArray) {
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        seed[i] = if (inBand(x, y, box, band)) 1 else 0
      }
    }
  }

  /**
   * H2 的家族色 = 内容**自己轮廓**上那一圈的颜色（不透明、且四邻里有不透明的对面），
   * 种子掩码就写这些像素。
   *
   * 一块铺满画面的底板，必然在它自己的边界上露出来 —— 这与 H1「底按角取」是同一条道理，
   * 只是量的是**形状的边**而不是**外接矩形的角**：圆盘在方框里，角上是空气，边上才是盘。
   * 轮廓太少（<1/50 框）就不成立：那说明内容几乎填满整框、没有"外圈"可言。
   */
  private fun outlineFamilies(pixels: IntArray, w: Int, box: Rect, s: Scratch, seed: ByteArray): Int {
    var n = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val on = Color.alpha(pixels[i]) >= A_SOLID
        val edge = on && (x == box.left || y == box.top || x == box.right - 1 ||
          y == box.bottom - 1 || Color.alpha(pixels[i - 1]) < A_SOLID ||
          Color.alpha(pixels[i + 1]) < A_SOLID || Color.alpha(pixels[i - w]) < A_SOLID ||
          Color.alpha(pixels[i + w]) < A_SOLID)
        seed[i] = if (edge) 1 else 0
        if (edge) n++
      }
    }
    val boxArea = box.width().toLong() * box.height()
    if (n.toLong() * 50 < boxArea) return 0
    var color = 0
    for (c in 0 until 3) {
      s.hist.fill(0)
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          if (seed[i] != 0.toByte()) s.hist[(pixels[i] shr (16 - 8 * c)) and 0xff]++
        }
      }
      color = color or (median(s.hist, 0, n) shl (16 - 8 * c))
    }
    s.famL[0] = labChannel(color, 0)
    s.famA[0] = labChannel(color, 1)
    s.famB[0] = labChannel(color, 2)
    s.famColor[0] = color
    s.fams = 1
    return 1
  }


  /**
   * **第二遍：参照 = 这一像素近旁那块主体。** 结构与第一遍**一模一样**，只是把"底"换成
   * "压在底上的那些墨"、把"内容框边界带"换成"图形与底接触的那一圈"：
   *  · 种子 = 墨里贴着底的像素 = 图形的外壳（它直接与底相接，所以它是图形的第一层）；
   *  · 洪泛 = 从外壳出发，穿过「离局部主体色够近」的墨；
   *  · 剩下的那些墨（被外壳围住、又离主体远）就是图形的第二层 —— 字腔、高光、白 logo 上的洞，
   *    按 dep₂ 连续降下来，不是一条"是不是洞"的线。
   *
   * ⚠ 为什么"够近"与"连得上"两样都要，只问一样一定错：
   *  · 只问颜色（离主体色远就降）→ 红蓝双色 logo 的另一半一样远，降下去等于把半个 logo 擦了。
   *    两半都压着底，连通性正好把它们都留在第一层。
   *  · 只问连通性（先把整片墨洪泛成一个主体）→ 围在里面的洞顺着墨一起连进参照，那一层就没了。
   *  这与第一遍「底取角上的家族、不取摘得最多的那个颜色」是同一条道理：**位置与色相各管一半**。
   */
  private fun bodyPass(pixels: IntArray, w: Int, h: Int, box: Rect, g: Int, s: Scratch): Pass {
    val total = w * h
    val boxArea = box.width().toLong() * box.height()
    val body = s.body
    val shell = s.grown      // 种子集要单独留着：body 每一轮都被洪泛重写
    val plate = s.plate
    val p = Pass()
    var inks = 0
    var seeds = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val ink = plate[i] == 0.toByte()
        // 贴着内容框边界 = 它就是图标的轮廓；四邻里有一个底 = 它压在底上。
        // ⚠ 短路的顺序不能改：先判边界，才不会去读框外的 [plate]（框外从来没被清零过）。
        val onGround = ink &&
          (x == box.left || y == box.top || x == box.right - 1 || y == box.bottom - 1 ||
            plate[i - 1] != 0.toByte() || plate[i + 1] != 0.toByte() ||
            plate[i - w] != 0.toByte() || plate[i + w] != 0.toByte())
        shell[i] = if (onGround) 1 else 0
        body[i] = shell[i]
        if (ink) inks++
        if (onGround) seeds++
      }
    }
    p.share = seeds.toFloat() / boxArea
    if (inks == 0 || seeds == 0) {
      // 没有墨（整框就是一块底），或墨没有一处压在底上：第二遍不参与 —— 降幅恒 0，
      // 明度序的参照退回第一遍那块底（β=255）。
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          s.off[i] = 0
          s.beta[i] = 255
        }
      }
      p.zero = 255
      p.span = 255
      return p
    }
    // 「拟合主体色场 → 量 dep₂ → 够近的从外壳洪泛过去」跑两轮，与第一遍同一个结构、
    // 同一套夹持值。第一轮还没有色场，容差给宽（[DEP_COARSE]，就是 ΔE [TOL_COARSE] 换算到
    // dep 单位上），之后每轮的容差由主体自己量。
    var nRef = 0
    var nOff = 0
    var tol = DEP_COARSE
    for (iter in 0 until BODY_ITERS) {
      if (iter > 0) connect(s, w, box, shell)
      fitField(pixels, w, box, g, s, body, solidRef = true)
      propagateField(w, h, total, box, s, body)
      s.hist.fill(0)
      s.hist2.fill(0)
      nRef = 0
      nOff = 0
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          if (plate[i] != 0.toByte()) {
            s.pass[i] = 0
            s.off[i] = 0
            continue
          }
          val d = departure(pixels[i], i, s)
          s.off[i] = d.toShort()
          s.pass[i] = if (d <= tol) 1 else 0
          if (body[i] != 0.toByte()) {
            s.hist[d.coerceAtMost(DBUCKETS - 1)]++
            nRef++
          } else {
            s.hist2[d.coerceAtMost(DBUCKETS - 1)]++
            nOff++
          }
        }
      }
      tol = percentile(s.hist, nRef, TOL_PCT).coerceIn(DEP_TOL_MIN, DEP_TOL_MAX)
    }
    // 降幅的标定与第一遍同一条：零点 = 主体自己的散度，实心点 = 剩下那些墨的 p[INK_REF_PCT]。
    p.zero = (if (nRef > 0) percentile(s.hist, nRef, TOL_PCT) else 255)
      .coerceIn(DEP_TOL_MIN, DEP_TOL_MAX)
    p.solid = if (nOff > 0) percentile(s.hist2, nOff, INK_REF_PCT) else p.zero
    // 第二遍能降多少，先看第一层占不占到一半：主体（连得上底的那些墨）只占墨 6% 的那张 ——
    // 整张插画，根本没有"主体"可言 —— 就没有"第二层"可刻，硬刻等于把整张图降下来。
    // 旧版这里是一条二值的 `bodyShare < BODY_MIN_SHARE → 不做`，现在按占比连续给。
    p.weight = (nRef.toLong() * 510 / inks.coerceAtLeast(1)).coerceAtMost(255).toInt()
    p.span = (p.solid - p.zero).coerceAtLeast(DEP_MIN_SPAN)
    return p
  }

  /** 从 [seed] 出发、穿过 `pass==1` 的像素洪泛，结果写进 `body`（= 这一遍的参照集）。
   *  四邻域，与 [flood] 同一个理由：八邻域会顺着对角线从笔画之间的缝里钻过去。 */
  private fun connect(s: Scratch, w: Int, box: Rect, seed: ByteArray): Int {
    val pass = s.pass
    val out = s.body
    val q = s.queue
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) out[row + x] = 0
    }
    var tail = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (seed[i] == 0.toByte() || pass[i] == 0.toByte()) continue
        out[i] = 1
        q[tail++] = i
      }
    }
    var head = 0
    while (head < tail) {
      val i = q[head++]
      val y = i / w
      val x = i - y * w
      if (x > box.left && out[i - 1] == 0.toByte() && pass[i - 1] != 0.toByte()) {
        out[i - 1] = 1; q[tail++] = i - 1
      }
      if (x < box.right - 1 && out[i + 1] == 0.toByte() && pass[i + 1] != 0.toByte()) {
        out[i + 1] = 1; q[tail++] = i + 1
      }
      if (y > box.top && out[i - w] == 0.toByte() && pass[i - w] != 0.toByte()) {
        out[i - w] = 1; q[tail++] = i - w
      }
      if (y < box.bottom - 1 && out[i + w] == 0.toByte() && pass[i + w] != 0.toByte()) {
        out[i + w] = 1; q[tail++] = i + w
      }
    }
    return tail
  }

  /** 第二遍洪泛的轮数与第一轮容差（ΔE [TOL_COARSE] 换算到 dep 单位上）。 */
  private const val BODY_ITERS = 2
  private const val DEP_COARSE = 46

  /** 这一像素**混好之后**的参照亮度（真值 ×64）：β 占几分用「背后的底」，剩下用「近旁的主体」。
   *  β=1（一整块实心底）时它就是底色的 L，β=0（透明底）时它就是主体的 L —— 明度序不需要两版。 */
  private fun refLight(i: Int, s: Scratch): Int {
    val b = s.beta[i].toInt() and 0xffff
    return (b * (s.gRefL[i].toInt() and 0xffff) + (255 - b) * bodyLight(i, s)) / 255
  }

  /** 第二遍那个参照（主体）在这一像素处的亮度（真值 ×64）。 */
  private fun bodyLight(i: Int, s: Scratch): Int = s.fL[if (s.ref[i] >= 0) s.ref[i] else 0]

  /**
   * **唯一的浓度量**：这一像素离它的参照多远，0..255。
   *
   *     dep = a × [ (1−β) + β × min(ΔE, EMAX) / EMAX ]
   *
   * 一个式子覆盖掉过去三支：β=0（参照是透明的）时 dep 就是覆盖率；β=1（一整块不透明的底）
   * 时 dep 就是"离参照多远"（对 ΔE 线性，标定与过去按 ΔE 量等价）；中间连续过渡。
   * 所以「这张图属于哪一支」不再是问题，也不用再为"透明底"开一支。
   *
   * 参照色与 β 都来自 [propagateField]：**这一像素背后那块参照是什么**，不是"它落在哪个网格里"。
   *
   * ⚠ 这里没有任何绝对的 alpha 线（旧版 `MIN_SHAPE_ALPHA` 那种切法正是「柔光字形被切成骨架」
   *   的来源）：淡只让 dep 变小，变小到多少浓度由这张图自己的分布标定。
   * ⚠ 半透明像素先按自己的 alpha 合成到参照上再比色是不做的 —— `getPixels` 给的是**非预乘**值，
   *   它的 RGB 就是它自己，而 dep 已经整体乘了 a：淡的本来就该近，不需要再改它的色相。
   */
  private fun departure(pixel: Int, i: Int, s: Scratch, withCoverage: Boolean = true): Int {
    val a = Color.alpha(pixel)
    if (a == 0) return 0
    val cell = if (s.ref[i] >= 0) s.ref[i] else 0
    val de = deltaE(
      s.labL[i].toInt(), s.labA[i].toInt(), s.labB[i].toInt(),
      s.fL[cell], s.fA[cell], s.fB[cell]
    ) shr 6
    val cf = if (de >= EMAX) 255 else de * 255 / EMAX
    val b = s.fCov[cell]
    return if (withCoverage) a * ((255 - b) * 255 + b * cf) / 65025 else a * b * cf / 65025
  }

  /** ΔE 到多少算"完全不像参照"。超过就按这个数截断，再往上的差别交给标定。 */
  private const val EMAX = 100

  /** dep 单位上的三个夹持值。dep 对 ΔE 是 ×2.55 的线性（β=1 时），所以这些数就是
   *  [TOL_MIN]、[TOL_MAX] 换算过来的，不是新的一条线；[DEP_MIN_SPAN] 是除数下限。 */
  private const val DEP_TOL_MIN = 15
  private const val DEP_TOL_MAX = 115
  private const val DEP_MIN_SPAN = 16

  /** 第二遍最多把浓度降下来多少（百分比）。100 = 完全透明 = 那块东西整个不算图形，
   *  留 12% 是给"其实是个很淡的高光"那种留的余地。 */
  private const val HOLLOW = 88

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
  private fun propagateField(
    w: Int, h: Int, total: Int, box: Rect, s: Scratch, seed: ByteArray,
  ) {
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
        if (seed[i] == 0.toByte()) continue
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
    // **这张图到底有没有"一块底"**：底是铺满一整片区域的东西，它落在内容框边界带上必然是
    // 实心的一大片。内切于内容框的那种图形只在几个切点上碰到那条带 —— 靠下面"往里走"同样
    // 能找到它的颜色，但那颜色是图形，拿它当底就会顺着切点把整个图形洪泛成底
    // （判据台上 透明底蓝圆 量出 底占=0.79、圆整个消失）。
    // 低于这条线就没有家族色：参照退回"没有东西"，原语自己退成覆盖率 —— 不需要为透明底开一支。
    var bandPx = 0
    var bandOn = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        if (!inBand(x, y, box, band)) continue
        bandPx++
        if (Color.alpha(pixels[row + x]) >= A_SOLID) bandOn++
      }
    }
    s.bandSolid = if (bandPx == 0) 0 else bandOn * 255 / bandPx
    if (s.bandSolid < BAND_SOLID_MIN) {
      // 「底」这个区域不存在 —— 但**透明仍然是参照**：洪泛穿过 a<[A_SOLID] 的像素（见
      // [passFromFamilies] 里那条恒通过），色场由淡的那一层定，覆盖率 β 自己就接近 0。
      // 于是原语在第一遍就退成覆盖率，柔光与投影按它们自己的透明度压回去，
      // 层次整个交给第二遍（离主体多远）。
      return 0
    }
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
   * 穿过 `pass==1` 的像素洪泛。结果写进 `plate`（1 = 与参照连通），返回像素数。
   *
   * 种子放在**内容框边界带**里：「这块底与外面连成一片」= 它是参照。框外本就是留白，图形
   * 不可能从那里被围住，所以种子只能在带里。
   * ⚠ 边界带而不是"整条框边"：一条框边像素在圆角图标的角上是空的，带宽按短边算，
   *   保证任何一块铺满某片区域的底都至少有一段落在带里。
   *
   * ⚠ 四邻域，不是八邻域 —— 八邻域会顺着对角线从笔画之间的缝里钻到图形内部，
   * 把本该留下的字腔一起当底擦掉。
   */
  private fun flood(s: Scratch, w: Int, box: Rect, seed: ByteArray): Int {
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
        if (pass[i] == 0.toByte() || seed[i] == 0.toByte()) continue
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

  /** 四邻域里有没有**墨**（不是底的那一侧）。贴着墨 = 这一格是某条笔画自己的抗锯齿边缘。 */
  private fun touchesInk(plate: ByteArray, w: Int, box: Rect, x: Int, y: Int): Boolean {
    if (x > box.left && plate[y * w + x - 1] == 0.toByte()) return true
    if (x < box.right - 1 && plate[y * w + x + 1] == 0.toByte()) return true
    if (y > box.top && plate[(y - 1) * w + x] == 0.toByte()) return true
    return y < box.bottom - 1 && plate[(y + 1) * w + x] == 0.toByte()
  }

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



  /** 跨度分位与"至少要有多少明暗差才值得排层次"（L\\* 单位）。 */
  private const val LAYER_AMP_PCT = 98

  private const val LAYER_MIN_AMP = 6

  /**
   * 每格取「该格**参照**像素」的中位 RGB，外加这一格的**覆盖率 β**。
   *
   * 中位而不是均值：一两个切错的字形像素不该拖动整格参照色。
   * β = 这一格参照像素的平均 alpha：`0` = 这一格参照是"没有东西"（透明底），`255` = 一整块
   *   实心底。它就是原语 [departure] 里的那一个 β —— **一支"参照是什么颜色"与一支"参照存不存在"
   *   合并在这里量**，所以透明底与实心底板之间不需要分类，也不需要哪一条绝对 alpha 线。
   * 没有参照像素的格子（被图形整个盖住）由 [fillEmptyCells] 补出来，**补出来的格 β=0**：
   * 那是"这一格根本没量到参照"，不是"参照是实心的一块"。
   *
   * @param seed 这一遍的参照集（第一遍 = 底，第二遍 = 主体）
   */
  private fun fitField(
    pixels: IntArray, w: Int, box: Rect, g: Int, s: Scratch, seed: ByteArray, solidRef: Boolean,
  ) {
    val cells = g * g
    s.cellN.fill(0, 0, cells)
    s.cellTot.fill(0, 0, cells)
    s.cellSum.fill(0, 0, cells)
    s.fCov.fill(0, 0, cells)
    s.fieldHist.fill(0, 0, cells * 768)
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (seed[i] == 0.toByte()) continue
        val p = pixels[i]
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val k = cy * g + cx
        val a = Color.alpha(p)
        s.cellTot[k]++
        s.cellSum[k] += a
        // 透明像素的 RGB 是垃圾（非预乘、未定义），不参与定色；它算参照，但不算「参照是什么颜色」。
        if (a < A_SOLID) continue
        val at = k * 768
        s.fieldHist[at + ((p shr 16) and 0xff)]++
        s.fieldHist[at + 256 + ((p shr 8) and 0xff)]++
        s.fieldHist[at + 512 + (p and 0xff)]++
        s.cellN[k]++
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
    // 覆盖率：这一格参照像素平均有多实。一个参照像素都没有的格 = 0（见上面那条注释）。
    // @param solidRef 参照是"图形自己"（第二遍）时为真：图形在不在，与它透不透明无关 ——
    //   β 回答的是「**参照**还在不在」，而图形自己的淡是它自己的浓度，已经在工作集的 dep 里
    //   乘过一遍 a 了。第二遍若按 alpha 量 β，外壳正好落在柔光的斜坡上（β≈190），
    //   核那种实心像素就会被算成"离主体很远"而整块降下来（判据台上 白底柔光+实心核 就是这样坏的）。
    for (k in 0 until cells) {
      val tot = s.cellTot[k]
      s.fCov[k] = when {
        tot == 0 -> 0
        solidRef -> 255
        else -> (s.cellSum[k] / tot).coerceIn(0, 255)
      }
    }
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
        // ⚠ 这一格原本在 `淡` 里数过（0<a<[A_SOLID] 才进得来这个分支），补成实心之后它属于
        //   `框内` 了 —— 两个数都要动，否则 `框内 + 淡 > 1`，日志里就会出现不可能的比例
        //   （判据台上 多色插画 打出过 0.91 + 0.40）。`框内` 与 `淡` 是互斥的两层，和必须 ≤ 1。
        if (Color.alpha(argb[i]) in 1 until A_SOLID &&
          y in box.top until box.bottom && x in box.left until box.right
        ) soft--
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
