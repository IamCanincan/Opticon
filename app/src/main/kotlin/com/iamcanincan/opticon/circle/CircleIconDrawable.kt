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
 * 同一张图标的所有副本共享的渲染结果（按 尺寸 + alpha 失效）。
 *
 * 宿主的 bind 会走 `getConstantState().newDrawable()` 每次建一个新副本；不共享的话
 * 每份副本各持一张 256² 位图（≈256 KB）并各渲一遍。共享之后一张图标只留一份。
 */
class RenderCache {
  var bitmap: Bitmap? = null
  var canvas: Canvas? = null
  var w = 0
  var h = 0
  var alpha = 255

  fun matches(w: Int, h: Int, a: Int): Boolean =
    bitmap != null && this.w == w && this.h == h && alpha == a

  fun reset() {
    bitmap = null
    canvas = null
    w = 0
    h = 0
  }
}

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
 *  · 尺寸：**原图原尺寸**放进去，不放大、不裁切（主人 2026-11-13：「有的图标压根没必要缩」
 *    与「过度裁切」两次点名）；透明的地方一律由下面的边角延伸补满 —— **快捷方式除外**：
 *    它们**只画自己的图**：不铺边、不补盘、不缩放，透明处就是壁纸（主人 2026-11-14 三轮收敛，
 *    见 [BareForeground.renderShortcut]）；
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
  /** 这张是**快捷方式**：单色那支的几何判据（`MonochromeSynth.forIcon`）。只认 LauncherApps 通道。 */
  private val isShortcut: Boolean = false,
  /**
   * 前景按**快捷方式的外观**画：只画自己的图，不铺边、不补盘、不缩放（见 [BareForeground.renderShortcut]）。
   * 默认跟随 [isShortcut]；走 Resources 通道出来的那批快捷方式只置这一位（[ShortcutLoad]），
   * 单色几何仍按应用图标算 —— 那一支是逐张调过的，不跟着通道猜测改口径。
   */
  private val shortcutLook: Boolean = isShortcut,
  /** 所有副本共享的渲染缓存（[getConstantState] 的副本会拿到同一个）。 */
  private val sharedCache: RenderCache = RenderCache(),
) : AdaptiveIconDrawable(
  // 不要底盘（2026-11-13）：老式图标自己就是整张图，另垫一块颜色等于替应用画了个底。
  // 用透明 ColorDrawable 而不是 null：有的宿主会直接读 `getBackground()`，空着比给个会抛的更安全。
  ColorDrawable(Color.TRANSPARENT),
  // 前景：内容补满可见区，形状由桌面裁；所有副本共享同一份渲染缓存
  BareForeground(icon, shortcutLook, sharedCache),
) {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  /** [getConstantState] 只建一次：每次 bind 都新建一个 state 的话，副本之间就共享不到缓存了。 */
  private var sharedState: ConstantState? = null

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
    return sharedState ?: object : ConstantState() {
      override fun newDrawable(): Drawable =
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, isShortcut, shortcutLook, sharedCache)

      override fun getChangingConfigurations(): Int = changingConfigurations
    }.also { sharedState = it }
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
 * **不缩放、不裁切**：原图按原尺寸铺上去（主人要求「过度放大 / 过度裁切」都不能有），
 * 只有透明的地方交给下面的边角延伸补满 —— 所以图案保持应用自己设计的比例。
 *
 * **边角延伸**（同日第二句：「边角透明的地方给它延伸完全」）：把图标光栅化到图标槽大小，
 * 从**画布边界**找出与外面连通的透明区域（内部镂空不动），**行向 + 列向各取一条边色再平均**
 * 铺进去（极角上两个方向都没有的小三角，用 BFS 从最近的已给色格拓）—— 圆角/圆盘图标那
 * 四个透明角从此是它自己的边缘色，不再是壁纸。铺完还往图标里垫 2 格，第二遍把图标叠在
 * 底色上画，抗锯齿边与底色混合，不会留下一条半透明的浅缝。
 * 每一张老式图标都过这一步（渲染结果按尺寸档位缓存，不会每帧重算）。
 */
/** 「带堆栈的第一条」只打一次（进程级）。 */
private val failStackLogged = java.util.concurrent.atomic.AtomicBoolean(false)

private class BareForeground(
  private val inner: Drawable,
  /** 快捷方式：不铺边，有底保持原样 / 没底补白或黑盘（见 [renderShortcut]）。 */
  private val shortcut: Boolean,
  /** 共享渲染缓存（同一张图标的所有副本一份）。 */
  private val shared: RenderCache = RenderCache(),
) : Drawable(), Drawable.Callback {

  /** 设了 colorFilter 时不走共享缓存（滤镜是每个实例自己的）。 */
  private var hasFilter = false
  private var local: RenderCache? = null
  private val cache: RenderCache
    get() = if (hasFilter) (local ?: RenderCache().also { local = it }) else shared

  init {
    // 源图标自己 invalidate（会动的图标、状态变化）时把缓存作废，否则会冻在第一帧。
    inner.callback = this
  }

  /** 正在往 Shared/RenderCache 里画 —— 这期间源图标 invalidate **不能** reset（会把正在用的位图清掉）。 */
  private var rendering = false

  override fun invalidateDrawable(who: Drawable) {
    // ⚠ 2026-11-13 真机：源图标 `setBounds` 时会 invalidateSelf，这里 reset 掉 cache.bitmap，
    //   而 drawFilled 随后还拿着那张位图去 drawBitmap → c.bitmap 为 null → NPE → 全量走兜底。
    if (who === inner && !rendering) cache.reset()
    invalidateSelf()
  }

  override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
    scheduleSelf(what, `when`)
  }

  override fun unscheduleDrawable(who: Drawable, what: Runnable) {
    unscheduleSelf(what)
  }

  /** 档位：渲染尺寸吸附到这个倍数（理由见 [RenderCache] 与 drawFilled 的注释）。 */
  private val renderStep = 16

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
        // ⚠ 带堆栈：只打第一条（每张图标一个实例，不带栈会刷满且看不出来源）
        if (failStackLogged.compareAndSet(false, true)) {
          Log.w(TAG, "自适应：前景 draw 抛了（堆栈只打这一条）", t)
        } else {
          Log.w(TAG, "自适应：前景 draw 抛了，退回原图（${t.javaClass.simpleName}: ${t.message}）")
        }
      }
      runCatching {
        // ⚠ 画在**可见区**，不是外层 bounds：外层是 1.5 倍的图层区，
        //   按它画等于把图标居中放大 1.5 倍（2026-11-13 真机「过度放大」就是这一条）。
        val v = visibleRect()
        inner.setBounds(v)
        inner.draw(canvas)
      }
    }
  }

  /** 可见区 = 外层 bounds 往里收 1/6（不读 inner.bounds：它会被 drawIcon 临时改动）。 */
  private fun visibleRect(): Rect {
    val b = bounds
    val frac = (1f - VIEW_PORT_SCALE) / 2f
    // 四舍五入而不是截断：384 × 1/6 = 63.999…，截断会少 1 格（真机打出来 view=[-1,-1][257,257]）
    val dx = Math.round(b.width() * frac)
    val dy = Math.round(b.height() * frac)
    return Rect(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy)
  }

  private fun drawInner(canvas: Canvas) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val view = visibleRect()
    if (view.isEmpty) return
    drawFilled(canvas, view)
  }

  /**
   * 光栅化 → 铺内容（可放大）→ 算边角延伸色 → **把延伸色垫在底下、再把图标叠上去** → 贴回图层。
   *
   * ⚠ 为什么画两遍：把透明区填成实色之后，图标自己那圈抗锯齿边（alpha 1..254）如果留在
   * 透明底上，屏幕上是**一条半透明的缝**（2026-11-13 主人：「接缝有点大」）。第二遍把图标
   * 叠在延伸色上，边缘与底色正常混合，缝就没了。
   */
  private fun drawFilled(canvas: Canvas, view: Rect) {
    var stage = "f0"
    try {
      val w = view.width()
      val h = view.height()
      if (w <= 0 || h <= 0) return
      // 渲染尺寸吸附到档位：动画里大部分帧落同一档，只 blit 缓存
      val rw = snapRender(w)
      val rh = snapRender(h)
      val c = cache
      stage = "f1"
      // ⚠ 必须用局部引用：渲染期间回调可能（合法地）reset 掉共享缓存，
      //   这时 c.bitmap 已经是 null，但它**不影响**我们手里这张。
      var bitmap = c.bitmap
      if (bitmap == null || !c.matches(rw, rh, alpha)) {
        bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        val layer = Canvas(bitmap)
        c.bitmap = bitmap
        c.canvas = layer
        c.w = rw
        c.h = rh
        c.alpha = alpha
        stage = "f2"
        renderInto(bitmap, layer, rw, rh, view)
      }
      stage = "f3"
      canvas.drawBitmap(bitmap, null, view, null)
    } catch (t: Throwable) {
      Log.w(TAG, "前景阶段: " + stage, t)
      throw t
    }
  }

  /** 渲染尺寸吸附到 [renderStep] 的倍数（太小就不吸，避免把 48px 的图标放大到 64）。 */
  private fun snapRender(size: Int): Int {
    if (size < 64) return size
    // 取上不取下：宁可渲染大一点再缩小（清晰），也不要把 70 渲成 64 再放大
    return ((size + renderStep - 1) / renderStep) * renderStep
  }

  /** 画两遍：先量出填充色，再把填充垫在底下、图标叠上去。快捷方式走 [renderShortcut]。 */
  private fun renderInto(bitmap: Bitmap, layer: Canvas, w: Int, h: Int, view: Rect) {
    var stage = "r0"
    rendering = true
    try {
      stage = "r1"; bitmap.eraseColor(Color.TRANSPARENT)
      stage = "r2"; drawIcon(layer, w, h, view)
      if (shortcut) {
        stage = "r3s"; renderShortcut(bitmap, w, h)
        return
      }
      stage = "r3"
      val px = IntArray(w * h)
      bitmap.getPixels(px, 0, w, 0, 0, w, h)
      // ① 抗锯齿边色渗透：源 PNG 的透明处常是"黑色带 alpha"，直接叠在补边上就是一条黑线。
      stage = "r4"; bleedEdgeColor(px, w, h); deFringe(px, w, h)
      stage = "r5"
      val fill = computeFill(px, w, h)
      // ② 软件合成（源 over 补边）：不再画第二遍 —— 第二遍会把黑 AA 又画回来。
      stage = "r6"; composeOver(px, fill)
      stage = "r7"
      bitmap.setPixels(px, 0, w, 0, 0, w, h)
      stage = "r8"
    } catch (t: Throwable) {
      Log.w(TAG, "渲染阶段: " + stage, t)
      throw t
    } finally {
      rendering = false
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
   * 轮廓去边：每一行/列最外 2 个不透明格的颜色，换成往里第 3 格的颜色。
   *
   * 老式 PNG 的透明区常是黑色（RGB=0、alpha 有值），位图被放大（如 176→256）时，
   * 抗锯齿和黑色混出**不透明的深色边**（实测 (2,21,29,a255)）—— 半透明的那些
   * [bleedEdgeColor] 能修，被放大成不透明的只能在这里修掉，否则叠在补边上就是一条线。
   */
  /**
   * 轮廓去边（**直接删最外 2 格**）：每一行/列最外 2 个可见格清成透明，交给补边长进去。
   *
   * 为什么是"删"而不是"换色"：源 PNG 的透明区是黑色，放大时抗锯齿会混出**不透明的深色边**
   * （实测 (2,21,29,a255)）——换色要知道"里面那格"是什么，而这条边可能比取样深度还宽；
   * 直接删掉后，computeFill 会从新的边（往里第 3 格）取色并补进来，边界就是补边的颜色，没有线。
   */
  private fun deFringe(px: IntArray, w: Int, h: Int) {
    val src = px.copyOf()
    fun visible(i: Int) = (src[i] ushr 24) > 8
    for (y in 0 until h) {
      val row = y * w
      var x0 = 0
      while (x0 < w && !visible(row + x0)) x0++
      if (x0 < w) for (k in 0 until 2) {
        val x = x0 + k
        if (x < w && visible(row + x)) px[row + x] = 0
      }
      var x1 = w - 1
      while (x1 >= 0 && !visible(row + x1)) x1--
      if (x1 >= 0) for (k in 0 until 2) {
        val x = x1 - k
        if (x >= 0 && visible(row + x)) px[row + x] = 0
      }
    }
    for (x in 0 until w) {
      var y0 = 0
      while (y0 < h && !visible(y0 * w + x)) y0++
      if (y0 < h) for (k in 0 until 2) {
        val y = y0 + k
        if (y < h && visible(y * w + x)) px[y * w + x] = 0
      }
      var y1 = h - 1
      while (y1 >= 0 && !visible(y1 * w + x)) y1--
      if (y1 >= 0) for (k in 0 until 2) {
        val y = y1 - k
        if (y >= 0 && visible(y * w + x)) px[y * w + x] = 0
      }
    }
  }

  /**
   * 快捷方式那一档：**只画它自己的图**（2026-11-14 三轮收敛后的定案）。
   *
   *   · 不按边缘色铺边（主人：「快捷方式那肯定不用这种方法了」）；
   *   · **不补任何盘**（主人：「我的意思就是只去除系统自带的那圈白盘」「不能按桌面那套每个图标
   *     都包白圈的一刀切」）。补盘会把同一个应用下的快捷方式分成两半 —— 滴滴「从这里出发」
   *     是白盘、「回家」是橙圆，Outlook「新邮件」白盘、「查看日历」又是别的底，屏上不像一套；
   *   · **不缩放、不裁切**（主人：「有的图标压根没必要缩」「你为什么还要特地量大小」）。
   *
   * 透明的地方就交给壁纸。唯一保留的是 [bleedEdgeColor]：源图透明区常是黑色 RGB，放大后的
   * AA 边直接画出来就是一圈黑边 —— 那一步只改半透明格的 RGB、不动 alpha，也不改形状。
   */
  private fun renderShortcut(bitmap: Bitmap, w: Int, h: Int) {
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    bleedEdgeColor(px, w, h)
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
  }

  /** 源 over 补边（fill 里 0 = 没有补边，保持透明）。 */
  private fun composeOver(px: IntArray, fill: IntArray) {
    for (i in px.indices) {
      val a = px[i] ushr 24
      if (a == 255) continue
      val f = fill[i]
      if (a == 0) {
        if (f != 0) px[i] = f
        continue
      }
      if (f == 0) continue
      val fr = (f shr 16) and 0xFF
      val fg = (f shr 8) and 0xFF
      val fb = f and 0xFF
      val sr = (px[i] shr 16) and 0xFF
      val sg = (px[i] shr 8) and 0xFF
      val sb = px[i] and 0xFF
      px[i] = (255 shl 24) or
        (((sr * a + fr * (255 - a)) / 255) shl 16) or
        (((sg * a + fg * (255 - a)) / 255) shl 8) or
        ((sb * a + fb * (255 - a)) / 255)
    }
  }

  /** 把原图**按原尺寸**画进图层（不缩放、不裁切）；两次绘制共用。 */
  private fun drawIcon(layer: Canvas, w: Int, h: Int, view: Rect) {
    inner.setBounds(0, 0, w, h)
    inner.draw(layer)
    inner.setBounds(view)
  }

  override fun onBoundsChange(bounds: Rect) {
    val frac = (1f - VIEW_PORT_SCALE) / 2f
    val dx = (bounds.width() * frac).toInt()
    val dy = (bounds.height() * frac).toInt()
    inner.setBounds(bounds.left + dx, bounds.top + dy, bounds.right - dx, bounds.bottom - dy)
  }

  private var alpha = 255

  override fun setAlpha(alpha: Int) {
    this.alpha = alpha
    inner.alpha = alpha
    cache.reset()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    hasFilter = colorFilter != null
    inner.colorFilter = colorFilter
    cache.reset()
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = inner.intrinsicWidth

  override fun getIntrinsicHeight(): Int = inner.intrinsicHeight

  override fun getConstantState(): ConstantState? =
    inner.constantState?.let { src ->
      object : ConstantState() {
        override fun newDrawable(): Drawable = BareForeground(src.newDrawable(), shortcut)

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/** 判"看得见"的 alpha 门槛。 */
private const val ALPHA_MIN = 8

/** 取边色时优先用"够实"的格：抗锯齿边色被拉出去就是一道浅色接缝。 */
private const val SOLID_ALPHA = 160

/** 取边色时往里让几格：边界那 1~2 格是抗锯齿混出来的浅色，照它铺就是一圈浅接缝。 */
private const val EDGE_INSET = 1

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

  // 4) 极角：从最近的已给色格 BFS 拓过去。
  //    起点只认第 3 步按行/列边色算好的格 —— 不能把图标自己的像素算进来：
  //    它们多半是抗锯齿边/软阴影（实测 (243,243,243,a175)），BFS 会把那一格的颜色摊满整个角，
  //    屏幕上就是一块"不是图标边色"的方块（2026-11-14 皎月连：1131 格）。
  var head = 0
  var tail = 0
  for (i in 0 until n) {
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
 * "桌面在载快捷方式"这条判据的**证据行**：前 [SHORTCUT_MARK_LOG_LIMIT] 张打一条，
 * 带环境（host / from）与资源名 —— 它同时是误伤的报警器（打在一个应用图标上就是串号了）。
 */
private const val SHORTCUT_MARK_LOG_LIMIT = 40

private val shortcutMarkLogLeft = AtomicInteger(SHORTCUT_MARK_LOG_LIMIT)

/**
 * 桌面**正在加载快捷方式图标**的计数（[IconHooks.hookShortcutIconCache] 在这几条上加减）：
 * `IconCache.getShortcutIcon`（4 个重载）、`CacheableShortcutCachingLogic.loadIcon`、
 * `ShortcutConfigActivityInfo(…VO).getFullResIcon`。
 *
 * 为什么不是 ThreadLocal：这几条链上不保证与调用方同线程；桌面是单 worker 载图标，窗口很窄。
 * 真有串号，`"快捷标记(桌面在载快捷方式)"` 那行会把应用图标的资源名打出来。
 *
 * 2026-11-14 装机取证：桌面钉着的「快捷方式活动」（联系人「直接拨号 / 发短信」）图标走的是
 * `ShortcutConfigActivityInfoVO.getFullResIcon → IconLoadRequest.getIcon → IconProvider →
 * Resources.getDrawableForDensity`，前面那两条都**不在**这条链上 —— 只有 `getFullResIcon` 是。
 */
internal object ShortcutLoad {
  private val depth = AtomicInteger(0)

  fun enter() {
    depth.incrementAndGet()
  }

  fun exit() {
    depth.decrementAndGet()
  }

  val inFlight: Boolean get() = depth.get() > 0
}

/**
 * 快捷方式**声明过的图标资源**（`包名/资源id`）—— Resources 通道认出快捷方式的依据。
 *
 * 为什么需要：桌面的一部分快捷方式图标是从 `Resources.getDrawableForDensity` 出来的
 * （`com.android.contacts/drawable/logo_quick_contacts_*`、Poweramp 的 `app_shortcut_playlist`…），
 * 那条通道的名字里没有 "Shortcut"，光看调用方认不出来。
 *
 * 登记点：凡是拿到 `ShortcutInfo` 的钩子（`LauncherApps.getShortcutIconDrawable` 的参数、
 * 桌面 `IconCache.getShortcutIcon` 的参数）都把 `iconResource` 记下来 —— 与"什么时候真的去画"
 * 无关，所以不怕异步。Resources 钩子把请求还原成真实 id 后查这张表。
 *
 * 键是"包名/id"而不是 id 本身：资源 id 只在包内唯一。
 * 上限 [MAX] 条，超了整体清空（快捷方式数量有限，正常一年也到不了）。
 */
internal object ShortcutIconResources {
  private const val MAX = 4096
  private val keys = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

  fun remember(pkg: String?, resId: Int) {
    if (pkg.isNullOrEmpty() || resId == 0) return
    if (keys.size > MAX) keys.clear()
    keys["$pkg/$resId"] = true
  }

  fun has(pkg: String?, resId: Int): Boolean =
    pkg != null && keys.containsKey("$pkg/$resId")
}

/**
 * 包装成自适应图标；**这一层本身绝不抛**。
 *
 * ⚠ 它被挂在宿主的取图热路径上，抛出去就是宿主崩：任何一步失败都原样放行。
 */
fun wrapLegacyIcon(icon: Drawable, from: String, shortcutHint: Boolean = false): Drawable =
  runCatching { wrapInner(icon, from, shortcutHint) }.getOrElse {
    if (clipFailLogged.compareAndSet(false, true)) {
      Log.w(TAG, "自适应：包装抛了，原样放行（${it.javaClass.simpleName}: ${it.message}）")
    }
    icon
  }

private fun wrapInner(icon: Drawable, from: String, shortcutHint: Boolean): Drawable {
  // 配置按 TTL 重读，所以界面里关掉开关后很快生效，不用重启进程。
  if (!ModuleRuntime.options().circleEnabled) return icon
  // 射程（2026-10-21 起）：**默认全包，唯一的排除是快速设置那一路**（调用方分得开）。
  if (calledByQuickSettings()) {
    noteTileSkip(from)
    return icon
  }
  // 已经包过的、以及**自适应图标**：都不动。自适应图标自带背景 + 前景，形状由系统 mask 定。
  if (icon is CircleIconDrawable || icon is AdaptiveIconDrawable) return icon

  noteWrap(from)
  // 快捷方式身份有三条来源：通道名（LauncherApps.getShortcutIconDrawable）、桌面正在加载时
  // 的计数（[ShortcutLoad]，实测兜不住的兜底）、以及**这张 drawable 的资源 id 被登记为
  // 某条快捷方式的 iconResource**（[ShortcutIconResources]，Resources 通道实际靠这条）。
  val byChannel = from.contains("Shortcut")
  val byLoader = !byChannel && ShortcutLoad.inFlight
  val byResource = !byChannel && !byLoader && shortcutHint
  // 单色那一位只认通道（那一支是逐张调过的，不跟着通道猜测改口径）；外观三条都认。
  val look = byChannel || byLoader || byResource
  if ((byLoader || byResource) && shortcutMarkLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "快捷标记(" + (if (byLoader) "桌面在载快捷方式" else "资源表") + ") host=$clipHost from=$from : " +
      IconNames.of(icon))
  }
  if (wrapLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} " +
      if (look) "快捷方式 不铺边(有底原样/没底补盘)" else "无底盘 原尺寸+延伸")
  }
  return CircleIconDrawable(icon, byChannel, look)
}
