package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

private const val ROUND_ART_RENDER_EDGE = 256

internal class LegacyIconGeometry(
  val pixels: IntArray,
  val plan: IconGeom.Plan?,
  val bounds: RectF,
  private val width: Int,
  private val height: Int,
) {
  private val exterior by lazy(LazyThreadSafetyMode.NONE) {
    cleanRim(pixels, width, height)
    bleedEdgeColor(pixels, width, height)
    IconGeom.deFringe(pixels, width, height)
    IconGeom.outsideOf(pixels, width, height)
  }

  fun preparePlate() {
    cleanRim(pixels, width, height)
    bleedEdgeColor(pixels, width, height)
  }

  fun colorExtension(): IntArray = computeLegacyExtension(pixels, width, height, exterior)

  fun renderInk(ink: Bitmap, window: IconCropWindow.Window?, alreadyPlaced: Boolean): IntArray {
    val edge = ink.width.toFloat()
    val windowScale = modelWindowScale(edge, window)
    val windowX = window?.let { (it.l + it.r) * edge / 2f } ?: (edge / 2f)
    val windowY = window?.let { (it.t + it.b) * edge / 2f } ?: (edge / 2f)
    val sx = bounds.width() / edge
    val sy = bounds.height() / edge
    val transform = Matrix().apply {
      if (alreadyPlaced) {
        setScale(width / edge, height / edge)
      } else {
        setScale(sx / windowScale, sy / windowScale)
        postTranslate(
          bounds.left + (windowX - edge / (2f * windowScale)) * sx,
          bounds.top + (windowY - edge / (2f * windowScale)) * sy,
        )
      }
    }
    val mapped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val result = IntArray(width * height)
    try {
      Canvas(mapped).drawBitmap(ink, transform, Paint(Paint.FILTER_BITMAP_FLAG))
      mapped.getPixels(result, 0, width, 0, 0, width, height)
    } finally {
      mapped.recycle()
    }
    if (plan?.plate == false) {
      val outside = exterior
      val recovered = recoverFlatPlateInk(pixels, result, width, height)
      extendInk(result, outside, recovered)
    } else {
      recoverFlatPlateInk(pixels, result, width, height)
    }
    return result
  }

  fun rasterForInk(edge: Int): Bitmap {
    val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    if (width == edge && height == edge) return source
    return try {
      Bitmap.createScaledBitmap(source, edge, edge, true)
    } finally {
      source.recycle()
    }
  }

  private fun extendInk(ink: IntArray, outside: ByteArray, recovered: Boolean) {
    val fill = computeLegacyExtension(pixels, width, height, outside, ink, recovered)
    for (i in ink.indices) {
      if (outside[i] == 2.toByte()) {
        ink[i] = 0
      } else if (fill[i] != 0) {
        val coverage = pixels[i] ushr 24
        val edgeInk = fill[i] and 255
        val sourceInk = minOf(ink[i] ushr 24, coverage)
        val alpha = sourceInk + (edgeInk * (255 - coverage) + 127) / 255
        ink[i] = alpha shl 24
      }
    }
  }
}

internal fun modelWindowScale(edge: Float, window: IconCropWindow.Window?): Float =
  window?.let {
    maxOf(edge / ((it.r - it.l) * edge).coerceAtLeast(1f),
      edge / ((it.b - it.t) * edge).coerceAtLeast(1f))
  } ?: 1f

internal fun legacyRenderSize(size: Int, roundGrid: Boolean): Int =
  if (roundGrid && size <= ROUND_ART_RENDER_EDGE) ROUND_ART_RENDER_EDGE
  else if (size < 64) size
  else ((size + 15) / 16) * 16

internal fun adaptiveVisibleInset(size: Int): Int {
  val extra = android.graphics.drawable.AdaptiveIconDrawable.getExtraInsetFraction()
  return (size * extra / (1f + 2f * extra)).roundToInt()
}

/** Render the source into its visible slot before adding any background or edge extension. */
internal fun drawLegacyIconGeometry(
  icon: Drawable, bitmap: Bitmap, canvas: Canvas, forceExtend: Boolean,
): LegacyIconGeometry {
  val w = bitmap.width
  val h = bitmap.height
  val saved = Rect(icon.bounds)
  val px = IntArray(w * h)
  val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
  try {
    icon.setBounds(0, 0, w, h)
    icon.draw(canvas)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val faintPlate = !forceExtend &&
      (IconGeom.hasFaintPlate(px, w, h) || hasFaintBitmapBacking(icon))
    // Measure the disk after removing a recognized backing. Keep the cleaned source
    // for resampling because redrawing the drawable would reintroduce the backing.
    val strippedBacking = !faintPlate &&
      (IconGeom.stripPaleDiskRim(px, w, h) || IconGeom.stripBakedWhiteCorners(px, w, h) > 0)
    val plan = IconGeom.planOf(px, w, h, forceExtend || strippedBacking, faintPlate)
    if (plan != null && plan.needsRedraw) {
      val dest = plan.bounds(w, h)
      bounds.set(Math.round(dest[0]).toFloat(), Math.round(dest[1]).toFloat(),
        Math.round(dest[2]).toFloat(), Math.round(dest[3]).toFloat())
      if (strippedBacking) {
        val cleaned = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        try {
          bitmap.eraseColor(Color.TRANSPARENT)
          canvas.drawBitmap(cleaned, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
          cleaned.recycle()
        }
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
      } else {
        val before = bboxOf(px, w, h)
        bitmap.eraseColor(Color.TRANSPARENT)
        icon.setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        icon.draw(canvas)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val after = bboxOf(px, w, h)
        // Fixed-dp layers can ignore drawable bounds. Transform the canvas in that case.
        if (before != null && after != null && before.contentEquals(after)) {
          bounds.set(dest[0], dest[1], dest[2], dest[3])
          bitmap.eraseColor(Color.TRANSPARENT)
          val save = canvas.save()
          try {
            canvas.translate(bounds.left, bounds.top)
            canvas.scale(plan.scale, plan.scale)
            icon.setBounds(0, 0, w, h)
            icon.draw(canvas)
          } finally {
            canvas.restoreToCount(save)
          }
          bitmap.getPixels(px, 0, w, 0, 0, w, h)
        }
      }
    }
    if (faintPlate) IconGeom.clearFaintPlate(px)
    return LegacyIconGeometry(px, plan, bounds, w, h)
  } finally {
    icon.bounds = saved
  }
}

/** 有色格外接框 `[l,t,r,b]`（与 [IconGeom.planOf] 同一条 α 线）；全透明 = null。 */
private fun bboxOf(px: IntArray, w: Int, h: Int): IntArray? {
  var l = w
  var t = h
  var r = -1
  var b = -1
  for (y in 0 until h) {
    val row = y * w
    for (x in 0 until w) {
      if ((px[row + x] ushr 24) <= ALPHA_MIN) continue
      if (x < l) l = x
      if (x > r) r = x
      if (y < t) t = y
      if (y > b) b = y
    }
  }
  return if (r < 0) null else intArrayOf(l, t, r, b)
}

/** Resampling thin strokes can erase the opaque pixels used to confirm the backing. */
private fun hasFaintBitmapBacking(icon: Drawable): Boolean {
  val drawable = icon as? BitmapDrawable ?: return false
  val bitmap = drawable.bitmap ?: return false
  if (!bitmap.hasAlpha() || bitmap.width !in 16..512 || bitmap.height !in 16..512 ||
    drawable.alpha != 255 || drawable.colorFilter != null || drawable.paint.shader != null
  ) return false
  return runCatching {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    IconGeom.hasFaintPlate(pixels, bitmap.width, bitmap.height)
  }.getOrDefault(false)
}

/**
 * 把**带噪的**实心边界环换成内侧光滑色（2026-11-18 parsec 放射条纹的根因刀）。
 *
 * 判据三层，保证射程只落在噪环上：
 * ① 只碰 α255 且深度 ≤3 的格（实心外三格壳——噪环可能有两圈，
 *    只认最外一圈会漏掉内圈那行全 α255 邻域的噪格）；深度种子 = α<250，
 *    近不透明的抖动噪点（α250..254）**不算边界**：2026-11-19 主人「有些非自适应
 *    图标变得好糊」（QQ/米家）根因就是种子收 α254/250 噪点，把图标**内部**实色边
 *    （米家 α254×7777 格、QQ α250/251）当透明边界，7×7 核平均改写出一圈锯齿。
 *    与 [deFringe] 的毛边口径（α<250）同一个数；
 * ② 取 7×7 窗内"光滑核"（到透明处切比雪夫距离 ≥4 的实心）的平均色——噪环内侧就是光滑的；
 *    窗内没有实心核（1~2px 细笔画）⇒ 不写，真细节一格不动；
 * ③ 与现色差 ≤2 ⇒ 不写（光滑环/±1 抖动逐字节不变）。
 */
private fun cleanRim(px: IntArray, w: Int, h: Int) {
  val n = w * h
  fun alpha(i: Int) = px[i] ushr 24
  // 到最近 α<250 的切比雪夫距离（两遍 chamfer）。噪环住在深度 1..3 的壳里，
  // 深度 ≥4 的实心才是光滑核——只按 alpha 认核会把噪环内圈那行全 α255 的噪格也当核。
  // 种子不收 α250..254：那是源图内部的近不透明抖动噪点，不是透明边界（见 KDoc ①）。
  val depth = IntArray(n) { if (alpha(it) < 250) 0 else 1 shl 20 }
  for (y in 0 until h) for (x in 0 until w) {
    val i = y * w + x
    if (depth[i] == 0) continue
    var d = depth[i]
    for (dy in -1..0) for (dx in -1..1) {
      if (dy == 0 && dx >= 0) continue
      val nx = x + dx
      val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      d = minOf(d, depth[ny * w + nx] + 1)
    }
    depth[i] = d
  }
  for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
    val i = y * w + x
    if (depth[i] == 0) continue
    var d = depth[i]
    for (dy in 0..1) for (dx in -1..1) {
      if (dy == 0 && dx <= 0) continue
      val nx = x + dx
      val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      d = minOf(d, depth[ny * w + nx] + 1)
    }
    depth[i] = d
  }
  val snapshot = px.copyOf()
  for (i in 0 until n) {
    if (alpha(i) != 255 || depth[i] > 3) continue
    val x = i % w
    val y = i / w
    var sr = 0
    var sg = 0
    var sb = 0
    var cnt = 0
    var minR = 255
    var minG = 255
    var minB = 255
    var maxR = 0
    var maxG = 0
    var maxB = 0
    for (dy in -3..3) for (dx in -3..3) {
      val nx = x + dx
      val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      val j = ny * w + nx
      if (depth[j] < 4) continue
      val r = snapshot[j] ushr 16 and 255
      val g = snapshot[j] ushr 8 and 255
      val b = snapshot[j] and 255
      sr += r
      sg += g
      sb += b
      minR = minOf(minR, r)
      minG = minOf(minG, g)
      minB = minOf(minB, b)
      maxR = maxOf(maxR, r)
      maxG = maxOf(maxG, g)
      maxB = maxOf(maxB, b)
      cnt++
    }
    if (cnt == 0) continue
    // At a junction the interior contains multiple colors. Averaging them into the rim
    // would erase the transition that the extension needs to follow.
    if (maxOf(maxR - minR, maxG - minG, maxB - minB) >= 32) continue
    val r = sr / cnt
    val g = sg / cnt
    val b = sb / cnt
    val dr = Math.abs(r - ((snapshot[i] shr 16) and 0xFF))
    val dg = Math.abs(g - ((snapshot[i] shr 8) and 0xFF))
    val db = Math.abs(b - (snapshot[i] and 0xFF))
    if (dr <= 2 && dg <= 2 && db <= 2) continue
    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }
}

/**
 * 抗锯齿边的**颜色渗透**：0 < alpha < 255 的格，RGB 换成附近不透明格的颜色（保留 alpha）。
 *
 * 为什么要：源 PNG 的透明区常是黑色（RGB=0、alpha 从 0 到 255），边缘 AA 因此是「往黑里混」的 ——
 * 直接叠在补边上就是一条黑线（2026-11-13 真机：contacts 快捷方式）。渗透几趟之后，
 * AA 边带的就是图形自己的颜色，叠上去只剩透明度变化，没有线。
 */
private fun bleedEdgeColor(px: IntArray, w: Int, h: Int) {
  val n = w * h
  val snapshot = IntArray(n)
  repeat(3) {
    System.arraycopy(px, 0, snapshot, 0, n)
    for (i in 0 until n) {
      val a = px[i] ushr 24
      if (a == 0 || a == 255) continue
      val x = i % w
      val y = i / w
      var sr = 0
      var sg = 0
      var sb = 0
      var cnt = 0
      var best = -1
      // 取四邻里 **alpha 最大** 的那个的颜色（并列取平均）：沿着 AA 斜坡把最纯的颜色往外带，
      // 混所有邻居会把外圈的黑色又平均回来。
      fun take(j: Int) {
        val al = snapshot[j] ushr 24
        if (al <= 8) return
        if (al > best) {
          best = al
          sr = (snapshot[j] shr 16) and 0xFF
          sg = (snapshot[j] shr 8) and 0xFF
          sb = snapshot[j] and 0xFF
          cnt = 1
        } else if (al == best) {
          sr += (snapshot[j] shr 16) and 0xFF
          sg += (snapshot[j] shr 8) and 0xFF
          sb += snapshot[j] and 0xFF
          cnt++
        }
      }
      if (x > 0) take(i - 1)
      if (x + 1 < w) take(i + 1)
      if (y > 0) take(i - w)
      if (y + 1 < h) take(i + w)
      if (cnt > 0) px[i] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
    }
  }
}

/**
 * 算"边角该填什么颜色"。返回与 [px] 等长的数组：0 = 不填，其余 = 要铺的 ARGB。
 *
 * 颜色取**最近的轮廓边界点**：从八邻里有 outside/soft 格的实色格（α≥[SOLID_ALPHA]）做多源 BFS
 * （soft = 从 outside 洪泛进来的 α<SOLID_ALPHA 渐隐坡，软边轮廓靠它才选得出种子），
 * 每个边界点带自己的颜色往外拓；等距波前相遇按种子数加权平均（角上不再是谁先到谁赢的硬缝）。
 *
 * ⚠ 旧版取"行向 + 列向各一条边线的平均"，2026-11-16 量出两种接缝（主人：「交界处还是不够平滑」）：
 *   补边色与局部边色不符（云闪付红补边贴白色横条，接缝色差 p90=113、24% 的接缝 >30），
 *   以及行/列采样把边色拖成轴向条纹（王者荣耀两张 / wisdomgame）。换边界 BFS 后真机 32 张
 *   p90 全部 ≤18、>30 占比 ≤1%（离线复现台 `_harness/_extfill.cjs`，`--mode=rowcol` 臂是负对照）。
 *   渐变底同样保得住：每个边界点带自己的颜色，渐变沿轮廓往外延。
 */
private fun computeLegacyExtension(
  px: IntArray, w: Int, h: Int, outside: ByteArray, ink: IntArray? = null,
  recovered: Boolean = false,
): IntArray {
  val n = w * h
  val fill = IntArray(n)
  if (n <= 0) return fill
  if (outside.none { it == 1.toByte() }) return fill
  val distance = if (ink == null || minOf(w, h) >= DirectionalExtension.MIN_EDGE) IntArray(n) else null
  val reached = IconGeom.traceExtension(px, w, h, outside, fill, ink, distance)
  if (reached.none { it == 1.toByte() }) return fill
  // A pale backing can surround a colored mark without contributing any theme ink.
  // Keep the coverage for compositing and suppress stray mask values at that boundary.
  val uninkedBacking = recovered || (ink != null && distance != null &&
    IconGeom.clearUninkedPaleOutline(px, ink, distance, w, h))
  if (uninkedBacking) {
    for (i in fill.indices) if (fill[i] != 0) fill[i] = 255 shl 24
  }
  // 4b) 补色场盒抹两遍 3×3：种子自带 ±1 抖动（盘边 17/18 交替）会被径向单亲链原样
  //     复制成放射条纹（2026-11-18 主人真机：「为什么有那种放射性的条纹」）。
  //     盒抹对线性渐变无损（邻域均值=自身），只杀抖动与等距波前的 rounding 缝。
  val snapshot = IntArray(n)
  repeat(2) {
    System.arraycopy(fill, 0, snapshot, 0, n)
    for (i in 0 until n) {
      if (outside[i] != 1.toByte() || snapshot[i] == 0) continue
      val x = i % w
      val y = i / w
      var sr = 0
      var sg = 0
      var sb = 0
      var c2 = 0
      for (dy in -1..1) for (dx in -1..1) {
        val nx = x + dx
        val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        val c = snapshot[ny * w + nx]
        if (c == 0) continue
        sr += (c shr 16) and 0xFF
        sg += (c shr 8) and 0xFF
        sb += c and 0xFF
        c2++
      }
      fill[i] = (0xFF shl 24) or ((sr / c2) shl 16) or ((sg / c2) shl 8) or (sb / c2)
    }
  }

  // 5) 再往图标里垫：盖住图标自己那圈抗锯齿边（alpha 1..254）。
  //    不垫的话，第二遍把图标叠上去时那圈半透明像素露出的是宿主底色 —— 屏幕上就是一条浅缝。
  //    ⚠ 2026-11-18：固定 2 格盖不住 parsec 那类 6 格宽的软边坡（剩下 4 格仍半透明，
  //    叠在全局底色上又是一圈浅环）⇒ 改成"垫到不再长"，上限 8 格；硬边图 1~2 格即停 ⇒ 零差异。
  var pass = 0
  var added = true
  while (added && pass < 8) {
    added = false
    pass++
    System.arraycopy(fill, 0, snapshot, 0, n)
    for (i in 0 until n) {
      if (fill[i] != 0) continue
      if ((px[i] ushr 24) >= 250) continue
      if (outside[i] == 2.toByte()) continue
      val x = i % w
      val y = i / w
      var sr = 0
      var sg = 0
      var sb = 0
      var cnt2 = 0
      fun near(j: Int) {
        val c = snapshot[j]
        if (c == 0) return
        sr += (c shr 16) and 0xFF
        sg += (c shr 8) and 0xFF
        sb += c and 0xFF
        cnt2++
      }
      if (x > 0) near(i - 1)
      if (x + 1 < w) near(i + 1)
      if (y > 0) near(i - w)
      if (y + 1 < h) near(i + w)
      if (cnt2 > 0) {
        fill[i] = (0xFF shl 24) or ((sr / cnt2) shl 16) or ((sg / cnt2) shl 8) or (sb / cnt2)
        added = true
      }
    }
  }
  // Smooth the distant paint after underpainting so the seam keeps its local border colors.
  if (distance != null) {
    if (ink == null) IconGeom.smoothExtensionColors(fill, distance, snapshot, w, h)
    if (!uninkedBacking) DirectionalExtension.apply(px, distance, fill, w, h, ink)
  }
  return fill
}
