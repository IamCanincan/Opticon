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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log

import com.iamcanincan.opticon.runtime.ModuleRuntime
import com.iamcanincan.opticon.runtime.TAG
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 给「没提供单色图标的应用」合成一个单色层。
 *
 * ## 为什么要有这个
 * Android 16 的「强制动态取色」下，桌面（`com.android.launcher3.icons.MonochromeIconFactory`）
 * 会为**没提供单色图标**的应用现场生成一张掩码，而它算出来的**极性经常是反的**：深色字形 +
 * 浅色底的图标会变成「字形是洞、底色是形」，整个反掉。
 *
 * 与其等它算错、再把输出整张翻回来（那是 [IconHooks] 里 `hookForcedThemedMono` 干的事），
 * 不如**在 `AdaptiveIconDrawable.getMonochrome()` 上直接返回一个极性正确的合成层**：
 * 桌面只在这个 getter 返回 null 时才去 new 那个 Factory，所以我们一返回非 null，
 * 那条算错的路径根本不会进。
 *
 * ## 分工：这个文件只管「画布与坐标」，不管「形状」
 * 怎么从一张图标里算出掩码，全部在 [MonochromeMask]（纯数组进出的模型，判据台跑的就是它）。
 * 这一层只剩四件事，每一件都是因为**只有这里看得到 Drawable 和桌面**才留在这里：
 *
 * 1. **取哪一层**：自适应图标**只有背景层是纯色盘时**才取前景层，其余（非自适应、
 *    或背景层里带东西的自适应）取整张 —— 见 [render] 里那条 2026-10-09 的注释（translate 的"文"）。
 * 2. **别碰已适配的**：见下面那道闸，量的是**整个合成图标**。
 * 3. **按桌面要的尺寸出图**：首问时桌面还没 `setBounds`，所以先探一档；[MonochromeLayer.draw]
 *    量到实际尺寸就报上来（[learnEdge]）并让这张的缓存作废（[demote]），下一次取
 *    `getMonochrome()` 时在**加载线程**上按新尺寸重压 —— 固定一档再让桌面放大就是「糊」的根源，
 *    而在 `draw()` 里当场重压则是把几百毫秒挂到桌面的绘制线程上。两头都不取。
 * 4. **放对位置**：非自适应图标的内容满幅，掩码算完**之后**才缩进可见区（[intoVisibleArea]）。
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
 * （`edge` = 图层 bounds），图形自然落在画布中间 61%（= 66dp / 108dp），可见区里就是
 * 61% × 1.5 = 92%（= 66dp / 72dp，正是自适应图标的安全区）。
 * **绝不能**按 inset 给 bounds：那会把图形画成 92% 的「画布」，到可见区里就是 137%、
 * 边缘被裁掉一圈。这个错误不会崩、也不会报错，只会让图形明显偏大且缺边。
 *
 * ## 两套口径，别混：判定用「满幅」，位置用「可见区」
 * 这是本文件最容易写错的地方 —— 2026-09-24 真机「非自适应图标全部变成色块」的病根：
 * 判据（[MonochromeMask] 里那些「框内」「底占」）都以「这一张光栅图」为输入，所以喂给它们的
 * 必须是**满幅光栅化**的结果；提前缩进会凭空多出一圈透明边，把满幅不透明的老式图标误判成
 * 「透明底 + 图形」。满幅出图顺带还保质量：内容按 1:1 渲染，缩进时是带滤波的降采样，
 * 比直接按 2/3 尺寸渲染更干净。
 *
 * ## 天然降级，不需要开关
 * 合成只在 `getMonochrome()` 原本返回 null 时才介入，且任何一步失败都返回 null ——
 * 那时桌面照旧走 Factory，而那道翻转仍然挂着兜底。两条路是**互斥**的：
 * 合成生效 → 桌面不进 Factory → 翻转自然不会被触发。
 *
 * ⚠ 但**回落不是中性的**：桌面自己那条 Factory 算错极性，用户看到的正是「不是纯色 / 一块白」。
 * 所以放弃率上升是回归信号，不是安全网生效 —— 每条出口都留痕（[noteGiveUp] / [noteBranch]）。
 */
internal object MonochromeSynth {

  /**
   * 首次合成的边长。桌面随后 `setBounds` 到**图层 bounds**（= 1.5 × 图标尺寸），
   * 比这个大就按需重合成（见 [MonochromeLayer.draw]）—— 所以这里只是「够用」而非「精确」。
   */
  private const val MIN_EDGE = 192

  /**
   * 合成边长的**上限**。没有上限时，一次超大的请求会把这台机器的质量目标永久抬上去
   * （[learnEdge] 取单调最大值，而模型那套 `ThreadLocal` 缓冲**只长不缩**），
   * 于是每张图的数组分配与耗时都跟着它走。
   *
   * **常驻是量出来的，不是估的**（`_harness/PartAreaKt --mem`：反射加模型自己那块缓冲的每个数组）：
   * `192 → 1.7MB`、`384 → 6.2MB`、`512 → 10.8MB`、`768 → 23.9MB` **每条线程**。桌面在多少个线程上
   * 取过 monochrome，就要乘多少遍 —— 这是这条上限存在的主要理由，比耗时那条更硬。
   *
   * 768 这一档也是量出来的：判据台（`_harness/CompatKt`）上 384² 最凶的一档实测 **285ms/张**、
   * 768² 是 **474ms/张**；真机日志里出现过的边长只有 192 与 384 两档（4242 行全是 384）。
   * 按图层 bounds = 1.5 × 图标尺寸推，常见密度下桌面要的都在 650 以内 —— 768 把所有现实场合
   * 都留在上限之下，只有异常大的请求会被压低，而**压低看不出来**：交出去的是一条连续 alpha
   * 斜坡，放大是带滤波的（[MonochromeLayer.draw]）。
   */
  private const val MAX_EDGE = 768

  /**
   * 掩码缓存的字节上限（见 [store]）。8 MB ≈ 55 张 384² 的掩码。
   *
   * 按字节而不是按条数封顶：一张 ALPHA_8 掩码 = 边长² 字节（384² = 147 KB），桌面一次会话里
   * 会问几百个图标 —— 按条数封顶的话，上限是多少内存取决于桌面问的是哪些尺寸，等于没人管
   * （原来那句「256 条，图标数本来有界」是 37 MB，住在桌面进程里）。
   * 超了就整体清掉：图标数有界，清一次的重建代价只是几毫秒 × N 张，比常驻几十 MB 划算；
   * 做 LRU 是给这个缓存加它不需要的复杂度。
   */
  private const val MAX_CACHE_BYTES = 8L * 1024 * 1024

  private var cacheBytes = 0L

  /**
   * 按图标**实例**缓存已经压好的**掩码位图**（[IdentityHashMap] 语义：同一个对象才命中）。
   *
   * 用实例而不是内容做 key：桌面滚动时同一个图标对象会被反复取 monochrome，每次都重压一遍太贵。
   * 图标对象由桌面的 IconCache 长期持有、数量有界，所以这里不会无限增长。
   *
   * ⚠ 缓存的是**位图**而不是 [MonochromeLayer] 实例：掩码是只读的，可以放心共享，而 Drawable
   * 不行 —— 同一个实例被两个父 `AdaptiveIconDrawable` 同时持有的话，`setBounds` / `setCallback`
   * 会互相抢（图层 bounds 是父级在 `onBoundsChange` 里设的）。所以每次 [forIcon] 都交出一个
   * **新实例**，只共享那张位图。
   */
  private val cache = IdentityHashMap<Drawable, Bitmap>()

  /**
   * 给 [source] 合成一个单色层；合成不出来就返回 null，让调用方照原样走下去。
   *
   * 尺寸已知就一次到位，不知道才按 MIN_EDGE 探一次（探的意义是「压不出形状就别交空壳出去」，
   * 那会让桌面以为有单色图标、结果画出一片空白）。一次会话里桌面给的图层 bounds
   * 是常量，所以只有第一个图标付两次。
   */
  @Synchronized
  fun forIcon(source: Drawable): Drawable? {
    cache[source]?.let { return MonochromeLayer(source, it).also { noteSynthesized() } }
    val mask = render(source, minOf(if (knownEdge > 0) knownEdge else MIN_EDGE, MAX_EDGE))
      ?: return null
    store(source, mask)
    return MonochromeLayer(source, mask).also { noteSynthesized() }
  }

  private fun store(source: Drawable, mask: Bitmap) {
    val size = mask.byteCount.toLong()
    if (cacheBytes + size > MAX_CACHE_BYTES && cache.isNotEmpty()) {
      cache.clear()
      cacheBytes = 0
    }
    val previous = cache.put(source, mask)
    cacheBytes += size - (previous?.byteCount?.toLong() ?: 0L)
  }

  /**
   * 桌面实际给的图层 bounds，由 [MonochromeLayer.draw] 回报，**夹在 [MAX_EDGE] 以下**。
   *
   * 只取见过的**最大值**：搜索框、通知里那种小尺寸场合不该把探测尺寸拉回 192，
   * 宁可多压一次也不交出偏小的掩码。
   */
  @Volatile
  private var knownEdge = 0

  fun learnEdge(edge: Int) {
    val capped = minOf(edge, MAX_EDGE)
    if (capped > knownEdge) knownEdge = capped
  }

  /**
   * 桌面上一次绘制要的尺寸**比手上这张大**：记下尺寸，并作废这一张的缓存，
   * 让下一次 `getMonochrome()`（在图标的加载线程上）按新尺寸重压。
   *
   * ⚠ 为什么不在 `draw()` 里当场重压：见 [MonochromeLayer.draw]。本机实测那条路一次都没走到
   * （15 行 `layer bounds=384x384 mask=384`），但它对别的桌面是**主线程上几百毫秒**。
   */
  @Synchronized
  fun demote(source: Drawable) {
    val previous = cache.remove(source) ?: return
    cacheBytes -= previous.byteCount.toLong()
  }

  /**
   * 累计交出去多少张单色层。**两条入口都经过 [forIcon]，所以一个计数器就够**。
   *
   * 为什么非要有这个埋点：默认配置（裁圆开着）下桌面拿到的图标几乎都是 [CircleIconDrawable]，
   * 那条路走的是它自己的 `getMonochrome()` override、**不经过**
   * `IconHooks.hookSynthesizedMonochrome` 里那个计数器 —— 也就是说主路径原先一条日志都没有。
   * 而这个项目已经吃过「hook 的提前 return 静默返回、最难查」的亏，所以补上：看到
   * `Mono: N layers synthesized` 就说明合成真的在跑，而不是悄悄返回 null 让桌面走了老路。
   */
  private fun noteSynthesized() {
    val total = synthesized.incrementAndGet()
    if (total <= LOG_FIRST || total % LOG_EVERY == 0) {
      // H0 的射程跟着报：这一支成立的前提是"桌面交给我们的位图"的几何口径（见 [regionGround]
      //  那道画布门槛）。换一台桌面看不见像素的时候，这两个数是唯一能看见的东西 ——
      //  接手数掉到 0 而"画布拦"涨起来 = 这台桌面把图标都留了边距，那一类我们不修，
      //  但这必须**说出来**，不能静默。
      Log.d(
        TAG, "Mono: $total layers synthesized  H0: 接手=${h0Taken.get()} 画布拦=${h0Canvas.get()}" +
          " 关系拦=${h0Relation.get()}  同色退=${vacuous.get()}"
      )
    }
  }

  /** 被「底与墨同色」那道验收退掉过至少一支假设的张数（见 [MonochromeMask.GROUND_INK_SAME_PCT]）。
   *  这一道改的是**谁接手**，所以它必须单独有一个数：换一台桌面后如果这个数掉到 0，
   *  说明那台桌面的图标全都有真底；如果它涨得比 `接手` 还快，说明送来的层大多是"单色剪影"，
   *  那一类本来就该整块交出去。 */
  private val vacuous = AtomicInteger(0)

  private fun noteVeto(bits: Int) {
    if (bits != 0) vacuous.incrementAndGet()
  }

  /** H0（区域关系那一支）逐张归类的三个数。0 与"没走到"不记 —— 那既不是成绩也不是失败。 */
  private val h0Taken = AtomicInteger(0)
  private val h0Canvas = AtomicInteger(0)
  private val h0Relation = AtomicInteger(0)

  private fun noteH0(why: Int) = when (why) {
    1 -> { h0Taken.incrementAndGet(); Unit }
    2 -> { h0Canvas.incrementAndGet(); Unit }
    3 -> { h0Relation.incrementAndGet(); Unit }
    else -> Unit
  }

  private val synthesized = AtomicInteger(0)

  private const val LOG_FIRST = 3

  private const val LOG_EVERY = 40

  /**
   * 按 [edge] 边长压一张单色掩码（ALPHA_8）；压不出像样的形状时返回 null。
   *
   * [edge] 的语义是**图层 bounds**（= 1.5 × 图标尺寸），掩码按整张 108dp 画布出图 ——
   * 为什么是 108dp 画布而不是可见区，见类文档的坐标系那一节。
   *
   * 加锁的理由与 `IconBitmap.monochrome` 一样：让 [MonochromeLayer.draw] 的按需重合成也走
   * 同一条路。模型自己是 `ThreadLocal` 缓冲、无共享状态，这把锁护的是缓存与桌面那边。
   */
  @Synchronized
  fun render(source: Drawable, edge: Int): Bitmap? {
    if (edge <= 0) return null
    return runCatching {
      val adaptive = source is AdaptiveIconDrawable
      val label = describe(source, adaptive)
      // **只取前景层，前提是背景层真的是一张盘。** 主人 2026-10-09 连着两次点
      // `com.google.android.apps.translate`：「"文"这个字也没有」。查的结论不是模型判错 ——
      // 那张的 `fg=BitmapDrawable` 里**只有左半的 G 与那道斜切**，"文"在**背景层**；
      // 旧规则无条件取 `source.foreground`，**模型从来没收到过那个字**。
      // 判据 = 背景层是不是 `ColorDrawable`：是，则图形确实只在前景层（桌面自己会补一张盘）；
      // 不是（矢量/位图/渐变/层），背景里带着东西，就得把整张交给模型。
      // ⚠ 波及面先量过：`corpus_runtime` 91 张自适应里背景是 `ColorDrawable` 的只有 **34** 张，
      //   另外 **57** 张（矢量 29 + 位图 25 + 渐变 2 + 层 1）会换输入。
      //   对表台按"整张输入"重跑过一遍（`YardKt --input=composite`）：
      //   `med 1.9→0.4`、`mean 7.1→6.7`、`iouMed 0.812→0.876` 三项变好，
      //   `dAlphaMed 8.1→16.7` 一项变差（整张的面积大，逐格 Δα 的分母跟着大）。
      // ⚠ 这一改同时把"盘在图里"的那批交给了**切底**那条路 —— 那正是本仓量过三次的墙。
      //   所以装机必须看：换输入之后**背景层是纯色但画成位图**的那批会不会被涂满。
      val flatten = adaptive && source.background !is ColorDrawable
      val target = if (adaptive && !flatten) source.foreground else source
      if (target == null) {
        noteGiveUp(edge, label, "取不到前景层")
        return@runCatching null
      }
      // ⚠ 整张那一路**不能**用 `rasterize(source)`：`AdaptiveIconDrawable.draw()` 按视口画，
      //   会把图标放大 1.5 倍再裁掉四边（主人 2026-10-09「不少图标被裁切放大了」）。
      val raster = if (flatten) rasterizeLayers(source, edge) else rasterize(target, edge)
      if (raster == null) {
        noteGiveUp(edge, label, "光栅化失败")
        return@runCatching null
      }
      // ── 这里原来有一道「整张已是灰度 → 放弃合成」，2026-09-27 删掉，两条理由 ──
      //  ① 逻辑上它站不住：能走到这一行，说明最外层那次 `getMonochrome()` 返回了 null ——
      //     也就是**这个应用没有单色层**。"应用自己适配过"这个前提已经被调用方否掉了，
      //     一张黑白 logo 和一张真正的单色层，在这里不是同一件事。
      //  ② 代价是看得见的：交回去 = 交给桌面的 `MonochromeIconFactory`，它假定「亮=墨」，
      //     于是**浅底深形**的图标整个反色。真机点名的小黑盒就是这一类（原图：深灰六边形
      //     在浅底上；我们合成给它 底占=0.77、框内=0.18、六边形成墨 —— 极性是对的；
      //     桌面给的是反过来的一块）。它一天里在这条 return 上走了 18 次，
      //     而漏接计数正好爬到 3 —— 屏幕上他看到的「反下色」。
      //  通知侧不看这个开关（那边是系统自己上色，不走这条遮罩路径）。
      val total = edge * edge
      val pixels = IntArray(total)
      raster.getPixels(pixels, 0, edge, 0, 0, edge, edge)
      // 彩色那支的裁剪窗口 —— **量在彩色量的那个对象上**（整个 `source` 光栅化到 128），
      // 见 [geometryWindow]。只在「强制裁圆」开着时量 —— 主人那条原则是"两个开关都开着，
      // 取色才跟着裁好的图走"，关掉裁圆时桌面看到的就是原图，剪影不该被一个没开的功能搬动。
      val win =
        if (ModuleRuntime.options().circleEnabled) geometryWindow(source) else null
      // `!flatten` 且是自适应 = 交给这里的**只有前景层**，那张盘在背景层里（桌面自己补）。
      // 把这件事告诉模型：这一张里不许有"实心的一块当底" —— 混元那半个太极就是这么丢的。
      // ⚠ `flatten` 那一路（背景是一张真 drawable）**另起一张只画背景层的**，作为 `under` 一起交
      //   给模型：那一层自己也可能就是一块盘，而合成之后模型只认一块底，另一块会被整片交成墨
      //   （主人 2026-10-11 点名的 translate「半边黑了」就是这一张）。见 [MonochromeMask.build] 的 `盘刀`。
      // ⚠ **「盘在另一层」这个前提会假。** 主人 2026-10-11 点名 `oss.krtirtho.spotube`
      //   「背景是黑色，被硬生生反色了」—— 它把一块**不透明黑底画进了自己的前景层**（背景层确实是
      //   `ColorDrawable`，所以按上面的规则只取了前景）。于是告诉模型"这层里不许有底" = H3，
      //   那块黑没有归属，被整片交成墨：`底占=0.00 框内=0.91`。同一张按"这层里可以有底"跑，
      //   得到的是 `底占=0.83 框内=0.09`（霓虹耳机是墨、黑底是盘）—— **差的就是这一位**。
      //   判据：前景层自己的最大色族盖到整张画布的这么多%，就说明这块层里有一张盘，别说它没有。
      //   ⚠ 线画在 35% 之前先摊过分布（`_harness/FgPlateKt.kt`，34 张走这条路的）：中位 13、
      //   p90 28，**≥35% 的只有 4 张** —— 贴吧 59 / kazumi 44 / spotify 38 / spotube 44。
      //   而逐张对过：前三张翻不翻**结果完全一样**（spotify 两条路逐字节相同），
      //   只有 spotube 真的变。所以这一条的波及面就是一张，不是 34 张。
      val plateLayer = adaptive && !flatten && fgPlatePct(pixels) < FG_PLATE_MIN_PCT
      // ⚠ `flatten` 那一路（背景是一张真 drawable）**另起一张只画背景层的**，作为 `under` 一起交
      //   给模型：那一层自己也可能就是一块盘，而合成之后模型只认一块底，另一块会被整片交成墨
      //   （主人 2026-10-11 点名的 translate「半边黑了」就是这一张）。见 [MonochromeMask.build] 的 `盘刀`。
      val under = if (flatten && adaptive) rasterizeLayer(source.background, edge) else null
      // 取完像素就回收：这张 384² 的 ARGB 有 590KB，留着要一直活到 `raster` 那两处 recycle 才走。
      val underPx = under?.let { u -> IntArray(total).also { a -> u.getPixels(a, 0, edge, 0, 0, edge, edge) }
        .also { u.recycle() } }
      // ── 乙：模型吃的是**裁圆调整好的那张图标**（主人三次要的就是这一条）──
      //   开着裁圆与取色，取色就该按桌面上那张调好的图来，而不是按应用原始的图层。
      //   窗口来自 [geometryWindow]（与彩色同一个对象、同一套判据），搬的是**输入**。
      // ⚠ `plateLayer` 仍按**原始像素**判：它问的是"盘在哪一层"，那是图层结构的事，
      //   不会因为放大而改变；而 35% 那条线是在原始取景下摊过分母定的（上面那段注释），
      //   换取景再比它 = 拿没标定的尺量。`under` 必须一起搬 —— 真机上整张图标都动了。
      // ⚠ 抓取通道带回来的也必须是**搬过的那两张**（`pxIn`/`underIn`），否则判据台复放出来的
      //   掩码不是设备上那一张 —— 这一条是 2026-10-12 那次"同一张输入、两边算出两张掩码"的教训。
      val inset = visibleInset(edge)
      val visT = edge - 2 * inset
      val pxIn = if (win == null) pixels
      else IconCropWindow.reframe(pixels, edge, edge, win, inset, inset, visT, visT)
      val underIn = if (win == null || underPx == null) underPx
      else IconCropWindow.reframe(underPx, edge, edge, win, inset, inset, visT, visT)
      val out = MonochromeMask.build(pxIn, edge, edge, plateLayer, underIn)
      // H0 的射程跟着记：见 [noteSynthesized] 那条汇总为什么带它。
      noteH0(out.h0why)
      noteVeto(out.veto)
      // 指纹打在判据行上：解不出包名的那一批（快捷方式）在日志里都叫 `?`，没有这个数
      // 就对不上"哪一行判据是哪一个带回来的 PNG"。见 [MonoDump.sigOf]。
      val sig = MonoDump.sigOf(pxIn, edge, edge)
      val id = noteBranch(edge, sig, label, out, win)
      val isWatched = MonoDump.watched(label)
      // ⚠ **变体标记必须进带回来的那个名字。** 桌面会把**同一张图标按不同尺寸再画一遍**：
      //   真机实测（`live2.txt`，2026-10-11 20:34–21:39）translate 在 `edge=384` 下有两种前景层
      //   —— `@432x432 intrinsic=288x288`（45 次）与 `@425x425 intrinsic=283x283`（3 次），
      //   而这两张算出来的掩码**不一样**（`盘刀 59430 / 分档第2级 134` vs `59351 / 155`）。
      //   8×8 指纹认不出这种差别（两种尺寸给出同一个 `sig=ce1fbfbd3fe7`），于是去重把后到的那张
      //   吞掉、重放时两张又写进同一个文件名 —— 2026-10-12 那次"同一张输入、设备和判据台算出两张掩码"
      //   就是这么判错的：**比的根本不是同一张。** 分歧不在 `under`。
      val variant = label.substringAfter("fg=", "").substringBefore(" intrinsic", "")
        .replace(Regex("[^A-Za-z0-9]"), "")
      val dump = {
        // 带上包名：不带回来的话，重放出来的预览图不知道是哪个应用的 —— 而那正是要对的东西。
        // 包名解不出来时（`?`）退而带指纹，重放的文件名里就带着它。
        // ⚠ 三个输入**一个都不能少**：模型吃的是 `(合成图, 层底, under)`，重放只拿到合成图时
        //   `盘刀/反相` 读的那一层就不存在了 —— 复放出来的掩码不是设备上那一张（见 [MonoDump.maybe]）。
        MonoDump.maybe(id, label, raster, pxIn, out.mask, underIn, plateLayer, edge, edge,
          "${label.substringBefore(' ')}_${sig}${if (variant.isEmpty()) "" else "_$variant"} | ${out.report}${geoTag(win)}", isWatched)
      }
      // 可疑的那几张把**原像素**带回来（PNG → base64 → 分片进日志），判据台原样重放。
      // 没有这条通道，"不够细致"永远只能靠猜：他报的应用发的是 vector / webp，台子解不出。
      // ⚠ 但只按 `suspicious` 会把他**点名**的那几张漏掉：一块灰框在形状体检里是"规整、连通、
      // 面积合适"的好形状，一个可疑指标都不触发 —— 而它恰恰是要看的东西。所以点名的必带。
      // 带不带还得过预算那一关，见 [MonoDump.maybe] 里 `WATCH_RESERVE_KB` 那一段。
      if (MonoDump.suspicious(out) || isWatched) dump()
      val ramp = out.mask
      if (ramp == null) {
        raster.recycle()
        return@runCatching null
      }
      // 掩码这一步**不再搬位置** —— 乙已经在输入侧搬过了（窗口映射到的就是可见区）。
      val alpha = toAlphaMask(ramp, edge, edge)
      // 非自适应图标的内容是满幅的，而它在图层 bounds 里只该占中间那 2/3（可见区），
      // 所以掩码算完**之后**才缩进 —— 顺序反了就会踩 [rasterize] 上面那个坑。
      // ⚠ 但有窗口时**不能再缩一遍**：`pxIn` 那一步已经把窗口放进可见区了。
      val placed0 = if (adaptive || win != null) alpha else intoVisibleArea(alpha, edge)
      // 最后一步：**剪影归一（只缩不放）** —— 顶到盘边的那一批缩回安全区，本来在区里的不动。
      val placed = normalizeSilhouette(placed0, edge, label)
      noteDiagnosis(edge, label, raster, placed)
      raster.recycle()
      if (placed !== placed0) placed0.recycle()
      if (placed0 !== alpha) alpha.recycle()
      placed
    }.onFailure {
      // ⚠ 这一条不能省：上面整个块包在 runCatching 里，任何异常都会被 `getOrNull()` 变成一个
      // 正常的 null 出口 —— 而 null 在功能上就是「放弃合成、桌面照旧」，不崩、不报错。
      // 上一轮那个「ALPHA_8 的 rowBytes 带 padding，掩码写歪 → copyPixelsFromBuffer 抛异常」
      // 就是这么在真机上完全看不见的：日志里只有「压不出掩码」，看不出**为什么**压不出。
      // 本项目已经吃过两次「提前 return 静默失效」的亏，异常这条同理。
      Log.w(TAG, "Mono: render threw at edge=$edge: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
  }

  /**
   * 诊断用：这个图标到底是哪种 drawable、前景层多大、走的哪条口径。
   *
   * 光看判据数字认不出源 —— 「66% 落在 1~127」既可能是柔光、也可能是半透明底板、还可能是
   * 低分辨率位图被放大。把类名和固有尺寸一起打出来，才能把数字对上号
   * （`BitmapDrawable@108x108` 和 `VectorDrawable@-1x-1` 是两回事）。
   *
   * `adaptive=` 是**这次要看的关键位**：`false` 表示这是非自适应图标 —— 它的掩码算完之后还要
   * 缩进可见区，正是「全是色块」问题所在的分类。
   */
  private fun describe(source: Drawable, adaptive: Boolean): String {
    val foreground = (source as? AdaptiveIconDrawable)?.foreground
    return buildString {
      // 包名/资源名放最前面：报一个应用名就能直接 grep 到它那一行（认不出打 `?`）。
      append(IconNames.of(source)).append(' ')
      append(source.javaClass.simpleName)
      if (foreground != null) {
        append(" fg=").append(foreground.javaClass.simpleName)
          .append('@').append(foreground.intrinsicWidth).append('x').append(foreground.intrinsicHeight)
      }
      append(" intrinsic=").append(source.intrinsicWidth).append('x').append(source.intrinsicHeight)
      append(" bounds=").append(source.bounds.width()).append('x').append(source.bounds.height())
      append(" adaptive=").append(adaptive)
    }
  }

  /**
   * 诊断：这一次**放弃了合成**，以及为什么。
   *
   * 存在的理由是本项目吃过的那条亏 —— **hook 的每条提前 return 都要留痕**。「返回 null」在
   * 功能上是正常降级（桌面会走自己的路），但和「压根没进这条函数」在日志里长得一模一样，
   * 排查时就分不清「合成失败」还是「合成没被调用」。
   */
  private fun noteGiveUp(edge: Int, label: String, reason: String) {
    val n = diagnosed.incrementAndGet()
    Log.d(TAG, "Mono: diag#$n edge=$edge $label 放弃合成：$reason")
  }

  /**
   * 诊断：这个图标走了哪一支、模型判出来的那几个数、最后有没有交出掩码。
   *
   * ⚠ 这是**每个图标一行**的廉价留痕 —— 不复算任何东西，数字是模型自己带出来的
   * （[MonochromeMask.Output.report]），所以这一条不打上限。贵的那条（输入直方图 + 掩码
   * 缩略图）汇总在 [noteDiagnosis]，那条才有限额。
   *
   * 为什么非要逐条打：报一个应用名就能 grep 到它那一行，判据落在哪、被谁拦下，一眼看得见。
   * 上一轮「白块」的教训不是没打数，而是**数打在错的分母上、看着完全健康** —— 所以这里的
   * `框内=` 就是判据自己吃的那一个口径（内容外接框）。
   */
  private fun noteBranch(
    edge: Int, sig: String, label: String, out: MonochromeMask.Output, win: IconCropWindow.Window?,
  ): Int {
    val n = diagnosed.incrementAndGet()
    Log.d(TAG, "Mono: diag#$n edge=$edge sig=$sig $label ${out.report} → " +
      (if (out.mask != null) "已交出掩码" else "判据不成立，放弃合成") + geoTag(win))
    return n
  }

  /**
   * 判据行末尾那截几何标记：`几何=满幅`（彩色也没动它）/ `几何=切1.47×`（内切放大）/
   * `几何=缩0.78×`（缩进，尖角不许顶到边）。**这一趟是搬输入** —— 模型吃的就是搬过的那张，
   * 所以这个数变了意味着判据也变了，不是只挪位置。
   *
   * 为什么挂在**这一行**而不是另起一行：图标加载是热路径，本仓的规矩是"每个图标一行"。
   * 装机后要判"染色那支有没有跟着裁圆走"，grep 这一个字段就够了。
   */
  private fun geoTag(win: IconCropWindow.Window?): String = when {
    win == null -> " 几何=满幅"
    else -> " 几何=${if (win.plate) "缩" else "切"}${"%.2f".format(win.zoom)}×"
  }

  /**
   * 诊断：把「输入长什么样、掩码长什么样」打出来。**只诊断，不参与任何判断**。
   *
   * 桌面主题图标这块我看不见（图像通道是 filtered 的，用户也明确不要截图），而「形状不对」
   * 有好几种**成因完全不同**的可能（输入本身就是一块板 / 模型刻错了极性 / 掩码没问题但下游
   * 画坏），靠推演猜已经错过一次。所以这里打两样：
   * 1. **输入 alpha 的四段直方图**：一眼看出这张图是「透明底 + 图形」还是「整张不透明」；
   * 2. **掩码的 ASCII 缩略图**：`#` 不透明、`.` 透明 —— 「是一个完整字形」还是「一堆碎片」
   *    在日志里就能直接看出来。
   *
   * ⚠ 成本：一次全画布读 + 一次掩码连通块，所以限 [HEAVY_DIAG_LIMIT] 条；缩略图再限
   * [ASCII_DUMP_LIMIT] 张（一张 40 行，全放行会淹掉 logcat）。逐条的判据分布由
   * [noteBranch] 那行廉价汇总兜着 —— 降级掉的是**贵的那部分**，不是「每条出口都留痕」这条规矩。
   */
  private fun noteDiagnosis(edge: Int, label: String, input: Bitmap, mask: Bitmap?) {
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
    if (mask == null) {
      Log.d(TAG, "Mono: diag#$n edge=$edge $label in=${w}x$h " +
        "a0=${pct(a0, total)} a1=${pct(a1, total)} a2=${pct(a2, total)} a255=${pct(a255, total)} " +
        "-> 没交出掩码")
      return
    }
    val mw = mask.width
    val mh = mask.height
    val bytes = ByteArray(mask.rowBytes * mh)
    mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    // 四邻域连通块：完整的字形是少数几块，碎掉的会是一大片小块。
    val on = BooleanArray(mw * mh)
    var opaque = 0
    var weight = 0L
    for (y in 0 until mh) {
      val row = y * mask.rowBytes
      for (x in 0 until mw) {
        val a = bytes[row + x].toInt() and 0xff
        weight += a
        if (a >= MASK_DUMP_THRESHOLD) {
          on[y * mw + x] = true
          opaque++
        }
      }
    }
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
    // `out` = 掩码里 ≥128 的像素占比；`fill` = 按 alpha 加权的等效覆盖率（Σalpha/255/面积）。
    // 图标看起来「被填满多少」看 `fill`；`out` 明显小于 `fill` 说明掩码里塞了一大片淡色。
    Log.d(TAG, "Mono: diag#$n edge=$edge $label in=${w}x$h " +
      "a0=${pct(a0, total)} a1=${pct(a1, total)} a2=${pct(a2, total)} a255=${pct(a255, total)} " +
      "out=${pct(opaque, total)} fill=${pct((weight / 255).toInt(), total)} " +
      "cc=$components top=${pct(largest, opaque)}")

    val dumped = asciiDumped.incrementAndGet()
    if (dumped <= ASCII_DUMP_LIMIT) dumpAscii(n, mw, mh, mask.rowBytes, bytes)
  }

  /**
   * 把 ALPHA_8 掩码的原始字节（[maskBytes]）打成 40 列 ASCII：`#` = 不透明、`.` = 透明。
   *
   * 阈值与 `MonochromeMask` 里形状统计用的 `A_SOLID` 同口径（[MASK_DUMP_THRESHOLD]）——
   * 缩略图如果自己用一套阈值，就和真产出的掩码对不上了，那还不如不打。
   *
   * ⚠ 必须传 `mask.rowBytes` 作步长：[maskBytes] 是按行步长填的，用 `y * w + x` 去读会把每一行
   * 往左挪 `(stride - w)` 字节，宽度不是 4 的倍数时整张缩略图**逐行错位** —— 看着像掩码碎了，
   * 其实是日志读错了。
   */
  private fun dumpAscii(n: Int, w: Int, h: Int, stride: Int, maskBytes: ByteArray) {
    val cols = ASCII_COLS
    val line = StringBuilder(cols)
    for (ry in 0 until cols) {
      line.setLength(0)
      val y0 = ry * h / cols
      val y1 = maxOf(y0 + 1, (ry + 1) * h / cols)
      for (rx in 0 until cols) {
        val x0 = rx * w / cols
        val x1 = maxOf(x0 + 1, (rx + 1) * w / cols)
        var hits = 0
        var cells = 0
        for (y in y0 until y1) {
          for (x in x0 until x1) {
            cells++
            if ((maskBytes[y * stride + x].toInt() and 0xff) >= MASK_DUMP_THRESHOLD) hits++
          }
        }
        line.append(if (cells > 0 && hits * 2 >= cells) '#' else '.')
      }
      Log.d(TAG, "Mono: diag#$n mask $ry|$line")
    }
  }

  private val diagnosed = AtomicInteger(0)

  /** 重诊断（[noteDiagnosis]：全画布直方图 + 掩码连通块 + 缩略图）每进程放行几条。 */
  private const val HEAVY_DIAG_LIMIT = 12

  private val heavyDiag = AtomicInteger(0)

  /** 「几何量不到」每进程最多打几条 —— 见 [geometryWindow] 里那条"量不到 ≠ 满幅"。 */
  private const val GEOM_FAIL_LIMIT = 20

  private val geomFailLogged = AtomicInteger(GEOM_FAIL_LIMIT)

  /** 「剪影归一」每进程放行几条日志（每张图标都合成一次，全打会淹掉 logcat）。 */
  private const val SIL_LOG_LIMIT = 40

  private val silLog = AtomicInteger(SIL_LOG_LIMIT)

  /** ASCII 缩略图的行列数（降采样后的画布大小）与最多打几张。 */
  private const val ASCII_COLS = 40

  /**
   * 缩略图上限。40 行一张 —— 在 [HEAVY_DIAG_LIMIT] 之上再收一道，免得放行那 12 条时
   * 一次刷出 480 行把 logcat 淹了（这台机器的 main buffer 一两分钟就滚掉）。
   */
  private const val ASCII_DUMP_LIMIT = 8

  private val asciiDumped = AtomicInteger(0)

  /**
   * 剪影阈值：alpha 低于它就不算图形。与 `MonochromeMask.A_SOLID`、`IconBitmap.MIN_SHAPE_ALPHA`
   * 同一个口径 —— 掩码缩略图、诊断统计、判据台读图必须用同一个数，否则日志看着像另一回事。
   */
  private const val MASK_DUMP_THRESHOLD = 128

  /**
   * 诊断用的占比格式化。
   *
   * ⚠ 必须显式给 [Locale.ROOT]：不指定时 `String.format` 用默认 locale，在小数点用逗号的地区
   * （de / fr 等）会打出 `0,08` —— 日志是给人对着源码读的，换个系统语言就变形了。
   * lint 的 `DefaultLocale` 也会拦这一条。
   */
  private fun pct(part: Int, whole: Int): String =
    if (whole <= 0) "0" else String.format(Locale.ROOT, "%.2f", part.toFloat() / whole)

  /**
   * 裁圆那个窗口 —— **量在彩色那支量的同一个对象上**：把整个 [source] 光栅化到量测格子再量。
   *
   * ⚠ 这里"就是"要用 [rasterize] 画整个 `source`，与 [render] 里那条"整张那一路不能用
   *   `rasterize(source)`"**正好相反** —— 那条讲的是**喂给模型的像素**（要 1.0 倍、不能裁边），
   *   而这里要的是**桌面上那个块头**：`AdaptiveIconDrawable.draw()` 按视口画（子层 1.5 倍铺出去、
   *   裁掉四边），彩色那支 `MaskedDrawable` 量的就是这个东西。
   *   量错对象 = 把剪影统一多放大约 1.5 倍（2026-10-14 第一版就错在这里：量前景层给出
   *   元宝 2.26×，而彩色实际是 1.49×；62 张里 13 张全不一致，其中 6 张彩色根本不动）。
   *
   * 成本：一张 128 边长的光栅化 + 一趟扫描，真机同类量测中位 0.60ms，而这里每次**合成**一次
   *   （不是每帧），合成本身是几十毫秒那一档。
   */
  private fun geometryWindow(source: Drawable): IconCropWindow.Window? {
    val edge = IconCropWindow.scanEdge
    val bmp = rasterize(source, edge)
    if (bmp == null) {
      // **量不到 ≠ 满幅**：这两件事如果打成同一个字，装机上就永远分不清「这张确实不用搬」
      // 和「我根本没量成」—— 本仓为这类事翻过不止一次车。
      if (geomFailLogged.decrementAndGet() >= 0) {
        Log.w(TAG, "Mono: 几何 ${IconNames.of(source)} 光栅化失败 → 量不到，不搬")
      }
      return null
    }
    return try {
      val px = IntArray(edge * edge)
      bmp.getPixels(px, 0, edge, 0, 0, edge, edge)
      // 视口空间 → 图层空间：掩码活在 108dp 画布里，桌面画它时按 1.5 倍铺出去再裁边。
      // 不换这一手，两个 1.5 正好抵消，重排等于没做（见 [IconCropWindow.inLayer]）。
      IconCropWindow.of(px, edge, edge)?.inLayer(AdaptiveIconDrawable.getExtraInsetFraction())
    } finally {
      bmp.recycle()
    }
  }

  /**
   * 把 [target] 光栅化成 `edge × edge` 的位图，一律 `setBounds(0, 0, edge, edge)`。
   *
   * `edge` 的坐标系是**图层 bounds**（= 1.5 × 图标尺寸，也就是整张 108dp 画布）—— 为什么不是
   * 可见区，见类文档的坐标系那一节。
   *
   * ## ⚠ 两种图标都不缩进
   * 非自适应图标的内容是满幅的，看起来「应该先缩到可见区再光栅化」。**不行**，这是
   * 2026-09-24 真机「非自适应图标全部变成色块」的病根：缩进会在四周留出一圈 `edge/6` 的透明边，
   * 而判据要量「内容框内透明像素占多少」—— 那一圈边正好把一张满幅不透明的老式图标量成
   * 「透明底 + 图形」。分工是：**分支判定用满幅口径**，**位置用可见区口径**（[intoVisibleArea]），
   * 两者各自独立、互不干扰。
   *
   * ⚠ 为什么必须还原 bounds：自适应图标的前景层是**应用那份图标对象自己持有的**
   * （`sourceIcon.foreground`），不是我们的副本 —— 改它的 bounds，改的是应用那份。
   * 而 `AdaptiveIconDrawable.draw()` 重画图层时用的是**子层当前的 bounds**
   * （`mLayerState.mChildren[FOREGROUND_ID].mDrawable.draw(mCanvas)`，不再重新 setBounds），
   * 且 `setBounds` 会让它 `invalidateSelf()` 把缓存的 layers shader 置空。于是我们改完不还原
   * 的话，下一次画**原图标**时前景层就按我们留下的 bounds 画：锚点跑到图层原点、尺寸缩一圈 ——
   * 图标直接画坏，而且只在「取过 monochrome 之后又重画过原图标」时才出现，属于最难查的那类偶发。
   *
   * 通知侧没这个问题：那边是「拿一个图标画一次就扔」，同一个 drawable 不会被反复画。
   */
  private fun rasterize(target: Drawable, edge: Int): Bitmap? {
    val saved = Rect(target.bounds)
    // ⚠ target 自己就是 AdaptiveIconDrawable 时，光栅化它等于**顺带改了它两个子层的 bounds**：
    //   `AdaptiveIconDrawable.draw()` → `updateLayerBoundsInternal()` 会按这次的 bounds 重算
    //   背景层与前景层，而它之后重画图层用的是子层**当前** bounds、不再 setBounds。
    //   只还原父层的话，那份「按 64px 算出来的子层 bounds」就留在应用自己的图标对象上，
    //   桌面下次画原图标时按 1/6 尺寸铺进 384 的画布 —— 观感就是「图标突然变糊」。
    //   这是上面那条坑（前景层要还原 bounds）在上一层的翻版。
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
   * **按图层空间**把自适应图标的两层压平成一张（不走 [AdaptiveIconDrawable.draw]）。
   *
   * ⚠ 为什么不能直接栅格化父 drawable（这一条是我 2026-10-09 亲手撞的）：
   *   `AdaptiveIconDrawable.draw()` 画的不是图层，是**视口** —— 图层 bounds = 1.5 × 图标尺寸
   *   （`updateLayerBoundsInternal` 按 `VIEW_PORT_SCALE` 算），而 `draw()` 只把中央那 2/3
   *   铺满它自己的 bounds。所以 `setBounds(0,0,edge,edge); draw()` 得到的是一张
   *   **放大 1.5 倍、四边被裁掉**的图。主人当天那句「不少图标被裁切放大了」就是这么来的 ——
   *   我为了让 translate 的背景层进画面，把整张 drawable 直接画了。
   *
   * 这里把两层各自 `setBounds` 到**整张 108dp 画布**再依次画，与"只取前景层"那条口径同一坐标系，
   * 交出去的掩码仍然是桌面要的「整张 108dp 画布」。bounds 的还原理由与 [rasterize] 里那条一样
   * （子层必须自己还原，不能指望父层那次 setBounds 带回去）。
   */
  /** 走"只取前景层"那条路时，前景层自己的最大色族盖到整张画布的这么多%，
   *  就说明**这张盘在这一层里**，不能再对它说"底只许是空气"。见 [render] 里那段。 */
  private const val FG_PLATE_MIN_PCT = 35

  /** 前景层里最大色族占**整张画布**的百分比（粗量化，每通道 24 一档，与 `plateKey` 同口径）。 */
  private fun fgPlatePct(px: IntArray): Int {
    if (px.isEmpty()) return 0
    val fam = HashMap<Int, Int>(); var solid = 0
    for (p in px) {
      if (Color.alpha(p) < 128) continue
      solid++
      val k = ((((p shr 16) and 0xff) / 24) shl 10) or
        ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)
      fam[k] = (fam[k] ?: 0) + 1
    }
    if (solid == 0) return 0
    return (fam.maxByOrNull { it.value }?.value ?: 0) * 100 / px.size
  }

  private fun rasterizeLayers(source: AdaptiveIconDrawable, edge: Int): Bitmap? {
    val bg = source.background
    val fg = source.foreground ?: return null
    val savedBg = bg?.let { Rect(it.bounds) }
    val savedFg = Rect(fg.bounds)
    return try {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bmp)
      if (bg != null && savedBg != null) {
        bg.setBounds(0, 0, edge, edge)
        bg.draw(canvas)
      }
      fg.setBounds(0, 0, edge, edge)
      fg.draw(canvas)
      bmp
    } finally {
      if (bg != null && savedBg != null) bg.bounds = savedBg
      fg.bounds = savedFg
    }
  }

  /** 只画**一层**，几何与 [rasterizeLayers] 里那两笔一模一样（`setBounds(0,0,edge,edge)` 后直接画），
   *  这样它和合成那张逐格对得上 —— `盘刀` 问的就是"这一格屏幕上看到的是不是背景层那块色"。 */
  private fun rasterizeLayer(d: Drawable?, edge: Int): Bitmap? {
    if (d == null) return null
    val saved = Rect(d.bounds)
    return try {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
      d.setBounds(0, 0, edge, edge)
      d.draw(Canvas(bmp))
      bmp
    } catch (t: Throwable) {
      null
    } finally {
      d.bounds = saved
    }
  }

  /**
   * 图层 bounds 里「可见区」离边缘的距离（两侧各让出多少像素）。
   *
   * 推导：可见区尺寸 `V = edge / (1 + 2f)`（f = [AdaptiveIconDrawable.getExtraInsetFraction]，
   * 即 1/4），两侧各让出 `(edge − V) / 2 = edge × f / (1 + 2f)` = **edge/6**。
   * 本机实测 `edge = 384` → inset = 64，可见区 256（正是图标尺寸）。
   *
   * ⚠ 是 `edge × f / (1 + 2f)`（≈ edge/6），**不是** `edge × f`（= edge/4）—— 两者差 1.5 倍，
   * 写错会让内容在可见区里只占 2/3、四周空一圈。`CircleIconDrawable.ViewportDrawable` 用的是
   * 同一个式子（`frac = (1 − VIEW_PORT_SCALE) / 2`），两处口径一致。
   */
  private fun visibleInset(edge: Int): Int {
    val f = AdaptiveIconDrawable.getExtraInsetFraction()
    return (edge * f / (1f + 2f * f)).toInt()
  }

  /**
   * 把**满幅口径**算出来的掩码缩进**可见区**，四周留透明。
   *
   * 非自适应图标（老式满幅 PNG、图标包给的整张图）的内容铺满整个 bounds，而它在图层 bounds 里
   * 只该占中间那 2/3 —— 与裁圆那边 `MaskedDrawable` 的填充同一口径。不缩进的话，可见区里
   * 只剩这张图的中间 2/3，看起来就是「放大一圈 + 四周被切」。
   *
   * ⚠ 只能在掩码**算完之后**调用，不能提前到光栅化那一步 —— 为什么，见 [rasterize]。
   * 缩放用双线性：掩码现在是一条连续斜坡，混它只会更平滑。
   */
  /**
   * 交付掩码的**剪影归一**：墨的主体出安全区就整体缩回去，**只缩不放**。
   *
   * 为什么放在最后一步、且只碰 alpha 的排布：判据（浓度、分档、盘刀、极性）此时已经算完，
   * 这一手不动任何一个判据数字 —— 那是今晚用真机语料量过的教训（改输入会让 spotube 那一族
   * 换支、把 xbox 推成一块黑饼）。
   *
   * ⚠ 分母是**盘的边长**（可见区），不是整张 108dp 画布；写错的话一堆图标会读成"100% 占满"，
   *   那条"大小不一"的账当场就是假的。判据与尺子都在 [IconCropWindow.silhouetteScale]。
   * ⚠ ALPHA_8 的 `rowBytes` 会补到 4 的倍数，读写都必须按 rowBytes 走（[toAlphaMask] 那条老坑）。
   */
  private fun normalizeSilhouette(mask: Bitmap, edge: Int, label: String): Bitmap {
    val h = mask.height
    val plateSide = edge - 2 * visibleInset(edge)
    val stride = mask.rowBytes
    val bytes = ByteArray(stride * h)
    mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    val a = IntArray(edge * h)
    for (y in 0 until h) {
      val row = y * stride
      for (x in 0 until edge) a[y * edge + x] = bytes[row + x].toInt() and 0xFF
    }
    val s = IconCropWindow.silhouetteScale(a, edge, h, plateSide)
    if (s >= 0.999f) return mask
    val scaled = IconCropWindow.scaleAbout(a, edge, h, s)
    val out = Bitmap.createBitmap(edge, h, Bitmap.Config.ALPHA_8)
    val ob = ByteArray(out.rowBytes * h)
    for (y in 0 until h) {
      val row = y * out.rowBytes
      for (x in 0 until edge) ob[row + x] = scaled[y * edge + x].toByte()
    }
    out.copyPixelsFromBuffer(ByteBuffer.wrap(ob))
    if (silLog.decrementAndGet() >= 0) {
      Log.d(TAG, "Mono: 剪影归一 $label 缩到 ${"%.3f".format(s)}（盘边 ${plateSide}px）")
    }
    return out
  }

  private fun intoVisibleArea(source: Bitmap, edge: Int): Bitmap {
    val inset = visibleInset(edge)
    val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ALPHA_8)
    Canvas(out).drawBitmap(
      source, null, Rect(inset, inset, edge - inset, edge - inset),
      Paint(Paint.FILTER_BITMAP_FLAG)
    )
    return out
  }

  /**
   * ARGB_8888（白色 + alpha）→ ALPHA_8。
   *
   * 桌面取色只读 alpha，留着三个颜色通道是白占内存（每张 147 KB → 36 KB @192px）。
   *
   * **原样搬，不做任何浓度决定。** 旧版这里有一套「内部一律钳实心 + 边缘保底 200」，
   * 那是模型之外第二次改 alpha：判据说"这个像素离底 30%"、这里又改成"实心"，于是日志里的
   * 数字和屏幕上的东西不是一张图。浓度归一现在全部发生在 [MonochromeMask] 里，
   * 那条斜坡就是要上屏的东西。
   *
   * ## ⚠ 必须按 [Bitmap.getRowBytes] 的下标写，不能按 packed 写
   * ALPHA_8 每像素 1 字节，而 rowBytes 会**补到 4 的倍数**，于是 `w` 不是 4 的倍数时
   * `rowBytes > w`。`copyPixelsFromBuffer` 要的是**带 padding 的整块**（`rowBytes × h` 字节），
   * 逐像素连写只交得出 `w × h` 字节 → 缓冲区不够 → 抛异常。而异常会被 [render] 的 `runCatching`
   * 吞成 null：桌面回落到自己的 Factory，**合成整条静默失效**，日志里只剩一句「没交出掩码」。
   * 本机 `edge = 384` 正好是 4 的倍数，所以这条永远踩不到 —— 但桌面实际给的尺寸是
   * `1.5 × iconSize`，density 3.0 的机器给 378、3.375 给 423，全都会踩。
   */
  private fun toAlphaMask(source: IntArray, w: Int, h: Int): Bitmap {
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val stride = mask.rowBytes
    val bytes = ByteArray(stride * h) // 末尾那 (stride-w)×h 个 padding 留 0 = 透明，无害
    var read = 0
    for (y in 0 until h) {
      val row = y * stride
      for (x in 0 until w) bytes[row + x] = (source[read++] ushr 24).toByte()
    }
    mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
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

/**
 * 单色层的载体：一张 ALPHA_8 掩码，画的时候由调用方（桌面的取色逻辑）上色。
 *
 * 不直接用 `BitmapDrawable` 是因为构造它要 `Resources`，而在被注入的进程里拿 Resources
 * 要么拿不到、要么拿到的是宿主的 —— 自己画一个更稳，也少一层依赖。
 * ⚠ 代价是**系统 drawable 自带的那些行为都得自己补上**，尤其是 tint —— 见下。
 *
 * ## tint：真机那条路径其实**不 tint**，但契约仍然接住
 *
 * ### 先纠正一个曾经的误判（2026-09-24）
 * 之前从字节码里读到 `mono.setTint(colors[1])`，就认定「桌面用 `setTint` 给这层上色」。
 * **真机日志把这条推翻了**：`Mono: layer bounds=384x384 … filter=none`，而且**一条 tint
 * 日志都没有** —— 桌面压根没调过。加了「谁在问 / 谁在画」的堆栈埋点后看清了实际路径。
 * 真机日志：
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
 * 现在 `setColorFilter` / `setTint` / `setTintList` / `setTintMode` 四个入口都落到 [paint] 上，
 * 显式 color filter 优先于 tint（和 `BitmapDrawable` 的取舍一致）。⚠ 四个入口都要接：
 * `setTint(int)` 确实会转调 `setTintList`，但那是**基类**的行为，而基类的 `setTintList`
 * 是空的 —— 只重写一个就漏了另一个。
 *
 * ## 为什么掩码是「按需」的
 * 桌面把这张 drawable 当作新 `AdaptiveIconDrawable` 的前景层、`setBounds` 到**图层 bounds**
 * 之后再画（推导见 [MonochromeSynth] 的坐标系那一节）—— 而那个尺寸在取 `getMonochrome()`
 * 的当下还不知道。固定一个尺寸再让桌面放大就是「糊」的根源，所以 [draw] 里量实际 bounds：
 * 比手上这张大就**报上去并作废自己这一张的缓存**（[MonochromeSynth.demote]），
 * 先按放大画（连续 alpha 斜坡，双线性看不出差），重压留给加载线程。
 */
private class MonochromeLayer(
  private val source: Drawable,
  initial: Bitmap,
) : Drawable() {

  /**
   * ⚠ `color = WHITE` 不是随手写的：掩码是 ALPHA_8（只有 alpha，没有颜色），画出来的颜色
   * **取 `Paint` 的 color** —— 这正是 `Bitmap.extractAlpha()` 那套「拿 alpha 当遮罩、
   * 颜色由画笔决定」的用法。默认的 `Paint` 是黑色，万一哪条路径没给 tint 也没给 color filter，
   * 黑字形会在深色底上直接消失；而白色剪影至少和「我们交出去的本来是一张白色剪影」语义一致。
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

  /** 手上这一张掩码。取到之后**不再换**：尺寸不够时交给加载线程重压（见 [draw]）。 */
  private val mask: Bitmap = initial

  /** 每个实例只打一次 bounds 日志，见 [draw]。 */
  private var loggedBounds = false

  /** 尺寸不够这件事也只报一次，见 [draw] 里 `demoted` 那条。 */
  private var demoted = false

  override fun draw(canvas: Canvas) {
    val edge = maxOf(bounds.width(), bounds.height())
    // 把桌面实际给的尺寸回报上去：后面的图标就一次按它出图，不再先探 192 再重压。
    MonochromeSynth.learnEdge(edge)
    if (edge > mask.width && !demoted) {
      // 手上这张比桌面要的**小**：先放大画出去，同时让这一张的缓存作废 ——
      // 重压交给下一次 `getMonochrome()`，那是图标的**加载线程**。
      //
      // ⚠ 绝不在这里当场合成。`draw()` 跑在桌面的绘制线程上，而一次全像素合成实测
      // 8~285ms（384²，判据台量到最凶的那一档），外加 [MonochromeSynth.render] 是
      // `@Synchronized` 的 —— 首屏一批图标要排队，那就是一次可被 jank 检测抓到的卡顿。
      // 本机实测这条路一次都没走到（日志里 15 行全是 `layer bounds=384x384 mask=384`，
      // 且 4182 次合成全在加载线程、主线程 0 次），但**换一台桌面就不一定了**。
      // 放大看不出差：交出去的是一条连续 alpha 斜坡，双线性滤波（paint 带 FILTER_BITMAP_FLAG）。
      //
      // ⚠ `demoted` 不是省一次调用，是**别每帧抢那把锁**：[MonochromeSynth.demote] 与合成共用
      // 同一把监视器，桌面滚动时这里每帧进来就会排在一张正在压的图标后面 —— 那还是卡顿。
      demoted = true
      MonochromeSynth.demote(source)
    }
    // 真机上把「桌面实际给了多大的 bounds」记下来 —— 类文档里那套「图层 bounds = 1.5 ×
    // 图标尺寸」的推导是从字节码和源码推出来的，但推演在这块踩过坑（我判反过一次方向），
    // 所以留一条**实测**出口：前 [BOUNDS_LOG_FIRST] 张打一次。
    //
    // `mask` 比 `edge` 小是**合法的当前状态**（这一张就是要被放大画的），不是 bug；
    // 长期只有小的在打，说明桌面取 monochrome 的次序里 `knownEdge` 一直没机会生效。
    if (!loggedBounds) {
      loggedBounds = true
      val total = boundsLogged.incrementAndGet()
      if (total <= BOUNDS_LOG_FIRST) {
        Log.d(TAG, "Mono: layer bounds=${bounds.width()}x${bounds.height()} mask=${mask.width} " +
          "edge=$edge alpha=${paint.alpha} filter=${paint.colorFilter?.javaClass?.simpleName ?: "none"}")
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
   * ⚠ 别的桌面路径走的**就是这个入口**（`mono.setTint(colors[1])`），所以必须直接重写它。
   *
   * 只重写 [setTintList] 是不够的 —— 那是在赌「基类的 `setTint` 会转调 `setTintList`」。
   * 两个入口都接住，才不管桌面走哪条。理由与空方法体的那段源码见类文档。
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
   * 为什么非要留一条**实测**出口：「桌面用 `setTint` 上色」这个结论是从字节码读出来的，
   * 而「自定义 Drawable 不重写 setTint 就会丢 tint」我一开始没意识到，整个链路静默失效、
   * 只有观感异常。以后同类问题一眼就能分清是「桌面没上色」还是「我们没接住」。
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
   * 而 `Drawable.getConstantState()` 的默认实现就是 `return null` —— 不重写，我们这一层会把
   * 外面那个 `AdaptiveIconDrawable` 的 ConstantState 一起拖成 null。桌面（FloatingIconView 的
   * 「点图标打开」动画）直接 `getConstantState().newDrawable()`，null 就是 NPE = 桌面进程崩。
   *
   * 交出去的 ConstantState 复用当前这张掩码：掩码是只读的、我们从不回收它，多个实例共享同一张
   * Bitmap 是安全的（`BitmapDrawable` 也是这么做的）。各实例的 bounds 仍归各自所有 ——
   * `ChildDrawable` 复制时会自己 setBounds。
   */
  override fun getConstantState(): ConstantState = object : ConstantState() {
    override fun newDrawable(): Drawable = MonochromeLayer(source, mask)

    override fun getChangingConfigurations(): Int = 0
  }
}
