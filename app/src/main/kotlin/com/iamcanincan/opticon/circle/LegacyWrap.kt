package com.iamcanincan.opticon.circle

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 把**非自适应（老式）图标**做成自适应图标时，需要从原图上量的三件事：
 * **缩到多大、形状是不是已经等于遮罩、底盘用什么颜色**。
 *
 * ## 倍率是照平台自己那套算的，不是拍出来的
 * Lawndesk / Launcher3 对老式图标做的是：`FixedScaleDrawable` 把原图按
 * `LEGACY_ICON_SCALE = .7f * .6667f` 画进自适应图标的**图层**里（图层 = 可见区的 1.5 倍），
 * 再乘一个 `IconNormalizer.getScale()` 算出来的归一倍率。两者都乘完，图案在圆里只占
 * `0.7 × 归一` 的直径 —— 真机上就是「圆里一个小图标」。本模块原先不做这一步，而是把图案
 * 放大到填满圆（2026-11-13 主人：「图标裁圆应该去掉，转为将非自适应图标优化为自适应图标」）。
 *
 * 归一那一半不是"再缩一刀"，是**光学尺寸对齐**：图案铺满整张画布的图标按面积会显得比
 * 只占中间一块 logo 的大一圈，平台用 `sqrt(设计面积 / 实际面积)` 把这批收回来
 * （满幅方图 0.807、近圆盘 0.91、稀疏 logo 1.0）。照搬它的常数与凸包顶点，才能和系统里
 * 其它非自适应图标长得一样。
 *
 * ## 为什么这一份是纯函数
 * 和 [IconCropWindow] 同一个理由：判据台（`_harness/`）编不动 Android 那两个类，只有纯函数
 * 才能被台子拿去对表；真机上"缩了没有、缩了多少"也才有离线可复算的一份。
 */
object LegacyWrap {

  /**
   * 平台 `FixedScaleDrawable.LEGACY_ICON_SCALE`。这是相对**图层 bounds** 的倍率 ——
   * 而 `AdaptiveIconDrawable` 交给前景层的 bounds 正是可见区的 `1 + 2 × extraInset` 倍，
   * 所以直接把它用到前景层上，屏幕上的直径就是可见圆的 `0.7 × 归一`。
   */
  const val SCALE_BASE = 0.7f * 0.6667f

  /** 平台 `IconNormalizer` 的设计面积：方形图 / 圆形图占整张画布的比例。 */
  private const val MAX_SQUARE_AREA_FACTOR = 375f / 576f
  private const val MAX_CIRCLE_AREA_FACTOR = 380f / 576f
  private const val CIRCLE_AREA_BY_RECT = (Math.PI / 4).toFloat()
  private const val LINEAR_SLOPE =
    (MAX_CIRCLE_AREA_FACTOR - MAX_SQUARE_AREA_FACTOR) / (1 - CIRCLE_AREA_BY_RECT)

  /** 与平台 `IconNormalizer.MIN_VISIBLE_ALPHA` 同一个值：半透明边缘不算"看得见的图"。 */
  private const val MIN_VISIBLE_ALPHA = 40

  /** 形状判定的两个门槛，同样来自平台（`isShape`）。 */
  private const val BOUND_RATIO_MARGIN = 0.05f
  private const val PIXEL_DIFF_PERCENTAGE_THRESHOLD = 0.005f

  /** 底盘取色的边带宽度（占画布短边的比例）。 */
  private const val RING_FRACTION = 0.04f

  /**
   * 量一张图。
   *
   * @param px ARGB，`w × h`，行优先。
   * @return null = 这张图一格可见像素都没有（透明 / 画不出来），调用方**不要包**。
   */
  fun measure(px: IntArray, w: Int, h: Int): Metrics? {
    if (w <= 0 || h <= 0 || px.size < w * h) return null
    val left = IntArray(h) { -1 }
    val right = IntArray(h) { -1 }
    var top = -1
    var bottom = -1
    var leftX = w
    var rightX = -1
    for (y in 0 until h) {
      var first = -1
      var last = -1
      for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) <= MIN_VISIBLE_ALPHA) continue
        if (first < 0) first = x
        last = x
      }
      left[y] = first
      right[y] = last
      if (first >= 0) {
        if (top < 0) top = y
        bottom = y
        if (first < leftX) leftX = first
        if (last > rightX) rightX = last
      }
    }
    if (top < 0 || rightX < 0) return null

    // 凸包：平台拿左右边界逐行推成外凸的两条折线，面积按它算（凹进去的缺口不算面积）。
    val l = FloatArray(h) { left[it].toFloat() }
    val r = FloatArray(h) { right[it].toFloat() }
    convex(l, 1, top, bottom)
    convex(r, -1, top, bottom)

    var area = 0f
    for (y in 0 until h) {
      if (l[y] <= -1f) continue
      area += r[y] - l[y] + 1f
    }
    val rectArea = (bottom + 1 - top).toFloat() * (rightX + 1 - leftX).toFloat()
    if (rectArea <= 0f) return null
    val hullByRect = area / rectArea
    val scaleRequired = if (hullByRect < CIRCLE_AREA_BY_RECT) {
      MAX_CIRCLE_AREA_FACTOR
    } else {
      MAX_SQUARE_AREA_FACTOR + LINEAR_SLOPE * (1f - hullByRect)
    }
    val areaScale = area / (w * h).toFloat()
    val scale = if (areaScale > scaleRequired) sqrt(scaleRequired / areaScale) else 1f

    return Metrics(
      scale = scale,
      maskShaped = isMaskShaped(px, w, leftX, top, rightX, bottom),
      plate = plateColor(px, w, h, leftX, top, rightX, bottom),
      aspect = w.toFloat() / h.toFloat(),
    )
  }

  /**
   * 形状是不是已经等于（圆形）遮罩 —— 平台的 `IconNormalizer.isShape()`。
   *
   * 相等时**不包**：那一类老式图标自带一个完整的圆，平台画出来已经是对的；再包一层只会
   * 把它缩到 64%（平台的 `0.7 × 0.917`）并多出一圈底盘。
   */
  private fun isMaskShaped(
    px: IntArray, w: Int, left: Int, top: Int, right: Int, bottom: Int,
  ): Boolean {
    val bw = right + 1 - left
    val bh = bottom + 1 - top
    if (bh <= 0 || abs(bw.toFloat() / bh - 1f) > BOUND_RATIO_MARGIN) return false
    val cx = (left + right + 1) / 2.0
    val cy = (top + bottom + 1) / 2.0
    val rx = bw / 2.0
    val ry = bh / 2.0
    var diff = 0
    var total = 0
    for (y in top..bottom) {
      for (x in left..right) {
        total++
        val inside = ((x + 0.5 - cx) / rx) * ((x + 0.5 - cx) / rx) +
          ((y + 0.5 - cy) / ry) * ((y + 0.5 - cy) / ry) <= 1.0
        val visible = (px[y * w + x] ushr 24) > MIN_VISIBLE_ALPHA
        if (inside != visible) diff++
      }
    }
    return total > 0 && diff.toDouble() / total < PIXEL_DIFF_PERCENTAGE_THRESHOLD
  }

  /**
   * 底盘色 = **图标自己的边**那一圈最常见的实色。
   *
   * 为什么不用平台的 `TaskDescription.getPrimaryColor()`：那是**活动主题的**颜色，取它要把
   * 目标包的主题挂起来解析一遍（换包、换活动都变），成本与失败面都大。用图标自己的边色有两个
   * 好处：铺满画布的那一类（绝大多数老式图标）底盘与它自己的底**同色**，缩下去不会出现一圈
   * 白边；没有实色边的（透明底 logo）退到白，与平台缺省一致。
   *
   * 取色顺序：整张画布的边带（4%）→ 没有实色格就退到**图案外框**的边带 → 再没有就白。
   */
  private fun plateColor(
    px: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int,
  ): Int {
    ringColor(px, w, h, 0, 0, w - 1, h - 1)?.let { return it }
    ringColor(px, w, h, left, top, right, bottom)?.let { return it }
    return 0xFFFFFFFF.toInt()
  }

  /** 边带里出现最多的实色（4 位量化后取均值），没有实色格返回 null。 */
  private fun ringColor(
    px: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int,
  ): Int? {
    val band = maxOf(1, (min(right - left + 1, bottom - top + 1) * RING_FRACTION).toInt())
    val counts = HashMap<Int, Int>()
    val sums = HashMap<Int, LongArray>()
    var opaque = 0
    var samples = 0
    for (y in top..bottom) {
      val edgeRow = y < top + band || y > bottom - band
      for (x in left..right) {
        if (!edgeRow && x >= left + band && x <= right - band) continue
        samples++
        val p = px[y * w + x]
        if ((p ushr 24) < 200) continue
        opaque++
        val key = ((p shr 16 and 0xF0) shl 12) or ((p shr 8 and 0xF0) shl 4) or (p and 0xF0)
        counts[key] = (counts[key] ?: 0) + 1
        val s = sums.getOrPut(key) { LongArray(3) }
        s[0] += p shr 16 and 0xFF
        s[1] += p shr 8 and 0xFF
        s[2] += p and 0xFF
      }
    }
    // 边带里大半是透明的 ⇒ 这不是"这张图自己的底"，交给下一圈或白。
    if (samples == 0 || opaque * 2 < samples) return null
    val best = counts.maxByOrNull { it.value } ?: return null
    val s = sums[best.key] ?: return null
    val n = best.value.toDouble()
    val r = (s[0] / n).toInt().coerceIn(0, 255)
    val g = (s[1] / n).toInt().coerceIn(0, 255)
    val b = (s[2] / n).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }

  /**
   * 平台 `IconNormalizer.convertToConvexArray` 的逐行移植：把左右边界推成外凸折线。
   * `direction` = 1 左边界、-1 右边界。缺行的值就地补上。
   */
  private fun convex(xs: FloatArray, direction: Int, top: Int, bottom: Int) {
    val angles = FloatArray(xs.size)
    val first = top
    var last = -1
    var lastAngle = Float.MAX_VALUE
    for (i in top + 1..bottom) {
      if (xs[i] <= -1f) continue
      var start: Int
      if (lastAngle == Float.MAX_VALUE) {
        start = first
      } else {
        var current = (xs[i] - xs[last]) / (i - last)
        start = last
        if ((current - lastAngle) * direction < 0) {
          while (start > first) {
            start--
            current = (xs[i] - xs[start]) / (i - start)
            if ((current - angles[start]) * direction >= 0) break
          }
        }
      }
      lastAngle = (xs[i] - xs[start]) / (i - start)
      for (j in start until i) {
        angles[j] = lastAngle
        xs[j] = xs[start] + lastAngle * (j - start)
      }
      last = i
    }
  }

  /**
   * @param scale 归一倍率（≤1），与 [SCALE_BASE] 相乘后才落到前景层上。
   * @param maskShaped 形状已经等于圆形遮罩 —— 不需要包。
   * @param plate 底盘 ARGB。
   * @param aspect 原图宽 / 高（前景层是方的，缩放时要按它还原比例）。
   */
  data class Metrics(
    val scale: Float,
    val maskShaped: Boolean,
    val plate: Int,
    val aspect: Float,
  )
}
