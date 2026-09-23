package com.iamcanincan.opticon.circle

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.iamcanincan.opticon.notify.IconBitmap
import com.iamcanincan.opticon.runtime.TAG
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 给「没提供单色图标的应用」合成一个单色层 —— **用的是通知那半边同一套剪影算法**。
 *
 * ## 为什么要有这个
 * Android 16 的「强制动态取色」下，桌面（`com.android.launcher3.icons.MonochromeIconFactory`）
 * 会为**没提供单色图标**的应用现场生成一张掩码，而它算出来的**极性经常是反的**：
 * 深色字形 + 浅色底的图标会变成「字形是洞、底色是形」，整个反掉。
 *
 * 与其等它算错、再把输出整张翻回来（那是 [IconHooks] 里 `hookForcedThemedMono` 干的事），
 * 不如**在 `AdaptiveIconDrawable.getMonochrome()` 上直接返回一个极性正确的合成层**：
 * 桌面只在这个 getter 返回 null 时才去 new 那个 Factory，所以我们一返回非 null，
 * 那条算错的路径根本不会进。
 *
 * ## 方案：**通知小图标那套**，两支，不为任何一类图标单独开分支
 * 通知侧（`NotificationIconPatch.launcherIconMonochrome`）做的是
 * `fill(foregroundOf(图标))` → `grayscale(...)`。桌面只读 alpha，所以那一步的**两个成分
 * 各自成了这里的一支**，实现都放在 [IconBitmap] 里、与通知**共用同一份代码**：
 *
 * 1. **透明像素真的构成背景** → [IconBitmap.alphaSilhouette]，即 `grayscale` 保留 alpha
 *    那一步（与 [IconBitmap.monochrome] 的 `useAlpha` 支同一段实现）。
 *    ⚠ 判据必须**先裁到内容外接框再量** —— 自适应前景层四周那 53% 的安全区留白不是背景，
 *    带着它去量会把「满幅不透明的整块底」误判成「透明底 + 图形」，压出来就是一整块色
 *    （淘宝就是这么变成大色块的）。通知侧那句「先 fill 再 monochrome，fill 裁掉的透明边
 *    正好是判断形状的凭据」说的就是这一步。
 * 2. **否则** → [IconBitmap.luminanceMask]，即 `grayscale` 把灰度留下的那一步
 *    （桌面只读 alpha，所以灰度写进 alpha）。通知侧对它的说明是「桌面图标是一整块不透明的
 *    彩色图形，二值化只会得到它的外轮廓（圆形图标就压成一个白圆），细节全丢；
 *    灰度化保留了明暗层次」—— 所以这一类**就该走灰度**，不是特例。
 *
 * 两支都不成立（光栅化失败、或纯色块没有任何对比度）才返回 null，桌面照旧走自己的路。
 *
 * 取前景层那一步没有直接用 [IconBitmap.foregroundOf]，而是在这里自己写了 [rasterize]：
 * 它会**还原原 drawable 的 bounds**（理由写在那上面）。
 * 通知侧是「拿一个图标画一次就扔」、不需要还原，所以那边保持原样。
 *
 * ## 两套口径，别混：判定用「满幅」，位置用「可见区」
 * 这是本文件最容易写错的地方 —— 2026-09-24 真机「非自适应图标全部变成色块」的病根：
 * - **判定**：两支判据都要先看「透明像素占多少」或「亮度铺开多少」，所以喂给它们的
 *   必须是**满幅光栅化**的结果。提前缩进可见区会凭空多出一圈透明边，
 *   把满幅不透明的老式图标误判成「透明底 + 图形」→ 压出一整块方色块。
 * - **位置**：非自适应图标的内容铺满整个 bounds，而它在图层 bounds 里只该占中间 2/3，
 *   所以掩码算完**之后**才缩进可见区（[intoVisibleArea]）。自适应前景层本来就在
 *   108dp 画布上、天然落中间 61%，不用动。
 *
 * ## 坐标系：掩码必须是「整张 108dp 画布」，不是可见区
 * 这一点最容易搞反，而且搞反了不会报错、只会画歪，所以把**证据**记在这里：
 *
 * 1. 桌面拿到非 null 的 `getMonochrome()` 后，是把它当成**前景层**塞进一个新的
 *    `AdaptiveIconDrawable`：`MonoIconThemeController.createThemedAdaptiveIcon` 的字节码是
 *    `if (getMonochrome() != null) return new AdaptiveIconDrawable(new ColorDrawable(colors[0]), mono)`
 *    （`MonoIconThemeController` 在 `com.android.launcher3.icons.mono` 包下）。
 * 2. `AdaptiveIconDrawable.updateLayerBoundsInternal` 会给**每一个子层**设
 *    `inset = bounds / (VIEW_PORT_SCALE × 2)`、即**图层 bounds = 1.5 × 图标尺寸**；
 *    而 `draw()` 只把中央那 2/3 画进 `mLayersBitmap`（尺寸 = 图标尺寸），其余被裁掉。
 *    所以桌面交给我们的 `bounds` 是**图层 bounds**，可见区只是它中间 2/3。
 *
 * 于是：掩码要按「108dp 画布」出图 —— 前景层 setBounds 到 `(0, 0, edge, edge)`
 * （`edge` = 图层 bounds），图形自然落在画布中间 61%（= 66dp / 108dp），
 * 可见区里就是 61% × 1.5 = 92%（= 66dp / 72dp，正是自适应图标的安全区）。
 * **绝不能**按 inset 给 bounds：那会把图形画成 92% 的「画布」，到可见区里就是 137%、
 * 边缘被裁掉一圈。这个错误不会崩、也不会报错，只会让图形明显偏大且缺边。
 *
 * ## 三个与通知侧不同的处理
 * 1. **不做 `fill()`** —— 通知要填满状态栏那点小尺寸，所以裁掉四周空白再放大；
 *    桌面单色层必须与上面那个坐标系对齐，一 fill 图形就会撑出可见区。
 * 2. **`preserveAlpha = true`** —— 保留边缘的抗锯齿值，不做 0/255 二值化。
 *    96px 的状态栏图标看不出硬边，桌面图标大得多，硬边放大就是一圈锯齿。
 *    ⚠ 它只影响**写出去的 alpha**，「哪些像素算图形」的判据仍然是
 *    `IconBitmap` 里的 `MIN_SHAPE_ALPHA`（128）—— 两者不能混，混了掩码就会
 *    被一整片淡色铺满（桌面图标的前景层常自带柔光/半透明底板）。
 * 3. **按桌面要的尺寸出图**（见 [MonochromeLayer]）—— 不固定一个尺寸再让桌面放大。
 *    桌面渲染多大就压多大，这是「糊」的根源。
 *
 * 顺带一提：桌面自己那条 Factory 路径（`MonochromeIconFactory`）出图尺寸是
 * `round(size × 2 × VIEW_PORT_SCALE) = 4/3 × size`，再被 `InsetDrawable(-0.25)` 撑到
 * 1.5 × size —— 它自己就在做一次 1.125× 放大。我们按图层 bounds 精确出图，
 * 比它清楚，不需要跟着放大。
 *
 * ## 天然降级，不需要开关
 * 合成只在 `getMonochrome()` 原本返回 null 时才介入，且任何一步失败都返回 null ——
 * 那时桌面照旧走 Factory，而那道翻转仍然挂着兜底。两条路是**互斥**的：
 * 合成生效 → 桌面不进 Factory → 翻转自然不会被触发。
 *
 * 两条出口都会在日志里留痕（[noteGiveUp] / [noteBranch]），不会和「压根没进这条函数」
 * 混在一起。
 */
internal object MonochromeSynth {

  /**
   * 首次合成的边长。桌面随后 `setBounds` 到**图层 bounds**（= 1.5 × 图标尺寸），
   * 比这个大就按需重合成（见 [MonochromeLayer.draw]）—— 所以这里只是「够用」而非「精确」。
   */
  private const val MIN_EDGE = 192

  /** 缓存上限。图标数量本来就有界，超过就整体清掉重来，避免长歪。 */
  private const val CACHE_LIMIT = 256

  /**
   * 按图标**实例**缓存已经压好的**掩码位图**（[IdentityHashMap] 语义：同一个对象才命中）。
   *
   * 用实例而不是内容做 key：桌面滚动时同一个图标对象会被反复取 monochrome，
   * 每次都重压一遍太贵。图标对象由桌面的 IconCache 长期持有、数量有界，
   * 所以这里不会无限增长。
   *
   * ⚠ 缓存的是**位图**而不是 [MonochromeLayer] 实例：掩码是只读的，可以放心共享，
   * 而 Drawable 实例不行 —— 同一个实例被两个父 `AdaptiveIconDrawable` 同时持有的话，
   * `setBounds` / `setCallback` 会互相抢（图层 bounds 是父级在 `onBoundsChange` 里设的）。
   * 所以每次 [forIcon] 都交出一个**新实例**，只共享那张位图。
   */
  private val cache = IdentityHashMap<Drawable, Bitmap>()

  /**
   * 给 [source] 合成一个单色层；合成不出来就返回 null，让调用方照原样走下去。
   *
   * @param source 原图标。通常是 `AdaptiveIconDrawable`，也可能是别的 Drawable。
   */
  @Synchronized
  fun forIcon(source: Drawable): Drawable? {
    val hit = cache[source]
    if (hit != null) return MonochromeLayer(source, hit).also { noteSynthesized() }
    // 先按最小边压一次：既是「能不能压出形状」的探测（压不出来就别交个空壳出去，
    // 那会让桌面以为有单色图标、结果画出一片空白），也是尺寸未知时的默认输出。
    val mask = render(source, MIN_EDGE) ?: return null
    if (cache.size >= CACHE_LIMIT) cache.clear()
    cache[source] = mask
    return MonochromeLayer(source, mask).also { noteSynthesized() }
  }

  /**
   * [MonochromeLayer] 在按需重压之后回填缓存。
   *
   * 不回填的话，下一次 [forIcon] 又会从 [MIN_EDGE] 的小尺寸重来一遍、然后在 `draw()` 里
   * 再重压一次 —— 每换一次主题、每个图标白做两次全像素光栅化。掩码只会变大不会变小，
   * 所以直接覆盖没有回退风险。
   */
  @Synchronized
  fun remember(source: Drawable, mask: Bitmap) {
    if (cache.size >= CACHE_LIMIT) cache.clear()
    cache[source] = mask
  }

  /**
   * 累计交出去多少张单色层。**两条入口都经过 [forIcon]，所以一个计数器就够**。
   *
   * 为什么非要有这个埋点：默认配置（裁圆开着）下桌面拿到的图标几乎都是
   * [CircleIconDrawable]，那条路走的是它自己的 `getMonochrome()` override、
   * **不经过** `IconHooks.hookSynthesizedMonochrome` 里那个计数器 —— 也就是说
   * 主路径原先一条日志都没有。而这个项目已经吃过「hook 的提前 return 静默返回、
   * 最难查」的亏，所以补上：看到 `Mono: N layers synthesized` 就说明合成真的在跑，
   * 而不是悄悄返回 null 让桌面走了老路。
   *
   * 前 [LOG_FIRST] 条逐条打，之后每 [LOG_EVERY] 条汇总一次 —— 图标加载是热路径
   * （刷一次应用列表上千次），全打会把 logcat 冲掉。
   */
  private fun noteSynthesized() {
    val total = synthesized.incrementAndGet()
    if (total <= LOG_FIRST || total % LOG_EVERY == 0) {
      Log.d(TAG, "Mono: $total layers synthesized")
    }
    // 诊断：谁在问这一层。桌面有好几条拿 monochrome 的路（`createThemedAdaptiveIcon` /
    // `createThemedBitmap` / Factory …），只有堆栈能一眼分辨实际走的是哪条 ——
    // 光看「有没有 tint」推断不出来。
    if (total <= STACK_LIMIT) Log.d(TAG, "Mono: who asks #$total\n" + stackTraceOf(STACK_FRAMES))
  }

  private val synthesized = AtomicInteger(0)

  private const val LOG_FIRST = 3

  private const val LOG_EVERY = 40

  /**
   * 按 [edge] 边长压一张单色掩码（ALPHA_8）；压不出像样的形状时返回 null。
   *
   * [edge] 的语义是**图层 bounds**（= 1.5 × 图标尺寸），掩码按整张 108dp 画布出图 ——
   * 为什么是 108dp 画布而不是可见区，见类文档里的坐标系那一节。
   *
   * 加锁的理由和 [IconBitmap.monochrome] 一样：那边用着进程级的复用缓冲。
   * 这里再包一层是为了让 [MonochromeLayer.draw] 的按需重合成也走同一条路。
   */
  @Synchronized
  fun render(source: Drawable, edge: Int): Bitmap? {
    if (edge <= 0) return null
    return runCatching {
      val adaptive = source is AdaptiveIconDrawable
      val label = describe(source, adaptive)
      val target = if (adaptive) source.foreground else source
      if (target == null) {
        noteGiveUp(edge, label, "取不到前景层")
        return@runCatching null
      }
      val raster = rasterize(target, edge)
      if (raster == null) {
        noteGiveUp(edge, label, "光栅化失败")
        return@runCatching null
      }
      // ── 走的**就是通知小图标那套方案的两支**，不为任何一类图标单独开分支 ──
      //
      // 通知侧（`NotificationIconPatch.launcherIconMonochrome`）是
      // `fill(foregroundOf(图标))` → `grayscale(...)`。桌面只读 alpha，所以那一步的
      // **两个成分各自成了这里的一支**，函数都在 [IconBitmap] 里、与通知共用同一份实现：
      //
      // 1. **透明像素真的构成背景** → 取 alpha（[IconBitmap.alphaSilhouette]）。
      //    它就是 `grayscale` 保留 alpha 那一步，走的是 [IconBitmap.monochrome] 里
      //    `useAlpha` 支的同一段代码。判据先裁到内容外接框再量 ——
      //    通知侧那句「先 fill 再 monochrome，fill 裁掉的透明边正好是判断形状的凭据」
      //    说的就是这一步；不裁框，自适应图标四周的安全区留白会把一张满幅不透明的底
      //    误当成「透明底 + 图形」，压出那块底的轮廓 = 一整块色（淘宝就是这么坏的）。
      // 2. **否则** → 把**灰度**写进 alpha（[IconBitmap.luminanceMask]），
      //    也就是 `grayscale` 那一步的移植。通知侧对它的说明是「桌面图标是一整块
      //    不透明的彩色图形，二值化只会得到它的外轮廓（圆形图标就压成一个白圆），
      //    细节全丢；灰度化保留了明暗层次」—— 所以这一类**就该走灰度**，不是特例。
      //
      // 两条都不成立时返回 null，桌面照旧走它自己的路（[noteGiveUp] 会留痕）。
      val silhouette = IconBitmap.alphaSilhouette(raster)
      val branch: String
      val base: Bitmap?
      if (silhouette != null) {
        branch = "alpha 轮廓"
        base = silhouette
      } else {
        val split = IconBitmap.analyzeSplit(raster)
        branch = "亮度掩码（图形偏${if (split.figureIsDark) "暗" else "亮"}）"
        base = IconBitmap.luminanceMask(raster, invert = split.figureIsDark)
      }
      noteBranch(edge, label, branch, base != null)
      // 非自适应图标的内容是满幅的，而它在图层 bounds 里只该占中间那 2/3（可见区），
      // 所以掩码算完**之后**才缩进 —— 顺序反了就会踩 [rasterize] 上面那个坑。
      val placed = if (adaptive) base else base?.let { intoVisibleArea(it, edge) }
      val mask = placed?.let { toAlphaMask(it) }
      noteDiagnosis(edge, label, raster, mask, silhouette != null)
      raster.recycle()
      if (placed !== base) placed?.recycle()
      base?.recycle()
      mask
    }.getOrNull()
  }

  /**
   * 诊断用：这个图标到底是哪种 drawable、前景层多大、走的哪条口径。
   *
   * 光看 alpha 直方图认不出源 —— 「66% 落在 1~127」既可能是柔光、也可能是
   * 半透明底板、还可能是低分辨率位图被放大。把类名和固有尺寸一起打出来，
   * 才能把数字对上号（`BitmapDrawable@108x108` 和 `VectorDrawable@-1x-1` 是两回事）。
   *
   * `adaptive=` 是**这次要看的关键位**：`false` 表示这是非自适应图标 ——
   * 它的剪影走的是「满幅算完再缩进可见区」那条路，正是「全是色块」问题所在的分类。
   */
  private fun describe(source: Drawable, adaptive: Boolean): String {
    val foreground = (source as? AdaptiveIconDrawable)?.foreground
    return buildString {
      append(source.javaClass.simpleName)
      if (foreground != null) {
        append(" fg=")
        append(foreground.javaClass.simpleName)
        append("@")
        append(foreground.intrinsicWidth)
        append("x")
        append(foreground.intrinsicHeight)
      }
      append(" intrinsic=")
      append(source.intrinsicWidth)
      append("x")
      append(source.intrinsicHeight)
      append(" bounds=")
      append(source.bounds.width())
      append("x")
      append(source.bounds.height())
      append(" adaptive=")
      append(adaptive)
    }
  }

  /**
   * 诊断：这一次**放弃了合成**，以及为什么。
   *
   * 存在的理由是本项目吃过的那条亏 —— **hook 的每条提前 return 都要留痕**。
   * 「返回 null」在功能上是正常降级（桌面会走自己的路），但和「压根没进这条函数」
   * 在日志里长得一模一样，排查时就分不清「合成失败」还是「合成没被调用」。
   */
  private fun noteGiveUp(edge: Int, label: String, reason: String) {
    val n = diagnosed.incrementAndGet()
    Log.d(TAG, "Mono: diag#$n edge=$edge $label 放弃合成：$reason")
  }

  /**
   * 诊断：这个图标走了哪条分支（alpha 轮廓 / 亮度掩码），以及有没有真的交出掩码。
   *
   * 和 [noteGiveUp] 同一个理由 —— 每一条出口都必须留痕，否则「走了哪条路」和
   * 「压根没进这条函数」在日志里长得一模一样。
   * 两条分支的**分布**也要能看出来：哪条路走得多、哪条路容易压不出东西，
   * 是复核判据该定在哪的唯一依据。
   */
  private fun noteBranch(edge: Int, label: String, branch: String, produced: Boolean) {
    val n = diagnosed.incrementAndGet()
    Log.d(TAG, "Mono: diag#$n edge=$edge $label $branch → " +
      if (produced) "已交出掩码" else "压不出掩码，放弃合成")
  }

  /**
   * 诊断：把「这一张掩码怎么来的、长什么样」打进日志。
   *
   * ## 为什么要有它
   * 桌面主题图标这块**我看不见** —— 我的图像通道是 filtered 的，用户也明确不要我截图。
   * 而「形状不对」有好几种**成因完全不同**的可能（走了 alpha 分支但输入本身是糊的 /
   * 亮度掩码把整块底当成了图形 / 掩码没问题但下游画坏），靠推演猜已经错过一次，
   * 所以这里把三样东西直接打出来：
   * 1. **输入 alpha 的四段直方图**（`a0/a1/a2/a255`）：一眼能看出这张图是
   *    「透明底 + 图形」还是「整张不透明」。
   * 2. **两支判据的决策过程**（`split …`，由 [IconBitmap.analyzeSplit] 复算真算法给出）：
   *    `box` / `boxfill` 是 alpha 那一支的判据（`boxfill` 到 1.00 就是「一整块底」）；
   *    Otsu 阈值、两侧占比、两侧的「贴外部」比例、图形偏哪一侧、亮度直方图是亮度那一支
   *    的依据 —— 只有它能把「结果不对」定位到具体哪一步。
   * 3. **掩码本身的 ASCII 缩略图**：`#` 是不透明、`.` 是透明。把 384² 降采样成 40×40
   *    打进日志，于是「是一个完整字形」还是「一堆碎片」在日志里就能直接看出来。
   *    ⚠ 缩略图**只给降级到亮度掩码的那些**（没走 alpha 轮廓这条主路），上限
   *    [ASCII_DUMP_LIMIT] 张 —— alpha 轮廓已经验过，缩略图留给真正要看形状的样本。
   *
   * 汇总行**不打上限**（每个图标一行）：上一轮的教训正是采样太少 ——
   * 只看得到前几个图标，而前几个往往同属一类，真正出问题的那一类一次都没出现。
   * 图标数量本来就有界，一行一条的成本可以忽略。
   *
   * 形状统计：`out` 掩码里 ≥128 的像素占比；`fill` 是**按 alpha 加权的等效覆盖率**
   * （Σalpha / 255 / 总面积）—— 图标看起来「被填满多少」看的是这个，
   * `out` 小但 `fill` 大就说明掩码里塞了一大片淡色（色块的典型特征）。
   * `cc` 四邻域连通块数、`top` 最大一块占输出像素的比例 —— 完整字形 cc 很小、top 接近 1；
   * 碎成一片时 cc 很大、top 很低。
   *
   * ## 「一整块色」在数字上长什么样
   * 判据是 `out` ≈ `fill`（掩码本身就是那块色，没有更淡的东西）+ `cc=1` + `top=1.00`
   * + `out` 落在 0.4~0.5 之间。这是**输入就已经是实心方块**的特征（前景层是满幅不透明的
   * 位图，alpha 直接就是那块底的轮廓），不是掩码算法把图形切碎了 ——
   * 切碎的特征是 `cc` 大、`top` 小，两者一眼能分开。
   * 真机实例：淘宝 `out=0.47 cc=1 top=1.00`、`box=0.47 boxfill=1.00`
   * （外接框内被填满 = 一整块底）→ [IconBitmap.alphaSilhouette] 判「透明像素不构成背景」、
   * 降级成亮度掩码之后是「淘」字本身。
   *
   * ⚠ 只是诊断，不参与任何判断。发版前应当把它连同 [ASCII_DUMP_LIMIT] / [dumpAscii]
   * 一起降级/移除。
   */
  private fun noteDiagnosis(
    edge: Int,
    label: String,
    input: Bitmap,
    mask: Bitmap?,
    alphaSilhouette: Boolean,
  ) {
    // ⚠ 汇总行**不打上限**：图标数量本来就有界（一屏一屏地加载），一行一条，
    // 8 MiB 的 logcat 缓冲装得下。采样只覆盖前 12 条正是上一轮的教训 ——
    // 前 12 条往往同属一类，真正出问题的那一类一次都没出现。
    val n = diagnosed.incrementAndGet()
    val w = input.width
    val h = input.height
    val total = w * h
    val pixels = IntArray(total)
    input.getPixels(pixels, 0, w, 0, 0, w, h)
    var a0 = 0
    var a1 = 0
    var a2 = 0
    var a255 = 0
    for (p in pixels) {
      val a = Color.alpha(p)
      when {
        a == 0 -> a0++
        a < 128 -> a1++
        a < 255 -> a2++
        else -> a255++
      }
    }
    // 两支判据的决策过程（由 [IconBitmap.analyzeSplit] 复算真算法给出）：
    // 外接框占比 / 框内填充率是 alpha 那一支的判据（`boxfill` 到 1.00 就是「一整块底」），
    // 阈值 / 两侧占比 / 连通性 / 图形偏哪侧是亮度那一支的极性依据，
    // 亮度直方图则说明输入到底有没有两色可分。
    val split = IconBitmap.analyzeSplit(input)
    val branch = buildString {
      append("split t=")
      append(split.threshold)
      append(" dark=")
      append(pct((split.darkRatio * 1000).toInt(), 1000))
      append(" light=")
      append(pct((split.lightRatio * 1000).toInt(), 1000))
      append(" db=")
      append(ratio(split.darkBorder))
      append(" lb=")
      append(ratio(split.lightBorder))
      append(" figureDark=")
      append(split.figureIsDark)
      append(" box=")
      append(ratio(split.boxRatio))
      append(" boxfill=")
      append(ratio(split.boxFill))
      append(" luma=")
      split.lumaBuckets.forEach { append(String.format(Locale.ROOT, "%02d", (it * 100).toInt())) }
    }

    if (mask == null) {
      Log.d(TAG, "Mono: diag#$n edge=$edge $label in=${w}x$h " +
        "a0=${pct(a0, total)} a1=${pct(a1, total)} a2=${pct(a2, total)} a255=${pct(a255, total)} " +
        "$branch -> 没交出掩码")
      return
    }

    val mw = mask.width
    val mh = mask.height
    val bytes = ByteArray(mask.rowBytes * mh)
    mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    val on = BooleanArray(mw * mh)
    var opaque = 0
    var weight = 0L
    for (y in 0 until mh) {
      val row = y * mask.rowBytes
      for (x in 0 until mw) {
        val a = bytes[row + x].toInt() and 0xff
        weight += a
        if (a >= 128) {
          on[y * mw + x] = true
          opaque++
        }
      }
    }
    // 四邻域连通块：完整的字形是少数几块，碎掉的会是一大片小块
    var components = 0
    var largest = 0
    val stack = IntArray(mw * mh)
    for (start in on.indices) {
      if (!on[start]) continue
      components++
      var top = 0
      var size = 0
      stack[top++] = start
      on[start] = false
      while (top > 0) {
        val i = stack[--top]
        size++
        val x = i % mw
        val y = i / mw
        if (x > 0 && on[i - 1]) { on[i - 1] = false; stack[top++] = i - 1 }
        if (x < mw - 1 && on[i + 1]) { on[i + 1] = false; stack[top++] = i + 1 }
        if (y > 0 && on[i - mw]) { on[i - mw] = false; stack[top++] = i - mw }
        if (y < mh - 1 && on[i + mw]) { on[i + mw] = false; stack[top++] = i + mw }
      }
      if (size > largest) largest = size
    }

    Log.d(TAG, "Mono: diag#$n edge=$edge $label in=${w}x$h " +
      "a0=${pct(a0, total)} a1=${pct(a1, total)} a2=${pct(a2, total)} a255=${pct(a255, total)} " +
      "out=${pct(opaque, total)} fill=${pct((weight / 255).toInt(), total)} " +
      "cc=$components top=${pct(largest, opaque)} $branch")

    // 掩码缩略图**只给降级到亮度掩码的那些**（没走 alpha 轮廓这条主路）：
    // alpha 轮廓已经验过，缩略图留给真正要看形状的样本，省 logcat 也省得淹掉。
    // 上限只是防「一整屏全是坏图标」时把缓冲写爆，不是采样。
    if (!alphaSilhouette) {
      val dumped = asciiDumped.incrementAndGet()
      if (dumped <= ASCII_DUMP_LIMIT) dumpAscii(n, "mask", mw, mh, bytes, MASK_DUMP_THRESHOLD)
    }
  }

  /**
   * 把 ALPHA_8 掩码的原始字节（[maskBytes]）打成 40 列 ASCII：`#` = 不透明、`.` = 透明。
   *
   * 判据用 [threshold]，与 `IconBitmap` 里的 `MIN_SHAPE_ALPHA` 同口径 ——
   * 缩略图如果自己用一套阈值，就和真产出的掩码对不上了，那还不如不打。
   */
  private fun dumpAscii(
    n: Int,
    what: String,
    w: Int,
    h: Int,
    maskBytes: ByteArray,
    threshold: Int,
  ) {
    val cols = ASCII_COLS
    val rows = cols
    val line = StringBuilder(cols)
    for (ry in 0 until rows) {
      line.setLength(0)
      val y0 = ry * h / rows
      val y1 = maxOf(y0 + 1, (ry + 1) * h / rows)
      for (rx in 0 until cols) {
        val x0 = rx * w / cols
        val x1 = maxOf(x0 + 1, (rx + 1) * w / cols)
        var hits = 0
        var cells = 0
        for (y in y0 until y1) {
          for (x in x0 until x1) {
            cells++
            if ((maskBytes[y * w + x].toInt() and 0xff) >= threshold) hits++
          }
        }
        line.append(if (cells > 0 && hits * 2 >= cells) '#' else '.')
      }
      Log.d(TAG, "Mono: diag#$n $what $ry|$line")
    }
  }

  private val diagnosed = AtomicInteger(0)

  /**
   * 放行几条**汇总行**。
   *
   * ⚠ 现在是**不打上限**（见 [noteDiagnosis]）—— 采样太少正是上一轮的教训：只看得到前 2 个
   * 图标，而前几个往往同属一类，真正出问题的那一类在日志里一次都没出现过。
   * 图标数量本来就有界，一行一条的成本可以忽略。
   */
  private const val ASCII_COLS = 40

  /**
   * 「不走主路」的样本最多打几张缩略图。
   *
   * 40 行一张，一张图标一份 —— 这是防「一整屏全是坏图标」时把 logcat 缓冲写爆的护栏，
   * **不是采样**：真出问题的那几类通常只占少数，8 张足够看清形状。
   */
  private const val ASCII_DUMP_LIMIT = 8

  /** [dumpAscii] 的计数，见 [ASCII_DUMP_LIMIT]。 */
  private val asciiDumped = AtomicInteger(0)

  /** 掩码缩略图的阈值，与 `IconBitmap.alphaSilhouette` 里的判据同口径。 */
  private const val MASK_DUMP_THRESHOLD = 128

  /**
   * 诊断用的占比格式化。
   *
   * ⚠ 必须显式给 [Locale.ROOT]：不指定时 `String.format` 用默认 locale，
   * 在小数点用逗号的地区（de / fr 等）会打出 `0,08` —— 日志是给人对着源码读的，
   * 换个系统语言就变形了。lint 的 `DefaultLocale` 也会拦这一条。
   */
  private fun pct(part: Int, whole: Int): String =
    if (whole <= 0) "0" else String.format(Locale.ROOT, "%.2f", part.toFloat() / whole)

  /** 诊断用的两位小数（比例本来就是 0..1，不用再除）。 */
  private fun ratio(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

  /**
   * 把 [target] 光栅化成 `edge × edge` 的位图，一律 `setBounds(0, 0, edge, edge)`。
   *
   * `edge` 的坐标系是**图层 bounds**（= 1.5 × 图标尺寸，也就是整张 108dp 画布）——
   * 为什么不是可见区，见类文档的坐标系那一节。
   *
   * ## ⚠ 两种图标都不缩进 —— 缩进挪到掩码算完之后（[intoVisibleArea]）
   * 非自适应图标的内容是满幅的，看起来「应该先缩到可见区再光栅化」。**不行**，
   * 这是 2026-09-24 真机「非自适应图标全部变成色块」的病根：
   *
   * 缩进会在四周留出一圈 `edge/6` 的透明边，而两支判据第一眼都是「透明像素占多少」
   * （[IconBitmap.alphaSilhouette] 裁到内容外接框之后量，那圈边会让外接框缩到中间
   * 2/3、把一张**满幅不透明**的老式图标量成「透明 44% / 实心 56%」）——
   * 正好落进「透明底 + 图形」的特征里（透明 > 5% 且实心 < 70%），于是走了 alpha 分支。
   * 而那个 alpha 描述的是**整张方图的轮廓**，压出来就是一整块实心方块 ——
   * 上色之后就是一块色。
   *
   * 满幅光栅化（不缩进）之后，同一张图是「透明 0% / 实心 100%」→ 判据正确地落到
   * **亮度掩码**分支（[IconBitmap.luminanceMask]），与通知侧处理同类图标的口径完全一致
   * —— 这也正是「要通知那套算法」的本意。
   *
   * 于是分工是：**分支判定用满幅口径**（判据只看得到这一张），
   * **位置用可见区口径**（[intoVisibleArea]）。两者各自独立、互不干扰。
   *
   * ⚠ 为什么必须还原 bounds：自适应图标的前景层是**应用那份图标对象自己持有的**
   * （`sourceIcon.foreground`），不是我们的副本 —— 改它的 bounds，改的是应用那份。
   * 而 `AdaptiveIconDrawable.draw()` 重画图层时用的是**子层当前的 bounds**
   * （`mLayerState.mChildren[FOREGROUND_ID].mDrawable.draw(mCanvas)`，不再重新 setBounds），
   * 且 `setBounds` 会让它 `invalidateSelf()` 把缓存的 layers shader 置空。
   * 于是我们改完不还原的话，下一次画**原图标**时前景层就按我们留下的 bounds 画：
   * 锚点跑到图层原点（左上角偏出可见区）、尺寸缩一圈 —— 图标直接画坏，而且只在
   * 「取过 monochrome 之后又重画过原图标」时才出现，属于最难查的那类偶发。
   *
   * 通知侧没这个问题：那边是「拿一个图标画一次就扔」，同一个 drawable 不会被反复画，
   * 所以 [IconBitmap.foregroundOf] 保持原样、不动已验过的那条链。
   */
  private fun rasterize(target: Drawable, edge: Int): Bitmap? {
    val saved = Rect(target.bounds)
    return try {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
      target.setBounds(0, 0, edge, edge)
      target.draw(Canvas(bmp))
      bmp
    } finally {
      target.bounds = saved
    }
  }

  /**
   * 图层 bounds 里「可见区」离边缘的距离（两侧各让出多少像素）。
   *
   * 推导：可见区尺寸 `V = edge / (1 + 2f)`（f = [AdaptiveIconDrawable.getExtraInsetFraction]，
   * 即 1/4），两侧各让出 `(edge − V) / 2 = edge × f / (1 + 2f)` = **edge/6**。
   * 本机实测 `edge = 384` → inset = 64，可见区 256（正是图标尺寸）。
   *
   * ⚠ 是 `edge × f / (1 + 2f)`（≈ edge/6），**不是** `edge × f`（= edge/4）——
   * 两者差 1.5 倍，写错会让内容在可见区里只占 2/3、四周空一圈。
   * `CircleIconDrawable.ViewportDrawable` 用的是同一个式子（`frac = (1 − VIEW_PORT_SCALE) / 2`），
   * 两处口径一致。
   */
  private fun visibleInset(edge: Int): Int {
    val f = AdaptiveIconDrawable.getExtraInsetFraction()
    return (edge * f / (1f + 2f * f)).toInt()
  }

  /**
   * 把**满幅口径**算出来的掩码缩进**可见区**，四周留透明。
   *
   * 非自适应图标（老式满幅 PNG、图标包给的整张图）的内容铺满整个 bounds，
   * 而它在图层 bounds 里只该占中间那 2/3 —— 与裁圆那边 `MaskedDrawable` 的填充同一口径。
   * 不缩进的话，可见区里只剩这张图的中间 2/3，看起来就是「放大一圈 + 四周被切」。
   *
   * ⚠ 只能在掩码**算完之后**调用，不能提前到光栅化那一步 ——
   * 为什么，见 [rasterize] 上面那段（这是「非自适应图标全是色块」的病根）。
   */
  private fun intoVisibleArea(source: Bitmap, edge: Int): Bitmap {
    val inset = visibleInset(edge)
    val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
      source, null, Rect(inset, inset, edge - inset, edge - inset),
      Paint(Paint.FILTER_BITMAP_FLAG)
    )
    return out
  }

  /**
   * ARGB_8888（白色 + alpha）→ ALPHA_8。
   *
   * [IconBitmap.alphaSilhouette] / [IconBitmap.luminanceMask] 交出来的都是 ARGB_8888，
   * 但桌面取色只读 alpha，所以留着三个颜色通道是白占内存（每张 147 KB → 36 KB @192px）。
   */
  private fun toAlphaMask(source: Bitmap): Bitmap? {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val bytes = ByteArray(mask.rowBytes * h)
    var o = 0
    for (p in pixels) {
      bytes[o++] = Color.alpha(p).toByte()
      if (o >= bytes.size) break // rowBytes 可能带 padding，别越界
    }
    mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, o))
    return mask
  }
}

/**
 * [MonochromeLayer.draw] 打 bounds 日志用的全局计数 —— 按实例各打一次会把 logcat 冲掉，
 * 所以只放行最前面几张。放在文件级是因为 [MonochromeLayer] 是文件内的另一个类，
 * 拿不到 [MonochromeSynth] 的私有成员。
 */
private val boundsLogged = AtomicInteger(0)

private const val BOUNDS_LOG_FIRST = 3

/** [MonochromeLayer] 的 tint 埋点计数，见 `MonochromeLayer.noteTint`。 */
private val tintLogged = AtomicInteger(0)

private const val TINT_LOG_FIRST = 3

/** [MonochromeLayer.draw] 打「谁在画」的计数。 */
private val drawLogged = AtomicInteger(0)

/** 堆栈埋点放行次数与深度（诊断用，见 `MonochromeSynth.noteSynthesized`）。 */
private const val STACK_LIMIT = 2

private const val STACK_FRAMES = 14

/**
 * 把当前调用栈前 [frames] 帧拼成多行文本。
 *
 * ⚠ 只用于诊断、且必须限次：`Throwable()` 要抓整条栈（含全部帧），在图标加载这种
 * 热路径上不加限制地调是会拖慢的。
 */
private fun stackTraceOf(frames: Int): String =
  Throwable().stackTrace.take(frames).joinToString("\n") { "    at $it" }

/**
 * 单色层的载体：一张 ALPHA_8 掩码，画的时候由调用方（桌面的取色逻辑）上色。
 *
 * 不直接用 `BitmapDrawable` 是因为构造它要 `Resources`，而在被注入的进程里
 * 拿 Resources 要么拿不到、要么拿到的是宿主的 —— 自己画一个更稳，也少一层依赖。
 * ⚠ 代价是**系统 drawable 自带的那些行为都得自己补上**，尤其是 tint —— 见下。
 *
 * ## tint：真机那条路径其实**不 tint**，但契约仍然接住
 *
 * ### 先纠正一个曾经的误判（2026-09-24）
 * 之前从字节码里读到 `mono.setTint(colors[1])`，就认定「桌面用 `setTint` 给这层上色」。
 * **真机日志把这条推翻了**：`Mono: layer bounds=384x384 … filter=none`，而且
 * **一条 tint 日志都没有** —— 桌面压根没调过。加了「谁在问 / 谁在画」的堆栈埋点后看清了
 * 实际路径（`adb logcat` 原文）：
 * ```
 * at MonoIconThemeController.toAlphaBitmap(MonoIconThemeController.kt:100)
 * at MonoIconThemeController.createThemedBitmap(MonoIconThemeController.kt:79)
 * at BaseIconFactory.createBadgedIconBitmap(BaseIconFactory.kt:210)
 * ```
 * 桌面走的是 `createThemedBitmap` → 第 78 行 `InsetDrawable(mono, -getExtraInsetFraction())`
 * → 第 79 行 `toAlphaBitmap(inset, iconBitmapSize)`：**把这一层画进一张 ALPHA 位图**，
 * 再交给 `MonoThemedBitmap(alphaBitmap, colorProvider, …)` 由 `colorProvider` 上色。
 * 也就是说**这条路上只有 alpha 有意义，画出来是什么颜色完全无所谓**。
 *
 * 顺带解释了另外两个观察：负 inset 把我们的层撑到 1.5×（真机 `bounds=384` = 1.5 × 256）；
 * 掩码被画进 256 的位图，所以只有中央 2/3 可见 —— 正是类文档里那套坐标系。
 *
 * ### 那为什么还留着 tint 的实现
 * 因为「自定义 `Drawable` 不重写 tint = **静默丢失**」这件事是真的，而且别的路径
 * （`ThemedIconDrawable`、桌面点开图标的动画、OEM 改过的 Launcher）完全可能调它。
 * 补上契约是防御性的，成本也就二十行。**`Drawable` 的 tint 是「声明了但完全不管」**
 * —— 本机 framework 源码（`android-37.0/android/graphics/drawable/Drawable.java`）里
 * `setTintList` 是空方法体，`setTint(int)` 也只是转调它：
 * ```java
 * public void setTint(@ColorInt int tintColor) { setTintList(ColorStateList.valueOf(tintColor)); }
 * public void setTintList(@Nullable ColorStateList tint) {}   // ← 空的
 * ```
 * 真正应用 tint 是**子类自己的事**：系统 drawable 都做了（`VectorDrawable` /
 * `BitmapDrawable` 实现 `TintAwareDrawable`），所以平时根本感觉不到这条规则。
 *
 * 现在 `setColorFilter` / `setTint` / `setTintList` / `setTintMode` 四个入口都落到
 * [paint] 上，显式 color filter 优先于 tint（和 `BitmapDrawable` 的取舍一致）。
 * ⚠ 四个入口都要接：`setTint(int)` 确实会转调 `setTintList`，但那是**基类**的行为，
 * 而基类的 `setTintList` 是空的 —— 只重写一个就漏了另一个。
 *
 * ## 为什么掩码是「按需」的
 * 桌面（Launcher3 的 `MonoIconThemeController`）是把这张 drawable 当作新
 * `AdaptiveIconDrawable` 的前景层、`setBounds` 到**图层 bounds**（= 1.5 × 图标尺寸）
 * 之后再画的（推导见 [MonochromeSynth] 的坐标系那一节）—— 而那个尺寸在取
 * `getMonochrome()` 的当下还不知道。固定一个尺寸再让桌面放大就是「糊」的根源，
 * 所以这里在 [draw] 里看实际的 bounds：比手上这张大就**按那个尺寸重压一次**，
 * 之后不再变。
 */
private class MonochromeLayer(
  private val source: Drawable,
  initial: Bitmap,
) : Drawable() {

  /**
   * ⚠ `color = WHITE` 不是随手写的：掩码是 ALPHA_8（只有 alpha，没有颜色），
   * 画出来的颜色**取 `Paint` 的 color** —— 这正是 `Bitmap.extractAlpha()` 那套
   * 「拿 alpha 当遮罩、颜色由画笔决定」的用法。默认的 `Paint` 是黑色，
   * 万一哪条路径没给 tint 也没给 color filter，黑字形会在深色底上直接消失；
   * 而白色剪影至少和「我们交出去的本来是一张白色剪影」语义一致。
   * 正常路径下 tint 一定会到，这一条只是兜底。
   */
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
    color = Color.WHITE
  }

  /** 显式 color filter，优先于 tint（见类文档）。 */
  private var explicitFilter: ColorFilter? = null

  /** 桌面 `setTint` 给的颜色。自己存一份 —— 基类的 tint 字段是私有的，读不到。 */
  private var tintList: ColorStateList? = null

  private var tintMode: PorterDuff.Mode = PorterDuff.Mode.SRC_IN

  /** 当前掩码。只会在 [draw] 里因为尺寸不够而被换掉，换完就稳定。 */
  @Volatile
  private var mask: Bitmap = initial

  /** 每个实例只打一次 bounds 日志，见 [draw]。 */
  private var loggedBounds = false

  override fun draw(canvas: Canvas) {
    val edge = maxOf(bounds.width(), bounds.height())
    if (edge > mask.width) {
      // 桌面要的比手上这张大 —— 按它重压，别把小的放大糊掉。
      // 压不出来就继续用旧的（总比空着强）。压出来的顺手回填缓存，见 remember。
      MonochromeSynth.render(source, edge)?.let {
        mask = it
        MonochromeSynth.remember(source, it)
      }
    }
    // 真机上把「桌面实际给了多大的 bounds」记下来 —— 类文档里那套「图层 bounds = 1.5 ×
    // 图标尺寸」的推导是从字节码和源码推出来的，但推演在这块踩过坑（我判反过一次方向），
    // 所以留一条**实测**出口：前 [BOUNDS_LOG_FIRST] 张打一次。
    //
    // ⚠ 必须在上面那段**之后**打：重压前 mask 还是 MIN_EDGE，打出来会变成
    // 「bounds=384 mask=192」，读着像「384 的框里塞 192 的图 = 放大糊了」，正好相反。
    // 放在这里，`bounds` 与 `mask` 相等就说明掩码是 1:1 铺满图层 bounds、没有被放大。
    if (!loggedBounds) {
      loggedBounds = true
      val total = boundsLogged.incrementAndGet()
      if (total <= BOUNDS_LOG_FIRST) {
        Log.d(TAG, "Mono: layer bounds=${bounds.width()}x${bounds.height()} mask=${mask.width} " +
          "alpha=${paint.alpha} filter=${paint.colorFilter?.javaClass?.simpleName ?: "none"}")
      }
    }
    // 诊断：谁在画这一层（见 stackTraceOf）。桌面有「直接把我们当前景层画」和
    // 「先把我们渲进位图再上色」两种可能，观感完全不同，堆栈能一眼分开。
    if (drawLogged.incrementAndGet() <= STACK_LIMIT) {
      Log.d(TAG, "Mono: who draws\n" + stackTraceOf(STACK_FRAMES))
    }
    canvas.drawBitmap(mask, null, bounds, paint)
  }

  override fun setAlpha(alpha: Int) {
    paint.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    explicitFilter = colorFilter
    updatePaint()
    noteTint("setColorFilter(${colorFilter?.javaClass?.simpleName ?: "null"})")
  }

  /**
   * ⚠ 桌面走的**就是这个入口**（`mono.setTint(colors[1])`），所以必须直接重写它。
   *
   * 只重写 [setTintList] 是不够的 —— 那是在赌「基类的 `setTint` 会转调 `setTintList`」。
   * 本机 framework 源码（`android-37.0/android/graphics/drawable/Drawable.java`）里
   * `setTintList` 是**空方法体**：
   * ```java
   * public void setTint(@ColorInt int tintColor) { setTintList(ColorStateList.valueOf(tintColor)); }
   * public void setTintList(@Nullable ColorStateList tint) {}   // ← 空的
   * ```
   * 也就是说 tint 这件事**整个基类都不管**，完全靠子类自己接（`VectorDrawable` /
   * `BitmapDrawable` 这类系统 drawable 都实现了 `TintAwareDrawable`）。
   * 两个入口都接住，才不管桌面走哪条。
   */
  override fun setTint(tintColor: Int) {
    super.setTint(tintColor)
    tintList = ColorStateList.valueOf(tintColor)
    updatePaint()
    noteTint("setTint(${hex(tintColor)})")
  }

  override fun setTintList(tint: ColorStateList?) {
    super.setTintList(tint)
    tintList = tint
    updatePaint()
    noteTint("setTintList(${hex(tint?.defaultColor)})")
  }

  override fun setTintMode(tintMode: PorterDuff.Mode?) {
    super.setTintMode(tintMode)
    this.tintMode = tintMode ?: PorterDuff.Mode.SRC_IN
    updatePaint()
  }

  /** 显式 color filter 优先于 tint，取舍与 `BitmapDrawable` 一致。 */
  private fun updatePaint() {
    val tint = tintList
    paint.colorFilter = explicitFilter ?: tint?.let {
      PorterDuffColorFilter(it.getColorForState(state, it.defaultColor), tintMode)
    }
  }

  /**
   * 埋点：桌面到底用哪个入口上色、给了什么颜色。
   *
   * 为什么非要留一条**实测**出口：类文档里「桌面用 `setTint` 上色」这个结论是从
   * `MonoIconThemeController` 的字节码读出来的 —— 但「自定义 Drawable 不重写
   * `setTint` 就会丢 tint」这件事我一开始没意识到，整个链路静默失效、只有观感异常。
   * 以后同类问题一眼就能分清是「桌面没上色」还是「我们没接住」。
   */
  private fun noteTint(what: String) {
    val total = tintLogged.incrementAndGet()
    if (total <= TINT_LOG_FIRST) Log.d(TAG, "Mono: tint #$total via $what")
  }

  /** 颜色打日志用：`#AARRGGBB`，不跟着系统语言变（`Locale.ROOT`）。 */
  private fun hex(color: Int?): String =
    if (color == null) "null" else String.format(Locale.ROOT, "#%08X", color)

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = mask.width

  override fun getIntrinsicHeight(): Int = mask.height

  /**
   * ⚠ **绝不能返回 null** —— 和 [CircleIconDrawable.getConstantState] 那边是同一个坑。
   *
   * `AdaptiveIconDrawable.getConstantState()` 是这么写的：
   * ```
   * if (mLayerState.canConstantState()) { ...; return mLayerState; }
   * return null;                    // 任一子层的 ConstantState 为 null → 整个 null
   * ```
   * 而 `Drawable.getConstantState()` 的默认实现就是 `return null` —— 不重写，
   * 我们这一层会把外面那个 `AdaptiveIconDrawable` 的 ConstantState 一起拖成 null。
   * 桌面（FloatingIconView 的「点图标打开」动画）直接 `getConstantState().newDrawable()`，
   * null 就是 NPE = 桌面进程崩。
   *
   * 交出去的 ConstantState 复用当前这张掩码：掩码是只读的、我们从不回收它，
   * 多个实例共享同一张 Bitmap 是安全的（`BitmapDrawable` 也是这么做的）。
   * 各实例的 bounds 仍归各自所有 —— `ChildDrawable` 复制时会自己 setBounds。
   */
  override fun getConstantState(): ConstantState = object : ConstantState() {
    override fun newDrawable(): Drawable = MonochromeLayer(source, mask)

    override fun getChangingConfigurations(): Int = 0
  }
}
