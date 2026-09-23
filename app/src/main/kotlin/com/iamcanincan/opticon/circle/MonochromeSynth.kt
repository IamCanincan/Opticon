package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
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
 * ## 剪影算法与通知侧共用，不另写一套
 * 剪影那一步用的是 [IconBitmap.monochrome] —— 和通知小图标黑白模式
 * （`NotificationIconPatch.launcherIconMonochrome` / `forceMonochrome`）**同一个函数**：
 * 由它用 **Otsu 自动阈值**二值化，并用**连通性**判断哪一侧才是图形
 * （背景通常与图标外部相连、图形是被包住的内部块）—— 这比任何固定阈值都稳，
 * 也省得同一个问题在仓库里有两份实现、修一处漏一处。
 *
 * 取前景层那一步（「透明底 + 图形」那一层，整张光栅化是不透明色块、压不出剪影）
 * 没有直接用 [IconBitmap.foregroundOf]，而是在这里自己写了 [rasterizeForeground]：
 * 它会**还原原 drawable 的 bounds**，并且**按自适应 / 非自适应分开给 bounds**
 * （非自适应图标的内容是满幅的，要缩进可见区）—— 两条理由都写在那上面。
 * 通知侧是「拿一个图标画一次就扔」、也不需要区分这两种口径，所以那边保持原样。
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
      val label = describe(source)
      val foreground = rasterizeForeground(source, edge)
      if (foreground == null) {
        noteDiagnosis(edge, label, null, null)
        return@runCatching null
      }
      val silhouette = IconBitmap.monochrome(foreground, preserveAlpha = true)
      val mask = silhouette?.let { toAlphaMask(it) }
      noteDiagnosis(edge, label, foreground, mask)
      foreground.recycle()
      silhouette?.recycle()
      mask
    }.getOrNull()
  }

  /**
   * 诊断用：这个图标到底是哪种 drawable、前景层多大。
   *
   * 光看 alpha 直方图认不出源 —— 「66% 落在 1~127」既可能是柔光、也可能是
   * 半透明底板、还可能是低分辨率位图被放大。把类名和固有尺寸一起打出来，
   * 才能把数字对上号（`BitmapDrawable@108x108` 和 `VectorDrawable@-1x-1` 是两回事）。
   */
  private fun describe(source: Drawable): String {
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
      append(" bounds=")
      append(source.bounds.width())
      append("x")
      append(source.bounds.height())
    }
  }

  /**
   * 诊断：把「这一张掩码怎么来的、长什么样」打进日志。只放行最前面几张（图标加载是热路径）。
   *
   * ## 为什么要有它
   * 桌面主题图标这块**我看不见** —— 我的图像通道是 filtered 的，用户也明确不要我截图。
   * 而「形状不对」有好几种**成因完全不同**的可能（走了 alpha 分支但输入本身是糊的 /
   * 走了 binarize 把彩色图形按亮度切碎 / 掩码没问题但下游画坏），靠推演猜已经错过一次，
   * 所以这里把两样东西直接打出来：
   * 1. **判定分支的数值**：`a0/a1/a2/a255` 是输入 alpha 的四段直方图，
   *    `useAlpha` 是按 [IconBitmap.monochrome] 的判据（`透明 > 5% && 不透明 < 70%`）复算的 ——
   *    一眼能看出它到底走了哪条分支。
   * 2. **掩码本身的 ASCII 缩略图**：`#` 是不透明、`.` 是透明。把 384² 降采样成 40×40
   *    打进日志，于是「是一个完整字形」还是「一堆碎片」在日志里就能直接看出来。
   *    输入 alpha 还会在 [INPUT_DUMPS] 那几档阈值下各打一张 —— 用来回答
   *    「图形到底落在哪一档 alpha 上」，也就是该不该把阈值定在 `MIN_SHAPE_ALPHA`。
   *
   * 形状统计：`out` 掩码里 ≥128 的像素占比；`fill` 是**按 alpha 加权的等效覆盖率**
   * （Σalpha / 255 / 总面积）—— 图标看起来「被填满多少」看的是这个，
   * `out` 小但 `fill` 大就说明掩码里塞了一大片淡色（色块的典型特征）。
   * `cc` 四邻域连通块数、`top` 最大一块占输出像素的比例 —— 完整字形 cc 很小、top 接近 1；
   * 碎成一片时 cc 很大、top 很低。
   *
   * ⚠ 只是诊断，不参与任何判断。发版前应当把它连同 [DIAG_LIMIT] 一起降级/移除。
   */
  private fun noteDiagnosis(edge: Int, label: String, input: Bitmap?, mask: Bitmap?) {
    val n = diagnosed.incrementAndGet()
    if (n > DIAG_LIMIT) return
    if (input == null) {
      Log.d(TAG, "Mono: diag#$n edge=$edge $label 前景层取不到，直接放弃")
      return
    }
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
    val transparent = a0 + a1
    val useAlpha = transparent > total / 20 && (total - transparent).toFloat() / total < 0.7f

    if (mask == null) {
      Log.d(TAG, "Mono: diag#$n edge=$edge $label in=${w}x$h " +
        "a0=${pct(a0, total)} a1=${pct(a1, total)} a2=${pct(a2, total)} a255=${pct(a255, total)} " +
        "useAlpha=$useAlpha -> monochrome() 返回 null")
      dumpInput(n, pixels, w, h)
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
      "useAlpha=$useAlpha out=${pct(opaque, total)} fill=${pct((weight / 255).toInt(), total)} " +
      "cc=$components top=${pct(largest, opaque)}")

    dumpInput(n, pixels, w, h)
    dumpAscii(n, "mask", null, mw, mh, bytes, MASK_DUMP_THRESHOLD)
  }

  /**
   * 输入 alpha 在 [INPUT_DUMPS] 那几档阈值下各打一张 ASCII。
   *
   * 存在的理由：`monochrome()` 把判据定在 `MIN_SHAPE_ALPHA`（128），而**这个值合不合适
   * 只有看图才知道** —— 图形整体偏淡时 128 会把形状切掉一大半（甚至切空、退回 null），
   * 整体偏浓时又会把柔光一起收进来。几张缩略图并排一看就知道该往哪边挪。
   */
  private fun dumpInput(n: Int, pixels: IntArray, w: Int, h: Int) {
    for (threshold in INPUT_DUMPS) {
      dumpAscii(n, "in>=$threshold", pixels, w, h, null, threshold)
    }
  }

  /**
   * 把 alpha（[pixels] 给 alpha 通道）或 ALPHA_8 掩码（[maskBytes] 给原始字节）打成 40 列的 ASCII。
   *
   * 每个格子取 [threshold] 当「算不算图形」的界，和 `monochrome()` 内部的判据同口径 ——
   * 缩略图如果自己用一套阈值，就和真产出的掩码对不上了，那还不如不打。
   */
  private fun dumpAscii(
    n: Int,
    what: String,
    pixels: IntArray?,
    w: Int,
    h: Int,
    maskBytes: ByteArray?,
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
            val a = if (maskBytes != null) (maskBytes[y * w + x].toInt() and 0xff)
            else Color.alpha(pixels!![y * w + x])
            if (a >= threshold) hits++
          }
        }
        line.append(if (cells > 0 && hits * 2 >= cells) '#' else '.')
      }
      Log.d(TAG, "Mono: diag#$n $what $ry|$line")
    }
  }

  private val diagnosed = AtomicInteger(0)

  /** 放行几张图标的诊断。每张要打 4 份 40×40 缩略图，别开大 —— logcat 环形缓冲只有 256 KiB。 */
  private const val DIAG_LIMIT = 2

  private const val ASCII_COLS = 40

  /** 输入 alpha 各打一张缩略图的候选阈值，见 [dumpInput]。 */
  private val INPUT_DUMPS = intArrayOf(64, 128, 192)

  /** 掩码缩略图的阈值，与 `IconBitmap.monochrome` 里的判据同口径。 */
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

  /**
   * 把图标光栅化成 `edge × edge` 的位图。`edge` 的坐标系是**图层 bounds**
   * （= 1.5 × 图标尺寸，也就是整张 108dp 画布）—— 为什么不是可见区，见类文档的坐标系那一节。
   *
   * 两种图标给 bounds 的方式**不同**，因为它们的「内容本来占多大」就不同：
   *
   * - **自适应图标**：取它的**前景层**（「透明底 + 图形」那一层；整张光栅化是不透明色块，
   *   压不出剪影），`setBounds(0, 0, edge, edge)`。前景层的坐标系本来就是整张 108dp 画布，
   *   图形天然落在中间 61%。
   * - **非自适应图标**（老式满幅 PNG、图标包给的整张图）：内容铺满整个 bounds，
   *   所以要把它缩到**可见区**（画布中间 2/3）—— 与裁圆那边 `MaskedDrawable` 的
   *   cover 填充同一口径。直接按 `(0, 0, edge, edge)` 画的话，可见区里只剩这张图的
   *   中间 2/3，看起来就是「放大一圈 + 四周被切」。
   *
   * ⚠ 可见区的 inset 是 `edge × f / (1 + 2f)`（f = [AdaptiveIconDrawable.getExtraInsetFraction]，
   * 即 edge/6），**不是** `edge × f`。这两个值差 1.5 倍，写错就会让非自适应图标
   * 在可见区里只占 2/3、四周空一圈。推导：可见区尺寸 `V = edge / (1 + 2f)`，
   * 两侧各让出 `(edge − V) / 2`。`CircleIconDrawable.ViewportDrawable` 用的是同一个式子
   * （`frac = (1 − VIEW_PORT_SCALE) / 2`），两处口径一致。
   *
   * 与 [IconBitmap.foregroundOf] 是同一件事，只有一处不同：**这里会把原 drawable 的
   * bounds 原样还原**，理由见下。
   *
   * ⚠ 为什么必须还原：自适应图标的前景层是**应用那份图标对象自己持有的**
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
  private fun rasterizeForeground(source: Drawable, edge: Int): Bitmap? {
    val adaptive = source is AdaptiveIconDrawable
    val target = (if (adaptive) source.foreground else source) ?: return null
    val saved = Rect(target.bounds)
    return try {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bmp)
      if (adaptive) {
        target.setBounds(0, 0, edge, edge)
      } else {
        val inset = (edge * AdaptiveIconDrawable.getExtraInsetFraction() /
          (1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction())).toInt()
        target.setBounds(inset, inset, edge - inset, edge - inset)
      }
      target.draw(canvas)
      bmp
    } finally {
      target.bounds = saved
    }
  }

  /**
   * ARGB_8888（白色 + alpha）→ ALPHA_8。
   *
   * `monochrome()` 交出来的是 ARGB_8888，但桌面取色只读 alpha，
   * 所以留着三个颜色通道是白占内存（每张 147 KB → 36 KB @192px）。
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

/**
 * 单色层的载体：一张 ALPHA_8 掩码，画的时候由调用方（桌面的取色逻辑）上色。
 *
 * 不直接用 `BitmapDrawable` 是因为构造它要 `Resources`，而在被注入的进程里
 * 拿 Resources 要么拿不到、要么拿到的是宿主的 —— 自己画一个更稳，也少一层依赖。
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

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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
        Log.d(TAG, "Mono: layer bounds=${bounds.width()}x${bounds.height()} mask=${mask.width}")
      }
    }
    canvas.drawBitmap(mask, null, bounds, paint)
  }

  override fun setAlpha(alpha: Int) {
    paint.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
  }

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
