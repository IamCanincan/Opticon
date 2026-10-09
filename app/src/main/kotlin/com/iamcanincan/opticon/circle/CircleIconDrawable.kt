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
 */
private class MaskedDrawable(private val icon: Drawable) : Drawable() {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val maskMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

  /**
   * 位图 / 画布 / 遮罩路径按尺寸缓存，三者一起换。
   *
   * 位图本来就缓存了，但 Canvas 和 Path 每次 draw 都新建 —— 而桌面滚动时每个图标
   * 每帧都要画一次，尺寸根本不会变。省下来的是每帧每图标一次 Canvas + 一次 Path 分配。
   */
  private var cache: Bitmap? = null
  private var cacheCanvas: Canvas? = null
  private var cacheMask: Path? = null

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

    val intrinsicWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: width
    val intrinsicHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: height

    // cover：填满 bounds，保持宽高比，多出来的居中裁掉
    val scale = maxOf(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight)
    layer.save()
    layer.translate(
      width / 2f - intrinsicWidth * scale / 2f,
      height / 2f - intrinsicHeight * scale / 2f,
    )
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
