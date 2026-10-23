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
import android.graphics.drawable.GradientDrawable
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
   * 实例层的字节上限（见 [store]）。8 MB ≈ 55 张 384² 的掩码。
   *
   * 按字节而不是按条数封顶：一张 ALPHA_8 掩码 = 边长² 字节（384² = 147 KB），桌面一次会话里
   * 会问几百个图标 —— 按条数封顶的话，上限是多少内存取决于桌面问的是哪些尺寸，等于没人管
   * （原来那句「256 条，图标数本来有界」是 37 MB，住在桌面进程里）。
   * 超了就整体清掉：图标数有界，清一次的重建代价只是几毫秒 × N 张，比常驻几十 MB 划算；
   * 做 LRU 是给这一层加它不需要的复杂度（[MonoCache] 那边才值得做，因为那边是真在命中的层）。
   * ⚠ **别把这一层的预算与 [MonoCache] 那 8 MB 相加当成"总共 16 MB"**：两层存的是**同一批位图对象**，
   *   各自记账、各自过封顶。真实常驻取决于两层有没有装到不相交的两批 —— 最坏 16 MB，常见远小于这个数。
   *   这条只是提醒：**这两个 8 MB 不是两道独立的墙，是一道半**。
   */
  private const val MAX_CACHE_BYTES = 8L * 1024 * 1024

  private var cacheBytes = 0L

  /**
   * 按图标**实例**缓存已经压好的**掩码位图**（[IdentityHashMap] 语义：同一个对象才命中）。
   *
   * ⚠ **这一层的存在理由原来写错过一次，而且是它整条设计的前提**：这里原本写着「图标对象由桌面的
   *   IconCache 长期持有、数量有界」—— 装机数（2026-10-15）直接推翻：桌面每次取 monochrome 都
   *   `getConstantState().newDrawable()` 交一个**新实例**，所以一次重启 1594 次合成里这一层几乎没命中过。
   *   留着它只为"同一个实例被连着问两次"那种场合（应用信息页、长按菜单），那一类命中仍然免费。
   *   **真正干活的是 [MonoCache]**（按内容认，不认对象）。
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

  /**
   * 命中路径的实际耗时（`render` 进门 → 交缓存那张）。没有这一格，“命中白扫两遍”改没改
   * 只能靠结构推断；有它才能在真机上看到命中均值的下降。
   */
  private val hitNanos = java.util.concurrent.atomic.AtomicLong()
  private val hitCalls = java.util.concurrent.atomic.AtomicLong()

  private fun noteHitCost(ns: Long) {
    hitNanos.addAndGet(ns)
    val n = hitCalls.incrementAndGet()
    if (n % HIT_LOG_EVERY == 0L) {
      Log.d(TAG, "Mono: 命中均=${hitNanos.get() / n / 1000}µs ($n 次)")
    }
  }

  private const val HIT_LOG_EVERY = 50L

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
    val t0 = System.nanoTime()
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
      //
      // 2026-10-17 主人把这条判据说成**类型**而不是像素：「背景是不上墨的，渐变底也是」。
      //   所以渐变盘（`GradientDrawable`）与纯色盘同级 —— 整层当盘、只取前景层，
      //   桌面会自己补主题底盘（它只在 `getMonochrome()==null` 时补，正好是这一支的前提）。
      // 🔴 **不能反过来做成"背景层一律不上墨"**：`com.google.android.apps.translate` 那颗"文"字
      //   就画在背景槽里（`bg=Bitmap`，10-09 那次翻案的起因），按层判会把它整个吃掉。
      //   ⇒ 分界只能是"这块底**是不是盘**"，不是"它在哪一层"。
      // ⚠ **波及面现量：今天 0 张开火** —— 语料 123 张里背景是渐变的只有 2 张
      //   （`com.maxmpz.audioplayer`、`com.microsoft.emmx`），而这两张**都自带单色层**，
      //   在第 0 关就被挡、根本走不到这一行。所以这一改是**防以后**（新装应用 / OTA 之后），
      //   不许记成"修好了几张"。
      // ⚠ 名字不能叫 `plateLayer` —— 下面那位是"盘在不在图层里"（喂给模型的 `plateIsLayer`），
      //   两个是不同问题；撞名会编译成 "Conflicting declarations"（本轮真撞上，见 devlog）。
      // ⚠ 必须显式转一下：`source` 的静态类型是 `Drawable`，`background` 是 `AdaptiveIconDrawable`
      //   那一对 getter 才有的（下面 351 行那处已经在 `adaptive` 分支里，靠智能转换过）。
      val bg = (source as? AdaptiveIconDrawable)?.background
      val bgIsPlate = bg is ColorDrawable || bg is GradientDrawable
      // 纯色背景的**颜色**也要交给模型（2026-10-23 Pinduoduo 白心案）：那层盘不在前景里，
      // 但它的颜色会从前景的镂空里透出来 —— 模型拿不到它，就把同色的小物品当成墨、
      // 把白心当成空气。下面先把它作为 `under` 交进去；当前景自己用到了这个颜色时，
      // 再把它整块铺到前景底下（见 `bgOn` 那一段与 [bgMarks]）。
      val bgColor = (bg as? ColorDrawable)?.color
      val flatten = adaptive && !bgIsPlate
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
      // 裁圆那个窗口 —— 量在**模型真吃的那一张**上（`raster` 的像素）。只在「强制裁圆」开着时量。
      // 搬的是**算好的墨**（见下面「甲」那一段），判据本身不看这个窗口。
      // ⚠ **「只给前景层」那一支不许搬**（主人 2026-10-16：强制裁圆开着时，单色几何要和彩色完全一样）。
      //   彩色那支（`MaskedDrawable.measureWindow`）量的是**整个图标** —— `AdaptiveIconDrawable.draw()`
      //   的合成图 = 盘 + 前景，而这一支的背景是纯色盘、盘把整张画布**铺满** → 彩色那边**恒判「满幅」**
      //   （内容按原位交付，屏幕上就是 1.5 倍铺出去）；这里量的是**前景层本身**（图形只占层画布的
      //   ~0.6）→ 拿到的窗口与彩色**不是同一档**，照着搬就是把前景撑到满盘。
      //   `_harness/DeckKt.kt`（`rec_model` 62 张：把两个窗口都换算到**层口径**再比跨度）逐张对表：
      //   这一支差别**方向两边都有** —— uu远程 mono 偏大 11 个点、termux 偏大 7 个点、azure 偏小 5 个点
      //   （正是主人说的"有大有小"）；置空后 **62 张全对齐**。非自适应与 `flatten` 两路本来就同源。
      // ⚠ 真机那一档（19:45 版，2026-10-15 00:23 重放，32 张搬过）**没有作废**：他说"没问题"的最大一张
      //   是 uu远程 **1.75×**、点名的两张是 **1.90×（openlist）/ 2.23×（元宝）**，离线 `_model.png` 量的
      //   1.893 / 2.263 与这两个对得上。**它量的是"倍数"，本条改的是"搬不搬"，两件事** —— 倍数口径一字未动。
      //   （今晚我在这儿加过 1.82 又删掉，是因为拿 20:14 之后那一版的日志当 19:45 的账 —— 那一版
      //   改成量整图标，元宝在它那里是 `满幅`。同一个字段名、两本账，别再对串了。）
      val circle = ModuleRuntime.options().circleEnabled
      // 「只给前景层」= 自适应且背景是纯色盘（`flatten` = false）。见上面那条。
      val fgOnly = adaptive && !flatten
      // 窗口**照量**（几何那一档与日志还要用它，见下），搬不搬由 `win` 定。
      // ⚠ **只在缓存未命中时才需要**：输入（pixels/edge/flags）已经在键里，命中路径白扫一遍 384²。
      val winMeasured by lazy(LazyThreadSafetyMode.NONE) { if (circle) IconCropWindow.measure(pixels, edge) else null }
      val win by lazy(LazyThreadSafetyMode.NONE) { if (fgOnly) null else winMeasured }
      // 极性的取数（彩色那一本账）**不在这里算**：它要额外画一张，而这一趟在 `MonoCache.lookup` 之前 ——
      // 命中那条路今天也要付钱就白付了（真机一次重启 1594 次 bind、只有 209 种内容）。
      // 它只在缓存未命中之后、`plateSubject` 那一段现算。见下面 🔴 那段。
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
      // ⚠ 同上：也**只在缓存未命中时才求值**。`fgPlatePct` 是又一遍全画布扫描，而
      //   `plateLayer` 是 `pixels` 的纯函数 ⇒ 命中路径用它 = 白付。
      val plateLayer by lazy(LazyThreadSafetyMode.NONE) {
        adaptive && !flatten && fgPlatePct(pixels) < FG_PLATE_MIN_PCT
      }
      // ⚠ `flatten` 那一路（背景是一张真 drawable）**另起一张只画背景层的**，作为 `under` 一起交
      //   给模型：那一层自己也可能就是一块盘，而合成之后模型只认一块底，另一块会被整片交成墨
      //   （主人 2026-10-11 点名的 translate「半边黑了」就是这一张）。见 [MonochromeMask.build] 的 `盘刀`。
      val under = if (flatten && adaptive) rasterizeLayer(source.background, edge) else null
      // 取完像素就回收：这张 384² 的 ARGB 有 590KB，留着要一直活到 `raster` 那两处 recycle 才走。
      // ⚠ **纯色背景也要交**（`under` 的一层颜色，不是位图）：桌面把这块底色补在层外，但前景的
      //   镂空里能看见它 —— 模型没有这块参照时，Pinduoduo 那样"白心 + 同色小物品"会被判反。
      val underPx = when {
        under != null -> IntArray(total).also { a -> under.getPixels(a, 0, edge, 0, 0, edge, edge) }
          .also { under.recycle() }
        adaptive && bgColor != null -> IntArray(total) { bgColor }
        else -> null
      }
      // ── 内容缓存：到这里为止，输入已经全了 ──
      //   掩码是 `(pixels, underPx, edge, adaptive, flatten, circle)` 的纯函数，这六样就是键。
      //   为什么这一层必须存在：桌面**每次 bind 都交一个新实例**（`getConstantState().newDrawable()`），
      //   上面那个按实例的缓存结构上命中不了 —— 真机一次重启 1594 次合成、只有 209 种内容，
      //   同一张图标最多重压 148 次，串行在一把锁上就是 760 秒。见 [MonoCache]。
      // ⚠ 键算在这里而不是 `forIcon` 里：算键要像素，而拿像素就得先光栅化。所以命中这条路
      //   仍然付一次光栅化（几毫秒），省下的是整条模型（三百多毫秒）。
      val key = MonoCache.keyOf(pixels, underPx, edge, adaptive, flatten, circle)
      MonoCache.lookup(key, edge)?.let { hit ->
        // 负缓存（`mask == null`）= 这张判据不成立，照旧交 null 给桌面走它自己的路。
        // 出口与不缓存时一模一样，只是不再重算第二遍 —— **两种命中都要留痕**：原来只在
        // `mask != null` 时打，"放弃合成"这条 telemetry 就从每 bind 一次塌成每内容一次，
        // 日志上看着像"这台桌面根本不放弃合成"（本仓吃过四次的那类静默）。
        noteCached(edge, label, hit, raster, key)
        if (hit.mask != null) MonoDump.dumpCached(label, raster, hit.mask, bgColor)
        noteHitCost(System.nanoTime() - t0)
        raster.recycle()
        return@runCatching hit.mask
      }
      // 判据吃的是**原样像素**（浓度、分档、盘刀、极性都不受几何影响）；几何只作用在
      // **算完之后**的交付掩码上 —— 这条是今晚量出来的：把重排放到输入侧（模型吃裁好的图），
      // 双线性会让 xbox 换支成一块黑饼、最近邻也会动到 4 张的判据；放到输出侧，14 闸结构上不可能红。
      // ── 几何搬到**判据之前**（主人 2026-10-15：「取色判断得按调整后的图标来，不然到时候出错了」）──
      //   交出去那张 = 判过那张，不再事后 resample。上一版（甲）判据吃原件、只把成品搬位置，
      //   于是**判据台量的那张与屏幕上的那张不是同一张**：倍数逐张相同也能差 1.5 倍，
      //   2026-10-15 为这件事返工了三轮，最后是靠量交付掩码的外接框才抓出来。
      // ⚠ 目标矩形**分两档** —— 这一条就是昨晚乙翻车的地方：
      //   · 自适应：桌面画这一层按 1.5 倍铺进去、只画中间那 2/3 → 输入映射到**可见区**；
      //   · 非自适应：判据必须吃**满幅口径**。先缩进可见区会在四周留一圈透明边，把一张满幅
      //     不透明的老式图标量成"透明底 + 图形" —— 那正是 2026-09-24「非自适应图标全部变成
      //     色块」的病根（见 [rasterize] 上面那段）。所以这一档映射到整张画布，位置留到掩码
      //     算完之后由 [intoVisibleArea] 收。**各收一次，不许收两遍。**
      // ⚠ `plateLayer` 仍按**原始像素**判：它问的是"盘在哪一层"，那是图层结构的事，不因放大
      //   而变；而 35% 那条线是在原始取景下摊过分母定的（上面那段），换取景再比 = 拿没标定的尺量。
      // ── 纯色盘：前景自己用到了盘色 → 先把盘铺进前景，再交模型（2026-10-23 Pinduoduo 白心）──
      //   判据与量过的波及面见 [bgMarks]；这一遍只在**冷路径**上跑（命中已在上面返回）。
      //   `plateLayer` 仍按原始 `pixels` 判：它问的是"盘在不在这层里"，与铺底无关。
      val bgOn = if (adaptive && under == null && bgColor != null) bgMarks(pixels, bgColor) else 0
      val srcPx = if (bgOn > 0) overBg(pixels, bgColor!!) else pixels
      val inset = visibleInset(edge)
      val visT = edge - 2 * inset
      val tOff = if (adaptive) inset else 0
      val tSide = if (adaptive) visT else edge
      // `win` 是 lazy（命中路径不付钱），取到本地才能做 smart cast。
      val winLocal = win
      val pxIn = if (winLocal == null) srcPx
        else IconCropWindow.reframe(srcPx, edge, edge, winLocal, tOff, tOff, tSide, tSide)
      // `under` 必须一起搬，否则模型看到的合成图与它背后那张层底对不上（真机上整张图标都动了）。
      val underIn = if (winLocal == null || underPx == null) underPx
        else IconCropWindow.reframe(underPx, edge, edge, winLocal, tOff, tOff, tSide, tSide)
      // 裁圆把这张判成"裁不动、整幅缩进圆里、还剩一半以上" → 那块大色块是**图案**，
      // 不是板。极性必须与彩色同意的档位一致，否则同一个模块对同一张图给两个答案。
      // 🔴 **读的是 `winColour`（彩色那一本账），不是 `winMeasured`（2026-10-21，两本账合成一本）**：
      //   这条 `50` 是在彩色那行 `裁了只剩` 上标定的（wenku8 真机 55%），却一直由判据那一本去比 ——
      //   同一张图两本账装机现量能差 **3 个点**（wenku8 55→52、`com.android.contacts` 彩色 50 / 判据 49），
      //   **只差 1 个点就会两边各给一个答案**：彩色把那块当图案缩进圆里保住、染色把它当底摘掉。
      //   "一个模块两套主张"正是这条判据当初要消灭的东西，而它自己留了最后一套。见 [colourWindow]。
      // ⚠ `winMeasured` 没有退役：它管的是**几何**（搬不搬、`几何=` 那一截），那是另一个问题，口径一字未动。
      // ⚠ **`!spiky` 这一道不能省**（2026-10-16，Kodi「还是整块上色了」）：本条原话是
      //   「彩色那支判这张是"裁不动、整幅缩进圆里、**裁了只剩这么点**" —— 这块大色块是**要被整块保住的
      //   图案**」，它依据的是 **keep 入口**（圆会咬掉一大块形状）。`plate` 档现在有**两条入口**，
      //   另一条 `spiky`（"圆盘比外框小太多"）说的是**几何**、对像素归属一个主张都没有 —— 极性跟着它走
      //   就成了"这张图里没有底"，整块底按覆盖率拿到半浓的墨。
      //   真机 `org.xbmc.kodi`：`rCover/外框内切圆 = 0.72` → spiky 入口、`keepPct=72` 过了 50 那条线，
      //   而它的参照那一支**本来认得那块灰白菱形盘**（`盘实=34485/30358 底占=0.76`）—— 屏上却是整块上墨。
      //   ⚠ **线一根都没挪**：keep 入口进这一档的图标 `keepPct` 必然 < 60（`IconCropWindow.KEEP_RATIO`），
      //   真机样本是 wenku8 55 / contacts 49 / mipush 32 / easytier 0 —— 改前改后**都只选中 wenku8**。
      //   波及面在离线台子上摊开量过（`_harness/WinReproKt.kt`，739 张 × 7 份语料）：
      //   `plate && keepPct>=50` 一共 **6 行 / 2 个图标**（Bard、LocalSend），其中 spiky 的就是这 2 个 ——
      //   · `org.localsend.localsend_app`：新旧掩码**逐格相同**（它两条口径都落到 H3 同一个产物）；
      //   · `com.google.android.apps.bard`：改后整颗四角星干净上墨，改前星里多一块深灰楔形伪影（看图判的）。
      // ── **极性读的是彩色那一本账**（2026-10-21，两本账合成一本）──
      // 画这一张的成本放在**缓存未命中之后**：命中那条路（真机一次重启 1594 次 bind / 209 种内容）
      // 已经交出现成掩码，不该再付一次绘制。量具口径见 [colourWindow]。
      val winColour = if (circle) colourWindow(source, fgOnly) else null
      // 取数口在 [IconCropWindow.polarityOf]（不在这里写式子）：那个文件在台子的编译清单里，
      // 这里没有 —— 写在这里，第 19 闸就退回"量具看不见生产"那一族（第十六闸的同一课）。
      val plateSubject = IconCropWindow.polarityOf(winColour, winMeasured)
      // 【主形】按包开闸（名单与"为什么不能全局"见 [MonochromeMask.TRIM_PKGS]）。
      //   `label` 的第一段是 `IconNames.of(source)`，两种形式都要认（`包名/类型/入口名` 与
      //   `pm:包名`）—— 只配前一种的话，快捷方式那一批永远开不了火，见 [IconNames.matchesPkg]。
      val trimOn = MonochromeMask.TRIM_PKGS.any { IconNames.matchesPkg(label, it) }
      val out = MonochromeMask.build(pxIn, edge, edge, plateLayer, underIn, plateSubject,
        trimSubject = trimOn)
      // H0 的射程跟着记：见 [noteSynthesized] 那条汇总为什么带它。
      noteH0(out.h0why)
      noteVeto(out.veto)
      // 指纹打在判据行上：解不出包名的那一批（快捷方式）在日志里都叫 `?`，没有这个数
      // 就对不上"哪一行判据是哪一个带回来的 PNG"。见 [MonoDump.sigOf]。
      val sig = MonoDump.sigOf(pxIn, edge, edge)
      val id = noteBranch(edge, sig, label, out, win, key, winMeasured, winColour, plateSubject)
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
        // ⚠ **这条通道看不见甲那一刀**：带的是 `out.mask`，而搬位置发生在它**之后**。两版装机对
        //   dump 里的 PNG 逐张相同（2026-10-15 量过：21/21 same），而屏上 me.ele 已经大了 1.5 倍。
        //   要核对"搬完之后那张"，只有 [noteDiagnosis] 那 8 行 ASCII 缩略图（`_harness/_thumbab.cjs`）。
        // ⚠ 铺过底的那一族要带**合成图**：`pxIn` 与它才是同一张；带原始前景复放出来的是铺底前那张。
        val src = if (srcPx === pixels) raster else Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
          .also { it.setPixels(srcPx, 0, edge, 0, 0, edge, edge) }
        try {
          MonoDump.maybe(id, label, src, pxIn, out.mask, underIn, plateLayer, edge, edge,
            "${label.substringBefore(' ')}_${sig}${if (variant.isEmpty()) "" else "_$variant"} | ${out.report}${geoTag(win, winMeasured)}", isWatched)
        } finally {
          if (src !== raster) src.recycle()
        }
      }
      // 可疑的那几张把**原像素**带回来（PNG → base64 → 分片进日志），判据台原样重放。
      // 没有这条通道，"不够细致"永远只能靠猜：他报的应用发的是 vector / webp，台子解不出。
      // ⚠ 但只按 `suspicious` 会把他**点名**的那几张漏掉：一块灰框在形状体检里是"规整、连通、
      // 面积合适"的好形状，一个可疑指标都不触发 —— 而它恰恰是要看的东西。所以点名的必带。
      // 带不带还得过预算那一关，见 [MonoDump.maybe] 里 `WATCH_RESERVE_KB` 那一段。
      if (MonoDump.suspicious(out) || isWatched) dump()
      val ramp = out.mask
      if (ramp == null) {
        // 判据不成立 = 这张以后再问还是一样，记个负缓存（只进内存，见 [MonoCache]）。
        MonoCache.store(key, edge, null)
        raster.recycle()
        return@runCatching null
      }
      // 掩码这一步**不再搬位置**：输入已经是裁圆调好的那一张，判出来的墨天然就在那个尺寸上。
      // （这里原来挂着甲那一刀 —— 判完再 resample 交付掩码。2026-10-15 撤，理由见上面那段。）
      val alpha = toAlphaMask(ramp, edge, edge)
      // 非自适应图标的内容是满幅的，而它在图层 bounds 里只该占中间那 2/3（可见区），
      // 所以掩码算完**之后**才缩进 —— 顺序反了就会踩 [rasterize] 上面那个坑。
      val placed = if (adaptive) alpha else intoVisibleArea(alpha, edge)
      // ⚠ 这一趟多花的钱（判据台 `_harness/CostKt.kt`，62 张真机语料 @edge=384，取七遍最快一趟）：
      //   `of` 中位 **0.54 ms**（**每张都跑**，因为它内部先降到 128 才扫，与画布多大无关 —— 与真机
      //   彩色那行 `量一张` 的 0.60 ms 同一档）；`reframe` 中位 **2.24 ms**，只有开了窗口的 **13/62**
      //   张跑到。分母是同一条语料上的合成主路（中位 332 ms）→ **整趟加的成本 0.8%，每张必付的那半 0.2%**。
      //   ⚠ 绝对毫秒是 PC JVM 的数，手机上另有倍数（`intoVisibleArea` 是 Canvas 调用，台子量不到）——
      //   所以这条只当**比值**用，别拿 0.54 ms 当真机读数。
      noteDiagnosis(edge, label, raster, placed)
      raster.recycle()
      if (placed !== alpha) alpha.recycle()
      // 存的是**交出去那一张**（几何与位置都在里面了），不是模型刚算完的那张 ——
      // 命中时返回的必须与屏幕上的是同一个东西，否则「判据吃的那张」与「屏上那张」又分家。
      // `label` 只进归属索引（[MonoCache.noteOwner]），不参与键、不参与判定。
      MonoCache.store(key, edge, placed, label)
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
   *
   * `state=` 打的是**这张 drawable 的 `ConstantState` 身份**（不是实例身份 —— 实例每一次 bind 都是新的，
   * 那是 [cache] 那一层已经量明的事实）。这一位是为下一刀摆的读数：桌面在两次 bind 之间到底
   * 共不共享 state，决定「把键换成 state、在取像素之前就命中」那一刀有没有东西可省
   * （现在每次命中仍付一次光栅化 + 哈希）。**两个方向都要看**：
   *   · 同一个内容键下 state 只有几个 → 共享成立，那一刀能省掉光栅化；
   *   · **同一个 state 会不会对出两个不同的内容键** → 那就是那一刀的否决票（共享了 state 而像素不同，
   *     按 state 命中就是交出别的图标的掩码）。`mutate()`、加徽标、桌面的 `BitmapInfo` 后处理都会造成这种分叉。
   * ⚠ 挂在 [describe] 而不是另起一行：本仓的规矩是每个图标一行；`getConstantState()` 对个别
   *   drawable 可能抛或返回 null，所以包起来、解不出打 `-`（一条读数不该把宿主的加载线程带崩）。
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
      // `state=` = 这张 drawable 的 ConstantState 身份。它今天回答过一个值不值钱的问题
      // （"按 state 建一层缓存能省掉光栅化吗" —— 真机量到 1043 次请求对 1043 个 state，答案是不能）。
      // ⚠ **本模块自己的 wrapper 不适用这一位**：`CircleIconDrawable.getConstantState()` 每次调用
      //   **新建一个匿名 ConstantState**，所以在包装路径上它**永远"独一无二"** —— 那种 1:1 不是"桌面不复用"，
      //   是这个 getter 自己每次新建。今天这批读数恰好一张 wrapper 都没有
      //   （`AdaptiveIconDrawable` 607 / `BitmapDrawable` 405 / `VectorDrawable` 32 / `LayerDrawable` 10 / `GradientDrawable` 1），
      //   所以那条否决站得住；但**换一台桌面再读同一位，先看类名**。这一位要打就把自己标出来。
      val vacuous = source is CircleIconDrawable
      val st = if (vacuous) "-wrap" else runCatching { source.constantState }
        .getOrNull()?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "-"
      append(" state=").append(st)
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
   * 诊断：这一张**没重算**，是从缓存里拿出来的（[MonoCache]）。
   *
   * 两件事必须留痕：① 它是个提前 return，本项目吃过两次「提前 return 静默失效」的亏；
   * ② 它带**同一张掩码的缩略图**（走 [noteDiagnosis]），这样"缓存交的那张"与"现算的那张"
   * 能在日志里逐张对上 —— 只看命中数不能证明缓存没错，只能证明它被走到了。
   *
   * ⚠ **负缓存也要留痕**（原来这一条只在 `mask != null` 时打，等于把"放弃合成"这条 telemetry
   * 从每 bind 一次改成每内容一次 —— 真机上一趟重启从 148 行塌成 1 行，看上去像"这台桌面根本不放弃合成"）。
   * ⚠ **命中不能占冷启动那 12 个 [HEAVY_DIAG_LIMIT] 名额**：热重启 1880 次命中会把预算吃干，
   * 于是**真正现算的那几张反而没有缩略图** —— 而那正是要核对的东西。两条路各一套名额。
   */
  private fun noteCached(edge: Int, label: String, hit: MonoCache.Entry, input: Bitmap, key: Long) {
    val n = diagnosed.incrementAndGet()
    val outcome = if (hit.mask == null) "判据不成立（负缓存，未重算）" else "直接交缓存里那张（未重算）"
    Log.d(TAG, "Mono: diag#$n edge=$edge 键=${MonoKey.hex(key)} $label 缓存=${hit.from} → $outcome")
    // 名额由 [noteDiagnosis] 自己按路判（这里再判一次会把同一个计数器加两遍，冷那条路就被挤掉更多）
    noteDiagnosis(edge, label, input, hit.mask, cache = true)
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
    key: Long, measured: IconCropWindow.Window?, colour: IconCropWindow.Window?, subject: Boolean,
  ): Int {
    val n = diagnosed.incrementAndGet()
    Log.d(TAG, "Mono: diag#$n edge=$edge sig=$sig 键=${MonoKey.hex(key)} $label ${out.report} → " +
      (if (out.mask != null) "已交出掩码" else "判据不成立，放弃合成") + geoTag(win, measured) +
      polarityTag(colour, subject))
    return n
  }

  /**
   * 极性那一档**自己吃的那两个数**，打在判据行末尾：`接管=开 判剩52% 入口=keep`。
   *
   * ⚠ 字段名叫 `接管=` 不叫 `极性=`：`README.md` 里 `极性=翻/不翻` 说的是另一件事
   *   （`MonochromeMask.polarity()` 那套翻不翻色，2026-10-16 全量检查时它**业务侧已零调用者**，
   *   装机永远打不出那一行）。同名两义会让人把"盘被整块保住"读成"这张要翻色"。
   *
   * 🔴 **`判剩=` 现在就是彩色那行 `裁了只剩` 的同一个数**（2026-10-21，两本账合成一本）：
   *   这一档以前量 `pixels`（喂进模型那张），而 `PLATE_SUBJECT_MIN=50` 是在彩色那本账上标定的，
   *   两本账装机现量差 3 个点（wenku8 55→52、contacts 50→49）—— 只差 1 个点就两边各给一个答案。
   *   取数改到 [colourWindow] 之后，**这两行必须逐包相等**；不等就是量具坏了，
   *   装机后由 `node _harness/_twobook.cjs <logcat>` 一条命令判（不等就非零退出）。
   *   ⚠ **旧日志的这一格与新日志不可对表**（同一个字段名换过一本账，本仓为这件事点过四次名）。
   * 为什么仍打在**这一行**而不是只看彩色那行：这一行还带 `入口=`（plate 档两条入口只有这里分得开），
   *   而"某张有没有被接管"必须和它的判据行在同一条里读出来 —— 跨两行 join 就是本仓吃过的那次串台。
   */
  private fun polarityTag(w: IconCropWindow.Window?, subject: Boolean): String {
    if (w == null || !w.plate) return if (subject) " 接管=开" else ""
    val byKeep = w.byKeep   // 未取整的分支原值，别拿 keepPct 反推（见 Window.byKeep）
    val entry = if (w.spiky && byKeep) "keep+spiky" else if (w.spiky) "spiky" else "keep"
    return " 接管=${if (subject) "开" else "关"} 判剩${w.keepPct}% 入口=$entry"
  }

  /**
   * 判据行末尾那截几何标记：`几何=缩|放<倍数>× 档=plate|inscribe` —— **字跟着数走，档位单独给**；
   * `几何=量到没搬`（`fgOnly` 或倍数超 `GEO_ZOOM_CAP`）/ `几何=满幅`（真没量到窗口）。
   * ⚠ 以前只有"搬了"和"满幅"两档，于是 `fgOnly` 那张也被叫作"满幅" —— 同一行可以同时打出
   *   `几何=满幅` 和 `判剩55%`，自相矛盾（2026-10-16 全量检查抓的）。
   * **这一趟是搬输出**：这个数只说明"算好的墨被摆到了多大"，判据吃的是搬之前那一张，所以它变了不动任何一档判断。
   *
   * 为什么挂在**这一行**而不是另起一行：图标加载是热路径，本仓的规矩是"每个图标一行"。
   * 装机后要判"染色那支有没有跟着裁圆走"，grep 这一个字段就够了。
   */
  private fun geoTag(win: IconCropWindow.Window?, measured: IconCropWindow.Window?): String = when {
    // 字**跟着数走**（缩 <1< 放），档位交给后面的 `档=`。以前反过来：字跟着档位走，
    //   于是真机 9 行打成 `缩1.00×`～`缩1.17×`（"缩"却在放大）。
    //   ⚠ 换掉的理由记在 devlog 2026-10-16：我原本拿"上百条 devlog 引用"当借口不改，
    //     实际数下来只有 **11 处**（3 个文件）—— 那是我没数就说的话，不该当成成本。
    win != null -> " 几何=${if (win.zoom < 1f) "缩" else "放"}${"%.2f".format(win.zoom)}×" +
      " 档=${if (win.plate) "plate" else "inscribe"}"
    // 量到了窗口却整张不搬 = `fgOnly`（只给前景层，那张盘在背景层）或倍数超 `GEO_ZOOM_CAP`。
    //   以前这一格也印 `满幅`，于是同一行能打成 `几何=满幅 判剩55%` —— "满幅"和"只剩 55%"自相矛盾。
    measured != null -> " 几何=量到没搬"
    // 剩下才是真·满幅（或裁圆压根关着 —— 那时无窗口可量）。
    else -> " 几何=满幅"
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
  private fun noteDiagnosis(edge: Int, label: String, input: Bitmap, mask: Bitmap?, cache: Boolean = false) {
    // ⚠ **名额按路分开**（装机量出来的：两条路共用一套名额这件事本身就是错的，谁先到谁吃干）。
    //   冷那 12 条是要核对的东西；命中那 8 条是"缓存交的那张与现算的那张对得上"的证据。
    val budget = if (cache) hitDiag else heavyDiag
    if (budget.incrementAndGet() > if (cache) HIT_DIAG_LIMIT else HEAVY_DIAG_LIMIT) return
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

    // 缩略图的名额**也按路分**：不然热重启那几秒里命中先把 8 张占满，**现算那两张一张都不出** ——
    // 而"缓存交的那张对不对"恰恰要拿现算那张当参照。
    val dumped = (if (cache) asciiHitDumped else asciiDumped).incrementAndGet()
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

  // `PLATE_SUBJECT_MIN` 与它那条算式已搬去 `IconCropWindow`（2026-10-16）——
  //   判据台编译得动那个文件、编译不动这个，留在原地就等于让闸只能抄一份、测一份镜像。
  //   线摆在 50 的依据与"下面第一张只差 3 个点"那笔账，记在那边。

  // `GEO_ZOOM_CAP`（1.82）与"量窗口 + 卡上限"那条链已搬去 `IconCropWindow.measure()`（2026-10-17）——
  //   理由同 `PLATE_SUBJECT_MIN`：判据台编得动那个文件、编不动这个，而 1.82 这个数
  //   已经在 `PlateSubKt` 与 `ReframeDiffKt` 里各抄了一份。
  //   线摆在 1.75/1.90 中间那笔账与"别为了压线那张挪到 1.85"那条，记在那边。

  /** 重诊断（[noteDiagnosis]：全画布直方图 + 掩码连通块 + 缩略图）每进程放行几条。 */
  private const val HEAVY_DIAG_LIMIT = 12

  private val heavyDiag = AtomicInteger(0)

  /**
   * **缓存命中那条路单独一套名额**（[noteCached]）。
   *
   * 不能与 [heavyDiag] 共用：真机热重启一趟有 1880 次命中，共用会把 12 条名额在几秒内吃干，
   * 于是**现算的那几张反而一张缩略图都没有** —— 而现算那几张才是要核对的东西。
   * 8 条足够对上一眼（一张 40 行，8 张 = 320 行，再多就淹 logcat）。
   */
  private const val HIT_DIAG_LIMIT = 8

  private val hitDiag = AtomicInteger(0)



  /** ASCII 缩略图的行列数（降采样后的画布大小）与最多打几张。 */
  private const val ASCII_COLS = 40

  /**
   * 缩略图上限。40 行一张 —— 在 [HEAVY_DIAG_LIMIT] 之上再收一道，免得放行那 12 条时
   * 一次刷出 480 行把 logcat 淹了（这台机器的 main buffer 一两分钟就滚掉）。
   */
  private const val ASCII_DUMP_LIMIT = 8

  private val asciiDumped = AtomicInteger(0)

  /** 命中那条路的缩略图计数（与 [asciiDumped] 分开，理由见 [noteDiagnosis] 里那段）。 */
  private val asciiHitDumped = AtomicInteger(0)

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
  private fun rasterize(target: Drawable, edge: Int): Bitmap? = rasterize(target, edge, edge)

  /** 同 [rasterize]，但画布是 `w×h`（彩色那本账的格子不一定是正方形，见 [IconCropWindow.scanSize]）。 */
  private fun rasterize(target: Drawable, w: Int, h: Int): Bitmap? {
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
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      target.setBounds(0, 0, w, h)
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
   * **彩色那一本账**：把这张图标按 [MaskedDrawable] 的画法与格子量一遍，取它的窗口。
   *
   * 与判据那一本（[IconCropWindow.measure] 吃 `pixels`）的三处不同，全都是为了**逐字对上彩色那行**：
   *  · **画的是 `source`**（整张图标：盘 + 前景，`AdaptiveIconDrawable.draw()` 的视口画法），
   *    不是喂进模型那一张（前景层 / 平铺层）；
   *  · **格子是 [IconCropWindow.scanSize]**（长边 128），不是 `edge`(384) 再降采样 ——
   *    同一张 drawable 画到 128 与画到 384 再 nearest 回 128 不是一张图，装机现量差 3 个点；
   *  · **走 [IconCropWindow.of] 而不是 [IconCropWindow.measure]**：`GEO_ZOOM_CAP` 管的是"染色的墨搬不搬"，
   *    彩色那本账从来不卡它（`CircleIconDrawable` 也调 `of`），卡了就又变成两本账。
   *
   * 同一套格子还有个结构上的好处：`IconCropWindow` 那层按**格子内容哈希**缓存窗口，
   * 彩色已经量过的图标在这里直接命中、拿回**同一个 `Window` 对象** —— 两本账不再是两个数。
   *
   * ⚠ **只给前景层那一支（`fgOnly`）不画**：背景是纯色盘，盘把整张画布铺满 → 彩色那本账**恒判满幅**
   *   （`TwoAcctKt` 在 132 张上现量：这一支一张窗口都拿不到）。恒判 = 不必画，画它是白花一次绘制。
   * ⚠ 异常**不许冒到 [render] 的 `runCatching`**：那会把整趟合成吞成 null，屏幕上=图标消失。
   *   量不到就交 null（= 不接管极性 = 这次改动之前的默认行为）。
   * ⚠ **残余的一处不完全同格**（诚实记一笔）：彩色那支在"固有尺寸不知道"那一档是按**当时画布**
   *   （`width`/`height`）算格子的，而这里没有画布 —— 传 `1 to 1` 走 canonical，得到正方形 128²。
   *   画布是方的（桌面上就是方的）时两边逐字相同；万一哪天出现**非方画布**的自适应槽位，
   *   那一张的格子会差在长短边上，装机对表（`_twobook.cjs`）会当场报出来，不会静默。
   */
  private fun colourWindow(source: Drawable, fgOnly: Boolean): IconCropWindow.Window? {
    if (fgOnly) return null
    val iw = source.intrinsicWidth
    val ih = source.intrinsicHeight
    // 固有尺寸不知道的（自适应）按彩色那支的做法撑到长边 128（`1 to 1` 走 canonical 那一路）。
    val (w, h) = if (iw > 0 && ih > 0) IconCropWindow.scanSize(iw, ih, false)
    else IconCropWindow.scanSize(1, 1, true)
    val bmp = runCatching { rasterize(source, w, h) }.getOrNull() ?: return null
    return try {
      val px = IntArray(w * h)
      bmp.getPixels(px, 0, w, 0, 0, w, h)
      runCatching { IconCropWindow.of(px, w, h) }.getOrNull()
    } catch (t: Throwable) {
      null
    } finally {
      bmp.recycle()
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
    // ⚠ **分子只数实心格，分母就必须是实心格**（主人 2026-10-19「灰底不行」查到的根）。
    //   原来这里除的是 `px.size`（整张画布）：前景层里那 58% 的空气被算进分母，飞鸟那张
    //   近白实心盘占**不透明格 83.0%**、占**画布却只剩 29.3%** —— 于是够不到 35% 那条线，
    //   装机就对模型说"这层里不许有底"，那块白盘没有归属，被整片交成淡墨 = 屏幕上一层浅灰膜
    //   （`底占=0.03 盘实=0/50208 淡=0.86`）。与 2026-10-11 spotube 同族，只是这次是**分母**把它压在线下。
    //   换成实心分母后飞鸟 29.3→83.0，翻到"这层里有底"那一侧；语料里 25 张走这条路的
    //   前景层都是**整幅不透明**（实心=画布），这一改对它们**一个百分点都不动**。
    return (fam.maxByOrNull { it.value }?.value ?: 0) * 100 / solid
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
