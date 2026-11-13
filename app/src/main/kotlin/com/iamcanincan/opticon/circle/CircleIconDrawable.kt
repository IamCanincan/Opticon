package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.iamcanincan.opticon.runtime.ModuleRuntime
import com.iamcanincan.opticon.runtime.TAG
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 可见区 = 图层 bounds 的多少。`AdaptiveIconDrawable` 把每一层的 bounds 撑到
 * `(1 + 2 × extraInset) ×` 可见区（本机 1.5 倍），这个值随安卓版本变，所以动态取。
 */
private val VIEW_PORT_SCALE = 1f / (1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction())

/**
 * 把**非自适应（老式）图标**做成自适应图标：前景 = 原图，**不留底盘**，形状交给系统。
 *
 * ## 为什么不再自己裁圆（2026-11-13 主人定）
 * 原来的做法是给每个图标 `DST_OUT` 一个圆、再绕开系统 mask 直接画前景 —— 桌面上得到一个圆，
 * 但那是**我们自己的几何**：应用信息页、最近任务、系统自己的图标解析各画各的，同一个应用
 * 在三处不一样；老式图标还会被放大到填满圆（ADB 的绿机器人顶到边上）。
 *
 * 现在只做"把它变成自适应图标"这一件事：
 *  · 背景层：**空的**（主人 2026-11-13：「不要有底盘」）；
 *  · 前景层：原图按可见区铺满（图层 bounds 往里收 1/6）—— 图案与图标槽 1:1；
 *  · 补满：自带透明留白的老式图标按**内容外框**等比放大到铺满整个方块（主人 2026-11-13：
 *    「非自适应的给它填补到完整的正方形」），放不下的部分裁掉；满幅的图一个像素不动；
 *  · 形状：**桌面说了算**（主人 2026-11-13：「我桌面图标的形状是可以改的」）。
 *    Launcher3 的 `drawFullBleed=true` 分支会把背景层 + 前景层直接画进图标位图、
 *    再按桌面自己的 `IconShape`（「图标形状」设置）裁；`false` 才会套框架那份
 *    `config_icon_mask`。模块在 [IconHooks] 里把这一位设成 `true`，所以桌面选圆角方就是
 *    圆角方、选圆就是圆，我们一行形状代码都不留。
 *
 * ⚠ `AdaptiveIconDrawable.draw()` 会先把图层位图**涂成黑色**再画两层，所以没有背景层时
 * 前景这里必须先把那块黑清成透明（[BareForeground]），否则图标透明的地方在屏幕上就是黑的。
 *
 * 自适应图标**不进这里**（[wrapLegacyIcon] 直接放行）：它自带背景 + 前景，形状本来就该由系统定。
 *
 * ## 单色层
 * 主题图标（Android 13+）染色靠自适应图标自带的 monochrome 层。原图标自带的就原样透传；
 * 没有的交合成出来的那张（[MonochromeSynth.forIcon]）—— 这一层是**老式图标能进主题图标体系**
 * 的唯一入口，所以 override 必须留在这里（`AdaptiveIconDrawable.getMonochrome()` 上挂的钩
 * 拦不到子类自己的实现）。
 */
class CircleIconDrawable(
  icon: Drawable,
  /** 原图的内容框 / 补满倍率 / 四边有没有透明格；null = 满幅且四边实色。 */
  private val metrics: IconMetrics? = null,
  /** 这张是**快捷方式**：只用于单色那支的几何判据（`MonochromeSynth.forIcon` 的 shortcut 位）。 */
  private val isShortcut: Boolean = false,
) : AdaptiveIconDrawable(
  // 不要底盘（2026-11-13）：老式图标自己就是整张图，另垫一块颜色等于替应用画了个底。
  // 用透明 ColorDrawable 而不是 null：有的宿主会直接读 `getBackground()`，空着比给个会抛的更安全。
  ColorDrawable(Color.TRANSPARENT),
  // 前景：内容补满可见区，形状由桌面裁
  BareForeground(icon, metrics),
) {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  /** 给**要未包装原图**的消费方（通知兜底 / 通知图标解码）。 */
  internal val source: Drawable get() = sourceIcon

  private val sourceState: ConstantState? = icon.constantState

  /** 原图标自带的 monochrome 层（自适应图标才有），能透传就透传。 */
  private val sourceMonochrome: Drawable? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      (icon as? AdaptiveIconDrawable)?.monochrome
    } else {
      null
    }

  /**
   * 原图标没提供单色层时，把合成出来的那张交出去（[MonochromeSynth.forIcon]）。
   *
   * 返回 null 只发生在两种情况下：总开关关掉（逃生口），或这张图合成不出来 ——
   * 那时桌面会去 new 它自己的 `MonochromeIconFactory`，而那条路在 [IconHooks] 里是**漏接计数**。
   */
  override fun getMonochrome(): Drawable? {
    sourceMonochrome?.let { return it }
    if (!ModuleRuntime.options().monoInvert) return null
    return MonochromeSynth.forIcon(sourceIcon, shortcut = isShortcut)
  }

  /**
   * 副本必须是新实例，且仍然要是 [AdaptiveIconDrawable]（否则 launcher 又走自己的老式包装）。
   * **绝不能返回 null**：Launcher3 的 FloatingIconView 直接调
   * `getConstantState().newDrawable()`，null = NPE = 桌面进程崩。
   */
  override fun getConstantState(): ConstantState? {
    val src = sourceState
    return object : ConstantState() {
      override fun newDrawable(): Drawable =
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, metrics, isShortcut)

      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 前景：清掉图层里的黑，把原图**补满**可见区，再把**边角透明处按最近的边缘颜色延伸填满**。
 *
 * `AdaptiveIconDrawable.draw()` 在画两层之前会 `mCanvas.drawColor(Color.BLACK)`；有背景层时
 * 黑被盖住，没有背景层时它就会从图标的透明处露出来。这里用 `CLEAR` 把整张图层清成透明 ——
 * 等价于"这张自适应图标没有底色"。
 *
 * 几何：Launcher3 把桌面图标按"背景层 + 前景层直接画"的方式渲染（`drawFullBleed=true`），
 * 前景层拿到的 bounds 是**可见区的 1.5 倍**（每边多垫 25%）。原图是给 72dp 可视区画的，
 * 所以这里把 bounds 往里收 `extraInset / (1 + 2 × extraInset)`（本机 = 1/6），
 * 图案正好铺满可见区、与图标槽 1:1 —— 随后由桌面自己的「图标形状」裁一刀。
 *
 * **补满**（主人 2026-11-13：「非自适应的给它填补到完整的正方形」）：[metrics] 是原图可见
 * 内容的外框（归一化），按 `max(1/宽, 1/高)` 等比放大、内容中心对到槽中心 —— 内容铺满方块，
 * 放不下的裁掉。满幅的图 `metrics == null`，直接原样画（快路径）。
 *
 * **边角延伸**（同日第二句：「边角透明的地方给它延伸完全」）：把图标光栅化到图标槽大小，
 * 从**画布边界**找出与外面连通的透明区域（内部镂空不动），**行向 + 列向各取一条边色再平均**
 * 铺进去（极角上两个方向都没有的小三角，用 BFS 从最近的已给色格拓）—— 圆角/圆盘图标那
 * 四个透明角从此是它自己的边缘色，不再是壁纸。铺完还往图标里垫 2 格，第二遍把图标叠在
 * 底色上画，抗锯齿边与底色混合，不会留下一条半透明的浅缝。
 * 这一步只在图标真的带透明边时才做（[IconMetrics] 在加载时量过）。
 */
private class BareForeground(
  private val inner: Drawable,
  private val metrics: IconMetrics? = null,
) : Drawable() {

  /** 补满倍率；≤1 = 不动。上限见 [MAX_FILL]。 */
  private val fill: Float = metrics?.fill ?: 1f

  /** 光栅化 + 延伸用的画布（按尺寸缓存，图标槽尺寸基本不变）。 */
  private var scratch: Bitmap? = null
  private var scratchCanvas: Canvas? = null
  private var scratchW = 0
  private var scratchH = 0

  /** draw 抛异常只报一次（这一层是被桌面直接画的，抛出去就是桌面崩）。 */
  private var failLogged = false

  override fun draw(canvas: Canvas) {
    // ⚠ 兜底：这一层被 BaseIconFactory / 桌面直接调用，异常会一路把桌面带崩
    //   （2026-11-13 真机：边角延伸的 BFS 数组越界，桌面进 launcher-loader 就崩）。
    //   宁可退回原图，也不把宿主拖下水。
    try {
      drawInner(canvas)
    } catch (t: Throwable) {
      if (!failLogged) {
        failLogged = true
        Log.w(TAG, "自适应：前景 draw 抛了，退回原图（${t.javaClass.simpleName}: ${t.message}）")
      }
      runCatching {
        inner.setBounds(bounds)
        inner.draw(canvas)
      }
    }
  }

  private fun drawInner(canvas: Canvas) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val view = inner.bounds
    if (view.isEmpty) return
    val m = metrics
    if (m == null) {
      // 满幅且四边不透明：一个像素都不用动
      inner.draw(canvas)
      return
    }
    drawFilled(canvas, view, m)
  }

  /**
   * 光栅化 → 铺内容（可放大）→ 算边角延伸色 → **把延伸色垫在底下、再把图标叠上去** → 贴回图层。
   *
   * ⚠ 为什么画两遍：把透明区填成实色之后，图标自己那圈抗锯齿边（alpha 1..254）如果留在
   * 透明底上，屏幕上是**一条半透明的缝**（2026-11-13 主人：「接缝有点大」）。第二遍把图标
   * 叠在延伸色上，边缘与底色正常混合，缝就没了。
   */
  private fun drawFilled(canvas: Canvas, view: Rect, m: IconMetrics) {
    val w = view.width()
    val h = view.height()
    if (w <= 0 || h <= 0) return
    var bitmap = scratch
    var layer = scratchCanvas
    if (bitmap == null || layer == null || scratchW != w || scratchH != h) {
      bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      layer = Canvas(bitmap)
      scratch = bitmap
      scratchCanvas = layer
      scratchW = w
      scratchH = h
    }
    bitmap.eraseColor(Color.TRANSPARENT)
    drawIcon(layer, w, h, m, view)

    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val fill = computeFill(px, w, h)

    bitmap.eraseColor(Color.TRANSPARENT)
    bitmap.setPixels(fill, 0, w, 0, 0, w, h)
    drawIcon(layer, w, h, m, view)

    canvas.drawBitmap(bitmap, null, view, null)
  }

  /** 把原图按补满倍率画进图层；两次绘制共用。 */
  private fun drawIcon(layer: Canvas, w: Int, h: Int, m: IconMetrics, view: Rect) {
    val save = layer.save()
    if (fill > 1.001f) {
      // 内容框中心 → 图标槽中心：T(p) = 槽中心 + fill × (p − 内容中心)
      val cX = w * (m.l + m.r) / 2f
      val cY = h * (m.t + m.b) / 2f
      layer.translate(w / 2f, h / 2f)
      layer.scale(fill, fill)
      layer.translate(-cX, -cY)
    }
    inner.setBounds(0, 0, w, h)
    inner.draw(layer)
    layer.restoreToCount(save)
    inner.setBounds(view)
  }

  override fun onBoundsChange(bounds: Rect) {
    val frac = (1f - VIEW_PORT_SCALE) / 2f
    val dx = (bounds.width() * frac).toInt()
    val dy = (bounds.height() * frac).toInt()
    inner.setBounds(bounds.left + dx, bounds.top + dy, bounds.right - dx, bounds.bottom - dy)
  }

  override fun setAlpha(alpha: Int) {
    inner.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    inner.colorFilter = colorFilter
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = inner.intrinsicWidth

  override fun getIntrinsicHeight(): Int = inner.intrinsicHeight

  override fun getConstantState(): ConstantState? =
    inner.constantState?.let { src ->
      val m = metrics
      object : ConstantState() {
        override fun newDrawable(): Drawable = BareForeground(src.newDrawable(), m)

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/** 量"内容占画布多少 / 四边透不透明"用的光栅边长。 */
private const val MEASURE_EDGE = 128

/** 补满倍率上限：再小的内容（<1/6 画布）也不放得更大，否则整张糊掉、也量不准。 */
private const val MAX_FILL = 6f

/** 判"看得见"的 alpha 门槛（与量内容框同一把尺）。 */
private const val ALPHA_MIN = 8

/** 取边色时优先用"够实"的格：抗锯齿边色被拉出去就是一道浅色接缝。 */
private const val SOLID_ALPHA = 160

/** 取边色时往里让几格：边界那 1~2 格是抗锯齿混出来的浅色，照它铺就是一圈浅接缝。 */
private const val EDGE_INSET = 1

/**
 * 老式图标在图标槽里的处理参数。
 *
 * @param l/t/r/b 可见内容的外框（归一化 0..1）。
 * @param fill 补满倍率（`max(1/宽,1/高)`，封顶 [MAX_FILL]）。
 * @param extend 四边有透明格（需要"边角延伸"）。
 */
data class IconMetrics(
  val l: Float,
  val t: Float,
  val r: Float,
  val b: Float,
  val fill: Float,
  val extend: Boolean,
)

/**
 * 量原图：可见内容外框 + 四边有没有透明格。满幅且四边不透明 → null（一个像素都不用动）。
 *
 * 只在图标加载时跑一次。任何一步失败都返回 null（= 原样画），与整条包装一样绝不把宿主拖下水。
 */
internal fun measureIcon(icon: Drawable): IconMetrics? = runCatching {
  val n = MEASURE_EDGE
  val saved = Rect(icon.bounds)
  val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
  try {
    Canvas(bitmap).also {
      icon.setBounds(0, 0, n, n)
      icon.draw(it)
    }
    val px = IntArray(n * n)
    bitmap.getPixels(px, 0, n, 0, 0, n, n)
    var l = n
    var t = n
    var r = -1
    var b = -1
    var edgeOpen = false
    for (y in 0 until n) {
      val row = y * n
      val edgeRow = y < 2 || y >= n - 2
      for (x in 0 until n) {
        val a = px[row + x] ushr 24
        if (a <= ALPHA_MIN) {
          if (edgeRow || x < 2 || x >= n - 2) edgeOpen = true
          continue
        }
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
      }
    }
    if (r < 0) return null
    val bw = (r + 1 - l).toFloat() / n
    val bh = (b + 1 - t).toFloat() / n
    // 满幅 + 四边都是实色：真机上的多数，一个像素都不动
    if (bw >= 0.97f && bh >= 0.97f && !edgeOpen) return null
    // 太小的一块：那不是"留白"，是杂点，补它只会把杂点放大
    val fill = if (bw < 0.04f || bh < 0.04f) {
      1f
    } else {
      maxOf(1f / bw, 1f / bh).coerceAtMost(MAX_FILL)
    }
    IconMetrics(l / n.toFloat(), t / n.toFloat(), (r + 1f) / n, (b + 1f) / n, fill, edgeOpen)
  } finally {
    bitmap.recycle()
    icon.bounds = saved
  }
}.getOrNull()

/**
 * 算"边角该填什么颜色"。返回与 [px] 等长的数组：0 = 不填，其余 = 要铺的 ARGB。
 *
 * 颜色取**行向 + 列向各一条边线**的平均（渐变底两个方向都保得住）；边线取的是
 * 往里让 [EDGE_INSET] 格的颜色 —— 边界那一两格是抗锯齿混出来的浅色，照它铺就是一圈浅接缝。
 * 极角上既没有行也没有列的小三角，用 BFS 从最近的已给色格拓过去。
 */
private fun computeFill(px: IntArray, w: Int, h: Int): IntArray {
  val n = w * h
  val fill = IntArray(n)
  if (n <= 0) return fill
  val outside = ByteArray(n)
  val work = IntArray(n)

  // 1) 从四条边洪泛，标出"与外面连通"的透明格（内部镂空走不到）
  var sp = 0
  fun mark(i: Int) {
    if (outside[i] == 0.toByte() && (px[i] ushr 24) <= ALPHA_MIN) {
      outside[i] = 1
      work[sp++] = i
    }
  }
  for (x in 0 until w) {
    mark(x)
    mark((h - 1) * w + x)
  }
  for (y in 0 until h) {
    mark(y * w)
    mark(y * w + w - 1)
  }
  while (sp > 0) {
    val i = work[--sp]
    val x = i % w
    val y = i / w
    if (x > 0) mark(i - 1)
    if (x + 1 < w) mark(i + 1)
    if (y > 0) mark(i - w)
    if (y + 1 < h) mark(i + w)
  }

  // 2) 行/列的实色界（优先"够实"的格；取色时往里让 EDGE_INSET 格）
  val rowLx = IntArray(h) { -1 }
  val rowRx = IntArray(h) { -1 }
  val rowLc = IntArray(h)
  val rowRc = IntArray(h)
  for (y in 0 until h) {
    var lx = -1
    var rx = -1
    for (x in 0 until w) {
      if ((px[y * w + x] ushr 24) >= SOLID_ALPHA) {
        if (lx < 0) lx = x
        rx = x
      }
    }
    if (lx < 0) {
      for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) > ALPHA_MIN) {
          if (lx < 0) lx = x
          rx = x
        }
      }
    }
    if (lx >= 0) {
      rowLx[y] = lx
      rowRx[y] = rx
      rowLc[y] = px[y * w + minOf(lx + EDGE_INSET, rx)]
      rowRc[y] = px[y * w + maxOf(rx - EDGE_INSET, lx)]
    }
  }
  val colTy = IntArray(w) { -1 }
  val colBy = IntArray(w) { -1 }
  val colTc = IntArray(w)
  val colBc = IntArray(w)
  for (x in 0 until w) {
    var ty = -1
    var by = -1
    for (y in 0 until h) {
      if ((px[y * w + x] ushr 24) >= SOLID_ALPHA) {
        if (ty < 0) ty = y
        by = y
      }
    }
    if (ty < 0) {
      for (y in 0 until h) {
        if ((px[y * w + x] ushr 24) > ALPHA_MIN) {
          if (ty < 0) ty = y
          by = y
        }
      }
    }
    if (ty >= 0) {
      colTy[x] = ty
      colBy[x] = by
      colTc[x] = px[minOf(ty + EDGE_INSET, by) * w + x]
      colBc[x] = px[maxOf(by - EDGE_INSET, ty) * w + x]
    }
  }

  // 3) 行向 + 列向取平均
  val filled = ByteArray(n)
  val color = IntArray(n)
  for (i in 0 until n) {
    if (outside[i] == 0.toByte()) continue
    val x = i % w
    val y = i / w
    var sumR = 0
    var sumG = 0
    var sumB = 0
    var cnt = 0
    if (rowLx[y] >= 0) {
      val c = when {
        x <= rowLx[y] -> rowLc[y]
        x >= rowRx[y] -> rowRc[y]
        else -> 0
      }
      if (c != 0) {
        sumR += (c shr 16) and 0xFF
        sumG += (c shr 8) and 0xFF
        sumB += c and 0xFF
        cnt++
      }
    }
    if (colTy[x] >= 0) {
      val c = when {
        y <= colTy[x] -> colTc[x]
        y >= colBy[x] -> colBc[x]
        else -> 0
      }
      if (c != 0) {
        sumR += (c shr 16) and 0xFF
        sumG += (c shr 8) and 0xFF
        sumB += c and 0xFF
        cnt++
      }
    }
    if (cnt > 0) {
      color[i] = (0xFF shl 24) or ((sumR / cnt) shl 16) or ((sumG / cnt) shl 8) or (sumB / cnt)
      filled[i] = 1
    }
  }

  // 4) 极角：从最近的已给色格 BFS 拓过去
  var head = 0
  var tail = 0
  for (i in 0 until n) {
    if ((px[i] ushr 24) > ALPHA_MIN) {
      color[i] = px[i]
      filled[i] = 1
    }
    if (filled[i] == 1.toByte()) work[tail++] = i
  }
  while (head < tail) {
    val i = work[head++]
    val c = color[i]
    val x = i % w
    val y = i / w
    if (x > 0) tail = spread(i - 1, c, outside, filled, color, work, tail)
    if (x + 1 < w) tail = spread(i + 1, c, outside, filled, color, work, tail)
    if (y > 0) tail = spread(i - w, c, outside, filled, color, work, tail)
    if (y + 1 < h) tail = spread(i + w, c, outside, filled, color, work, tail)
    if (x > 0 && y > 0) tail = spread(i - w - 1, c, outside, filled, color, work, tail)
    if (x + 1 < w && y > 0) tail = spread(i - w + 1, c, outside, filled, color, work, tail)
    if (x > 0 && y + 1 < h) tail = spread(i + w - 1, c, outside, filled, color, work, tail)
    if (x + 1 < w && y + 1 < h) tail = spread(i + w + 1, c, outside, filled, color, work, tail)
  }

  // 5) 只交"外面"的格
  for (i in 0 until n) {
    if (outside[i] == 1.toByte() && filled[i] == 1.toByte()) fill[i] = color[i]
  }

  // 6) 再往图标里垫 2 格：盖住图标自己那圈抗锯齿边（alpha 1..254）。
  //    不垫的话，第二遍把图标叠上去时那圈半透明像素露出的是宿主底色 —— 屏幕上就是一条浅缝。
  val snapshot = IntArray(n)
  repeat(2) {
    System.arraycopy(fill, 0, snapshot, 0, n)
    for (i in 0 until n) {
      if (fill[i] != 0) continue
      if ((px[i] ushr 24) >= 250) continue
      val x = i % w
      val y = i / w
      var sr = 0
      var sg = 0
      var sb = 0
      var cnt = 0
      fun near(j: Int) {
        val c = snapshot[j]
        if (c == 0) return
        sr += (c shr 16) and 0xFF
        sg += (c shr 8) and 0xFF
        sb += c and 0xFF
        cnt++
      }
      if (x > 0) near(i - 1)
      if (x + 1 < w) near(i + 1)
      if (y > 0) near(i - w)
      if (y + 1 < h) near(i + w)
      if (cnt > 0) fill[i] = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
    }
  }
  return fill
}

/** BFS 入队：只吃"外面且还没给色"的格。 */
private fun spread(
  j: Int,
  c: Int,
  outside: ByteArray,
  filled: ByteArray,
  color: IntArray,
  work: IntArray,
  tail: Int,
): Int {
  if (outside[j] != 1.toByte() || filled[j] != 0.toByte()) return tail
  filled[j] = 1
  color[j] = c
  work[tail] = j
  return tail + 1
}

/** 「包装自身抛了」只报一次（每张图标一行会把日志刷满）。 */
private val clipFailLogged = AtomicBoolean(false)

/**
 * 取回**没包装过**的原图（不是 [CircleIconDrawable] 就原样返回）。
 *
 * 给通知那两条消费方用：SystemUI 里 `PackageManager.getApplicationIcon` 与
 * `Resources.getDrawable*` 也会被这套钩子包起来，而通知的剪影必须从**原图**的 alpha 取 ——
 * 拿"按形状裁过一刀"的那份去压，剪影会缺角。
 */
internal fun unwrappedIcon(d: Drawable): Drawable =
  if (d is CircleIconDrawable) d.source else d

/** 宿主包名，装配时置一次；只为日志服务。 */
@Volatile
var clipHost: String = "?"

/**
 * 射程（**2026-10-21 主人定：「裁圆算法应只排除快速设置的磁贴，别的应做到全覆盖」**）：
 * **默认全包，只排快速设置那一路**（宿主名只用于日志，见上面那行 [clipHost]）。
 *
 * 🔴 **代价写在明处**：作用域内的**每一个进程**现在都走这套包装（第三方 app 里显示 app 图标的
 *   那些界面也是）。哪一户被包坏了，拿 `自适应` 那行日志的 `host=` 点名排除，
 *   **不要回去改默认值**。
 */
/**
 * 快速设置那一路的**调用方前缀**（不是通道名 —— 通道名分不开，见 [calledByQuickSettings]）。
 * 只有一条，但它是**扫出来的**而不是猜的：2026-10-21 从这台机器的 `SystemUI.apk` 三个 dex 里
 * 数过命名空间 —— `qs.tiles` 217、`qs.tiles.dialog` 90（= 预览界面那一页）、`qs.panels.*`、
 * `qs.pipeline.*`、`qs.external` 59（第三方磁贴的宿主）**全都在 `com.android.systemui.qs.` 底下**。
 * ⚠ 第三方磁贴的 `TileService` 如果在**它自己进程**里取图标，这一条看不见 —— 那种现象出现时
 *   先加日志再谈规则，不要提前扩面。
 */
private const val TILE_CALLERS = "com.android.systemui.qs."

/** 只在 SystemUI 里读堆栈：磁贴界面只可能由它画，别的过程读了白读。 */
private const val SYSTEMUI_HOST = "com.android.systemui"

/**
 * 「这一次是不是快速设置在取图标」。
 *
 * 通知行的应用图标走 `loadUnbadgedIcon → loadUnbadgedItemIcon → APM.getDrawable`，而磁贴走的
 * `loadItemIcon` **内部第一件事就是回 `loadUnbadgedItemIcon`** —— 两条路在框架里**汇到同一个
 * 出口**，通道名分不开它们，只有"是谁调的"分得开。
 */
private fun calledByQuickSettings(): Boolean {
  if (clipHost != SYSTEMUI_HOST) return false
  return Thread.currentThread().stackTrace.any { it.className.startsWith(TILE_CALLERS) }
}

/**
 * 挡下一次时进账。**"没挡下"与"没东西可挡"是两件事**：这道判据失效（AOSP 改了类名、
 * OTA 把 `qs.` 挪走）的现象正是"磁贴被包了"，所以既要有挡下的账，也要在收尾时报
 * "这一轮 SystemUI 里包了几张、挡了几次"。
 */
private fun noteTileSkip(from: String) {
  val key = "$from ← 磁贴(按调用方挡住)"
  skipTally.merge(key, 1) { a, b -> a + b }
  if (skipTally[key] == 1) Log.d(TAG, "自适应按磁贴挡下 host=$clipHost：$key")
}

/**
 * **射程的账**：记 `通道 × 宿主` 的第一次与总量。没有它，"全覆盖"这三个字就只能靠信。
 * ⚠ 每个「通道×宿主」**第一次**必打一行；总账每 [RANGE_DUMP_EVERY] 次打一行。
 */
private const val RANGE_DUMP_EVERY = 200L
private const val RANGE_DUMP_MAX = 20L
private val skipTally = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val wrapTally = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val wrapCount = java.util.concurrent.atomic.AtomicLong(0)
private val wrapDumps = java.util.concurrent.atomic.AtomicLong(0)

private fun noteWrap(from: String) {
  com.iamcanincan.opticon.runtime.Stats.bump("自适应")
  val key = "$from ← $clipHost"
  wrapTally.merge(key, 1) { a, b -> a + b }
  if (wrapTally[key] == 1) Log.d(TAG, "自适应新开 host=$clipHost 通道=$from（第一次）")
  val n = wrapCount.incrementAndGet()
  if (n % RANGE_DUMP_EVERY == 0L && wrapDumps.getAndIncrement() < RANGE_DUMP_MAX) {
    val rows = wrapTally.entries.sortedByDescending { it.value }.take(8)
      .joinToString(" ; ") { "${it.key} ×${it.value}" }
    Log.d(TAG, "自适应累计 $n 次，通道×宿主 top：$rows")
  }
}

/** 每一张"包了"的图标一条。前 [WRAP_LOG_LIMIT] 条。 */
private const val WRAP_LOG_LIMIT = 200

private val wrapLogLeft = AtomicInteger(WRAP_LOG_LIMIT)

/**
 * 包装成自适应图标；**这一层本身绝不抛**。
 *
 * ⚠ 它被挂在宿主的取图热路径上，抛出去就是宿主崩：任何一步失败都原样放行。
 */
fun wrapLegacyIcon(icon: Drawable, from: String): Drawable =
  runCatching { wrapInner(icon, from) }.getOrElse {
    if (clipFailLogged.compareAndSet(false, true)) {
      Log.w(TAG, "自适应：包装抛了，原样放行（${it.javaClass.simpleName}: ${it.message}）")
    }
    icon
  }

private fun wrapInner(icon: Drawable, from: String): Drawable {
  // 配置按 TTL 重读，所以界面里关掉开关后很快生效，不用重启进程。
  if (!ModuleRuntime.options().circleEnabled) return icon
  // 射程（2026-10-21 起）：**默认全包，唯一的排除是快速设置那一路**（调用方分得开）。
  if (calledByQuickSettings()) {
    noteTileSkip(from)
    return icon
  }
  // 已经包过的、以及**自适应图标**：都不动。自适应图标自带背景 + 前景，形状由系统 mask 定。
  if (icon is CircleIconDrawable || icon is AdaptiveIconDrawable) return icon

  // 内容外框：只给"自带透明留白"的老式图标用（补满正方形）；满幅的返回 null。
  val metrics = measureIcon(icon)
  noteWrap(from)
  if (wrapLogLeft.decrementAndGet() >= 0) {
    val fill = metrics?.fill ?: 1f
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} 无底盘 补满=" + "%.2f".format(fill) +
        "× 延伸=" + (if (metrics?.extend == true) 1 else 0))
  }
  return CircleIconDrawable(icon, metrics, from.contains("Shortcut"))
}
