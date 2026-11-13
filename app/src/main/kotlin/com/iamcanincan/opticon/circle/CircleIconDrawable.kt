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
  /** 原图内容占画布的那块（归一化 0..1）；null = 满幅或量不出来。 */
  private val content: ContentBox? = null,
  /** 这张是**快捷方式**：只用于单色那支的几何判据（`MonochromeSynth.forIcon` 的 shortcut 位）。 */
  private val isShortcut: Boolean = false,
) : AdaptiveIconDrawable(
  // 不要底盘（2026-11-13）：老式图标自己就是整张图，另垫一块颜色等于替应用画了个底。
  // 用透明 ColorDrawable 而不是 null：有的宿主会直接读 `getBackground()`，空着比给个会抛的更安全。
  ColorDrawable(Color.TRANSPARENT),
  // 前景：内容补满可见区，形状由桌面裁
  BareForeground(icon, content),
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
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, content, isShortcut)

      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 前景：先把图层位图里的黑色清掉，再把原图**补满**可见区（图层的 2/3）。
 *
 * `AdaptiveIconDrawable.draw()` 在画两层之前会 `mCanvas.drawColor(Color.BLACK)`；有背景层时
 * 黑被盖住，没有背景层时它就会从图标的透明处露出来。这里用 `CLEAR` 把整张图层清成透明 ——
 * 等价于"这张自适应图标没有底色"，透明处由桌面/壁纸透出来。
 *
 * 几何：Launcher3 把桌面图标按"背景层 + 前景层直接画"的方式渲染（`drawFullBleed=true`），
 * 前景层拿到的 bounds 是**可见区的 1.5 倍**（每边多垫 25%）。原图是给 72dp 可视区画的，
 * 所以这里把 bounds 往里收 `extraInset / (1 + 2 × extraInset)`（本机 = 1/6），
 * 图案正好铺满可见区、与图标槽 1:1 —— 随后由桌面自己的「图标形状」裁一刀。
 *
 * **非自适应图标补满正方形**（主人 2026-11-13）：「填补到完整的正方形」。
 * 老式 PNG 常自带一圈透明留白（图形只占中间一块），直接铺上去会缩在图标槽里。
 * [content] 是原图可见内容的外框（归一化），这里按 `max(1/宽, 1/高)` 等比放大、把内容框
 * 的中心对到图标槽中心 —— 内容铺满整个方块，放不下的部分裁掉（与"填满"是同一件事）。
 * 满幅的图（真机上的多数）`content == null`，这条一刀都不动。
 *
 * （另一条路是宿主直接 `draw()`：那时系统 mask 会把这份铺满可见区的内容再裁一次。）
 */
private class BareForeground(
  private val inner: Drawable,
  private val content: ContentBox? = null,
) : Drawable() {

  /** 补满倍率；≤1 = 不动。上限见 [MAX_FILL]。 */
  private val fill: Float = content?.let {
    val w = (it.r - it.l).coerceAtLeast(0.01f)
    val h = (it.b - it.t).coerceAtLeast(0.01f)
    maxOf(1f / w, 1f / h).coerceAtMost(MAX_FILL)
  } ?: 1f

  override fun draw(canvas: Canvas) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val box = content
    if (box == null || fill <= 1.001f) {
      inner.draw(canvas)
      return
    }
    // 内容框中心 → 图标槽中心：T(p) = 槽中心 + fill × (p − 内容中心)
    val view = inner.bounds
    val cX = view.left + view.width() * (box.l + box.r) / 2f
    val cY = view.top + view.height() * (box.t + box.b) / 2f
    val save = canvas.save()
    canvas.translate(view.exactCenterX(), view.exactCenterY())
    canvas.scale(fill, fill)
    canvas.translate(-cX, -cY)
    inner.draw(canvas)
    canvas.restoreToCount(save)
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
      val box = content
      object : ConstantState() {
        override fun newDrawable(): Drawable = BareForeground(src.newDrawable(), box)

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/** 量"内容占画布多少"用的光栅边长。 */
private const val MEASURE_EDGE = 128

/** 补满倍率上限：再小的内容（<1/6 画布）也不放得更大，否则整张糊掉、也量不准。 */
private const val MAX_FILL = 6f

/** 内容框（归一化 0..1）。 */
data class ContentBox(val l: Float, val t: Float, val r: Float, val b: Float)

/**
 * 量原图**可见内容**的外框（alpha > 8）。满幅（两轴都 ≥97%）或量不出来 → null。
 *
 * 只在图标加载时跑一次。任何一步失败都返回 null（= 不补），与整条包装一样绝不把宿主拖下水。
 */
internal fun measureContent(icon: Drawable): ContentBox? = runCatching {
  val n = MEASURE_EDGE
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
    for (y in 0 until n) {
      val row = y * n
      for (x in 0 until n) {
        if ((px[row + x] ushr 24) <= 8) continue
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
      }
    }
    if (r < 0) return null
    val bw = (r + 1 - l).toFloat() / n
    val bh = (b + 1 - t).toFloat() / n
    // 太小的一块：那不是"留白"，是杂点，补它只会把杂点放大
    if (bw < 0.04f || bh < 0.04f) return null
    // 满幅（真机上的多数）：一个像素都不动
    if (bw >= 0.97f && bh >= 0.97f) return null
    ContentBox(l / n.toFloat(), t / n.toFloat(), (r + 1f) / n, (b + 1f) / n)
  } finally {
    bitmap.recycle()
  }
}.getOrNull()

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
  val content = measureContent(icon)
  noteWrap(from)
  if (wrapLogLeft.decrementAndGet() >= 0) {
    val fill = content?.let {
      val w = (it.r - it.l).coerceAtLeast(0.01f)
      val h = (it.b - it.t).coerceAtLeast(0.01f)
      maxOf(1f / w, 1f / h).coerceAtMost(MAX_FILL)
    } ?: 1f
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} 无底盘 补满=" + "%.2f".format(fill) + "×")
  }
  return CircleIconDrawable(icon, content, from.contains("Shortcut"))
}
