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
 *     dep = max(a − c, 0) + a × β × min(ΔE, EMAX) / EMAX    0..255
 *
 *  a = 这一像素自己的覆盖率，c = **参照那把尺**（默认 β，出图那一步换 [Scratch.fTop]），
 *  β = **参照自己的**覆盖率，ΔE = 与参照色的 CIELAB 距离。
 *  ⚠ 第一项是**超出量**，不是 `a×(1−β)`：两者只在 `0 < a < c` 那一段分家，而按比例那一版
 *    会在那一段给描边的抗锯齿像素留墨、连成一条斜痕（真机实录见 [departure] 里那段）。
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

  /** 算"空气"的门槛：低到这条才算**什么都没有**（不是"淡一点的画"）。 */
  private const val A_AIR = 64

  /**
   * 「盘自己的外沿」有多宽：内侧挨着底、外侧这么几步之内就是空气的那些实心格。
   *
   * 为什么需要这一条：判连通比的是"含 `(1−β)` 的 dep"，容差却是**不含** β 量的（见
   * [fitGround] 里那一段）。承载面的外沿背后那一格跨在"盘↔空气"之间，β 只有 27~29，
   * 于是那一圈实心、颜色就是盘的像素 dep=227、出墨 255 —— 屏幕上就是盘轮廓外那 8 个
   * 暗色小楔子（run.xbud / 中国移动 点名的"外圈瑕疵"：cell 1/6/8/15/48/55/57/62 =
   * 圆盘与方形画布四角相接那几格，449/463 格）。把它们放回底里，唯一还没过的关是
   * **别把贴着盘的图形一起放回去** —— 楔子的外侧是空气，贴盘的图形外侧还是底。
   *
   * 6 不是猜的：`_harness/RimDbg --rim` 量到那 449 格到最近空气的切比雪夫距离是
   * **3 步 165 格、4 步 174、5 步 98、6 步 12，1~2 步与 7 步以上都是 0**。
   * 取 6 刚好包住整条尾巴。（1~2 步为 0 也解释了为什么"四邻贴不贴空气"那一版完全惰性。）
   */
  private const val PLATE_EDGE_R = 6

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
   *  · 太粗 —— 每格一个中位色，底是渐变时就成一格一格的台阶。他报「马赛克」，在真机带回的
   *    图上量到**台阶正好压在格线上**（`_harness/GridCheck`：比"格线上"与"其它列"的单像素
   *    跳变之比，>1.6 记一张）：8 格时 **13/39 张**越线 —— 抖音 / taptap / 华为市场 / 搜狗 /
   *    wps / songpal 全在里面。
   *  · 太细 —— 每格贴着自己那一片像素，"底板散度"被量成 0，洪泛随之只容得下逐像素相同的
   *    颜色，渐变尾端整个留在掩码里（`淡` 涨）。这条另由 [TOL_MIN] 兜住，但网格不能指望远。
   *
   * 16 格试过，**退了回去**，理由不是上面任何一条软指标，而是一次**定性的翻转**：
   * 透明底 + 线条字形那类图（bilibili 的粉色电视机），8 格时 groundPass 找不到底
   * （`底占=0.00`）→ dep 退成覆盖率 → 整个字形是墨（`框内=0.50`，正确的答案）；
   * 16 格时它**凭空找到一块占 66% 的底**（`底占=0.00→0.66`），于是把字形自己的身体
   * 当成背景腾空（`框内=0.50→0.30`）—— 屏幕上就是他报的「只剩线条」。
   * 台阶确实掉了一半（13/39→6/38），但那不值一次身体消失。
   *
   * 结论：**网格密度是"底该选哪个假设"的一部分，不是平滑度旋钮。**要治马赛克得在别处治
   * （候选：只在"这一格与邻格同属一块底"的地方插值 —— 但插值这条路本身已两次弄红
   *  「拼底的中缝没有留下墨」，见下）。
   *
   * ⚠ 两条试过并否掉的路：**格间双线性插值** —— 把拼底的中缝一起抹了，
   * 「拼底的中缝没有留下墨」当场红（缝上 0→69；加保边权重反而 186），因为整个模型靠的就是
   * "参照在真边界上**可以**不连续"；**格子色互相平滑** —— 台阶 13→11 几乎没动，同样弄红那条。
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

  /** 内容框里实心像素占到这个比例以上，就当这张图**一格空气都没有** ——
   *  那时"参照=空气"是空话，β 不再有权把亮度轴压回 0。见 [LUM_SWALLOW] 下面那段与 build() 里 `cov`。 */
  private const val NO_AIR_OPAQUE_PCT = 95

  /** 上面那条的配对门槛：**这张真的有两个调子**才允许接管。量的是有符号 ΔL 那条斜坡的端点跨度
   *  `spread`（L\\*×64）。整张纯色也"没有空气"，可它的正确答案是一张空掩码（钉着的性质
   *  「整张纯色 → 交出空掩码，不是 null」），而纯色那张 spread 退到下限 `1 shl 6`。
   *  20 个 L\\* 单位是两侧差得远的位置：纯色=1，满幅插画实测 59。 */
  private const val TWO_TONE_SPREAD = 20 shl 6

  /** 底板散度的分位，用来定每一轮的洪泛容差。 */
  // ⚠ **这条容差量的是"底内部的散度"，所以它天生收不下底的边界。** 2026-09-28 反射量过
  //   （`_harness/RimDbg.kt`，圆角渐变底板那张合成图）：底板内部的 p[TOL_PCT] 被夹在
  //   `DEP_TOL_MIN` 上（日志里 `零=15`），而**盘自己那圈抗锯齿的 dep 是 84~94** —— 差六倍，
  //   永远进不了底。于是那 116 格（源 α=200..254）被当墨出图，每格 163/255：
  //   这就是 GAP「板子那圈抗锯齿交出 0 墨」剩下的那截，也是合成用例 6b 里 `cc=9` 的
  //   大半（交付的墨 10% vs 那条白字只占 4.9%）。
  //   已排除的两条：① 参照被 `fillEmptyCells` 补成中灰 —— 量出来 β=255、参照 Lab 的 a/b
  //   就是盘自己的蓝紫，**不是中灰**；② 归属规则（`touchesInk` 把空气当墨）—— 已修
  //   （`2c18a24`，边上 32.3→29.9），但病不主要在那儿。
  //   为什么 dep 高：格是 8×8（每格约 48px）而板是渐变的，格心参照由格的内部决定，
  //   盘边正落在渐变的极端 → 离自己的参照 ~40 ΔE。**所以别去抬 TOL_PCT**：抬它是拿
  //   "底更贪"换"边更干净"，而 `bd0d870` 已经量过贪的代价（16 格把线条字形的身体腾空）。
  //   待验的那一条是几何而不是颜色的：α 近实心、且一侧挨底一侧挨空气的格子，**按位置**归底
  //   （和 [absorbSeams] 同一族判据）。前一版试过并撤掉，因为它当时还要求"不许挨着实心非底
  //   像素"——而这张图上 12.9% 的实心像素本来就不在底里（白方块自己），那条守卫把整圈挡死。
  private const val TOL_PCT = 97

  /** 距离直方图的桶数（一桶一个 ΔE76 单位）。 */
  private const val DBUCKETS = 256

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
   * ⚠ **但"把低段抬得少一点"这一刀 2026-10-01 报过价，全局改指数付不起**：`0.75 → 0.90` 跑满
   *   六段回归台 = `淡 30→35`、`平色饼 10→13`（两项变坏、闸门直接红），只换来 `档数 306→315`、
   *   `ρ色 0.962→0.971`。**别把它当一个可以调的常数再试一次。**
   *   值钱的是那个**没动**的数：`残盘 3540 → 3540`，一格不差 —— 也就是说把盘子按住的**不是**这条
   *   曲线，而是零点与归属那一层。所以这一类只剩**分格/分区**的形状可走（"离自己那块盘近的不抬"），
   *   而那要先有一张建在 8×8 拟合色场上的区域图。
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
  internal fun deltaE(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
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
    /** 哪一支赢了（[groundPass] 的 H1/H2/H3/H0）。-1 = 没走到判据（尺寸非法 / 整张透明）。
     *  带出来只为一个用途：[MonoDump] 要能认出"这一张是新开的那一支接手的"，见那里的说明。 */
    val hyp: Int = -1,
    /** H0（区域关系那一支）走到哪一步：0=没走到、1=接手、2=画布关系不成立、3=区域关系不成立、
     *  4=被 [GROUND_INK_SAME_PCT] 那道验收退掉。
     *  与 [hyp] 分开记，是因为**换桌面时要看的正是"没接手的那部分被哪道拦下"** —— 只报 hyp
     *  就只能看见"接手了几张"，看不见这一支在新桌面上有没有整体失声。见 [MonoDump.suspicious]。 */
    val h0why: Int = 0,
    /** 哪些假设被「底与墨同色」这道验收退掉了（位：1=H1、2=H2、4=H0）。见 [GROUND_INK_SAME_PCT]。
     *  单独带出来是因为这一道**改的是接手人**：不记下来，日志上只看见"H=3 的一张变多了"，
     *  看不见是这一道把它们退下去的 —— 换桌面时这两件事要能分开。 */
    val veto: Int = 0,
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

  /** 「同一块平色」的界：同色 = 真 ΔE<2（⚠ `deltaE` 返回**真值 ×64**，写常数先 `shl 6`）；
   *  块至少占画布 3‰（⚠ 必须是**比例**，见 [VOTE_MIN_PERMILLE] 那条）；
   *  少数侧要占够 10% 才算"真给了两个答案"。数与扫档见 README。 */
  private const val VOTE_DE = 2 shl 6
  // ⚠ **块的最小尺寸按画布的千分数，不按绝对格数**：第一版写死 500 格，全扫四档边长当场
  //   露馅 —— 开火 1 行(96) → 2(192) → 9(384) → 19(768)，抹的格 216 → 27296。边长翻倍面积×4，
  //   过线的块自然变多，这就是一条"会随边长换人的闸门"（与 `outlineFamilies` 那条
  //   「轮廓按周长长、面积×4」是同一族错配）。500/384² = 3.4‰，按这个数换成千分数。
  private const val VOTE_MIN_PERMILLE = 3
  private const val VOTE_SHARE = 10
  /** 第二遍传播只接这么淡的格（0..255 浓度口径）。理由见 [flatVote] 第二遍那段。 */
  private const val VOTE_PROP_HI = 40
  /**
   * 地板带的均值低于这个数，这块就判成"底 + 膜"而不是"一块真浓度"，只收带、不抬底。
   * 取的是实测两族（5..24 与 35..63）中间那段空档的下沿，理由见 [flatVote] 里 `leak` 那段。
   */
  private const val VOTE_BAND_INK = 25

  /**
   * **「同一块平色被给了两个答案」—— 按这块自己的多数定调，少数侧统一过去。**
   *
   * 与已死的八把的差别：那八把全在问"这一格是不是底"（离底多远、几族、多纯、贴不贴边、
   * 边界在源图里有没有），这一把**不问参照**，只问"这一块自己是不是被交付成了两套浓度"，
   * 而修的方向由**这块的多数**定 —— 多数是底就抹，多数是墨就**抬**。
   *
   * 为什么必须允许抬：合成用例「三段距离」里那块"偏一点的红"，模型把贴着边界的 12 格判成了
   * 0、其余 4896 格交 43。只抹的那一版（`plateLeak`）把 4896 格收走，打红了钉着的
   * 「最近那段是淡的（不是整块底）」；按多数定调它算出的是**抬那 12 格**，性质原样绿。
   * 数与扫档：`_harness/VoteKt.kt`（README「多数定调这一刀报过价了」那条）。
   *
   * ⚠ **破"只减不加"**：这是这一族第一次往上动浓度，代价写在这里，不藏。
   * ⚠ 块的代表色取**起点那一格的源 Lab**（与报价那台同一个口径）。按均值取会撞
   *   `跨峰的均值不是一个颜色` 那条老坑 —— 渐变盘会被均值劈成两半。
   *
   * @return 动了的格数（抹 + 抬）。0 = 这张图一个都没碰到。
   */
  private fun flatVote(out: IntArray, pixels: IntArray, w: Int, h: Int, box: Rect, s: Scratch): Int {
    val total = w * h
    if (s.vote.size < total) s.vote = ByteArray(total)
    val comp = s.vote
    for (i in 0 until total) comp[i] = 0
    if (s.queue.size < total) return 0
    val q = s.queue
    var erase = 0; var raise = 0; var hits = 0
    val tgt = IntArray(total) { -1 }          // 第一遍给这一格定下的目标浓度（-1 = 没定过）
    for (y0 in box.top until box.bottom) for (x0 in box.left until box.right) {
      val start = y0 * w + x0
      if (Color.alpha(pixels[start]) != 255 || comp[start] != 0.toByte()) continue
      // 走一块：4 连通，边条件是"与起点同色"
      var head = 0; var tail = 0
      // ⚠ **1 = 走过，不是组件号**：第一版往这张 ByteArray 里存 `mark++`，过了 255 块绕回 0，
      //   而 0 在这张数组里的意思正是"没走过" —— 于是同一片格子被反复入队，队列越界
      //   （`ArrayIndexOutOfBoundsException: Index 147456`，SelfTest 当场炸）。byte 装不下组件号。
      q[tail++] = start; comp[start] = 1
      val sl = s.labL[start].toInt() and 0xffff; val sa = s.labA[start].toInt() and 0xffff
      val sb = s.labB[start].toInt() and 0xffff
      while (head < tail) {
        val c = q[head++]
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(if (cx > box.left) c - 1 else -1, if (cx < box.right - 1) c + 1 else -1,
          if (cy > box.top) c - w else -1, if (cy < box.bottom - 1) c + w else -1)) {
          if (j < 0 || !solidPx(pixels, j) || comp[j] != 0.toByte()) continue
          if (deltaE(s.labL[j].toInt() and 0xffff, s.labA[j].toInt() and 0xffff, s.labB[j].toInt() and 0xffff,
                     sl, sa, sb) >= VOTE_DE) continue
          comp[j] = 1; q[tail++] = j
        }
      }
      val n = tail
      if (n * 1000 < total * VOTE_MIN_PERMILLE) continue      // 千分数：与边长无关
      // 三套浓度：底 / 地板带 / 实墨
      var n0 = 0; var nf = 0; var ns = 0; var sumF = 0L; var sumS = 0L
      for (u in 0 until n) {
        val a = out[q[u]] ushr 24
        if (a <= 4) n0++ else if (a < 80) { nf++; sumF += a } else { ns++; sumS += a }
      }
      val side = intArrayOf(n0, nf, ns)
      if (side.count { it * 100 >= n * VOTE_SHARE } < 2) continue      // 少数侧不够材料性 → 不动
      val maxSide = side.max()!!
      val bandMean = if (nf > 0) (sumF / nf).toInt() else 0
      // **地板带自己当多数，不等于它是答案。** 带跨 4..80，均值落在带的最下一截时，这块
      // 不是"平色被给了两套浓度"，而是"底 + 一层漏出来的膜" —— 按均值统一会把干净地板
      // (α≤4) 一起抬成膜。icbc 实测：788 格从 α=0..7 抬到 9..11，同时 510 格从 13..33 抹到
      // 同一档，两边都往"一层 4% 的灰"收 —— 那正是主人说的「还是有痕迹」的成因。
      // 判它是不是膜，只看带自己的均值：全量 183 张里 maj=1 的 11 块，均值分成两族
      // 5..24 与 35..63，中间 25..34 是空的，取空档下沿。见 README「地板带当多数」。
      val leak = maxSide == nf && bandMean < VOTE_BAND_INK
      val maj = if (maxSide == ns) 2 else if (maxSide == nf) 1 else 0
      val target = when { leak -> 0; maj == 0 -> 0; maj == 1 -> bandMean; else -> (sumS / maxOf(ns, 1)).toInt() }
      hits++
      for (u in 0 until n) {
        val c = q[u]
        val a = out[c] ushr 24
        // `keep` = 这一侧本来就是答案，不动它。判成膜时反过来：要收的正是**带里那些格**，
        // 地板（本来就干净）与实墨（`ns`，chrome 那块有 1686 格）两侧一律留着 ——
        // 一句"多数偏淡"没有权力抹掉真墨。
        val keep = if (leak) a <= 4 || a >= 80 else if (maj == 0) a <= 4 else if (maj == 1) a > 4 && a < 80 else a >= 80
        if (keep || a == target) continue
        out[c] = (target shl 24) or 0x00FFFFFF
        tgt[c] = target
        if (target < a) erase++ else raise++
      }
    }
    // 第二遍：**已定的决策沿"相邻两格局部同色"再传一次**。
    //   为什么需要：分块的条件是"与块**起点**同色"，一道渐变盘会被它劈成好几块 —— 微博底部
    //   那两条薄条就是这样自成一块、少数侧只占 2.3% / 5.9%，被 10% 材料性门故意放掉，
    //   于是"抹了大半、还剩痕迹"（主人 2026-10-03 装机后报的就是这个）。
    //   起点色只是**分块的工具**，不该拦住决策本身往同一块颜色里传。
    //   传的条件收紧两处：只踩**地板带**的格（不碰实墨、不碰已经交成 0 的），且必须贴着
    //   上一遍**改过**的格（`chg`）—— 没开火的图一格都不动。
    if (hits > 0) {
      val chg = ByteArray(total)
      val q2 = IntArray(total)
      var n2 = 0
      var head2 = 0
      // ⚠ 传播**继承种子那一块定下的目标**：第一版把这里写死成 0，于是"抬"出来的块
      //   转头把自己的邻居抹成 0 —— 决策自相矛盾，且只在开抬的图上犯（azure / appui）。
      for (i in 0 until total) if (tgt[i] >= 0) { chg[i] = 1; q2[n2++] = i }
      while (head2 < n2) {
        val c = q2[head2++]
        val cy = c / w; val cx = c - cy * w
        for (j in intArrayOf(if (cx > box.left) c - 1 else -1, if (cx < box.right - 1) c + 1 else -1,
          if (cy > box.top) c - w else -1, if (cy < box.bottom - 1) c + w else -1)) {
          if (j < 0 || chg[j] != 0.toByte() || !solidPx(pixels, j)) continue
          val a = out[j] ushr 24
          // 只接**淡到地板**的格：传播要收的是「没被分块规则接到的薄条」，而薄条按定义就是最淡
          // 那批（微博底部 α≈20）。中间调不在目标物里 —— 放它们跟着传，小步点盘的中间调会被
          // 整片收走，ρ色 从 1.000 掉到 0.670。
          if (a <= 4 || a >= VOTE_PROP_HI) continue
          if (deltaE(s.labL[j].toInt() and 0xffff, s.labA[j].toInt() and 0xffff, s.labB[j].toInt() and 0xffff,
                     s.labL[c].toInt() and 0xffff, s.labA[c].toInt() and 0xffff, s.labB[c].toInt() and 0xffff) >= VOTE_DE) continue
          chg[j] = 1; tgt[j] = tgt[c]
          out[j] = (tgt[c] shl 24) or 0x00FFFFFF
          if (tgt[c] < a) erase++ else raise++
          q2[n2++] = j
        }
      }
    }
    s.voteErase = erase; s.voteRaise = raise; s.voteHits = hits
    return erase + raise
  }

  /** 源像素是不是全不透明（[flatVote] 只在实心格里走块）。 */
  private fun solidPx(pixels: IntArray, i: Int): Boolean = (pixels[i] ushr 24) == 255

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
    /** [regionGround] 的连通块编号：≥0 = 第几块，-1 = 还没走到，-2 = 求洞那一趟走过。
     *  **单独一块数组**：上一版借用 [visited]，探测本身没接手也改了后面两支的答案 —— 借来的
     *  数组留脏数据是抓不住的，自己的数组才谈得上"用完不用清"。 */
    var region = IntArray(0)
    /** [plateThickness] 的距离场（只在内容框里用，按需自己扩容）。 */
    var thickDist = IntArray(0)
    /** 每格到**最近一个空气像素**的切比雪夫距离（Short，封顶 32000）。
     *  ⚠ 单独一块数组，不借 [thickDist]：借来的数组留脏数据是抓不住的（[region] 那条写过）。
     *  用途见 [PLATE_EDGE_R] —— 盘自己的外沿是"内侧挨着底、外侧几步内是空气"的那一圈。 */
    var airDist = ShortArray(0)
    /** 盘沿的**拓扑**标记：1 = 这一格不透明、且颜色与"最近一块实的底"对得上（plate-like）；
     *  2 = 这样的格子里，能从"盘↔空气"那一圈出发、只踩 plate-like 格走到的一些。
     *  用途见 [fitGround] 里 `rb` 那一段。⚠ 自己的数组：借 `pass` / `visited` 的下场
     *  [region] 那条已经写过一遍。 */
    var rimBand = ByteArray(0)
    /** 第二遍降层次的"围住"标记：1 = 比主体亮且够远（降档候选）；2 = 这些候选里能从**空气**
     *  只踩候选格走得到的那些 —— 那是设计者的第二种颜色，不是镂空。用途见 [build] 里 `kc` 那段。 */
    var knockReach = ByteArray(0)
    /** [propagateBands] 自己那份"这一格属于第几块**粗块**"（0 = 不是粗块）。
     *  用途见那里：补一条带之前，先看它会不会把两块粗块连起来。自己的数组，不借 [visited]。 */
    var bridgeLab = ShortArray(0)
    /** [propagateBands] 自己那份"这一格已经归进某条带"的标记。
     *  ⚠ **自己的数组，不借 [visited]** —— 借来的数组留脏数据抓不住，[region] 那条写过两遍了。 */
    var bandSeen = ByteArray(0)
    /** [flatVote] 自己的分块号（0 = 还没走到）。自己的数组，不借 `visited`。 */
    var vote = ByteArray(0)
    /** [flatVote] 的三笔账：抹了多少格、抬了多少格、动了几块（只为进日志）。 */
    var voteErase = 0; var voteRaise = 0; var voteHits = 0
    /** H2 那一圈轮廓到自己中位色的 p97 ΔE —— 「这一圈是不是一种颜色」（见 [outlineFamilies]）。 */
    var ringSpread = 0
    var hist = IntArray(DBUCKETS)
    /** 第二块直方图：距离与明度要同时统计（明度序的跨度归一用它）。 */
    var hist2 = IntArray(DBUCKETS)
    var fieldHist = IntArray(0)
    var cellR = ShortArray(0)
    var cellG = ShortArray(0)
    var cellB = ShortArray(0)
    var cellN = IntArray(0)
    /** 这一格里参照像素的**总数**（含透明的）—— 出 [fCov]（原语里的那一个 β）。 */
    var cellTot = IntArray(0)
    /** 每格参照色的 Lab（×64）与覆盖率 β —— 距离与浓度都直接查它们，不每像素现算转换。 */
    var fL = IntArray(0)
    var fA = IntArray(0)
    var fB = IntArray(0)
    var fCov = IntArray(0)
    /** 每格参照像素里**最实**的那一个 α（0 = 这一格压根没有实参照）。
     *
     *  为什么要与 [fCov] 分开一支：`fCov` 是"这一格参照平均有多实"，它同时被 H 判据
     *  （`fCov >= 250` / `>= 128` 那两道洪泛门）和浓度标定读着，动它会换掉整张图走哪一支。
     *  而**"盘自己的像素要不要画回去"**（[build] 出图循环里那条底格子透传）问的其实是另一个
     *  问题：「这一格有没有实参照」，不是「这一格平均下来有多实」。两者只在**跨在盘↔渐变上的
     *  格子**里才分家 —— 那正是翻译那条斜痕的来源：格子 25% 是不透明蓝、75% 是 α≈30 的淡影，
     *  `fCov` 算出 60，于是**完全不透明的蓝盘**被算成"四分之三是空气"、按 `α×(1−β)` 画回去
     *  一整条 195/255 的斜带（`_harness/InkWhere.kt` 量到 4px 宽、墨 195~255）。
     *  `fTop` 在同一个格子上给 255，那一项就归零。 */
    var fTop = IntArray(0)
    /** **出图专用的那一份 dep**（[departure] 的 `byTop=true` 口径，见 [fTop]）。
     *
     *  为什么不直接改 [dist]：`dist` 是三件事共用的输入 —— ①第二遍的 `d1`（决定"降"多少）、
     *  ②浓度标定 `hist2`（出 `p.solid`/`p.span`）、③"同源色同浓度"那张表 `cDep`。
     *  动了它，驾考宝典那种"底 81%、字形压在底上"的图整套标定会跟着缩水
     *  （`实` 237→178、`字均净` 184→145、`分档` 整条掉一档）。
     *  只换**最后写出去的那一个数**，上面三样全部照旧 —— 这是这一版能同时保住两头的唯一办法。 */
    var distT = ShortArray(0)
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
    /** H0（[regionGround]）这一支走到哪一步了：0=没走到、1=接手、2=被画布门槛（④ `盒布`）拦、
     *  4=被"底墨同色"那道验收拦、
     *  **5=最大那块不够大（① `REGION_MIN_PCT`）、6=那块没挨着实心内容的边（②）、7=它的洞里没有墨（③）**。
     *  每次 [groundPass] 开头归零。
     *  带它是因为**换桌面时这一支的射程必须看得见**，见那条汇总日志。
     *  ⚠ 5/6/7 是 2026-09-29 加的：原先 ①②③ 三道门都只写 3，`com.example.appui` 那张
     *    （白圆角方底 + 蓝图形）报 `H0=3` 却查不出是哪道 —— 量出来是 **②**，而 ② 当时
     *    **恒不成立**（见 [REGION_EDGE_SLACK]）。**诊断编码的粒度就是排查速度**。 */
    var h0why = 0
    /** 这一张被"这一支的底不成立"那两道验收退掉过哪几支（位：1=H1、2=H2、4=H0 被同色退；
     *  8=H2 被 [thinPlate]「盘比墨还小」退）。每次 [groundPass] 开头归零。 */
    var veto = 0
    /** 被退那一支自己的 `inkSame`（最后退的那一支）。**没有它这道验收不可查**：日志上只看得见
     *  接手人换成了 H3，看不见被退那支离门槛多近 —— 6b「浅盘+白字」被反过来那次就是这么找的。 */
    var vetoSame = -1
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
        region = IntArray(total)
        body = ByteArray(total)
        beta = ShortArray(total); gRefL = ShortArray(total); off = ShortArray(total)
        distT = ShortArray(total)
      }
      if (cellR.size < cells) {
        cellR = ShortArray(cells); cellG = ShortArray(cells); cellB = ShortArray(cells)
        cellN = IntArray(cells); cellTot = IntArray(cells)
        fL = IntArray(cells); fA = IntArray(cells); fB = IntArray(cells); fCov = IntArray(cells)
        fTop = IntArray(cells)
        fieldHist = IntArray(cells * 1024)
      }
    }
  }

  private val pool: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

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
    /** 这一支的参照家族数（H1 的角上家族 / H2 的轮廓家族）。 */
    var ring: Int = 0,
    /** **哪一支赢了**：1=角上家族(H1)、2=轮廓家族(H2)、3=没有参照(H3)。
     *  没有它日志是会骗人的：H1 与 H2 都往 `族` 里写 1，"H2 有没有跑"这个问题我因此答错三次。 */
    var hyp: Int = 0,
    /** 这块参照**有多厚**（底像素到自身边界的距离 p85，像素）。见 [plateThickness]。 */
    var thick: Int = -1,
    /** H2 那一圈轮廓自己的散度（p97 ΔE）。见 [outlineFamilies] 里那段。 */
    var spread: Int = -1,
    /** 剩下的墨里有多少百分比**碰不到空气**（被这块底围住）。见 [enclosedShare]。 */
    var enclosed: Int = -1,
    /** H0：最大那块区域的**洞里**有多少个百分比的内容框是不透明的（= 坐在它上面的画）。
     *  见 [regionGround]；-1 = 这一支没接手这张图。 */
    var holed: Int = -1,
    /** 被 [PLATE_ALL_SHARE] 否决的那一支 H2 的占比与厚度（-1 = 没走到那一步）。只为进日志。 */
    var h2: Float = -1f,
    var h2thick: Int = -1,
    var h2spread: Int = -1,
    var h2enc: Int = -1,
    /** 被退掉的那一支 **H1** 自己的数（-1 = 没走到 / 没被退）。见 [groundPass] 里 `h1share` 那一段：
     *  只报"拦=16"看不出它吞到多少、剩下几格当墨，而这两样正是"该不该给这块底开豁免"的依据。 */
    var h1: Float = -1f,
    var h1thick: Int = -1,
    var h1fams: Int = -1,
    var h1ink: Int = -1,
    /** **墨那一侧有多少比例的格子与底的本色分不开**（0..100，只数 α=255 的格子；-1 = 有一侧是空的）。
     *  这是这一支的**验收**而不是描述：见 [GROUND_INK_SAME_PCT]。 */
    var inkSame: Int = -1,
    /** 底自己的平均色（Lab ×64）与参与平均的格数。带出来只为一个用途：出图那一侧要把
     *  **"没归进底的盘"** 从字形里分出去 —— 混在一起量的 `字均` 会骗人，见 [Scratch.vetoSame] 旁边
     *  那段与 build() 里 `字均净=` 那一条。gN=0 时这一组数没有意义。 */
    var gL: Int = 0,
    var gA: Int = 0,
    var gB: Int = 0,
    var gN: Int = 0,
    /** **实心**像素里被判成"墨"的那一半有多少格（与 [gN] 同一趟、同一口径：α=255）。
     *  两个数一起回答的是"这一刀把画面分成两半之后，哪一半才是主体" —— 见 [thinPlate]。 */
    var iN: Int = 0,
    /** 同一趟里被判成**墨**的那一半的平均亮度（Lab 的 L ×64，与 [gL] 同一口径）。
     *  极性要问的是「墨比底亮吗」，不是「底够不够亮」—— 见 [tonalBright] 那一段。 */
    var iL: Int = 0,
  )

  /**
   * 把一张光栅化的图标**合成**成单色层。
   *
   * 只有一个量、应用两遍 —— 这就是"没有分支"的意思。
   *
   * **量**：[departure] —— `dep = max(a − c, 0) + a × β × min(ΔE,EMAX)/EMAX`，0..255。
   *  a = 这一像素自己的覆盖率；c = 参照那把尺（默认 β，出图那一步换 `fTop`）；
   *  β = **参照自己的**覆盖率；ΔE = 与参照色的 CIELAB 距离。
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
  /**
   * @param plateIsLayer **这一张的承载面在图层里，不在像素里**。`true` 只有一种来路：
   *  自适应图标 + 背景层是 `ColorDrawable`，于是 `MonochromeSynth` 把**前景层单独**取了交给这里
   *  （桌面自己会补那张盘）。这一张里每一块实心都是设计师画的图形，
   *  所以底**只许是空气**（H3）—— 见 [groundPass] 里那条注释。默认 `false` = 整张位图那一路，
   *  盘有没有、是哪一块，只能从像素里判。
   */
  fun build(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false): Output {
    val total = w * h
    if (total <= 0) return Output(IntArray(0), 0f, 0f, 0f, 0, 0f, 0f, "尺寸非法 → 空层")
    val box = contentBounds(pixels, w, h)
      ?: return Output(IntArray(total), 0f, 0f, 0f, 0, 0f, 0f, "整张透明 → 空层")
    val boxArea = box.width().toLong() * box.height()
    val band = (minOf(box.width(), box.height()) / RING_BAND_DIV).coerceIn(2, 8)
    val g = if (minOf(box.width(), box.height()) >= FIELD_MIN_SIDE) FIELD_GRID else FIELD_GRID_MIN
    val s = checkNotNull(pool.get()) { "withInitial 建的 ThreadLocal 不会给回 null" }
    s.grid = g
    s.ensure(total, g * g)

    // 0) 每个像素的 Lab 只算一次：后面每一轮洪泛、每一次距离都只是查表 + 一次开方。
    for (i in 0 until total) {
      val p = pixels[i]
      s.labL[i] = labChannel(p, 0).toShort()
      s.labA[i] = labChannel(p, 1).toShort()
      s.labB[i] = labChannel(p, 2).toShort()
    }

    // **这张里到底有没有空气露出来** —— 按**整张画布**数，不按内容框。
    // 为什么必须是画布：这一位问的是"背景层那张盘有没有从前景层的空处透出来"，
    // 而盘是**铺满整个视口**的。按内容框数会把一个实心圆判成"没露空气"：
    // 混元那张圆盘在框内只露 21% 的角（圆占框 ≈78%，算术上就这样），卡在 25% 线下，
    // 于是"另外半个太极"又回来了 —— 而它前景层按画布数是 **61% 空**。
    var airN0 = 0
    for (i in 0 until total) if (Color.alpha(pixels[i]) < A_SOLID) airN0++
    val airCanvasPct = airN0 * 100 / total
    // ⚠ **这一位要自己带前提**：`plateIsLayer` 说"承载面在另一层"，而这句话只有**这张真的露出空气**
    //   时才成立。对表台量出来的反例 `com.limelight.qiin`：背景层是纯色，可前景层自己
    //   **92% 是实心的**（设计师在前景层里画了一整块场），于是"这张没有承载面"把整块场都判成墨 ——
    //   `11.7% → 92.8%`，而人画的是 `16.6%`。同一位管住的两张（kimichat 空 50%、frontpage 空 65%）
    //   都是**修好**的，所以问题不在这一位，在它没问"空气到底有没有"。
    // 🔑 **这条线不用挑**：整张画布口径下真机 + 对表这 11 张分成 **{0, 7} 与 {50…81}**，
    //   中间空 43 个点 —— 与今天否掉的那几条"在人群里画线"不是一回事。
    //   门槛沿用已有的 `TONAL_AIR_MIN`（25%），**不新造常数**。
    val plateLayer = plateIsLayer && airCanvasPct >= TONAL_AIR_MIN
    // 1) 两遍参照。顺序固定：第二遍要用第一遍剩下的那一半当工作集。
    val ground = groundPass(pixels, w, h, box, band, g, s, plateLayer)
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
    // ⚠ **可这条判别对"满幅、一格空气都没有"的图是反的**（智慧游戏 2026-09-28）：那种图
    //   走到 H3、参照是空气，而图里根本没有空气 → 参照色的覆盖率 β=0 → q 被乘成 0 →
    //   只剩距离轴 → "凡是实心都是墨"，交出去一块实心（`框内=0.94`）。用户对着它说的
    //   「感觉反下色更好」，量的就是这件事：他要的是"亮=墨"，也就是亮度轴接管。
    //   所以这里加一条**替身判别**：内容框几乎全不透明时，"参照=空气"这句话是空的，
    //   β 不再有权把 q 压下去。但**必须再问一句这张是不是真有两个调子** ——
    //   整张纯色也"没有空气"，而它的正确答案是一张空掩码（钉着的性质），
    //   用 `spread`（有符号 ΔL 那条斜坡的端点跨度）挡它：纯色那张 spread 退到下限。
    var boxOpaque = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) if (Color.alpha(pixels[row + x]) >= A_SOLID) boxOpaque++
    }
    val noAir = boxOpaque.toLong() * 100 >= boxArea.toLong() * NO_AIR_OPAQUE_PCT
    val cov = if (noAir && spread >= TWO_TONE_SPREAD) 255 else ground.meanCov
    // ⚠ **swallow 那一项有自己的前提，前提不在就别算它**（2026-09-29 长条形量出来的）。
    //   它的原话是"底占高到≈1.00 → 参照就是整张图，等于没有参照 → 距离项给不出任何形状"。
    //   可是"距离项给不出形状"等价于**墨那一侧一格都不剩**（`盘实/墨实` 的后一个数）。
    //   墨侧还有格子，前提就是假的：距离项明明排出了形状，这时再按占比把权重交给明度轴，
    //   抬起来的不是形状，是**一整块被判成底的盘子** —— 判据台 `长条快捷方式`：
    //   `底占=0.98 盘实=273244/6561 端=-42/-42` → 明度两端重合 → 整颗药丸 100% 出墨。
    //   反过来，墨侧真空的那一类**必须**留着这一项：真机带回来的
    //   `com.google.android.apps.photos/…view_screenshots_ic`（`底占=1.00 盘实=6424/0`）
    //   全靠它把图形从"整张都被洪泛吃掉"里捞出来（`框内=0.53 字均净=242`）——
    //   第一版按"跨度不够就整条关掉"改，正好把这一张改成了全空，所以撤回来换这条判据。
    //   takeover 那一侧（底占太**低**）与 wisdomgame 那条 noAir 支都不受这里影响：
    //   它们走的是另两个式子。
    val swallow =
      if (ground.iN > 0) 0f else (ground.share - LUM_SWALLOW) / (1f - LUM_SWALLOW)
    val q = (maxOf(
      1f - ground.share / LUM_TAKEOVER,
      swallow
    ).coerceIn(0f, 1f) * cov / 255f * 100).toInt()
    // 试过并否掉的第三条路（别再走一遍）：饱和段里有 ≥2 块竞争时，把明暗这条轴的
    // ±up/down 拉宽。实测反而更糟：四块从 201/221/227 掉到 143/157/162 —— 整段一起被压淡，
    // 间隔一点没开。原因是这条轴根本分不开它们：**白、黄、绿相对深蓝底都是"更亮"**，
    // u 的符号与大小都差不多，真正的区别在色相，而单色墨没有色相这一维。
    // 要在饱和段里排序只能另找一维（候选：按第二遍的 `off` —— 离主体的距离 —— 在段内全排序，
    // 而不是像现在这样只降档）。
    // ⚠ **第四条：墨全在参照暗侧时（`端` 的 p5>0），把亮度那条斜坡按 50/50 混进来** ——
    //   2026-09-30 真编了两份字节码跑完 A/B（补丁留在 `_harness/one_side_luma.patch`，**没收**）。
    //   **它确实把层次铺开了**：坚果云那张橡子 `分档 1/6(223,250,252,255,255,255)` →
    //   `2/6(125,152,246,255,255,255)`，看图从"一整块实心饼"变成看得见的果身/帽/描边三层。
    //   **但同一前提放进来的人里有本来正确的**：网易 UU `框内 0.18→0.09`、`字均净 208→171`
    //   （看图：深蓝那几个面从近黑退成中灰，对比掉了半档）；feimo 的 rec3 那份
    //   `字均净 187→84`、`淡 0.03→0.21`；中国移动 `顶占比 70→22`；kazumi `残盘 1180→16025`。
    //   真机 61 张动 9 张、老语料 113 张动 13 行，**没有一条现成的判据能把"该铺开的"与
    //   "本来就对的"分开** —— `端` 的 p5（坚果云 13 / 网易 UU 14）与 `顶占比`（58 / 48）都差一个
    //   身位以内。**这就是 [LEVEL_FLOOR] 那段表过态的那一笔：细腻与实心冲突时先不伤人。**
    //   要再碰这条，缺的还是那个"这两块是不是两个物体"的证据，不是把 50 调成 30。
    val up = SPLIT_UP * (100 - q) / 100
    val down = SPLIT_DOWN * (100 - q) / 100
    // 2.5) 这一张能不能**拿自己的调子当浓度**（满幅插画那一类，没有参照可谈）？
    //   是的话下面第 3 步的混轴、第 4 步的发档与抬顶全部让路。判据与方向各一条：
    //
    //   ① **开火只看跨度**（[TONAL_SPAN_MIN]）。纹理与平涂那两道 2026-10-08 拆掉 —— 它们挡住的
    //     正是逐张看图判"变好"的那批（驾考 纹理 17、azure 18、易班 13、微博 8）。
    //     跨度这道是**结构性**守卫而非阈值：真机上被"全开"弄空的那 6 张，跨度全是 0~7
    //     （支付宝 1、钉钉 0、翻译 7）—— 一块白剪影坐在空气上本来就没有调子可言，
    //     而下面那条斜坡会把最亮的白**直接归零 = 屏幕上整个不画**。
    //     ⚠ 那 6 张的像素就是回归台第八闸的语料（`_harness/rec_blank/`，底线 0 张），
    //     它是 2026-10-08 那次真机事故自己捞回来的，不是挑出来的。
    //   ② **方向不写死「暗=墨」**：底暗 → 墨在亮侧（翻），底亮 → 墨在暗侧（与旧行为一致）。
    //     用的**不是新尺子**，就是 [groundPass] 交回来的 `gL`（它找到的那块底有多亮）；
    //     前提沿用"这块底得真的存在"那一道（`gN` 至少占实心两成）—— 满幅插画那类 `ground`
    //     不可信，那时**不翻**，而那一类正是这一支存在的原因。
    //     为什么必须翻：`全开`（暗=墨写死）把饿了么/全球网测/Google 翻译三张**画成反色**，
    //     而钉着的性质里 `字形实心`/`白方块还是实心` 那族全报 `方块=0.0`（白字被掏空）。
    //     加上这一条之后 `SelfTest` 从红 23 条降到红 3 条、兼容性台 30→57。
    val tonal = tonalClass(pixels, w, h, box)?.takeIf { it.on }
    // 候选：盘子是**空气**时不许翻极性。"亮=墨"这条启发式存在的理由是"深盘上的白字"，
    // 而那需要一个**不透明的盘**；四周本来就是透明的时候，翻极性等于把最暗的笔画擦掉
    // （azure 的锁梁 L=35.8，比它自己那块被当成底的盾牌还暗 4.3 —— 一翻就归零）。
    // 盘是**空气**的时候不许翻极性（azure 锁梁那一条）—— 这里按**内容框**数，
    // 与上面 `airCanvasPct` 是两个问题：那一条问"盘有没有透出来"（画布），这一条问
    // "这张有没有一块不透明的盘"（内容框）。**别把它们并成一个。**
    var airN = 0
    if (tonal != null) for (y in box.top until box.bottom) for (x in box.left until box.right)
      if (Color.alpha(pixels[y * w + x]) < A_SOLID) airN++
    val airShare = airN * 100 / maxOf(box.width() * box.height(), 1)
    val groundIsAir = airShare >= TONAL_AIR_MIN
    // **极性问的是关系，不是绝对值**：「墨那一半比底亮吗」。
    // 原来那条 `底 L* < 80 就翻` 是一条绝对阈值，而 2026-10-09 把全语料的底 L 摊开之后
    // 它站不住（`_harness/InkSpanKt.kt`，只数"底占 ≥20%"的 50 张）：
    //   该翻的挤在 58~79（翻译 58、淘宝 60、饿了么 65、云闪付 68、小米智能家居 72、xbud 79），
    //   不该翻的 PH 是 **75** —— 就踩在这一堆中间。**这条线上没有第二个切点**，
    //   往左挪一格就要赔掉上面那几张。所以换成同一趟里已经攒着的 `iL`（墨那一半的平均亮度）：
    //   PH 的墨是 L=4 的黑三角 < 底 75 → 不翻（三角成墨、橙底归零）；
    //   云闪付的墨是 L=74 的白字 > 底 68 → 翻。**一个常数都不需要。**
    // ⚠ 上一版这里写的是 `iL > gL + 10`（留 10 的余量）。那 10 就是云闪付：它的墨只比底亮 6，
    //   加了余量之后整块蓝底被当成墨（`框内 0.35→0.61`）。余量不是保守，是把边界上的真答案切错。
    val tonalBright = tonal != null && !groundIsAir && ground.gN * 5 >= (ground.gN + ground.iN) &&
      ground.iL > ground.gL

    // 3) 出图：距离与明暗两条按 q 混成浓度，第二遍降层次。
    //    **第二遍能降多狠，只问一句：被降的那一层交出的墨，有没有比留下来的那层还多？**
    //    第二遍的前提是"壳是主体、比它亮的只是第二层（高光、镂空）"。这个前提会破：
    //    云闪付与淘宝闪购的白色主字相对深蓝/橙底就是"更亮"，于是**主体自己被当成第二层**
    //    压掉四成 —— 实测主字只落在 89~143/255，他报的「细节不足、过渡不自然」就是这个。
    //    判据不看"壳占画面多少"（试过：小洞本来就该刻掉，按面积一刀切正好把它否了），
    //    也不看两层的**像素个数**（真机重放里华为应用市场 / 12123 / 米家 三张全栽在这）：
    //    个数比的是"底板洪泛没吃干净的那圈中间调残边"和白色主字谁多 —— 残边天生贴底、
    //    天生占多数（华为：留 11668 像素 vs 被降 9131 → 按个数是"留的多"→ 满力降层，
    //    主字 dep₁=207 最后只剩 37）。屏幕上就是他报的「上色没上全」。
    //    ⚠ 也不能比**平均实度**（试过：被降侧均值 > 留下侧就退出）：它和"比主体更亮"是同一件事
    //    的两个说法，于是一下子命中 36/84 张 —— 太宽。按"两边各交出多少墨"（Σ dep₁）只命中
    //    13/84：华为 3442 vs 6431（被降的才是主体 → 退出）、饿了么 1382 vs 9012（退出，
    //    分档 4/6→5/6 反而更好）、抖音 7565 vs 7765（也退出：音符外面那两层错位影不再被刻开，
    //    分档 4→2，但框内 0.25→0.46，屏幕上是一个结实的音符而不是一圈重影）。
    //    全 84 张重放：框内没有一张下降，档数 7 张变多、2 张变少，空洞 0.032 不变。
    //    被降那一侧的墨**多过**留下来的那一侧 → 前提不成立，第二遍对这一张整个退出；
    //    否则力度只由"主体在墨里占多少"给（[bodyPass] 里同一个 Σ dep₁ 加权）。
    var knockCand = 0L
    var keep = 0L
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) < A_SOLID) continue
        val dep = (s.dist[i].toInt() and 0xffff).toLong()
        if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) > 0 &&
          (s.off[i].toInt() and 0xffff) > body.zero
        ) knockCand += dep else keep += dep
      }
    }
    // 日志里的这一对 = 两边各交出多少墨，除以 255 换成"等效实心像素数"。
    val keepInk = keep / 255
    val knockInk = knockCand / 255
    // ⚠ 上面那条比的是"两边各交出多少墨"，它挡得住"主体自己被当成第二层"，挡不住另一种：
    //   **比主体亮的那一块，可能是设计者的第二种颜色，不是镂空。** 哔哩哔哩（`tv.danmaku.bili`）
    //   量到的就是这一种：粉色电视机壳 + 白色 wordmark 压在壳上并伸出两侧，白字相对壳就是
    //   "更亮"，于是整条字被按高光刻下去 —— 交付成 143/218/240 一片灰渣，`空洞=0.35`、`cc=9`。
    //   判据不能是"参照实不实"（试过 `ground.meanCov<128` 一刀切：钉着的性质
    //   「透明底+围住的镂空」当场 FAIL，洞 0→225 —— 透明底上**被围住的**亮块确实该刻）。
    //   分开两者的是**围合关系**：被主体围住的亮块是镂空，贴着空气的亮块是另一种颜色。
    //   所以从"四邻有不是实心的地方"的那些候选格出发，只在候选格之间洪泛一次；
    //   走得到的（kc==2）不刻，走不到的（kc==1）照旧刻。
    //   ⚠ 种子只认**真的空气**（α<A_SOLID），不认底 —— 底上"透过亮块看得见板"正是镂空的定义，
    //   把底也算进去等于在这类图上整条退出。没有空气的图一个种子都没有，行为与改之前逐像素相同。
    // 满幅插画那一支不做"比主体亮就当镂空压下去"：它的浓度本来就是亮度，压一次等于把
    // 亮部又掏空一遍（那是参照模型的算法，不是它的）。
    val hollow = if (tonal != null || knockCand > keep) 0 else HOLLOW * body.weight / 255
    var kc = s.knockReach
    if (kc.size < total) { kc = ByteArray(total); s.knockReach = kc }
    if (hollow > 0) {
      kc.fill(0, 0, total)
      var head = 0
      var tail = 0
      val q2 = s.queue
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) < A_SOLID) continue
          if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) <= 0) continue
          if ((s.off[i].toInt() and 0xffff) <= body.zero) continue
          kc[i] = 1
          val open = (x == box.left || y == box.top || x == box.right - 1 || y == box.bottom - 1 ||
            Color.alpha(pixels[i - 1]) < A_SOLID || Color.alpha(pixels[i + 1]) < A_SOLID ||
            Color.alpha(pixels[i - w]) < A_SOLID || Color.alpha(pixels[i + w]) < A_SOLID)
          if (open) { kc[i] = 2; q2[tail++] = i }
        }
      }
      while (head < tail) {
        val i = q2[head++]
        val y = i / w
        val x = i - y * w
        for (d in 0 until 4) {
          val inside = when (d) {
            0 -> x > box.left; 1 -> x < box.right - 1; 2 -> y > box.top; else -> y < box.bottom - 1
          }
          if (!inside) continue
          val j = i + when (d) { 0 -> -1; 1 -> 1; 2 -> -w; else -> w }
          if (kc[j] == 1.toByte()) { kc[j] = 2; q2[tail++] = j }
        }
      }
    }
    // 3.4) **同一种源色交付同一种浓度。** `dep` 与参照都是**逐格**拟合的色场，所以"底"自己
    //   多色的时候，同一个颜色的像素会在相邻两格里拿到差一倍的离底距离 —— 抖音
    //   （`com.ss.android.ugc.aweme`，H2 把青+红两层错位影认成底）实测：白音符 8811 格全
    //   是同一个色，dep 按格子从 **122 一路散到 241**，出墨跟着从 162 散到 255，屏幕上
    //   就是一个被切成十几块灰度的音符（`_harness/RimDbg --cells` 那十二行）。
    //   这里按**源色本身**取该色墨侧"离底最远的那一格"当这一色的 dep/参照：底匀的图两者本来就
    //   相等（恒等，一个像素都不动），底多色的图整块回到一个浓度、且回到它最有把握的那一档。
    //   ⚠ 键必须是**精确颜色**，不能用 [SEP_BUCKETS] 那种粗桶（L 每 8、a/b 每 32）：
    //   第一版拿粗桶做，美团那张 `字均净 227→76` —— 黑字与黄底落进同一个桶，均值把字拖到中间调。
    //   ⚠ 代表值必须是**最远那一格**，不是均值：精确色＋均值那一版是
    //   `字均净 升84/降34（净+112）、淡(<200) 50→56（弄坏 9 张）、cc 变坏 37`；
    //   同一个键换成最远那一格才是下面引号里那组数。理由：一块颜色跨在两种深的底上时，
    //   均值把"看得清的那半"拖下来陪"本来就远的那半" —— 陪的是浓度，不是真相。
    //   渐变不受影响：同一个精确颜色的像素本来就落在同一条带上。
    val cDep = HashMap<Int, LongArray>()
    // 这张图有几种实心颜色，就得开几条表项 —— 所以这里必须有个上限。
    // 语料实测（`_harness/PartAreaKt --colours`）：老 113 张 **中位 1267 / p90 11088 / 最大 27986**；
    // ⚠ **但真机 61 张"会上屏"的那一半把上限撞穿了**：王者荣耀 **89951**、国际版 **72429** ——
    //   所以 65536 对真图标**不是惰性的**，这两张一直在走"退回按格子给浓度"那一条，
    //   而"按格子给浓度"正是它们画出一层细密斜痕的原因之一（`--shot` 的图上看得见）。
    //   2026-09-30 之后这两张改走满幅插画那一支（[tonalClass]），根本不建这张表，所以
    //   上限暂时没被真图标碰到 —— **但别把这句当成"65536 够用"**：再抓到一张满幅插画而它
    //   没过那三条判据，就会又落回这里。它挡的是异常大的那一张：满幅照片 × 超大边长可以开到
    //   几十万个精确色，就是几十 MB 的瞬时分配，而这段跑在桌面的图标加载线程上。
    var cDepOff = false
    if (tonal == null) for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) != 255) continue
        if (cDepOff) continue
        val k = pixels[i] and 0x00FFFFFF
        val v = cDep.getOrPut(k) { LongArray(3) }
        if (cDep.size > CDEP_MAX) {
          // 越界就整张退回"按格子给浓度"（`f2196b7` 之前的行为）：颜色多到这个量级的图，
          // 本来就没有"某一块颜色应当匀成同一档"这回事可言 —— 清掉比留着半张表更诚实。
          cDep.clear(); cDepOff = true; continue
        }
        val d = s.distT[i].toInt() and 0xffff
        v[2]++                                              // 顺手数这种颜色交付了多少格（见 [modalInk]）
        if (d > v[0]) { v[0] = d.toLong(); v[1] = refLight(i, s).toLong() }
      }
    }
    // **主体是哪一种颜色**：交付了墨的那批格子里最多的那一种源色。下面第二遍的镂空那一层要用它。
    // 放在这张表里数而不是另开一趟扫描：这张表访问的正是同一批格子（非底、实心），而这一段
    // 跑在桌面的图标加载线程上，多一趟全框遍历是白花的。
    var modalInk = -1; var modalN = 0L
    for ((k, v) in cDep) if (v[0] > ground.zero && v[2] > modalN) { modalN = v[2]; modalInk = k }
    // 主体那一色的 Lab。下面那道"主体不许被自己挖空"的闸**按这个比**，不按精确 RGB 比 ——
    // 理由见 [MODAL_INK_DE]。
    val miL = if (modalInk < 0) 0 else labChannel(modalInk, 0)
    val miA = if (modalInk < 0) 0 else labChannel(modalInk, 1)
    val miB = if (modalInk < 0) 0 else labChannel(modalInk, 2)
    val out = IntArray(total)
    var knocked = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        // ⚠ **只抬"本来就当它是墨"的格子。** 自己那一格 `dep ≤ 容差` 的像素按模型的说法就是底，
        //   让它借用同色另一格的浓度，等于把盘子画回去 —— `f2196b7` 落地时没量这一列，
        //   判据台 113 张真图标的 `残盘=` 从 4923 涨到 26440（40 行涨、0 行跌，9 行 >1000，
        //   最狠浙江在线 0→5334），正是他报过两次的那圈"看得见的灰盘"。
        val own = s.distT[i].toInt() and 0xffff
        val v = if (own > ground.zero && s.plate[i] == 0.toByte() && Color.alpha(pixels[i]) == 255)
          cDep[pixels[i] and 0x00FFFFFF] else null
        val dep = if (v != null) v[0].toInt() else own
        val ref = if (v != null) v[1].toInt() else refLight(i, s)
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
        var a = if (tonal != null) {
          // 这一支的浓度 = 这张图自己的亮度，**暗=墨**，端点是它自己的 p5/p95。
          // 透明像素照样不给墨（乘上源 alpha，与下面 `tl` 同一条规矩）。
          val l64 = s.labL[i].toInt() and 0xffff
          val ramp = if (tonalBright) l64 - tonal.lo else tonal.hi - l64
          var ta = (ramp * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255)
          // **盘在另一层的这一张：斜坡的零端不许发给标记自己的面。**
          // 零端本来给的是"离墨最远的那一块"，也就是**承载面**；而 [plateIsLayer] 为真时这张里
          // 根本没有承载面（只有前景层，盘是背景层里那张 `ColorDrawable`，桌面自己会补）。
          // 于是最亮那个刻面被推到 α=0 —— 真机上 `com.netease.uuremote` 四个面丢两个、
          // `com.xunmeng.pinduoduo` 整片是灰，都是这一条。压回 128..255 之后内部对比仍占满一整段。
          // ⚠ **顺序是全部重点**：地板压在 `× 源α / 255` **之前**，空气还是 0。上一版
          //   （`cand/MonochromeMask.ghostFloor.kt`）把它压在乘法之后、又拿 `ground.gN==0` 猜这一位，
          //   13 张变实心方块 —— 那个方块是**空气被抬起来了**，不是标记变实了（第九闸 0/219 钉住这件事）。
          if (plateLayer) ta = PLATE_FLOOR_MIN + (255 - PLATE_FLOOR_MIN) * ta / 255
          ta * Color.alpha(pixels[i]) / 255
        } else (td * (100 - q) + tl * q) / 100
        if (a == 0) continue
        // 比参照暗的往上、亮的往下，**按这张图自己的跨度成比例** —— 不是"暗/亮"两档。
        // ⚠ 上限不对称是有理由的：白字在彩色底上是最常见的"亮侧实心"，往下压狠了就是他报过的
        //   「主题色怎么这么浅」。q 上去之后这两侧自动退场：那时明暗已经是浓度本身了。
        //   满幅插画那一支整个跳过这一步：它要的**就是**一条按亮度排的斜坡，再叠一次明度序
        //   等于把同一条轴乘两遍（斜率变陡，中间调全被推到两端 —— 正是他报的「过渡生硬」）。
        if (tonal == null)
          a = (a + a * (u * 100 / amp).coerceIn(-down, up) / 100).coerceIn(0, 255)
        // 第二遍只降**比主体亮**的那一侧（镂空、高光、白 logo 上的洞）。比主体暗的那些是图形
        // 自己的暗部，降它等于把细节擦了 —— 与上面那条明度序同一个极性。
        // ⚠ 但**主体自己那一色不许降**。这一层的参照取的是"离这格最近的那块主体"，于是一块
        //   大的实心字形（白 logo、白条）坐在渐变底上时，字形中间那片"最近主体"会落到它自己
        //   压着的深色上 —— **主体把自己挖空**。兼容性台 `渐变底+白字` 就是这个现场，而且它
        //   **随边长变坏**：96/192 好，384/768 字形内部只剩 76% 实心（`_harness/LineDbg` 沿
        //   y=192 那一行量到：同一条纯白、源 α 都是 255，x=84 出墨 255 而 x=192 出墨 83，
        //   那两列的 `离体=175`、参照 L 从 100 翻到 49）。加上这一条之后 384/768 回到 100%，
        //   而「镂空刻出来了」那条性质照旧绿 —— 洞本来就是**另一种**颜色，不是主体的颜色。
        if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) > 0 && kc[i] != 2.toByte() &&
          deltaE(s.labL[i].toInt() and 0xffff, s.labA[i].toInt() and 0xffff,
            s.labB[i].toInt() and 0xffff, miL, miA, miB) > (MODAL_INK_DE shl 6)
        ) {
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
    }    // 4) 交出去之前把墨量抬到满阶（他要「图标上必须有系统最深和最浅的取色」）。**两层叠加**：
    //    全局那一层管"整张最深都不够"，字形那一层管"只有 1% 到顶、主体停在 140"。
    //    ⚠ 中间夹一步发档（4.5），两层不能并排 —— 理由写在那一段里。
    val global = if (tonal == null) stretchGlobal(out, total) else null
    global?.let { g ->
      for (i in 0 until total) {
        val a = out[i] ushr 24
        if (a != 0) out[i] = (g.first[a] shl 24) or 0x00FFFFFF
      }
    }
    // 4.5) 把"源色看得出差别、交付却并成一片"的那几块各自让开一档（见 [assignLevels]）。
    //   ⚠ **这一条必须夹在两层抬顶中间**，不是随手放的：
    //   · 放在两层**之前**（旧位置）—— 第一层是整张分布的 LUT（p99→255），它会把刚分开的
    //     两档一起贴上顶。判据台那条「相邻部分必须有差别」量到的最小间隔 20 就是这么来的：
    //     源里那两块是 201 与 221，发档时"不许往上抬"（[LEVEL_FLOOR] 那段）+ 地板 200，
    //     可分的最多 21 格，**差 25 在算术上就做不到** —— 除非先有人把它们抬上去。
    //   · 抬上去这件事正是第一层该干的（它只抬不压，抬的是"整张最深都不够"），所以让它在
    //     发档**之前**跑：LUT（那张图是 p99=227→255）把 201/221 抬成 225/248，发档再让开成
    //     200/225/255，最小间隔 20→25，那条钉了一整晚的性质当场绿（46 条全过，一条 GAP 不剩）。
    //   · 第二层 [stretchMark] 留在发档**之后**：它管的是"单色字形必须实心"，正好补回
    //     让开档位让出去的那点浓度。两层换过来试过（发档挪到最末尾）：并档 37→28 对是多了，
    //     代价是 `字均净<200` 从 57 涨到 72（弄坏 16 张、修好 1 张）—— 那是他最早点名的
    //     「颜色偏淡」，[LEVEL_FLOOR] 那段已经表过态：细腻与实心冲突时先不伤人。所以不取。
    if (tonal == null) assignLevels(out, pixels, w, box, s)
    // 4.6) 一条连续的描边被颜色渐变切成几截 —— 按**拓扑**把它接回去（见 [propagateBands]）。
    //   ⚠ **必须放在发档之后**：放之前它会改掉色桶的均值，`assignLevels` 于是重排、整桶平移量跟着变，
    //   结果"接一条线"这件事被放大成"改一整张的台阶"（易班上看跳≥30 的连续像素对 0%→1%、p99 25→52，
    //   而真正被补的只有 20 格 —— 硬边是发档造的，不是那 20 格）。放在发档之后，两者才解耦。
    val bands = if (tonal == null) propagateBands(out, pixels, w, h, box, s) else Triple(0, 0, 0)

    // 4.7) **同一块平色被给了两套浓度的，按这块自己的多数定调统一**（可抬，见 [flatVote]）。
    //   放在接带之后：`propagateBands` 补的是"一条连续描边被渐变切成几截"，它先跑完，
    //   这一块才拿到最终浓度 —— 在它之前统一会被它再改一遍。
    // ⚠ **三笔账每次先归零**：它们是 Scratch 上的共享字段，而满幅那一支（`tonal != null`）
    //   压根不跑这一刀 —— 不归零的话那张日志会把**上一张图**的 `票=` 印在自己行里
    //   （第一次跑 12 张点名表时，智慧树就顶着浙江在线的 4126 抹，看着像它也开火了）。
    s.voteErase = 0; s.voteRaise = 0; s.voteHits = 0
    val voteN = if (tonal == null) flatVote(out, pixels, w, h, box, s) else 0
    val stretch = "${global?.second ?: "已满"}+${if (tonal == null) stretchMark(out, pixels, w, h, box, s) else "已满"}"

    // 5) 形状体检。**修不修形状看浓度是谁给的**：颜色给的（β 高）才可能有"切底切出来的杂点"，
    //    覆盖率给的（β≈0）那些淡值全是设计者自己画的柔光与抗锯齿，擦掉就是一圈锯齿、桥起来
    //    就是给一条笔画凭空加粗。（旧版这里是两支各写死的 `bridge=/despeckle=` 常量。）
    // 满幅插画那一支**不做形状修补**：`bridge` 填的是"阈值切出来的淡边"（把 1..127 的格子
    // 按邻居继承成实心），`despeckle` 擦的是"切底切出来的杂点" —— 两样都是**参照那条路**的
    // 产物。这一支的淡格是这张图自己的中间调，桥一下就是凭空多一块实心、擦一下就是丢一格
    // 细节，而它压根没有"切底"这一步可言。
    val colourDriven = ground.meanCov >= 128 && tonal == null
    // 量的是**交出去那一张**：分档 = 几块 / 落在几档，括号里是每块的浓度。
    // ⚠ 这个数现在**只是诊断**，没有配套的修法 —— 曾按它铺过两趟单调重排（dep 轴 + 浓度轴），
    // 平均档数 2.66→2.94、完全分开的 8/32→12/32，看着是进步；但同一改动在真图上画出过
    // **一块看得见的灰方框**（azure：盾牌外面那圈柔光被当成"一个部分"，重排就发给它一整格
    // 浓度，于是留白变成实心方块，还带一条横带）。A/B 对过：撤掉保底仍在、撤掉重排才消失。
    // 一笔买卖换不来一个方框，所以修法撤了、数留着 —— 下一步的锚点必须先把"柔光/留白"
    // 排除在"部分"之外（按面积占比与离底距离双重门槛），而不是先铺匀再找补。
    // 浓度的体检数，**只为进日志**（不参与任何判断）：只看"源像素本来就实心"那一堆墨 ——
    // `字均` 是它们的平均浓度，`顶占比` 是其中到 250 以上的比例。为什么要单独两个数：
    // `框内=` 只数"多少像素上了墨"，不数"墨有多实"，所以 87 张真机像素里 36 张字形平均不到 200，
    // 判据却一条都不红（见 [stretchLut] 那段）。这两个数就是那条判据的原料。
    //
    // ⚠ **`字均` 单独一个是会骗人的，2026-09-28 被它骗过一次。** 它把两样东西混在一个均值里：
    //   真正的字形，和**"没归进底的盘"**（源里实心、颜色就是那块底、只是洪泛没走到）。小步点
    //   （`run.xbud.android`）实测：白闪电 12314 格出墨 **253/255**（一点都不淡），而盘自己有
    //   12078 格没进底、出墨 13 —— 两个混一平均就成了 `字均=169`。于是"机上有 66/190 张偏淡"
    //   这个数是按一个假指标排出来的。所以这里把两半**分开量**：`字均净=` 只算与底本色分得开的
    //   那些格（＝字形），`残盘=` 是另一半的格数。判"淡不淡"用前者，判"盘边漏没漏"用后者。
    // ⚠ **不能用"与底的全局均值同色"来认残盘。** 那样量出来的数好看、也完全错：盘子是渐变或
    //   多块拼底时均值落在中间，会把一种**离自己那一格参照差 40 多个 dep 的真实颜色**判成"和底
    //   同色"。浙江在线（`cn.com.zjol`）实测：按均值算 39624 格"残盘"、墨均 216，看着像一大块
    //   盘被当墨画了；按模型自己的 dep 算，那些格子 dep=42 ≫ 零=15，它们是**真的图形**，只是
    //   恰好接近盘的中间调。113 张上两种定义差 30 倍（614851 格 vs 127320 格）。
    var markN = 0; var markSum = 0L; var markTop = 0
    var figN = 0; var figSum = 0L; var figTop = 0
    var resN = 0; var resSum = 0L
    var bfN = 0; var bfSum = 0L
    // 满幅插画那一支"把被叫作底的格子画上了墨"另记一笔，**换名字记，不藏**。
    // 理由与 `带内残` 那一条一模一样：`残盘` 的定义是"非 plate 且 dep ≤ 零"，它要防的是
    // **参照那一条路把整片盘子点亮**；而这一支的前提就是"这一张的 plate 标签不可信"
    // （`tonalClass` 的三条判据就是在说"没有哪一块是盘"）。混在一起量等于把这一支的
    // **目标物计成损伤** —— 第一次跑就是这么撞上 5787→40685 的，归因之后：
    // 未走这一支的 105 行合计 3540（比基线还低 2247），涨的 37145 格 100% 落在 fired 的 8 行上。
    var tonResN = 0; var tonResSum = 0L
    for (i in 0 until total) {
      if (Color.alpha(pixels[i]) < 255) continue
      val a = out[i] ushr 24
      if (a == 0) continue
      markN++; markSum += a; if (a >= 250) markTop++
      // dep 在这里就是判连通用的那一个数（fitGround 交付段之后，非底的格子没被改写过）。
      // ⚠ **沿细带补上去的那些格（`bandSeen==3`）另记一笔，不混进 [resN]**：
      //   `残盘` 的定义是"非 plate 且 dep ≤ 零"，而 [propagateBands] 专门补的就是"颜色离底很近、
      //   但属于一条已成墨的带"的格 —— 混在一起量，这一刀的**目标物**会被计成**损伤**
      //   （第一次跑就是这么撞上 5787→12327 的：+6540 格 100% 落在 fired 的 8 张上，
      //   没 fired 的一张没涨）。分开记，`残盘` 回到它原本要防的那件事："底被整片点亮"。
      if (s.plate[i] == 0.toByte() && (s.distT[i].toInt() and 0xffff) <= ground.zero) {
        if (tonal != null) { tonResN++; tonResSum += a }
        else if (i < s.bandSeen.size && s.bandSeen[i] == 3.toByte()) { bfN++; bfSum += a } else { resN++; resSum += a }
      }
      else { figN++; figSum += a; if (a >= 250) figTop++ }
    }
    // 满幅那一支把「图标自己的底色」也当成一个调子转录了 —— cainiao 那块蓝方底 L*≈50
    // 被映射成 α≈126（分档=3/4(0,126,237,254) 里那个 126 就是它），屏幕上是一层半透明灰，
    // 和已适配图标那种干净的盘对不上。这里把参照**已经判成底**的那些格直接归零。
    // ⚠ 只在这块底**一直顶到画布边缘**时才这么做 —— 那是「整张画布就是一张盘」的形状证据。
    //   azure 的「底」是盾牌、四周是空气、不顶边：刚修好的锁梁不会被这一刀带倒。
    if (tonal != null && tonal.flat >= PLATE_FLAT_MIN && plateTouchesBorder(pixels, w, h, s))
      for (i in 0 until total) if (s.plate[i] != 0.toByte()) out[i] = 0
    val sep = separation(out, w, h, box, s)
    val st = measure(out, w, h, box, s, bridge = colourDriven, despeckle = colourDriven)
    val fill = st.kept.toFloat() / boxArea
    return Output(
      out, fill, st.hole, st.largest, st.components, st.smallShare, softShare(st, box),
      "票=${s.voteErase}抹/${s.voteRaise}抬/${s.voteHits}块 带占=${s.bandSolid * 100 / 255} 底占=${pct(ground.share)} H=${ground.hyp} 族=${ground.families} 底厚=${ground.thick}" +
      " 同色=${ground.inkSame}/${s.vetoSame} 拦=${s.veto}" +
      // 实心两半各多少格：`盘实<墨实` 就是"这块底比它承载的东西还小"，见 [thinPlate]。
      // 判据台上量过一次就够、日志上要能当场看见 —— 抖音那一张退掉之前是 6294/9614。
      " 盘实=${ground.gN}/${ground.iN}" +
      " 盒布=${boxArea * 100 / (w.toLong() * h)} H0=${s.h0why}" +
      (if (ground.holed < 0) "" else " 洞墨=${ground.holed}") +
      (if (ground.h2 < 0f) "" else " H2被否=${pct(ground.h2)}/${ground.h2thick}/${ground.h2enc}/${ground.h2spread}") +
      // H1 被退掉的那一档：`占比/厚度/族数/剩下当墨的实心格`。放在**整串末尾**，
      // 前面所有列的位置一个都不动 —— 按列号读的脚本（`res.sh`）不能再被挪一次。
      (if (ground.h1 < 0f) "" else " H1被否=${pct(ground.h1)}/${ground.h1thick}/${ground.h1fams}/${ground.h1ink}") +
      // ⚠ `围=` 这一列**已经被 H0 的"围合度"占了**（`ground.enclosed`，取值 -1..100）。
      //   新加列别再叫它：2026-10-01 那版"围住者"那一刀也叫 `围=`，A/B 读到的全是这一列的旧值，
      //   新刀一次没响都看不出来 —— 换了 `嵌=` 才看见它其实擦了 39/113 张。
      " 围=${ground.enclosed} 环散=${ground.spread} 零=${ground.zero} 实=${ground.solid} 端=${lo shr 6}/${hi shr 6} " +
        "壳占=${pct(body.share)} 层比=$keepInk/$knockInk " +
        "降=${pct(knocked.toFloat() / boxArea)} 降力=${body.weight} 力度=$hollow 明暗=${q} " +
        "框内=${pct(fill)} 字均=${if (markN == 0) -1 else (markSum / markN)}" +
        " 顶占比=${if (markN == 0) -1 else markTop * 100 / markN} " +
        "字均净=${if (figN == 0) -1 else (figSum / figN)} 净顶=${if (figN == 0) -1 else figTop * 100 / figN}" +
        // 残盘带两个数：**格数 / 它们平均出了多少墨**。只有后者才说"看不看得见" ——
        // QQ 那 17730 格 dep 只有 10（围住的背景，洪泛只收与边界连通的），出墨≈0，屏幕上根本
        // 不存在；小步点那 11640 格出墨 80~130，就是盘外那一圈看得见的灰环。只报格数会把两者
        // 混成"52 张有残盘"这种没法用的数。
        " 残盘=$resN/${if (resN == 0) 0 else resSum / resN} " +
        "cc=${st.components} top=${pct(st.largest)} 小片=${pct(st.smallShare)} " +
        "空洞=${pct(st.hole)} 淡=${pct(softShare(st, box))} 色驱=${ground.meanCov} " +
        "色阶=$stretch " +
        "分档=${sep.second}/${sep.first}" +
        (if (sep.third.isEmpty()) "" else "(${sep.third.joinToString(",")})") +
        // 接回去的细带：几条、补了多少格。放在**整串末尾**，前面列位一个不动（`res.sh` 按列号读）。
        // `带内残` 是这些补上去的格里"颜色本来就离底很近"的那些 —— 它**故意不从 `残盘` 里消失**，
        // 只是换个名字记：这样"这一刀补了多少、其中有多少看着像底"两件事在日志里都看得见。
        " 带补=${bands.first}/${bands.second}(${bands.third}拒) 带内残=$bfN/${if (bfN == 0) 0 else bfSum / bfN}" +
        // 满幅插画那一支开了就把三个判据数报上；**没开一个字符都不加** —— 这两列在整串末尾，
        // 前面所有列的列位一个不动（`res.sh` 按列号读，这条规矩上面已经写过两次）。
        (if (tonal == null) "" else " 调=1 纹理=${tonal.tex} 主色=${tonal.flat} 跨=${tonal.span}" +
          " 调残=$tonResN/${if (tonResN == 0) 0 else tonResSum / tonResN}"),
      ground.hyp,
      s.h0why,
      s.veto
    )
  }

  // ─────────────────── 满幅插画那一支：不要参照 ───────────────────
  //
  // 2026-09-30 主人一次点名四张：`com.neowizgames.game.browndust2`、`com.levelinfinite.sgameGlobal`、
  // `com.tencent.tmgp.sgame`、`com.zyddz.wisdomgame`。它们在模型里落到**四支不同的分支**
  // （H1 底占 0.86 / H2 底占 0.20 / H3 空气参照 ×2）、`明暗` 权重 0 / 59 / 100 / 1，
  // 错法也四种：整块实心饼、拼布带方块、几乎全空（框内 0.07）、洗成灰雾（cc 252）。
  // 而四张的内容类型是同一个：**满幅插画 —— 压根没有"哪一块是盘"这件事**。
  // 这一族前面五把尺子（占比 / 厚度 / 围合 / 环散 / 轮廓归一）全在问"那块参照长什么样"，
  // 问得再好也问不出答案，因为**这一类没有参照**。所以这一支换问题：
  // **这张图自己有没有连续的调子可发** —— 有就直接拿它自己的亮度当浓度，不经过参照。
  //
  // 极性固定"暗=墨"，与 [polarity] 那一段真机量出来的方向一致（墨比参照暗才该翻）。
  // 斜坡端点取这张**自己**亮度的 p5/p95（不是最值）：一条 JPEG 噪点线不该决定标尺 ——
  // 与上面 `LUM_LOW_PCT/LUM_HIGH_PCT` 同一个口径，只是那条量的是"离参照多远"，
  // 这一条量的是"这张图自己有多亮"，**这是这一支唯一的新信息**。
  //
  // ⚠ **不能对所有图标开**：红盘白字那一类（网易 UU 的深蓝盘 + 青色环、audiofx 的四块扇形）
  //  拿亮度当浓度会把盘子画满、字掏空 —— 正是主人最早报的「颜色反一下」。所以开火条件是三条
  //  **同时**成立。阈值由 `_harness/TonalGateKt.kt` **直接调这一个函数**在 122 张真机语料 +
  //  113 张老语料上量的分布定的（不是另一套自算的量 —— 那两处差一点就是两个判据），
  //  每一条各挡一类反例，`纹理/主色/跨` 三个数是实测值：
  //   · [TONAL_TEX_MIN] 纹理：相邻格"差得开但差得少"的占比。平涂 logo 内部几乎恒等、
  //     只有边界大跳 → 天生低。挡掉 饿了么 26 / 驾考宝典 24 / limelight 23 / UU·audiofx·webapk ≤12。
  //   · [TONAL_FLAT_MAX] 有没有一整块平涂占到画面大头。挡掉 智慧树（书法字压在白底上，
  //     纹理 68 比点名的任何一张都高，可它**就是**一块平涂底：主色 28）。
  //     ⚠ **这条线原来画在 14，2026-10-01 挪到 16，理由是"同一个应用的两次抓取跨在两侧"**：
  //     feimo 的语料那张 `32/13/65` 过线、真机带回来的同一张 `35/15/67` 不过 ——
  //     **14 落在 13 与 15 之间，也就是落在自己的测量噪声里**。挪到 16 之后两张一致过线，
  //     而全语料（122 + 113，96 与 384 两档）多进来的**只有 feimo 那一份**，一行不多。
  //     方向也复核过：feimo 真机那张换支前 92% 是空的、只剩眼睛与嘴，换支后是一整张能认的脸。
  //   · [TONAL_SPAN_MIN] 这张自己的亮度跨度。挡掉 京东 35/**2/46** 与 Xbox 53/1/**50**
  //     （一张有阴影的球：只有一个物体的调子，不是满幅画）。
  //  走这一支的：王者荣耀 54/0/85、国际版 51/0/83、browndust2 47/0/81、智游斗地主 43/8/65、
  //  feimo 32/13/65（真机那份 35/15/67）。**逐张看过图**，五张都比换支前好（前四张是主人点名的，
  //  feimo 是同一类里唯一过线的：换支前 88% 是空的，只剩两只眼睛）。
  //  ⚠ 真机复核（2026-10-01 00:10 那一轮，200 张唯一图标）：走的正是点名的**四张**，
  //  `框内 0.32~0.55`；`cc` 从 1 涨到 28~173 是**预期的** —— 一张肖像的中间调天生连不成几个大块，
  //  那一列在这一支上不数碎片，看图两张（browndust2 / 智游斗地主）都是干净的整脸，没有麻点。
  //  ⚠ 线不敢再往下挪：没走的那批里纹理最高的是**驾考宝典 24**，而它下面一格就是
  //  **limelight 23** —— 那一张换支前是一条 crisp 的描边（看图判"现在这样更好"）。
  //  24 与 23 之间没有能站人的地方，所以"驾考宝典也该修"这条**记成已知边界**，不是没看见。
  //
  // ⚠ 纹理与主色这两条在**格子**上算（与 [POLAR_EDGE] 同一套路），跨度在**逐像素直方图**上算。
  //  逐像素版的纹理随边长漂：
  //  王者荣耀在 96/192/384/768 上是 55/67/73/36（768 是放大出来的，四邻几乎相等 → ΔL=0
  //  被排除在外），拿它定阈值就是今天下午那条 H2 入场券"按面积不按周长"的同一个病。
  //  ⚠ 这里原来还写着"降采样到固定格之后**开火集合在四个边长上一字不差**" —— **那句到 2026-10-09
  //  重量时已经失效**（真机语料 192 vs 384 差 6 张）。失效的原因不在格子上，而在"框比格小就不算"
  //  那一句：它跟着纹理一起被写进来，纹理 10-08 退出开火判据之后它还在替纹理赶人。
  //  现在格子按 `min(96, 框)` 缩，那一句删了 —— 账见 [tonalClass] 的文档。
  //
  // ⚠ 这一支**跳过**发档 [assignLevels]、接带 [propagateBands]、两层抬顶 [stretchGlobal] /
  //  [stretchMark]：那四样都是在"参照给出一条斜坡"的前提上排序与抬顶，而这一支的浓度本来就是
  //  这张图的亮度。特别是发档 —— 它按 13×8×8 的**粗色桶**合并，一张插画会被它压成 ≤6 段灰带，
  //  正是 `d51d4d0` 修掉的那个"一根线上四道灰"。

  /** 这一支判据格的**上限**（框更小就按 `min(96, 框)` 缩 —— 2026-10-10：不许反过来把人判成不可测）。
   *  96 与 [POLAR_EDGE] 同值：判"有没有参照"不需要全分辨率。 */
  private const val TONAL_GRID = 96

  /** 纹理的那一条带：相邻两格**差得开**（>0）但**差得少**（≤[TONAL_TEX_HI] 个 L\\*）。 */
  private const val TONAL_TEX_LO = 1
  private const val TONAL_TEX_HI = 5

  /** 三条开火线的**原始**数值，来自 `_harness/TonalGateKt` 在 235 张上的分布。
   *  ⚠ **2026-10-08 起只有 [TONAL_SPAN_MIN] 还在开火判断里**（见 `build` 第 2.5 步）——
   *    纹理与平涂这两道拆掉了，但 `tonalClass` 仍然算它们，因为 `TonalGateKt` 那台定线台
   *    要靠这两个数继续盯"下一张是谁会被放进来"。**别把它们当死常量删掉。**
   *  [TONAL_SPAN_MIN] 从 55 降到 40 是**极性修好之后**才重量的（之前挪阈值等于同时挪两件事）：
   *    55→40 只多红 1 条性质、第八闸仍 0 张空白，而多放进 20 张（含主人点名的京东「头顶没了」）。 */
  private const val TONAL_TEX_MIN = 30
  private const val TONAL_FLAT_MAX = 16
  private const val TONAL_SPAN_MIN = 35

  /**
   * 「主色」那条门的**唯一一条豁免**：不是"这块平涂亮不亮"，而是**两条独立的路线在这一件事上同意**。
   *
   * 这一支的极性是「暗 = 墨」，参照那一支的极性是「底 = 交成 0」。当**参照那一支找到的那块底
   * 本身就是一大块亮的东西**，两条路线说的是同一句话：那块亮的该消失。这时候换这一支
   * 不会改变"谁该消失"，只会把"剩下的东西之间怎么排浓度"从按参照换成按亮度 —— 而按亮度那条
   * 在渐变上不会断（[TONAL_FLAT_MAX] 挡不住的那种断，正是主人报的「线条不全」）。
   *
   * 为什么**不能**拿"整张平均亮度"或"那块平涂的亮度"当豁免（两种都试过、都被证伪）：
   *   · 拿"占大头那一格平均色的亮度"：放进来 2 张 —— 智慧树（要的）与 **Kimi 自带单色层**
   *     （`com.moonshot.kimichat…_mono`）。后者换支之后 `字均净 253→148`、**`实255` 12261 格 → 0**，
   *     整张再没有一个实心 —— 正是这一支最不该犯的「颜色反一下」。
   *   · 换成"全图不透明像素的平均 L\\*"：Kimi 那张 **69**、智慧树 **83** —— 要把线画在 69 与 83 之间
   *     就是**为一个图标挑一个数**，而这一族已经有六条这样挑死的路（见 README 那六把尺子）。
   *   ⚠ 而且 Kimi 那张**没有踩红任何一条钉着的性质**（54 条全绿）。**性质台只覆盖写下它那天想到的
   *     摆法** —— "全绿"从来不是"安全"。
   * 而"两条路线同不同意"这条一量就分开了：Kimi 的单色层 `盘实=0/13127` —— **它的参照那一支
   * 根本没找到任何一块底**（`底占=0.82` 全是画布外的空气）。没有底，就没有"同意"可言，
   * 这一支一开就等于宣布"黑盘是图形、白 K 是背景"。
   *
   * 20% 那一半是不许"几乎没找到底"的图蒙进来：`gN` 至少要占实心的两成，才谈得上"它有一块底"。
   *
   * ⚠ 这里原来还挂着第三个条件 `底 L* < TONAL_PLATE_L_MIN(80)`，**2026-10-09 删掉了**：
   *  把全语料的底 L 摊开看，该翻的挤在 58~79、不该翻的 PH 是 75，这条线上没有第二个切点
   *  （分布与逐张名单见 [build] 里 `tonalBright` 那段）。它换成的是「墨比底亮吗」这一条**关系**。
   *  留这段不是为了记那个数，是为了记**它为什么不够**：上面那两种"拿平均亮度当豁免"的死法，
   *  和 80 这一条的死法是同一个 —— 绝对阈值在一堆中间调里没有落脚的地方。
   */
  private const val TONAL_AIR_MIN = 25
  private const val PLATE_FLAT_MIN = 40
  /** 甲类（盘在另一层）时满幅那条斜坡的**下界**：零端留给空气，标记最亮那一面不许归零。
   *  128 = 一半浓度，不是挑出来的数 —— 内部对比仍然占满 128..255 这一整段。 */
  private const val PLATE_FLOOR_MIN = 128

  /** 判据产物。[lo]/[hi] 是这张自己亮度的 p5/p95（L\\*×64），[span] = hi−lo。 */
  internal class Tonal(val tex: Int, val flat: Int, val span: Int, val lo: Int, val hi: Int) {
    /** **开火判据只有一处定义**，就在这里 —— 2026-10-08 起只剩跨度那道（结构性守卫：
     *  一块白剪影坐在空气上没有调子可言，而这一支的斜坡会把最亮的白直接归零 = 屏幕上整个不画）。
     *  ⚠ [tex] 与 [flat] **仍然照算**，但只给 `_harness/TonalGateKt` 那台定线台看"下一张是谁会被放进来"；
     *    它们不再是判据。**别把这条 getter 改回三条 AND** —— 那会让定线台重新和模型脱节
     *    （2026-10-08 就脱过一次：台子报 `c.on`、模型用 `span >=`，于是"走这一支的 N 张"
     *    数的是已经不存在的规则）。规则要挪，就挪这一行。 */
    val on: Boolean get() = span >= TONAL_SPAN_MIN
  }

  /**
   * 这张是不是"满幅、连续调、没有平涂大头"。**只读源图**，不碰参照、不碰 plate/dist，
   * 也不碰 [Scratch]（判据台的 `TonalGateKt` 要能单独调它）。亮度用 [labChannel] 现算 ——
   * 一张 384² 全框多算一次 Lab 是 1ms 量级，而这一支省下的色表与发档远不止这个数。
   *
   * ⚠ **2026-10-10：这里原来写的是"内容框比格还小就返回 null"，删掉了 —— 那是入场随边长漂的唯一来源。**
   *  量出来的账（`_harness/SizeKt.kt` + `_sizecurve.cjs`，真机语料 49 张唯一图 × 96/192/384/768）：
   *   · **跨度这个量本身不随边长漂**：修完之后 49 张在 192/384/768 三档全量得到，漂的只有 **1 张、
   *     漂 1 个点**（`com.huawei.appmarket` 35/34 —— 同一条资源两次抓取本来就差 3，那是分位尾部的噪声，
   *     不是分辨率）；四条候选量（格 p5/p95、格 p10/p90、众数阶数、众阶级跨度）反而**更漂**，
   *     所以"换一个量"这条路当场作废。
   *   · 而"192 档比 384 档少开火 6 张"里**每一张**都是被那句 null 拦掉的，它们的跨度是
   *     38 38 39 50 50 56 —— 全在闸上面 3~21 个点，**没有一张是因为分数不够**：
   *     抖音 / 拼多多 / songpal / azure / B站 / Xbox。
   *   · 所以把格子改成 `min(96, 框)` 之后：96 档与 384 档**一字不差**、192 档只差那 1 张尾部噪声，
   *     而**真机主档（384）进一个人都不多** —— 这一刀只买"同一张图在桌面两个位置走同一支"。
   *  为什么当年那句 null 站得住、现在站不住：它服务的是**纹理**（小框量不出相邻格差别），而纹理
   *  2026-10-08 就已经不是开火判据了（见 [Tonal.on]）。留着一个只为旧判据服务的前提，等于让旧判据
   *  隔着两天还在决定谁能入场。
   */
  internal fun tonalClass(pixels: IntArray, w: Int, h: Int, box: Rect): Tonal? {
    val bw = box.width()
    val bh = box.height()
    // 格子只服务纹理与主色这两条**报告用**的量，所以它按框缩；跨度走的是逐像素直方图，与格子无关。
    val n = minOf(TONAL_GRID, bw, bh)
    if (n < 2) return null
    val cL = IntArray(n * n); val cR = IntArray(n * n); val cG = IntArray(n * n)
    val cB = IntArray(n * n); val cN = IntArray(n * n)
    val hist = IntArray(101)
    var opq = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = (y - box.top) * n / bh
      for (x in box.left until box.right) {
        val i = row + x
        val pa = Color.alpha(pixels[i])
        if (pa < A_SOLID) continue
        opq++
        val l = labChannel(pixels[i], 0)
        hist[(l shr 6).coerceIn(0, 100)]++
        val k = cy * n + (x - box.left) * n / bw
        cL[k] += l
        cR[k] += (pixels[i] shr 16) and 0xff
        cG[k] += (pixels[i] shr 8) and 0xff
        cB[k] += pixels[i] and 0xff
        cN[k]++
      }
    }
    if (opq == 0) return null
    // 每格的平均亮度与平均色（平涂那一块的所有格会得到同一个色，这就是"主色"的算法）。
    // ⚠ 三通道**分开累加**：把打包好的 0xRRGGBB 直接相加再除格数，通道之间会进位，
    //   平涂区的两格会算出两个"不同的"颜色，主色那条判据就恒不成立。
    val cellL = IntArray(n * n) { -1 }
    val share = HashMap<Int, Int>()
    var cells = 0
    for (k in 0 until n * n) {
      if (cN[k] == 0) continue
      cells++
      cellL[k] = (cL[k] / cN[k]) shr 6
      val c = ((cR[k] / cN[k]) shl 16) or ((cG[k] / cN[k]) shl 8) or (cB[k] / cN[k])
      share.merge(c, 1, Int::plus)
    }
    var pairs = 0L
    var mid = 0L
    for (cy in 0 until n) for (cx in 0 until n) {
      val k = cy * n + cx
      val a = cellL[k]
      if (a < 0) continue
      for (j in intArrayOf(if (cx + 1 < n) k + 1 else -1, if (cy + 1 < n) k + n else -1)) {
        if (j < 0 || cellL[j] < 0) continue
        pairs++
        val d = maxOf(a - cellL[j], cellL[j] - a)
        if (d in TONAL_TEX_LO..TONAL_TEX_HI) mid++
      }
    }
    if (pairs == 0L) return null
    val lo = percentile(hist, opq, LUM_LOW_PCT) shl 6
    val hi = percentile(hist, opq, LUM_HIGH_PCT) shl 6
    return Tonal(
      (mid * 100 / pairs).toInt(),
      (share.values.maxOrNull() ?: 0) * 100 / cells,
      (hi - lo) shr 6, lo, hi
    )
  }

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

  /**
   * 一个像素属于哪个"部分"。[partMeans] 数的"几块"与 [regionGround] 认的"一块区域"
   * 必须查同一套词汇 —— 分块只能有一套。
   */
  private fun bucketOf(i: Int, s: Scratch): Int {
    val l = (s.labL[i].toInt() and 0xffff) shr 6
    val a = ((s.labA[i].toInt() shr 6) + 128).coerceIn(0, 255)
    val b = ((s.labB[i].toInt() shr 6) + 128).coerceIn(0, 255)
    return ((l / SEP_L) * 8 + (a / SEP_AB)) * 8 + (b / SEP_AB)
  }

  /** 一块色至少占工作集百分之几才算"图上的一个部分"；少于它的是一圈抗锯齿或一个噪点。 */
  private const val SEP_MIN_SHARE_PCT = 3

  /** [build] 里"同一种源色交付同一种浓度"那张表最多开多少条（见用处的地方）。
   *  ⚠ **它不是惰性的**：真机 61 张"会上屏"的图标里，王者荣耀 89951 / 国际版 72429 **都撞穿过这条线**
   *   （老 113 张的最大值只有 27986，所以 65536 当初被写成"最大值的 2.3 倍 = 碰不到"）。
   *   撞穿 = 整张退回"按格子给浓度"，同一块颜色被切成十几档灰。现在那两张改走 [tonalClass]
   *   那一支、不建这张表，所以暂时没人撞线 —— 别把"暂时没人撞"读成"这条线够高"。 */
  private const val CDEP_MAX = 65536

  /** 最多看几个部分（按面积取前几），再多就不是"相邻有没有差别"而是数颜色了。 */
  private const val SEP_MAX_PARTS = 6

  /** 两档之间至少差这么多浓度才算看得出是两个部分（255 的 ~12%）。
   *
   * ⚠ **这一条与 [LEVEL_FLOOR] 合起来先定死了"最多能分开几块"**，这是算术，不是取舍：
   *   档从 255 往下走，每步至少让 30，但不许低于 200 ——
   *   `255 → 225 → 200 → 200 → 200 → 200`（[SEP_MAX_PARTS]=6）。
   *   **三档以上就是必然并档，而且第 3 块起全钉在 200 上。**
   *   判据台探针（`_harness/PartAreaKt`，按精确源色分组，只数实心且出了墨的格子）量到：
   *   46 张可分块的图标里 **14 张（30%）面积最大的那块，比某一小块淡 ≥25**（探针的报门槛，
   *   与这里的 30 不是同一个数）——
   *   抖音 `最大块=200 占墨 54%`、哔哩哔哩 `208/34%`、闲鱼 `194/16%`。
   *   真机 census 那 32/190 张 `字均净<200`，**其中 30 张 `明暗=0`**：淡不是"轴选错了"，
   *   就是这里让出来的。所以别把这条当成"排序键选错"来修（按面积发顶档 = 让第 2 块去陪
   *   一块本来该柔的层），也别再抬地板 —— 两头都量死过，见 [LEVEL_FLOOR] 那一段。
   *
   * ⚠ **最后那条设想（"先把块数认对，再让块数决定档距"）也量死了，2026-09-29，别再开第三次。**
   *   主人点名的 yiban 把这条逼到了台前，三条独立证据都指着"没有便宜可捡"：
   *   · **代价没有出口。** 地板 200→128 时新掉进淡的 10 张**全是 5–6 块**（yiban 6、中国移动 6、
   *     Google 5、云闪付 6、京东 6、智慧树 6），而"档数变多"那 45 张里 34 张也是 ≥5 块 ——
   *     所以"只对多块的图放开地板"这道闸门免掉的代价是 **0/10**（`_harness/_gate_parts.cjs`）。
   *     ⚠ 读 `分档=` 那列注意它是 **档数/块数**，不是块数/档数：拿第一个数当块数，会得出
   *     "代价都落在 ≤4 块"的相反结论（我自己错过一次）。
   *   · **更根本的一条（当时说的）：那些档是编出来的。** yiban 那 6 块在**源色**上只铺了 47 个 ΔE
   *     （8.4 / 19.0 / 22.8 / 28.0 / 44.4 / 55.3 —— `_harness/PartsKt --merge`，按原生尺寸、
   *     与模型同一套 Lab 粗桶、同一道 ≥3% 面积门槛），而 6 档要 ≥25 的间隔得铺 125 以上。
   *     ⚠ **这条推论 2026-09-30 被图否掉了**：那 47 是"每块到底"的距离，发档要拉开的是"块与块"
   *     的距离，两者不是一回事；条件下沉上线后 yiban 拿到 `6/6`，图上是**熊的五官回来了**。
   *     留着的只有前半句 —— **"按块数开闸"不该开**（它同时放过云闪付与 songpal，见 [SEP_SINK_DEP]）。
   *     数与图见 [LEVEL_FLOOR] 末尾那两条。
   *   · **块数确实虚高，但虚高不在这一族。** 61 张 N≥5 的图里 **28 张按源色 ΔE<15 合并后 ≤3 块**
   *     （中国移动 6→3、Google 5→4、云闪付 6→5、京东 6→6、yiban 6→5）：认对块数救得了中国移动
   *     那一类，**救不了 yiban**。
   *   · 还有一条同族的写法也量掉了：档距不固定，改成 `min(30, 两块源色的 ΔE)`（源色本来就差不到 30
   *     就不许硬拉开）—— **155 张报告行逐字节全同**，在这条轴上它是个惰性问题，见 [regionGround]
   *     末尾那一条（它顺带证明：yiban 的档不是这一层压掉的，是 dep→浓度那条标定链）。
   *   一句话：这一族要的是"一维墨装不下的那一维"（见 [assignLevels] 顶上「墨是一维，颜色是二维」），
   *   不是更长的梯子。
   */
  /** 一块参照至少要留出多宽的浓度窗口，才**接得住手**。
   *
   * 低于它，交付出来的斜坡只剩几十级：dep 上一点点噪声就被放大成「要么实心、要么擦掉」，
   * 屏幕上就是**碎成几十块**而不是一个图形。真机 wisdomgame（灰发蓝眼、低饱和插画）H2 接手后
   * `零=53 实=99`（跨 46）、`cc=23`、框内 0.08 —— 主人一句「很奇怪」。
   * 量在 235 张（122 真机 + 113 老语料）上：**64 这一档只碰到 wisdomgame 两张**，其余一张不动。
   * ⚠ **只挂在 H2 上，H1 挂不得**：H1 那条路上有钉着的性质「整张纯色 → 交出空掩码」，而纯色图的
   *  窗口天生 0 宽 —— 一挂上去它就被判成"参照不可信"、退到空气参照、变成**整张涂满**（实测红的就是这条）。
   * ⚠ 它量的是**交付出来的动态范围**，不是「这块参照长什么样」—— 前五把尺子（占比/厚度/围合/
   *  环散/轮廓归一）都是后者，都量死过。所以这一条不是第六个阈值挑数，是换了一类证据。
   * 拒了要**自己报**：见 `拦=` 里的第 32 位。
   */
  private const val GROUND_MIN_SPAN = 64

  private const val SEP_MIN_GAP = 30

  /** [assignLevels] 往下发档的地板：低于这个浓度就不再让开（宁可并列）。
   *  200 ≈ 8 成墨 —— 过了这条线，"分得开"就变成他先报的那句「偏淡」了。
   *
   * ⚠ **这条地板只挡往下，不往上抬**（`minOf(mean[idx], …)` 里那块自己均值 90 的就停在 90）。
   *   看着像 bug，2026-09-28 当成 bug 试过：把 `coerceAtLeast(0)` 换成抬到地板，单变量 A/B
   *   113 张 —— **字均净跨过 200 的 0 行**（升 51 降 45，白忙），代价是三条性质当场红：
   *     三段按距离排开得   近=200 中=200（两档并成一块）
   *     最近那段是淡的     近=200（**该柔的那层被抬成实心**）
   *     层次搬出来了       淡 0.088→0.043（层次被抹平）
   *   外加「四块两两差 25」从最小间隔 20 掉到 1。所以这不是漏了半边，是**故意的**：
   *   那 32 张 `字均净<200` 的图，淡的是"源里本来就比底近"的那几块，把它们抬起来等于
   *   取消整套明度序 —— 那套设计存在的全部理由就是第三条性质。别再往这个方向试第二次。
   *
   * ⚠ **这根"细腻 ↔ 实心"的 dial 第一次被真正量化（2026-09-29，`_harness/floor_by_parts.patch`
   *   那版实验，未提交），量完的结论是**无差别地拨，不**：**
   *   地板随**部分数**下沉（硬底 128）→ yiban `2/6` 变 `6/6`、SelfTest 48/48 一条没破，
   *   但 113 张真图 `淡(<200) 25→35`、`字均净 升3降51`、`净顶≥50 的张数 52→48`，掉下去那 10 张每张
   *   赔 20~55 个浓度点（中国移动 228→174、Google 搜索框 227→177、云闪付 221→173、京东 203→196）。
   *   取"半档"（硬底 170）则是**白付**：淡只 +5，而 yiban 仍是 `2/6`（它要 5 个间隔，170 只给到 3 档）。
   *
   * ⚠ **但"这一族到此为止"那句当时说重了 —— 2026-09-30 有条件下沉已经上了屏**（见 [SEP_MIN_SRC_DE]
   *   与 [SEP_SINK_DEP]，[assignLevels] 里那段）：淡 25→29、档数 294→323、SelfTest 48/48、兼容性台不动。
   *   被否掉的从来不是"让地板沉"，是**"按块数开闸"** —— 块数一多就让，等于让排序键在没有信息的地方
   *   刻刻度。换成"源色差得开才让 + 已经实心的那块不许动"之后，同一张 yiban 的 `6/6` 在图上是
   *   **熊的五官回来了**（`_harness/q1_old` vs `q1_new`），而云闪付的黑字保住了（顶占比 7→61）。
   *   ⚠ 顺带纠正一条我自己拿来当证据的数：那句"yiban 六块源色只铺 47 个 ΔE，所以那 6 档是编的"，
   *   47 量的是**每块到自己那块底的距离**（`PartsKt --merge`，也就是 `QualKt` 表里那列 ΔE：8..55）；
   *   而发档要拉开的是**块与块之间**的距离 —— 两块可以都离底 30，却彼此差 40（易班的棕毛与红衣服就是这样）。
   *   拿前者去否后者是个**推论跳跃**，图已经把它否掉了。**"按块数开闸"照样不该开，但理由不是那 47。**
   */
  private const val LEVEL_FLOOR = 200

  /**
   * 相邻两块要**硬拉开一格**（[SEP_MIN_GAP]）所需要的**源色**代价 —— 不够这一对就不许拉开。
   *
   * 它管两件事，两件都是同一句话：**"图上有几个部分"只能由源色说，不能由排序键说。**
   *  · 撑得起：这一对之间才发一个 ≥30 的台阶，并且地板才准多让一格（见 [LEVEL_FLOOR_MIN]）；
   *  · 撑不起：两块**照自己的原样发**（只不许比上一块更浓）—— 原来这里对**每一对**都硬拉，
   *    于是"一根笔画上的一段连续渐变"被切成几段灰，见 [assignLevels] 里全球网测那一段。
   *
   * 为什么尺子必须是源色而不是 dep：中位 **dep 跨 119 是 ΔE 跨 53 的 2.2 倍**（`_harness/QualKt`，
   * 113 张真图标 @384）—— dep 有跨度不等于源图上有看得见的差别。azure 那张就是标本：
   * 五块墨 dep 只差 **4**（246..250）、源 ΔE 差 **31**，交付却拉开 **76**，那次序不是图给的。
   * 而"那些档是编出来的"正是上一版无差别下沉（`_harness/floor_by_parts.patch`，地板随**块数**下沉）
   * 被否掉的理由：块数一多就让，等于让排序键在没有信息的地方刻刻度（yiban 六块源色只铺 47 个 ΔE）。
   * 所以这一道门要的不是"几块"，是"相邻这两块本身差多少"。
   *
   * 40 这个数是**量出来的双峰**，不是拍的（`_harness/BandKt`，逐张复刻 `partMeans` 那套粗桶，
   * 按交付均值排序后数**相邻两桶**的源色差）：
   *  · **全球网测**那条弧：相邻差 15 / 20 / 21 / ~30 / 63，中位 **21** —— 它是**一根笔画上的一段
   *    连续渐变**，桶只是渐变路过的切片。15 那一版把它的台阶放出来了：`2/6(31,39,230,231,253,255)`
   *    变成 `4/6(27,34,135,165,225,253)`，**一根线沿线上出现四段灰**（主人点名的"线条整条消失/断开"）。
   *  · **易班**那只熊：相邻差 **54~82**，中位 73 —— 毛、嘴、衣服是**本来就分开的离散平色**，
   *    分档分的是真东西。
   *  · 全语料 350 对相邻桶：p10=7、**p50=20**、p90=114 —— 一半的对差不到 20，也就是**一半的"部分"
   *    其实是渐变的切片**。所以 15 太松，40 落在两个峰中间。
   * ⚠ 尺子必须是**源色**、且必须是**相邻两桶之间**的差：拿"每块到底的距离"当尺会得出相反的结论
   *   （yiban 那 47 个 ΔE 就是那种量法，见 [LEVEL_FLOOR] 末尾那条纠正）。
   */
  private const val SEP_MIN_SRC_DE = 40

  /** 源色撑得起时，地板最多沉到这儿。128 沿用上一版实验的那个数（再低就进"淡"那一档了）。 */
  private const val LEVEL_FLOOR_MIN = 128

  /**
   * dep 高过这个数的块**不许被发档拖下 [LEVEL_FLOOR]** —— "已经实心"的那几块不参与换层次。
   *
   * 224 = 满墨的 88%：dep 到这儿，模型自己已经认定这块离底足够远了，再拿它去给别块让位，
   * 换出来的"层次"是**从字形身上刮的**，不是源图里找回来的。云闪付与 songpal 的数见 [assignLevels]。
   */
  private const val SEP_SINK_DEP = 224


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
        val k = bucketOf(i, s)
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

  /**
   * **沿一根连通的细带传播归属** —— 修「某条线粗出一截 / 部分缺一块」。
   *
   * ## 病灶
   * 主人两次驳回之后指到形状上：一条**连续的描边**，沿路一段有一段没有，末端还碎成几块飘着。
   * 两张点名的图是同一个病：易班那圈气泡青绿描边（@384 量到 **521 格、平均宽 1.0px、圆度 148、
   * 断成 9 截**）、全球网测那条仪表弧（深色尾段贴着背景，整段被判成底）。
   * 根子在**颜色**：一条带沿长度方向渐变，渐变里"离底近"的那一段与底对得上，就被归进底。
   * 这一族以前量死过 —— 但那些量的是「**能不能从颜色上认出那是弧的延续**」（结论：颜色上它就是
   * 连成一片的，任何颜色规则都分不开）。这里换的是**拓扑**：这一整条是**同一个源色连通块**，
   * 大头已经成了墨，剩下那几截是同一条带 —— 位置与连通性说话，不再让颜色说话。
   *
   * ## 闸门（四个数全是从 `_harness/ShapeKt` 的表里读的，不是拍的）
   *  1. **够大**：≥ 内容框的 0.3%（语料上低于这个的连通块大半是 21 格的噪点，见那段注释）；
   *  2. **刻了一半**：已刻比例落在 15%~85% —— 全刻与全没刻都不关这一刀的事；
   *  3. **又细又长**：平均宽 `2A/p ≤ 8px` 且圆度 `p²/(4πA) ≥ 4`（一条 L×w 的带这个值 ≈ L/(πw)）；
   *  4. **断成 ≥2 截**：已刻的那部分自己不连通。**这一条是安全阀** —— 整条本来就全丢的，
   *     说明它从头到尾都被认成底，补它就是点亮一片底，那不是这一刀要救的对象。
   * 再加一道**两侧判据**（挑出"分界"、挡掉"盘沿/柔光"）：把带外的邻居按离底远近分两堆，
   *  要求两堆的中位 dep 差 ≥40。**语料 @384 上 50 块候选里 36 块是这种"分界"，只有 1 块是
   *  "两侧都近底"的那类**（浙江在线那块 3821 格、宽 11.8px 的盘边 —— 它被这道门单独挑出来挡住）。
   *
   * 补的值 = 这条带**已刻那部分的中位浓度**（不是 255，也不是全局某个档）：补进去的格要跟
   * 同一条带的其它部分一样，才叫"一条线"，不叫"多一块墨"。
   *
   * ⚠ **这一刀故意不救 globalspeed 那条仪表环**（主人 2026-09-30 点名的"右上角缺口"）：那条续弧
   *   宽 ~25px，被下面"平均宽 ≤ 8px"那道门挡在外面 —— 放宽它就是把盘沿整片点亮，代价早就量死。
   *   而它真正的死因不在这里：续弧 `#232F4E` ↔ 背景 `#191E2C` 只差 **ΔE 13.4**，这张图自己的
   *   `零 = 15`；更要命的是那几格**离自己的参照只差 3**（参照 L=22 / 它自己 L=19.8）——
   *   **色格子被续弧自己占满了，它成了自己的底**。那是 [propagateField] 那一层的病（H0 级），
   *   不是补带能碰的。记在 README「已知取舍」，别再从这道门里想办法。
   *
   * 跑在**发档之后**（4.6）—— 原来放在发档之前，结果补几格会改掉色桶均值、让 `assignLevels` 重排，
   * "接一条线"被放大成"改一整张的台阶"。挪过来之后两者解耦（代价：语料档数 317→316，噪声级）。
   */
  private fun propagateBands(out: IntArray, pixels: IntArray, w: Int, h: Int, box: Rect, s: Scratch): Triple<Int, Int, Int> {
    val total = w * h
    if (s.bandSeen.size < total) s.bandSeen = ByteArray(total) else s.bandSeen.fill(0)
    val q = s.queue
    val min = maxOf(64, box.width().toLong() * box.height() * BAND_MIN_SHARE_PERMILLE / 1000).toInt()
    var bands = 0; var filled = 0; var bandsSkipped = 0
    labelThickBlocks(out, w, h, box, s)
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i0 = row + x
        if (s.bandSeen[i0] != 0.toByte() || Color.alpha(pixels[i0]) < A_SOLID) continue
        val my = bucketOf(i0, s)
        var head = 0; var tail = 0
        q[tail++] = i0; s.bandSeen[i0] = 1.toByte()
        while (head < tail) {
          val c = q[head++]; val cx = c % w; val cy = c / w
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < box.left || ny < box.top || nx >= box.right || ny >= box.bottom) continue
            val j = ny * w + nx
            if (s.bandSeen[j] != 0.toByte() || Color.alpha(pixels[j]) < A_SOLID || bucketOf(j, s) != my) continue
            s.bandSeen[j] = 1.toByte(); q[tail++] = j
          }
        }
        if (tail < min) continue
        // **当前这条带单独标 2**（`bandSeen` 里 1 = 以前处理过的带）。⚠ 第一版只判"非 0"，
        // 于是数断截的那趟 BFS 会**漏进上一条带**里 —— 栈数组按本带长度开的，当场越界。
        for (k in 0 until tail) s.bandSeen[q[k]] = 2.toByte()
        val tone = bandTone(q, tail, w, h, out, pixels, s)
        for (k in 0 until tail) s.bandSeen[q[k]] = 1.toByte()
        if (tone <= 0) continue
        // 补：这条带里**比它自己的中位浓度淡**的格，一律抬到那个中位。
        // ⚠ 试过两个旋钮想"只补真没墨的那段"，**都不行**，记下来免得再试第三遍：
        //   · 只补 `a ≤ tone/4` → 接线打回 1/20 格（几乎不命中），硬边**一点没少**
        //     （易班"源图连续处却跳 ≥30"的像素对仍是 1%、p99 52）；
        //   · 整步挪到发档之后 → 接线保住（1/360），硬边**还在**（1% / p99 50）。
        //   原因是几何的：把一条发虚的渐变边印成一条实心线，**接好的部分与没接的部分之间必然有界**。
        //   所以这是「断线 ↔ 硬边」这对取舍本身，不是第三个常数能消掉的。
        var did = 0
        // 先把**要补的那几格**列出来（不是整条带）—— 防桥判据只能看它们：一条描边天生沿着好几块
        // 东西走，"整条带碰了两块粗块"是常态（第一版这么写，结果 5 张图 5 次全拒，接线等于没做）。
        // 只有"要新填的格子自己同时挨着两块粗块"才是新搭一座桥。
        val fill = IntArray(tail)
        for (k in 0 until tail) {
          val c = q[k]
          if ((out[c] ushr 24) >= tone) continue
          fill[did++] = c
        }
        // ⚠ 宁可留着断线，不搭新桥 —— 断线是"少画"，粘连是"画错"，后者更伤人。
        //   `_harness/BridgeKt`：`55a45b9` 上线后细桥净增 14 座（sgameGlobal 3→11、王者荣耀 0→5），
        //   正好落在补带开火最猛的那两张上。
        if (did == 0) continue
        if (cellsBridgeBlocks(fill, did, w, h, s)) { bandsSkipped++; continue }
        for (k in 0 until did) {
          val c = fill[k]
          out[c] = (tone shl 24) or 0x00FFFFFF
          s.bandSeen[c] = 3.toByte()          // 3 = 这一格是**这一刀补上去的**，体检时要分得出来
        }
        if (did > 0) { bands++; filled += did }
      }
    }
    return Triple(bands, filled, bandsSkipped)
  }

  /**
   * 给"粗块"编号写进 [Scratch.bridgeLab]：出墨 ≥128，且**左右上下各 3 格都还是墨**
   * （= 这一处至少 6px 粗，不是抗锯齿的绒毛或一条细线）。同一块给同一个号。
   * 只做一次，全框 O(n)；不另开距离场数组（那个十字判据本身就够用，省 295 KB/线程）。
   */
  private fun labelThickBlocks(out: IntArray, w: Int, h: Int, box: Rect, s: Scratch) {
    val total = w * h
    if (s.bridgeLab.size < total) s.bridgeLab = ShortArray(total) else s.bridgeLab.fill(0)
    val q = s.queue
    fun thickAt(i: Int): Boolean {
      if ((out[i] ushr 24) < 128) return false
      val x = i % w; val y = i / w
      for (d in 1..3) {
        if (x - d < 0 || x + d >= w || y - d < 0 || y + d >= h) return false
        if ((out[i - d] ushr 24) < 128 || (out[i + d] ushr 24) < 128 ||
          (out[i - d * w] ushr 24) < 128 || (out[i + d * w] ushr 24) < 128
        ) return false
      }
      return true
    }
    var lab: Short = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i0 = row + x
        if (s.bridgeLab[i0] != 0.toShort() || !thickAt(i0)) continue
        lab++
        if (lab == Short.MAX_VALUE) return
        var head = 0; var tail = 0
        q[tail++] = i0; s.bridgeLab[i0] = lab
        while (head < tail) {
          val c = q[head++]; val cx = c % w; val cy = c / w
          for (oy in -1..1) for (ox in -1..1) {
            if (ox == 0 && oy == 0) continue
            val nx = cx + ox; val ny = cy + oy
            if (nx < box.left || ny < box.top || nx >= box.right || ny >= box.bottom) continue
            val j = ny * w + nx
            if (s.bridgeLab[j] == 0.toShort() && thickAt(j)) { s.bridgeLab[j] = lab; q[tail++] = j }
          }
        }
      }
    }
  }

  /**
   * 这些**要新填的格子**是不是同时挨着两块不同的粗块？是 → 填它们等于搭一座桥（粘连），别填。
   *
   * ⚠ 判据必须只看"要填的格"，不能看"整条带"：一条描边本来就沿着好几块东西走，
   * 第一版按整条带判，5 张图 5 次全拒、接线等于没做（细桥倒是从 146 掉回 130 了 —— 那是把
   * 孩子和水一起倒掉）。
   */
  private fun cellsBridgeBlocks(fill: IntArray, n: Int, w: Int, h: Int, s: Scratch): Boolean {
    var a = 0; var b = 0                                     // 最多记两个号，见到第三个就够判了
    val lab = s.bridgeLab
    for (k in 0 until n) {
      val c = fill[k]; val cx = c % w; val cy = c / w
      for (oy in -1..1) for (ox in -1..1) {
        if (ox == 0 && oy == 0) continue
        val nx = cx + ox; val ny = cy + oy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        val v = lab[ny * w + nx].toInt()
        if (v == 0 || v == a || v == b) continue
        if (a == 0) a = v else if (b == 0) b = v else return true
      }
    }
    return a != 0 && b != 0
  }

  /**
   * 这一条带该不该补、补成多浓。返回浓度，或 **-1 = 不归这一刀**。
   * 四道门与两侧判据的出处见 [propagateBands]；`s.bandSeen[...] == 2` 的那一圈就是这条带自己。
   */
  private fun bandTone(
    q: IntArray, tail: Int, w: Int, h: Int, out: IntArray, pixels: IntArray, s: Scratch,
  ): Int {
    var inked = 0; var perim = 0
    val vals = ArrayList<Int>(tail)
    for (k in 0 until tail) {
      val c = q[k]; val cx = c % w; val cy = c / w
      if ((out[c] ushr 24) >= 128) { inked++; vals += out[c] ushr 24 }
      for (d in 0 until 4) {
        val nx = cx + intArrayOf(1, -1, 0, 0)[d]; val ny = cy + intArrayOf(0, 0, 1, -1)[d]
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) { perim++; continue }
        if (s.bandSeen[ny * w + nx] != 2.toByte()) perim++
      }
    }
    if (perim == 0) return -1
    val cov = inked * 100 / tail
    if (cov < BAND_LO_PCT || cov > BAND_HI_PCT) return -1
    if (2L * tail * 10 / perim > BAND_MAX_WIDTH * 10) return -1                 // 平均宽 > 8px：不是"一条线"
    if (perim.toLong() * perim < BAND_MIN_CIRC4 * tail.toLong()) return -1       // 圆度 <4：不是"一条"
    // 已刻的部分断成几截（<2 = 整条本来全丢或全留，不归这一刀）
    val seen = BooleanArray(w * h)
    val st = IntArray(tail)
    var pieces = 0
    for (k in 0 until tail) {
      val c = q[k]
      if (seen[c] || (out[c] ushr 24) < 128) continue
      pieces++
      var a = 0; var b = 0
      st[b++] = c; seen[c] = true
      while (a < b) {
        val u = st[a++]; val ux = u % w; val uy = u / w
        for (oy in -1..1) for (ox in -1..1) {
          if (ox == 0 && oy == 0) continue
          val nx = ux + ox; val ny = uy + oy
          if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
          val j = ny * w + nx
          if (!seen[j] && s.bandSeen[j] == 2.toByte() && (out[j] ushr 24) >= 128 && b < tail) {
            seen[j] = true; st[b++] = j
          }
        }
      }
    }
    if (pieces < 2) return -1
    // 两侧：带外的邻居按离底远近分两堆，两堆中位差够大才叫"图形与底的分界"
    val near = ArrayList<Int>(); val far = ArrayList<Int>()
    for (k in 0 until tail) {
      val c = q[k]; val cx = c % w; val cy = c / w
      for (d in 0 until 4) {
        val nx = cx + intArrayOf(1, -1, 0, 0)[d]; val ny = cy + intArrayOf(0, 0, 1, -1)[d]
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        val j = ny * w + nx
        if (s.bandSeen[j] == 2.toByte() || Color.alpha(pixels[j]) < A_SOLID) continue
        (if ((s.dist[j].toInt() and 0xffff) <= 60) near else far) += (s.dist[j].toInt() and 0xffff)
      }
    }
    if (near.isEmpty() || far.isEmpty()) return -1
    near.sort(); far.sort()
    if (far[far.size / 2] - near[near.size / 2] < BAND_MIN_SIDE_GAP) return -1
    vals.sort()
    return vals[vals.size / 2]
  }

  /** [propagateBands] 的闸门，四个数都来自 `_harness/ShapeKt` 的语料表（@384，50 块候选）。 */
  /** 一条带至少占内容框这么多（千分之 3 = 语料上 21 格那类噪点全挡在外面）。 */
  private const val BAND_MIN_SHARE_PERMILLE = 3
  private const val BAND_LO_PCT = 15
  private const val BAND_HI_PCT = 85
  private const val BAND_MAX_WIDTH = 8
  /** 圆度 `p²/(4πA) ≥ 4` 的整数写法：`p² ≥ 50·A`。 */
  private const val BAND_MIN_CIRC4 = 50L
  private const val BAND_MIN_SIDE_GAP = 40

  /**
   * **按部分分配档位** —— "不够细腻"那一类的修法（三次试错之后剩下的那一条）。
   *
   * ## 病灶与为什么单标量必输
   * 判据台 `_harness/NuanceKt` 量到：37 对"两块都上了墨、源色 ΔE≥15"的部分里 **17 对（46%）
   * 交付后被并掉**，最狠的一对 ΔE=153 只差 4 个单位。试过并否掉的两条都是"给像素找一个标量"：
   *  · 只按彩度 → 30 对，**空转**（整个 logo 通常是一个连通主体，豁免主体等于豁免全部）；
   *  · 亮度×2 + 彩度 → 11 对，但顶占比中位 50→22，且「两块底上的白块都还在」当场 FAIL
   *    （白剪影被"比底亮"那一维排进淡档 = 上一轮刚修掉的「偏淡」又回来了）。
   * 原因是结构性的：**墨是一维，颜色是二维**。按任何标量排都只能保住一半的差别 ——
   * 按"比底暗"排，白与浅灰并列；按"比底亮多少的绝对值"排，白与黑并列。
   *
   * ## 这一条怎么绕开它
   * 不再给像素找标量，而是**给部分发档位**：先按源色认出图上那几块（与 [separation] 同一套桶、
   * 同一个面积门槛，所以"几块"在这里和日志里是同一件事），最浓的那块抬到满墨，其余每块
   * 至少让开 [SEP_MIN_GAP]。并列是被"分配"掉的，不是被排序挤掉的 —— 白对黑、白对黄都能各得其所。
   * 每块挪的是**整块的偏移量**（`a + 目标 − 本块均值`），块内部的渐变与抗锯齿原样保留，
   * 不会画成硬边色块；本来就差得开（≥ 目标间隔）的一块不动。
   *
   * ⚠ 已知的代价：让开的那几块会**变淡** —— 那是"分得开"的价格，最多让 [SEP_MAX_PARTS] 块。
   *   接在后面的 [stretchMark] 会把单色字形重新抬实，两层是配套的，不是互相打脸。
   */
  private fun assignLevels(out: IntArray, pixels: IntArray, w: Int, box: Rect, s: Scratch) {
    val cnt = IntArray(SEP_BUCKETS)
    val sum = LongArray(SEP_BUCKETS)
    // 每块的**源色**均值（×64 的 Lab）。发档往下让一格的理由只能是"这两块在源图上真的差得开"，
    // 而"差得开"必须由源色自己说 —— 不能由 dep 说（见 [SEP_MIN_SRC_DE]）。
    val sumL = LongArray(SEP_BUCKETS)
    val sumA = LongArray(SEP_BUCKETS)
    val sumB = LongArray(SEP_BUCKETS)
    val sumDep = LongArray(SEP_BUCKETS)
    var ink = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) < A_SOLID) continue
        val a = out[i] ushr 24
        if (a == 0) continue
        val k = bucketOf(i, s)
        cnt[k]++; sum[k] += a; ink++
        sumL[k] += s.labL[i].toInt(); sumA[k] += s.labA[i].toInt(); sumB[k] += s.labB[i].toInt()
        sumDep[k] += s.dist[i].toInt() and 0xffff
      }
    }
    if (ink < 256) return
    val min = maxOf(64, ink * SEP_MIN_SHARE_PCT / 100)
    val left = cnt.copyOf()
    val pick = ArrayList<Int>(SEP_MAX_PARTS)
    repeat(SEP_MAX_PARTS) {
      var best = -1
      for (k in 0 until SEP_BUCKETS) if (left[k] >= min && (best < 0 || left[k] > left[best])) best = k
      if (best < 0) return@repeat
      left[best] = 0; pick.add(best)
    }
    if (pick.size < 2) return
    val mean = IntArray(pick.size) { (sum[pick[it]] / cnt[pick[it]]).toInt() }
    // 每块的源色均值（×64 单位，与 `deltaE` 同一口径）。
    val colL = IntArray(pick.size) { (sumL[pick[it]] / cnt[pick[it]]).toInt() }
    val colA = IntArray(pick.size) { (sumA[pick[it]] / cnt[pick[it]]).toInt() }
    val colB = IntArray(pick.size) { (sumB[pick[it]] / cnt[pick[it]]).toInt() }
    val depMean = IntArray(pick.size) { (sumDep[pick[it]] / cnt[pick[it]]).toInt() }
    // 从最浓的那块往下发档：第一块满墨，其余"不超过上一块 − 间隔"，也不高于自己原来的位置。
    val order = pick.indices.sortedByDescending { mean[it] }
    val target = IntArray(pick.size) { mean[it] }
    var prev = 255
    // **地板按"源色撑不撑得起这一格"逐级下沉**（原来是恒定的 [LEVEL_FLOOR]，所以 255→225→200
    // 之后必然并档 —— 语料平均 4.43 块只发得出 2.60 档，这就是"没细节"的全部机制）。
    // 往下让一格的条件：相邻这两块在**源色**上至少差 [SEP_MIN_SRC_DE]。够，就多给 30 的额度；
    // 不够，就照旧并档。恒底 [LEVEL_FLOOR_MIN] 兜住"再淡也不能淡过这条"。
    var floor = LEVEL_FLOOR
    // **地板只沉"本来就不实心"的那几块。** 多让一格是拿"这一块的浓度"付的，所以付款的必须是
    // "源色真的差得开、而模型自己也没把它当实心墨"的那块 —— 否则就是拿字形换层次。
    // 云闪付 @384 是标本：dep **254**、占墨 **36%** 的那块（"云闪付"三个字）被拖到 195，
    // 而 dep 只有 223/231 的两块小斜纹坐在它上面；顶占比 61→7，黑字变灰字。
    // Sony songpal 是另一头：dep 只跨 **40**、交付却跨 **253**（6.3 倍），五块里四块 dep≥248 ——
    // 那一档"层次"整个是现编的。加了 [SEP_SINK_DEP] 之后两张的实心块都不许动，
    // 而易班熊那几块（dep 162/183/197/222）远在 224 之下，五官该分还分。
    for ((j, idx) in order.withIndex()) {
      var apart = false                                   // 这一对相邻块，源色撑不撑得起一个可见台阶
      if (j > 0) {
        val p = order[j - 1]
        val de = deltaE(
          colL[p], colA[p], colB[p], colL[idx], colA[idx], colB[idx]
        ) shr 6
        apart = de >= SEP_MIN_SRC_DE
        if (apart) floor = maxOf(LEVEL_FLOOR_MIN, floor - SEP_MIN_GAP)
      }
      // 地板只沉"本来就不实心"的那几块。（云闪付 / songpal 的数见上面 [SEP_SINK_DEP] 那段。）
      val fl = if (depMean[idx] >= SEP_SINK_DEP) LEVEL_FLOOR else floor
      // ⚠ **只有源色撑得开的那一对才硬拉开 ≥30；撑不开的那一对照自己的原样发**（只不许比上一块更浓）。
      //   原来这条对**每一对**相邻块都硬拉，那正是"一根笔画被切成几段灰"的来路：全球网测那条弧是
      //   一段**连续渐变**，它的桶只是渐变路过的切片（相邻源色差 15/20/21/~30/63，中位 21），
      //   硬拉的结果就是沿着一根线出现四段台阶（`2/6(31,39,230,231,253,255)` → `4/6(27,34,135,165,225,253)`，
      //   主人点名的"线条整条消失/断开"）。而易班熊的毛/嘴/衣是**离散的平色**（相邻差 54~82），
      //   该拉的照拉 —— 它的 `6/6` 与五官一条都不动。
      target[idx] = if (j == 0) 255
      else if (apart) minOf(mean[idx], maxOf(prev - SEP_MIN_GAP, fl)).coerceAtLeast(0)
      else minOf(mean[idx], prev)
      prev = target[idx]
    }
    val shift = HashMap<Int, Int>(pick.size)
    for (idx in pick.indices) {
      val d = target[idx] - mean[idx]
      if (d in -7..7) continue                                // 本来就分得开：一个像素都不动
      shift[pick[idx]] = d
    }
    if (shift.isEmpty()) return
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val a = out[i] ushr 24
        if (a == 0) continue
        val d = shift[bucketOf(i, s)] ?: continue
        val v = (a + d).coerceIn(0, 255)
        out[i] = if (v == 0) 0 else (v shl 24) or 0x00FFFFFF
      }
    }
  }

  /** 全局抬顶的分位（[stretchGlobal]）：取分位不取最值，一圈抗锯齿的极值不该决定整张的色阶。 */
  private const val STRETCH_HI_PCT = 99

  /**
   * 第一层抬顶：**全体**交付墨的 p99 → 255，"只往上、锚在 0"的一条直线。
   *
   * 只抬、不压、不重排，锚在 0 上 —— 两条踩过的坑写在这里以免再被"优化"掉：
   *  · 分段折线按块重排：把"0 → 第一个锚点"整段抬起来，真图上多出一块灰方框（已撤，见 `分档` 那节）；
   *  · 连低端一起拉（`[p1..p99] → [22..255]`）：判据台四条同时红 —— 一条 2px 细线本来就占不到
   *    墨量的 1%，按 p1 归一等于把它当成噪点擦掉。**"最浅的一档"不能靠压别的档来给。**
   *
   * 它的**盲区**是它自己那句 `色阶=已满`：p99 是尾部统计，一张图只要有 1% 的墨到顶就不动了，
   * 剩下 87% 停在 140 的整块字形它管不着。那一半由 [stretchMark] 接手 —— 两层是**叠加**的：
   * 曾把这条换成按字形统计的那一条，结果 84 张里 11 张反而掉回没抬的水位（`分档` 逐张对出来的）。
   */
  private fun stretchGlobal(out: IntArray, total: Int): Pair<IntArray, String>? {
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

  /** 认作"一种颜色画出来的字形"的比例地板，见 [stretchMark]。
   *  85 = 留余量给字形自己的抗锯齿与两色拼的字标；真机语料上该抬的那几张都在 95 以上。 */
  private const val STRETCH_FLAT_SHARE = 85

  /** 单色字形抬到满阶的锚：字形那一堆墨的**中位数**。不能取 p85/p99 —— 要修的正是
   *  "13% 到顶、87% 停在 140"那种分布，高分位恰好被那 13% 撑满（前一版就栽在这上面）。 */
  private const val STRETCH_MARK_PCT = 50

  /** 多色（渐变填的）字形的锚与上限：锚取 p70（主体那一堆），倍率**封顶 ×1.35**。
   *  ⚠ 这条上限**今天被证明基本不咬人**：整个去掉（135→255）在真机群体 64 行上只挪出
   *  `字均净` 净 +9（升 2 行、降 0 行、最大 +6），判据台 46 条全过 —— 也就是说"插画偏淡"
   *  不在这一层（我先猜的是它，错了）。留着是因为它不要钱，但**别再把它当淡的旋钮**。 */
  private const val STRETCH_RAMP_PCT = 70
  private const val STRETCH_RAMP_CAP = 135

  /**
   * 第二层抬顶：**一种颜色画出来的字形，必须实心。**（第一层在 [stretchGlobal]，两层是叠加的。）
   *
   * ## 病灶在哪
   * `_harness/DensKt` 在 87 张真机像素上量的：把交出去的墨按"源像素本来实不实心"劈成两堆 ——
   * 「那圈抗锯齿比字形还实」只有 **1 张**（我先前的那个猜想是错的，已推翻），而
   * **36 张字形平均 <200、59 张实心占比 <50%**：华为市场 140、王者荣耀 154、小步点 161。
   *
   * ## 为什么第一层抬不到它
   * [stretchGlobal] 取的是**全体墨的 p99**。一张图只要有 1% 的墨到顶，它就报 `色阶=已满` 收工 ——
   * 剩下 87% 停在 140 的那一整块字形根本不在它的口径里。日志里那句"已满"当时被当成好消息。
   *
   * ## 为什么这一层不再管"并档"
   * 前一版在这里加过一道否决：抬升会把 `分档` 里两个部分一起贴上顶、最小间隔 7→0，那撞上
   * 判据台「相邻部分必须有差别」。可那道否决把**要修的图也一起挡掉了** —— 华为市场那张源色是
   * 一整块同色（[modalShare] 95% 以上），它内部那两档浓度全是模型自己算出来的，不是设计者的
   * 明暗；保护一个造出来的层次，就等于保住那份淡。所以门槛换成**源色单一不单一**：
   *  · 单色字形 → 抬到中位数贴顶（内部那几档本就是我们的误差）；
   *  · 多色字形 → 一个像素都不动（`色阶=…+多色`），那部分差别是设计者的，由 [separation] 管。
   * 抗锯齿与柔光（源 α<255）始终不在统计里、也不在抬升里 —— 判据台钉着「那一圈原样传下去」。
   *
   * @return 进日志的那一句（`色阶=` 的第二段），抬升已就地写进 [out]。
   */
  private fun stretchMark(
    out: IntArray, pixels: IntArray, w: Int, h: Int, box: Rect, s: Scratch,
  ): String {
    val total = out.size
    val hist = IntArray(256)
    var n = 0
    for (i in 0 until total) {
      if (Color.alpha(pixels[i]) < 255) continue          // 边与柔光：不在统计里，也不在抬升里
      val a = out[i] ushr 24
      if (a > 0) { hist[a]++; n++ }
    }
    if (n < 64) return "已满"
    // 单色字形（源色 ≥[STRETCH_FLAT_SHARE]）：内部那几档浓度全是模型造的，抬到中位数贴顶。
    // 多色字形（渐变填的袋子、有明暗的字标）：**倍率封顶**地抬 —— 屏幕上"淡"是主体那一堆给的，
    // 而它自己的明暗是设计者画的，抬过这个倍率就把层次抹成一块实色（他报过两次）。
    // 看图定的这个数：华为应用市场 140 的渐变袋子，×1.35 抬到 189 就已经不再是"灰蒙蒙"，
    // 再狠（单色那条的 ×1.82）袋子的高光与暗部并成一片。
    val flat = modalShare(pixels, out, total, s) >= STRETCH_FLAT_SHARE
    val hi = percentile(hist, n, if (flat) STRETCH_MARK_PCT else STRETCH_RAMP_PCT)
    if (hi >= 250) return "已满"
    var mul = 25500 / hi                                     // 抬到贴顶需要的倍率（×100）
    if (!flat) mul = minOf(mul, STRETCH_RAMP_CAP)
    // 渐变那一支多一道否决：抬之前/之后各量一次"部分之间的最小间隔"。
    // 单色那一支不设这道 —— 它内部那几档是模型造的，保护它就是保住那份淡（上面说过）。
    // 第一版没分开这两件事，全局加了否决，结果要修的图全被挡掉；反过来不加，判据台
    // 「相邻部分必须有差别」的最小间隔从 7 被压到 0（渐变袋子的三段并成一档）。
    val baseGap = if (flat) -1 else minGap(separation(out, w, h, box, s).third)
    // 回滚用的那一份借 [Scratch.queue]（这一步之后 [measure] 会自己重置它，不新分配 590KB）。
    val buf = if (flat) null else s.queue.also { System.arraycopy(out, 0, it, 0, total) }
    for (i in 0 until total) {
      if (Color.alpha(pixels[i]) < 255) continue
      val a = out[i] ushr 24
      if (a > 0) out[i] = ((a * mul / 100).coerceIn(a, 255) shl 24) or 0x00FFFFFF
    }
    if (buf != null && minGap(separation(out, w, h, box, s).third) < baseGap) {
      System.arraycopy(buf, 0, out, 0, total)
      return "渐变并档不抬"
    }
    return (if (flat) "单色" else "渐变") + "p" + (if (flat) STRETCH_MARK_PCT else STRETCH_RAMP_PCT) +
      "=$hi×" + mul / 100
  }

  /** [separation] 那几块平均浓度（升序）之间最小的那道差。少于一块没有"相邻"可言 → 不设限。
   *  钉的是判据台「相邻部分必须有差别」那条，**间隔**才是它的尺 —— 数"几档"会把 255/255
   *  这种并档算成还在，第一版就是这么漏的。 */
  private fun minGap(means: IntArray): Int {
    if (means.size < 2) return 255
    var g = 255
    for (i in 1 until means.size) g = minOf(g, means[i] - means[i - 1])
    return g
  }

  /** 字形那一堆墨（源 α=255 且交出去非零）里，**同一个源色**占多少百分比。
   *  接近 100 = 整块字形是一种颜色画出来的，那它交付浓度里的分档全是模型造的。
   *  桶与 [separation] 同一套（[bucketOf]）—— "一种颜色"在这里和 `分档` 里必须是同一件事。 */
  private fun modalShare(pixels: IntArray, out: IntArray, total: Int, s: Scratch): Int {
    val cnt = IntArray(SEP_BUCKETS)
    var n = 0; var top = 0
    for (i in 0 until total) {
      if (Color.alpha(pixels[i]) < 255 || (out[i] ushr 24) == 0) continue
      val c = ++cnt[bucketOf(i, s)]
      if (c > top) top = c
      n++
    }
    return if (n == 0) 0 else top * 100 / n
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
    val s = checkNotNull(pool.get()) { "withInitial 建的 ThreadLocal 不会给回 null" }
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
    // **满幅图（内容框里几乎全是实心）没有"退路"**：那种图上"哪一块是底"本来就该由局部参照说了算，
    // 没有一块背景可供"退出"。见 [cellFar] 的护栏：把"离家族远的格子"判成墨会把整张插画反过来 ——
    // 真机实测王者荣耀国际版（`com.levelinfinite.sgameGlobal`，`盒布=100`）`H 3→1`、
    // `框内 0.51→0.06`，而 H3 正是这一类**该走**的那一支。判据与 [NO_AIR_OPAQUE_PCT] 同一条
    // （"框内 ≥95% 是不透明的"），只是这里问的是"这一张有没有空气可退"。
    var opaque = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) if (Color.alpha(pixels[row + x]) >= A_SOLID) opaque++
    }
    val fullBleed = opaque.toLong() * 100 >= boxArea * NO_AIR_OPAQUE_PCT

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
    // 到最近空气的切比雪夫距离：两趟扫描（正向看左上四邻、反向看右下四邻），O(框)。
    // 空气=0，框外也算 0（画布的边就是"什么都没有"的边界）。见 [PLATE_EDGE_R]。
    if (s.airDist.size < total) s.airDist = ShortArray(total)
    val ad = s.airDist
    val INF = (box.width() + box.height()).toShort()
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        ad[i] = if (Color.alpha(pixels[i]) < A_AIR) 0 else INF
        if (x > box.left && (ad[i - 1].toInt() + 1) < ad[i].toInt()) ad[i] = (ad[i - 1] + 1).toShort()
        if (y > box.top) {
          for (dx in -1..1) {
            val xx = x + dx
            if (xx < box.left || xx >= box.right) continue
            val v = (ad[i - w + dx].toInt() + 1).toShort()
            if (v < ad[i]) ad[i] = v
          }
        }
      }
    }
    for (y in box.bottom - 1 downTo box.top) {
      val row = y * w
      for (x in box.right - 1 downTo box.left) {
        val i = row + x
        if (x < box.right - 1 && (ad[i + 1].toInt() + 1) < ad[i].toInt()) ad[i] = (ad[i + 1] + 1).toShort()
        if (y < box.bottom - 1) {
          for (dx in -1..1) {
            val xx = x + dx
            if (xx < box.left || xx >= box.right) continue
            val v = (ad[i + w + dx].toInt() + 1).toShort()
            if (v < ad[i]) ad[i] = v
          }
        }
      }
    }
    passFromFamilies(pixels, w, box, s, TOL_COARSE shl 6, fams)
    // 第一轮之前先洪泛一次：色场是从「与边界连通的底」上拟合的，没有这一步就没有种子像素。
    flood(s, w, box, seed)
    var nGround = 0
    var nInk = 0
    var covSum = 0L
    for (iter in 0 until FIELD_ITERS) {
      if (iter > 0) {
        // 「离这块实的底有多远」的查表：每格找最近的一格全覆盖（β≥250）。一格都没有
        // （H3「参照=空气」那种）就是 -1，外沿那条判断整条不参与 —— 白色剪影不许自吸。
        val cells = g * g
        val nFull = IntArray(cells) { c ->
          val cx = c % g
          val cy = c / g
          var best = -1
          var bd = Int.MAX_VALUE
          for (j in 0 until cells) {
            if (s.fCov[j] < 250) continue
            val d2 = (j % g - cx) * (j % g - cx) + (j / g - cy) * (j / g - cy)
            if (d2 < bd) { bd = d2; best = j }
          }
          best
        }
        // ── 盘沿的拓扑 ──────────────────────────────────────────────────────────────
        // 为什么非得是拓扑：盘自己的外沿与"被误吸进底的图形"（抖音音符的柄）在**颜色与 β 上
        // 同构** —— 都是"不透明、与参照同色、背后那格半空"。三条按颜色/β 区分的交付侧修法都
        // 因此连带砍掉真图形（见下面 `透传` 那段与 `PLATE_EDGE_R` 那段）。唯一把它们分开的是
        // 位置：**盘沿能只踩着同色的实心格走到盘与空气的边界；被围进去的图形不能** ——
        // 它四周是盘，不是空气，或者中间隔着半透明的柔光（洪泛过不去）。
        // 三步：① plate-like = 实心且颜色与最近一块实的底对得上；② 种子 = plate-like 且四邻
        // 里有不实心的一方（那就是盘的边界）；③ 只沿 plate-like 格洪泛。
        // ⚠ 参照本来就是空气的图（H3、白色剪影）一格全覆盖的都没有 → nFull 全 -1 → 第一步就
        //   全 false，这条整条不参与 —— 上面"白色剪影不许自吸"那条性质因此不受影响。
        if (s.rimBand.size < total) s.rimBand = ByteArray(total)
        val rb = s.rimBand
        rb.fill(0, box.top * w + box.left, ((box.bottom - 1) * w + box.right).coerceAtMost(total))
        val jnd2 = GROUND_INK_JND.toLong() * GROUND_INK_JND
        for (y in box.top until box.bottom) {
          val row = y * w
          for (x in box.left until box.right) {
            val i = row + x
            if (Color.alpha(pixels[i]) != 255) continue
            val c0 = if (s.ref[i] >= 0) s.ref[i] else 0
            // ⚠ 只问**自己那一格是虚的**像素。不加这一条，`nFull` 会指回自己那一格 ——
            //   实心格里的像素当然与自己的格同色，于是"plate-like"退化成"什么都是"，
            //   洪泛从画布边一路淹穿整张图（第一次跑就是这样：`四块四档` 两块被擦成 0、
            //   `多色插画` 框内=0.00、`三段距离` 最远那段没了）。
            //   实心格里的像素本来就由正常的 dep 进底，不需要这条救它；这条只管
            //   "盘的外沿那几格被空气稀释了参照"的那一种。
            if (c0 >= cells || s.fCov[c0] >= 128) continue
            val nc = nFull[c0]
            // 整张没有一格是全覆盖的（H3「参照=空气」那一类）→ 没有"实的底"可借，这条不参与。
            if (nc < 0) continue
            val dl = (s.labL[i].toInt() and 0xffff) - s.fL[nc]
            val da = (s.labA[i].toInt() and 0xffff) - s.fA[nc]
            val db = (s.labB[i].toInt() and 0xffff) - s.fB[nc]
            if (dl * dl + da * da + db * db < jnd2) rb[i] = 1
          }
        }
        run {
          var head = 0
          var tail = 0
          val q = s.queue
          for (y in box.top until box.bottom) {
            val row = y * w
            for (x in box.left until box.right) {
              val i = row + x
              if (rb[i] != 1.toByte()) continue
              // 盘的边界：四邻里有一个不是完全不透明的（含框外 —— 框外就是"什么都没有"）
              val edge = (x == box.left || y == box.top || x == box.right - 1 || y == box.bottom - 1 ||
                Color.alpha(pixels[i - 1]) < 255 || Color.alpha(pixels[i + 1]) < 255 ||
                Color.alpha(pixels[i - w]) < 255 || Color.alpha(pixels[i + w]) < 255)
              if (edge) { rb[i] = 2; q[tail++] = i }
            }
          }
          while (head < tail) {
            val i = q[head++]
            val y = i / w
            val x = i - y * w
            for (d in 0 until 4) {
              val j = i + when (d) { 0 -> -1; 1 -> 1; 2 -> -w; else -> w }
              val jj = when (d) {
                0 -> x > box.left; 1 -> x < box.right - 1; 2 -> y > box.top; else -> y < box.bottom - 1
              }
              if (!jj) continue
              if (rb[j] == 1.toByte()) { rb[j] = 2; q[tail++] = j }
            }
          }
        }
        for (y in box.top until box.bottom) {
          val row = y * w
          for (x in box.left until box.right) {
            val i = row + x
            // 「没有颜色」永远算参照的一部分 —— 参照本来就是"透明"时它当然在参照里。
            val a = Color.alpha(pixels[row + x])
            val d = s.dist[i].toInt() and 0xffff
            // ⚠ 判连通用的 `d` **含** β 那一项，而容差 `tol` 是按**不含** β 的量出来的
            //   （`withCoverage = false`，理由见上面"零点只量参照自己颜色的散度"那段）。
            //   两把尺不一样长，代价是**盘自己的边永远进不了底**：那一圈背后的色场格子跨在
            //   "盘↔空气"之间，β 只有 178 而不是 255，于是 `d ≈ a×(255−β)/255 ≈ 77`，
            //   而它的颜色与参照其实差 0 —— 小步点盘外那一圈看得见的灰环就是这么来的
            //   （`_harness/RimDbg --res` 的反事实：换成纯色那把尺，dep 掉到 7，容差 15）。
            //   这里把"那一格自己的空气"减回去（恒等式，不用重开一次 ΔE 开方）：
            //       纯色距离 = d − a×(255−β)/255
            //   **只在 β≥128 时这么问**：参照本来就是空气的图（H3、β≈0）不许靠这条把自己
            //   圈成底 —— 那正是当初把连通改成带 β 的那把尺要挡的事（`透明底白剪影 底占=1.00`）。
            //   `s.beta` 读的是上一轮拟合的 β，这里要的就是"这一格刚才有多实"。
            //
            // ⚠ **已知没修掉的缺陷，2026-09-28 用户点名（run.xbud / 中国移动「外圈还是有瑕疵」）。**
            //   盘的轮廓外有 8 个暗色小楔子，位置是 cell 1/6/8/15/48/55/57/62 —— 圆盘与方形
            //   画布四角相接的那几格。`_harness/RimDbg --rim` 量到：run.xbud 449 格、中国移动 463 格，
            //   每一格**背后那一格 β 只有 27~29**（一整格九成三是空气）、dep≈227、出墨 255。
            //   它们只有几百格，所以 `边上=`、`残盘=` 这类按面积数的指标全看不见，但它们落在
            //   盘的轮廓**外面**，屏幕上极扎眼。
            //   三条已经试过并排掉的解释，别再重复：① 把这道门换成"这张图里有没有一块实的底"
            //   （有就拿最近的全覆盖格当尺）—— 楔子没掉，反而红 4 条，其中「白底柔光+实心核」
            //   掉到 `框内=0.0`（核与底同色，它唯一的存在依据就是 `(1−β)` 那一项）；
            //   ② 加"四邻贴不贴空气"这个拓扑条件 —— SelfTest 44 条全过、动了 25 行的字均，
            //   **但对这两张完全惰性，449 格一格没掉**；③ 决策时改读当前的 `fCov` 而不是上一轮
            //   存的 `s.beta`（怀疑时序）—— 同样惰性。所以病不在这道门上。
            //   ④ 把"贴不贴空气"换成"**贴不贴底**"（四邻有一个是底就按纯色那把尺问）——
            //     **楔子真的清到 0 格**（两张都是），说明方向对；但红 3 条：
            //     「抗锯齿那一圈原样传下去」淡掉到 1.2e-4（那一圈被吸进底）、
            //     「两半都留着」红=135.3（**与盘同色又挨着盘的图形**被一起吸掉）、
            //     「实心格平均上成实心墨」229.8 < 240。松在"贴盘的图形"也满足这个条件，
            //     而那恰好是最常见的一种图形。
            //   ④ 与 ② 合起来给出还没有的那一个条件：楔子的几何是**内侧是底、外侧几步内
            //     就是空气**（`--rim`：449 格里 441 格四邻最小 α≥200，可 6 步内一定摸得到空气）；
            //     贴着盘的图形外侧是底、不是空气。下一次从这条差异找，别再调 128。
            val b = s.beta[i].toInt() and 0xffff
            val gnd = (x > box.left && s.plate[i - 1] != 0.toByte()) ||
              (x < box.right - 1 && s.plate[i + 1] != 0.toByte()) ||
              (y > box.top && s.plate[i - w] != 0.toByte()) ||
              (y < box.bottom - 1 && s.plate[i + w] != 0.toByte())
            val rim = gnd && ad[i].toInt() <= PLATE_EDGE_R
            // 外沿那一圈**不能**用"减掉那一格的空气"来问：`b=28` 时 `d − a(255−b)/255 ≤ tol`
            // 化简成 `d ≤ 242`，那一格里几乎任何像素都过 —— 贴着盘的两色 logo 因此被吃掉一半
            // （红 225→135）。要问就得**拿一块实的底当尺**：离它最近的那格全覆盖色。
            val byRim = rim && run {
              val c0 = if (s.ref[i] >= 0) s.ref[i] else 0
              val nc = if (c0 < g * g) nFull[c0] else -1
              nc >= 0 && a > 0 && run {
                val lim = tol.toLong() * 100L * 64L / a
                val dl = (s.labL[i].toInt() and 0xffff) - s.fL[nc]
                val da = (s.labA[i].toInt() and 0xffff) - s.fA[nc]
                val db = (s.labB[i].toInt() and 0xffff) - s.fB[nc]
                dl * dl + da * da + db * db <= lim * lim
              }
            }
            val solid = if (b >= 128) d - a * (255 - b) / 255 else d
            s.pass[i] = if (a < A_SOLID) 1
            else if (!fullBleed && cellFar(s, if (s.ref[i] >= 0) s.ref[i] else 0, fams, g * g)) 0
            else if (d <= tol || solid <= tol || byRim || rb[i] == 2.toByte()) 1 else 0
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
    //
    // ⚠ **这一条在盘沿上会漏出用户报的"绕盘一圈、贴着白盘外沿的痕迹"**（2026-09-28，
    //   `com.greenpoint.android.mc10086.activity` 真机带回那张，`Rimwhere` 量的）：
    //   半径>0.72 处有 **700 个 α=255、颜色就是白的盘格出墨 254**，其中 **304 个已经在底里**，
    //   是被这一行按 `α×(255−β)/255` 画回去的 —— 盘外沿那几格跨在"盘↔空气"之间，
    //   **β 只有 27~29**，于是"完全不透明的一格盘"被算成"九成像空气"。
    //   两条已试过并排掉的修法，别再走：
    //   ① 把这一行加个 `a < 255` 的门（不透明格不透传）：304 格清零、SelfTest 44 条全过、
    //     连钉了一天的「白字上成实心墨」都从 `块数=9` 推到 6 —— **但抖音的音符被劈成两半**
    //     （`最大块 1.00→0.59`），翻译多出一块占 15% 的碎片。这一条在抖音上是**承重**的：
    //     柄与球体之间就靠这几个透传格连着。
    //   ② 用"离空气多远"当判据（[PLATE_EDGE_R]）：剩下那 396 格离最近空气 **8~15 像素**，
    //     而这条带的宽度跟着位图尺寸缩放（同一张图 288 内蕴→432 bounds），所以任何固定半径
    //     都会在某个尺寸上失效。半径不是这里的尺。
    //   还没分清的：半径>0.72 也包含 logo 自己外伸的笔画，这 396 格里多少是"盘沿"、多少是
    //   "笔画之间的白缝"，量之前不能说它们都是 bug。
    //   （2026-09-28 量完了：**396/396 离盘心那团真墨 ≥64 像素**，全是盘沿，没有白缝。）
    //
    // ⚠ 第三条交付侧的修法也试过、也排掉了（同一张图，`Rimwhere` 复量）：把透传限制成
    //   "颜色与**自己那一格**的参照对不上"才给（`colourOnly = dep − α(255−β)/255 > tol`，
    //   尺度无关，不需要半径）。它把中国移动那 304 格清零、SelfTest 44 条全过、
    //   「白字上成实心墨」还推到 `块数=6` —— 但语料上 **抖音的音符 top 1.00→0.58 被劈成三块**、
    //   cc 变坏 9 张。三条一起指向同一件事：**这条透传是"被误吸进底的图形"唯一的活路**，
    //   而"被误吸进去的图形"看起来恰好就是"颜色与自己的参照对得上"的格子 —— 从交付这一侧
    //   按颜色区分，永远会连带砍掉真图形。往上走一格才是出路：**让跨在"盘↔空气"之间的那一格
    //   别把自己的 β 算成空气的占比**（见 [fitField]）。β 对了，dep 就对了，
    //   归属与透传两侧都不用再 argue。
    s.hist2.fill(0)
    nInk = 0
    covSum = 0L
    // 底与墨各自的平均色（只数本来就实心的格子：抗锯齿边在两半里都有，算进去就是拿一条边
    // 去比一条边，量出来的 ΔE 与"这一刀切没切开东西"无关）。
    var gl = 0L; var ga = 0L; var gb = 0L; var gn = 0
    var il = 0L
    var inn = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (Color.alpha(pixels[i]) == 255) {
          if (s.plate[i] != 0.toByte()) {
            gl += s.labL[i].toInt() and 0xffff; ga += s.labA[i].toInt() and 0xffff
            gb += s.labB[i].toInt() and 0xffff; gn++
          } else { inn++; il += s.labL[i].toInt() and 0xffff }
        }
        if (s.plate[i] != 0.toByte()) {
          // 底格子这一条用 [fTop] 问"这一格有没有实参照"，不是"平均有多实"：跨在
          // "盘↔空气"（中国移动盘沿）与跨在"实心↔渐变淡影"（翻译那条斜痕）上的格子，
          // β 都会被淡的那一半拖低，于是"完全不透明的一格盘"被算成"四分之三是空气"、
          // 画回去一整条 `α×(1−β)` 的斜带。
          val cell = if (s.ref[i] >= 0) s.ref[i] else 0
          val t = s.fTop[cell]
          val d = if (touchesInk(s.plate, pixels, w, box, x, y))
            Color.alpha(pixels[i]) * (255 - t) / 255 else 0
          s.dist[i] = d.toShort()
          s.distT[i] = d.toShort()
          continue
        }
        val d = s.dist[i].toInt() and 0xffff
        // 出图那一份换尺（见 [distT]）：`dist` 本身照旧，标定与第二遍读到的还是老口径。
        s.distT[i] = departure(pixels[i], i, s, byTop = true).toShort()
        // **交付侧收掉"盘自己那圈边"**（见 [thinInk]）：一个非底的像素，3×3 里至少 5 个是底。
        // ⚠ 只改**写出去的那一个数**，`hist2` / `covSum` / `nInk` 照旧吃原来的 `d` ——
        //   这一条绝不能顺手把标定的输入也换掉：判据台上 `icon554_…photos` 就是因为
        //   `nInk` 被抽空（那张图的墨**全是**这种细结构）而 `p.solid` 退成 `p.zero`、
        //   整张掩码归零（`框内 0.53→0.00`、`字均净 242→-1`）。标定要的是"这张图本来有多浓"，
        //   与"最后写不写出去"是两件事。
        if (thinInk(s.plate, w, box, x, y)) { s.dist[i] = 0; s.distT[i] = 0 }
        s.hist2[d.coerceAtMost(DBUCKETS - 1)]++
        covSum += s.beta[i].toInt() and 0xffff
        nInk++
      }
    }
    val p = Pass()
    p.share = nGround.toFloat() / boxArea
    p.families = fams
    p.gL = if (gn > 0) (gl / gn).toInt() else 0
    p.gA = if (gn > 0) (ga / gn).toInt() else 0
    p.gB = if (gn > 0) (gb / gn).toInt() else 0
    p.gN = gn.toInt()
    p.iL = if (inn > 0) (il / inn).toInt() else 0
    p.iN = inn.toInt()
    // 两半的平均色之差。写成"交叉相乘再除"是为了不出现两次除法带来的漂移；Lab 是 ×64 的，
    // 所以最后除 64 回到 ΔE 单位，再 ×10 存成整数（日志里读 0..255 的人不需要那半位）。
    p.inkSame = if (gn == 0 || inn == 0) -1 else {
      // 底的本色（×64 的 Lab 均值），然后**逐格**问墨那一侧的每一格离它多近 —— 不问两半的均值
      // 差多少，那条会被"跨在底两侧的墨"抵消，见 [GROUND_INK_SAME_PCT] 那段。
      val gl0 = gl / gn; val ga0 = ga / gn; val gb0 = gb / gn
      val lim = GROUND_INK_JND.toLong() * GROUND_INK_JND
      var close = 0
      for (y in box.top until box.bottom) {
        val row = y * w
        for (x in box.left until box.right) {
          val i = row + x
          if (Color.alpha(pixels[i]) != 255 || s.plate[i] != 0.toByte()) continue
          val dl = (s.labL[i].toInt() and 0xffff) - gl0
          val da = (s.labA[i].toInt() and 0xffff) - ga0
          val db = (s.labB[i].toInt() and 0xffff) - gb0
          if (dl * dl + da * da + db * db < lim) close++
        }
      }
      close * 100 / inn
    }
    // ⚠ **这个零点是承重的，别想着"按格放开它"。** 它是整块底散度的 p97，而底越大越花这一个数
    //   越高：点名的十二张里 智慧树 115（顶到 `DEP_TOL_MAX`）、驾考 91、易班 81、京东 63，
    //   其余都在 15 的下限上 —— 而 `零` 是从**每个像素**头上扣的（`td = CURVE[(dep-零)*255/跨度]`），
    //   所以"比底自己的噪声还淡的图形"整个没了。主人报的那五条「线条不全 / 中断 / 底部线条不对」
    //   正好落在高 `零` 那几张上。这条路 2026-10-01 **实现过三个版本，全部撤**：
    //     ① 只取"底在这一格里 ≥3/4"的那些格子的散度当零点（全局 115 → 该格 20）：
    //        `字均净` 净 +100（14 升 / 8 降）、`框内` +0.09、`空洞` -0.01 —— **同时**
    //        `残盘` 从 0 涨到 **10422**（15 张涨、**0 张跌**，最狠智慧树 0→3181、平均出 129 墨）。
    //     ② 按格取，但只对"离每一个底家族都 ≥ `SEAM_DIVERGE`"的像素取：`残盘` 9456（还是 15 涨
    //        0 跌），而 `字均净` 的净收益从 +100 掉到 **0**（11 升 11 降）—— 闸门把好处拦光了
    //        没把坏处拦住。原因：漏判的盘边是**渐变的尾巴**，它离角上那几个家族本来就远。
    //     ③ 中间还有一版把围住者那一刀叠在上面，见 `regionGround` 里 2026-10-01 那条。
    //   **所以缺的不是"哪一格该用多大的零点"，是"这一格的中位色到底是底还是画"** ——
    //   半底半墨的格子里，底与画都离那个混合的中位色一样远，`零` 抬高压根是在替这次混淆买单。
    //   要动这件事，得先有一个不描述"离参照多远"、而描述"这一格是谁"的证据；
    //   **别再从"把零点调低/调细"这一侧进。**（`_harness/_zjcmp.cjs` 是那三版的对照台。）
    // ⚠ **第五版也量死了，而且它把这一族指到别的层去了。** 上面那句"分开它们的是形状"是
    //   推断，就去验了推断：零点按格取，但**只给"不是一条贴在盘上的细边"的像素** —— 闸门
    //   直接**复用** [thinInk]（3×3 里底邻 ≥5，`SelfTest` 第 9 条两头钉着），不新造数。
    //   作用面确实窄了：12 张点名图里 **8 张逐字节不动**，智慧树 `字均净 191→211`、`分档 4→5`
    //   （橙条上那行 "reenity" **在图上真的回来了**）。可 `残盘` 照涨：智慧树 0→3181、驾考 0→3364。
    //   **看图才知道那 3181 格是什么** —— 不是盘边细缝，是**整条橙带本身**被涂上一层淡琥珀色。
    //   也就是说它们从来不是"被漏判的边"，是**一块从来没被登记成底的底**：
    //   智慧树的橙带不在 `plate` 里（`底占=0.80` 收的是白气泡那一圈），可它是那行字的背景。
    //   → **这一族的根不在标定层，在归属层**：橙带成了"非底但 dep 很低"的两不像，
    //   `零` 抬高压根是在替它遮丑。真要修，修的是**"一块图上第二大的平色区域，只要它上面坐着
    //   高对比的小图形，它就该当底"**（主人报的五条「线条不全」里，智慧树、饿了么、驾考是同一个形状）。
    //   ⚠ 但那条判据天然的反例是**"彩色圆徽 + 白色 logo"**：徽是墨、字是洞，判据会把徽降成底，
    //   屏幕上只剩一个字的轮廓。**分不开它们的是"这块区域挨不挨画布边"** —— 没做过，别当现成的。
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
   * 四个假设按顺序问，取**第一个留下真图形**的 —— figure/ground 本来就不是适定的，任何做法
   * 都得验一次结论，区别只在于验的是数字还是手感：
   *  · **H1 内容框四角上的颜色** —— 老式满幅图、以及底板画到边的自适应图标；
   *  · **H2 内容自己轮廓上的颜色** —— 圆盘 / 圆角方**整块画在前景层里**的那种：空气在内容框
   *    之外，角上必然量不到东西，于是 H1 直接判"没有这块底"、参照退成空气，而那块浅色圆盘
   *    才是这张图真正的底。真机点名那一批（"浅盘 + 深字"的 azure / 翻译 / 驾考宝典 / 华为市场
   *    …）全栽在这条上：判据比的是"图形 vs 空气"，底根本不在比较里，于是七张全判成不翻。
   *  · **H3 参照 = 没有东西**（透明）—— 图形浮在空气上时的正确答案。H2 在这种图上会失败：
   *    薄字形的轮廓就是字形自己，洪泛吃掉整框，验收（[PLATE_ALL_SHARE]）把它挡回这里。
   *  · **H0 区域关系**（[regionGround]）—— H1、H2 都说不出"哪块是底"时，最后问一次"有没有一块
   *    大区域把别的画围在里面、并且铺满整幅画布"，再退到 H3。它问的不是某个位置的颜色，
   *    这才是"底"这个词的本义；排在 H2 之后是有原因的，见那里的真值表与 [regionGround]。
   *
   * 洪泛**穿过所有底家族的并集**，不是冠军那一个：底常常是几块拼的（高德四块、云闪付红白两段、
   * 饿了么橙蓝两段），只选一个家族，剩下那块底整块变成墨 = 「还剩一块大色块」。
   */
  private fun groundPass(
    pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, g: Int, s: Scratch,
    plateIsLayer: Boolean = false,
  ): Pass {
    val seed = s.mark      // 洪泛的种子掩码（measure 之前没人用这块）
    s.h0why = 0            // 这一张走到 H0 没有、被哪道拦下，逐张归零（见 [Scratch.h0why]）
    s.veto = 0; s.vetoSame = -1  // 同上：被"底墨同色"退掉过哪几支、被退那支自己的数（见 [Output.veto]）
    // H1 被否时**它自己的数**也要能进日志（与 H2 那条 `H2被否=` 同一做法）。
    // 判据台 2026-09-29 撞出来的缺口：`拦=16` 只说"被整框那道退了"，不说它吞到多少、
    // 剩下几格当墨 —— 于是"这块底该不该开豁免"只能靠猜（当年 `同色=接手/被退` 一加出来，
    // 65 与 54 之间那个空档才看得见，是同一件事）。
    var h1share = -1f
    var h1thick = -1
    var h1fams = -1
    var h1ink = -1
    val fams = cornerFamilies(pixels, w, box, band, s)
    // ⚠ **这一张的盘在另一层的时候，实心区里不许有底**（H1 角上那一色 / H2 轮廓那一色 都不许）。
    // 依据不是统计量，是**输入是怎么来的**：`MonochromeSynth` 只有在"自适应图标 + 背景层是
    // `ColorDrawable`"时才把**前景层单独**交进来（桌面自己会补那张盘）。也就是说这一张里
    // 每一块实心都是设计师画在前景层里的东西，**唯一的承载面是空气** —— 那正是 H3。
    // 为什么今天才立这一条：它唯一真正改到的那一张是 `com.tencent.hunyuan.app.chat`（混元）——
    // H2 把太极**自己的白色那一瓣**当成了底，屏幕上就是主人那句「我这只有半个太极」。
    // 干净对照（`cand/MonochromeMask.noSolid2.kt` + `NoSolidKt.kt`，16 张甲类 dump）：
    // **12 张逐位相同**（含 azure / 拼多多 / 贴吧 / xbox —— 它们走的是满幅那一支，
    // 那一条路的浓度不看 `plate`，所以换了底也换不出画面），只有混元补全（`框内 0.27→0.53`）
    // 与 kazumi 挪了 0.02。
    // ⚠ 第一版量出来的是"这条规则会把 azure 等涂成实心方块"，**那是错的**：那份候选里还留着
    //   另一刀（`GHOST_FLOOR=128`）的地板，方块是地板画的。规则本身无罪，被叠在身上的那一刀冤枉了。
    if (fams > 0 && !plateIsLayer) {
      bandSeed(s, w, box, band, seed)
      val p = fitGround(pixels, w, h, box, g, s, fams, seed)
      p.ring = fams
      p.hyp = 1
      p.thick = plateThickness(w, box, s)
      // 角上确实有一块色，**并且它和剩下的东西不是一个颜色** —— 后半句才是这一支的验收。
      // ⚠ 实验（wisdomgame 2026-09-28）：还该有前半句的另一半 —— **它没吃掉整框**。
      //   [PLATE_ALL_SHARE] 那道一直只挂在 H2 上，H1 从来没有：智慧游戏那张 H1 交出的
      //   `底占=0.99 盘实=36591/墨实=273` —— 99.3% 的实心格都被说成底，剩下 273 格当墨，
      //   于是 `明暗=76`（底占太高→参照等于没有→整个交给亮度轴）画成一片 1..254 的灰雾，
      //   `字均=122 顶占比=1 淡=0.71`。同色验收救不了它：墨侧只有 273 格，`inkSame=0` 当然通过。
      // ⚠ 但**只在"角上不止一种颜色"的时候**才拿整框去否它：钉着的性质「整张纯色 → 交出一张
      //   空掩码」走的正是"底占=1.00 且族=1"这条路 —— 整张一个颜色时"什么都是底"是**对的**，
      //   否掉它反而退成 H3、把整幅涂满（第一版就红在这条上，框内=1.0）。
      // ⚠ **可这条豁免真机上漏了一整族图标**（2026-09-30 从设备日志抓到）：长按快捷方式那些
      //   **单色线条矢量图形 + 全透明背景** —— YouTube 订阅 / 设置电池 / 相机视频，
      //   全是 `底占=1.00 族=1 盘实=全部/0 框内=0.00`，**屏幕上整个看不见**。
      //   它们和「整张纯色」的区别不在颜色数（都是一种），在**这座"底"占画布多少**：
      //   快捷方式 `盒布=3%~10%`，而「整张纯色」`盒布=100%`。一座只占画布几个百分点的小岛，
      //   四周全是空气 —— "什么都是底"不可能是对的，**岛本身就是图形**。所以豁免加一条前提。
      val island = box.width().toLong() * box.height() * 100L <=
        w.toLong() * h * GROUND_ISLAND_PCT.toLong()
      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p
      if (p.share >= PLATE_ALL_SHARE && p.families > 1) s.veto = s.veto or 16
      if (vacuous(s, p, box)) s.veto = s.veto or 1
      h1share = p.share; h1thick = p.thick; h1fams = p.families; h1ink = p.iN
    }
    // 只被「底比墨小」挡住的那个 H2 候选，留到**最后**再决定要不要用（见下面 air 之前那一段）。
    var h2ThinOnly: Pass? = null
    val ring = outlineFamilies(pixels, w, box, s, seed)
    // H2 被否决时它的数也要能进日志 —— 用局部变量带，不碰对象上的字段（这是多线程的钩子路径）。
    var h2share = -1f
    var h2thick = -1
    var h2spread = -1
    var h2enc = -1
    if (ring > 0 && !plateIsLayer) {
      val alt = fitGround(pixels, w, h, box, g, s, ring, seed)
      alt.ring = ring
      alt.thick = plateThickness(w, box, s)
      alt.enclosed = enclosedShare(pixels, w, box, s)
      alt.hyp = 2
      alt.spread = s.ringSpread
      // ⚠ **只看占比，是量出来的结论，不是没来得及改。** 另外三条（厚度 / 围合 / 环散）都接进
      //  日志了，也真拿去当过验收：放开门槛 + 三条一起用，84 张里 6 张明确变好
      //  （taptap、钉钉、微信、WPS、小步点、拼多多）、2 张明确变坏（steamlink、哔哩哔哩）；
      //  再给"空主张"开豁免（占比=0 时不吃厚度 —— 拿"太薄"去否一个没主张的人，等于把参照
      //  从轮廓色悄悄换成空气），哔哩哔哩回来了，但联系人 / Song Search 被推进 H2-空，
      //  框内 0.72→0.90，整块实心。
      //  真值表（该收 / 该拒，四列依次是 占比 / 厚度 / 围合 / 环散）：
      //    收 小步点 .81 39 64 1      收 steamlink .39 17 0 0      收 哔哩哔哩 0 0 0 59
      //    拒 taptap  .75  9 0 0      拒 联系人     0  0 0 24      拒 SongSearch 0 0 0 7
      //  哔哩哔哩和联系人在前三列**逐位相同**、第四列方向相反 —— 这四个量里没有能分开两组的信息，
      //  别再试第五个阈值。要修这一类，得换一个不是"描述这块参照长什么样"的证据。
      // ⚠ **"换一个证据"这条 2026-10-01 真找过一个，预演过，不够好，没写进模型**：
      //    底 = **所有**挨着内容框边的颜色区域（不再只挑一族），被围住的才算墨 ——
      //    这是连通性证据，不是颜色证据。预演台 `_harness/TwoPlateKt.kt`（区域生长 + 三联图）：
      //      · **赢**：Google 翻译（两块盘都算底，G 与"文"都成墨，右半边不再被画满）、
      //        淘宝闪购/饿了么（橙盘 + 蓝盘都算底，"e·饿了么"那行字回来了，蓝条不再是一坨）。
      //      · **输**：浙江在线（`cn.com.zjol`）—— 那条**被围住的浅蓝波浪**按这条规则也算墨，
      //        于是和"潮"字糊成一整块，比现在差。
      //      · 其余**中性**：真机 61 张里"模型现在画的墨落在这条规则说是底的格上"> 20% 的
      //        只有这 4 张（不含走亮度那一支的），其余逐格一致 —— 它不是一个大改动，是一个窄改动。
      //    想过一条补救："围住的区域要离**每一块**底都够远才算墨"。**它当场把翻译弄死**：
      //    那只 G 与白盘本来就是同一个颜色（#F5F5F5 vs #EEEEEE，ΔE 2.5），"离每块底都远"不成立。
      //    → 归属要**同时**看连通性与颜色，而这两条在这批图上互相矛盾。这就是那堵墙本身，
      //    不是还差一个常数。**别在这道门里再挑数**，也别拿"翻译变好了"当收它的理由（同一天
      //    它就弄坏浙江在线）。
      // ⚠ **但"别从这条路进"这句上面那一段说重了，2026-10-01 主人点名 12 张之后要改口**：
      //    把那条规则改成**按嵌套深度的奇偶**定墨（从所有挨边的区域 + 空气做 BFS，奇数层=墨、
      //    偶数层=空），**上一版那两个输掉的现场都翻回来了** —— 浙江在线的波浪掉到偶数层不再
      //    糊住"潮"字；azure 的"盾里的人"也回到偶数层，盾成墨、人被镂空（现在这张是盾整个没了）。
      //    它同时天然处理"字形里的洞"：洞是偶数层 = 空 ✓。
      //    ⚠ **但这个预演不能当验收**：`TwoPlateKt` 的区域生长是我自己写的（跑动均值 + 一个
      //    ΔE 容差），它把驾考宝典的车身与底盘**并成一块**（两个蓝相差不到 18）、把易班的书法字
      //    切碎成"只剩轮廓"—— 也就是说这张台子上**分割质量主导了结论**，奇偶那一版到底值不值，
      //    要用**模型自己的色场/容差**再判一次才算。别拿这份预演的图去立项，也别拿它去否。
      // ⚠ **第三条比前两条都对，但它需要一次真正的实现**（同一晚，主人又点名 12 张之后）：
      //    判据不是"离所有的底远"、也不是"奇数层"，而是 **「离围住它的那一块远才算墨」** ——
      //    递推：一个区域成了墨，它围住的下一层就拿**它**当参照。
      //      · 浙江在线：波浪是"浅一点的蓝"、离围住它的蓝底近 → 空；"潮"是白、离蓝底远 → 墨。
      //        **前两条规则都做不到** —— 波浪与字是**兄弟**，连通性把它们一起收，奇偶靠 BFS 顺序碰运气。
      //      · azure：人被盾围住、浅蓝离盾近 → 空（那本来就该是镂空），而**盾自己回到墨里**
      //        （这张现在的毛病正是盾整个没了）。
      //      · 智慧树 / 翻译 / 淘宝闪购：底带与第二块盘掉回"底"，字留成墨。
      //    12 张点名图里这条**赢 4 张**，而且赢的全是**减法**（只擦画错的墨、不新增墨）——
      //    这就是它能安全落地的理由。
      //    ⚠ **唯一的拦路石是分割质量，不是规则**：驾考宝典的车身与底盘是相差不到 18 的两个蓝，
      //    粗容差把它们并成一块（车身就没了），细容差又把一条笔画切成十几块。京东同理。
      //    → 区域必须用**模型自己的色场**长（8×8 格 + 每格中位色 + `TOL_MIN..TOL_MAX` 那套自适应
      //    容差），不能另起一套粗洪泛。**这就是这次实现真正的工作量，也是它还没做的原因。**
      //    ⚠ 这句现在是**量出来的**，不是猜的（`_harness/EncloseKt.kt` 扫了四档量化：
      //    色桶 L 每 8/16/24、a/b 每 32/64/85，配合并容差 0/12/20）：
      //      · **azure 稳**：盾 → 白圆 ΔE=**74**、盾里的人 → 盾 ΔE=**37~41**，四档下数值不动
      //        → 一条 T≈50 的线就能"盾留墨、人退回镂空"。**嵌套镂空那一类现在就能做。**
      //      · **浙江在线不稳**：字与底之间的"围住者"永远报成占框 0.0% 的碎片，ΔE 在
      //        3 / 19 / 23 / 35 之间随量化档跳 —— 它的底是**渐变蓝**，色桶把底切成几十块，
      //        字共边最多的邻居只是其中一块碎片；**贪心并块在渐变上链不通，救不了**。
      //    ⚠ **2026-10-01：这一刀写出来跑过了，逐张看过图，然后撤掉。别照着上面那句
      //      "现在就能做"再走一遍 —— 它做出来了，而且是错的。** 实现 = 在 [bucketOf] 那套词上
      //      长连通块（底整块算 0 号）→ 每块取"共边最多的够大的邻居"当围住者 → 围住者必须是另一块墨、
      //      至少大四倍、两块**整体均色** ΔE ≤ 50 → 整块擦成 0。只减不加，另存一张表、不碰 `plate`。
      //      · **它擦对了浙江在线**：那条波浪 4135 格没了，"潮"字留着 —— 正是主人报的「背景奇怪线条」。
      //      · **但它在它唯一的设计案例上擦错了对象**：azure 擦掉的是**盾自己那条渐变带**，
      //        浅蓝的"人"一格没动（`_harness/bench/az_sheet/` 第四屏）。原因就在上面那条
      //        "azure 稳"里 —— 它比的是**盾这个整体**与**人**的均色，而真跑起来盾被色桶切成
      //        上/中/下三条渐变带，"中带的围住者是上带、ΔE 很小"先成立，刀就落在盾身上。
      //        **ΔE 到围住者这一条分不出"第二层"和"主体自己的明暗"。**
      //      · 代价（真机 61 张，`--min=1`）：**11 张被擦**，其中看得见的错有 5 张 ——
      //        百度贴吧的气泡边、驾考宝典的车身、sangfor 的斜条、Steamlink 手柄、坚果云橡子的轮廓线。
      //        第一版更糟（18 张，哔哩哔哩 18781 格墨擦了 11845、支付宝"支"缺一笔），
      //        是靠"围住者必须是另一块墨（不许是底）+ 比整体均色而不是比贴边一圈的均色"这两条
      //        才退到 11 张的 —— **那两条都是把刀收窄，没有一条能把对的那一半留下来。**
      //      · 所以这条路第三次停在同一处：连通性（`41164cf`）→ 嵌套奇偶（`bc5f893`）→
      //        围住者（这一条）。**停的地方不是规则，是"一块平色区域里哪几条缝算边界"这件事**，
      //        而模型现在没有任何一种分法能把"主体的渐变带"和"主体上的第二层"分开。
      //    ⚠ 混元（`com.tencent.hunyuan.app.chat`）的"只有半个太极"**不归这条管**：那一瓣白与
      //    圆外背景同色**且真的连通**（不是从缝里漏过去的），而它的 fg 层是 `ColorDrawable`、
      //    画身在 **bg 那个 Vector 层**里 —— "有层的用层"在这里也拿不到主体轮廓。
      //    那一类要的是**轮廓闭合**，是另一件东西（34/122 张的 fg 都是 Color，所以"用层"这条
      //    路对三分之一的应用根本没有层可用，别再把它当现成的下一步）。
      // ⚠ **2026-10-01 又量了一次：模型现在给的两种"分块"都不能当这张区域图的底。**
      //   想问的是"这块平色区域挨不挨画布边、它围住了几个高对比小块"（第二块盘那条问法的
      //   第一次数）。两台都跑了：
      //     · **按 [bucketOf] 的 4 连通块** —— 一条渐变带被切成几千片，"挨框"恒 0（没有一片
      //       能一路走到框），"围住的小高对比块"数出来 551 / 1185 个（那是抗锯齿碎屑，不是字）。
      //     · **按精确源色** —— 带子本来是"一条橙"，但它带渐变，于是 `ff7503 / ff5301 / …`
      //       各占 5%，**没有任何一种颜色拿得下整条带**；"贴在这一色上的格"恒 0.0%，
      //       因为四邻要求**逐字节同色**。
      //   还有一条独立的坏消息：**"挨不挨画布边"这个分隔量在圆角/圆形图标上天生不成立** ——
      //   内容框是圆的**外接方**，圆只在四个切点上碰框，所以连智慧树的白都只报 `挨框=2.1%`。
      //   → 这两条合起来就是上面那句"**区域必须建在模型自己的拟合色场上**"的具体代价：
      //   不是还没找到合适的容差，是**这两种粒度都不表达"一块平色区域"**。
      //   要动这一族，第一步是那张区域图本身（H0 级），不是再挑一个判据数。
      //   （探针：`_harness/Plate2Kt.kt`，两种模式都在，跑出来的就是上面这两组死数。）
      //  （试跑污染那个 bug 已经在 `31fe511` 修掉了：fitGround 的退路现在逐张与不跑它一致。）
      //
      // ⚠ **2026-09-28 真机量到 H2 的另一条病 —— 和上面那张表无关，别再往表里加列。当天定案。**
      //   钉钉（`com.alibaba.android.rimet`，`_harness/dd/icon5_…`）：`底占=0.84 框内=0.11
      //   cc=10 top=0.26 小片=0.25`，按源亮度分桶只有白那一桶（L=100）：**这桶 66.8% 被判成底**，
      //   剩下当墨的 4799 格平均只出 **57/255**。用户的话是"基本每个都少一块一块"。
      //   **定案：是 A，不是 B。** 三条独立证据：
      //     · 那一层的实心格**全是** (254,254,254) —— 根本没有蓝盘，"白字在蓝盘上"这个前提是我
      //       凭空加的（B 就建在它上面）。盘在桌面那边，不在模块拿到的这张位图里。
      //     · `RimDbg` 按源色分桶量到 11231 个实心格里 **8132 格（72%）被判成底**，即 A 说的
      //       "鸟被划进 P"。三联图（`--dump`）直接看得见：中屏那一整块绿就是翅膀身体。
      //     · 把这一支退掉之后（见 [GROUND_INK_SAME_PCT]），同一张变 `H=3 cc=1 身体=245.8 洞=0`。
      //   修法当时写的"轮廓色在内容框内部占比过高"那条**没有采用** —— 它确实撞上面那张真值表。
      //   采用的是一个不描述参照、只描述**这一刀**的量：墨那一侧与底的本色分不开的格子过半。
      //   动手前钉的那条性质也钉了：`SelfTest` 里那条拿真机带回的 PNG 当样本的用例
      //   （H2 不许接手 / 实心格均浓 ≥240 / 不许有洞 / cc≤3），带 class md5 逐张量过 113 张。
      // 验收两件事：这块底**没吃掉整框**，而且它**与剩下的墨不是一个颜色**。后者是
      // [GROUND_INK_SAME_PCT] 那一段：真机钉钉那一批（判据台 23 行）全靠它。
      // 第三件（2026-09-28，抖音「还是缺了一部分」）：它**不比墨那一侧小** —— 见 [thinPlate]。
      val vac = vacuous(s, alt, box)
      val thin = thinPlate(alt)
      val narrow = alt.solid - alt.zero < GROUND_MIN_SPAN
      if (alt.share < PLATE_ALL_SHARE && !vac && !thin && !narrow) {
        alt.h1 = h1share; alt.h1thick = h1thick; alt.h1fams = h1fams; alt.h1ink = h1ink
        return alt
      }
      if (vac) s.veto = s.veto or 2
      if (thin) s.veto = s.veto or 8
      if (narrow) s.veto = s.veto or 32
      // **只有**"薄"这一条理由的才留候选：还带着 vacuous / 窗口塌掉的，是别的证据说"这块不能当底"，
      // 那种不许走最后手段（真机语料上正是这一条把抖音、taptap、钉钉、拼多多挡在门外 —— 它们
      // `拦=` 里除了 8 还有 2 或 32，改前改后逐位不动）。
      if (thin && !vac && !narrow) h2ThinOnly = alt
      h2share = alt.share; h2thick = alt.thick; h2enc = alt.enclosed; h2spread = alt.spread
    }
    // H1、H2 都没说出"哪块是底"之后，先问 H0（区域关系），再退到 H3（没有底）。
    // 位置很要紧：H0 抢在 H2 前面会撞翻判据台的「透明底+围住的镂空」那类用例，而它接手的活
    // 本来就只有"今天注定退成空气"的那几张（84 张真图标里走得到这里的只有 3 张）。
    regionGround(pixels, w, h, box, g, s, seed)?.let {
      it.h2 = h2share; it.h2thick = h2thick; it.h2enc = h2enc; it.h2spread = h2spread
      it.h1 = h1share; it.h1thick = h1thick; it.h1fams = h1fams; it.h1ink = h1ink
      return it
    }
    // **最后手段：宁可要一块薄底，也不要"根本没有底"。** 走到这里意味着 H1、H2、H0 都没说出
    // 哪块是底，剩下的选择只有 H3「空气当参照」—— 而对满幅不透明的圆盘图标，那等于宣布
    // **凡是画了像素的地方全是图形**，交出去就是一块平色饼（真机语料里塌得最狠的那批：
    // Chrome 0.78 / Spotify 0.79 / Telegram 0.78 / 百度贴吧 0.78 / edge 0.78 / 小米视频 0.75）。
    // 一块被 `thinPlate` 嫌薄的底，仍然比"没有底"多知道一件事。
    // ⚠ 这不是把 `thinPlate` 撤了：它照旧否掉一切"还有别的毛病"的候选，这里只接住
    //   **唯一毛病是薄**、且**退路是整张涂满**的那几个。接手要记 `拦=` 第 64 位 ——
    //   它不是正常过关的 H2，日志里必须分得出来。
    h2ThinOnly?.let {
      it.h1 = h1share; it.h1thick = h1thick; it.h1fams = h1fams; it.h1ink = h1ink
      it.h2 = h2share; it.h2thick = h2thick; it.h2enc = h2enc; it.h2spread = h2spread
      s.veto = s.veto or 64
      return it
    }
    bandSeed(s, w, box, band, seed)
    val air = fitGround(pixels, w, h, box, g, s, 0, seed)
    air.hyp = 3
    air.h2 = h2share; air.h2thick = h2thick; air.h2enc = h2enc; air.h2spread = h2spread
    air.h1 = h1share; air.h1thick = h1thick; air.h1fams = h1fams; air.h1ink = h1ink
    return air
  }

  /**
   * **H0：不问"哪个位置是什么色"，问"图上有没有一块大区域、肚子里坐着别的画、而且它就是画布自己那一层"。**
   *
   * 为什么要有这一支：H1 取内容框四角的色、H2 取内容轮廓的色 —— 两个种子都在**描述某个位置的颜色**。
   * 而"底"本来说的是一种**关系**。这条关系正是 [groundPass] 那张真值表（占比 / 厚度 / 围合 / 环散）
   * 里唯一没有的量，也是那四个量分不开的那一对唯一还分得开的地方。
   *
   * 四道门槛，每一道都在 `_harness/GateKt.kt` 上量过（edge=384，"该收"= 图上确实有一块承载面）：
   *  ④ **内容框 ≥ [REGION_FULL_PCT] 的整幅画布**。这条才是把 H0 与判据台那四条"盘子"用例分开的东西：
   *    一张图缩在画布中间时，"框外是空气"说的是**画里的留白**，不是"这张图没有底"。绿盘子中间坐
   *    一个白圆（「透明底+围住的镂空」）与色盘子上坐一个闪电（小步点）在区域关系上**同构**，
   *    区别只在前者的盘子是**被画出来的物体**、后者是**承载面**。两侧实测差得很开（`盒布=` 那一列）：
   *      收 小步点 100 中国移动 100 Moondrop 100 元宝 100      ← icons/ 里点名的那几张
   *      拒 合成「透明底+围住的镂空」44  icon81 21  icon500 14  ← rec3/ 里上一版 H0 顺手接手的两张
   *    后两张正是那一次 A/B 里我说不出好坏就跟着变掉的图 —— ④ 把它们退回今天的行为。
   *    ⚠ 这条门槛认的是"模块拿到的那张位图"：桌面自己留了边距的呈现（判据台 `--inset`，
   *    名样本一律量到 37）会让 ④ 全体不放行 —— 那一类上 H0 不开口，等于今天，不会更坏。
   *  ① 最大那块 ≥ [REGION_MIN_PCT] 的内容框；② 它**挨着内容框的边**（缩在正中的大家伙是图案）；
   *  ③ 它的**洞里**（补集里碰不到框边的那些连通块）至少 [REGION_HOLE_PCT]% 的内容框是不透明的 ——
   *    那就是"坐在它上面的画"。
   *
   * **五条候选判据被量掉，所以没写进来**（头两条是上一轮的报告当成"下一步"提出的，后三条是
   *  2026-09-29 为 `com.cnspeedtest.globalspeed` 那道弧提的 —— 全在这一支门外量完，一条没进代码）：
   *  · 「区域要比它围住的更**实**」：合成镂空 区α=255 洞α=255、小步点 254/254 —— 相等，分不开。
   *  · 「围住的要比区域更**亮**」：合成镂空 L 74.3→100.0 与 小步点 78.9→95.9 **同向**，而该收的
   *    中国移动是 99.7→61.1（反向）、QQ 99.7→43.9（反向）。它既分不开那一对，还会把点名的应用推掉。
   *  · 「底像素**局部薄**的那一堆 = 穿着底色的图形」（2026-09-29 为 `com.cnspeedtest.globalspeed`
   *    提的，当时说"围合看不见那道弧，它要的是厚度"）—— **厚度这一维也否了**，两条各自独立地否：
   *      - 分不开：`底厚≥20`（真是块盘）的 44 张里 **40 张有 ≥3% 的"内薄"**，占比 8.5%~29.6%，
   *        而全球网测自己就在这带里（真机那张 **11.1%**）。`--shot` 涂红看得懂为什么：薄格子描的是
   *        每样东西外圈 1~2px 的毛边（盘沿、弧的两侧、每个字周围），饿了么那 29.6% 全是压缩噪点。
   *        那道弧自己是 ~25px 的**宽带**，与盘只差 3 倍 —— 语料的噪声底就在这个倍数里。
   *      - 换形状判据也不行（底色连通块、面积≥框 0.5%、边界≥80% 压在图形上）：全球网测**不命中**，
   *        因为它的弧与背景是**同一块连通底**（真机那张 384²：最大底块 129162 格、最厚 76、边界 图:空=3014:1536=66%），
   *        与 `围=` 失败是同一个原因；而命中的 8 张是王者荣耀占框 **68%**、浙江在线 **45.6%** 这种
   *        照片式插画被噪点打碎的**底碎片**，方向正好相反。
   *      - 而且只有 **56/114** 张会算出 `底厚`（其余 -1，[plateThickness] 没跑）：按厚度下的判据
   *        对三分之二的语料是瞎的。
   *    探针：`_harness/ThinKt.kt`（默认量内薄/外薄，`--slab` 量被图形包住的底块，`--shot=DIR` 涂红）。
   *  · 「**顺着颜色走向继续洪泛**：从图形出发，只要颜色还连续地往下走就继续吃进底」—— 这一条是三条里
   *    最像"真的那一维"的，死得最干净，**两种活法各死一头**（`_harness/RampKt.kt`）：
   *      - 带刹车（每跳 ΔE≤12 **且**明度继续朝同一方向变）：**61 张有底的图里 0 张吃进一格**。
   *        全球网测 3014 个"图形↔底"边界对里只有 139 对是颜色连续的，而**这 139 对里 ΔL≠0 的
   *        一对都没有** —— 模型的图↔底边界，在它是连续的地方**纯是彩度差、明度不动**。这一维没有燃料。
   *      - 不带刹车（纯 ΔE≤12）：**全球网测自己 91% 的底被吃掉**、易班 91%、智慧树 73%，
   *        61 张里 16 张超过跑之前写下的那条淘汰线（中位 >2% 就否）。`--shot` 的图就是整块背景变红 ——
   *        它不是把那道弧救回来，是把"底"这个概念溶掉。
   *      - 根子在事实层，不在判据：**那道弧的尾段与背景在颜色上本来就连成一片**，这正是它被判成底的
   *        原因，也正是任何颜色规则都分不开它的原因。
   *    ⚠ 上面这几条关于全球网测的数是**用真机抓回来的那张 384² 重量的**（判据台重放与设备自己打的
   *    那行 35/35 全同）。先前写的"弧与背景是同一块连通底"引的是从 APK 里挖的那张 512² ——
   *    **同一条 8×8 指纹，35 个字段里有 9 个不同**（含 `分档`）。同 sig 不等于同像素。
   *  · 附带量掉的一条**发档**写法（不属这一支，记在这因为同一轮做的）：把 [SEP_MIN_GAP] 的固定档距
   *    换成 `min(30, 两块源色的 ΔE)`（源色本来就差不到 30，就不许为了发档硬拉开）——
   *    113 张报告行**逐字节全同**（候选在 `_harness/cand/MaskC.kt`，仓里这份没动）。它证明的是：
   *    **易班那 6 块的档不是 [assignLevels] 压掉的**，这一层对它根本没动手；丢的地方在
   *    dep→浓度那条标定链上（6 块的 dep₁ 跨 141、交付只跨 77，而且两个次序互相打脸）。
   *    别再往发档这一层找它，见 [LEVEL_FLOOR] 顶上"那些档是编出来的"那一条。
   *
   * ⚠ 读 `分档=` 那列的口径：它是 **档数/块数(每块的交付浓度)**，不是块数在前。
   *   拿第一个数当块数会得出"淡的代价都落在少块的图上"这种相反结论（2026-09-29 错过一次，
   *   重算才翻回来，见 [SEP_MIN_GAP] 那一段）。
   *
   * 已知管不到的：整张缩在画布中间、但确实该认底盘的那一类（老式图标里画一块盘当底）—— ④ 把它们
   * 一起放掉了。这是**选了**的：那条边界上判据台钉着四条"盘子是画、不是底"的性质，而真机点名的一批
   * 全在 100 那一侧。这一支只接手今天注定退成 H3（空气）的图：它排在 H2 被否之后、H3 之前。
   */
  private fun regionGround(
    pixels: IntArray, w: Int, h: Int, box: Rect, g: Int, s: Scratch, seed: ByteArray,
  ): Pass? {
    val boxArea = box.width().toLong() * box.height()
    if (boxArea * 100 < w.toLong() * h * REGION_FULL_PCT) { s.h0why = 2; return null }
    // 走到这里就是在**考区域关系**了：先按"不成立"记，接手了再改 1（三个 return null 都在考完之前）。
    s.h0why = 3
    val rid = s.region
    val q = s.queue
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) rid[row + x] = -1
    }
    var best = -1; var bestArea = 0L; var next = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val start = row + x
        if (rid[start] >= 0 || Color.alpha(pixels[start]) < A_SOLID) continue
        val want = bucketOf(start, s); val my = next++
        var head = 0; var tail = 0
        rid[start] = my; q[tail++] = start
        var area = 0L
        while (head < tail) {
          val i = q[head++]
          val cx = i % w; val cy = i / w
          area++
          for ((dx, dy) in NEIGHBOURS) {
            val nx = cx + dx; val ny = cy + dy
            if (nx < box.left || nx >= box.right || ny < box.top || ny >= box.bottom) continue
            val j = ny * w + nx
            if (rid[j] >= 0 || Color.alpha(pixels[j]) < A_SOLID || bucketOf(j, s) != want) continue
            rid[j] = my; q[tail++] = j
          }
        }
        if (area > bestArea) { bestArea = area; best = my }
      }
    }
    if (best < 0 || bestArea * 100 < boxArea * REGION_MIN_PCT) { s.h0why = 5; return null }
    // ② 它**挨着内容框的边**（缩在正中的大家伙是图案）。
    //
    // ⚠ **"内容的边"要按 `α ≥ A_SOLID` 算，不能按内容框的边算，而且还要留 [REGION_EDGE_SLACK] 的余量。**
    //   内容框是 `α ≥ A_BOX` 的边界，它外面裹着一圈**鬼影 + 抗锯齿**：
    //   用户点名的 `com.example.appui`（白圆角方底 + 蓝图形）实测过圆心那一列 ——
    //   `α=1~3` 的鬼影占 4 行（y=9..12）、抗锯齿过渡又占 4 行（y=13..16，53/116/209），
    //   纯白要到 y=17 才开始。内容框顶在 y=9，白底区域够不到它 → `H0=6` 恒成立 →
    //   H0 被否 → 一路退到 H3「参照=没有东西」→ **整块图标被涂满**
    //   （`框内=0.73`，屏幕上就是"整个图标糊成一块"，`字均净=224` 但其实全是糊的）。
    //   只放宽固定像素数不够用：鬼影环的宽度跟着源图被放大的倍率走（这张是 192→384 放大两倍）。
    //   按实心边界判就与放大倍率无关了。
    var sx0 = box.right; var sy0 = box.bottom; var sx1 = box.left - 1; var sy1 = box.top - 1
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) if (Color.alpha(pixels[row + x]) >= A_SOLID) {
        if (x < sx0) sx0 = x; if (x > sx1) sx1 = x
        if (y < sy0) sy0 = y; if (y > sy1) sy1 = y
      }
    }
    if (sx1 < sx0) { s.h0why = 6; return null }     // 整块都是"鬼影"，没有实心内容
    var rim = false
    for (k in 0 until REGION_EDGE_SLACK) {
      val yt = sy0 + k; val yb = sy1 - k
      for (x in sx0..sx1) {
        if (rid[yt * w + x] == best || rid[yb * w + x] == best) { rim = true; break }
      }
      if (rim) break
      val xl = sx0 + k; val xr = sx1 - k
      for (y in sy0..sy1) {
        if (rid[y * w + xl] == best || rid[y * w + xr] == best) { rim = true; break }
      }
      if (rim) break
    }
    if (!rim) { s.h0why = 6; return null }
    // 求洞那一趟借用同一个数组打"走过"的记号（-2）：自己的数组，用完不必还原 —— 这正是上一版
    // 借 [Scratch.visited] 时做不到的事（它留了脏数据，两张 H0 没接手的图跟着变了答案）。
    var holeInk = 0L
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val start = row + x
        if (rid[start] == best || rid[start] == -2) continue
        rid[start] = -2
        var head = 0; var tail = 0
        q[tail++] = start
        var touches = false; var ink = 0L
        while (head < tail) {
          val i = q[head++]
          val cx = i % w; val cy = i / w
          if (cx == box.left || cx == box.right - 1 || cy == box.top || cy == box.bottom - 1) touches = true
          if (Color.alpha(pixels[i]) >= A_SOLID) ink++
          for ((dx, dy) in NEIGHBOURS) {
            val nx = cx + dx; val ny = cy + dy
            if (nx < box.left || nx >= box.right || ny < box.top || ny >= box.bottom) continue
            val j = ny * w + nx
            if (rid[j] == best || rid[j] == -2) continue
            rid[j] = -2; q[tail++] = j
          }
        }
        if (!touches) holeInk += ink
      }
    }
    if (holeInk * 100 < boxArea * REGION_HOLE_PCT) { s.h0why = 7; return null }
    // 四条都过：这块区域就是承载面。种子 = 它自己（不再靠颜色容差去猜它到哪为止），参照色 = 它的平均色。
    var sr = 0L; var sg = 0L; var sb = 0L
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (rid[i] != best) { seed[i] = 0; continue }
        seed[i] = 1
        sr += (pixels[i] shr 16) and 0xff; sg += (pixels[i] shr 8) and 0xff; sb += pixels[i] and 0xff
      }
    }
    val color = (0xff000000.toInt()) or
      ((sr / bestArea).toInt() shl 16) or ((sg / bestArea).toInt() shl 8) or (sb / bestArea).toInt()
    s.famL[0] = labChannel(color, 0); s.famA[0] = labChannel(color, 1)
    s.famB[0] = labChannel(color, 2); s.famColor[0] = color
    s.fams = 1
    val p = fitGround(pixels, w, h, box, g, s, 1, seed)
    p.hyp = 0
    p.ring = 1
    p.thick = plateThickness(w, box, s)
    p.holed = (holeInk * 100 / boxArea).toInt()
    // 区域关系说"这块大区域是底"，最后一问仍然是它切开了没有：一块与画同色的"底"不是底。
    if (vacuous(s, p, box)) { s.h0why = 4; s.veto = s.veto or 4; return null }
    s.h0why = 1     // 接手了：这一张的底是"区域关系"给的，不是颜色位置给的
    return p
  }

  /** 这一支交出的墨**大半与底是同一个颜色**（= 这一刀没切开任何东西，假设不作数）。
   *  `inkSame < 0` 是"量不出来"（有一侧是空的），既不赞成也不推翻 —— 见 [GROUND_INK_SAME_PCT]。
   *  退掉的那一支把自己的数留在 [Scratch.vetoSame]：不留在日志上，这道验收红了也没人知道为什么。
   *
   * ⚠ **底自己不实（β<128）时也算"没切开"**，这一条不是新加的门槛，是同一个守卫的第二处：
   *   一块九成三是空气的"底"没有本色可问 —— `inkSame` 量的是一条噪声。2026-09-28 放宽连通之后
   *   openlist 被 H2 以 `底占=0.89 β=18` 接手，cc 从 2 碎成 14，就是因为它的 `inkSame` 掉到 13
   *   而不再被退。与 `残盘=` 那条用同一个 `colourDriven` 的门槛（128），不另立数。
   */
  private fun vacuous(s: Scratch, p: Pass, box: Rect): Boolean {
    // `inkSame < 0` = 有一侧是空的（整张都是底，或整张都不是），**量不出来**：不赞成也不推翻。
    // 这条不只是保守：`meanCov` 是在墨那一侧上量的，墨侧为空时它退化成 0 —— 没有下面那个
    // `inkSame >= 0` 的短路，「整张纯色」（正确答案是一张空掩码）会被误判成"底是空气"而退到
    // H3，整张涂成实心饼。2026-09-28 就是这么红了一次。
    // ⚠ 但"墨侧为空"有**两种**，上面那句只说中了其中一种（2026-10-07 逐张量出来的）：
    //   · 整框每一格都是这层实心漆（`gN == 框面积`）—— 这才真是「整张纯色」：没有图形，
    //     空掩码是对的；
    //   · 框里**还有空气**（`gN < 框面积`）—— 这块"底"只是坐在空气里的一个形状，而形状
    //     本身就是图形（兼容性台 `空气+白剪影` 那条契约说的是同一件事）。在这里弃权，交出去
    //     的就是一张空掩码 = 屏幕上整个看不见。真机四张全死在这条上：documentsui
    //     `盘实=98971/0`、glimpse `103809/0`、kernelsu `30276/0`、pixez `22496/0`。
    //   分的不是颜色，是**这块"底"里有多少格真是实心漆** —— 一个 α 几何量。刻意没有百分比：
    //   「整张纯色」那一张 `gN` 与框面积**恰好相等**，四张坏图差的都是几万格，没有可调的空间。
    if (p.inkSame < 0) return p.iN == 0 && p.gN.toLong() < box.width().toLong() * box.height()
    if (p.inkSame < GROUND_INK_SAME_PCT && p.meanCov >= 128) return false
    s.vetoSame = if (p.meanCov < 128) -p.meanCov else p.inkSame
    return true
  }

  /** **被判成底的那一半实心，比被判成墨的那一半还小 —— 那它就不是承载面。**
   *  与 [vacuous] 同一族：量的都是**这一刀**，不是"这块参照长什么样"（那条路上四列已经试过并
   *  否掉了，见 H2 那段真值表）。抖音（`com.ss.android.ugc.aweme`）2026-09-28：H2 把青+红两层
   *  错位影认成底，`盘实=6294 < 墨实=9614` —— 于是那两层被当盘子擦掉，用户看到的正是
   *  **「还是缺了一部分」**（音符外面那两条 crescent 没了）。Xbox SmartGlass 更极端：`盘实=0`，
   *  所谓底一格实心都没有，全是空气与抗锯齿。
   *  退掉之后落回 H0/H3（"没有底"），整张轮廓如实交成墨 —— 与钉钉那一次同一条路。
   */
  private fun thinPlate(p: Pass): Boolean = p.gN < p.iN

  /** H0 的三道区域门槛 + 那道画布门槛。分界都来自 [regionGround] 那段注释里的实测表，两侧差得远。
   *
   *  ⚠ **这一支接不了"满幅插画"那一张，量过的**（2026-09-28，用户先点智慧游戏「取色有点问题」、
   *   再说「感觉反下色更好」）：把 H1/H2 的验收临时全关掉，让 H0 先挑 —— 六张插画
   *   （wisdomgame / browndust2 / sgameGlobal / astralparty / songpal / kazumi）**一张都没走到接手**：
   *   五张 `H0=3`（被区域关系拦），一张 `H0=2`（被画布门槛拦），全部落到 H3，
   *   交出去是 `框内=0.94~1.00` 的实心方块。原因是结构性的：这里的"区域"是**按颜色量化后
   *   的连通块**，而画出来的图是连续渐变，量化不出"一整块把别的东西围起来"的区域。
   *   所以"给这一类做一支按区域认主体的分支"这条**不成立** —— 要做得换一套按色调切分
   *   （先把画面切成两个色调，再问哪一侧是被另一侧围住的），那是新算法，不是这道门槛放宽能换来的。
   */
  private const val REGION_FULL_PCT = 80
  private const val REGION_MIN_PCT = 25
  /** ② 「挨着内容框的边」允许的余量（像素）：抗锯齿环在任何 edge 下都是 1~2px，
   *  框边那一行/列本身是混色，不属于任何"底色"的桶 —— 不留余量这道门恒不成立。 */
  private const val REGION_EDGE_SLACK = 2
  private const val REGION_HOLE_PCT = 2

  /**
   * **这块"底"有多厚**：从它的边界（挨着非底或挨着框外）做多源 BFS，取底像素到边距离的
   * p85（单位：像素）。
   *
   * 这是为开一条判据准备的数 —— 「底吃掉了整框」既可能是**满幅圆盘**（该认它是底，图形坐在上面），
   * 也可能是**线条字自己的轮廓**（认了就等于把图形腾空，就是他报过并回退的「只剩线条」）。
   * 两者 share 都接近 1，**厚度差一个量级**：圆盘几十像素，描边几像素。
   * 而且它不依赖 β —— β 那条（中位数治灰框）之所以不敢动，就是因为 [PLATE_ALL_SHARE] 这个阈值
   * 是绕着 β 的均值长出来的；换成厚度之后两件事解耦。
   */
  private fun plateThickness(w: Int, box: Rect, s: Scratch): Int {
    val boxW = box.width(); val boxH = box.height()
    val n = boxW * boxH
    if (s.thickDist.size < n) s.thickDist = IntArray(n)
    val d = s.thickDist; val q = s.queue
    for (i in 0 until n) d[i] = -1
    var head = 0; var tail = 0
    for (y in 0 until boxH) {
      val row = y * w
      for (x in 0 until boxW) {
        val i = row + x + box.left + box.top * w
        if (s.plate[i] == 0.toByte()) continue
        // 挨着非底（含框外，框外从没被清零，所以只看框内邻居）就是这块底的"边"
        var isEdge = false
        for ((dx, dy) in NEIGHBOURS) {
          val nx = x + dx; val ny = y + dy
          if (nx < 0 || ny < 0 || nx >= boxW || ny >= boxH ||
            s.plate[(box.top + ny) * w + box.left + nx] == 0.toByte()
          ) { isEdge = true; break }
        }
        if (isEdge) { d[y * boxW + x] = 0; q[tail++] = y * boxW + x }
      }
    }
    if (tail == 0) return 0
    var mx = 0
    while (head < tail) {
      val c = q[head++]
      val x = c % boxW; val y = c / boxW
      for ((dx, dy) in NEIGHBOURS) {
        val nx = x + dx; val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= boxW || ny >= boxH) continue
        val j = ny * boxW + nx
        if (d[j] >= 0) continue
        if (s.plate[(box.top + ny) * w + box.left + nx] == 0.toByte()) continue
        d[j] = d[c] + 1
        if (d[j] > mx) mx = d[j]
        q[tail++] = j
      }
    }
    // p85：一半的底厚到哪儿不算厚，取到八成 —— 描边的 p85 只有几像素，圆盘有几十。
    val hist = IntArray(mx + 2)
    var seen = 0
    for (i in 0 until n) if (d[i] >= 0) { hist[d[i]]++; seen++ }
    if (seen == 0) return 0
    var run = 0
    for (v in hist.indices) { run += hist[v]; if (run * 100 >= seen * 85) return v }
    return mx
  }

  /**
   * 剩下的那些"墨"里，有多大比例是**被底围住的**（它所在的那块连通域碰不到内容框的边）。
   *
   * 这是 H2 验收的第三条、也是最后一条：厚度说"这块底是一块板"，占比说"它没吞掉整框"，
   * 但两者都还分不开**"两半拼的 logo"**：红蓝各半那种，两半都是厚块、都只占一半，
   * 按前两条会把红半认成底、蓝半认成图形 —— 红半就此腾空（判据台「两半都留着」钉着它，
   * 实测这条一红就是 红=237 蓝=6）。区别只在**位置**：拼板的每一半都露在空气里，
   * 而坐在底板上的图形是被底**围住**的（小步点的闪电、华为的字标都是）。
   */
  private fun enclosedShare(pixels: IntArray, w: Int, box: Rect, s: Scratch): Int {
    val bw = box.width(); val bh = box.height(); val n = bw * bh
    if (s.thickDist.size < n) s.thickDist = IntArray(n)
    val lab = s.thickDist                        // 0 = 未定，-1 = 底，-2 = 碰过边
    for (i in 0 until n) lab[i] = 0
    val q = s.queue
    var ink = 0
    for (y in 0 until bh) {
      val row = (box.top + y) * w + box.left
      for (x in 0 until bw) if (s.plate[row + x] != 0.toByte()) lab[y * bw + x] = -1 else ink++
    }
    if (ink == 0) return 0
    // 「露在空气里」的起点：四邻里有一格是框外、或者本来就是**透明**的（α < A_SOLID）。
    // 只按外接框的边算不行 —— 两半拼的 logo 常常整个缩在框中间，露的是自己那圈空气。
    var tail = 0
    for (y in 0 until bh) for (x in 0 until bw) {
      if (lab[y * bw + x] != 0) continue
      var open = false
      for ((dx, dy) in NEIGHBOURS) {
        val nx = x + dx; val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= bw || ny >= bh ||
          Color.alpha(pixels[(box.top + ny) * w + box.left + nx]) < A_SOLID
        ) { open = true; break }
      }
      if (open) { lab[y * bw + x] = -2; q[tail++] = y * bw + x }
    }
    // 挨着"露边的那块墨"的也算露边
    var head = 0
    while (head < tail) {
      val c = q[head++]
      val x = c % bw; val y = c / bw
      for ((dx, dy) in NEIGHBOURS) {
        val nx = x + dx; val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
        val j = ny * bw + nx
        if (lab[j] == 0) { lab[j] = -2; q[tail++] = j }
      }
    }
    var enclosed = 0
    for (i in 0 until n) if (lab[i] == 0) enclosed++
    return enclosed * 100 / ink
  }

  private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

  /** 参照吃掉整框到这个比例，就说明"这块底"其实是图形自己（薄字形的轮廓=字形）→ 假设不成立。 */
  private const val PLATE_ALL_SHARE = 0.92f

  /**
   * 内容框占画布不到这个比例，就**不许**用"整张只有一个颜色家族"去豁免 [PLATE_ALL_SHARE] 那道闸。
   *
   * 20 是这么来的（`_harness/SiloKt` + 真机日志）：中招的长按快捷方式 `盒布 = 3% / 4% / 10%`，
   * 而必须保住的两个反例远在另一边 —— 「整张纯色」`盒布=100%`（钉着的性质：交出一张空掩码）、
   * 合成方板 `盒布=49%`。取 20 让两边都有余量，不是拟合出来的最优点。
   */
  private const val GROUND_ISLAND_PCT = 20

  /**
   * **墨那一侧有多少比例的格子与底的本色分不开**（ΔE76 < [GROUND_INK_JND]），到这个比例这一刀
   * 就不算切开了东西。
   *
   * 为什么要有这一道，以及它为什么不是 [PLATE_ALL_SHARE] 那张真值表里"第五个阈值"：
   * 那张表（占比 / 厚度 / 围合 / 环散）量的全是**参照这一块自己长什么样**，而钉钉那一批的病是
   * "参照 = 字形自己的颜色" —— 白翅膀浮在透明上，H2 从内容轮廓下种子，洪泛顺着同一种白灌进
   * 翅膀身体，于是**身体成了底、抗锯齿边成了墨**，屏幕上就是一块没上色的洞（三联图见
   * `_harness/RimDbg` 的 `--dump`：左=源、中=一遍判的底、右=交付的墨）。
   *
   * 它描述的不是"这块参照长什么样"，而是**这一刀有没有把图切成两个不同的东西**。切不开颜色的
   * 一刀不携带任何信息，正确答案就是"这一层根本没有底"→ 退到下一支，最终退到 H3（空气），
   * 整块实心剪影交出去 —— 那正是这一类图在单色规范下该有的样子。
   *
   * ⚠ **为什么按格子数、不按"两半的平均色之差"。** 第一版写的是后者，被判据台的
   *   「同距离不同明度侧」当场拦下：那块底是中灰，墨是**一深一浅两块**——两个平均色正好
   *   互相抵消在底上，ΔE(均值,均值)≈0 就被当成"没切开"，而它切得清清楚楚。按格子数是逐像素
   *   问"这一格跟底本色分得开吗"，跨在底两侧的墨各算各的，抵消不了。
   *   同一条性质在那一版上从 亮=225 掉到 亮=28（整块浅色被擦掉）。
   *
   * ⚠ 门槛画在 **65**，理由是判据台 113 张真机像素上的**实测分布**（被退那一支自己的 `inkSame`，
   *   它进日志就是为了这一天）：
   *     100 ×13 张（钉钉 / 微信 / taptap / WPS / steamlink / openlist / 小黑盒 / 音流记 / 拼多多…）
   *     82 ×3 77 ×3 73 ×3 70 ×1        ← 全是 H2 接手的那一批，这一道要救的就是它们
   *     ─────────────── 空档 ───────────────
   *     54 —— 合成用例「白字在彩盘上」的 H0：浅蓝盘 + 一条通向盘边的白字，**这一刀切得清清楚楚**
   *     50 —— 翻译（`com.google.android.apps.translate`）的 H1
   *   第一版写的是 50，结果把 6b 那一张**整个反过来**：盘成了墨、白字成了洞（字=255 → 28）。
   *   所以门槛只能落在 54 与 70 之间那个空档里；取 65。
   *   代价说清楚：**翻译那一张因此救不到**（它停在 50，而它本可以 190→245、cc 4→2）。
   *   它是"盘与字确实同色、但留白也是那个色"的一类，不是这一道管的东西 —— 别为了它把门槛
   *   挪回 50，那等于把 6b 反过来。
   *
   * ⚠ 两个数都是**绝对**值，不是这张图自己的分位。是有意的："两个颜色人眼分不分得开"不是图像
   *   相对的性质。量不出来（有一侧空）时返回 -1，[vacuous] 认它"不作证也不推翻"。
   */
  private const val GROUND_INK_SAME_PCT = 65

  /** 刚可觉差：ΔE76 到 12 以下就是同一个颜色。Lab 存的是 ×64，所以比较时用 12×64。 */
  private const val GROUND_INK_JND = 12 * 64

  /**
   * H2 验收的两条换候选 —— **都还没接线**，原因见 [groundPass] 里那条验收的注释。
   *
   * ⚠ 这里原来挂着两个 `private const val`（`PLATE_THICK_DIV = 16` / `ENCLOSED_MIN = 50`），
   *   而它们**一个都没被读过** —— 常量摆在那里会让「厚到 1/16」「围合 50%」看着像生效中的
   *   阈值，而真正在场的是别的判据。2026-09-30 收掉常量、只留候选本身：
   *  · **厚度**：参照这块要**厚到框短边的 1/16** 才算一块底板（按短边归一，不写死像素 ——
   *    同一张图在 192 / 384 上必须给出同一个答案，判据台钉着尺寸不变性）；量它的是 [plateThickness]。
   *  · **围合**：剩下的墨里要有大半**碰不到空气**，否则那块"底"只是并排的图形之一；
   *    量它的是 [enclosedShare]。
   */

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
   *
   * ⚠ 入场券原来写的是「轮廓像素 < 1/50 框就不成立」，**那条是反的**：一圈 1px 的轮廓，
   *   数量只跟周长走（∝边长），而框是面积（∝边长²）—— 于是**越铺满的圆盘越容易被拒**，
   *   而小得多的线条字反而能过。真机上他点名的三个「反色」有两个正是死在这儿：
   *   小步点 / 中国移动 那种铺满框的圆底图标，轮廓占框 0.8% → H2 直接不跑 → 退到 H3
   *   「参照=空气」→ dep 变成覆盖率 → 整张涂满、图形反而镂空（日志里就是 `H=3 底厚=-1`）。
   *   ⚠ **这条门槛还没改，但不是因为不敢**：把它改成"只要轮廓存在"之后 H2 会跑（试跑污染那个 bug
   *   已经修掉了，`31fe511`），小步点确实翻正 —— 可同时放进来 6 张、打翻 2 张（steamlink、
   *   哔哩哔哩），而给"空主张"开豁免又会把联系人 / Song Search 从 H3 推进 H2-空（整块实心）。
   *   四个候选判据（占比 / 厚度 / 围合 / 环散）全部量过，**没有一个分得开这两组**，
   *   详见 [plateThickness]、[enclosedShare] 与下面 `ringSpread` 那段的真值表。
   *   所以这一类要修，缺的不是阈值，是一个新的证据种类。
   *   ⚠ 2026-09-29 又量了**第五个**：把这条闸换成尺度对的 `n ≥ (bw+bh)/4`（单位错是真的，
   *   见下面 `boxArea` 旁边那段）跑了一遍全扫。**结论没变，代价换了人** —— 通讯录与腾讯元宝修好了，
   *   Azure Authenticator 与那张橙盘白线字反而把底板丢了。逐张的数与图都在下面那道闸旁边。
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
    val boxPer = box.width().toLong() + box.height().toLong()
    // ⚠ **这道闸的单位原来是错的，2026-09-30 修成对的了 —— 两笔账是怎么翻的要记清楚。**
    //   原来写的是 `n*50 < 面积`：`n` 是轮廓像素数，按**周长**长（∝边长），分母是**面积**（∝边长²）
    //   —— 于是**图越大这道闸越严**。实测（`_harness/RingKt`，113 张真图标 × 96/192/384/768 四档）：
    //   按面积归一时 **113 张里 113 张**的过/不过会随边长改变；换成 `n/(bw+bh)` 就稳（通讯录四档
    //   1.87/1.90/1.91/1.98），换支张数 = 0。**单位这条一直成立，只是当时不敢收。**
    //   2026-09-29 第一次逐张看图，账是 **2 好 / 2 明确坏 / 1 wash**：好的是通讯录 @384 从「整张涂满」
    //   （`H=3 框内=0.87`）回到 `H=2 框内=0.18`、腾讯元宝 净 163→248；坏的是 **Azure**（盾整个没了，
    //   只剩里面那个人）与 `icon541`（橙盘+白线字，盘没了只剩线字浮在那儿）—— 那两张**该留在 H3**，
    //   它们那圈轮廓**就是底板自己的边**，放 H2 进去等于宣布底板是空气。**当时不收，是对的。**
    //   2026-09-30 换了语料口径（`debug/CorpusCapture` 一轮抓 122 张真机图标）才看见：
    //   **老那 113 张里"满幅圆盘"这一类一张都没有** —— 判据 `框内≥0.66 且只有一档` 是
    //   **0/113 vs 7/122**（Chrome / 坚果云 / Spotify / 驾考宝典 / Limelight / audiofx / browndust2，
    //   七张数字逐位雷同 `H=3 底占=0.21`，而 **0.21 正是内切圆外那四个角**：模型能找到的底只有空气，
    //   盘里那块盘没有一条边连到框角，洪泛走不到）。重判 235 张：换署名的 96 张**几乎全是噪声**
    //   （`H 0→2` 而框内、分档逐位不动 —— 别数那个），真正动了交付的 **13 个应用 = 10 好 / 2 坏 / 1 wash**。
    //   好：驾考宝典、皎月连、feimo、Limelight、Steam 两张、饿了么、通讯录、aperture、files
    //   （现在是一整块淡饼，换完是看得见的图形）；坏：**还是 Azure 与混元**（盘整个没了）——
    //   **坏的一侧一张没变，变的是好的一侧被新语料补上了。** 逐张对照在 `_harness/bench/cmp_sheet/`。
    //   全扫另买到底价：**`框内随边长漂移 28→18`**（修单位本来就该买的东西）、ρ色 0.871→0.915、
    //   SelfTest 48/48 与兼容性台 `PASS=59 GAP=0 FAIL=9` 全不动；代价是三项轻差
    //   （`淡(<200) 29→30`、`档数 312→310`、`顶平% 43→45`），主人认账后 `--update` 进了基线。
    //   ⚠ **但"再来挑一档"这件事仍然不许**：`n/(bw+bh)` @384 是 线字盘 1.40 < 盾 1.78 < **通讯录 1.91**
    //     < steamlink 3.06，该收的夹在两个该拒的中间，换任何常数都是**换一批错**而不是修好。
    //     这是**第五个**这样的量（前四个：占比 / 厚度 / 围合 / 环散，见上面那张真值表）。
    //     **收的是"单位对"，不是"这档调得准"**：Azure / 混元那一类要修，得换一个不描述参照长什么样的
    //     证据（第六种），别回到这道门里挑数。
    if (n.toLong() * 4L < boxPer) return 0
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
    // **这一圈自己是不是一种颜色。** 猜的是：轮廓可以横跨好几块（联系人的卡片边上同时切过
    //  深卡片、浅色人像、信封三角），那时中位色谁都不是，于是"离参照多远"对整张图都大 →
    //  屏幕上是一整块实心。量的还是模型已有的那个东西：参照自己的散度（轮廓到它自己中位色的 p97 ΔE）。
    // ⚠ **猜错了，数在这儿**（该收 / 该拒 分不开，方向还反着）：
    //    小步点 1（收）  steamlink 0（收）  哔哩哔哩 59（收）
    //    taptap 0（拒）  联系人 24（拒）    Song Search 7（拒）
    //  理由回头看也站得住：taptap 那一圈**就是字形自己的颜色**（单一、散度 0），拿它当参照
    //  当然把字形吃掉一半；哔哩哔哩的环是混色，可它的洪泛本来什么都没 claim 住，参照只是个起点。
    //  留着这个数，是因为它和 [plateThickness] / [enclosedShare] 一起把结论钉死：
    //  **这四个量里没有能分开这两组的信息**（哔哩哔哩与联系人在 占比/厚度/围合 上逐位相同 0/0/0），
    //  别再试第五个阈值 —— 要修这一类，得换一个不是"描述这块参照长什么样"的证据。
    s.hist2.fill(0)
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (seed[i] == 0.toByte()) continue
        val de = deltaE(
          s.labL[i].toInt(), s.labA[i].toInt(), s.labB[i].toInt(),
          s.famL[0], s.famA[0], s.famB[0]
        ) shr 6
        s.hist2[de.coerceAtMost(DBUCKETS - 1)]++
      }
    }
    s.ringSpread = percentile(s.hist2, n, TOL_PCT)
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
   *
   * ⚠ 试过把工作集从"所有非底"收紧成"第一遍真的上了墨的那些"（dep₁ > 那个零点），理由是
   *  底板残边不该当主体。真机重放量下来**几乎不动**（框内 0.312→0.313）：那圈残边的 dep₁ 均值
   *  本来就有 77~84，远在零点之上，收紧收不掉它们。管用的是下面按 Σdep₁ 加权的 [Pass.weight]
   *  与出图处那条"谁交出的墨多谁是主体"的前提，不是这里。
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
    // 最后一轮的"两边各有多少**墨**"：主体那一边与全部墨那一边，都按第一遍的 dep₁ 加权。
    var inkDep = 0L
    var bodyDep = 0L
    var tol = DEP_COARSE
    for (iter in 0 until BODY_ITERS) {
      if (iter > 0) connect(s, w, box, shell)
      fitField(pixels, w, box, g, s, body, solidRef = true)
      propagateField(w, h, total, box, s, body)
      s.hist.fill(0)
      s.hist2.fill(0)
      nRef = 0
      nOff = 0
      inkDep = 0
      bodyDep = 0
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
          val d1 = (s.dist[i].toInt() and 0xffff).toLong()
          inkDep += d1
          if (body[i] != 0.toByte()) {
            s.hist[d.coerceAtMost(DBUCKETS - 1)]++
            nRef++
            bodyDep += d1
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
    // 第二遍能降多少，先看主体在**墨**里占不占到一半：只占 6% 的那张 —— 整张插画，根本没有
    // "主体"可言 —— 就没有"第二层"可刻，硬刻等于把整张图降下来。
    // 旧版这里是一条二值的 `bodyShare < BODY_MIN_SHARE → 不做`，后来改成按占比连续给。
    // ⚠ 占比必须按**墨**（Σ dep₁）算，不能按像素数 —— 真机重放里华为应用市场 / 12123 两张
    // 量出来的病灶：底板洪泛没吃干净的中间调残边（dep₁ 4~21，本来几乎不上屏）也是"贴着底的墨"，
    // 于是它们把主体那一边整个占满（11.5k 像素 vs 白色主字 9k），按个数算主体占到 55% → 满力
    // 降层 → 主字从 dep=205 被压到 37。按墨加权，那些残边加起来不到 8%，权重自动退回去。
    p.weight = if (inkDep == 0L) 0 else (bodyDep * 510 / inkDep).coerceAtMost(255L).toInt()
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
   *     dep = max(a − c, 0) + a × β × min(ΔE, EMAX) / EMAX
   *
   * 第一项问的是**这一像素比参照自己实出来的那份多出多少 α**（`c` = 那把尺：默认 β，
   * 出图那一步在"同一种料"时换成 [Scratch.fTop]），第二项是颜色差按 α 与 β 一起缩过的那一份。
   * ⚠ **别把第一项"化简"成 `a×(1−c/255)`**：`0 < a < c` 那一段正是描边的抗锯齿像素所在，
   * 按比例那一版会给它们留下墨、连成一条斜痕 —— 2026-09-30 有人这么改过一次，
   * 判据台把 `abfa161` 修掉的东西原样还了回来（翻译那张 `淡` 260→504、`字均` 238→234）。
   * 另：`c ≥ b` 恒成立（fTop 是这一格 ≥A_SOLID 的最大 α，b 是均值），所以 dep ≤ a 恒成立。
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
   *   它的 RGB 就是它自己；而"淡的本来就该近"已经由 `dep ≤ a` 兜着（两项各自都不超过 a），
   *   不需要再动它的色相。
   */
  private fun departure(pixel: Int, i: Int, s: Scratch, withCoverage: Boolean = true, byTop: Boolean = false): Int {
    val a = Color.alpha(pixel)
    if (a == 0) return 0
    val cell = if (s.ref[i] >= 0) s.ref[i] else 0
    val de = deltaE(
      s.labL[i].toInt(), s.labA[i].toInt(), s.labB[i].toInt(),
      s.fL[cell], s.fA[cell], s.fB[cell]
    ) shr 6
    val cf = if (de >= EMAX) 255 else de * 255 / EMAX
    val b = s.fCov[cell]
    // ⚠ `byTop` 只在**出图**那一步开（见 [build] 的出图循环）：覆盖率那一项问的是
    //   「这一格有没有实参照」，用 [fTop]；而洪泛决策里问的是「这一格平均有多实」，
    //   必须继续用 β —— 两个口径混用会把美团 / Google Photos 的 H 从 3 翻成 1、整张变空。
    //
    // ⚠ **而且只在"这一像素与参照根本是同一种料"时才换尺**（`de < SEAM_DIVERGE`）。
    //   不设这道门的代价在判据台上量得很清楚：113 张真图标 `字均净` 净 −99，
    //   且**几乎全落在驾考宝典（3 张 −31）与王者荣耀（1 张 −21、3 张 −13）**上 ——
    //   那几张的字形压在盘上、格子跨在"盘↔**空气**"之间，`α×(1−β)` 那一项本来就是
    //   它们唯一的墨量来源，一刀切掉就等于把字形抽淡。翻译那条斜痕不一样：
    //   它的像素与盘**同色**（ΔE 0~19 < 25），换尺之后 dep 归零，正是我们要的。
    val same = de < SEAM_DIVERGE
    val c = if (byTop && same) s.fTop[cell] else b
    // ⚠ **这一项是"超出量"，不是"按比例缩小"** —— 写成 `a * (255 - c) / 255` 是**错的**，
    //   哪怕它看着更像教科书里的 `a×(1−β)`。2026-09-30 有人正是这么"修"过一次，
    //   判据台当场把 `abfa161` 修掉的东西还了回来：翻译那张的斜痕（1px 浅蓝描边，
    //   与盘 ΔE≈12）在缩略图里整条重新出现，`淡` 260→504、`字均` 238→234。
    //   差别只在 `0 < a < c` 的那一段：描边的抗锯齿像素 α 本来就**比参照自己实出来的
    //   那份还低**，它没有"多出来的 α"，按比例那一版却给它留下 `a×(1−c/255)` 的墨 ——
    //   斜痕就是这些像素连成的。所以这一项问的是**「比参照多出多少 α」**：
    //   `c` 就是那把尺（默认 β；出图那一步在"同一种料"时换成 [Scratch.fTop]），
    //   `c ≥ b` 恒成立（fTop 是这一格 ≥A_SOLID 的最大 α，而 β 是均值），所以 dep ≤ a 恒成立。
    //
    //   ⚠ 三处公式说明（类文档 / [build] / 本函数 KDoc）必须与这一行**逐字对上**，
    //     它们原先写的是 `a × [(1−β) + β×ΔE/EMAX]` —— 那正是这个陷阱的来源。
    return if (withCoverage) {
      (if (a > c) a - c else 0) + a * b * cf / 65025
    } else {
      a * b * cf / 65025
    }
  }

  /** ΔE 到多少算"完全不像参照"。超过就按这个数截断，再往上的差别交给标定。 */
  private const val EMAX = 100

  /** dep 单位上的三个夹持值。dep 对 ΔE 是 ×2.55 的线性（β=1 时），所以这些数就是
   *  [TOL_MIN]、[TOL_MAX] 换算过来的，不是新的一条线；[DEP_MIN_SPAN] 是除数下限。
   *
   *  ⚠ [DEP_TOL_MAX] 在满幅插画上**会顶格**（判据台 113 张真图标里 17 张 `零>60`、其中 10 张
   *   正好 115）—— 看着像"容差标定炸了"，把上限压到 60 试过：**两头都更差**
   *   （`字均净` 214→207 / 真机群 197→191，`淡(<200)` 40→47 与 29→32，残盘暴涨 12/4 行，
   *   cc 好坏各半）。顶格不是 bug 的成因，是插画本来就没有"一种颜色的底"。别再调这两个数。
   */
  private const val DEP_TOL_MIN = 15
  private const val DEP_TOL_MAX = 115
  private const val DEP_MIN_SPAN = 16

  /** 第二遍最多把浓度降下来多少（百分比）。100 = 完全透明 = 那块东西整个不算图形，
   *  留 12% 是给"其实是个很淡的高光"那种留的余地。
   *
   * ⚠ **试过"被刻的那一块占框 >10% 就整条不刻"，2026-09-29 撤掉，别再试第二次。**
   *   动机是真的：真机 190 张里 **34 张正被这一刀压着**，最淡的一批全在里头
   *   （astralparty `字均净=153`、腾讯 sgame `174`、微博 `178`、驾考宝典 `181`），
   *   混元与通讯录 logo 更是 `降=0.25 / 0.19` —— 四分之一张画面被当成"洞"。
   *   数也全绿：语料 `淡(<200) 39→34`、`字均净` 升 10 降 0、`cc` 好 8 坏 0、`残盘` 净 0、
   *   SelfTest 48/48（钉着的「透明底+围住的镂空」照样绿，它占框很小）。
   *   **图否掉了它**（`_harness/shot_old|new`，面板 2 越黑=越多墨）：
   *   通讯录那张快捷设置瓦片，改前是"蓝卡成墨、白人是洞"（反相），
   *   改后是"卡和人都是墨"（**整块实心，内部结构全丢**）—— 一刀下去换了个错法，
   *   而 `空洞 0.18→0.00`、`cc 2→1`、`字均净 190→225` 三个数都说"变好了"。
   *   病根不在这条刀上：这些图的 `H=3`（盘子被当成墨）才是，见 [LUM_SWALLOW] 那一段与
   *   `MonochromeSynth` 的"居中盘"那一类。**这一族要修，得修归属，不是修这一刀。**
   */
  private const val HOLLOW = 88

  /**
   * "主体自己那一色不许被当成高光压下去"（[build] 第二遍里那道闸）算不算"同一色"的容差，
   * 单位 ΔE76 真值。**原来这道闸是按精确 RGB 比的**，Google 翻译
   * （`com.google.android.apps.translate`）把它否掉了：
   * 那只白色的 "G" 是 `#F5F5F5`，而右半边的白是 `#EEEEEE`/`#EFEFEF` —— **ΔE 只有 2.5**，
   * 肉眼分不出的两个白，于是同一张图上拿到**相反**的处理：右半边交 255，G 被压到 **36/255**，
   * 品牌字标在屏幕上几乎看不见（`_harness/LineDbg --row=192`：G 与右半边的源 L 都是 94~96、
   * dep 都是 178~181，只有交付差七倍 —— 差别全在这道"是不是主体那一色"上）。
   *
   * 6 这个数不是新调的旋钮：它就是 [TOL_MIN] 那条"肉眼分不出"的量级，比它更小的差别本来
   * 就不该在遮罩上分出两种结果。**不再往上放**：钉着的那条「镂空刻出来了」里洞是**另一种颜色**
   * （差得远的色，不是差 2.5 的白），容差宽到看得出的差别，就会开始保护真该刻的洞。
   *
   * ⚠ **这一条没有合成性质，别再试**：造过两种白嵌在一起、两种白隔开两张 fixture，
   *   在改前的代码上都照样全绿（第二遍那道刀要 `off > body.zero` 且 `hollow > 0`，
   *   合成图凑不出那两个条件）。验收是真图标那一行 A/B ——
   *   `com.google.android.apps.translate` 的 `分档 3/5(0,28,183,218,237)` → `(0,181,183,218,239)`。
   */
  private const val MODAL_INK_DE = 6

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
   * ⚠ **同一道理的反面也踩过一次（2026-09-28，改动已退）**：想在交付端拿"底的全局平均色"
   *   把与底同色的残盘归零，判据台当场红 4 条 —— `对角渐变底白方块` / `四块四档` 里的白方块
   *   被整个擦成 0.0。渐变盘的**平均**色接近白，而白方块相对**自己那一格**差得很远。
   *   所以任何"这一格是不是盘的颜色"的问法，都必须问它**背后那一格**（或最近的一格全覆盖），
   *   不能问全局均值 —— 那等于把这里删掉的"按格子取"从另一头请回来。
   *
   * ⚠ **但换成局部参照再问，同一条路还是死的（2026-09-28 第二次试，也已退）。** 把尺换成
   *   "最近的一格全覆盖"（`fCov≥250`，逐像素一次查表 + 一次 ΔE，成本可忽略）之后仍然红 2 条：
   *   `四块都上了墨` 从 `201 221 255 127` 掉成 **`201 221 0 0`**，`最远那段是实心` 掉成 **远=0.0**。
   *   这一条不是精度问题，是**契约问题**：图形完全可以就是盘的那个颜色，而屏幕上盘会被丢掉 ——
   *   对那样一块图形来说，"上墨"是它**唯一的存在方式**。所以「与底同色 ⇒ 不是墨」这个前提
   *   本身不成立，两种参照选法都只是把擦掉的东西从 4 块减到 2 块。
   *   **残盘要治，得到参照那一侧去治**：让跨在"盘↔空气"之间的那一格别把自己的参照稀释成
   *   半空气（实测 β均 178 而不是 255，反事实见 `_harness/RimDbg --res`：盘外那一圈有 25.9%
   *   会直接掉进容差、小步点 76.3%），洪泛自然就走到了 —— 而不是在出图的时候按颜色事后抹。
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
  /**
   * 第一轮能不能过，只看"离这一遍**指定的**那几族色够近"。
   *
   * ⚠ 家族数必须由参数给，不许读 [Scratch.fams]：那是上一个假设留下的。H3（"参照=没有东西"）
   *   以前恰好没事 —— 走到它面前时 `s.fams` 总是 0；一旦 H2 先跑过（轮廓族=1），H3 就会拿着
   *   **H2 那个轮廓色**去洪泛，退路不再是原来的答案。这就是"H2 跑一次就脏一次"的那一处。
   */
  private fun passFromFamilies(
    pixels: IntArray, w: Int, box: Rect, s: Scratch, tol: Int, fams: Int,
  ) {
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (Color.alpha(pixels[i]) < A_SOLID) { s.pass[i] = 1; continue }
        val l = s.labL[i].toInt(); val a = s.labA[i].toInt(); val b = s.labB[i].toInt()
        var ok = false
        for (c in 0 until fams) {
          if (deltaE(l, a, b, s.famL[c], s.famA[c], s.famB[c]) <= tol) { ok = true; break }
        }
        s.pass[i] = if (ok) 1 else 0
      }
    }
  }

  /** 这一格的参照色离**每一个**家族色都 ≥ [SEAM_DIVERGE] → 它不是"底"，是另一种料。
   *
   *  为什么需要这一条：`pass` 是拿像素比**它自己那一格的中位色**（局部参照，为了吃渐变）。
   *  一块**够大**的彩色区域会占满整个格子 → 那格的中位色就是它自己 → `d≈0` → 它成了"局部底"、
   *  一点墨都不上。用户点名的 `com.jingdong.app.mall`（白购物袋 + 彩色渐变图案）就是这样：
   *  左侧那块青 `#5BDFFF` 占满了 cell 32，而家族色是近白 `#FCF0FD`，两者 ΔE≈40 —— 于是青色
   *  **整块从掩码里消失**（`框内=0.10`，屏幕上"缺一块"），而同一张图上黄/粉因为没占满格子
   *  正常成了墨。**同一张图里两种彩色的待遇不一样**，这正是它难看的来源。
   *
   *  ⚠ 门槛用已有的 [SEAM_DIVERGE]（"同一种料"那条线），不新造数。`fams == 0`（H3 参照=空气）
   *  时整条不参与 —— 那时没有"家族"可比，硬套会把整张图判成墨。
   */
  private fun cellFar(s: Scratch, k: Int, fams: Int, cells: Int): Boolean {
    if (fams <= 0 || k < 0 || k >= cells) return false
    for (c in 0 until fams) {
      if (deltaE(s.fL[k], s.fA[k], s.fB[k], s.famL[c], s.famA[c], s.famB[c]) < SEAM_DIVERGE shl 6) return false
    }
    return true
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
  /**
   * 底的这一格要不要留墨：**只有贴着"墨"的那一圈才算数**，贴着**空气**的那一圈不算。
   *
   * ⚠ "非底"不等于"墨"。旧写法拿 `plate==0` 当墨的代理，于是底盘自己那圈抗锯齿（内侧是底、
   * 外侧是空气）被判成"贴着墨"，照样出 `α×(1−β)` —— 屏幕上是一圈看得见的淡边，而它在墨侧的
   * p85 锚点里占着一大堆，把字形永远压在 255 以下（「偏淡」的上游）。判据台那条
   * 「板子那圈抗锯齿交出 0 墨」量的就是它（`边上=32.3`，858 像素）。
   * 两次改"归属"都打不中（单像素判据、从底出发的传递闭包），因为病在**这一条出图规则**上。
   */
  private fun touchesInk(
    plate: ByteArray, pixels: IntArray, w: Int, box: Rect, x: Int, y: Int,
  ): Boolean {
    fun ink(nx: Int, ny: Int): Boolean =
      nx >= box.left && nx < box.right && ny >= box.top && ny < box.bottom &&
        plate[ny * w + nx] == 0.toByte() && Color.alpha(pixels[ny * w + nx]) >= A_SOLID
    return ink(x - 1, y) || ink(x + 1, y) || ink(x, y - 1) || ink(x, y + 1)
  }

  /**
   * **交付侧的"这一格是不是盘自己的一圈边"**：一个非底的像素，**3×3 邻域里至少 5 个是底**。
   *
   * 2026-09-28，用户点名的 `com.google.android.apps.translate` 那条**斜涂抹痕**就是这一条。
   * 它的源是一圈 **1px 的浅蓝描边**（`_harness/InkWhere.kt` 逐行量到 `n=1`、源 α 全是 255、
   * 颜色 `#6C9FF6` 而盘是 `#4F8DF4`，ΔE≈12），沿蓝盘的边界从左上斜贯到右下。
   * 这一圈描边在栅格上是**阶梯**的，局部会连成 2×2 的小团，所以：
   *  · "四邻全是底"只清掉 22/427；
   *  · "某一对相反紧邻都是底"清掉 274/427，剩下的全是阶梯拐角（实测 (179,98) 四邻只有 2 个底）；
   *  · 数 **3×3** 才把它整条收掉（实测该区域 `墨>3` 归零）。
   *
   * ⚠ **只认细，不认颜色、不认贴谁** —— 一旦掺进"颜色离盘多近"，就等于再调一次容差，
   *   而容差那一路（`pass` → `flood` → `plate`）会反馈：2026-09-28 试过把 [seamAcross] 改成
   *   "两侧同格时退到端点像素色"，翻译当场 `框内 0.02→0.55`、整块蓝盘被亮度轴上成墨。
   *   这里只决定"这个数不写出去"，`plate` / `fCov` / `β` / `q` / `tol` 一个都不碰。
   *
   * ⚠ **阈值 5 不是随手取的**：判据台 `SelfTest` 第 9 条钉着"均匀底上 2px 细线必须留下"，
   *   竖线上每一格的 3×3 里固定有 6 格是线自己 → 只有 3 个底邻，`≥5` 一格都不会误伤；
   *   而 2px 是判据台认的下限，1px 在 108dp 的画布上是 0.28dp，屏幕上本来就分辨不出来。
   *   取 4 会开始啃 2px 斜线的两端（斜线的 3×3 里线自己只有 4~5 格）。
   */
  /** 参照判出来的底有没有一直顶到画布四条边（= 整张画布就是一张盘）。 */
  private fun plateTouchesBorder(pixels: IntArray, w: Int, h: Int, s: Scratch): Boolean {
    fun on(i: Int) = s.plate[i] != 0.toByte() && Color.alpha(pixels[i]) >= A_SOLID
    for (x in 0 until w) if (on(x) || on((h - 1) * w + x)) return true
    for (y in 0 until h) if (on(y * w) || on(y * w + w - 1)) return true
    return false
  }

  private fun thinInk(plate: ByteArray, w: Int, box: Rect, x: Int, y: Int): Boolean {
    if (x <= box.left || x >= box.right - 1 || y <= box.top || y >= box.bottom - 1) return false
    val i = y * w + x
    var n = 0
    for (dy in -1..1) {
      val row = i + dy * w
      for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        if (plate[row + dx] != 0.toByte()) n++
      }
    }
    return n >= 5
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
    s.fCov.fill(0, 0, cells)
    s.fieldHist.fill(0, 0, cells * 1024)
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
        // 覆盖率也进直方图（第 4 个通道）：β 取**中位数**，理由与参照色取中位数是同一条 ——
        // 见下面 [fCov] 那段。均值不许再用，一格里的少数抗锯齿会把整格的参照拖成"半不在"。
        s.fieldHist[k * 1024 + 768 + a]++
        // 透明像素的 RGB 是垃圾（非预乘、未定义），不参与定色；它算参照，但不算「参照是什么颜色」。
        if (a < A_SOLID) continue
        val at = k * 1024
        s.fieldHist[at + ((p shr 16) and 0xff)]++
        s.fieldHist[at + 256 + ((p shr 8) and 0xff)]++
        s.fieldHist[at + 512 + (p and 0xff)]++
        s.cellN[k]++
      }
    }
    for (k in 0 until cells) {
      val n = s.cellN[k]
      if (n == 0) continue
      // ⚠ **三个通道各取各的中位，半底半墨的格子里这个"参照"是图上不存在的颜色。**
      //   L / a / b 的中位各自可能落在双峰之间，拼出来的那个色谁都不是。后果不在这一格，
      //   在**整张图的标定**：底像素离自己的假参照很远 → `零`（底散度的 p97）被抬起 → 而 `零`
      //   是从每个像素头上扣的。智慧树（`com.able.wisdomtree`）沿 y=345 逐格量到：橙条的源 L
      //   全是 **59**，各格参照却报 **69~81** —— 底先量到"参照不是我的颜色"，再量自己的噪声。
      //   于是 `零=115`（顶到 `DEP_TOL_MAX`）、跨度只剩 140，橙条上那行白字 dep=168 只映射到
      //   **105/255**。主人报的「线条不全」不是被删，是**被压成淡灰**。
      //   ⚠ 这一条 2026-10-01 **直接修过，撤了**：拿初版中位当种子、只留 ΔE≤`SEAM_DIVERGE` 的
      //   像素再取一次中位（众数搜索一步，门槛复用"同一种料"那条线，不新造数）。
      //   `零` 确实塌到 15、`残盘` 一格不涨 —— **但 `残盘` 的定义里就写着 `零`，它护不住这件事**
      //   （零点一降它机械地归零，是一把自证的尺子）。真正的账在别处：
      //   **113 张里 108 张动了**、`淡` 净 +7.7（60 涨 / 14 跌）、`字均净` 净 **−230**，
      //   最狠一张 229→67、一张 webapk 244→52。原因：**渐变盘的"宽"是真宽，不是双峰**，
      //   同一次收缩把真散度也收掉了，零点掉到地板，凡是离盘有一丁点距离的全成了墨。
      //   → **以后要动这一层，判据必须先过这一关：修的是"中位落在两个峰之间"，
      //   不是"把格子收窄"。** 只碰真双峰的格（比如要求第二趟剩下来的那批**自己也是双峰**、
      //   或者要求两峰之间的谷够深），并且 `零` 不许跌破"未动的那些格"的量出来的散度。
      //   这不是"再找一个阈值"——那两版量死的是**动阈值**，这一条死在**动了不该动的格**。
      s.cellR[k] = median(s.fieldHist, k * 1024, n).toShort()
      s.cellG[k] = median(s.fieldHist, k * 1024 + 256, n).toShort()
      s.cellB[k] = median(s.fieldHist, k * 1024 + 512, n).toShort()
    }
    fillEmptyCells(g, s)
    // 覆盖率：这一格的参照**平均**有多实（α 的均值）。一个参照像素都没有的格 = 0。
    //
    // ⚠ **中位数对"盘沿那一圈"根本不起作用，这是第二条独立的理由（2026-09-28 又试一次）。**
    //   下面那段讲的是"少数抗锯齿把整格拖成半不在"，那种格子里**多数**像素是实的，取中位数
    //   就回到 255。可中国移动盘沿那几格不是：那里**多数**像素真的就是空气（β=27 是事实，
    //   不是被少数像素拖累的假象），所以均值与中位数都得低 β —— 量完盘沿照旧 396+299 格出墨
    //   255，还多红一条性质。**β 不可能从这一格自己的像素里求出来**：要修痕迹，尺必须在
    //   格子外面（相邻的实心格 / 从空气出发的拓扑），不是换一个统计量。
    //
    // ⚠ **均值是绕着它标定过的，中位数试过、量着否掉了** —— 别再看错一遍：
    //  板子边缘那圈抗锯齿（α 1..254）确实会把所在格的 β 拉到 170 左右，于是"与参照色完全相同
    //  的板子像素"每人白拿几十单位墨 —— 华为应用市场量出来 8824 个红像素 ΔE 只有 0.6~1.0，
    //  交出 97~102/255，屏幕上就是那圈灰边 + 四个角斑，判据台上新增一条 **GAP** 钉着它。
    //  改成中位数，那些全部归零、层次也拉开了（12123 六块从 62/117/123/131/185/250 变成
    //  49/91/160/198/225/231 一条干净的梯）。**但同一刀把 H2 的验收换了口径**：哔哩哔哩那种
    //  透明底线条字，原本 H2 洪泛吃掉 ≥0.92 被 [PLATE_ALL_SHARE] 挡回 H3（参照=没有东西 →
    //  dep 退成覆盖率 → 整个字形是墨，这才是对的），中位数让洪泛更容易穿，底占 0.00→0.74，
    //  H2 被接受 → 电视机的身体被当成底腾空，框内 0.50→0.24，就是他报过并回退过的「只剩线条」
    //  （拼多多同类 0.52→0.18）。一句话：[PLATE_ALL_SHARE] 那个数是**绕着均值长出来的**，
    //  β 一动它就换了含义。要修 β，先把 H2 的验收换成不依赖 β 的判据（候选：底里有多大比例
    //  的像素其实"贴着边"—— 线条字的所谓底整片都是边，真底板大部分是内部），别再拿阈值追。
    // @param solidRef 参照是"图形自己"（第二遍）时为真：图形在不在，与它透不透明无关 ——
    //   β 回答的是「**参照**还在不在」，而图形自己的淡是它自己的浓度，已经在工作集的 dep 里
    //   乘过一遍 a 了。第二遍若按 alpha 量 β，外壳正好落在柔光的斜坡上（β≈190），
    //   核那种实心像素就会被算成"离主体很远"而整块降下来（判据台上 白底柔光+实心核 就是这样坏的）。
    for (k in 0 until cells) {
      val tot = s.cellTot[k]
      s.fCov[k] = when {
        tot == 0 -> 0
        solidRef -> 255
        else -> {
          var sum = 0L
          for (v in 0..255) sum += v.toLong() * s.fieldHist[k * 1024 + 768 + v]
          (sum / tot).toInt().coerceIn(0, 255)
        }
      }
      // 「这一格有没有实参照」：取这一格参照像素里最实的那个 α。没有参照像素的格子 = 0
      // （那是"根本没量到参照"，不是"参照是实心的一块"，与 [fCov] 同一条口径）。
      //
      // ⚠ **只认 α ≥ [A_SOLID] 的那一段。** 不设这道门会连带把"空气 + 抗锯齿"的格子也算成
      //   有实参照：哔哩哔哩 / Google logo / 美团那些格子里最高 α 是 104~127 —— 那正是**抗锯齿边**
      //   自己的 α，不是"这一格有一块实心参照"。带上它们，判据台上 Google logo `字均净` 231→189、
      //   哔哩哔哩 233→213，两处都是白挨的。有实参照的格子（≥128）才是我们要问的那一种。
      s.fTop[k] = when {
        tot == 0 -> 0
        solidRef -> 255
        else -> {
          var top = 0
          for (v in 255 downTo A_SOLID) if (s.fieldHist[k * 1024 + 768 + v] > 0) { top = v; break }
          top
        }
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
