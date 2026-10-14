package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.iamcanincan.opticon.runtime.ModuleRuntime
import com.iamcanincan.opticon.runtime.TAG
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 自适应图标多出来的那一圈。`AdaptiveIconDrawable` 把每一层的 bounds 设成
 * `(1 + 2 × extraInset) × view bounds`，只有中心的 view bounds 是可见区域。
 *
 * 这个值**随安卓版本变**（老版本是 0.25 → 1/1.5），所以一律动态取，不写死。
 */
private val EXTRA_INSET_FRACTION = AdaptiveIconDrawable.getExtraInsetFraction()

/** 内容要缩到这个比例，才能在自适应图标里 1:1 呈现。 */
private val VIEW_PORT_SCALE = 1f / (1f + 2f * EXTRA_INSET_FRACTION)

/**
 * 把图标**裁成圆形**，并让系统按自适应图标的方式对待它。
 *
 * ## 图标包的三件套：前景 / 遮罩 / 背景
 * 图标包给每个图标提供三层：**前景（upon）**、**遮罩（mask）**、**背景（back）**。
 * 本模块只提供遮罩 —— 前景和背景两个装饰层都留空，形状完全由那个圆形遮罩决定，
 * 不叠加任何装饰。
 *
 * 合成顺序与参考项目一致：
 * 1. 画原图标（自适应图标就是它自己的 background + foreground）
 * 2. **遮罩 `DST_OUT`** —— 遮罩是「圆外不透明、圆内透明」的，擦掉圆外即得到圆
 *
 * 落到 `AdaptiveIconDrawable` 上就是参考项目验证过的结构：
 * **背景透明 + 前景 = 套过圆形遮罩的图标（缩到可见区）**。
 *
 * ## 外层 `draw()` 必须重写，且不调 `super.draw()`
 * 父类自带的 `draw()` 会在合成之后再套一次**系统 mask**（`config_icon_mask`），
 * 最终形状变成 ROM 决定的那个（圆角方 / 水滴 / 方），而不是我们的圆。
 * 参考项目为此写了 `UnClipAdaptiveIconDrawable`：用「全幅矩形」路径作画绕开 mask，
 * 靠反射 `mLayersBitmap` / `mLayersShader` / `mCanvas` / `mPaint` 实现。
 * 我们不反射（版本一变就断），直接把前景层画出来即可 —— 圆已经在遮罩那一步裁好了。
 */
/**
 * 桌面自己给主题图标用的那块盘是什么颜色。
 *
 * 为什么不是常数（2026-10-09）：已适配图标的盘色来自桌面资源 `themed_icon_background_color`，
 * 本机 `aapt2 dump` 量出来它白天是 `system_accent1_100`、夜里是 `system_accent2_800` ——
 * **是 Material You 动态色，跟着壁纸变**。我们写死白，就等于把别人的主题猜成一个值，
 * 主人报的「背景和适配取色的背景颜色不一样了」就是这么来的。
 *
 * 三个取值口径：
 *  · 用**宿主的** `Resources` + `theme`，所以日夜模式与动态色由系统自己解析；
 *  · 名字取不到（换桌面、被 R8 改名、非桌面进程）→ 退回白，与改前一模一样；
 *  · 一次进程解析一次（`by lazy`）：图标是热路径，而换主题本来就要清图标缓存才重画。
 */
internal fun themedPlateColor(): Int = themedPlateCache.getOrPut("plate") {
  runCatching {
    val app = currentApplication() ?: return@runCatching Color.WHITE
    val res = app.resources
    val id = res.getIdentifier("themed_icon_background_color", "color", app.packageName)
    // **取没取到必须说话**（本仓"0 与没走到长得一样"这一族吃过多次亏）：
    // 这一行也顺手回答了那个只有装机能判的问题 —— 合成器到底是照我们给的颜色画，还是自己再染一次。
    android.util.Log.i(TAG, "Plate: ${app.packageName} themed_icon_background_color id=$id " +
      (if (id == 0) "取不到 → 用白" else "= #%06X".format(res.getColor(id, app.theme) and 0xFFFFFF)))
    if (id == 0) Color.WHITE else res.getColor(id, app.theme)
  }.getOrDefault(Color.WHITE)
}

/** 缓存放在这里而不是 object 里：`getIdentifier` 的口径是"当前进程"，一个进程只有一个答案。 */
private val themedPlateCache = HashMap<String, Int>()

class CircleIconDrawable(icon: Drawable) :
  AdaptiveIconDrawable(
    // 背景：**桌面自己给主题图标用的那块盘**（2026-10-09），取不到时退回白。
    // 原来这里是写死的 `Color.WHITE`（2026-10-08）。为什么那一手不够：桌面上"已适配"图标的盘
    // 不是白的，是**桌面资源 `themed_icon_background_color`** —— 本机量出来它白天是
    // `system_accent1_100`、夜里是 `system_accent2_800`（Material You 动态色）。
    // 于是主人报的那句「背景和适配取色的背景颜色不一样了」有了来源：**我们的盘是一个猜出来的常数。**
    // 现在改成问宿主：同一个资源名、同一套日夜与动态色解析，**由桌面自己决定那块盘是什么颜色**。
    //
    // 为什么安全：本类自己的 `draw()` **从不画背景层**（只画前景，见下面那段），
    // 普通图标上这块颜色一个像素都看不见；只有**去取背景层的消费方**（取色合成器）会拿到它。
    // ⚠ 两种可能的结局，只有装机能判：(a) 合成器照我们给的颜色画 → 从此与已适配图标一致；
    //   (b) 合成器自己再染一次 → 这一改是**空转**，那条投诉的根不在这里。
    //   回滚 = 把这一行改回 `Color.WHITE`（或 `Color.TRANSPARENT`）。
    ColorDrawable(themedPlateColor()),
    // 前景：原图标套上圆形遮罩，再缩到可见区
    ViewportDrawable(MaskedDrawable(icon)),
  ) {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  private val sourceState: ConstantState? = icon.constantState

  /**
   * 主题图标（Android 13+ 的「动态取色」）染色的依据是 adaptive 图标自带的
   * **monochrome 层**。包装时只给了背景 + 前景、这一层是空的 —— 开了主题图标的
   * 启动器会发现它取不到 monochrome，这些图标就**不会被染色**。
   *
   * 所以原图标自带的 monochrome 要原样透传回去。用重写 `getMonochrome()` 而不是
   * 三参构造：那个构造器 API 33 才有，低版本上解析不到会直接 `NoSuchMethodError`。
   */
  private val sourceMonochrome: Drawable? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      (icon as? AdaptiveIconDrawable)?.monochrome
    } else {
      null
    }

  /**
   * 原图标没提供单色层时，**在这里把合成出来的那张交出去**（[MonochromeSynth.forIcon]）。
   *
   * ⚠ 这里是 override，`AdaptiveIconDrawable.getMonochrome()` 上挂的那道合成钩
   * **拦不到本类**（虚方法分派直接走这个实现）—— 而裁圆默认开着、桌面拿到的图标几乎都是
   * 本类实例，所以合成必须在这里也接一次，否则默认配置下主路径等于没上。
   *
   * ⚠ 合成用 **`sourceIcon`（原图标）而不是本对象**：本对象的可见外观是「圆内裁好的内容」，
   *   拿它去压剪影只会得到一个实心圆饼。
   *
   * 返回 null 只发生在两种情况下：总开关关掉（逃生口），或这张图合成不出来。
   * 那时桌面会去 new 它自己的 `MonochromeIconFactory`，而那条路在 [IconHooks] 里是**漏接计数**
   * —— 屏幕上这批就是反的，所以它绝不静默。
   */
  override fun getMonochrome(): Drawable? {
    sourceMonochrome?.let { return it }
    if (!ModuleRuntime.options().monoInvert) return null
    return MonochromeSynth.forIcon(sourceIcon)
  }

  override fun draw(canvas: Canvas) {
    // 背景是透明的，画它等于什么都没画 —— 圆在前景层。
    val fg = foreground
    if (fg == null) super.draw(canvas) else fg.draw(canvas)
  }

  /**
   * 副本必须是新实例，且仍然要是 [AdaptiveIconDrawable]（否则 launcher 又走 wrap 路径）。
   * **绝不能返回 null**：Launcher3 的 FloatingIconView 直接调
   * `getConstantState().newDrawable()`，null = NPE = 桌面进程崩。
   */
  override fun getConstantState(): ConstantState? {
    val src = sourceState
    return object : ConstantState() {
      override fun newDrawable(): Drawable = CircleIconDrawable(src?.newDrawable() ?: sourceIcon)

      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 把内容缩到**可见区**：把 bounds 四周各裁掉 `(1 - scale) / 2`。
 *
 * 对应参考项目的 `IconHelper.ScaleDrawable`。缩放做在这一层而不是画的时候，
 * 好处是 [getIntrinsicWidth] 能报出 `原尺寸 / scale` —— 与参考一致；
 * 若在内层自己算，`AdaptiveIconDrawable` 报出的固有尺寸会只有 2/3。
 */
private class ViewportDrawable(private val inner: Drawable) : Drawable() {

  override fun onBoundsChange(bounds: Rect) {
    val frac = (1f - VIEW_PORT_SCALE) / 2f
    val dx = ceil(bounds.width() * frac).toInt()
    val dy = ceil(bounds.height() * frac).toInt()
    inner.setBounds(bounds.left + dx, bounds.top + dy, bounds.right - dx, bounds.bottom - dy)
  }

  override fun draw(canvas: Canvas) = inner.draw(canvas)

  override fun setAlpha(alpha: Int) {
    inner.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    inner.colorFilter = colorFilter
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = scaled(inner.intrinsicWidth)

  override fun getIntrinsicHeight(): Int = scaled(inner.intrinsicHeight)

  private fun scaled(value: Int) = if (value < 0) -1 else (value / VIEW_PORT_SCALE).toInt()

  override fun getConstantState(): ConstantState? =
    inner.constantState?.let { src ->
      object : ConstantState() {
        override fun newDrawable(): Drawable = ViewportDrawable(src.newDrawable())

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/**
 * 按 cover 方式把原图标画满 bounds，再用**圆形遮罩**擦掉圆外。
 *
 * 遮罩与用户给的那张 PNG 同构：圆外不透明、圆内透明，配合 `DST_OUT` 使用。
 * 这里用 `EVEN_ODD` 的「整块矩形 + 圆」直接构造出同一张遮罩，不必带一张超大位图。
 *
 * ## 内切的基准是**图案实际占的那块**，不是整块画布（2026-10-14）
 * 改前一律按整块画布 cover + 内切。图案没铺满画布时（自带透明边、或背景层透明只有
 * logo 的自适应图标），**圆就比图案大一圈** —— 圆内围一圈透明，一个"不规则图标"
 * 被切成了圆里塞一个小方块。现在先量出图案的外框（[contentBox]），拿它当画布：
 * 圆于是正好内切在图案上。图案本来就满幅的（真机 61 张里 59 张）逐位不变。
 *
 * ## 分成两档，白底与缩小**只给"裁不动"那一档**（同日第三、四轮）
 * 判据是**"按圆裁下去还剩几成图案"**（[KEEP_RATIO]，基准 = 正方形按圆形裁的 78.5% ——
 * 所有正常图标都是这个样子）。两条路的产物不一样，不许混：
 *  - **裁得动的** → 按 [contentBox] 量出的**不透明圆盘**内切、填满圆、圆外透明 ——
 *    这一档**不收边、不铺白底**。图案本来就满幅的（真机 61 张里 59 张）在 [BOX_FULL_RATIO]
 *    处直接早退，与改前**逐位不变**。
 *  - **裁不动的**（宽幅 banner、铃铛、十字、环 —— 裁下去只剩 [KEEP_RATIO] 以下）→ **不裁**：
 *    整幅缩进圆里，圆内先铺一层白底（[plateWhite]），盖不满的地方由它补上，圆于是始终是完整的。
 *    缩多少由三条尺子里**最严**的那条定：主体进安全区（[SAFE_ZONE]）、尖角不贴边（[TIP_FLOOR]）、
 *    墨量对齐（[PLATE_INK]），最后再乘一刀 [SHRINK_EXTRA]。
 *
 * ⚠ 第三轮曾经把"收一刀薄边 + 铺白底"做成**全局**的（主人那句"不要贴着边缘"与"稍微小一点"），
 *   第四轮被"我刚才说的都是针对不规则图标"收回 —— 所以现在**只有裁不动那一档**有白边、才被缩小。
 *   白底画在**前景层的位图里**，绝不动 `AdaptiveIconDrawable` 的背景层：那一层的颜色是给
 *   取色合成器当盘色用的（10-09 定案），换掉它会连带改单色那一支的输出。
 */
private class MaskedDrawable(private val icon: Drawable) : Drawable() {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val maskMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

  /** 圆内的那层白底（见 [draw] 里那一段）。 */
  private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

  /**
   * 位图 / 画布 / 遮罩路径按尺寸缓存，三者一起换。
   *
   * 位图本来就缓存了，但 Canvas 和 Path 每次 draw 都新建 —— 而桌面滚动时每个图标
   * 每帧都要画一次，尺寸根本不会变。省下来的是每帧每图标一次 Canvas + 一次 Path 分配。
   */
  private var cache: Bitmap? = null
  private var cacheCanvas: Canvas? = null
  private var cacheMask: Path? = null

  /** 图案外框（画布比例 0..1，`l,t,r,b`）；null = 量不出来 —— 按整块画布内切。 */
  private var boxMeasured = false
  private var boxFraction: FloatArray? = null

  /**
   * 这张走的是"裁不动 → 不裁 + 圆内补白底"那条路。
   * ⚠ 白底**只给这一类**（主人："我刚才说的都是针对不规则图标"）—— 原本裁得好的图标
   * 恢复原样：内切到圆、填满、圆外透明、没有白边。
   */
  private var plateWhite = false

  override fun draw(canvas: Canvas) {
    val width = bounds.width()
    val height = bounds.height()
    if (width <= 0 || height <= 0) return

    // 尺寸没变就复用这套缓存；内容每次重画（图标可能是会动的，比如时钟）。
    var bitmap = cache
    var layer = cacheCanvas
    var mask = cacheMask
    if (bitmap == null || layer == null || mask == null ||
      bitmap.width != width || bitmap.height != height
    ) {
      bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      layer = Canvas(bitmap)
      mask = circleMaskPath(width, height)
      cache = bitmap
      cacheCanvas = layer
      cacheMask = mask
    }

    bitmap.eraseColor(Color.TRANSPARENT)

    // 图案在哪 / 走哪条路（下面 cover 用）。必须**先问**：多孔那类要先支起白底
    val sizeUnknown = icon.intrinsicWidth <= 0 || icon.intrinsicHeight <= 0
    val intrinsicWidth = if (sizeUnknown) width else icon.intrinsicWidth
    val intrinsicHeight = if (sizeUnknown) height else icon.intrinsicHeight
    val box = contentBox(intrinsicWidth, intrinsicHeight, sizeUnknown)

    // 圆内白底：**只给"裁不动"那类**（[plateWhite]，见 [scanBoxFraction] ③）。它们不裁、
    // 缩进圆里，盖不满的地方补上的就是这层。裁得好的图标一个像素都不铺 —— 铺了就是白边。
    // ⚠ 白底画在**前景层的位图里**，不动 AdaptiveIconDrawable 的背景层：那一层的颜色
    //   是给取色合成器当盘色用的（10-09 定案），换掉它会连带改单色那一支的输出。
    if (plateWhite) layer.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, platePaint)

    // cover：填满 bounds，保持宽高比，多出来的居中裁掉 —— 基准是图案外框
    val boxWidth = (box?.width() ?: intrinsicWidth).coerceAtLeast(1).toFloat()
    val boxHeight = (box?.height() ?: intrinsicHeight).coerceAtLeast(1).toFloat()
    val boxCenterX = box?.exactCenterX() ?: intrinsicWidth / 2f
    val boxCenterY = box?.exactCenterY() ?: intrinsicHeight / 2f

    val scale = maxOf(width / boxWidth, height / boxHeight)
    layer.save()
    layer.translate(width / 2f - boxCenterX * scale, height / 2f - boxCenterY * scale)
    layer.scale(scale, scale)
    icon.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
    icon.draw(layer)
    layer.restore()

    // 遮罩：圆外不透明 → DST_OUT 擦掉圆外，只留圆内
    paint.xfermode = maskMode
    layer.drawPath(mask, paint)
    paint.xfermode = null

    canvas.drawBitmap(bitmap, null, bounds, paint)
  }

  /**
   * 图案实际有像素的外框，换算到图标自身坐标系（= `intrinsicWidth/Height`）。
   *
   * 量法：把图标光栅化到一张封顶 [BOX_SCAN_EDGE] 的小图，扫 alpha 取行列极值。
   * **只量一次**：同一张图的图案外框与画多大无关，所以存成比例、换尺寸时按比例还原
   * （`AdaptiveIconDrawable` 的固有尺寸是 -1，第一次绘制时才知道画布多大，比例正好躲开这一脚）。
   *
   * ⚠ **外框（bbox）不等于"圆能填满的范围"**（2026-10-14 第二轮，主人一句"圆要完整，
   * 中间不要有空隙"）：外框的四个角可能是空的（铃铛/喇叭这类图案上窄下宽），
   * 按外框内切出来的圆边上就会缺一块。所以真正当裁剪窗口的是 [coveredRadius] ——
   * **以图案中心为心、整个落在不透明区里的最大圆盘**（这才是几何意义上的"内切圆"）。
   *
   * 两处保守，都是为了"宁可不动，也别把好图标切坏"：
   *  ① 一行/一列要有 ≥ [BOX_ROW_MIN_PIXELS] 个像素才算数 —— 挡掉孤立噪点把框撑开；
   *  ② 按这个圆裁下去只剩 [KEEP_RATIO] 以下的图案（宽幅 banner、铃铛、十字、环…）→ **不裁**：
   *     整幅缩进圆里，圆内剩下的地方由白底补上（见 [MaskedDrawable.draw]）。任何异常
   *     （画不出来、量出空框、尺寸非正）→ 不动。
   */
  private fun contentBox(iw: Int, ih: Int, canonical: Boolean = false): Rect? {
    if (!boxMeasured) {
      boxMeasured = true
      val t0 = System.nanoTime()
      boxFraction = runCatching { scanBoxFraction(iw, ih, canonical) }.getOrNull()
      // 这张小图是**每个图标多出来的一次绘制**，所以成本要能看见（本仓吃过"只报数不量毫秒"的亏）。
      if (boxLogLeft.decrementAndGet() >= 0) {
        Log.d(TAG, "裁圆外框: 量一张 %.2f ms → %s".format(
          (System.nanoTime() - t0) / 1_000_000.0,
          if (boxFraction == null) "满幅（不放大）" else "有留白",
        ))
      }
    }
    val f = boxFraction ?: return null
    return Rect(
      (f[0] * iw).roundToInt(), (f[1] * ih).roundToInt(),
      (f[2] * iw).roundToInt(), (f[3] * ih).roundToInt(),
    )
  }

  private fun scanBoxFraction(iw: Int, ih: Int, canonical: Boolean): FloatArray? {
    if (iw <= 0 || ih <= 0) return null
    // 固有尺寸未知的（自适应图标）一律量到 [BOX_SCAN_EDGE]：与"这次画多大"解耦
    val shrink = if (canonical) {
      BOX_SCAN_EDGE.toFloat() / maxOf(iw, ih)
    } else {
      minOf(1f, BOX_SCAN_EDGE.toFloat() / maxOf(iw, ih))
    }
    val sw = maxOf(1, (iw * shrink).roundToInt())
    val sh = maxOf(1, (ih * shrink).roundToInt())
    val scratch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    Canvas(scratch).also {
      icon.setBounds(0, 0, sw, sh)
      icon.draw(it)
    }
    val px = IntArray(sw * sh)
    scratch.getPixels(px, 0, sw, 0, 0, sw, sh)
    scratch.recycle()

    // ① 图案在哪（外框）
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

    // ③ 裁下去会丢掉太多形状（宽幅、铃铛、十字、环…）→ **不裁**：缩一缩整幅装进圆里，
    //    圆内剩下的地方由 [draw] 那层白底补上。判据是"圆里还能留下几成图案"，
    //    基准是正方形按圆形裁的 π/4 = 78.5%（所有正常图标的样子）。
    if (keepTotal > 0 && keep < keepTotal * KEEP_RATIO) {
      val ext = extents(px, sw, sh, cx, cy)
      val far = ext[0]
      val body = ext[1]
      if (far < 1f) return null
      plateWhite = true
      // 三条约束取最保守（= 图案最小）的那个：
      //   ① 主体落在平台安全区内（body / SAFE_ZONE）
      //   ② 最远的像素不许贴边（far / TIP_FLOOR）
      //   ③ 墨量对齐（[PLATE_INK]）—— 治"形状不同看着大小不一"
      // ⚠ 取 max 意味着墨量对齐**只能把图案往小里对齐，永远不许把外缘切掉**。这是主人定的口径
      //   （2026-10-14："图案就是得完整的，不能光看占比"）。所以"外缘先顶到圆边"的那几种
      //   （宽幅式、五角星式）**不会为了块头一致而放大** —— 放大就等于裁掉它们的外缘。
      //   代价：它们的墨量永远比实心的小一档。几何决定，不是参数问题（B 案=允许裁外缘，
      //   已评估、被否，离线对照图见 `_harness/_ab2.png`）。
      val rBox3 = maxOf(far / TIP_FLOOR, body / SAFE_ZONE)
      val rInk = kotlin.math.sqrt(keepTotal / (4f * PLATE_INK))
      val rWin = maxOf(rBox3, rInk) * SHRINK_EXTRA
      val l = cx + 0.5f - rWin
      val t = cy + 0.5f - rWin
      val right = cx + 0.5f + rWin
      val bottom = cy + 0.5f + rWin
      val keepPct = (keep * 100f / keepTotal).roundToInt()
      val zoom = maxOf(sw / (right - l), sh / (bottom - t))
      // ⚠ 整条消息一次成型：`"…%.0f…" + "…%.2f…".format(…)` 会把格式符留在前半段、参数全错位
      //   （2026-10-14 真机上打出来就是 `裁了只剩 %.0f%%` + `body/far=50.00 放大 60.00×`）。
      Log.d(TAG, "裁圆外框: 画布 ${iw}x$ih 图案 ${bw}x$bh 裁了只剩 ${keepPct}%" +
        "（<${(KEEP_RATIO * 100).roundToInt()}%）→ 不裁、缩进圆里" +
        " body/far=${"%.2f".format(body / far)} 放大 ${"%.2f".format(zoom)}×，圆内补白")
      return floatArrayOf(l / sw, t / sh, right / sw, bottom / sh)
    }

    if (rCover < 1f) return null
    // ④ 裁得好的（原本就好的）图标：**恢复原样** —— 按不透明圆盘内切、填满圆、圆外透明。
    //    这里不收边、不铺白底：主人要的白边/缩小只针对不规则那批（③）。
    //    ⚠ 图案本来就满幅的（真机 59/61）直接不动（逐位不变）—— 它的内切圆本来就被自己填满，
    //      不需要为"圆要完整"再放大那一两个百分点（那会让整屏图标都悄悄长大）。
    if (bw >= sw * BOX_FULL_RATIO && bh >= sh * BOX_FULL_RATIO) return null
    val rWin = rCover
    val l = cx + 0.5f - rWin
    val t = cy + 0.5f - rWin
    val right = cx + 0.5f + rWin
    val bottom = cy + 0.5f + rWin
    val cZoom = maxOf(sw / (right - l), sh / (bottom - t))
    Log.d(TAG, "裁圆外框: 画布 ${iw}x$ih 图案 ${bw}x$bh（扫描 ${sw}x$sh）裁了还剩 " +
      "${(keep * 100f / keepTotal).roundToInt()}% 外框内切圆 r=${"%.1f".format(rBox)}" +
      " 不透明圆盘 r=${"%.1f".format(rCover)} → 放大 ${"%.2f".format(cZoom)}×")
    return floatArrayOf(l / sw, t / sh, right / sw, bottom / sh)
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
   */
  private fun extents(px: IntArray, w: Int, h: Int, cx: Int, cy: Int): FloatArray {
    var far = 0f
    val hist = IntArray(minOf(w + h, 4096))
    var total = 0
    for (y in 0 until h) for (x in 0 until w) {
      if ((px[y * w + x] ushr 24) < BOX_ALPHA_MIN) continue
      val dx = (x - cx).toFloat()
      val dy = (y - cy).toFloat()
      val r = kotlin.math.sqrt(dx * dx + dy * dy)
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

  override fun setAlpha(alpha: Int) {
    icon.alpha = alpha
    invalidateSelf()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    icon.colorFilter = colorFilter
    invalidateSelf()
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

  override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

  override fun getConstantState(): ConstantState? =
    icon.constantState?.let { src ->
      object : ConstantState() {
        override fun newDrawable(): Drawable = MaskedDrawable(src.newDrawable())

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/**
 * 量图案外框时把图标光栅化到的边长：**两处都用这一个数**。
 *  · 有固有尺寸的（位图/矢量）：量到 `min(固有尺寸, 它)`，不做无意义的放大；
 *  · 固有尺寸未知的（`AdaptiveIconDrawable`）：一律量到它，与"这次画多大"解耦 ——
 *    真机上同一张图标会以 79/95/96/192/283 好几种尺寸被画，量测尺寸跟着变，
 *    "裁完剩几成"这种比例数就会随尺寸飘，判据在边界上瞎切。
 *
 * ⚠ 从 192/256 降到 128（2026-10-14）：这一趟的成本与像素数同阶，而它对每个图标都是
 *   净多出来的（真机 `量一张` 中位 2.64ms、p90 3.88ms）。128 的坐标精度是 1/128 ≈ 0.8%，
 *   对"留 4%~8% 的边"这件事没有可感影响。
 */
private const val BOX_SCAN_EDGE = 128

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
 * （宽幅 banner 只留 ~20%、铃铛 ~57%、十字/环更少），那就别裁：缩一缩装进圆里、圆内补白底。
 */
private const val KEEP_RATIO = 0.6f

/**
 * 白底那一类留多少边 —— 从**平台自己的安全区**推出来的，不是拍脑袋：
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
 * 只把外缘套在同一个圈上，**块头**差得很远（当时量到宽幅 18% / 星形 29% / 圆环 38%），
 * 三个挤在一起看就是大小不一。
 *
 * ⚠ 它**只能把胖的收下来，不能把瘦的放大**（窗口取三条尺子的最大值，见 ③ 那段）——
 *   所以那句"对齐之后它们才是一个视觉尺寸"对宽幅 / 星形那一类**不成立**，它们的墨量就停在下面那一档。
 * ⚠ **真机上这条尺子从没绑过**：装机那几张开火的图标，外缘那条都比它严（16:45 那一版缩量与上一版逐张相同）。
 *   而且上面那组 18/29/38 与 `_circab.cjs` 现在跑出来的那组（20/22/23/50）**不是同一次量测** ——
 *   后者可复现（那 5 个形状是脚本自己生成的），这组 18/29/38 本轮没复现，两组别混用。见 docs/KNOWN-LIMITS.md。
 */
private const val PLATE_INK = 0.5f

/** 外框测量日志只给前 [BOX_LOG_LIMIT] 次（图标加载是热路径，刷一次列表上千次）。 */
private const val BOX_LOG_LIMIT = 200

private val boxLogLeft = AtomicInteger(BOX_LOG_LIMIT)

/**
 * 「圆外不透明、圆内透明」的遮罩。
 *
 * `EVEN_ODD` 下「整块矩形 + 圆」两个同向轮廓重叠处计数为偶 → 成为洞，
 * 于是填充出来的正是圆外的那一圈，配合 `DST_OUT` 恰好把圆外擦掉。
 */
private fun circleMaskPath(width: Int, height: Int) = Path().apply {
  fillType = Path.FillType.EVEN_ODD
  addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
  addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
}

/**
 * 诊断用：所有通道最终都汇到这里，所以在这里打一次日志就能知道**这条通道到底跑没跑**，
 * 以及**是谁在取图标**。
 *
 * 图标加载是热路径（刷一次应用列表上千次），所以：
 * - 每个进程只记前 [CLIP_LOG_LIMIT] 条，之后不再取堆栈；
 * - 只有前 [CLIP_LOG_LIMIT] 次付出 `stackTrace` 的开销。
 */
private const val CLIP_LOG_LIMIT = 40

private const val MODULE_PREFIX = "com.iamcanincan.opticon"

/** 取堆栈本身会压进来的栈帧，不是真正的调用方。 */
private val STACK_NOISE = setOf("java.lang.Thread", "dalvik.system.VMStack")

private val clipLogLeft = AtomicInteger(CLIP_LOG_LIMIT)

/**
 * 已经套过的不重复套。
 *
 * ⚠ **自适应图标也要套遮罩**：它自带的形状是系统 mask 决定的（圆角方 / 水滴），
 * 不是圆。参考项目同样对自适应图标应用遮罩 —— 漏掉它们正是"只有桌面生效"的原因之一。
 *
 * ## 总开关在这里判断
 * 所有替换通道（Resources / APM / 最近任务 / 快捷方式 / 归档应用…）最终都汇到这里，
 * 所以开关只在这一处读就够了 —— 关掉时把原图标原样交回去，图标 id 本身是**换回原始值之后**
 * 才解析的（见 IconHooks 的加载出口），拿到的就是应用原本的图标。
 *
 * 标记该不该打由 system_server 决定，那里是另一条路径；这里只需要保证
 * 「即使被打了标记，关闭开关也不裁」。
 */
fun clipToCircle(icon: Drawable): Drawable {
  // 配置按 TTL 重读，所以界面里关掉开关后很快生效，不用重启进程。
  if (!ModuleRuntime.options().circleEnabled) return icon
  if (clipLogLeft.decrementAndGet() >= 0) {
    // 跳过本模块自己的栈帧，剩下的第一个就是被 hook 的方法、第二个是它的调用方 ——
    // 正是判断"某个页面走哪条通道"需要的信息。
    val frames =
      Thread.currentThread()
        .stackTrace
        .asSequence()
        .filter { !it.className.startsWith(MODULE_PREFIX) && it.className !in STACK_NOISE }
        .take(2)
        .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
    Log.d(TAG, "clip $frames : ${icon.javaClass.simpleName}")
  }
  return if (icon is CircleIconDrawable) icon else CircleIconDrawable(icon)
}
