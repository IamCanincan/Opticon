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
    /** H0（[regionGround]）这一支走到哪一步了：0=没走到、1=接手、2=被画布门槛拦、3=被区域关系拦、
     *  4=被"底墨同色"那道验收拦。每次 [groundPass] 开头归零。
     *  带它是因为**换桌面时这一支的射程必须看得见**，见那条汇总日志。 */
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
      }
      if (cellR.size < cells) {
        cellR = ShortArray(cells); cellG = ShortArray(cells); cellB = ShortArray(cells)
        cellN = IntArray(cells); cellTot = IntArray(cells)
        fL = IntArray(cells); fA = IntArray(cells); fB = IntArray(cells); fCov = IntArray(cells)
        fieldHist = IntArray(cells * 1024)
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
    val hollow = if (knockCand > keep) 0 else HOLLOW * body.weight / 255
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
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (s.plate[i] != 0.toByte() || Color.alpha(pixels[i]) != 255) continue
        val k = pixels[i] and 0x00FFFFFF
        val v = cDep.getOrPut(k) { LongArray(2) }
        val d = s.dist[i].toInt() and 0xffff
        if (d > v[0]) { v[0] = d.toLong(); v[1] = refLight(i, s).toLong() }
      }
    }
    val out = IntArray(total)
    var knocked = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        val v = if (s.plate[i] == 0.toByte() && Color.alpha(pixels[i]) == 255)
          cDep[pixels[i] and 0x00FFFFFF] else null
        val dep = if (v != null) v[0].toInt() else s.dist[i].toInt() and 0xffff
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
        var a = (td * (100 - q) + tl * q) / 100
        if (a == 0) continue
        // 比参照暗的往上、亮的往下，**按这张图自己的跨度成比例** —— 不是"暗/亮"两档。
        // ⚠ 上限不对称是有理由的：白字在彩色底上是最常见的"亮侧实心"，往下压狠了就是他报过的
        //   「主题色怎么这么浅」。q 上去之后这两侧自动退场：那时明暗已经是浓度本身了。
        a = (a + a * (u * 100 / amp).coerceIn(-down, up) / 100).coerceIn(0, 255)
        // 第二遍只降**比主体亮**的那一侧（镂空、高光、白 logo 上的洞）。比主体暗的那些是图形
        // 自己的暗部，降它等于把细节擦了 —— 与上面那条明度序同一个极性。
        if ((s.labL[i].toInt() and 0xffff) - bodyLight(i, s) > 0 && kc[i] != 2.toByte()) {
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

    // 4) 交出去之前把墨量抬到满阶（他要「图标上必须有系统最深和最浅的取色」）。**两层叠加**：
    //    全局那一层管"整张最深都不够"，字形那一层管"只有 1% 到顶、主体停在 140"。
    //    ⚠ 中间夹一步发档（4.5），两层不能并排 —— 理由写在那一段里。
    val global = stretchGlobal(out, total)
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
    assignLevels(out, pixels, w, box, s)
    val stretch = "${global?.second ?: "已满"}+${stretchMark(out, pixels, w, h, box, s)}"

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
    for (i in 0 until total) {
      if (Color.alpha(pixels[i]) < 255) continue
      val a = out[i] ushr 24
      if (a == 0) continue
      markN++; markSum += a; if (a >= 250) markTop++
      // dep 在这里就是判连通用的那一个数（fitGround 交付段之后，非底的格子没被改写过）。
      if (s.plate[i] == 0.toByte() && (s.dist[i].toInt() and 0xffff) <= ground.zero) { resN++; resSum += a }
      else { figN++; figSum += a; if (a >= 250) figTop++ }
    }
    val sep = separation(out, w, h, box, s)
    val st = measure(out, w, h, box, s, bridge = colourDriven, despeckle = colourDriven)
    val fill = st.kept.toFloat() / boxArea
    return Output(
      out, fill, st.hole, st.largest, st.components, st.smallShare, softShare(st, box),
      "带占=${s.bandSolid * 100 / 255} 底占=${pct(ground.share)} H=${ground.hyp} 族=${ground.families} 底厚=${ground.thick}" +
      " 同色=${ground.inkSame}/${s.vetoSame} 拦=${s.veto}" +
      // 实心两半各多少格：`盘实<墨实` 就是"这块底比它承载的东西还小"，见 [thinPlate]。
      // 判据台上量过一次就够、日志上要能当场看见 —— 抖音那一张退掉之前是 6294/9614。
      " 盘实=${ground.gN}/${ground.iN}" +
      " 盒布=${boxArea * 100 / (w.toLong() * h)} H0=${s.h0why}" +
      (if (ground.holed < 0) "" else " 洞墨=${ground.holed}") +
      (if (ground.h2 < 0f) "" else " H2被否=${pct(ground.h2)}/${ground.h2thick}/${ground.h2enc}/${ground.h2spread}") +
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
        (if (sep.third.isEmpty()) "" else "(${sep.third.joinToString(",")})"),
      ground.hyp,
      s.h0why,
      s.veto
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

  /** 最多看几个部分（按面积取前几），再多就不是"相邻有没有差别"而是数颜色了。 */
  private const val SEP_MAX_PARTS = 6

  /** 两档之间至少差这么多浓度才算看得出是两个部分（255 的 ~12%）。 */
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
   */
  private const val LEVEL_FLOOR = 200


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
    // 从最浓的那块往下发档：第一块满墨，其余"不超过上一块 − 间隔"，也不高于自己原来的位置。
    val order = pick.indices.sortedByDescending { mean[it] }
    val target = IntArray(pick.size) { mean[it] }
    var prev = 255
    for ((j, idx) in order.withIndex()) {
      // 地板：让开可以，但没让到"淡"。低于 [LEVEL_FLOOR] 就不再往下发档 —— 那几块宁可并列。
      // 这条是被点名的应用逼出来的：没有地板时中国移动 的实心占比从 76% 掉到 16%（均浓 229→168），
      // 而"偏淡"正是他先报的那一条。细腻与实心冲突时，**这里选的是先不伤人**。
      target[idx] = if (j == 0) 255
      else minOf(mean[idx], maxOf(prev - SEP_MIN_GAP, LEVEL_FLOOR)).coerceAtLeast(0)
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
   *  倍率是看图定的：再狠就把设计者自己画的明暗抹成一块实色（见 [stretchMark]）。 */
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
            s.pass[i] = if (a < A_SOLID || d <= tol || solid <= tol || byRim || rb[i] == 2.toByte()) 1 else 0
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
    var inn = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      for (x in box.left until box.right) {
        val i = row + x
        if (Color.alpha(pixels[i]) == 255) {
          if (s.plate[i] != 0.toByte()) {
            gl += s.labL[i].toInt() and 0xffff; ga += s.labA[i].toInt() and 0xffff
            gb += s.labB[i].toInt() and 0xffff; gn++
          } else inn++
        }
        if (s.plate[i] != 0.toByte()) {
          val b = s.beta[i].toInt() and 0xffff
          s.dist[i] = (if (touchesInk(s.plate, pixels, w, box, x, y))
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
    p.gL = if (gn > 0) (gl / gn).toInt() else 0
    p.gA = if (gn > 0) (ga / gn).toInt() else 0
    p.gB = if (gn > 0) (gb / gn).toInt() else 0
    p.gN = gn.toInt()
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
  ): Pass {
    val seed = s.mark      // 洪泛的种子掩码（measure 之前没人用这块）
    s.h0why = 0            // 这一张走到 H0 没有、被哪道拦下，逐张归零（见 [Scratch.h0why]）
    s.veto = 0; s.vetoSame = -1  // 同上：被"底墨同色"退掉过哪几支、被退那支自己的数（见 [Output.veto]）
    val fams = cornerFamilies(pixels, w, box, band, s)
    if (fams > 0) {
      bandSeed(s, w, box, band, seed)
      val p = fitGround(pixels, w, h, box, g, s, fams, seed)
      p.ring = fams
      p.hyp = 1
      p.thick = plateThickness(w, box, s)
      // 角上确实有一块色，**并且它和剩下的东西不是一个颜色** —— 后半句才是这一支的验收。
      if (!vacuous(s, p)) return p
      s.veto = s.veto or 1
    }
    val ring = outlineFamilies(pixels, w, box, s, seed)
    // H2 被否决时它的数也要能进日志 —— 用局部变量带，不碰对象上的字段（这是多线程的钩子路径）。
    var h2share = -1f
    var h2thick = -1
    var h2spread = -1
    var h2enc = -1
    if (ring > 0) {
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
      val vac = vacuous(s, alt)
      val thin = thinPlate(alt)
      if (alt.share < PLATE_ALL_SHARE && !vac && !thin) return alt
      if (vac) s.veto = s.veto or 2
      if (thin) s.veto = s.veto or 8
      h2share = alt.share; h2thick = alt.thick; h2enc = alt.enclosed; h2spread = alt.spread
    }
    // H1、H2 都没说出"哪块是底"之后，先问 H0（区域关系），再退到 H3（没有底）。
    // 位置很要紧：H0 抢在 H2 前面会撞翻判据台的「透明底+围住的镂空」那类用例，而它接手的活
    // 本来就只有"今天注定退成空气"的那几张（84 张真图标里走得到这里的只有 3 张）。
    regionGround(pixels, w, h, box, g, s, seed)?.let {
      it.h2 = h2share; it.h2thick = h2thick; it.h2enc = h2enc; it.h2spread = h2spread
      return it
    }
    bandSeed(s, w, box, band, seed)
    val air = fitGround(pixels, w, h, box, g, s, 0, seed)
    air.hyp = 3
    air.h2 = h2share; air.h2thick = h2thick; air.h2enc = h2enc; air.h2spread = h2spread
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
   * **两条候选被量掉，所以没写进来**（上一轮的报告把它们当成下一步，这里先量再说）：
   *  · 「区域要比它围住的更**实**」：合成镂空 区α=255 洞α=255、小步点 254/254 —— 相等，分不开。
   *  · 「围住的要比区域更**亮**」：合成镂空 L 74.3→100.0 与 小步点 78.9→95.9 **同向**，而该收的
   *    中国移动是 99.7→61.1（反向）、QQ 99.7→43.9（反向）。它既分不开那一对，还会把点名的应用推掉。
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
    if (best < 0 || bestArea * 100 < boxArea * REGION_MIN_PCT) return null
    var rim = false
    for (x in box.left until box.right) {
      if (rid[box.top * w + x] == best || rid[(box.bottom - 1) * w + x] == best) { rim = true; break }
    }
    if (!rim) for (y in box.top until box.bottom) {
      if (rid[y * w + box.left] == best || rid[y * w + box.right - 1] == best) { rim = true; break }
    }
    if (!rim) return null
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
    if (holeInk * 100 < boxArea * REGION_HOLE_PCT) return null
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
    if (vacuous(s, p)) { s.h0why = 4; s.veto = s.veto or 4; return null }
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
  private fun vacuous(s: Scratch, p: Pass): Boolean {
    // `inkSame < 0` = 有一侧是空的（整张都是底，或整张都不是），**量不出来**：不赞成也不推翻。
    // 这条不只是保守：`meanCov` 是在墨那一侧上量的，墨侧为空时它退化成 0 —— 没有下面那个
    // `inkSame >= 0` 的短路，「整张纯色」（正确答案是一张空掩码）会被误判成"底是空气"而退到
    // H3，整张涂成实心饼。2026-09-28 就是这么红了一次。
    if (p.inkSame < 0) return false
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

  /** H0 的三道区域门槛 + 那道画布门槛。分界都来自 [regionGround] 那段注释里的实测表，两侧差得远。 */
  private const val REGION_FULL_PCT = 80
  private const val REGION_MIN_PCT = 25
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

  /** H2 验收换候选：参照这块要**厚到框短边的 1/16** 才算一块底板（按短边归一，不写死像素 ——
   *  同一张图在 192 / 384 上必须给出同一个答案，判据台钉着尺寸不变性）。
   *  另一条候选是 [enclosedShare]：剩下的墨里要有大半**碰不到空气**，否则那块"底"只是并排的图形之一。
   *  两条都还没接线，原因见 [groundPass] 里那条验收的注释。 */
  private const val PLATE_THICK_DIV = 16
  private const val ENCLOSED_MIN = 50

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
