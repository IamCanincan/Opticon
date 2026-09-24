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
import com.iamcanincan.opticon.notify.ToneCheck
import com.iamcanincan.opticon.runtime.ModuleRuntime
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
 * 2. **否则** → [IconBitmap.binarizeSilhouette]，也就是 [IconBitmap.monochrome] 的 else 支
 *    （Otsu 自动阈值 + 连通性判据挖出图形），与通知侧**同一份实现**。
 *    ⚠ 这一支必须是**二值**，不能是连续灰度：桌面拿 alpha 当**不透明度**上色，
 *    灰阶染出来是同一个颜色的渐变，而主题图标要的是**一整块纯色**。
 *    （通知侧的 `grayscale` 不受这个约束 —— 它交出去的是**完整位图**，RGB 与 alpha 各司其职。
 *    2026-09-24「要纯色、不要渐变」那条反馈的病根就是这里：把通知侧那一步的**结论**
 *    搬过来时漏掉了「桌面只读 alpha」这个约束差异。）
 *
 * 两支都不成立（光栅化失败、或纯色块没有任何对比度）才返回 null，桌面照旧走自己的路。
 *
 * 取前景层那一步没有直接用 [IconBitmap.foregroundOf]，而是在这里自己写了 [rasterize]：
 * 它会**还原原 drawable 的 bounds**（理由写在那上面）。
 * 通知侧是「拿一个图标画一次就扔」、不需要还原，所以那边保持原样。
 *
 * ## 两套口径，别混：判定用「满幅」，位置用「可见区」
 * 这是本文件最容易写错的地方 —— 2026-09-24 真机「非自适应图标全部变成色块」的病根：
 * - **判定**：第一支要量「内容外接框内透明像素占多少」，第二支要在**整张图**上跑 Otsu ——
 *   两者都以「这一张光栅图」为输入，所以喂给它们的必须是**满幅光栅化**的结果。
 *   满幅出图还顺带保证了质量：内容按 1:1 渲染，缩进可见区时是从全分辨率带滤波降采样，
 *   比直接按 2/3 尺寸渲染更干净。
 *   ⚠ 2026-09-24「非自适应图标全部变成色块」的病根正是提前缩进：那圈凭空多出来的透明边
 *   把满幅不透明的老式图标误判成「透明底 + 图形」→ 压出一整块方色块。现在第一支改成
 *   「先裁到内容外接框再量」之后已经对这种误判免疫，但顺序照旧保持「先满幅、后缩进」。
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

  /**
   * alpha 支的「这大概是一块底」提示线：框内填充率到这一步以上，就先试一次
   * [IconBitmap.plateSilhouette] 把字形从底板里刻出来，别把整块底当图形交出去。
   *
   * ⚠ 这是**要不要再试一次**的门槛，不是拒绝线 —— 刻不出来时 alpha 的结果照旧生效，
   * 所以试错的方向是安全的。定在 0.55 的理由见 `IconBitmap.PLATE_FILL` 那一段。
   */
  private const val PLATE_HINT = 0.55f

  /**
   * 「整张图标是不是灰度」那道判据的出图尺寸。
   *
   * `ToneCheck` 自己就会把输入缩到 64 再逐像素看三通道，画得更大只是白付一次光栅化 ——
   * 这一步只为了回答「要不要动手」，不参与任何形状计算。
   */
  private const val TONE_SAMPLE = 64

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
    // 尺寸已知就一次到位，不知道才按 MIN_EDGE 探一次（探的意义是「压不出形状就别交空壳出去」，
    // 那会让桌面以为有单色图标、结果画出一片空白）。
    // 没有这一行的话每个图标要把整条全像素管线跑两遍：先 192 探一次、`draw()` 里发现桌面
    // 要 384 再重压一次 —— 真机日志里就是每个图标成对的 `edge=192` / `edge=384` 两行。
    // 一次会话里桌面给的图层 bounds 是常量，所以只有第一个图标付两次。
    val mask = render(source, if (knownEdge > 0) knownEdge else MIN_EDGE) ?: return null
    if (cache.size >= CACHE_LIMIT) cache.clear()
    cache[source] = mask
    return MonochromeLayer(source, mask).also { noteSynthesized() }
  }

  /**
   * 桌面实际给的图层 bounds，由 [MonochromeLayer.draw] 回报。
   *
   * 只取见过的**最大值**：搜索框、通知里那种小尺寸场合不该把探测尺寸拉回 192，
   * 宁可多压一次也不交出偏小的掩码。
   */
  @Volatile
  private var knownEdge = 0

  fun learnEdge(edge: Int) {
    if (edge > knownEdge) knownEdge = edge
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
      // ── 「原本就适配取色的图标，一个像素都不要碰」 ──
      // 应用自己提供单色层的那种「已适配」，`getMonochrome()` 非 null，钩子在最外层就透传了
      // （见 `IconHooks.hookSynthesizedMonochrome`）。还有第二种：**图标整体就是一张灰度图**、
      // 等系统按主题上色 —— 那种没有单色层，会一路走进这里被我们重压一遍。判据复用通知侧
      // 那一个函数（`ToneCheck.isGrayscale` + `preserveTinted`），两边说的同一件事不另写一套。
      //
      // ⚠ 量的必须是**整个图标**，不是「我们要动的那一层」。上一版我在这里判了前景层，
      //   真机 24 小时内就错杀 295 次：自适应图标**最典型的没适配结构**就是
      //   「白色剪影前景 + 一层彩色底板」，只看前景当然是灰度，于是我们撒手不管，
      //   桌面回去走自己那条算错极性的路 —— 观感直接退成「不是纯色」。
      //   真正算「已经适配」的，是把背景也算进去之后整体仍然只有明暗、没有彩色。
      // 非自适应图标的前景层就是整张图，直接复用刚才那次光栅化，不多画一遍。
      val whole = if (adaptive) rasterize(source, TONE_SAMPLE) else raster
      val alreadyTinted = whole != null && ModuleRuntime.options().preserveTinted &&
        ToneCheck.isGrayscale(whole)
      if (whole !== raster) whole?.recycle()
      if (alreadyTinted) {
        noteGiveUp(edge, label, "整张图标已是灰度（应用自己适配过取色）→ 不动")
        raster.recycle()
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
      // 2. **否则** → 二值化（[IconBitmap.binarizeSilhouette]），也就是
      //    [IconBitmap.monochrome] 的 else 支：Otsu 自动阈值 + 连通性判据挖出图形。
      //    ⚠ 这一支是**二值**而不是连续灰度：桌面拿 alpha 当**不透明度**上色，
      //    灰阶染出来是同一颜色的渐变，而主题图标要的是**一整块纯色**。
      //    通知侧的 `grayscale` 之所以能用灰度，是因为它交出去的是**完整位图**
      //    （RGB 与 alpha 各司其职），桌面这条路上 RGB 根本没人看。
      //
      // ⚠ 两支用**同一条**覆盖率线（`IconBitmap.MAX_SHAPE_RATIO`，0.85），而且必须按
      //    **内容外接框**量、不能按整张画布量：自适应前景的框只占画布约 47%，
      //    「整个框被填满」这种最坏的掩码按画布量永远只有 0.47，看着完全健康 ——
      //    真机那个 `out=0.47 cc=1 top=1.00` 的白块就是这么一路放行的。
      //    两支各自把量出来的 `boxFill` 与结论带出来，交给下面那行逐条日志。
      //
      // 两条都不成立时返回 null，桌面照旧走它自己的路（[noteGiveUp] 会留痕）。
      // 三条判据、按「最忠实的先试」排：alpha → 从底板刻字形 → 亮度 Otsu。
      //
      // 1. **alpha**（[IconBitmap.alphaSilhouette]）：交出去的就是图标自带的 alpha，不猜。
      // 2. **刻底板**（[IconBitmap.plateSilhouette]）：只要 alpha 说「框内已经是成片的底」
      //    （≥ [PLATE_HINT]，含它直接判成底板那种），就先试着按**离底板主色的距离**把字形
      //    刻出来。这一步专治大色块 —— 0.55~0.85 那一段以前被 alpha 整块收下，屏幕上就是
      //    一块带几条镂空洞的色板，字形根本没露出来。刻不出来（多色 logo / 整张同色）就照旧
      //    用 alpha 的结果，不会因为试了而变差。
      // 3. **亮度 Otsu**（[IconBitmap.binarizeSilhouette]）：前两条都不成立时的兜底。
      val alpha = IconBitmap.alphaSilhouette(raster)
      val carved = if (alpha.boxFill >= PLATE_HINT) IconBitmap.plateSilhouette(raster) else null
      val bin = if (alpha.mask == null && carved?.mask == null)
        IconBitmap.binarizeSilhouette(raster) else null
      val chosen = carved?.mask?.let { carved }
        ?: alpha.mask?.let { alpha }
        ?: bin?.takeIf { it.mask != null }
      noteBranch(edge, label, alpha, carved, bin, chosen)
      val base = chosen?.mask
      // 非自适应图标的内容是满幅的，而它在图层 bounds 里只该占中间那 2/3（可见区），
      // 所以掩码算完**之后**才缩进 —— 顺序反了就会踩 [rasterize] 上面那个坑。
      val placed = if (adaptive) base else base?.let { intoVisibleArea(it, edge) }
      val mask = placed?.let { toAlphaMask(it, chosen?.tones == true) }
      noteDiagnosis(edge, label, raster, mask, chosen === alpha)
      raster.recycle()
      if (placed !== base) placed?.recycle()
      base?.recycle()
      mask
    }.onFailure {
      // ⚠ 这一条不能省：上面整个块包在 runCatching 里，任何异常都会被 `getOrNull()` 变成
      // 一个正常的 null 出口 —— 而 null 在功能上就是「放弃合成、桌面照旧」，不崩、不报错。
      // 上一轮那个「ALPHA_8 的 rowBytes 带 padding，掩码写歪 → copyPixelsFromBuffer 抛异常」
      // 就是这么在真机上完全看不见的：日志里只有「压不出掩码」，看不出**为什么**压不出。
      // 本项目已经吃过两次「提前 return 静默失效」的亏，异常这条同理。
      Log.w(TAG, "Mono: render threw at edge=$edge: ${it.javaClass.simpleName}: ${it.message}")
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
      // 包名/资源名放最前面：报一个应用名就能直接 grep 到它那一行（认不出打 `?`）。
      append(IconNames.of(source)).append(' ')
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
   * 诊断：这个图标走了哪条分支、两支各自量出多少、最后有没有交出掩码。
   *
   * ⚠ 这是**每个图标一行**的廉价留痕 —— 不复算任何东西，数字是两支自己带出来的
   * （见 [IconBitmap.Silhouette]），所以这一条不打上限。贵的那条全画布复算汇总在
   * [noteDiagnosis]，那条才有限额。
   *
   * 为什么非要把 `框内=` 这个数打在逐条行上：上一轮「白块」的教训是**数打了、口径错了** ——
   * `out=0.47` 是画布口径，一整块底板也只量到 0.47，看上去完全健康，于是一路放行。
   * 这一行给的是**内容外接框**口径的那一个数，它才是真正决定走哪支、拦不拦得住的数。
   */
  private fun noteBranch(
    edge: Int,
    label: String,
    alpha: IconBitmap.Silhouette,
    carved: IconBitmap.Silhouette?,
    bin: IconBitmap.Silhouette?,
    chosen: IconBitmap.Silhouette?,
  ) {
    val n = diagnosed.incrementAndGet()
    val trail = buildString {
      append("alpha[").append(ratio(alpha.boxFill)).append(' ').append(alpha.verdict).append(']')
      if (carved != null) {
        append(" → 刻底[").append(ratio(carved.boxFill)).append(' ').append(carved.verdict).append(']')
      }
      if (bin != null) {
        append(" → 二值[").append(ratio(bin.boxFill)).append(' ').append(bin.verdict).append(']')
      }
    }
    Log.d(TAG, "Mono: diag#$n edge=$edge $label $trail → " +
      if (chosen != null) "已交出掩码" else "三条判据都不成立，放弃合成")
  }

  /**
   * 诊断：把「这一张掩码怎么来的、长什么样」打进日志。
   *
   * ## 为什么要有它
   * 桌面主题图标这块**我看不见** —— 我的图像通道是 filtered 的，用户也明确不要我截图。
   * 而「形状不对」有好几种**成因完全不同**的可能（走了 alpha 分支但输入本身是糊的 /
   * 二值剪影把整块底当成了图形 / 掩码没问题但下游画坏），靠推演猜已经错过一次，
   * 所以这里把三样东西直接打出来：
   * 1. **输入 alpha 的四段直方图**（`a0/a1/a2/a255`）：一眼能看出这张图是
   *    「透明底 + 图形」还是「整张不透明」。
   * 2. **两支判据的决策过程**（`split …`，由 [IconBitmap.analyzeSplit] 复算真算法给出）：
   *    `box` / `boxfill` 是 alpha 那一支的判据（`boxfill` 到 1.00 就是「一整块底」）；
   *    Otsu 阈值、两侧占比、两侧的「贴外部」比例、图形偏哪一侧、亮度直方图是二值化那一支
   *    的依据 —— 只有它能把「结果不对」定位到具体哪一步。
   * 3. **掩码本身的 ASCII 缩略图**：`#` 是不透明、`.` 是透明。把 384² 降采样成 40×40
   *    打进日志，于是「是一个完整字形」还是「一堆碎片」在日志里就能直接看出来。
   *    ⚠ 缩略图**只给走二值剪影的那些**（没走 alpha 轮廓这条主路），上限
   *    [ASCII_DUMP_LIMIT] 张 —— alpha 轮廓已经验过，缩略图留给真正要看形状的样本。
   *
   * ## ⚠ 成本：一次全画布复算，所以限 [HEAVY_DIAG_LIMIT] 条
   * 这一条汇总行**不便宜** —— `a0/a1/a2/a255`、`box/boxfill`、Otsu 与两侧连通性要在
   * 384² 上各重跑一遍（`analyzeSplit` 是复算真算法，不是读现成结果），再加掩码连通块，
   * 单次约 4 MB 短命数组。上一轮为普查短暂放宽过「逐条打、不打上限」（115 个包 ×
   * 每图标两条，那一次确实需要），普查做完留着就是每图标白付的开销。
   * 现在每进程只放行最前面 [HEAVY_DIAG_LIMIT] 条，够看清「判据落在哪、形状对不对」，
   * 要看全量分布看的是 [noteBranch] 那条**逐条打**的廉价汇总。
   *
   * 形状统计：`out` 掩码里 ≥128 的像素占比；`fill` 是**按 alpha 加权的等效覆盖率**
   * （Σalpha / 255 / 总面积）—— 图标看起来「被填满多少」看的是这个。
   * 两支产出的掩码现在都是**二值**（只留边缘那一圈抗锯齿值），所以正常情况下 `out` ≈ `fill`；
   * 两者明显拉开（`out` 小 `fill` 大）就说明掩码里塞了一大片淡色 —— 色块的典型特征。
   * `cc` 四邻域连通块数、`top` 最大一块占输出像素的比例 —— 完整字形 cc 很小、top 接近 1；
   * 碎成一片时 cc 很大、top 很低。
   * ⚠ 这两个统计量只在**二值**掩码上可读：早先那一版连续灰度的掩码下，照片底按 128
   * 二值化必然碎成几百块（实测 `cc=198`），`cc` / `top` 全是噪声，判它只能看 `fill`。
   *
   * ## 「一整块色」在数字上长什么样
   * 判据是 `out` ≈ `fill`（掩码本身就是那块色，没有更淡的东西）+ `cc=1` + `top=1.00`
   * + `out` 落在 0.4~0.5 之间。这是**输入就已经是实心方块**的特征（前景层是满幅不透明的
   * 位图，alpha 直接就是那块底的轮廓），不是掩码算法把图形切碎了 ——
   * 切碎的特征是 `cc` 大、`top` 小，两者一眼能分开。
   * 真机实例：淘宝 `out=0.47 cc=1 top=1.00`、`box=0.47 boxfill=1.00`
   * （外接框内被填满 = 一整块底）→ [IconBitmap.alphaSilhouette] 判「透明像素不构成背景」、
   * 降级成二值剪影之后是「淘」字本身。
   *
   * ⚠ 只是诊断，**不参与任何判断** —— 两支的选择与极性都由 `IconBitmap` 里的判据自己定。
   */
  private fun noteDiagnosis(
    edge: Int,
    label: String,
    input: Bitmap,
    mask: Bitmap?,
    alphaSilhouette: Boolean,
  ) {
    // 重活只干前 HEAVY_DIAG_LIMIT 次，理由见上面「成本」那一节。
    // 单独一个计数器：`diagnosed` 是三条出口共用的 diag# 编号，不能拿来当闸门。
    if (heavyDiag.incrementAndGet() > HEAVY_DIAG_LIMIT) return
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
    // 阈值 / 两侧占比 / 连通性 / 图形偏哪侧是二值化那一支的极性依据，
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

    // 掩码缩略图**只给走二值剪影的那些**（没走 alpha 轮廓这条主路）：
    // alpha 轮廓已经验过，缩略图留给真正要看形状的样本，省 logcat 也省得淹掉。
    // 这里已经整体被 [HEAVY_DIAG_LIMIT] 兜住，[ASCII_DUMP_LIMIT] 只是再收一道：
    // 一张 40 行，全放行会让「前 12 条恰好都是二值支」时刷出 480 行。
    if (!alphaSilhouette) {
      val dumped = asciiDumped.incrementAndGet()
      if (dumped <= ASCII_DUMP_LIMIT) dumpAscii(n, "mask", mw, mh, mask.rowBytes, bytes, MASK_DUMP_THRESHOLD)
    }
  }

  /**
   * 把 ALPHA_8 掩码的原始字节（[maskBytes]）打成 40 列 ASCII：`#` = 不透明、`.` = 透明。
   *
   * 判据用 [threshold]，与 `IconBitmap` 里的 `MIN_SHAPE_ALPHA` 同口径 ——
   * 缩略图如果自己用一套阈值，就和真产出的掩码对不上了，那还不如不打。
   *
   * ⚠ [stride] 必须传 `mask.rowBytes`：[maskBytes] 是按行步长填的（见 [toAlphaMask]），
   * 用 `y * w + x` 去读会把每一行往左挪 `(stride - w)` 字节，宽度不是 4 的倍数时
   * 整张缩略图**逐行错位** —— 看着像掩码碎了，其实是日志读错了。
   */
  private fun dumpAscii(
    n: Int,
    what: String,
    w: Int,
    h: Int,
    stride: Int,
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
            if ((maskBytes[y * stride + x].toInt() and 0xff) >= threshold) hits++
          }
        }
        line.append(if (cells > 0 && hits * 2 >= cells) '#' else '.')
      }
      Log.d(TAG, "Mono: diag#$n $what $ry|$line")
    }
  }

  private val diagnosed = AtomicInteger(0)

  /**
   * 重诊断（[noteDiagnosis]：全画布直方图 + 复算 `analyzeSplit` + 掩码连通块）每进程放行几条。
   *
   * 12 条够看清「判据落在哪、形状对不对」这两件事，又不是每个图标都付一次全画布复算。
   * 逐条的分布仍然有 [noteBranch] 那行廉价汇总兜着 —— 降级掉的是**贵的那部分**，
   * 不是「每条出口都留痕」这条规矩。
   */
  private const val HEAVY_DIAG_LIMIT = 12

  private val heavyDiag = AtomicInteger(0)

  /** ASCII 缩略图的行列数（降采样后的画布大小）。 */
  private const val ASCII_COLS = 40

  /**
   * 「不走主路」的样本最多打几张缩略图。
   *
   * 40 行一张，一张图标一份 —— 在 [HEAVY_DIAG_LIMIT] 之上再收一道，
   * 免得放行那 12 条恰好全是二值支时刷出 480 行。
   */
  private const val ASCII_DUMP_LIMIT = 8

  /** [dumpAscii] 的计数，见 [ASCII_DUMP_LIMIT]。 */
  private val asciiDumped = AtomicInteger(0)

  /**
   * 剪影阈值：alpha 低于它就不算图形。与 `IconBitmap.MIN_SHAPE_ALPHA` 同一个口径 ——
   * 掩码缩略图、实心钳位、判据台读图都得用同一个数，否则日志看着像另一回事。
   */
  private const val MASK_DUMP_THRESHOLD = 128

  /**
   * 轮廓那一圈的 alpha 保底值：过渡值只允许在 [EDGE_ALPHA_FLOOR]..255 之间浮动。
   *
   * 不留保底的话，三四像素宽的描边整个都是"边界"，逐像素保留抗锯齿过渡值 =
   * 整条笔画半透明，主题色看起来就发浅。留了保底，边缘仍然是渐变的（不硬），
   * 但最淡也有八成不透明度。
   */
  private const val EDGE_ALPHA_FLOOR = 200

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
   * 这是 2026-09-24 真机「非自适应图标全部变成色块」的病根：缩进会在四周留出一圈
   * `edge/6` 的透明边，而当时的判据是在**整张画布**上量「透明像素占多少」——
   * 那一圈边正好把一张满幅不透明的老式图标量成「透明底 + 图形」，于是走了 alpha 分支；
   * 而那个 alpha 描述的是**整张方图的轮廓**，压出来就是一整块实心方块，上色之后就是一块色。
   *
   * ⚠ 判据现在改成了「先裁到内容外接框再量」（[IconBitmap.alphaSilhouette]），对上面那种
   * 误判已经免疫 —— 但顺序照旧保持「先满幅、后缩进」，理由有两条：
   * 1. 第二支（[IconBitmap.binarizeSilhouette]）的 Otsu 直方图统计的是**整张图**的不透明
   *    像素，喂进去的图必须和判据、和最终掩码是同一张，否则数出来的阈值没有意义。
   * 2. 质量：满幅出图时内容按 1:1 渲染，[intoVisibleArea] 再带滤波降采样到 2/3 ——
   *    比直接按 2/3 尺寸渲染更干净（等于多做了一次超采样）。
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
    // ⚠ target 自己就是 AdaptiveIconDrawable 时，光栅化它等于**顺带改了它两个子层的 bounds**：
    //   `AdaptiveIconDrawable.draw()` → `updateLayerBoundsInternal()` 会按这次的 bounds 重算
    //   背景层与前景层，而它之后重画图层用的是子层**当前** bounds、不再 setBounds。
    //   只还原父层的话，那份「按 64px 算出来的子层 bounds」就留在应用自己的图标对象上，
    //   桌面下次画原图标时按 1/6 尺寸铺进 384 的画布 —— 观感就是「图标突然变糊」。
    //   这是本文件早就写过的那条坑（前景层要还原 bounds）在上一层的翻版。
    val background = (target as? AdaptiveIconDrawable)?.background
    val foreground = (target as? AdaptiveIconDrawable)?.foreground
    val savedBackground = background?.let { Rect(it.bounds) }
    val savedForeground = foreground?.let { Rect(it.bounds) }
    return try {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
      target.setBounds(0, 0, edge, edge)
      target.draw(Canvas(bmp))
      bmp
    } finally {
      // 子层**必须自己还原**，不能指望上面那次 setBounds 把它们带回去：取 monochrome 的时机
      // 常常在桌面给这个 drawable 设 bounds 之前（真机日志里就是 `bounds=0x0`），还原成空矩形
      // 时 `Drawable.setBounds` 根本不会触发 onBoundsChange → 图层不重算 → 64px 的子层 bounds
      // 就留在那份图标对象上了。
      if (background != null && savedBackground != null) background.bounds = savedBackground
      if (foreground != null && savedForeground != null) foreground.bounds = savedForeground
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
   * [IconBitmap.alphaSilhouette] / [IconBitmap.binarizeSilhouette] 交出来的都是 ARGB_8888，
   * 但桌面取色只读 alpha，所以留着三个颜色通道是白占内存（每张 147 KB → 36 KB @192px）。
   *
   * ## ⚠ 必须按 [Bitmap.getRowBytes] 的下标写，不能按 packed 写
   * ALPHA_8 每像素 1 字节，而 rowBytes 会**补到 4 的倍数**，于是 `w` 不是 4 的倍数时
   * `rowBytes > w`。`copyPixelsFromBuffer` 要的是**带 padding 的整块**（`rowBytes × h` 字节），
   * 按 `bytes[o++]` 那种逐像素连写只交得出 `w × h` 字节 → 缓冲区不够 → 抛异常。
   * 而异常会被 [render] 的 `runCatching` 吞成 null：桌面回落到自己的 Factory，
   * **合成整条静默失效**，日志里只剩一句「没交出掩码」。
   * 本机 `edge = 384` 正好是 4 的倍数，所以这条永远踩不到 —— 但桌面实际给的尺寸是
   * `1.5 × iconSize`，density 3.0 的机器给 378、3.375 给 423，全都会踩。
   * 同一条口径在 `IconHooks.kt` 的 `invertAlphaMask` 那边本来就是按
   * `rowBytes × height` 整块搬的，这里照同一个做法。
   */
  private fun toAlphaMask(source: Bitmap, honorTones: Boolean): Bitmap? {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val stride = mask.rowBytes
    val bytes = ByteArray(stride * h) // 末尾那 (stride-w)×h 个 padding 留 0 = 透明，无害
    // ⚠ 内部一律钳成实心，轮廓那一圈也托个底 —— 但带三档的掩码除外（见 [honorTones]）。
    //   桌面把 alpha 当**不透明度**上色，而 `preserveAlpha` 保留的是整片原始 alpha，
    //   不只是抗锯齿那圈边：源图标里 alpha 150~200 的区域会被画成六到八成浓度。
    //   细笔画更糟 —— 三四像素宽的描边整个都是"边界"，逐像素保留过渡值等于整条笔画半透明，
    //   观感就是「主题色怎么这么浅」。所以：内部一律 255，边界保底 [EDGE_ALPHA_FLOOR]。
    var read = 0
    for (y in 0 until h) {
      val row = y * stride
      for (x in 0 until w) {
        val a = Color.alpha(pixels[read++])
        bytes[row + x] = when {
          // 三层刻底那条路交出来的掩码自带三档（255 主体 / 128 点缀 / 0 底板）：这里只做
          // **吸附归档**，绝不钳实心 —— 钳了就等于把中间档抹掉、三层塌回两层。
          // 为什么还要吸：非自适应图标在这之后要经 `intoVisibleArea` 缩放一次，双线性会把
          // 档与档之间混出中间值；重新吸附保证交到桌面手上的仍然是三个确定值。
          honorTones -> when {
            a < 64 -> 0
            a < 192 -> 128
            else -> 255
          }
          a < MASK_DUMP_THRESHOLD -> 0
          a >= 255 -> 255
          isSilhouetteEdge(pixels, w, h, x, y) -> maxOf(a, EDGE_ALPHA_FLOOR)
          else -> 255
        }.toByte()
      }
    }
    mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    return mask
  }

  /** 该像素是不是剪影的外圈：8 邻域里有低于 [MASK_DUMP_THRESHOLD] 的（含画布外）。 */
  private fun isSilhouetteEdge(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Boolean {
    for (dy in -1..1) for (dx in -1..1) {
      if (dx == 0 && dy == 0) continue
      val nx = x + dx; val ny = y + dy
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
      if (Color.alpha(pixels[ny * w + nx]) < MASK_DUMP_THRESHOLD) return true
    }
    return false
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
 * 实际路径（那两条埋点是本轮排查用的，结论已经落在下面这段，代码已移除）。真机日志：
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
    // 把桌面实际给的尺寸回报上去：后面的图标就一次按它出图，不再先探 192 再重压。
    MonochromeSynth.learnEdge(edge)
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
