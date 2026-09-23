package com.iamcanincan.opticon.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.iamcanincan.opticon.runtime.MemberLookup

/**
 * 图标位图的取与造。
 *
 * 这里只做像素层面的事：把各种形态的来源（Drawable / 资源 id / Icon）画成位图，
 * 以及做圆形裁切、转单色、合成底板。是否该替换由上层决定。
 */
object IconBitmap {

    /**
     * 通知图标位图的边长。高密度屏上状态栏图标要 90px 往上，
     * 按这个尺寸出图，系统缩放时才不会糊成一团。
     */
    private const val ICON_EDGE = 96

    /** alpha 低于这个值就当透明，不算图形的一部分 —— 用来滤掉图标自带的半透明投影 */
    private const val MIN_SHAPE_ALPHA = 128

    /**
     * 不透明像素占比超过这个值，就认为整张是个实心块（圆形/方形实心图标）。
     * 这类图标的 alpha 只描述外轮廓，照搬 alpha 压出来就是一坨白块。
     */
    private const val SOLID_SHAPE_LIMIT = 0.7f

    /**
     * 压出来的形状占整张图的比例，低于下限（几乎全空）或高于上限（几乎全满）都算失败。
     *
     * 状态栏只认 alpha，交出去一张空图就是一个看不见的洞，交出去一张满图就是一个纯色块，
     * 两者都比原图标更糟。这种情况宁可放弃替换、保持原样。
     */
    private const val MIN_SHAPE_RATIO = 0.02f
    private const val MAX_SHAPE_RATIO = 0.85f

    /**
     * 亮度掩码（[luminanceMask]）要求不透明像素的亮度至少铺开这么多档，否则「归一化」
     * 只是把噪声放大：整块纯色（`max - min ≈ 0`）时没有任何可用的对比度，
     * 与其交出去一片灰雾，不如返回 null 让调用方放弃。
     */
    private const val MIN_LUMINANCE_SPAN = 24

    /**
     * [alphaSilhouette] 的判据：**内容外接框**里不透明像素占比到这个程度，
     * 就认定它是一整块底而不是「透明底 + 图形」。
     *
     * 0.92 这条线是「矩形 vs 形状」的分界 —— **实心圆只有 0.785**（π/4），
     * 带圆角的形状更低，所以够得着 0.92 的只有真正铺满的方底（满幅位图 / 照片 /
     * 纯色块）。取更松的值（比如 0.7）会把实心圆这类正常形状一起误伤。
     */
    private const val FULL_BOX_FILL = 0.92f

    /** 二值化时每个像素的归类，用于连通性判断 */
    private const val CLASS_TRANSPARENT = 0
    private const val CLASS_DARK = 1
    private const val CLASS_LIGHT = 2

    /**
     * 把 Drawable 画成指定边长的方形位图。
     *
     * 关键点：显式调用 setBounds 而不是依赖 intrinsicWidth/Height。
     * 自适应图标（AdaptiveIconDrawable）的固有尺寸常常是 -1，
     * 依赖它就会算出非法尺寸、整条替换链路静默失效。
     */
    fun rasterize(drawable: Drawable, size: Int = ICON_EDGE): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** TYPE_RESOURCE 类型的 Icon 需要用正确的 Resources 解码，不能走 Drawable 兜底 */
    fun fromResources(context: Context, resId: Int): Bitmap {
        val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
        return if (drawable != null) rasterize(drawable) else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * 裁掉四周的空白边，再把内容放大填满。
     *
     * 自适应图标光栅化之后，内容只落在画布中间约 61% 的区域（safe zone），
     * 外面一圈是空的；直接拿去当通知图标，缩到状态栏那点尺寸就又小又糊，
     * 根本认不出是哪个应用。这里先按非透明像素求出内容边界裁出来，
     * 再等比放大填满目标尺寸。
     */
    fun fill(source: Bitmap, size: Int = ICON_EDGE): Bitmap {
        val bounds = contentBounds(source) ?: return scaleTo(source, size)
        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height())
        return scaleTo(cropped, size)
    }

    private fun scaleTo(source: Bitmap, size: Int): Bitmap {
        return if (source.width == size && source.height == size) {
            source
        } else {
            Bitmap.createScaledBitmap(source, size, size, true)
        }
    }

    /**
     * 取桌面图标里真正代表图形的那一层。
     *
     * 自适应图标分成背景层和前景层：整张光栅化出来是一块不透明的彩色方块，
     * 拿去压单色就只剩一个白方块，什么信息都不剩。只有前景层是「透明底 + 图形」，
     * 用它的 alpha 才压得出有用的剪影。普通图标没有分层，直接用整张。
     */
    fun foregroundOf(drawable: Drawable, size: Int = ICON_EDGE): Bitmap {
        val source = if (drawable is AdaptiveIconDrawable) drawable.foreground else drawable
        return rasterize(source, size)
    }

    /**
     * 位图里是否还有可见像素。
     *
     * 全透明就等于什么都没画。状态栏只认 alpha，交一张空图出去会变成一个看不见的洞 ——
     * 比原来的图标更糟，所以生成图之后必须先过这一关。
     */
    fun hasContent(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (pixel in pixels) {
            if (Color.alpha(pixel) >= MIN_SHAPE_ALPHA) return true
        }
        return false
    }

    /**
     * **通知方案的第一支**（`grayscale` 保留 alpha 那一步）—— 透明底 + 图形的轮廓。
     *
     * 与 [monochrome] 的 `useAlpha` 支走**同一份实现**（[alphaShape]），
     * 唯一的差别是「透明像素占多少」在**哪张图上量**。返回 null 表示这张图的透明像素
     * 不构成背景（整张是满幅的图形 / 照片 / 纯色块），调用方应当改用 [luminanceMask]。
     *
     * ## 判据，以及为什么必须**先裁到内容外接框再量**
     * 判据只有一条：**内容外接框里有没有留下成片的透明**。
     *
     * ⚠ 关键在「**在哪张图上量**」。自适应图标的**前景层**四周自带安全区留白
     * （约 53% 的面积是透明的），带着那圈留白去量，一张**满幅不透明的整块底**也会被量成
     * 「透明 53% / 实心 47%」，正好落进「透明底 + 图形」的特征里 → 走 alpha →
     * 压出那块底的外轮廓 = 一整块色。真机实例：淘宝（前景是一张满幅不透明的彩色图）
     * 就是这么变成大色块的。
     *
     * 裁到内容外接框之后再量，同一张图是「框内填充 1.00」→ 判据正确地落到亮度那一支；
     * 而真正的透明底图形（图形笔画之间、四周都有透明）照旧走 alpha。
     * 于是「安全区留白」与「透明背景」这两件完全不同的事被分开 ——
     * **不需要为某一类图标单独开一条分支**。
     *
     * 通知侧对同一件事的表述是「**先 fill 再 monochrome** —— fill 裁掉的透明边正好是
     * 判断形状的凭据，反过来做会把整块图标压成一个实心圆」（见
     * `NotificationIconPatch.launcherIconMonochrome`）。这里是同一个道理：
     * 桌面不能像通知那样把内容放大填满（坐标系要对齐 108dp 画布），
     * 但**「先裁框、再判形状」这一步必须一致**。
     *
     * 分界线取 [FULL_BOX_FILL]（0.92）而不是更松的值：**实心圆形的填充率只有 0.785**
     * （π/4），带圆角的形状更低，只有「一整块矩形底」才够得着 0.92。
     * 这条线不能跟 [monochrome] 里那个 `useAlpha` 的 0.7 混 ——
     * 那个是**整张画布**口径下对覆盖率的兜底，量的是完全不同的东西。
     *
     * @param preserveAlpha 保留原始 alpha 的抗锯齿值（只把颜色换成白色），
     *   与 [monochrome] 的同名参数含义一致。
     */
    @Synchronized
    fun alphaSilhouette(input: Bitmap, preserveAlpha: Boolean = true): Bitmap? {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val bounds = contentBounds(input) ?: return null
        var transparent = 0
        for (y in bounds.top until bounds.bottom) {
            val row = y * w
            for (x in bounds.left until bounds.right) {
                if (Color.alpha(pixels[row + x]) < MIN_SHAPE_ALPHA) transparent++
            }
        }
        val boxTotal = bounds.width().toLong() * bounds.height()
        if (boxTotal <= 0) return null
        val solidRatio = (boxTotal - transparent).toFloat() / boxTotal
        // 外接框内几乎被填满 → 这不是「透明底 + 图形」，是一整块底。
        if (solidRatio >= FULL_BOX_FILL) return null

        // 与通知方案同一个实现：见 [alphaShape]。
        val output = IntArray(w * h)
        val count = alphaShape(pixels, output, preserveAlpha)
        val shapeRatio = count.toFloat() / (w * h)
        if (shapeRatio < MIN_SHAPE_RATIO || shapeRatio > MAX_SHAPE_RATIO) return null
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * [analyzeSplit] 的结果 —— 「这个图标能不能二分成图形 / 背景」的全部依据。
     *
     * 永久用途是给亮度掩码（[luminanceMask]）定极性；诊断日志也用同一份数字，
     * 免得两处各算一遍再分叉。
     */
    internal class Split(
        /** Otsu 阈值（亮度 ≤ 它的算「暗部」） */
        val threshold: Int,
        /** 暗部 / 亮部各占**不透明**像素的比例（0..1） */
        val darkRatio: Float,
        val lightRatio: Float,
        /** 暗部 / 亮部里「贴着图标外部」的比例（0..1）—— 判图形就是比这两个数 */
        val darkBorder: Float,
        val lightBorder: Float,
        /** `true` = 图形偏暗。亮度掩码据此决定要不要取反。 */
        val figureIsDark: Boolean,
        /** 不透明像素的亮度直方图，8 桶（0-31 / 32-63 / … / 224-255）各占的比例（0..1） */
        val lumaBuckets: FloatArray,
        /** 内容外接框占整张画布的比例 */
        val boxRatio: Float,
        /** 内容外接框内被不透明像素填满的比例 —— 1.00 就是「一整块矩形底」 */
        val boxFill: Float,
    )

    /**
     * 把 [monochrome] 内部「二值化那条路」的决策过程**原样复算一遍**，交成结构化数据。
     *
     * ⚠ 复用的是真算法（[otsuThreshold] / [keepDarkSide] 那两个私有函数），所以数字和
     * 真产出必然一致；另写一份副本一定会分叉，那就白算了。
     *
     * 两个用途：
     * 1. **定极性**：[Split.figureIsDark] 是「图形偏暗还是偏亮」，判据与二值化选侧
     *    完全同一个（连通性）。亮度掩码保留明暗层次，极性摆反了就是一张负片。
     * 2. **诊断**：`MonochromeSynth` 把它格式化成一行日志 —— 含亮度直方图（那是判断
     *    「该不该二值化」的关键：明显两个峰 = 双色图标，一路铺开 = 照片底），
     *    以及外接框占比 / 框内填充率（[Split.boxRatio] / [Split.boxFill]）——
     *    **后者就是 [alphaSilhouette] 判据的两个数**，出问题时一眼能看出它为什么走了那一支。
     */
    @Synchronized
    internal fun analyzeSplit(input: Bitmap): Split {
        val w = input.width
        val h = input.height
        val total = w * h
        val pixels = IntArray(total)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        var transparent = 0
        val buckets = IntArray(8)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                if (Color.alpha(p) < MIN_SHAPE_ALPHA) {
                    transparent++
                    continue
                }
                buckets[minOf(7, luma(p) / 32)]++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val solid = total - transparent
        val boxArea = if (maxX < 0) 0 else (maxX - minX + 1) * (maxY - minY + 1)
        val boxRatio = boxArea.toFloat() / total
        val boxFill = if (boxArea > 0) solid.toFloat() / boxArea else 0f
        if (solid == 0) {
            return Split(128, 0f, 0f, 0f, 0f, false, FloatArray(8), boxRatio, boxFill)
        }
        val threshold = otsuThreshold(pixels)
        val classes = classBuffer(total)
        var dark = 0
        var light = 0
        for (i in 0 until total) {
            val p = pixels[i]
            classes[i] = when {
                Color.alpha(p) < MIN_SHAPE_ALPHA -> CLASS_TRANSPARENT
                luma(p) <= threshold -> {
                    dark++
                    CLASS_DARK
                }
                else -> {
                    light++
                    CLASS_LIGHT
                }
            }
        }
        val outside = outsideMask(classes, w, h)
        val darkBorder = borderConnectedRatio(classes, outside, w, h, CLASS_DARK, dark)
        val lightBorder = borderConnectedRatio(classes, outside, w, h, CLASS_LIGHT, light)
        val figureIsDark = if (darkBorder == lightBorder) dark <= light else darkBorder < lightBorder
        val ratios = FloatArray(8) { buckets[it].toFloat() / solid }
        return Split(
            threshold,
            dark.toFloat() / solid,
            light.toFloat() / solid,
            darkBorder,
            lightBorder,
            figureIsDark,
            ratios,
            boxRatio,
            boxFill,
        )
    }

    /**
     * **通知方案的第二支**（[grayscale] 那一步的移植）：把「不透明像素的亮度」当作 alpha
     * 写出去，保留明暗层次。桌面只读 alpha，所以通知侧留下的**灰度**在这里必须写进 alpha。
     *
     * ## 什么时候用它（而不是 [alphaSilhouette]）
     * 通知侧的原话已经把这个道理写清楚了（见 [grayscale] 的说明）：
     * 「桌面图标是一整块不透明的彩色图形，二值化只会得到它的外轮廓（圆形图标就压成一个
     * 白圆），细节全丢。灰度化保留了明暗层次」。真机上这一类是：
     * 整块不透明的彩色图 / 照片底 / 渐变底 —— 走 alpha 只会得到那块底的外轮廓
     * （真机实测 `out=0.47 cc=1 top=1.00`，一整片实心方块），二值化则把它切成一地碎块
     * （真机实测 `cc=92`）。这两类输入的共同点是**没有可辨认的图形边界**，
     * 而亮度掩码不依赖边界：它把明暗原样交出去，缩到小尺寸也仍然认得出是哪个应用。
     *
     * 这也正是桌面自己在 `MonochromeIconFactory.generateMono` 里干的事 —— 那条路
     * 用户看下来是「黑白图标」，比色块 / 碎块好得多。
     *
     * ## 为什么要归一化
     * 不归一化的话，低对比度的图标（整张落在 luma 100~160）交出去就是一片半透明灰雾，
     * 上色之后几乎看不见。把 `[min, max]` 线性拉到 `[0, 255]` 之后，最暗处透明、
     * 最亮处实心，图形才立得住 —— 与桌面那条路一致。
     * `max - min` 太小（纯色块）时没有可用的对比度，返回 null 让调用方放弃。
     *
     * 原 alpha 会乘进去（`level × srcAlpha / 255`），否则图标的抗锯齿边缘会变成硬边。
     *
     * @param invert 图形偏暗时传 true —— 亮度掩码保留明暗层次，摆反了就是一张负片。
     *   [grayscale] 本身不翻转，所以这一步是**必需的补充**：它用的判据与
     *   [monochrome] 二值化选侧完全同一个（连通性），不另造一套。
     */
    @Synchronized
    internal fun luminanceMask(input: Bitmap, invert: Boolean): Bitmap? {
        val w = input.width
        val h = input.height
        val total = w * h
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(total)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        var min = 255
        var max = 0
        for (p in pixels) {
            if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
            val level = luma(p)
            if (level < min) min = level
            if (level > max) max = level
        }
        val span = max - min
        if (span < MIN_LUMINANCE_SPAN) return null
        val output = IntArray(total)
        var weight = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val sourceAlpha = Color.alpha(p)
            if (sourceAlpha < MIN_SHAPE_ALPHA) continue
            var level = (luma(p) - min) * 255 / span
            if (invert) level = 255 - level
            level = level * sourceAlpha / 255
            output[i] = Color.argb(level, 255, 255, 255)
            weight += level
        }
        val coverage = weight.toFloat() / 255f / total
        if (coverage < MIN_SHAPE_RATIO || coverage > MAX_SHAPE_RATIO) return null
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    /** 非透明像素的外接矩形；整张都透明时返回 null */
    private fun contentBounds(source: Bitmap): Rect? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[row + x]) >= MIN_SHAPE_ALPHA) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * 把彩色图标压成系统风格的单色剪影：透明底 + 白色形状。
     *
     * 系统对这种图标的处理，和对「应用自己做好了主题适配」的完全一样 ——
     * 按主题统一着色、随深浅主题变化。所以交给系统的必须是纯 alpha 形状，
     * 不能是带颜色的位图，否则染出来就不对了。
     *
     * 形状优先取自 alpha 通道：绝大多数图标是透明底 + 图形，alpha 本身就是轮廓。
     * 这一路的判据是 `alpha >= MIN_SHAPE_ALPHA` —— **低于它的淡色一律不算图形**，
     * 因为前景层常自带柔光 / 投影 / 半透明底板，照收就会把整张画布铺满淡色。
     * 整张都不透明时（自适应图标光栅化后常见）退化成亮度二值化，
     * 再按连通性区分「连到图标外部的背景」与「被包在里面的图形」。
     * 只有透明像素占多数时才走 alpha —— 透明像素少而图形大时，
     * alpha 描述的是整块底板的轮廓，照搬会压出一个白方块。详见 [binarize]。
     *
     * 压不出像样的形状时返回 null，由上层保持原样 —— 详见 [MIN_SHAPE_RATIO]。
     *
     * @param preserveAlpha 保留原始 alpha 的抗锯齿值（只把颜色换成白色），
     *   而不是二值化成 0 / 255。状态栏图标不需要（96px 下硬边看不出来），
     *   但桌面主题图标的渲染尺寸大得多，硬边一放大就是一圈锯齿 —— 那边传 true。
     *   ⚠ 它只决定「写出去的 alpha 是多少」，**不参与「哪些像素算图形」的判定** ——
     *   判定一律是 `alpha >= MIN_SHAPE_ALPHA`（[binarize] 那边同理，判据是
     *   `class != CLASS_TRANSPARENT`）。这两件事一旦混在一起，掩码的真实覆盖率
     *   就会和返回的 `shapeCount` 差一个数量级，而调用方正是拿 `shapeCount`
     *   在判「压出来的形状合不合理」—— 判据和产物对不上，等于没判。
     *
     * ⚠ 带 @Synchronized：下面那四块复用缓冲区是进程级可变状态，而 SystemUI 的通知处理
     * 会并发进来（binarize → keepDarkSide → outsideMask / borderConnectedRatio 一路都在用）。
     * 本函数是它们唯一的入口，锁在这一层就锁住了全部四块缓冲，
     * 与 [ToneCheck.isGrayscale] 用的是同一套做法（详见 [classBufferCache] 的说明）。
     * 其余函数（[fill] / [grayscale] / [hasContent] / [rasterize]）各自 new 局部数组，
     * 不共任何状态，不用跟着上锁。
     */
    @Synchronized
    fun monochrome(input: Bitmap, preserveAlpha: Boolean = false): Bitmap? {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val outputPixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var transparentCount = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) transparentCount++
        }

        val total = pixels.size
        val solidRatio = (total - transparentCount).toFloat() / total
        val useAlpha = transparentCount > total / 20 && solidRatio < SOLID_SHAPE_LIMIT
        val shapeCount = if (useAlpha) {
            // 透明底上的图形：alpha 本身就是最准的轮廓
            alphaShape(pixels, outputPixels, preserveAlpha)
        } else {
            // 实心图标：alpha 只描述外轮廓，照搬就是一坨白块，改用二值化挖图形
            binarize(pixels, outputPixels, w, h, preserveAlpha)
        }

        val shapeRatio = shapeCount.toFloat() / total
        if (shapeRatio < MIN_SHAPE_RATIO || shapeRatio > MAX_SHAPE_RATIO) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * [monochrome] 的 **alpha 那一支**，单独抽出来是因为桌面侧也要走同一份。
     *
     * 判据只有一条：**够不够实**（`alpha >= MIN_SHAPE_ALPHA`）。绝不能写成「有没有 alpha」——
     * 桌面图标的前景层常常自带一层很淡的柔光 / 投影 / 半透明底板
     * （alpha 1 ~ MIN_SHAPE_ALPHA-1，实测能占到画布的 60% 以上）。把它们一并收进掩码，
     * 掩码的真实覆盖率会是这里数出来的十几倍 —— 上色之后整块图标被一层淡色铺满，
     * 看上去就是一个纯色块。
     *
     * 之前 preserveAlpha 分支写成 `alpha > 0 && (...)`，判据用的是 128、写出去的却是 1 起步，
     * 两边口径不一致，就是这么踩进去的。
     *
     * @param preserveAlpha 保留原 alpha 的抗锯齿值（只换颜色），不二值化成 0 / 255。
     *   状态栏图标（96px）不需要，桌面主题图标渲染尺寸大得多、硬边一放大就是锯齿，那边传 true。
     * @return 涂了多少个像素（调用方拿它在判「压出来的形状合不合理」）
     */
    private fun alphaShape(pixels: IntArray, outputPixels: IntArray, preserveAlpha: Boolean): Int {
        var count = 0
        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            if (alpha < MIN_SHAPE_ALPHA) continue
            count++
            outputPixels[i] = if (preserveAlpha) Color.argb(alpha, 255, 255, 255) else Color.WHITE
        }
        return count
    }

    /**
     * 去掉颜色、只留明暗。
     *
     * 灰度图三通道相等，正好命中系统「这个图标做过主题适配」的判据
     * （ToneCheck 用的是同一套标准），于是系统会按主题给它上色 ——
     * 深色主题下白、浅色主题下深，和原生适配过的图标一模一样。
     *
     * 为什么不压成纯 alpha 剪影：桌面图标是一整块不透明的彩色图形，
     * 二值化只会得到它的外轮廓（圆形图标就压成一个白圆），细节全丢。
     * 灰度化保留了明暗层次，缩到状态栏那点尺寸也认得出是哪个应用。
     *
     * ⚠ 桌面侧那个 [luminanceMask] 就是这一支的移植：桌面只读 alpha，
     * 所以把这里留下的**灰度**写进 alpha。**两支是同一条方案的两个分支**，
     * 不是一个通知方案 + 一个桌面自造方案。
     */
    fun grayscale(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val output = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = Color.alpha(pixel)
            if (alpha < MIN_SHAPE_ALPHA) continue
            val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            output[i] = Color.argb(alpha, gray, gray, gray)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 亮度二值化，给实心图标挖出里面的图形。返回被涂成形状的像素数。
     *
     * 阈值不拍脑袋定常数，用 Otsu 自动求（让前后景的类间方差最大），各种配色的图标都能自适应。
     *
     * ⚠ 比较必须用 `<=`：Otsu 返回的阈值 t 语义是「暗部 = [0..t]」，
     * 端点 t 自己属于暗部。写成 `<` 会把恰好落在阈值上的那一档色调整批漏掉 ——
     * 两色图标（浅底 + 深图形）里就会出现「暗部 0 个像素」，进而判断成留亮部、
     * 结果一个像素都不画，交出去一张全空图。
     */
    private fun binarize(
        pixels: IntArray,
        output: IntArray,
        w: Int,
        h: Int,
        preserveAlpha: Boolean,
    ): Int {
        val threshold = otsuThreshold(pixels)
        val total = pixels.size
        val classes = classBuffer(total)
        var dark = 0
        var light = 0
        for (i in 0 until total) {
            val pixel = pixels[i]
            classes[i] = when {
                Color.alpha(pixel) < MIN_SHAPE_ALPHA -> CLASS_TRANSPARENT
                luma(pixel) <= threshold -> {
                    dark++
                    CLASS_DARK
                }
                else -> {
                    light++
                    CLASS_LIGHT
                }
            }
        }
        if (dark + light == 0) return 0

        val keepDark = keepDarkSide(classes, w, h, dark, light)
        var shapeCount = 0
        for (i in 0 until total) {
            val cls = classes[i]
            if (cls == CLASS_TRANSPARENT) continue
            if ((cls == CLASS_DARK) == keepDark) {
                // 同 [monochrome]：preserveAlpha 时保留原 alpha 的抗锯齿边缘
                output[i] = if (preserveAlpha) {
                    Color.argb(Color.alpha(pixels[i]), 255, 255, 255)
                } else {
                    Color.WHITE
                }
                shapeCount++
            }
        }
        return shapeCount
    }

    /**
     * 决定形状取暗部还是亮部 —— 这是「压出来是图形还是背景」的分水岭。
     *
     * 原来按「像素少的那一类是图形」猜。这个假设在图标里常常不成立：
     * 大 logo 顶到边、只有四角露出底色时，少数派反而是**背景**，
     * 于是压出来是个「框 + 中间一个洞」，和原图正好相反。
     *
     * 改成看**连通性**：背景通常与图标外部相连，图形是被背景包住的内部块。
     * 两类各做一次「从外部出发的同类连通填充」，与外部相连的比例低的那个才是形状。
     * 两类打平（上下对半分、图形本身也顶到边之类）时退回「取较少一侧」，
     * 保证至少不会比以前更差。
     */
    private fun keepDarkSide(classes: IntArray, w: Int, h: Int, dark: Int, light: Int): Boolean {
        val outside = outsideMask(classes, w, h)
        val darkBorder = borderConnectedRatio(classes, outside, w, h, CLASS_DARK, dark)
        val lightBorder = borderConnectedRatio(classes, outside, w, h, CLASS_LIGHT, light)
        if (darkBorder == lightBorder) return dark <= light
        return darkBorder < lightBorder
    }

    /**
     * 标出「图标外部」：与画布四边相连的透明区域。
     *
     * 不能只看画布四边。图标自带一圈透明边时（不到 5%，仍会走二值化），
     * 前景和背景都缩在画布内部，两类都判成「不与边界相连」，比例双双为 0，
     * 判定就退化成「取较少一侧」—— 在「大图形 + 透明边」的组合下又压反了。
     * 把外部定义成透明区域向内的延伸，那圈透明边就重新成为可用的边界。
     *
     * 只认「与画布四边连通」的透明区域：图形内部的透明洞不算外部，
     * 否则图形自己会被当成边界，判定同样失效。
     */
    private fun outsideMask(classes: IntArray, w: Int, h: Int): BooleanArray {
        val total = w * h
        val outside = outsideBuffer(total)
        outside.fill(false)
        val stack = stackBuffer(total)
        var top = 0

        fun seed(i: Int) {
            if (classes[i] == CLASS_TRANSPARENT && !outside[i]) {
                outside[i] = true
                stack[top++] = i
            }
        }

        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }

        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        return outside
    }

    /**
     * 某一类里，贴着图标外部的像素占该类的比例。
     *
     * 边界有两处：画布四边（整张不透明时唯一可用），以及紧邻 [outside] 的那一圈
     * （图标自带透明边时可用）。两处一起当种子，两类都够不到任何一处才会打平。
     *
     * 用显式栈做四邻域洪泛，不递归 —— 整张图标可能连成一片，递归会爆栈。
     * 缓冲区复用（这段代码在通知刷新的路径上），复用状态由 [monochrome] 上的锁串起来，
     * 见 [classBufferCache]。
     */
    private fun borderConnectedRatio(
        classes: IntArray, outside: BooleanArray, w: Int, h: Int, kind: Int, kindTotal: Int
    ): Float {
        if (kindTotal == 0) return 0f
        val total = w * h
        val visited = visitedBuffer(total)
        visited.fill(false)
        val stack = stackBuffer(total)
        var top = 0

        fun seed(i: Int) {
            if (classes[i] == kind && !visited[i]) {
                visited[i] = true
                stack[top++] = i
            }
        }

        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        for (i in 0 until total) {
            if (classes[i] != kind || visited[i]) continue
            val x = i % w
            val y = i / w
            if ((x > 0 && outside[i - 1]) ||
                (x < w - 1 && outside[i + 1]) ||
                (y > 0 && outside[i - w]) ||
                (y < h - 1 && outside[i + w])
            ) {
                seed(i)
            }
        }

        var connected = 0
        while (top > 0) {
            val i = stack[--top]
            connected++
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        return connected.toFloat() / kindTotal
    }

    /**
     * 这四个取缓冲函数都不判锁 —— 唯一的调用链是 [monochrome] → [binarize] / [keepDarkSide]，
     * 锁已经加在那条链的入口上了。绕过 [monochrome] 直接来用这些缓冲就是无保护地踩共享状态，
     * 加新代码时要么走 monochrome，要么自己带锁。理由详见 [classBufferCache] 的说明。
     */
    private fun classBuffer(size: Int): IntArray {
        var buf = classBufferCache
        if (buf == null || buf.size < size) {
            buf = IntArray(size)
            classBufferCache = buf
        }
        return buf
    }

    private fun visitedBuffer(size: Int): BooleanArray {
        var buf = visitedBufferCache
        if (buf == null || buf.size < size) {
            buf = BooleanArray(size)
            visitedBufferCache = buf
        }
        return buf
    }

    private fun stackBuffer(size: Int): IntArray {
        var buf = stackBufferCache
        if (buf == null || buf.size < size) {
            buf = IntArray(size)
            stackBufferCache = buf
        }
        return buf
    }

    /** 外部掩码必须与 [visitedBuffer] 分开缓存 —— 洪泛期间两者同时在用，共用会互相清掉 */
    private fun outsideBuffer(size: Int): BooleanArray {
        var buf = outsideBufferCache
        if (buf == null || buf.size < size) {
            buf = BooleanArray(size)
            outsideBufferCache = buf
        }
        return buf
    }

    /**
     * 通知刷新路径上复用的四块缓冲区，grow-only（只在需要时换成更大的数组，从不缩回去）。
     *
     * ⚠ **进程级可变状态，必须串行访问**：SystemUI 的通知处理会并发进来（不同通知各走一条线程），
     * 而这里以前是四个裸 `private var`、[monochrome] 不带锁 —— 那和 ToneCheck 开头
     * 「必须线程安全」的说法直接互相打脸。两边的说法只能留一个，这里保守地按有风险的那条来：
     * 现在锁在 [monochrome] 上（唯一入口），classBuffer / visitedBuffer / stackBuffer /
     * outsideBuffer 这四个取缓冲函数整体被串起来，做法与 [ToneCheck.isGrayscale] 完全一致。
     * 不加锁能坏在哪，两条都是「日志干净、查不到」那一类：
     *   1. 两条线程往同一块 visited/outside 里互相盖标记 → 连通性判据出错，
     *      压出来的形状整个反掉（图形与背景互换），或者判成空图；
     *   2. 共用的 stack 里混进另一条线程那张**更大**图的下标，本线程把它弹出来当索引
     *      去访问自己手上那块较小的 outside/visited → ArrayIndexOutOfBoundsException。
     *      （grow-only 自己换数组不会越界，越界是「下标来自别人的图」带来的。
     *      异常从这里抛出去 = 整条 inflateViews 钩子被 protective 吞掉，那条通知不补丁。）
     */
    private var classBufferCache: IntArray? = null
    private var visitedBufferCache: BooleanArray? = null
    private var stackBufferCache: IntArray? = null
    private var outsideBufferCache: BooleanArray? = null

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
    }

    /** Otsu：遍历所有可能阈值，取类间方差最大的那个 */
    private fun otsuThreshold(pixels: IntArray): Int {
        val histogram = IntArray(256)
        var total = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) continue
            histogram[luma(pixel)]++
            total++
        }
        if (total == 0) return 128

        var sum = 0
        for (i in 0..255) sum += i * histogram[i]

        var sumBackground = 0
        var weightBackground = 0
        var maxVariance = 0.0
        var threshold = 128
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                    (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        return threshold
    }

    /**
     * 把各种载体的 Icon 还原成位图，取不到就返回 null 交给上层跳过。
     *
     * getType()/getResId() 是 API 28 才公开的，Android 8.x 上只能走 loadDrawable 兜底，
     * 所以这里显式判版本而不是指望 catch —— 兜底路径同样能拿到图。
     */
    fun decode(icon: Icon, context: Context): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (icon.type) {
                Icon.TYPE_RESOURCE -> fromResources(context, icon.resId)
                Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> MemberLookup.readField(icon, "mObj1") as? Bitmap
                Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> {
                    val uriString = MemberLookup.readField(icon, "mString1") as? String
                    if (uriString != null) decodeUri(uriString, context) else null
                }
                Icon.TYPE_DATA -> {
                    val bytes = MemberLookup.readField(icon, "mObj1") as? ByteArray
                    val offset = MemberLookup.readField(icon, "mInt1") as? Int
                    val length = MemberLookup.readField(icon, "mInt2") as? Int
                    if (bytes != null && offset != null && length != null) {
                        BitmapFactory.decodeByteArray(bytes, offset, length)
                    } else {
                        null
                    }
                }
                else -> icon.loadDrawable(context)?.let { rasterize(it) }
            }
        } else {
            icon.loadDrawable(context)?.let { rasterize(it) }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 解码 TYPE_URI 图标。
     *
     * `mString1` 存的是 URI 字符串（形如 `file:///data/local/tmp/x.png`），
     * 不能直接喂给 BitmapFactory.decodeFile —— 那个方法要的是文件路径，
     * 拿到带 scheme 的串会 ENOENT。file:// 剥掉 scheme 取 path，
     * 其它 scheme（content:// 等）交给 ContentResolver。
     */
    private fun decodeUri(uriString: String, context: Context): Bitmap? {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == null || uri.scheme == "file") {
            BitmapFactory.decodeFile(uri.path ?: uriString)
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }

    /**
     * Notification.mSmallIcon 是私有字段，没有公开 setter，只能反射写。
     * 这是本模块唯一一处「必须碰私有 API」的地方，没有替代方案，故抑制告警。
     *
     * 返回是否真的写进去了：某些定制系统改过这个字段，反射会失败。
     * 上层靠这个返回值决定要不要打 patched 日志，写失败就不该报成功 ——
     * patched 是排查时唯一的「真的换了」凭据，报假阳性会让排查走错方向。
     */
    @SuppressLint("DiscouragedPrivateApi")
    fun applySmallIcon(icon: Icon, notification: Notification): Boolean = runCatching {
        val field = Notification::class.java.getDeclaredField("mSmallIcon")
        field.isAccessible = true
        field.set(notification, icon)
        true
    }.getOrDefault(false)

}
