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

class CircleIconDrawable(
  icon: Drawable,
  /**
   * 快捷方式那一路才开：**去掉贴边的那一圈细环**。
   *
   * 真机（2026-10-25）：拼多多的四条动态快捷方式，位图自己在**内切圆半径处**画了一圈
   * 1px 灰环（r=53→54 平均 alpha 10→128），裁成圆之后那圈正好压在圆的边上，看起来像我们
   * 多套了一圈；高德等同批位图在同半径是 42–51 的渐变，没有这个跳变。
   * 判据只看"贴边那一格平均不透明、往里三格就透明"这条**形状关系**，不认颜色；
   * 命中时把遮罩圆收 [EDGE_RING_INSET]（约 1.2%），环整圈切掉，图案本身不动。
   */
  private val trimEdgeRing: Boolean = false,
) :
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
    ViewportDrawable(MaskedDrawable(icon, trimEdgeRing)),
  ) {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  /** 给**要未裁原图**的消费方（通知兜底 / 通知图标解码）：wrapper 画出来是一块圆，拿去压剪影会变成饼。 */
  internal val source: Drawable get() = sourceIcon

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
      // ⚠ `trimEdgeRing` 必须一起带过去：桌面每次 bind 都是 `getConstantState().newDrawable()`，
      //   漏了它副本就退回"白盘 + 细环"那一条（2026-10-25 全量检查抓到）。
      override fun newDrawable(): Drawable =
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, trimEdgeRing)

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
private class MaskedDrawable(
  private val icon: Drawable,
  /** 快捷方式那一路才开：去掉贴边的细环（判据与来历见 [CircleIconDrawable] 的构造参数）。 */
  private val trimEdgeRing: Boolean = false,
) : Drawable() {

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
  /** 当前遮罩用到的内收量（px）；[edgeRing] 在第一次量窗口之后才知道，变了要重做遮罩路径。 */
  private var cacheInset = 0f
  /** 这张图标自带一圈贴边细环（见构造参数 `trimEdgeRing`），遮罩要往里收一点把它切掉。 */
  private var edgeRing = false

  /**
   * 图案窗口（[IconCropWindow] 三条出口里的两条非空者）；null = 满幅或任何"宁可不动"。
   * 存的是**比例**，所以同一张图换画布尺寸不用重量（真机同一张会以 79/95/96/192/283 画好几遍，
   * 跟着尺寸飘的话"裁完剩几成"这种判据会在边界上瞎切）。
   */
  private var boxMeasured = false
  private var boxWin: IconCropWindow.Window? = null

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

    // 贴边细环是**量完窗口才知道的**，而遮罩在上一段就已经建好了 —— 变了就重做一次路径。
    // 只收 [EDGE_RING_INSET]（约 1.2%）：够把 1px 环切掉，图案本身看不出来。
    val inset = if (edgeRing) minOf(width, height) * EDGE_RING_INSET else 0f
    if (cacheInset != inset) {
      mask = circleMaskPath(width, height, inset)
      cacheMask = mask
      cacheInset = inset
    }

    // 圆内白底：**只给"裁不动"那类**（[plateWhite]，见 [IconCropWindow] ③）。它们不裁、
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
   * 图案实际占的那块，换算到图标自身坐标系（= `intrinsicWidth/Height`）。
   *
   * 判据本身在 [IconCropWindow] —— 那里同时是**彩色与染色两条路的唯一来源**，本文件只负责
   * "把图标光栅化到量测格子"和"把日志打出来"。**只量一次**（[boxMeasured]）：同一张图的窗口
   * 与画多大无关，所以存成比例、换尺寸时按比例还原。
   *
   * 两处保守都在 [IconCropWindow] 那侧（行/列门槛、任何异常 → 不动）。这里再兜一层
   * `runCatching`，因为光栅化那一步会抛 —— 宿主的 drawable 可能画不出尺寸。
   */
  private fun contentBox(iw: Int, ih: Int, canonical: Boolean): Rect? {
    if (!boxMeasured) {
      boxMeasured = true
      val t0 = System.nanoTime()
      boxWin = runCatching { measureWindow(iw, ih, canonical) }.getOrNull()
      plateWhite = boxWin?.plate == true
      // 这张小图是**每个图标多出来的一次绘制**，所以成本要能看见（本仓吃过"只报数不量毫秒"的亏）。
      // 这一行也要带包名：它是**唯一一条"这张没动"的读数** —— 不带名字就没法回答
      // "某张在彩色那支到底动没动"（2026-10-14 判元宝时正是卡在这里，只能靠反推）。
      if (boxLogLeft.decrementAndGet() >= 0) {
        Log.d(TAG, "裁圆外框: ${IconNames.of(icon)} 量一张 %.2f ms → %s".format(
          (System.nanoTime() - t0) / 1_000_000.0,
          if (boxWin == null) "满幅（不放大）" else "有留白",
        ) + " 缓存=${IconCropWindow.winHits}/${IconCropWindow.winHits + IconCropWindow.winMisses}")
        // ↑ 尾部那一截是**累计**命中/总量（不是这一条的）。加在最末尾：这两行的前面每个字段
        //   都是装机后的唯一读数，位置一个不许动。没这一截的话"缓存到底有没有活"只能靠猜。
      }
    }
    val win = boxWin ?: return null
    return Rect(
      (win.l * iw).roundToInt(), (win.t * ih).roundToInt(),
      (win.r * iw).roundToInt(), (win.b * ih).roundToInt(),
    )
  }

  /**
   * 光栅化到 [IconCropWindow.scanEdge] 那张格子，交给共用判据，然后把落到哪一档打进日志。
   *
   * ⚠ 这两行日志的**格式是装机后的唯一读数**（README 记的排查路径、以及"哪一档开火几张"那本
   *   语料账都按字段 grep），所以字段一个都不能少、也不能改名。
   * ⚠ 整条消息一次成型：`"…%.0f…" + "…%.2f…".format(…)` 会把格式符留在前半段、参数全错位
   *   （2026-10-14 真机上打出来就是 `裁了只剩 %.0f%%` + `body/far=50.00 放大 60.00×`）。
   */
  private fun measureWindow(iw: Int, ih: Int, canonical: Boolean): IconCropWindow.Window? {
    if (iw <= 0 || ih <= 0) return null
    // 固有尺寸未知的（自适应图标）一律量到那个边长：与"这次画多大"解耦。
    // ⚠ 这套格子现在由 [IconCropWindow.scanSize] **单一定义**：染色那一支的**极性**也读它
    //   （`MonochromeSynth.colourWindow`，2026-10-21 两本账合成一本），这里再抄一遍就是两个数。
    val (sw, sh) = IconCropWindow.scanSize(iw, ih, canonical)
    val scratch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    Canvas(scratch).also {
      icon.setBounds(0, 0, sw, sh)
      icon.draw(it)
    }
    val px = IntArray(sw * sh)
    scratch.getPixels(px, 0, sw, 0, 0, sw, sh)
    scratch.recycle()
    if (trimEdgeRing) {
      edgeRing = hasEdgeRing(px, sw, sh)
      if (edgeRing) Log.d(TAG, "裁圆：去掉贴边细环 ${IconNames.of(icon)}（扫描 ${sw}x${sh}）")
    }
    val win = IconCropWindow.of(px, sw, sh) ?: return null
    // 快捷方式里"透明底 + 一个图案"的那一类（拼多多的四条动态快捷方式就是）没有实心内容可裁：
    // `keepPct=0` 会让共用判据落进"不规则图标"那一档，进而**缩小 + 圆内补白** —— 屏幕上就是
    // 图案外面多了一整块白盘（主人 2026-10-25：「拼多多的快捷方式比别的图标多套了一圈」）。
    // 这一档对快捷方式直接**按满幅**画：不缩、不补白，图案保持它自己的比例；位图自带的贴边
    // 细环由 [trimEdgeRing] 那一刀在遮罩上切掉。
    // ⚠ 第一版对所有 `keepPct=0` 的快捷方式都按满幅 → **有些图案被圆切掉了**（主人 2026-10-25）。
    //   现在放行条件多两条：① 位图确认有那圈贴边细环；② **把环带排除之后，图案自己落在圆内**
    //   （最远不透明格 ≤ 0.95R）—— 这一条保证"不缩"不会裁到任何东西。图案碰到边的一律退回
    //   原来的"缩小 + 圆内补白"。
    if (trimEdgeRing && edgeRing && win.plate && win.keepPct == 0) {
      val n = minOf(sw, sh)
      val r = n / 2f
      val cx = (sw - 1) / 2f
      val cy = (sh - 1) / 2f
      val skip2 = (r - 3f) * (r - 3f)          // 环带（R-3..R）不计入图案
      val fit2 = (r * 0.95f) * (r * 0.95f)
      var max2 = 0f
      for (y in 0 until sh) for (x in 0 until sw) {
        if (((px[y * sw + x] ushr 24) and 0xff) < 64) continue
        val dx = x - cx
        val dy = y - cy
        val d2 = dx * dx + dy * dy
        if (d2 >= skip2) continue
        if (d2 > max2) max2 = d2
      }
      if (max2 <= fit2) {
        Log.d(TAG, "裁圆外框: ${IconNames.of(icon)} 快捷方式：细环 + 图案在圆内 → 按满幅：不缩、不补白")
        return null
      }
      Log.d(TAG, "裁圆外框: ${IconNames.of(icon)} 快捷方式：图案碰到圆外 → 保留缩小+补白")
    }
    // 带包名：这两行原本只有尺寸，装机上**没法把"彩色对某张做了什么"和"染色对同一张做了什么"
    // 对起来** —— 2026-10-14 这一轮三次判错都卡在这个洞上（单色那行有包名，这两行没有）。
    val who = IconNames.of(icon)
    // ⚠ 方向词按**数**给，不按档位给（2026-10-16 全量检查）：这两行以前**无论 zoom 是多少都印"放大"**，
    //   真机就打出过 `放大 0.57×`（明明是缩）。plate 档的 zoom 可以 <1 也可以 >1，档位不是方向。
    val dir = if (win.zoom < 1f) "缩" else "放"
    if (win.plate) {
      // ⚠ `入口=` 这一位是 2026-10-16 补的：plate 档有**两条语义完全不同的入口**
      //   （keep = "圆会咬掉一大块形状，这块大色块是要整块保住的图案"；spiky = 纯几何"圆盘比外框小太多"），
      //   而这一行原本**两条都印同一句 `（<60%）`** —— kodi 真机打出来就是 `裁了只剩 72%（<60%）`，
      //   72 明明不小于 60。于是日志读起来像"这张没过线"，其实它是走 spiky 进来的，
      //   而染色侧的极性只认 keep。**"哪条入口 fired"在日志里根本读不出来** ——
      //   回归台空跑 + 日志说谎，Kodi 那一刀就是这样躺了 19 小时。
      // ⚠ **两条不是互斥的**（装机第一读就抓到）：`mipush 剩 32%`、`easytier 剩 0%` 两张
      //   **既满足 keep 又满足 spiky**，第一版把 `入口=` 写成"spiky 谓词成不成立"，于是它们被打成
      //   `入口=spiky` 并把那句真话 `（<60%）` 也一起藏了 —— 换了一个谎。
      // ⚠ 这里读 `win.byKeep`（**未取整**的分支原值），不再用 `keepPct < 60` 反推 ——
      //   `keepPct` 是四舍五入过的，`[59.5,60)` 那批会被印成"没过线"而分支其实走的 keep。
      val byKeep = win.byKeep
      val why = if (byKeep) "（<${IconCropWindow.keepRatioPercent}%）" else ""
      val entry = if (win.spiky && byKeep) "keep+spiky" else if (win.spiky) "spiky" else "keep"
      // `（扫描 WxH）` 以前**只在非 plate 分支打** —— 偏偏最需要拿它核对"两边量的是不是同一格"的
      //   就是 plate 这一档（彩色量 draw() 那张、染色量喂进模型那张，两个 keepPct 不是一回事）。
      Log.d(TAG, "裁圆外框: $who 画布 ${iw}x$ih 图案 ${win.boxW}x${win.boxH}（扫描 ${win.scanW}x${win.scanH}）" +
        " 裁了只剩 ${win.keepPct}%$why → 不裁、缩进圆里" +
        " body/far=${"%.2f".format(win.bodyOverFar)} $dir ${"%.2f".format(win.zoom)}×，圆内补白 入口=$entry")
    } else {
      Log.d(TAG, "裁圆外框: $who 画布 ${iw}x$ih 图案 ${win.boxW}x${win.boxH}（扫描 ${win.scanW}x${win.scanH}）裁了还剩 " +
        "${win.keepPct}% 外框内切圆 r=${"%.1f".format(win.rBox)}" +
        " 不透明圆盘 r=${"%.1f".format(win.rCover)} → $dir ${"%.2f".format(win.zoom)}×")
    }
    return win
  }

  /**
   * **贴边细环**：内切圆半径处一圈明显不透明、再往里三格就透明。
   *
   * 只认这条形状关系，不认颜色：拼多多的四条动态快捷方式位图是透明底 + 红气泡，额外在
   * 内切圆半径处画了一圈 1px 灰环（扫描图上 r=53→54 平均 alpha 10→128）；高德等同批位图
   * 在同一半径是 42–51 的渐变，没有跳变，不会被误判。
   */
  private fun hasEdgeRing(px: IntArray, sw: Int, sh: Int): Boolean {
    val n = minOf(sw, sh)
    if (n < 24) return false
    val cx = (sw - 1) / 2f
    val cy = (sh - 1) / 2f
    val rOut = n / 2f - 1f
    var outer = 0L
    var inner = 0L
    var cnt = 0
    for (i in 0 until 360) {
      val t = Math.toRadians(i.toDouble())
      val c = Math.cos(t).toFloat()
      val s = Math.sin(t).toFloat()
      val xo = (cx + rOut * c).toInt(); val yo = (cy + rOut * s).toInt()
      val xi = (cx + (rOut - 4f) * c).toInt(); val yi = (cy + (rOut - 4f) * s).toInt()
      if (xo in 0 until sw && yo in 0 until sh) outer += (px[yo * sw + xo] ushr 24) and 0xff
      if (xi in 0 until sw && yi in 0 until sh) inner += (px[yi * sw + xi] ushr 24) and 0xff
      cnt++
    }
    return outer / cnt >= 100 && inner / cnt <= 30
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

/** 外框测量日志只给前 [BOX_LOG_LIMIT] 次（图标加载是热路径，刷一次列表上千次）。 */
private const val BOX_LOG_LIMIT = 200

private val boxLogLeft = AtomicInteger(BOX_LOG_LIMIT)

/**
 * 「圆外不透明、圆内透明」的遮罩。
 *
 * `EVEN_ODD` 下「整块矩形 + 圆」两个同向轮廓重叠处计数为偶 → 成为洞，
 * 于是填充出来的正是圆外的那一圈，配合 `DST_OUT` 恰好把圆外擦掉。
 */
private fun circleMaskPath(width: Int, height: Int, inset: Float = 0f) = Path().apply {
  fillType = Path.FillType.EVEN_ODD
  addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
  addCircle(width / 2f, height / 2f,
    (minOf(width, height) / 2f - inset).coerceAtLeast(0f), Path.Direction.CW)
}

/**
 * 贴边细环那一刀把遮罩圆收多少（短边占比）。
 *
 * 实测这圈环在半径上的分布（拼多多 108 档，720 点平均 alpha）：0.963R≈11、0.972R≈46、
 * 0.977R≈77、0.981R≈128、0.991R≈194 —— 可见质量从 0.97R 才开始。遮罩半径 = (1 − 2×这个值)R，
 * 所以 **1.2% 只到 0.976R，还会留下 alpha≈77 的一条**；2% 到 0.96R（那里 alpha≈11，与透明底同级），
 * 整圈才算真去掉。图案最远 0.58R，收 2% 碰不到它。
 */
private const val EDGE_RING_INSET = 0.02f

/**
 * 诊断用：所有通道最终都汇到这里，所以在这里打一次日志就能知道**这条通道到底跑没跑、在哪个宿主里跑**。
 *
 * ⚠ **通道名由调用点交进来，不再取堆栈**（2026-10-17 改）。原来的写法是
 * "滤掉 `com.iamcanincan.opticon` 前缀的栈帧，剩下前两帧就是调用方" —— 而 **release 包里 R8
 * 把我们的类名改成了 `he.a` / `dz` 这种短名**，前缀一条都滤不掉，于是真机 131 行追踪
 * 全是自家帧（`he.a <- dz.intercept : AdaptiveIconDrawable`），本该由它回答的
 * "SystemUI 的哪个页面走哪条通道"**根本读不出来**。这次能定位到磁贴图层那一案，
 * 靠的是另一行 `放行(未标记)` 恰好带着资源名 —— 是运气，不是仪器。
 * ⚪ 顺带把 `Thread.stackTrace` 从热路径上摘掉（图标加载一次刷新上千次，取栈不是免费的）。
 *
 * 图标加载是热路径，所以每个进程只记前 [CLIP_LOG_LIMIT] 条。
 */
private const val CLIP_LOG_LIMIT = 40

private val clipLogLeft = AtomicInteger(CLIP_LOG_LIMIT)

/** 宿主包名，装配时置一次；只为这一行日志服务。 */
@Volatile
var clipHost: String = "?"

/**
 * 射程（**2026-10-21 主人定：「裁圆算法应只排除快速设置的磁贴，别的应做到全覆盖」**）：
 * **默认全裁，只排快速设置那一路**（宿主名只用于日志，见上面那行 [clipHost]）。
 *
 * ⚠ **这一条把原来的"白名单宿主"整个翻过来了。** 旧设计是 fail-closed：`CROP_HOSTS` 名单外
 *   整个进程不裁，只有 SystemUI 按调用方放行通知行那几次（`_notifyguard.cjs` 守的就是那条前缀）。
 *   翻过来的理由不是"旧设计错了"，是它的**代价不对称**：漏一户 = 屏幕上有个方的，主人一句
 *   「别的应做到全覆盖」就把判据定死了；而多一户 = 某个界面里本来方的图变圆，看得见、说得清、
 *   可以点名排除 —— 所以下面那几行"谁被挡了"的账是必需的，不是啰嗦。
 * 🔴 **代价写在明处**：作用域内的**每一个进程**现在都裁（第三方 app 里显示 app 图标的那些界面也是）。
 *   哪一户裁坏了，拿 `裁圆` 那行日志的 `host=` 点名，往 [TILE_CALLERS] 那族旁边加排除，
 *   **不要回去改默认值**。
 */
/**
 * 快速设置那一路的**调用方前缀**（不是通道名 —— 通道名分不开，见 [calledByQuickSettings]）。
 * 只有一条，但它是**扫出来的**而不是猜的：2026-10-21 从这台机器的 `SystemUI.apk` 三个 dex 里
 * 数过命名空间（`node _qsfind.cjs D:/tmp/systemui.apk`）—— `qs.tiles` 217、`qs.tiles.dialog` 90
 * （= 主人点的"预览界面"那一页）、`qs.panels.*`、`qs.pipeline.*`、`qs.external` 59（第三方磁贴的宿主）
 * **全都在 `com.android.systemui.qs.` 底下**，所以一条前缀盖住整族。
 * ⚠ 第三方磁贴的 `TileService` 如果在**它自己进程**里取图标，这一条看不见（那边不读堆栈，见
 *   [calledByQuickSettings] 那行的成本说明）—— 那种现象出现时先加日志再谈规则，不要提前扩面。
 */
private const val TILE_CALLERS = "com.android.systemui.qs."

/** 只在 SystemUI 里读堆栈：磁贴界面只可能由它画，别的过程读了白读（`stackTrace` 一次是微秒级，
 *  而这处每个图标都要过一次 —— 本仓为"只报数的列也要量 ms/张"撤回过一整把尺）。 */
private const val SYSTEMUI_HOST = "com.android.systemui"

/**
 * 「这一次是不是快速设置在取图标」。
 *
 * 为什么必须按**调用方**分，而不是按通道（2026-10-17 从这台机器的 framework dex 里读出来的）：
 * 通知行的应用图标是 `statusbar.notification.row.icon.AppIconProviderImpl.fetchAppIconBitmapInfo`
 * → `ApplicationInfo.loadUnbadgedIcon` → `PackageManager.loadUnbadgedItemIcon` → `APM.getDrawable`；
 * 而磁贴走 `loadItemIcon`，**它内部第一件事就是回 `loadUnbadgedItemIcon`**（dex `1f6df0`）——
 * 两条路在框架里**汇到同一个出口**，通道名分不开它们，只有"是谁调的"分得开。
 */
private fun calledByQuickSettings(): Boolean {
  if (clipHost != SYSTEMUI_HOST) return false
  return Thread.currentThread().stackTrace.any { it.className.startsWith(TILE_CALLERS) }
}

/**
 * 挡下一次时进账。**"没挡下"与"没东西可挡"是两件事**：这道判据失效（AOSP 改了类名、
 * OTA 把 `qs.` 挪走）的现象正是"磁贴被裁了"，所以既要有挡下的账，也要在收尾时报
 * "这一轮 SystemUI 里裁了几张、挡了几次"（见 [noteTileSkip] 与 `IconHooks` 的收尾汇总）。
 */
private fun noteTileSkip(from: String) {
  val key = "$from ← 磁贴(按调用方挡住)"
  skipTally.merge(key, 1) { a, b -> a + b }
  if (skipTally[key] == 1) Log.d(TAG, "裁圆按磁贴挡下 host=$clipHost：$key")
}

/**
 * **射程的账**（2026-10-21 随"默认全裁"改版）。
 *
 * 为什么方向反了：旧账记的是"**挡住谁**"（白名单时代，名单外全被挡，得留下"该不该放它进来"的原始数据）。
 * 默认全裁之后，坏的一户的现象是"某个界面的图本来方的是圆的"，要回答的是**"到底哪些界面开始裁了"** ——
 * 所以这本账记 `通道 × 宿主` 的第一次与总量。没有它，"全覆盖"这三个字就只能靠信。
 * ⚠ 每个「通道×宿主」**第一次**必打一行（低量宿主攒够 N 次才打的写法在这仓里翻过车：
 *   SystemUI 那一轮就是 0 行）；总账每 [RANGE_DUMP_EVERY] 次打一行，最多 [RANGE_DUMP_MAX] 行。
 * 成本：一次 `ConcurrentHashMap.merge`，**不取堆栈**（堆栈只在 SystemUI 那一判里读一次）。
 */
private const val RANGE_DUMP_EVERY = 200L
private const val RANGE_DUMP_MAX = 20L
private val skipTally = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val cropTally = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val cropCount = java.util.concurrent.atomic.AtomicLong(0)
private val cropDumps = java.util.concurrent.atomic.AtomicLong(0)

private fun noteCrop(from: String) {
  val key = "$from ← $clipHost"
  cropTally.merge(key, 1) { a, b -> a + b }
  if (cropTally[key] == 1) Log.d(TAG, "裁圆新开 host=$clipHost 通道=$from（第一次）")
  val n = cropCount.incrementAndGet()
  if (n % RANGE_DUMP_EVERY == 0L && cropDumps.getAndIncrement() < RANGE_DUMP_MAX) {
    val rows = cropTally.entries.sortedByDescending { it.value }.take(8)
      .joinToString(" ; ") { "${it.key} ×${it.value}" }
    Log.d(TAG, "裁圆累计 $n 次，通道×宿主 top：$rows")
  }
}

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
 *
 * [from] = 哪条通道把这张图交进来的（字符串在调用点写死，见上面那条 R8 的账）。
 */
/**
 * 取回**没裁过**的原图（不是 [CircleIconDrawable] 就原样返回）。
 *
 * 给通知那两条消费方用：SystemUI 里 `PackageManager.getApplicationIcon` 与
 * `Resources.getDrawable*` 也会被裁圆钩子包起来（真机日志
 * `clip from=PM.getApplicationIcon host=com.android.systemui`），而通知的剪影必须从
 * **原图**的 alpha 取 —— 拿裁过的圆去压，整张剪影会变成一块实心饼。
 */
internal fun uncroppedIcon(d: Drawable): Drawable =
  if (d is CircleIconDrawable) d.source else d

fun clipToCircle(icon: Drawable, from: String): Drawable {
  // 配置按 TTL 重读，所以界面里关掉开关后很快生效，不用重启进程。
  if (!ModuleRuntime.options().circleEnabled) return icon
  // 射程（2026-10-21 起）：**默认全裁，唯一的排除是快速设置那一路**。
  // 磁贴与通知行在框架里汇到同一个出口，只有调用方分得开（见 [calledByQuickSettings]）。
  if (calledByQuickSettings()) {
    noteTileSkip(from)
    return icon
  }
  noteCrop(from)
  if (clipLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "clip from=$from host=$clipHost : ${icon.javaClass.simpleName} " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight}")
  }
  return if (icon is CircleIconDrawable) icon
  else CircleIconDrawable(icon, trimEdgeRing = from.contains("Shortcut"))
}
