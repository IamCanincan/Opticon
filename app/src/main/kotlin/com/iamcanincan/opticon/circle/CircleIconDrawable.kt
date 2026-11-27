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

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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
    val dx = adaptiveVisibleInset(b.width())
    val dy = adaptiveVisibleInset(b.height())
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
      // Repair and extend round artwork on the same grid at Settings and launcher icon sizes.
      val roundGrid = forceExtend && w == h
      val rw = legacyRenderSize(w, roundGrid)
      val rh = legacyRenderSize(h, roundGrid)
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
        renderInto(bitmap, layer, rw, rh)
      }
      stage = "f3"
      canvas.drawBitmap(bitmap, null, view, if (forceExtend) bitmapPaint else null)
    } catch (t: Throwable) {
      Log.w(TAG, "前景阶段: " + stage, t)
      throw t
    }
  }

  /**
   * ① 原图 → 交 [IconGeom] 算出几何（填满正方形 / 缩进内切圆），按倍率**重画一遍**；
   * ② 小占比那一支铺白底；③ 其余按边缘色延伸补角。
   *
   * 尺寸与分档的判断**不在这里**（在 [IconGeom]，单色那边调的是同一份）——
   * 这里只负责"把彩色要画的那一刀画出来"。
   */
  private fun renderInto(bitmap: Bitmap, layer: Canvas, w: Int, h: Int) {
    var stage = "r0"
    rendering = true
    try {
      stage = "r1"; bitmap.eraseColor(Color.TRANSPARENT); cache.holes = null
      stage = "r2"
      val geometry = drawLegacyIconGeometry(inner, bitmap, layer, forceExtend)
      val px = geometry.pixels
      val plan = geometry.plan
      if (plan != null && geomLogLeft.decrementAndGet() >= 0) {
        Log.d(TAG, "自适应几何 host=$clipHost fill=" + ((plan.fill * 100).toInt() / 100f) +
          " scale=" + ((plan.scale * 100).toInt() / 100f) +
          " 支=" + (if (plan.plate) "铺底" else "延伸") + " : " + IconNames.of(inner))
      }
      // ⓪ 源图自带的噪边：parsec 的 round PNG 最外圈 α255 实心环自己就是噪的
      //    （r87 逐度 18↔56 交替、r86 光滑），种子踩上去就被径向复制成放射条纹，
      //    bleedEdgeColor 还会把这份噪色沿 AA 坡往外带。先把**带噪的**实心边界环
      //    换成内侧光滑色；光滑环与细笔画一格不动（差 ≤2 不写、窗内无实心核不写）。
      stage = "r3c"
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
        stage = "r4s"; geometry.preparePlate()
        // ② 铺底。不延伸 —— 透明处一律由底承担。默认白底（主人 2026-11-14）；
        //    墨自己整片偏亮时白底=看不见（菜鸟快捷方式 p10=238.6）⇒ 换黑底（主人 2026-11-19）。
        stage = "r5s"
        val light = inkTooLightForWhite(px, w, h)
        if (plateLogLeft.decrementAndGet() >= 0) {
          Log.d(TAG, "铺底色 host=$clipHost 底=" + (if (light) "黑" else "白") + " : " + IconNames.of(inner))
        }
        composeOverColor(px, if (light) Color.BLACK else Color.WHITE)
        stage = "r6s"
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        return
      }
      // ⓪ 源图烤进的白角背景（圆贴在不透明白方块上）：白角是 α255 实像素，洪泛进不去 ⇒
      //    延伸只填透明处、白角原样留下（微信接龙管家）。四角同余白块认成背景清零，见函数 KDoc。
      //    清完再跑一遍 cleanRim：r3c 那遍看到的是白角环，盘缘噪点没被清 ⇒ 会当补色种子放射条纹。
      //    只在开火时跑（射程=接龙家族），成本不摊到每张延伸图标。
      stage = "r3w"
      stage = "r5"
      val fill = geometry.colorExtension()
      // ② 软件合成（源 over 补边）：不再画第二遍 —— 第二遍会把黑 AA 又画回来。
      stage = "r6"; composeOver(px, fill)
      stage = "r7"
      bitmap.setPixels(px, 0, w, 0, 0, w, h)
      // 封闭镂空掩码：渲染里 α0 的格（补边只填得到从外部可达的区域，
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

  /** 墨的亮度 p10 ≥ [PLATE_LIGHT_P10] = 整片墨偏亮、白底上看不见 ⇒ 铺黑底（菜鸟快捷方式）。
   *  用 p10 不用中位：中位会把"白字+深色块"（pdd p10=52.5、中位 255）也算亮，
   *  那种深色块在白底上看得见、不该换底。阈值 206 摆在候选铺底行 p10 的空档 192↔220 中间
   *  （145 行现量）；菜鸟四张 p10=238.6 在档上。直方图与 [IconGeom] 的延伸淡墨否决共用
   *  [IconGeom.inkLumPercentiles] 一份，别各数各的。 */
  private fun inkTooLightForWhite(px: IntArray, w: Int, h: Int): Boolean {
    val p10 = IconGeom.inkLumPercentiles(px, w, h)?.second ?: return false
    return p10 >= PLATE_LIGHT_P10
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

  override fun onBoundsChange(bounds: Rect) {
    val dx = adaptiveVisibleInset(bounds.width())
    val dy = adaptiveVisibleInset(bounds.height())
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

/** 取边色时优先用"够实"的格：抗锯齿边色被拉出去就是一道浅色接缝。
 *  ⚠ [IconGeom.inkLumPercentiles] 也吃它（淡墨判据两边必须数同一批格子）—— 所以不是 private。 */
internal const val SOLID_ALPHA = 160

/** 淡墨门槛 206，两条判据共用但吃不同分位（见 [IconGeom.inkLumPercentiles]）：
 *  铺底换黑底吃 **p10**（[CircleIconDrawable.inkTooLightForWhite]）；延伸淡墨否决吃 **p5**
 *  （[IconGeom] uniformPaleInk，连最暗 5% 都淡才拦，白盘灰字放行）。206 摆在 p10 空档
 *  192↔220、p5 空档 109↔238 中间。⚠ 不是 private：IconGeom 也吃它。 */
internal const val PLATE_LIGHT_P10 = 206

/**
 * 内切圆占正方形的**面积比**：π/4 ≈ 0.7854。
 * 可见占比 >= 它的图标（贴边实心那种）走正常延伸；小于它的缩进内切圆 + 垫对比底
 * （主人 2026-11-14：「大于那个占比，还是正常延伸」）。
 *
 * ⚠ 2026-11-19 主人「那就稍微将占比调小点」：0.785 → **0.778**。π/4 的真值是 0.7854，
 * 旧数 0.785 只留 0.0004 余量，而格子量出来的正圆占比因抗锯齿/源量化 shortfall 0.003~0.007
 * （学习通正圆 96 档 0.7859 在线上、192/384/768 档 0.7840/0.7798/0.7794 全在线下 ⇒ 同一张
 * 圆随尺寸翻支）。0.778 摆在实测空档 **0.773↔0.7794** 中间：非圆铺底行最高 0.773
 * （audiofx/appui），正圆行最低 0.7794（768 档）—— 新线把整族正圆收进延伸，又不碰非圆。
 * 0.773/0.7794 是量出来的，0.778 是我选的空档中点。
 */
internal const val INSCRIBED_SHARE = 0.778f

/**
 * 延伸淡墨否决只作用在**这次挪线新翻进来的那一档** `[INSCRIBED_SHARE, PALE_VETO_TOP)`：
 * 旧线 0.785 以上早已延伸的行（chrome/美团/glimpse…）一个不动，免得"稍微调小"变成重排整族。
 * 这一档里**连笔画都白**的（文件前景白文件夹、菜鸟淡蓝框，p5≥206）若延伸，补出来的白角会把
 * 字藏进白里 ⇒ 否决延伸、仍走铺底（黑底）。**白盘灰字**的学习通 p5=109 不拦 ⇒ 延伸成一整块
 * 白底灰字（主人 2026-11-20：「要一整块白底」）。混色的（消息 p10=50、混元 154）照常延伸。
 */
internal const val PALE_VETO_TOP = 0.785f

/**
 * 小占比那一支缩进内切圆之后，内容最大半径占圆半径的多少（= 圆边留多少余量）。
 * 0.98 是"刚好贴住圆"；主人 2026-11-14「再稍微缩一点」→ 0.92 →「再缩一点点」→ 0.88（圆边留 12%）。
 * 想再紧/再松只改这一个数。
 */
internal const val CIRCLE_FIT = 0.88f

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

/** 「铺底色 底=黑/白」证据行额度（前 [GEOM_LOG_LIMIT] 张铺底支）。 */
private val plateLogLeft = AtomicInteger(GEOM_LOG_LIMIT)

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
