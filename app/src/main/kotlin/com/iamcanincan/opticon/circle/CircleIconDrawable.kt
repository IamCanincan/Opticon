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
 *  · 几何（[IconGeom]，彩色与单色共用同一份算式）：**先缩到恰好填满正方形**、不裁掉任何有色
 *    像素，再按填满后的可见占比分两支 —— ≥ π/4 按边缘色延伸补满、不另加底盘；< π/4 缩进
 *    内切圆并**铺白底**（主人 2026-11-14：「铺上白底」）。不做旧裁圆那种 cover 放大
 *    （主人 2026-11-13：「有的图标压根没必要缩」与「过度裁切」两次点名）；
 *    ⚠ **快捷方式整个不进这里**（主人 2026-11-14：「算了，你快捷方式别管了」）—— 原样放行，
 *    由桌面自己画；
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
  /** 所有副本共享的渲染缓存（[getConstantState] 的副本会拿到同一个）。 */
  private val sharedCache: RenderCache = RenderCache(),
) : AdaptiveIconDrawable(
  // 不要底盘（2026-11-13）：老式图标自己就是整张图，另垫一块颜色等于替应用画了个底。
  // 用透明 ColorDrawable 而不是 null：有的宿主会直接读 `getBackground()`，空着比给个会抛的更安全。
  ColorDrawable(Color.TRANSPARENT),
  // 前景：内容补满可见区，形状由桌面裁；所有副本共享同一份渲染缓存
  BareForeground(icon, sharedCache),
), IconSourceCarrier {

  /** 原图标留一份引用，只为了 [getConstantState] 能重建出副本。 */
  private val sourceIcon: Drawable = icon

  /** [getConstantState] 只建一次：每次 bind 都新建一个 state 的话，副本之间就共享不到缓存了。 */
  private var sharedState: ConstantState? = null

  /** 给**要未包装原图**的消费方（通知兜底 / 通知图标解码），口径见 [IconSourceCarrier]。 */
  override val source: Drawable get() = sourceIcon

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
    return MonochromeSynth.forIcon(sourceIcon)
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
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, sharedCache)

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
 * **几何在 [IconGeom]**：先缩到恰好填满正方形（不裁掉任何有色像素），再按可见占比分两支 ——
 * 占比够的走下面的边角延伸；占比小的缩进内切圆 + 铺白底，那一支**不延伸**（透明处一律由白底承担）。
 * 全程等比，图案保持应用自己设计的比例；不做旧裁圆那种 cover 放大（主人要求「过度放大 / 过度裁切」都不能有）。
 *
 * **边角延伸**（同日第二句：「边角透明的地方给它延伸完全」）：把图标光栅化到图标槽大小，
 * 从**画布边界**找出与外面连通的透明区域（内部镂空不动），从真实轮廓边界做多源 BFS、
 * 每个边界点带自己的颜色往外拓（等距波前平均）—— 圆角/圆盘图标那
 * 四个透明角从此是它自己的边缘色，不再是壁纸。铺完还往图标里垫 2 格，第二遍把图标叠在
 * 底色上画，抗锯齿边与底色混合，不会留下一条半透明的浅缝。
 * 延伸那一支的老式图标都过这一步（渲染结果按尺寸档位缓存，不会每帧重算）。
 */
/** 「带堆栈的第一条」只打一次（进程级）。 */
private val failStackLogged = java.util.concurrent.atomic.AtomicBoolean(false)

private class BareForeground(
  private val inner: Drawable,
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

  /**
   * ① 原图 → 交 [IconGeom] 算出几何（填满正方形 / 缩进内切圆），按倍率**重画一遍**；
   * ② 小占比那一支铺白底；③ 其余按边缘色延伸补角。
   *
   * 尺寸与分档的判断**不在这里**（在 [IconGeom]，单色那边调的是同一份）——
   * 这里只负责"把彩色要画的那一刀画出来"。
   */
  private fun renderInto(bitmap: Bitmap, layer: Canvas, w: Int, h: Int, view: Rect) {
    var stage = "r0"
    rendering = true
    try {
      stage = "r1"; bitmap.eraseColor(Color.TRANSPARENT)
      stage = "r2"; drawIcon(layer, w, h, view)
      stage = "r3"
      val px = IntArray(w * h)
      bitmap.getPixels(px, 0, w, 0, 0, w, h)
      // ⓪ **先把图标缩放到恰好填满正方形**（内容外接框贴住四边、不被方块裁），
      //    再拿填满之后的可见占比去分界（主人 2026-11-14：「不应该先将应用图标缩放到恰好
      //    不被正方形裁切时的占比吗」）。有留白的图标（PH 的圆角方形）缩满后占比就上去了。
      stage = "r3a"
      val plan = IconGeom.planOf(px, w, h)
      if (plan != null && geomLogLeft.decrementAndGet() >= 0) {
        // 与单色那行 `Mono: 跟彩色 … label=` **逐张配对**（同一份 [IconGeom] 出来的三个数）：
        // 对不上就是"单色与彩色不符"，别再靠肉眼看桌面。配对台 `node _harness/_geompair.cjs <logcat>`。
        Log.d(TAG, "自适应几何 host=$clipHost fill=" + ((plan.fill * 100).toInt() / 100f) +
          " scale=" + ((plan.scale * 100).toInt() / 100f) +
          " 支=" + (if (plan.plate) "铺底" else "延伸") + " : " + IconNames.of(inner))
      }
      if (plan != null && plan.needsRedraw) {
        val nb = plan.bounds(w, h)
        bitmap.eraseColor(Color.TRANSPARENT)
        inner.setBounds(Math.round(nb[0]), Math.round(nb[1]), Math.round(nb[2]), Math.round(nb[3]))
        inner.draw(layer)
        inner.setBounds(view)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
      }
      // 分界（同一个 [IconGeom] 算的，单色那边读的是同一份）：可见占比 >= 内切圆占正方形的
      // 面积比（[INSCRIBED_SHARE]）走正常延伸；小于它的缩进内切圆 + **铺白底**（主人 2026-11-14：「铺上白底」）。
      stage = "r3b"
      if (plan?.plate ?: true) {
        if (plan != null && plan.shrunk && zoomLogLeft.decrementAndGet() >= 0) {
          Log.d(TAG, "圆内缩 host=$clipHost r=" + plan.radius.toInt() +
            " half=" + (minOf(w, h) / 2f).toInt() +
            " scale=" + ((plan.scale * 100).toInt() / 100f) + " : " + IconNames.of(inner))
        }
        // ① 抗锯齿边色渗透：源 PNG 的透明处常是"黑色带 alpha"，直接叠在底盘上就是一圈黑边。
        stage = "r4s"; bleedEdgeColor(px, w, h)
        // ② 铺**白底**。不延伸 —— 透明处一律由底承担。
        stage = "r5s"; composeOverColor(px, Color.WHITE)
        stage = "r6s"
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        return
      }
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
   * 轮廓去边：把每行/列最外 2 个**毛边**格清成透明，交给 [computeFill] 从新的边取色补进来。
   *
   * 源 PNG 的透明区常是黑色（RGB=0、alpha 有值），位图放大时抗锯齿会混出**不透明的深色边**
   * （实测 (2,21,29,a255)）；半透明的那些 [bleedEdgeColor] 能修，被放大成不透明的只能在这里清掉，
   * 否则叠在补边上就是一条线。
   *
   * ⚠ 2026-11-14 收窄成"只抹毛边"：原来是无条件抹最外 2 格，会把本身就有边缘细节的图标
   * （潮新闻 / 智游斗地主 / 吉星派对）糊掉。现在只清 alpha < 250 的 AA，或亮度 < [RIM_LUM]
   * 且比内侧暗 [RIM_DELTA] 以上的不透明深色边；实色真细节一格不动。
   * 实测这三张的左边缘与源图的平均亮度差：28.2→19.4、22.8→3.8、54.4→30.1。
   */
  private fun deFringe(px: IntArray, w: Int, h: Int) {
    val src = px.copyOf()
    fun visible(i: Int) = (src[i] ushr 24) > 8
    // 只抹**毛边**：半透明的 AA（alpha < 250），或明显比里面暗的不透明黑边。
    // 实色的真细节保留（2026-11-14：原来的"无条件抹最外 2 格"会把这 3 张的边缘细节糊掉）。
    fun rim(i: Int, inner: Int) {
      if (!visible(i)) return
      val a = src[i] ushr 24
      if (a < 250) {
        px[i] = 0
        return
      }
      if (lum31(src[i]) < RIM_LUM && lum31(src[inner]) - lum31(src[i]) > RIM_DELTA) px[i] = 0
    }
    for (y in 0 until h) {
      val row = y * w
      var x0 = 0
      while (x0 < w && !visible(row + x0)) x0++
      if (x0 < w) for (k in 0 until 2) {
        val x = x0 + k
        if (x < w && visible(row + x)) rim(row + x, row + minOf(x0 + 2, w - 1))
      }
      var x1 = w - 1
      while (x1 >= 0 && !visible(row + x1)) x1--
      if (x1 >= 0) for (k in 0 until 2) {
        val x = x1 - k
        if (x >= 0 && visible(row + x)) rim(row + x, row + maxOf(x1 - 2, 0))
      }
    }
    for (x in 0 until w) {
      var y0 = 0
      while (y0 < h && !visible(y0 * w + x)) y0++
      if (y0 < h) for (k in 0 until 2) {
        val y = y0 + k
        if (y < h && visible(y * w + x)) rim(y * w + x, minOf(y0 + 2, h - 1) * w + x)
      }
      var y1 = h - 1
      while (y1 >= 0 && !visible(y1 * w + x)) y1--
      if (y1 >= 0) for (k in 0 until 2) {
        val y = y1 - k
        if (y >= 0 && visible(y * w + x)) rim(y * w + x, maxOf(y1 - 2, 0) * w + x)
      }
    }
  }

  /** 亮度（Rec.601，0..255）：只给"深色毛边"判据用。 */
  private fun lum31(c: Int): Int {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
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

  /** 源 over 一整块纯色盘（透明处都由它承担）。 */
  private fun composeOverColor(px: IntArray, color: Int) {
    val fr = (color shr 16) and 0xFF
    val fg = (color shr 8) and 0xFF
    val fb = color and 0xFF
    for (i in px.indices) {
      val a = px[i] ushr 24
      if (a == 255) continue
      val sr = (px[i] shr 16) and 0xFF
      val sg = (px[i] shr 8) and 0xFF
      val sb = px[i] and 0xFF
      px[i] = (255 shl 24) or
        (((sr * a + fr * (255 - a)) / 255) shl 16) or
        (((sg * a + fg * (255 - a)) / 255) shl 8) or
        ((sb * a + fb * (255 - a)) / 255)
    }
  }

  /** 把原图整张画进图层（不动内容外接框）；填满正方形那一刀在 [renderInto] 里按 [IconGeom] 另算。 */
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
        override fun newDrawable(): Drawable = BareForeground(src.newDrawable())

        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    }
}

/**
 * 判"看得见"的 alpha 门槛。
 * ⚠ [IconGeom] 也吃它（几何那一步两边必须数同一批格子）—— 所以不是 private。
 */
internal const val ALPHA_MIN = 8

/** 取边色时优先用"够实"的格：抗锯齿边色被拉出去就是一道浅色接缝。 */
private const val SOLID_ALPHA = 160

/** 判"深色毛边"的亮度上限：源图透明区是黑 RGB 时，放大出来的边实测 (2,21,29) ≈ 15。 */
private const val RIM_LUM = 60

/** 外圈格比里面暗多少才算毛边（< 它就算真细节，保留）。 */
private const val RIM_DELTA = 40

/**
 * 内切圆占正方形的**面积比**：π/4 ≈ 0.785。
 * 可见占比 >= 它的图标（贴边实心那种）走正常延伸；小于它的缩进内切圆 + 垫对比底
 * （主人 2026-11-14：「大于那个占比，还是正常延伸」）。
 */
internal const val INSCRIBED_SHARE = 0.785f

/**
 * 小占比那一支缩进内切圆之后，内容最大半径占圆半径的多少（= 圆边留多少余量）。
 * 0.98 是"刚好贴住圆"；主人 2026-11-14「再稍微缩一点」→ 0.92 →「再缩一点点」→ 0.88（圆边留 12%）。
 * 想再紧/再松只改这一个数。
 */
internal const val CIRCLE_FIT = 0.88f

/**
 * 算"边角该填什么颜色"。返回与 [px] 等长的数组：0 = 不填，其余 = 要铺的 ARGB。
 *
 * 颜色取**最近的轮廓边界点**：从八邻里有 outside 格的实色格（α≥[SOLID_ALPHA]）做多源 BFS，
 * 每个边界点带自己的颜色往外拓；等距波前相遇按种子数加权平均（角上不再是谁先到谁赢的硬缝）。
 *
 * ⚠ 旧版取"行向 + 列向各一条边线的平均"，2026-11-16 量出两种接缝（主人：「交界处还是不够平滑」）：
 *   补边色与局部边色不符（云闪付红补边贴白色横条，接缝色差 p90=113、24% 的接缝 >30），
 *   以及行/列采样把边色拖成轴向条纹（王者荣耀两张 / wisdomgame）。换边界 BFS 后真机 32 张
 *   p90 全部 ≤18、>30 占比 ≤1%（离线复现台 `_harness/_extfill.cjs`，`--mode=rowcol` 臂是负对照）。
 *   渐变底同样保得住：每个边界点带自己的颜色，渐变沿轮廓往外延。
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

  // 2) 种子：八邻里有 outside 格的实色格 —— 真实轮廓边界，不是行/列极值
  val dist = IntArray(n) { -1 }
  val sumR = IntArray(n)
  val sumG = IntArray(n)
  val sumB = IntArray(n)
  val cnt = IntArray(n)
  var head = 0
  var tail = 0
  for (i in 0 until n) {
    if ((px[i] ushr 24) < SOLID_ALPHA) continue
    val x = i % w
    val y = i / w
    var seed = false
    for (dy in -1..1) for (dx in -1..1) {
      if (dx == 0 && dy == 0) continue
      val nx = x + dx
      val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      if (outside[ny * w + nx] == 1.toByte()) seed = true
    }
    if (!seed) continue
    dist[i] = 0
    cnt[i] = 1
    sumR[i] = (px[i] shr 16) and 0xFF
    sumG[i] = (px[i] shr 8) and 0xFF
    sumB[i] = px[i] and 0xFF
    work[tail++] = i
  }

  // 3) BFS 往外拓（八邻）。等距波前相遇按种子数累加、最后平均 ——
  //    "谁先到谁赢"会在两色边界的角上留一条硬缝。
  while (head < tail) {
    val i = work[head++]
    val x = i % w
    val y = i / w
    val d = dist[i] + 1
    for (dy in -1..1) for (dx in -1..1) {
      if (dx == 0 && dy == 0) continue
      val nx = x + dx
      val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      val j = ny * w + nx
      if (outside[j] != 1.toByte()) continue
      if (dist[j] < 0) {
        dist[j] = d
        cnt[j] = cnt[i]
        sumR[j] = sumR[i]
        sumG[j] = sumG[i]
        sumB[j] = sumB[i]
        work[tail++] = j
      } else if (dist[j] == d) {
        cnt[j] += cnt[i]
        sumR[j] += sumR[i]
        sumG[j] += sumG[i]
        sumB[j] += sumB[i]
      }
    }
  }

  // 4) 只交"外面"的格
  for (i in 0 until n) {
    if (outside[i] == 1.toByte() && dist[i] >= 0) {
      fill[i] = (0xFF shl 24) or
        (((sumR[i] + cnt[i] / 2) / cnt[i]) shl 16) or
        (((sumG[i] + cnt[i] / 2) / cnt[i]) shl 8) or
        ((sumB[i] + cnt[i] / 2) / cnt[i])
    }
  }

  // 5) 再往图标里垫 2 格：盖住图标自己那圈抗锯齿边（alpha 1..254）。
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
      if (cnt2 > 0) fill[i] = (0xFF shl 24) or ((sr / cnt2) shl 16) or ((sg / cnt2) shl 8) or (sb / cnt2)
    }
  }
  return fill
}

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

/** 「圆内缩」证据行额度（前 [ZOOM_LOG_LIMIT] 张，带半径与倍率）。 */
private const val ZOOM_LOG_LIMIT = 40

/**
 * 「自适应几何」证据行额度（前 [GEOM_LOG_LIMIT] 张）。
 * 这一行与单色那行 `Mono: 跟彩色 … label=` 是**一对**（同一份 [IconGeom] 的两个消费者），
 * 额度两边要对得上，配表台见 `_harness/_geompair.cjs`。
 */
private const val GEOM_LOG_LIMIT = 300

private val geomLogLeft = AtomicInteger(GEOM_LOG_LIMIT)

private val zoomLogLeft = AtomicInteger(ZOOM_LOG_LIMIT)

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

  // 快捷方式：**整个放行**（主人 2026-11-14：「算了，你快捷方式别管了」）。
  // 桌面会按它自己那套画（老式图标 / 白底 / 形状都由它决定），我们一个像素不碰。
  // 身份来源三条：① 通道名 LauncherApps.getShortcutIconDrawable；② 桌面正在载快捷方式时
  // 的计数（[ShortcutLoad]）；③ 参数里的 iconResource 登记（[ShortcutIconResources]）。
  val byChannel = from.contains("Shortcut")
  val byLoader = !byChannel && ShortcutLoad.inFlight
  val byResource = !byChannel && !byLoader && shortcutHint
  if (byChannel || byLoader || byResource) {
    if (shortcutMarkLogLeft.decrementAndGet() >= 0) {
      Log.d(TAG, "快捷方式放行(" + (if (byChannel) "通道" else if (byLoader) "桌面在载" else "资源表") +
        ") host=$clipHost from=$from : " + IconNames.of(icon))
    }
    return icon
  }
  if (wrapLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} 无底盘 原尺寸+延伸")
  }
  return CircleIconDrawable(icon)
}
