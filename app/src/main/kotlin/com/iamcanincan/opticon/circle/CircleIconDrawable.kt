package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
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
import kotlin.math.min

/**
 * 把**非自适应（老式）图标**做成自适应图标：一块底盘 + 原图缩到安全区，形状交给系统。
 *
 * ## 为什么不再自己裁圆（2026-11-13 主人定）
 * 原来的做法是给每个图标 `DST_OUT` 一个圆、再绕开系统 mask 直接画前景 —— 桌面上得到一个圆，
 * 但那是**我们自己的几何**：应用信息页、最近任务、系统自己的图标解析各画各的，同一个应用
 * 在三处不一样；老式图标还会被放大到填满圆（ADB 的绿机器人顶到边上）。
 * 平台本来就有对老式图标的处理（Launcher3 / Lawndesk 的
 * `LauncherIcons.normalizeAndWrapToAdaptiveIcon` + `FixedScaleDrawable`）：套一个自适应壳，
 * 图案缩到安全区、底盘补齐，再由系统按用户选的形状裁。现在照这一套做：
 *  · 背景：图标自己边上的实色（[LegacyWrap] 的 `plate`）；
 *  · 前景：原图按 `0.7 × 0.6667 × 归一` 缩放（[ScaledForeground]）；
 *  · 形状：`super.draw()` 走系统的 mask，桌面 / 应用信息页 / 最近任务从此同一份。
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
  /** 从原图量出来的三件事：归一倍率、底盘色、宽高比（[LegacyWrap.measure]）。 */
  private val metrics: LegacyWrap.Metrics,
  /** 这张是**快捷方式**：只用于单色那支的几何判据（与彩色那支同源）。 */
  private val isShortcut: Boolean = false,
) : AdaptiveIconDrawable(
  // 底盘：图标自己边上的实色。它铺满画布时与自己的底同色，缩下去不会凭空多出一圈白边。
  ColorDrawable(metrics.plate),
  // 前景：原图缩到安全区
  ScaledForeground(icon, metrics),
) {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  /** 给**要未包装原图**的消费方（通知兜底 / 通知图标解码）：包装画出来是"底盘 + 小图"。 */
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
 * 把原图按 `0.7 × 0.6667 × 归一` 画进前景层。
 *
 * `AdaptiveIconDrawable` 交给前景层的 bounds 是**可见区的 1.5 倍**（每边多垫 25%），
 * 平台 `FixedScaleDrawable` 就是在这个 bounds 上乘 `LEGACY_ICON_SCALE` 的 —— 所以这里
 * 直接照做，屏幕上图案直径正好是可见圆的 `0.7 × 归一`。
 *
 * 宽高比：平台只在非正方形时补一刀（`setScale` 里那段），这里按 `aspect` 把短边等比收，
 * 效果相同且不会把图案拉扁（前景层的 bounds 总是正方形）。
 */
private class ScaledForeground(
  private val inner: Drawable,
  private val metrics: LegacyWrap.Metrics,
) : Drawable() {

  private val scaleX: Float
  private val scaleY: Float

  init {
    val base = LegacyWrap.SCALE_BASE * metrics.scale
    val aspect = if (metrics.aspect > 0f) metrics.aspect else 1f
    if (aspect > 1f) {
      scaleX = base
      scaleY = base / aspect
    } else {
      scaleY = base
      scaleX = base * aspect
    }
  }

  override fun draw(canvas: Canvas) {
    if (bounds.isEmpty) return
    val save = canvas.save()
    canvas.scale(scaleX, scaleY, bounds.exactCenterX(), bounds.exactCenterY())
    inner.draw(canvas)
    canvas.restoreToCount(save)
  }

  override fun onBoundsChange(bounds: Rect) {
    inner.setBounds(bounds)
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

  /** 副本用同一份倍率 —— 倍率只由原图决定，不随 bounds 变。 */
  override fun getConstantState(): ConstantState? =
    inner.constantState?.let { src ->
      val m = metrics
      object : ConstantState() {
        override fun newDrawable(): Drawable = ScaledForeground(src.newDrawable(), m)

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/** 量测原图用的光栅边长：与 `IconCropWindow.scanEdge` 同一量级，缩一次就够。 */
private const val MEASURE_EDGE = 128

/**
 * 把原图光栅化到一张小画布上，量出 [LegacyWrap.Metrics]。
 *
 * 只在图标**加载时**跑一次。任何一步失败 → null，调用方原样放行 —— 这条路径挂在宿主的
 * 取图热路径上，抛出去就是宿主崩。
 */
internal fun measureLegacy(icon: Drawable): LegacyWrap.Metrics? = runCatching {
  val iw = if (icon.intrinsicWidth > 0) icon.intrinsicWidth else MEASURE_EDGE
  val ih = if (icon.intrinsicHeight > 0) icon.intrinsicHeight else MEASURE_EDGE
  val shrink = min(1f, MEASURE_EDGE.toFloat() / maxOf(iw, ih))
  val sw = maxOf(1, (iw * shrink).toInt())
  val sh = maxOf(1, (ih * shrink).toInt())
  val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
  try {
    Canvas(bitmap).also {
      icon.setBounds(0, 0, sw, sh)
      icon.draw(it)
    }
    val px = IntArray(sw * sh)
    bitmap.getPixels(px, 0, sw, 0, 0, sw, sh)
    LegacyWrap.measure(px, sw, sh)
  } finally {
    bitmap.recycle()
  }
}.getOrNull()

/**
 * 取回**没包装过**的原图（不是 [CircleIconDrawable] 就原样返回）。
 *
 * 给通知那两条消费方用：SystemUI 里 `PackageManager.getApplicationIcon` 与
 * `Resources.getDrawable*` 也会被这套钩子包起来，而通知的剪影必须从**原图**的 alpha 取 ——
 * 拿"底盘 + 小图"去压，剪影会变成一块带小图的饼。
 */
internal fun unwrappedIcon(d: Drawable): Drawable =
  if (d is CircleIconDrawable) d.source else d

/** 「包装自身抛了」只报一次（每张图标一行会把日志刷满）。 */
private val clipFailLogged = AtomicBoolean(false)

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
 * 数过命名空间（`node _qsfind.cjs D:/tmp/systemui.apk`）—— `qs.tiles` 217、`qs.tiles.dialog` 90
 * （= 主人点的"预览界面"那一页）、`qs.panels.*`、`qs.pipeline.*`、`qs.external` 59（第三方磁贴的宿主）
 * **全都在 `com.android.systemui.qs.` 底下**，所以一条前缀盖住整族。
 * ⚠ 第三方磁贴的 `TileService` 如果在**它自己进程**里取图标，这一条看不见 —— 那种现象出现时
 *   先加日志再谈规则，不要提前扩面。
 */
private const val TILE_CALLERS = "com.android.systemui.qs."

/** 只在 SystemUI 里读堆栈：磁贴界面只可能由它画，别的过程读了白读。 */
private const val SYSTEMUI_HOST = "com.android.systemui"

/**
 * 「这一次是不是快速设置在取图标」。
 *
 * 为什么必须按**调用方**分，而不是按通道（2026-10-17 从这台机器的 framework dex 里读出来的）：
 * 通知行的应用图标是 `statusbar.notification.row.icon.AppIconProviderImpl.fetchAppIconBitmapInfo`
 * → `ApplicationInfo.loadUnbadgedIcon` → `PackageManager.loadUnbadgedItemIcon` → `APM.getDrawable`；
 * 而磁贴走 `loadItemIcon`，**它内部第一件事就是回 `loadUnbadgedItemIcon`** —— 两条路在框架里
 * **汇到同一个出口**，通道名分不开它们，只有"是谁调的"分得开。
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

/** 每一张"包了"的图标一条：归一倍率、图案直径、盘色、尺寸。前 [WRAP_LOG_LIMIT] 条。 */
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
  // 已经包过的、以及**自适应图标**：都不动。自适应图标自带背景 + 前景，形状由系统 mask 定；
  // 我们再去包一层只会把它的安全区再缩一圈。
  if (icon is CircleIconDrawable || icon is AdaptiveIconDrawable) return icon

  val metrics = measureLegacy(icon) ?: return icon
  // 形状本来就等于遮罩（自带完整圆盘）：平台画它已经是对的，再包只会把它缩到 64% 并多一圈底盘。
  if (metrics.maskShaped) return icon

  noteWrap(from)
  if (wrapLogLeft.decrementAndGet() >= 0) {
    val normal = "%.2f".format(metrics.scale)
    val shown = "%.2f".format(LegacyWrap.SCALE_BASE * metrics.scale)
    val plate = "#%06X".format(metrics.plate and 0xFFFFFF)
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} 归一=" + normal + " 图案=" + shown + "×圆 盘=" + plate)
  }
  // ⚠ 只认"通道是快捷方式"这一条（`from` 里带 Shortcut），**不要**再加 `pm:包名/id` 那条标签判据：
  //   这个位只喂给单色合成（`MonochromeSynth` 的几何），而那边的标定用的是 `trimEdgeRing` 那份口径。
  val shortcut = from.contains("Shortcut")
  return CircleIconDrawable(icon, metrics, shortcut)
}
