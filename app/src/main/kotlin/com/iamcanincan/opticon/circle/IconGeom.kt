package com.iamcanincan.opticon.circle

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 老式图标的几何：**一份**算式，彩色与单色共用（主人 2026-11-15：「彩色不能给单色提供图标，再让单色改吗」）。
 *
 * 三步（主人 2026-11-14 原话定下的口径）：
 *  ① 把内容缩放到**恰好不被正方形裁掉任何一个有色像素**（[INSCRIBED_SHARE] 之前先做这一步）；
 *  ② 按填满之后**可见格占整张光栅的比例**分档：≥ π/4 走边角延伸，< π/4 要铺底；
 *  ③ 铺底那一支再缩到**内切圆不会裁掉任何一个有色像素**（留 [CIRCLE_FIT] 余量）。
 *
 * 为什么要抽成一份：单色层必须落在与彩色同一块几何上，否则主题图标与彩色图标一大一小
 * （主人 2026-11-14：「单色图标和彩色图标不符」）。原先单色那边把这三段**重抄了一遍**，
 * 两份各自数格 —— 抄本用的分档口径还与彩色不同（按内容外接框归一 vs 按整张光栅归一），
 * 非方形内容的图标两边会各给一个答案。这就是本仓点过多次名的"一个模块两套主张"。
 *
 * 现在两边都调 [planOf]：彩色拿倍率**重画** drawable，单色拿同一个倍率**缩放**自己那张掩码。
 * 纯 `IntArray`、不吃 Android —— 所以判据台编得动这个文件（与 [IconCropWindow] 同一待遇）。
 */
internal object IconGeom {

  /**
   * 一次几何决定的结果，坐标与倍率都以**送进来的那张光栅（w×h）**为基准。
   *
   * @param scale 相对这张光栅的总倍率（1 = 不用动）
   * @param cx/cy 内容外接框中心（缩放原点，之后要对到光栅中心）
   * @param fill  填满正方形之后可见格占整张光栅的比例（②那把尺的读数）
   * @param radius ①之后有色格到内容中心的最大半径（②不成立时才有意义，否则 0）
   * @param plate true = 小占比那一支（彩色要铺底；单色只跟尺寸，底由宿主的主题色承担）
   * @param shrunk true = ③真的动了手（内切圆会裁到内容，所以按 [CIRCLE_FIT] 缩了）
   */
  class Plan(
    val scale: Float,
    val cx: Float,
    val cy: Float,
    val fill: Float,
    val radius: Float,
    val plate: Boolean,
    val shrunk: Boolean,
  ) {

    /** 缩放后图层要给的 bounds：`[left, top, right, bottom]`（内容中心对到光栅中心）。 */
    fun bounds(w: Int, h: Int): FloatArray {
      val left = w / 2f - cx * scale
      val top = h / 2f - cy * scale
      return floatArrayOf(left, top, left + w * scale, top + h * scale)
    }

    /** 倍率离 1 不到 1% 就别重画/重采样：两边共用同一句，别各写各的容差。 */
    val needsRedraw: Boolean get() = abs(scale - 1f) >= 0.01f
  }

  /** 全透明（一个有色格都没有）→ null，调用方按"不动几何"处理。
   *
   * @param forceExtend 调用方按**资源身份**认定这张是满幅圆盘（应用自己声明的 roundIcon
   *   老式位图）：它的可见占比天生比 π/4 低一圈抗锯齿，走铺底支会再缩一圈 + 铺白底 =
   *   给本来完美的圆套白圈（2026-11-18 真机 parsec 应用信息页）。强制走延伸支：
   *   角按它自己的边缘色补满，不缩、不铺底。单色侧经 [MonochromeSynth.forIcon] 跟同一个旗标，
   *   两边落在同一块几何上。
   */
  fun planOf(px: IntArray, w: Int, h: Int, forceExtend: Boolean = false): Plan? {
    var l = w
    var t = h
    var r = -1
    var b = -1
    var vis = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if ((px[row + x] ushr 24) <= ALPHA_MIN) continue
        vis++
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
      }
    }
    if (r < 0) return null
    val bw = (r - l + 1).toFloat()
    val bh = (b - t + 1).toFloat()
    val cx = (l + r + 1) / 2f
    val cy = (t + b + 1) / 2f

    // ① 填满正方形（已经贴边就当 1，不重画 —— 与旧写法同一个容差）
    val s1 = minOf(w / bw, h / bh)
    val fillScale = if (abs(s1 - 1f) < 0.01f) 1f else s1

    // ② 分档读的是**填满之后**占整张光栅的比例。彩色先前是真的重画一遍再数，
    //   这里是同一个数的解析式：等比缩放 s 倍，有色格按 s² 涨。
    val fill = vis * fillScale * fillScale / (w * h)

    // ③ 铺底那一支才要缩进内切圆：半径在未缩放的格子上量，所以 ①② 合成一步时
    //   总倍率里的 fillScale 正好约掉（s1 × need/(maxR·s1) = need/maxR）。
    if (forceExtend) return Plan(fillScale, cx, cy, fill, 0f, false, false)
    if (fill >= INSCRIBED_SHARE) return Plan(fillScale, cx, cy, fill, 0f, false, false)
    var maxR2 = 0f
    for (y in t..b) {
      val dy = y + 0.5f - cy
      val row = y * w
      for (x in l..r) {
        if ((px[row + x] ushr 24) <= ALPHA_MIN) continue
        val dx = x + 0.5f - cx
        val d2 = dx * dx + dy * dy
        if (d2 > maxR2) maxR2 = d2
      }
    }
    val maxR = sqrt(maxR2)
    val radius = maxR * fillScale
    val need = minOf(w, h) / 2f * CIRCLE_FIT
    val shrunk = radius > need
    val scale = if (shrunk) need / maxR else fillScale
    return Plan(scale, cx, cy, fill, radius, true, shrunk)
  }
}
