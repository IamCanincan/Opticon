package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Paint
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
  var holes: Bitmap? = null
  var w = 0
  var h = 0
  var alpha = 255

  fun matches(w: Int, h: Int, a: Int): Boolean =
    bitmap != null && this.w == w && this.h == h && alpha == a

  fun reset() {
    bitmap = null
    canvas = null
    holes = null
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
  /**
   * 这张是应用自己声明的 roundIcon 老式位图（满幅圆盘）：几何强制走延伸支，
   * 不缩进内切圆、不铺白底 —— 否则给本来完美的圆套一圈白（2026-11-18 parsec 应用信息页）。
   * 单色层跟同一个旗标（[getMonochrome]），两边几何不许分家。
   */
  private val forceExtend: Boolean = false,
) : AdaptiveIconDrawable(
  // 不要底盘（2026-11-13）：老式图标自己就是整张图，另垫一块颜色等于替应用画了个底。
  // 用透明 ColorDrawable 而不是 null：有的宿主会直接读 `getBackground()`，空着比给个会抛的更安全。
  ColorDrawable(Color.TRANSPARENT),
  // 前景：内容补满可见区，形状由桌面裁；所有副本共享同一份渲染缓存
  BareForeground(icon, sharedCache, forceExtend),
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
    return MonochromeSynth.forIcon(sourceIcon, forceExtend = forceExtend)
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
        CircleIconDrawable(src?.newDrawable() ?: sourceIcon, sharedCache, forceExtend)

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
  /** 满幅圆盘（roundIcon 老式位图）：几何强制延伸支，见 [CircleIconDrawable.forceExtend]。 */
  private val forceExtend: Boolean = false,
) : Drawable(), Drawable.Callback {

  /** 设了 colorFilter 时不走共享缓存（滤镜是每个实例自己的）。 */
  private var hasFilter = false
  private var local: RenderCache? = null
  private val cache: RenderCache
    get() = if (hasFilter) (local ?: RenderCache().also { local = it }) else shared

  /** 把铺底的 edge 色从封闭镂空里抠掉（见 drawInner）。
   *  ⚠ 必须 DST_OUT 不能 CLEAR：CLEAR 的公式是 [0,0]，**不看源 alpha**、整块绘制矩形全清
   *  （2026-11-19 真机：全透明掩码也把整张图标抠空 → 233 张落库全透明黑块）。 */
  private val holePaint =
    Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

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
    val view = visibleRect()
    if (view.isEmpty) return
    drawFilled(canvas, view)
    // 外圈 1/6 安全环填实（2026-11-18 parsec 桌面马赛克）：drawFilled 只贴里圈 view，
    // 外圈若留透明，桌面把这张画进它**复用的位图池**时，上一张的像素会从外圈透出来成角部
    // 噪点（真机 flatten 输入 trans%=3、四角 ffffff00/ff000001；好的纯色图标 trans%=0）。
    // 用里圈渲染出的边缘色（图标自己的颜色，不是另垫底盘，守 2026-11-13「不要底盘」）整张
    // 铺实再重贴里圈，交出去的位图外圈就没有透明像素。外圈在屏幕上被桌面遮罩裁掉，只影响
    // 落库那张全幅位图。alpha<255（淡入淡出）时不填，保持透明。
    // ⚠ 铺完必须把**封闭镂空**用 DST_OUT 掩码抠回透明（2026-11-19 主人：「outlook 的查看日历
    //   为什么变成了蓝色的一整块」）：那张矢量五个反绕圆点在渲染里是 α0，blit 不覆盖底下像素，
    //   整张铺色会把洞垫成实色 → 圆点消失、整块蓝。掩码 = 渲染的 α0 格（随渲染缓存）。
    // ⚠ 也不能省掉铺色改成只 clipOutRect 铺外圈（同日第二版，已否）：那样 view 内的半透明像素
    //   会叠在桌面**复用位图池的上一张残影**上（真机 pixdiff：audioplayer 60% 格随池漂、
    //   大半透明图标被白残影染成白整块）。铺 edge 色才是确定的合成底。
    if (alpha == 255) {
      val b = cache.bitmap
      if (b != null && !b.isRecycled) {
        val px = IntArray(1)
        b.getPixels(px, 0, 1, 0, 0, 1, 1)
        val edge = px[0]
        if ((edge ushr 24) == 255) {
          canvas.drawColor(edge)
          drawFilled(canvas, view)
          val m = cache.holes
          if (m != null && !m.isRecycled) canvas.drawBitmap(m, null, view, holePaint)
        }
      }
    }
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
      stage = "r1"; bitmap.eraseColor(Color.TRANSPARENT); cache.holes = null
      stage = "r2"; drawIcon(layer, w, h, view)
      stage = "r3"
      val px = IntArray(w * h)
      bitmap.getPixels(px, 0, w, 0, 0, w, h)
      // ⓪ **先把图标缩放到恰好填满正方形**（内容外接框贴住四边、不被方块裁），
      //    再拿填满之后的可见占比去分界（主人 2026-11-14：「不应该先将应用图标缩放到恰好
      //    不被正方形裁切时的占比吗」）。有留白的图标（PH 的圆角方形）缩满后占比就上去了。
      stage = "r3a"
      val plan = IconGeom.planOf(px, w, h, forceExtend)
      if (plan != null && geomLogLeft.decrementAndGet() >= 0) {
        // 与单色那行 `Mono: 跟彩色 … label=` **逐张配对**（同一份 [IconGeom] 出来的三个数）：
        // 对不上就是"单色与彩色不符"，别再靠肉眼看桌面。配对台 `node _harness/_geompair.cjs <logcat>`。
        Log.d(TAG, "自适应几何 host=$clipHost fill=" + ((plan.fill * 100).toInt() / 100f) +
          " scale=" + ((plan.scale * 100).toInt() / 100f) +
          " 支=" + (if (plan.plate) "铺底" else "延伸") + " : " + IconNames.of(inner))
      }
      if (plan != null && plan.needsRedraw) {
        val before = bboxOf(px, w, h)
        val nb = plan.bounds(w, h)
        bitmap.eraseColor(Color.TRANSPARENT)
        inner.setBounds(Math.round(nb[0]), Math.round(nb[1]), Math.round(nb[2]), Math.round(nb[3]))
        inner.draw(layer)
        inner.setBounds(view)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        // 写死尺寸的层（layer-list 固定 dp + gravity）不随外框缩放：重画后外接框一格没动
        // ⇒ 拉外框对它无效（DeepSeek 快捷方式：图案留原地、四周被补色铺满 = 满幅底+小图案）。
        // 补一次画布整体缩放：canvas 变换作用于所有 draw 操作，固定层也跟着放大。
        // 只对"重画没生效"的图开火 ⇒ 随外框缩放的图逐格不变。
        val after = bboxOf(px, w, h)
        if (before != null && after != null && before.contentEquals(after)) {
          bitmap.eraseColor(Color.TRANSPARENT)
          val save = layer.save()
          layer.translate(w / 2f - plan.cx * plan.scale, h / 2f - plan.cy * plan.scale)
          layer.scale(plan.scale, plan.scale)
          inner.setBounds(0, 0, w, h)
          inner.draw(layer)
          layer.restoreToCount(save)
          inner.setBounds(view)
          bitmap.getPixels(px, 0, w, 0, 0, w, h)
        }
      }
      // ⓪ 源图自带的噪边：parsec 的 round PNG 最外圈 α255 实心环自己就是噪的
      //    （r87 逐度 18↔56 交替、r86 光滑），种子踩上去就被径向复制成放射条纹，
      //    bleedEdgeColor 还会把这份噪色沿 AA 坡往外带。先把**带噪的**实心边界环
      //    换成内侧光滑色；光滑环与细笔画一格不动（差 ≤2 不写、窗内无实心核不写）。
      stage = "r3c"; cleanRim(px, w, h)
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
      // ⓪ 源图烤进的白角背景（圆贴在不透明白方块上）：白角是 α255 实像素，洪泛进不去 ⇒
      //    延伸只填透明处、白角原样留下（微信接龙管家）。四角同余白块认成背景清零，见函数 KDoc。
      //    清完再跑一遍 cleanRim：r3c 那遍看到的是白角环，盘缘噪点没被清 ⇒ 会当补色种子放射条纹。
      //    只在开火时跑（射程=接龙家族），成本不摊到每张延伸图标。
      stage = "r3w"
      if (stripBakedWhiteCorners(px, w, h) > 0) cleanRim(px, w, h)
      // ① 抗锯齿边色渗透：源 PNG 的透明处常是"黑色带 alpha"，直接叠在补边上就是一条黑线。
      stage = "r4"; bleedEdgeColor(px, w, h); deFringe(px, w, h)
      stage = "r5"
      val fill = computeFill(px, w, h)
      // ② 软件合成（源 over 补边）：不再画第二遍 —— 第二遍会把黑 AA 又画回来。
      stage = "r6"; composeOver(px, fill)
      stage = "r7"
      bitmap.setPixels(px, 0, w, 0, 0, w, h)
      // 封闭镂空掩码：渲染里 α0 的格（computeFill 只填得到从四边洪泛可达的外面，
      // 围在图标内部的洞走不到 ⇒ 留 α0）。drawInner 铺完 edge 底后拿它 DST_OUT 抠回透明。
      val hm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      hm.setPixels(IntArray(w * h) { if ((px[it] ushr 24) == 0) Color.BLACK else 0 }, 0, w, 0, 0, w, h)
      cache.holes = hm
      stage = "r8"
    } catch (t: Throwable) {
      Log.w(TAG, "渲染阶段: " + stage, t)
      throw t
    } finally {
      rendering = false
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
      for (dy in -3..3) for (dx in -3..3) {
        val nx = x + dx
        val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        val j = ny * w + nx
        if (depth[j] < 4) continue
        sr += (snapshot[j] shr 16) and 0xFF
        sg += (snapshot[j] shr 8) and 0xFF
        sb += snapshot[j] and 0xFF
        cnt++
      }
      if (cnt == 0) continue
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
        override fun newDrawable(): Drawable = BareForeground(src.newDrawable(), forceExtend = forceExtend)

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

  // 1a) 设计留缝退标（见 [unmarkDesignSlots]）：横档两端通外面，不退标就被当背景填实。
  //     缝格标 outside=2（不是 0）：第 5 步往图标里垫色时靠它跳过，否则从缝口把缝逐环吃回去。
  unmarkDesignSlots(px, w, h, outside)

  // 1b) 软边通行证：从 outside 出发洪泛整条 α<255 的渐隐坡（只作通路，不作种子）。
  //     parsec 那类轮廓是 6 格宽的 α 坡（21→204→255），贴 outside 的那格 α=33 不是实色，
  //     旧种子判据（八邻有 outside 的实色格）在整条软边弧上**一个种子都选不出**，
  //     底角只能借顶弧硬边的浅色种子 ⇒ 补色 34 贴局部边色 11 = 主人看到的色差。
  //     soft 供选种子与波前通行（种子在坡内侧），但只往 outside 格写色 ⇒ 补色区域逐格不变；
  //     硬边图 soft==outside ⇒ 零差异。
  val soft = ByteArray(n)
  var sp2 = 0
  for (i in 0 until n) if (outside[i] == 1.toByte()) { soft[i] = 1; work[sp2++] = i }
  while (sp2 > 0) {
    val i = work[--sp2]
    val x = i % w
    val y = i / w
    if (x > 0 && soft[i - 1] == 0.toByte() && (px[i - 1] ushr 24) < 255) { soft[i - 1] = 1; work[sp2++] = i - 1 }
    if (x + 1 < w && soft[i + 1] == 0.toByte() && (px[i + 1] ushr 24) < 255) { soft[i + 1] = 1; work[sp2++] = i + 1 }
    if (y > 0 && soft[i - w] == 0.toByte() && (px[i - w] ushr 24) < 255) { soft[i - w] = 1; work[sp2++] = i - w }
    if (y + 1 < h && soft[i + w] == 0.toByte() && (px[i + w] ushr 24) < 255) { soft[i + w] = 1; work[sp2++] = i + w }
  }

  // 2) 种子：八邻里有 outside/soft 格的**全实色**格 —— 真实轮廓边界，不是行/列极值。
  //    ⚠ 2026-11-18：阈值曾为 SOLID_ALPHA(160)，把 α 坡上的半透明格也当种子；那些格的
  //    straight-alpha 色 = 预乘÷α，低 α 处 rounding 噪声被放大成 25..62 锯齿（真机环向
  //    读数 r=126/128），被径向链拉成放射亮条。只认 α=255 的实心边（环向光滑），
  //    一个都选不出时退回 160 兜底（防整张空补色）。
  val dist = IntArray(n) { -1 }
  val sumR = IntArray(n)
  val sumG = IntArray(n)
  val sumB = IntArray(n)
  val cnt = IntArray(n)
  var head = 0
  var tail = 0
  for (threshold in intArrayOf(255, SOLID_ALPHA)) {
    for (i in 0 until n) {
      if ((px[i] ushr 24) < threshold) continue
      val x = i % w
      val y = i / w
      var seed = false
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val nx = x + dx
        val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        if (soft[ny * w + nx] == 1.toByte()) seed = true
      }
      if (!seed) continue
      dist[i] = 0
      cnt[i] = 1
      sumR[i] = (px[i] shr 16) and 0xFF
      sumG[i] = (px[i] shr 8) and 0xFF
      sumB[i] = px[i] and 0xFF
      work[tail++] = i
    }
    if (tail > 0) break
    java.util.Arrays.fill(dist, -1)
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
      // 波前可以穿过 soft 渐隐坡（软边种子在坡内侧，不放行就一步也迈不进补色区），
      // 但第 4 步只往 outside 格写色 ⇒ 补色区域逐格不变。
      if (soft[j] != 1.toByte()) continue
      if (dist[j] < 0) {
        dist[j] = d
        cnt[j] = cnt[i]
        sumR[j] = sumR[i]
        sumG[j] = sumG[i]
        sumB[j] = sumB[i]
        work[tail++] = j
      } else if (dist[j] == d) {
        // ⚠ 2026-11-18 修溢出：旧写法 cnt[j]+=cnt[i]、sum+=sum[i] 把父格已累加的计数整份
        //   再加进来，cnt 数的是"最短路径条数"而非种子数，随距离指数爆炸（parsec 底角
        //   d=196 时 cnt=4.78 亿、sum 负穿 Int → 补色变黄/黑垃圾 = 桌面马赛克）。
        //   改为只加父格的**平均色**、cnt 只 +1：cnt 恒 ≤4、sum 恒 ≤1020，永不溢出；
        //   单种子区域（cnt=1）逐字不变，等距相遇仍是平滑平均（无硬缝）。
        cnt[j] += 1
        sumR[j] += (sumR[i] + cnt[i] / 2) / cnt[i]
        sumG[j] += (sumG[i] + cnt[i] / 2) / cnt[i]
        sumB[j] += (sumB[i] + cnt[i] / 2) / cnt[i]
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
  return fill
}

/**
 * 设计留缝：上下（或左右）被实色夹住、厚度 ≤ 边长/8 且连续长度 ≥ 半边长的透明通道
 * = 图标自己的缝（主人 2026-11-19：「查看日历那个镂空的横档呢」——outlook 日历顶块与
 * 身体之间那根横档），不是背景 ⇒ 从 outside 退标成 2，不补色、留 α0 交镂空掩码抠回透明。
 * 缝两端通外面，第 1 步洪泛会把它当外面填实 = 横档消失；封闭圆点那族不走这里（本就不是 outside）。
 *
 * ⚠ 厚度 = 到上/下实色距离之**和**（α≥[SOLID_ALPHA]，与种子兜底同数），不是半厚：
 *   半厚口径 D 会漏缝的首尾几行（384 档 D=24 时 27px 厚的缝只退中间 17行，缝上下留蓝条）。
 * ⚠ 缝缘一圈 α 渐隐格也归 α0 并标 2：它们不在 outside 里 ⇒ 补色场在它们是 0，composeOver
 *   把半透明压黑底 = 缝上下两条黑细线（384 正对照看图抓到）。缝是直边，抹渐隐无锯齿代价；
 *   **不碰封闭圆点那族的渐隐**（那族压在蓝底上是已验收的样子）。
 * 射程现量：四份语料 293 张延伸支只开火日历 1 张（离线台 `_extfill.cjs --mode=prod --slots`）。
 */
private fun unmarkDesignSlots(px: IntArray, w: Int, h: Int, outside: ByteArray): Int {
  val n = w * h
  val d = maxOf(4, Math.round(w / 8f))
  val big = 1 shl 28
  val a = IntArray(n)
  val b = IntArray(n)
  fun ink(i: Int) = (px[i] ushr 24) >= SOLID_ALPHA
  for (x in 0 until w) {
    var t = big
    for (y in 0 until h) { val i = y * w + x; a[i] = t; t = if (ink(i)) 0 else t + 1 }
    t = big
    for (y in h - 1 downTo 0) { val i = y * w + x; b[i] = t; t = if (ink(i)) 0 else t + 1 }
  }
  val slot = ByteArray(n)
  var un = 0
  fun sandV(i: Int) = outside[i] == 1.toByte() && a[i] + b[i] <= d + 1
  for (y in 0 until h) {
    var x = 0
    while (x < w) {
      if (!sandV(y * w + x)) { x++; continue }
      var x2 = x
      while (x2 < w && sandV(y * w + x2)) x2++
      if (x2 - x >= w / 2) for (xx in x until x2) { val i = y * w + xx; outside[i] = 2; slot[i] = 1; un++ }
      x = x2
    }
  }
  for (y in 0 until h) {
    var t = big
    for (x in 0 until w) { val i = y * w + x; a[i] = t; t = if (ink(i)) 0 else t + 1 }
    t = big
    for (x in w - 1 downTo 0) { val i = y * w + x; b[i] = t; t = if (ink(i)) 0 else t + 1 }
  }
  fun sandH(i: Int) = outside[i] == 1.toByte() && a[i] + b[i] <= d + 1
  for (x in 0 until w) {
    var y = 0
    while (y < h) {
      if (!sandH(y * w + x)) { y++; continue }
      var y2 = y
      while (y2 < h && sandH(y2 * w + x)) y2++
      if (y2 - y >= h / 2) for (yy in y until y2) { val i = yy * w + x; outside[i] = 2; slot[i] = 1; un++ }
      y = y2;
    }
  }
  for (i in 0 until n) {
    if (slot[i] != 1.toByte()) continue
    val x = i % w
    val y = i / w
    if (x > 0) slotFringe(px, outside, i - 1)
    if (x + 1 < w) slotFringe(px, outside, i + 1)
    if (y > 0) slotFringe(px, outside, i - w)
    if (y + 1 < h) slotFringe(px, outside, i + w)
  }
  return un
}

private fun slotFringe(px: IntArray, outside: ByteArray, j: Int) {
  val al = px[j] ushr 24
  if (al in 9..254) {
    px[j] = px[j] and 0x00FFFFFF
    outside[j] = 2
  }
}

/** 源图把圆烤在不透明白方块上时（微信接龙管家快捷方式），白角是 α255 实像素：
 * planOf 的可见占比把白角算进去 ⇒ 走延伸支，但 computeFill 只填**透明**外面 ⇒ 白角原样留下。
 * 这里把「内切圆的白色补集」认成背景清零 α，交给后面的 bleedEdgeColor/computeFill 按边缘色补满。
 *
 * 判据 = 中性白（min≥235 且 max−min≤12、α≥128）的 4-连通块里，**四个角各有一块、
 * 每块恰好碰两条相邻边、四块面积同余（max/min≤1.15）且每块≥0.5% 光栅**。
 * 这是「圆/圆角方贴在白底上」的几何签名；设计白底不长这样：
 * 易班熊的右上白底 1 块、translate 1 块、kazumi/高德 2 块、白盘环碰四边 —— 全不满足。
 * ⚠ 第一版「碰两相邻边就清」在离线语料开火 6 张设计白底（易班 60331 格=熊的背景），已否。
 * 白→盘色的不透明渐隐环同清（只从已清零格往外长，min≥150 且 spread≤110，最多 4 环）：
 * 留着会当 computeFill 种子把四角补色拉向白（正对照看图=米色角）。清完调用方再跑一遍
 * cleanRim：r3c 那遍看到的是白角环，盘缘噪点没清 ⇒ 放射条纹（正对照第二版看图抓到）。
 * 射程现量：349 张源（runtime 语料+201 快捷方式+微信/bili 设备源）只开火接龙管家两代源图；
 * 141 张延伸合成负对照带/不带 --wc 逐字节相同。
 */
private fun stripBakedWhiteCorners(px: IntArray, w: Int, h: Int): Int {
  val n = w * h
  val white = ByteArray(n)
  for (i in 0 until n) {
    val p = px[i]
    if ((p ushr 24) < 128) continue
    val r = (p ushr 16) and 255
    val g = (p ushr 8) and 255
    val b = p and 255
    val mn = minOf(r, g, b)
    if (mn >= 235 && maxOf(r, g, b) - mn <= 12) white[i] = 1
  }
  val lab = IntArray(n) { -1 }
  val stack = IntArray(n)
  val sides = ArrayList<Int>()
  val sizes = ArrayList<Int>()
  for (s in 0 until n) {
    if (white[s] != 1.toByte() || lab[s] >= 0) continue
    val id = sides.size
    var sp = 0
    var size = 0
    var mask = 0
    stack[sp++] = s
    lab[s] = id
    while (sp > 0) {
      val i = stack[--sp]
      size++
      val x = i % w
      val y = i / w
      if (y == 0) mask = mask or 1
      if (y == h - 1) mask = mask or 2
      if (x == 0) mask = mask or 4
      if (x == w - 1) mask = mask or 8
      if (x > 0 && white[i - 1] == 1.toByte() && lab[i - 1] < 0) { lab[i - 1] = id; stack[sp++] = i - 1 }
      if (x + 1 < w && white[i + 1] == 1.toByte() && lab[i + 1] < 0) { lab[i + 1] = id; stack[sp++] = i + 1 }
      if (y > 0 && white[i - w] == 1.toByte() && lab[i - w] < 0) { lab[i - w] = id; stack[sp++] = i - w }
      if (y + 1 < h && white[i + w] == 1.toByte() && lab[i + w] < 0) { lab[i + w] = id; stack[sp++] = i + w }
    }
    sides.add(mask)
    sizes.add(size)
  }
  // 碰边掩码：T=1 B=2 L=4 R=8 ⇒ 四角块 = TL5 / TR9 / BL6 / BR10
  val want = intArrayOf(5, 9, 6, 10)
  val pick = IntArray(4) { -1 }
  for (id in sides.indices) for (k in 0..3) if (sides[id] == want[k]) pick[k] = id
  if (pick.any { it < 0 }) return 0
  val sz = pick.map { sizes[it] }
  if (sz.max() / sz.min().toDouble() > 1.15 || sz.min() < n * 0.005) return 0
  val kill = BooleanArray(sides.size)
  for (id in pick) kill[id] = true
  val zeroed = ByteArray(n)
  var un = 0
  for (i in 0 until n) {
    if (lab[i] >= 0 && kill[lab[i]]) { px[i] = px[i] and 0x00FFFFFF; zeroed[i] = 1; un++ }
  }
  // 白→盘色的不透明渐隐环留着会当 computeFill 的种子，把四角补色拉向白（正对照看图=米色角）。
  // 只从已清零格往外长：min≥150 且 spread≤110 才认（环实测 min225..241/spread13..30，
  // 盘黄 min76/spread179 在第一层就停住），最多 4 环。
  for (pass in 0 until 4) {
    var grew = false
    for (i in 0 until n) {
      if (zeroed[i] == 1.toByte()) continue
      val p = px[i]
      if ((p ushr 24) < 128) continue
      val r = (p ushr 16) and 255
      val g = (p ushr 8) and 255
      val b = p and 255
      val mn = minOf(r, g, b)
      if (mn < 150 || maxOf(r, g, b) - mn > 110) continue
      val x = i % w
      val y = i / w
      val near = (x > 0 && zeroed[i - 1] == 1.toByte()) || (x + 1 < w && zeroed[i + 1] == 1.toByte()) ||
        (y > 0 && zeroed[i - w] == 1.toByte()) || (y + 1 < h && zeroed[i + w] == 1.toByte())
      if (near) { px[i] = p and 0x00FFFFFF; zeroed[i] = 1; un++; grew = true }
    }
    if (!grew) break
  }
  return un
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
  qsWalked.incrementAndGet()
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

/**
 * 磁贴判据的**成本账**（2026-11-16）：那条判据要抓整条线程堆栈，是这一层最贵的一步。
 * `走` = 真的抓了堆栈（SystemUI 里的老式图标）；`免` = 自适应/已包过的图标提前放行、没抓。
 * 没有这两个数，"把自适应判断提前省了堆栈钱"就只是结构推断 —— 省了多少份要看真机读数。
 */
private val qsWalked = java.util.concurrent.atomic.AtomicLong(0)
private val qsAvoided = java.util.concurrent.atomic.AtomicLong(0)

private fun noteWrap(from: String) {
  com.iamcanincan.opticon.runtime.Stats.bump("自适应")
  val key = "$from ← $clipHost"
  wrapTally.merge(key, 1) { a, b -> a + b }
  if (wrapTally[key] == 1) Log.d(TAG, "自适应新开 host=$clipHost 通道=$from（第一次）")
  val n = wrapCount.incrementAndGet()
  if (n % RANGE_DUMP_EVERY == 0L && wrapDumps.getAndIncrement() < RANGE_DUMP_MAX) {
    val rows = wrapTally.entries.sortedByDescending { it.value }.take(8)
      .joinToString(" ; ") { "${it.key} ×${it.value}" }
    Log.d(TAG, "自适应累计 $n 次，通道×宿主 top：$rows" +
      " ｜磁贴判据 走=${qsWalked.get()} 免=${qsAvoided.get()}")
  }
}

/** 每一张"包了"的图标一条。前 [WRAP_LOG_LIMIT] 条。 */
private const val WRAP_LOG_LIMIT = 200

private val wrapLogLeft = AtomicInteger(WRAP_LOG_LIMIT)

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
 * 「round 资源按延伸画」证据行额度（前 [ROUND_LOG_LIMIT] 张）。
 * 这一族 = 应用自己声明了 roundIcon 老式位图、且被本机框架当 icon 交出来的那些；
 * 名单靠这行拿（2026-11-18 parsec / kimichat 两症状的受影响族）。
 */
private const val ROUND_LOG_LIMIT = 40

private val roundLogLeft = AtomicInteger(ROUND_LOG_LIMIT)

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
 * 2026-11-19 起认出是为了**补包进自适应**（主人：「快捷方式也要自适应」）；11-14~11-18 曾反过来用于整体放行。
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
 * 应用自己声明的 **round 资源**（`包名 → roundIconRes`）—— 认出"满幅圆盘老式位图"的依据。
 *
 * 为什么需要（2026-11-18 parsec 应用信息页白圈）：这台 ROM 把 `ApplicationInfo.icon` 直接
 * 填成 roundIconRes（ctor 打标记前现量 icon=0x7f020001 = manifest 的 roundIcon，而 manifest
 * 的 icon 是 0x7f020000），于是那份"本来就是一个完美的圆"的老式圆盘从**标记通道**进了
 * 我们的几何：可见占比 0.765 比 π/4 低一圈抗锯齿 → 被判成小占比 → 缩 0.9166 + 铺白底 =
 * 给完美的圆套白圈。2026-10-15「round 留给平台」那条裁定的前提（icon 与 round 各走各的）
 * 被 ROM 打破了；裁定本身不变 —— 认出它之后按**延伸支**画（角按它自己的边缘色补满，
 * 不缩、不铺底），单色侧跟同一个旗标（[MonochromeSynth.forIcon]）。
 *
 * 登记点：`markIconResId` 打标记时顺手记下（只记 icon 与 round 都非 0 的应用）。
 * 消费点：Resources/APM 取图钩子把请求还原成真实 id 后查这张表。与 [ShortcutIconResources]
 * 同一个套路：登记与"什么时候真的去画"无关，所以不怕异步；键带包名，因为 id 只在包内唯一。
 * 上限 [MAX] 条，超了整体清空（应用数量有限，正常一年也到不了）。
 */
internal object RoundIconResources {
  private const val MAX = 4096
  private val ids = java.util.concurrent.ConcurrentHashMap<String, Int>()

  fun remember(pkg: String?, round: Int) {
    if (pkg.isNullOrEmpty() || round == 0) return
    if (ids.size > MAX) ids.clear()
    ids[pkg] = round
  }

  fun isRound(pkg: String?, rid: Int): Boolean =
    pkg != null && rid != 0 && ids[pkg] == rid
}

/**
 * 包装成自适应图标；**这一层本身绝不抛**。
 *
 * ⚠ 它被挂在宿主的取图热路径上，抛出去就是宿主崩：任何一步失败都原样放行。
 */
fun wrapLegacyIcon(
  icon: Drawable,
  from: String,
  roundArt: Boolean = false,
): Drawable =
  runCatching { wrapInner(icon, from, roundArt) }.getOrElse {
    if (clipFailLogged.compareAndSet(false, true)) {
      Log.w(TAG, "自适应：包装抛了，原样放行（${it.javaClass.simpleName}: ${it.message}）")
    }
    icon
  }

private fun wrapInner(
  icon: Drawable,
  from: String,
  roundArt: Boolean,
): Drawable {
  // 配置按 TTL 重读，所以界面里关掉开关后很快生效，不用重启进程。
  if (!ModuleRuntime.options().circleEnabled) return icon
  // 已经包过的、以及**自适应图标**：都不动。自适应图标自带背景 + 前景，形状由系统 mask 定。
  // ⚠ 这一条必须排在磁贴判据**之前**（2026-11-16）：那条判据要抓整条线程堆栈，
  //   而 SystemUI 里取到的图标大多数是自适应的 —— 它们反正原样放行，何必先付一次堆栈钱。
  //   两条判据对自适应图标的结论相同（都不包），换序不改任何一张图的去向。
  if (icon is CircleIconDrawable || icon is AdaptiveIconDrawable) {
    qsAvoided.incrementAndGet()
    return icon
  }
  // 射程（2026-10-21 起）：**默认全包，唯一的排除是快速设置那一路**（调用方分得开）。
  if (calledByQuickSettings()) {
    noteTileSkip(from)
    return icon
  }

  // 快捷方式不再排除（主人 2026-11-19：「快捷方式也要自适应」，推翻 11-14「别管了」）：
  // 与应用图标同一套两分支。Resources 通道漏出去的那批由 IconHooks 按 ShortcutIconResources /
  // ShortcutLoad 认出来补包，这里不再需要认快捷方式。
  if (wrapLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "自适应 from=$from host=$clipHost : " + IconNames.of(icon) + " " +
      "${icon.intrinsicWidth}x${icon.intrinsicHeight} 无底盘 原尺寸+延伸")
  }
  // ⚠ 这一行同时喂三样东西：统计页的「自适应」计数、按 `通道×宿主` 的射程账、
  //   以及「第一次见到这个宿主」那行日志。2026-11-14 快捷方式整体放行那次重构把它删掉了，
  //   函数留着、调用没了 —— 于是统计页那一格从那天起一直是 0（主人 2026-11-16 报的）。
  //   别把它挪到上面任何一个 branch 里：只有**真要包这张图**的时候才该记一次。
  noteWrap(from)
  if (roundArt && roundLogLeft.decrementAndGet() >= 0) {
    Log.d(TAG, "round 资源按延伸画 host=$clipHost from=$from : " + IconNames.of(icon))
  }
  return CircleIconDrawable(
    icon,
    forceExtend = roundArt,
  )
}
