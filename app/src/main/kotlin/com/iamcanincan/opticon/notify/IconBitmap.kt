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
import kotlin.math.abs
import kotlin.math.sqrt

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
     * 压出来的形状占**它自己那块内容外接框**的比例，低于下限（几乎全空）或高于上限
     * （几乎全满）都算失败。
     *
     * 状态栏只认 alpha，交出去一张空图就是一个看不见的洞，交出去一张满图就是一个纯色块，
     * 两者都比原图标更糟。这种情况宁可放弃替换、保持原样。
     *
     * ## ⚠ 桌面那两支必须按**外接框**口径量，不能按整张画布
     * 这是 2026-09-24「白块 / 块不干净」那条反馈的病根。自适应前景层的内容只落在 108dp
     * 画布中央约 61% 一线，**外接框本身就只有画布的约 47%** —— 于是「整个外接框被填满」
     * 这种最坏的掩码（一整块底板，字形根本不在里面）按画布量出来是 0.47，
     * 离 0.85 的上限远得很，**永远拦不住**。真机日志早就把这个数打出来了：
     * 淘宝那一版 `out=0.47 cc=1 top=1.00`（`out` 就是画布口径）——
     * `cc=1 top=1.00` 说的是「一整块」，`out=0.47` 却一路放行。
     *
     * 通知侧不受这个影响：那条链在 [monochrome] 之前先 [fill] 过，内容已经放大填满整张画布，
     * 外接框 ≈ 画布，两种口径是同一个数。所以这里**只有桌面的两个入口**换成框口径，
     * [monochrome] 保持画布口径不动（改它等于改已验过的通知那条链）。
     */
    private const val MIN_SHAPE_RATIO = 0.02f
    private const val MAX_SHAPE_RATIO = 0.85f

    /**
     * 「一整块底板」的两条线之一：产出**填得满**（≥ 这个数）而且**里面几乎是空的**
     * （内部结构占比 ≤[PLATE_HOLE]）才判成板。
     *
     * 为什么要两条一起用：只填得满是不够的 —— 一张带字形的彩色底板（中国移动那种
     * 「白底 + 彩色 logo」被 Otsu 保住的圆角方）框内也能到 0.9+，但它的洞就是字形，
     * 那是**对的形状**。真正要拦的是「连洞都没有」的那一类：前景就是一张满幅不透明的
     * 彩图、亮度切不出任何东西，交出去屏幕上就是一块纯色。
     * 0.55 / 0.03 这两条是判据台上按「汉字字形（洞占比通常 0.05 以上）不误伤」定的，
     * 真机跑一轮要看日志里 `结构=` 的分布再收。
     *
     * ⚠ [MonochromeSynth] 里"要不要先试着刻底"用的就是同一个数（不是另一个 0.55）——
     *   两条线量的本是同一件事，分成两个常量的话，迟早只调一个、判据自相矛盾。
     */
    internal const val PLATE_FILL = 0.55f

    /**
     * 「一整块板」的另一条线：框内填满到 [PLATE_FILL] 之后，**内部结构**少到这个比例以下
     * 才算一块底（字形总有字腔，洞不会是 0）。
     *
     * 同样是 `MonochromeSynth` 判「alpha 交出来的是图形还是那块底」时用的数 —— 一个意思
     * 只留一个常量。真机日志每条掩码都带着 `结构=`，这条线就是拿来收它的。
     */
    internal const val PLATE_HOLE = 0.03f

    /**
     * 「碎成一片」：修整之后仍然块数 ≥[SHATTER_COMPONENTS] 且最大块占比 ≤[SHATTER_TOP]。
     *
     * ⚠ 两个数必须一起看，单看块数会误杀汉字 —— 「淘」这种四个字形的块天然 cc=4，
     * 而每一块都是实心笔画（top≈0.4）。真正的碎裂是渐变底被切出的一堆小渣。
     * 先闭运算再判，是因为闭运算本来就该把抗锯齿切断的笔画连回去；连不回去的才是真碎。
     */
    private const val SHATTER_COMPONENTS = 8
    private const val SHATTER_TOP = 0.35f

    /**
     * 碎渣的最小尺寸：连通块小于 `总面积 / MIN_SPECK_DIVISOR`（且不少于 8 像素）就擦掉。
     *
     * 按面积给比例而不是写死像素数，是因为掩码出图尺寸跟着桌面要的大小变
     * （本机 192 / 384 两档）—— 定死 36 像素的话，192 那一档上就等于抹掉一整笔。
     */
    private const val MIN_SPECK_DIVISOR = 4096

    /**
     * 「有一块主导底板」的最低占比：最大色桶要占到不透明像素的这个比例，才认为这张图是
     * 「底板 + 字形」结构、值得按色距去刻。低于它说明这张图本来就多色（几个色块拼出来的
     * logo），那时亮度 Otsu 比色距更接近正解，交回 [binarizeSilhouette]。
     *
     * 0.30 偏保守：刻错的代价是把好形状刻没（还有别的支兜底），漏刻的代价是大色块照旧。
     * 真机要看的是这个占比的实际分布，不是这一个数对不对 —— 它现在跟着日志一起打出来。
     */
    private const val MIN_PLATE_SHARE = 0.30f

    /**
     * 占比低于这个数的层当作噪声并回底板。
     *
     * 渐变底最容易骗出这么一层：三阶采样总会在渐变最暗端挑中一个精确色，于是它独立成层、
     * 只占几个百分点。让它参与字形就等于**把底板的碎片留在掩码里** —— 观感就是"色没去掉"。
     */
    private const val NOISE_LAYER_SHARE = 0.02f

    /**
     * 一个色桶要占多少像素才"配当一层"：桶计数 × 本数 ≥ 不透明像素数，即占比 ≥ 1/[MIN_LAYER_VOTE]。
     *
     * 挑层心前先过这道线，是因为渐变底会把颜色散成几百个只占千分之几的小桶；不过这道线，
     * 最远点采样就会挑中渐变尾端的一个小桶当"第二层"，把底板碎片当成字形。
     */
    private const val MIN_LAYER_VOTE = 100

    /** 二值化时每个像素的归类，用于连通性判断 */
    private const val CLASS_TRANSPARENT = 0
    private const val CLASS_DARK = 1
    private const val CLASS_LIGHT = 2

    /**
     * 三层输出里"点缀"那一档的 alpha：50% 的系统前景色压在系统底色上 = 两档的**中值**。
     *
     * 主体是 255、底板是 0（让系统的底色透出来）。三档现在是插值的**控制点**、不是分箱：
     * 见 [toneAlpha] —— 平色区域照旧平色，渐变区域恢复成过渡。
     */
    internal const val TONE_MID = 128

    /**
     * 三档插值之后，多淡的像素才算"图形的一部分"。
     *
     * 有了分段插值，靠近底板色的像素会拿到一个很小的 alpha —— 那就又把柔光/渐变尾端
     * 整片收进掩码了（本项目踩过的那类"淡色铺满"）。所以带档的掩码也要有条线，取中档的
     * 一半：视觉上不到半成色的地方一律当透明。
     */
    internal const val TONE_FLOOR = 64

    /**
     * 形层距离下界的取数分位（%）—— 斜坡零点落在「形这两层里 5% 的像素散不到这么低」
     * 的那个距离上。取数与理由见 [glyphFloor]。
     *
     * 用分位而不用最小值：抗锯齿与渐变最淡的那一头总会产出几个几乎等于底色的像素，
     * 最小值会被它们拽回 0，零点等于没设。真机日志每条三层判定都带着这个数
     * （`档距下/近/远`），它是用来收这条线的。
     */
    private const val GLYPH_FLOOR_PCT = 5

    /**
     * 「独立第三种颜色」的最小离轴距离（RGB 欧氏空间，三通道求和口径，0..765）。
     *
     * 低于它，那层就是**渐变 / 抗锯齿 / 同色阴影**的混合带而不是第三种色相；35 ≈ 每通道偏
     * 12，肉眼分不出色相的差别就在这个量级以内。
     *
     * ⚠ 目前**只用来给日志分类**（`三层·离轴NN=混合带/独立色`），不参与掩码判定 ——
     * 上一轮拿它做过"共线就把这层并回主体"，alpha 改回插值之后不需要并层了，
     * 混合带在斜坡上自然穿过中点。留这个数是为了真机分布出来时看得懂分层结果。
     */
    private const val ACCENT_OFF_AXIS = 35.0

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
     * [alphaSilhouette] / [binarizeSilhouette] 的产出：掩码，加上**这一张的判据数字**。
     *
     * 为什么要把数字带出去而不是只回一个 `Bitmap?`：桌面主题图标这块看不见（图像通道是
     * filtered 的，用户也明确不要我截图），而「白块 / 块不干净」这类观感问题只有一句
     * 「压不出掩码」是查不了的 —— 上一轮的 `out=0.47` 就是活例子：数打了，但打在**画布**
     * 口径上，一整块底板量出 0.47，看着完全正常。现在每个图标那行日志都带着它被收 / 被拒
     * 的那个**框内**比例，判据定得对不对一眼能核。
     *
     * @param mask 白色 + alpha 的掩码；null = 这一支不成立，调用方该换支或放弃合成。
     * @param boxFill 内容外接框内的等效覆盖率（0..1）。被拒时它就是**致拒的那个数**。
     * @param verdict 一句话结论，直接拼进日志。
     * @param tones 这张掩码带**有意的三档浓度**（主体 255 / 点缀 128 / 底 0）。
     *   调用方（`MonochromeSynth.toAlphaMask`）据此决定还能不能做"内部一律钳实心"那一步 ——
     *   钳了就等于把中档抹平，三层又变回两层。
     * @param structure 掩码的**内部结构**占框比例（[Shape.structureRatio]）。不带档的掩码里
     *   它就是「外接框内的封闭空洞 / 框」—— 一块底板永远是 0（它没有洞），而字形有字腔。
     * @param largest **最大连通块**占掩码的比例。**一整块底 = 1.00**。
     *
     * 后两个数是为调用方准备的：`MonochromeSynth` 要靠它们判「alpha 交出来的这件东西到底是
     * 图形还是那块底」。**光看 [boxFill] 判不了** —— 真机上 元宝 0.27 与 一件正常字形 0.31
     * 前后脚，而前者 `结构=0.00 cc=1 top=1.00`（一块实心板）、后者有洞有笔画。
     */
    internal class Silhouette(
        val mask: Bitmap?,
        val boxFill: Float,
        val verdict: String,
        val tones: Boolean = false,
        val structure: Float = 0f,
        val largest: Float = 0f,
    )

    /**
     * **通知方案的第一支**（`grayscale` 保留 alpha 那一步）—— 透明底 + 图形的轮廓。
     *
     * 与 [monochrome] 的 `useAlpha` 支走**同一份实现**（[alphaShape]），
     * 唯一的差别是「透明像素占多少」在**哪张图、哪个口径**上量：这里是**内容外接框**口径，
     * 理由见 [MAX_SHAPE_RATIO] 上那一段（画布口径会让一整块底板量出 0.47 而一路放行）。
     * 不成立时 `mask` 为 null，调用方应当改用 [binarizeSilhouette]。
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
     * 裁到内容外接框之后再量，同一张图是「框内填充 1.00」→ 判据正确地落到 [binarizeSilhouette]
     * 那一支；而真正的透明底图形（图形笔画之间、四周都有透明）照旧走 alpha。
     * 于是「安全区留白」与「透明背景」这两件完全不同的事被分开 ——
     * **不需要为某一类图标单独开一条分支**。
     *
     * 通知侧对同一件事的表述是「**先 fill 再 monochrome** —— fill 裁掉的透明边正好是
     * 判断形状的凭据，反过来做会把整块图标压成一个实心圆」（见
     * `NotificationIconPatch.launcherIconMonochrome`）。这里是同一个道理：
     * 桌面不能像通知那样把内容放大填满（坐标系要对齐 108dp 画布），
     * 但**「先裁框、再判形状」这一步必须一致**。
     *
     * 分界线就是 [MAX_SHAPE_RATIO]（0.85），不再另设一条：实心圆的框内填充率只有
     * 0.785（π/4），够不着这条线，所以「圆形底板压成一个圆」这种**正确**的行为不会被误伤。
     * 也不要跟 [monochrome] 里那个 `useAlpha` 的 0.7 混 —— 那是**画布**口径下对覆盖率的
     * 兜底（通知侧 fill 过，框≈画布），量的不是同一个东西。
     *
     * @param preserveAlpha 保留原始 alpha 的抗锯齿值（只把颜色换成白色），
     *   与 [monochrome] 的同名参数含义一致。
     */
    @Synchronized
    internal fun alphaSilhouette(input: Bitmap, preserveAlpha: Boolean = true): Silhouette {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return Silhouette(null, 0f, "尺寸非法")
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val bounds = contentBoundsOf(pixels, w, h) ?: return Silhouette(null, 0f, "整张透明")
        var transparent = 0
        for (y in bounds.top until bounds.bottom) {
            val row = y * w
            for (x in bounds.left until bounds.right) {
                if (Color.alpha(pixels[row + x]) < MIN_SHAPE_ALPHA) transparent++
            }
        }
        val boxTotal = bounds.width().toLong() * bounds.height()
        if (boxTotal <= 0) return Silhouette(null, 0f, "外接框为空")
        val boxFill = (boxTotal - transparent).toFloat() / boxTotal
        // 外接框内几乎被填满 → 这不是「透明底 + 图形」，是一整块底。
        if (boxFill >= MAX_SHAPE_RATIO) return Silhouette(null, boxFill, "框内已填满=底板")
        if (boxFill <= MIN_SHAPE_RATIO) return Silhouette(null, boxFill, "框内几乎全空")

        // 与通知方案同一个实现：见 [alphaShape]。
        // ⚠ 这里**不再**重复一遍覆盖率守卫：alphaShape 收的正是「框内 alpha ≥ 128」那一批
        // 像素，它的计数除框面积就是上面那个 boxFill（按构造必然相等）。再量一遍只会
        // 多一次全画布遍历，还会留下两条可能对不上的数。
        val output = IntArray(w * h)
        val count = alphaShape(pixels, output, preserveAlpha)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return Silhouette(result, boxFill, "$count 像素")
    }

    /**
     * [analyzeSplit] 的结果 —— 「这个图标能不能二分成图形 / 背景」的全部依据。
     *
     * 它是**诊断用途**：`MonochromeSynth` 把它格式化成一行日志，于是
     * 「[binarizeSilhouette] 为什么选了这一侧、切得对不对」在真机上看得见。
     * 判定本身不依赖它（那由 [binarize] 内部的 [otsuThreshold] / [keepDarkSide] 直接做）。
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
        /** `true` = 图形偏暗（即 [binarize] 会保留暗部）。 */
        val figureIsDark: Boolean,
        /** 不透明像素的亮度直方图，8 桶（0-31 / 32-63 / … / 224-255）各占的比例（0..1） */
        val lumaBuckets: FloatArray,
        /** 内容外接框占整张画布的比例 */
        val boxRatio: Float,
        /** 内容外接框内被不透明像素填满的比例 —— 1.00 就是「一整块矩形底」 */
        val boxFill: Float,
    )

    /**
     * 把 [binarize] 那条路的决策过程**原样复算一遍**，交成结构化数据（**只用于诊断**）。
     *
     * ⚠ 复用的是真算法（[otsuThreshold] / [keepDarkSide] 那两个私有函数），所以数字和
     * 真产出必然一致；另写一份副本一定会分叉，那就白算了。
     *
     * 它回答的是「二值化切在哪、切得对不对」：Otsu 阈值、两侧各占多少、两侧的
     * 「贴外部」比例（连通性判据的两个输入）、图形偏哪一侧，以及亮度直方图 ——
     * 明显两个峰 = 双色图标（切得开），一路铺开 = 照片底（切出来会很碎）。
     * 另外还带外接框占比 / 框内填充率（[Split.boxRatio] / [Split.boxFill]）——
     * **后者就是 [alphaSilhouette] 判据的两个数**，一眼能看出它为什么没走 alpha 支。
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
     * **通知方案的第二支**：Otsu 自动阈值二值化 + 连通性判据挖出图形
     * （[monochrome] 的 else 支就是这一段，这里只是把它单独开一个入口）。
     *
     * ## 什么时候用它（而不是 [alphaSilhouette]）
     * 透明像素不构成背景时 —— 整块不透明的彩色图 / 照片底 / 满幅位图。
     * 这类输入的 alpha 只描述那块底的外轮廓，照搬就是一整片实心方块
     * （真机实测 `out=0.47 cc=1 top=1.00`），所以要按**亮度**把里面的图形挖出来。
     *
     * ## 为什么必须是二值，不能是连续灰度
     * 桌面拿到掩码后只读 alpha，并且**拿 alpha 当不透明度**上色。alpha 是连续灰阶时，
     * 染出来就是同一个颜色的**渐变**（最暗处几乎透明、最亮处实心）—— 而主题图标要的是
     * **一整块纯色**。所以这里输出 0 / 255 二值（`preserveAlpha` 只保留边缘那一圈
     * 抗锯齿值，不是灰阶），与 [monochrome] 完全一致。
     *
     * 选哪一侧（暗部还是亮部）由 [keepDarkSide] 的**连通性**判据定：背景通常与图标
     * 外部相连、图形是被包住的内部块 —— 比任何固定阈值都稳，也和通知侧同一套。
     *
     * 压不出像样的形状时返回 `mask = null`，由上层保持原样。⚠ 这里的「像样」按
     * **内容外接框**口径量（与 [alphaSilhouette] 同一个理由）：这一支最常踩的坑正是
     * 「连通性打平 → 退回取较少一侧 → 结果保住的还是那块底」，按画布量它永远只有
     * 约 0.47，拦不住；按框量就是 1.00，当场拒掉、让桌面走自己的路。
     */
    @Synchronized
    internal fun binarizeSilhouette(input: Bitmap, preserveAlpha: Boolean = true): Silhouette {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return Silhouette(null, 0f, "尺寸非法")
        val total = w * h
        val pixels = IntArray(total)
        val output = IntArray(total)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val count = binarize(pixels, output, w, h, preserveAlpha)
        val bounds = contentBoundsOf(pixels, w, h) ?: return Silhouette(null, 0f, "整张透明")
        val boxTotal = bounds.width().toLong() * bounds.height()
        if (boxTotal <= 0) return Silhouette(null, 0f, "外接框为空")
        // 分子是「保住了的那一侧」，分母是**内容外接框** —— 两侧都不是整张画布，见 [MAX_SHAPE_RATIO]。
        val boxFill = count.toFloat() / boxTotal
        if (boxFill > MAX_SHAPE_RATIO) return Silhouette(null, boxFill, "保住的那侧=整块底")
        if (boxFill < MIN_SHAPE_RATIO) return Silhouette(null, boxFill, "几乎没保住东西")

        return finish(output, w, h, bounds, "亮度 Otsu")
    }

    /**
     * **按「与底板主色的距离」把字形从底板里刻出来** —— 大色块的正解。
     *
     * ## 为什么亮度 Otsu 不够
     * 中国 App 的图标绝大多数是「一层彩色底板 + 与之不同的字形」：红底白字、蓝底白 logo、
     * 白底彩字。这类图**每一像素都不透明**，alpha 描述的是那块底的轮廓（整块收下就是大色块），
     * 而亮度 Otsu 只在「底与字形恰好一明一暗」时切得开 —— 底板自带渐变时，直方图根本没有
     * 两个峰，切在哪一档全看运气。
     * 「离底板主色有多远」这条线就不挑明暗：白字在红底上、红底在白字上，距离都是大。
     * 判据仍是**一条通用的**（有主导色才切、没有就交回别的支），不为任何具体图标开特判。
     *
     * ## 什么时候返回 null（= 这条路不适用）
     * 1. 不透明像素里**没有主导色**（最大色桶占比 < [MIN_PLATE_SHARE]）—— 多色 logo，
     *    根本没有"一块底"可谈；
     * 2. 距离直方图切不出分层（阈值落在 0 档）—— 整张就是单一颜色，没有可刻的字形；
     * 3. 刻出来的东西过满 / 过空，或体检不过关（见 [finish]）。
     *
     * ⚠ 透明底上的彩色字形**不会被误刻**：那种图的最大色桶就是字形自己，
     * 全图距离≈0 → 走到第 2 条返回 null → 仍然由 alpha 支交出原形状。
     */
    @Synchronized
    internal fun plateSilhouette(input: Bitmap): Silhouette {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return Silhouette(null, 0f, "尺寸非法")
        val total = w * h
        val pixels = IntArray(total)
        val output = IntArray(total)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val bounds = contentBoundsOf(pixels, w, h) ?: return Silhouette(null, 0f, "整张透明")
        val boxTotal = bounds.width().toLong() * bounds.height()
        if (boxTotal <= 0) return Silhouette(null, 0f, "外接框为空")

        // 1) 底板颜色 = **逐通道中位色**，不是众数色。
        //    先试过按 4 bit 量化分桶取众数：渐变底会散成几千种颜色，真机实测 12306 那张
        //    最大色桶只占 0.11 —— 「有没有一块主导底」当场判否，整块底照旧交出去，
        //    大色块根本没治到。换成中位色后同一张图「离底够近」占 0.44，越得过线。
        //    取中位数走 256 桶直方图而不是排序：这是在桌面加载图标的路上，排 14 万个数不值。
        val histR = IntArray(256); val histG = IntArray(256); val histB = IntArray(256)
        var solid = 0
        for (p in pixels) {
            if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
            solid++
            histR[Color.red(p)]++; histG[Color.green(p)]++; histB[Color.blue(p)]++
        }
        if (solid == 0) return Silhouette(null, 0f, "整张透明")
        val plateR = medianOf(histR, solid)
        val plateG = medianOf(histG, solid)
        val plateB = medianOf(histB, solid)

        // 2) 「一个图标最多三层颜色」是**预算**，不是否决条件。每个不透明像素都必须归到
        //    三层之一：底板 / 主体 / 点缀，归类按「离哪个层心最近」，再跑一次均值修正
        //    （Lloyd 一步）让层心落到该类的真实中心上。
        //
        //    ⚠ 上一版在这里留了个出口：「离三层心都超过阈值的像素占比 >0.30 就放弃刻底」。
        //    那等于把「我算不出三层」当成「这张图不是三层」—— 渐变底整块被判成装不下、
        //    直接不刻，彩色底板原样留在掩码里，就是你说的「去色没做好」。
        //    不留出口之后：底那一层必然被摘掉，剩下的两层就是字形，没有"逃出去"的像素。
        val layer = assignLayers(pixels, plateR, plateG, plateB)
        var plateCount = 0
        var count = 0
        var l1 = 0
        var l2 = 0
        var opaque = 0
        for (i in 0 until total) {
            when (layer[i]) {
                0 -> plateCount++
                1 -> l1++
                2 -> l2++
                else -> continue
            }
            opaque++
        }
        // 走到这里说明上面已经量到不透明像素了，所以"一层都没有"只可能是**整张同色**
        // （assignLayers 在 d1<=0 时不给任何层）—— 别报成「整张透明」，那会把排查引到
        // 「alpha 判据坏了」上去，而真实情况是这张图根本没有第二种颜色可刻。
        if (opaque == 0) return Silhouette(null, 0f, "整张同色，无层可刻")
        // 占比不到 NOISE_LAYER 的那一层当作噪声，并回底板 —— 渐变底常会骗出一个只占几个
        // 百分点的"第三层"，让它参与字形就等于把底板的碎片留在掩码里。
        // ⚠ 必须**改标签**，不能只把计数清零：只清 `l1 = 0` 的话，下面按面积定主体/点缀时
        //   会把这一层派成"点缀"，而 `layer[i]` 里那些像素还在 —— 噪声照样被涂进掩码，
        //   还正好涂上最显眼的中灰档（观感就是"去色没去干净、边上发脏"）。计数也会与
        //   实际涂出的像素对不上，日志里 `像素=`/`boxFill=` 描述的就不是交出去的那张图。
        val noise1 = l1 > 0 && l1.toFloat() / opaque < NOISE_LAYER_SHARE
        val noise2 = l2 > 0 && l2.toFloat() / opaque < NOISE_LAYER_SHARE
        if (noise1 || noise2) {
            for (i in 0 until total) {
                val l = layer[i]
                if ((l == 1 && noise1) || (l == 2 && noise2)) layer[i] = 0
            }
            if (noise1) { plateCount += l1; l1 = 0 }
            if (noise2) { plateCount += l2; l2 = 0 }
        }
        count = l1 + l2
        if (plateCount.toFloat() / opaque < MIN_PLATE_SHARE) {
            return Silhouette(null, 0f, "无主导底板（底占 ${"%.2f".format(plateCount.toFloat() / opaque)}）")
        }
        if (count == 0) return Silhouette(null, 0f, "除底板外没有别的层")
        // ── 哪层算主体按**面积**定：大的是形，小的是点缀。 ──
        val mainLayer = if (l1 >= l2) 1 else 2
        val accentLayer = if (mainLayer == 1) 2 else 1
        // ── 「是不是底」由**归层**说了算，"多浓"由**离底多远**说了算 ──
        // 这两件事以前一直混在同一个量里量，结果两个方向都翻过车：
        //   · 只看距离、零点摆在底板色散上 → 与底板颜色相近的那层形整片被当成底抹掉。
        //     这就是用户报的「同一图标内颜色太相近会被画成同一个颜色，然后成了白块 / 黑块」
        //     —— 细节不是糊掉，是**根本没涂进掩码**。
        //   · 只看距离、零点摆在 0 → 底板自己那截渐变尾端越过形状线被涂成形。
        //     判据台上 steamlink 那一版框内填满 **0.78**，就是一块色板。
        // 分开之后各司其职：`assignLayers` 已经把「离中位色最近的那一群」判成底，
        // 它内部有没有渐变与"是不是底"无关；剩下两层无论离底多近都是形，
        // 浓度按**形自己那一段距离**撑开（下界见 [glyphFloor]），所以相近的两种颜色
        // 照样分得开，而渐变段照旧是渐变。
        //
        // ⚠ 两个形档按**离底远近**排浓度（近 → [TONE_MID]、远 → 255），不按面积排。
        //   alpha 本就是距离的单调函数，面积大的那层离底更近时两个控制点前后颠倒，
        //   斜坡刚起步就饱和成 255。判据台的 `档距下/近/远` 抓到的就是这个：
        //   12306 一度量出 55/129/**56**、steamlink 31/164/**24**。
        //
        // ⚠ 这条线在一天里被裁定过五次，记下来免得再翻：
        //   ① 按层写死 0 / 128 / 255 → 渐变段被整片吸进某一层，"渐变被做成平色块"；
        //   ② 改成插值 → "不纯色、发糊"（这个项目一开始就反对连续过渡的老理由）；
        //   ③ 回到离散，并把共线的混合带并回相邻层（"纯色 = 跟旁边一个颜色"）；
        //   ④ 渐变还是保留渐变的样子 —— 插值回来；
        //   ⑤ **归层管底、距离管浓度** —— 就是现在这版，④ 那版把两件事混在距离里，
        //     于是"相近颜色并成一个"和"底板渐变涂成块"同时存在。
        val dMain = meanDistanceFromPlate(pixels, layer, mainLayer, plateR, plateG, plateB)
        val dAccent = if (l2 > 0) meanDistanceFromPlate(pixels, layer, accentLayer, plateR, plateG, plateB) else 0
        val dFar = maxOf(dMain, dAccent)
        val dNear = minOf(dMain, dAccent)   // 0 = 只有一层形，没有中间档
        // 斜坡的零点 = 形那两层自己的距离下界。渐变尾端与抗锯齿边都在这之下，
        // 而"与底板只差一点点"的那层形落在它上面 —— 这就是与 ④ 那版的分界线。
        //
        // ⚠ 但零点最远只能设在**字形跨度的一半**处。平色字形里「最淡的 5%」与均值
        //   几乎是同一个数，照字面用分位数会把整层压成一步跳变，还会削掉细笔画的抗锯齿边
        //   —— 判据台上元宝一度量出 `档距149/0/147`：零点(149)高过实心点(147)，
        //   结果 `结构=0.00 top=1.00`，一块实心。钳在半跨上之后同一张回到有过渡、有结构。
        val dFloor = minOf(glyphFloor(pixels, layer, plateR, plateG, plateB), dFar / 2)
        // 离轴距离**只作为观测量打印**：上一轮用它做过"共线就把这层并回主体"，插值恢复之后
        // 不需要并了 —— 混合带在斜坡上自然会穿过中点。留这个数是为了真机上看得懂分层结果。
        val offAxis = if (l1 > 0 && l2 > 0) accentOffAxis(pixels, layer, accentLayer, plateR, plateG, plateB) else -1.0
        val toneNote = if (offAxis >= 0) {
            "离轴${"%.0f".format(offAxis)}${if (offAxis < ACCENT_OFF_AXIS) "=混合带" else "=独立色"}"
        } else "单层"
        var painted = 0
        for (i in 0 until total) {
            val p = pixels[i]
            // ⚠ 只有「归没归到底层」这一刀是离散的，浓度全按距离连续算：
            //   在底 / 形的分配边界上那圈抗锯齿像素会被判给底 → 透明，
            //   量子上看不见（它们本就是几个百分点的过渡），换来的是渐变段完整保留。
            val a = if (Color.alpha(p) < MIN_SHAPE_ALPHA || layer[i] < 1) 0
            else toneAlpha(colorDistance(p, plateR, plateG, plateB) / 3, dFloor, dNear, dFar)
            output[i] = if (a == 0) 0 else Color.argb(a, 255, 255, 255)
            if (a >= TONE_FLOOR) painted++
        }
        count = painted
        val boxFill = count.toFloat() / boxTotal
        if (boxFill > MAX_SHAPE_RATIO) return Silhouette(null, boxFill, "刻出来的仍是整块底")
        if (boxFill < MIN_SHAPE_RATIO) return Silhouette(null, boxFill, "几乎刻不出东西")
        return finish(output, w, h, bounds, "三层·档距$dFloor/$dNear/$dFar·$toneNote", tones = true)
    }

    /**
     * 两支共用的收尾：形状体检（闭运算 + 去碎渣 + 三道闸）→ 成图。
     * 就地改 [output]，[pixels] 只用于日志里的口径说明。
     */
    private fun finish(
        output: IntArray,
        w: Int,
        h: Int,
        bounds: Rect,
        from: String,
        tones: Boolean = false,
    ): Silhouette {
        val boxTotal = bounds.width().toLong() * bounds.height()
        val shape = polishBinarized(output, w, h, bounds, tones)
        val filled = shape.kept.toFloat() / boxTotal
        // 闭运算 + 去碎渣之后可能把仅剩的一点杂色全擦光 —— 空掩码交出去就是一个看不见的洞，
        // 比不合成糟得多。这道守卫必须在体检之后：之前那几条量的是体检**之前**的原始计数。
        if (filled < MIN_SHAPE_RATIO) return Silhouette(null, filled, "$from：修整后几乎全空")
        if (filled >= PLATE_FILL && shape.structureRatio <= PLATE_HOLE) {
            return Silhouette(null, filled, "$from：仍是一整块板（内部结构 ${"%.2f".format(shape.structureRatio)}）")
        }
        if (shape.components >= SHATTER_COMPONENTS && shape.largestRatio <= SHATTER_TOP) {
            return Silhouette(null, filled, "$from：桥接后仍碎成 ${shape.components} 块")
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return Silhouette(
            result, filled,
            "$from ${shape.kept} 像素 cc=${shape.components} top=${"%.2f".format(shape.largestRatio)} " +
                "结构=${"%.2f".format(shape.structureRatio)}",
            tones = tones,
            structure = shape.structureRatio,
            largest = shape.largestRatio,
        )
    }

    /**
     * [polishBinarized] 的产出。
     *
     * ⚠ 这里报的是**内部结构占比** [structureRatio]，不是"空洞占比"：带三档的掩码里，
     *   字腔那部分常常是第三种颜色（点缀档），它是实心的、根本不算"洞"。只量洞的话，
     *   三层模式下这条闸门会系统性失明（真机日志里 `hole=0.00` 一片就是这个原因），
     *   「一整块板」那条闸就永远不触发。所以：**内部结构 = 封闭空洞 + 点缀档像素**。
     */
    private class Shape(
        val kept: Int,
        val components: Int,
        val largestRatio: Float,
        val structureRatio: Float,
    )

    /**
     * 二值支的后处理：**桥接 1 像素的缝 → 去碎渣 → 形状体检**，就地改 [output]。
     *
     * ## 为什么这一支需要、alpha 那一支不需要
     * alpha 支交出去的就是图标自带的 alpha —— 那是设计者画好的形状，多做一次形态学只会
     * 凭空造像素。二值支不一样：它是**按亮度 / 颜色猜**出来的形状，一刀切下去会把抗锯齿
     * 那圈中间调判给另一侧，于是笔画被切出一条 1 像素的缝、字被拆成几截。
     *
     * ## 为什么是「条件桥接」而不是闭运算
     * 一开始用的是闭运算（膨胀一次再腐蚀一次，8 邻域）。它补得掉 1 像素的缝，也补得掉
     * 2 像素的 —— 两笔之间那条本该看得见的空隙一起被填满，就是「缝隙小的部分粘连在一块」。
     * 现在只做一个动作：透明像素在**相反的两个方向上都夹着图形**才算图形。1 像素的缝接得
     * 上，2 像素的缝接不上；不膨胀所以不变粗，不腐蚀所以不削尖角。
     *
     * ## 去碎渣
     * 桥完按连通块擦掉太小的孤立点（比例见 [MIN_SPECK_DIVISOR]）—— 那些是渐变底被切出来
     * 的杂色碎屑，留着就是「脏」。多笔画图标不受影响：一笔画下来的块远大于这个尺寸。
     */
    private fun polishBinarized(output: IntArray, w: Int, h: Int, box: Rect, tones: Boolean): Shape {
        val total = w * h
        val shape = outsideBuffer(total)   // 复用规则与理由见 [classBufferCache]：整体被外层锁串起来
        val dilated = morphBuffer(total)

        val stack = stackBuffer(total)
        // ⚠ 「算不算图形」的线随掩码种类变：
        //   · 不带档的掩码里 alpha 是**源图的抗锯齿值**，必须按 [MIN_SHAPE_ALPHA] 把淡色滤掉
        //     —— 否则柔光/投影那一片淡底会整片进掩码（本项目踩过，出来就是一块色）；
        //   · 带档的掩码里 alpha 是**我们按离底板色距离算出来的斜坡**（[toneAlpha]：底板 0 →
        //     点缀档 [TONE_MID] → 主体档 255）。这时候还按 ≥128 判，等于把整条中色带都当成
        //     背景擦掉，渐变部分会重新变成"刻没了"。所以带档时线放在 [TONE_FLOOR]。
        var accentPixels = 0
        for (i in 0 until total) {
            val a = Color.alpha(output[i])
            shape[i] = if (tones) a >= TONE_FLOOR else a >= MIN_SHAPE_ALPHA
            if (shape[i] && tones && a < 255) accentPixels++
            dilated[i] = false
        }
        // ── 只补 1 像素的缝：条件桥接，不做膨胀 / 腐蚀 ──
        // 原来是闭运算（3×3 膨胀再腐蚀）。它可不止能连 1 像素 —— 两笔之间那条本该看得见
        // 的空隙一起被补满了，就是用户说的「缝隙小的部分粘连在一块」。换成条件桥接：一个
        // 透明像素，只有在**相反的两个方向上都夹着图形**（上下 / 左右 / 两条对角）才算图形。
        // 于是 1 像素的缝接得上，2 像素的缝接不上（中间那一格只有一侧是图形），也就不可能
        // 把两笔粘成一块；并且不膨胀 → 形状不变粗，不腐蚀 → 不削尖角（连"腐蚀要另开缓冲"
        // 那个坑一起省了，见下面注释里记的那次级联吃掉整张掩码）。
        shape.copyInto(dilated)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (shape[i]) continue
            val bridged = (shape[i - w] && shape[i + w]) ||
                (shape[i - 1] && shape[i + 1]) ||
                (shape[i - w - 1] && shape[i + w + 1]) ||
                (shape[i - w + 1] && shape[i + w - 1])
            if (bridged) dilated[i] = true
        }
        // 桥出来的像素**继承对面那格的档位**（取最浓的一个），不一律给实心白 ——
        // 一律 255 等于把点缀档周围桥出一圈主体档，中灰细节又被硬边吃掉。
        var kept = 0
        for (i in 0 until total) {
            if (!dilated[i]) { output[i] = 0; shape[i] = false; continue }
            if (!shape[i]) {
                val y = i / w; val x = i - y * w
                var inherit = Color.alpha(output[i - w])
                inherit = maxOf(inherit, Color.alpha(output[i + w]))
                inherit = maxOf(inherit, Color.alpha(output[i - 1]))
                inherit = maxOf(inherit, Color.alpha(output[i + 1]))
                inherit = maxOf(inherit, Color.alpha(output[i - w - 1]))
                inherit = maxOf(inherit, Color.alpha(output[i + w + 1]))
                inherit = maxOf(inherit, Color.alpha(output[i - w + 1]))
                inherit = maxOf(inherit, Color.alpha(output[i + w - 1]))
                output[i] = Color.argb(inherit, 255, 255, 255)
            }
            shape[i] = true   // 修整结果留在 shape 里：下面两遍 BFS 读它
            kept++
        }
        // ── 连通块：顺带擦掉太小的碎渣 ──
        val minSpeck = maxOf(8, total / MIN_SPECK_DIVISOR)
        val visited = dilated   // 膨胀结果已经用完，这块直接当「已访问」复用
        visited.fill(false)
        var components = 0
        var largest = 0
        for (start in 0 until total) {
            if (!shape[start] || visited[start]) continue
            components++
            var head = 0
            var tail = 0
            visited[start] = true
            stack[tail++] = start
            while (head < tail) {
                val i = stack[head++]
                val x = i % w; val y = i / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until w) continue
                        val j = ny * w + nx
                        if (shape[j] && !visited[j]) { visited[j] = true; stack[tail++] = j }
                    }
                }
            }
            if (tail >= minSpeck) {
                if (tail > largest) largest = tail
            } else {
                // 整块擦掉：stack[0 until tail] 就是这一块的坐标（队列式 BFS，弹出不清记录）
                for (k in 0 until tail) { output[stack[k]] = 0; shape[stack[k]] = false }
                kept -= tail
                components--
            }
        }
        // ── 洞：外接框内、不接触框边界的那些「非形状」连通块 ──
        var holePixels = 0
        visited.fill(false)
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val start = y * w + x
            if (shape[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var touchesBorder = false
            visited[start] = true
            stack[tail++] = start
            while (head < tail) {
                val i = stack[head++]
                val x = i % w; val yy = i / w
                if (x == box.left || x == box.right - 1 || yy == box.top || yy == box.bottom - 1) touchesBorder = true
                for (dy in -1..1) {
                    val ny = yy + dy
                    if (ny !in box.top until box.bottom) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in box.left until box.right) continue
                        val j = ny * w + nx
                        if (!shape[j] && !visited[j]) { visited[j] = true; stack[tail++] = j }
                    }
                }
            }
            if (!touchesBorder) holePixels += tail
        }
        val boxArea = box.width().toLong() * box.height()
        return Shape(
            kept = kept,
            components = components,
            largestRatio = if (kept > 0) largest.toFloat() / kept else 0f,
            structureRatio = if (boxArea > 0) (holePixels + accentPixels).toFloat() / boxArea else 0f,
        )
    }

    /** 形态学用的第三块布尔缓冲。必须与 outside / visited 分开 —— 三者同时在用。 */
    private fun morphBuffer(size: Int): BooleanArray {
        var buf = morphBufferCache
        if (buf == null || buf.size < size) {
            buf = BooleanArray(size)
            morphBufferCache = buf
        }
        return buf
    }

    /** 非透明像素的外接矩形；整张都透明时返回 null。位图版只是 [contentBoundsOf] 的薄壳。 */
    private fun contentBounds(source: Bitmap): Rect? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        return contentBoundsOf(pixels, w, h)
    }

    /**
     * 同上，但直接吃已经取好的像素数组。
     *
     * 桌面那两支手上本来就有整张 `pixels`，走位图版会白白再来一次 `getPixels`
     * —— 384² 就是 590 KB 一次的全画布拷贝，而这两支每个图标各跑一遍。
     */
    private fun contentBoundsOf(pixels: IntArray, w: Int, h: Int): Rect? {
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
     * ⚠ 桌面侧的合成**不能用这一支**：那边只读 alpha，而 alpha 在那里是**不透明度** ——
     * 把灰度写进 alpha，染出来就是同一个颜色的渐变（最暗处几乎透明），
     * 而主题图标要的是**一整块纯色**。所以桌面在「透明像素不构成背景」时走
     * [binarizeSilhouette]（二值化），也就是 [monochrome] 的 else 支，与通知同一份实现。
     * 通知侧不受这个约束：它交出去的是**完整位图**，RGB 与 alpha 各司其职。
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

    /** 形态学第二块缓冲：桥接的结果位，与 outside / visited 同时在用，理由同 [outsideBufferCache]。 */
    private var morphBufferCache: BooleanArray? = null

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
    }

    /** 像素到某个参考色的曼哈顿距离（0..765）。三通道等权，不套感知权重 —— 这里要的是"像不像"。 */
    private fun colorDistance(pixel: Int, r: Int, g: Int, b: Int): Int =
        abs(Color.red(pixel) - r) + abs(Color.green(pixel) - g) + abs(Color.blue(pixel) - b)

    /**
     * 把每个不透明像素归到三层之一：`0` = 底板、`1`/`2` = 主体与点缀；透明像素 `-1`。
     *
     * 层心的取法是**最远点采样**而不是 k-means 迭代：c0 = 逐通道中位色（底板），
     * c1 = 离 c0 最远的那个精确色（字形主体 —— 渐变底的最亮端也会落在这里，所以渐变不会
     * 骗走它），c2 = 离 {c0,c1} 都最远的那个（点缀）。然后归类一次、按类均值修正层心一次
     * （Lloyd 一步）就收 —— 图标本来就只有一层底加两笔，迭代到收敛是白烧 CPU。
     *
     * ⚠ **没有「装不进三层」这个出口**。留出口等于把「我算不出三层」当成「这张图不是三层」，
     *   渐变底会整块被判成装不下、彩色底板原样留在掩码里（上一版就是这么把「去色」做没的）。
     *   强制归类之后，底那一层必然被摘掉；真的多色照片底也不会崩，只是刻出来的东西会被
     *   [finish] 那三道形状闸门拦下。
     */
    private fun assignLayers(pixels: IntArray, plateR: Int, plateG: Int, plateB: Int): IntArray {
        val layer = IntArray(pixels.size) { -1 }
        // 层心从**量化色板**里挑，而不是从单个精确像素里挑。上一版有两处不稳：
        //   · 「离底板最远的那个像素」可以是一个孤立高饱和噪点 —— 它就能独占一层；
        //   · 更糟的是同一遍循环里 `b1` 还在长大，`b2` 却已经拿它去选最近距离了 ——
        //     于是点缀层落在哪一档取决于像素数组的遍历顺序。判据台上能看到表现：点缀档
        //     占形体的比例从 4.6%（元宝）乱跳到 29%（12306），规定的"第三种颜色"根本不稳定。
        // 现在：按 4 bit/通道统计色桶 → 只保留占 [MIN_LAYER_VOTE] 以上的桶（渐变底里散开的
        // 小桶不算一层）→ 按桶计数排序（同数按桶号，结果与像素顺序无关）→ 依次挑「离已选
        // 层心最远」的桶。
        val counts = IntArray(4096)
        val sumR = LongArray(4096); val sumG = LongArray(4096); val sumB = LongArray(4096)
        var solid = 0
        for (p in pixels) {
            if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
            solid++
            val k = (Color.red(p) shr 4 shl 8) or (Color.green(p) shr 4 shl 4) or (Color.blue(p) shr 4)
            counts[k]++
            sumR[k] += Color.red(p); sumG[k] += Color.green(p); sumB[k] += Color.blue(p)
        }
        if (solid == 0) return layer
        val candidates = ArrayList<Int>()
        for (k in counts.indices) if (counts[k] * MIN_LAYER_VOTE >= solid) candidates.add(k)
        if (candidates.isEmpty()) return layer
        candidates.sortWith(compareByDescending<Int> { counts[it] }.thenBy { it })
        val centers = IntArray(9)
        centers[0] = plateR; centers[1] = plateG; centers[2] = plateB
        var taken = 1
        while (taken < 3) {
            var bestK = -1
            var bestD = 0
            for (k in candidates) {
                val kr = (sumR[k] / counts[k]).toInt()
                val kg = (sumG[k] / counts[k]).toInt()
                val kb = (sumB[k] / counts[k]).toInt()
                var d = Int.MAX_VALUE
                for (c in 0 until taken) {
                    val distance = abs(kr - centers[c * 3]) + abs(kg - centers[c * 3 + 1]) + abs(kb - centers[c * 3 + 2])
                    if (distance < d) d = distance
                }
                if (d > bestD) { bestD = d; bestK = k }
            }
            // bestD == 0 = 所有候选桶都和已选层心同色，没有第二种颜色可分了
            if (bestK < 0 || bestD <= 0) break
            centers[taken * 3] = (sumR[bestK] / counts[bestK]).toInt()
            centers[taken * 3 + 1] = (sumG[bestK] / counts[bestK]).toInt()
            centers[taken * 3 + 2] = (sumB[bestK] / counts[bestK]).toInt()
            taken++
        }
        if (taken < 2) return layer
        val layerCount = taken
        for (pass in 0 until 2) {
            for (i in pixels.indices) {
                val p = pixels[i]
                if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
                var best = 0
                var bestD = Int.MAX_VALUE
                for (k in 0 until layerCount) {
                    val d = colorDistance(p, centers[k * 3], centers[k * 3 + 1], centers[k * 3 + 2])
                    if (d < bestD) { bestD = d; best = k }
                }
                layer[i] = best
            }
            if (pass == 1) break
            // 均值修正：层心回到该类的真实中心（色桶中心带量化误差，一步就够）
            val newR = LongArray(3); val newG = LongArray(3); val newB = LongArray(3); val n = IntArray(3)
            for (i in pixels.indices) {
                val k = layer[i]
                if (k < 0) continue
                val p = pixels[i]
                newR[k] += Color.red(p); newG[k] += Color.green(p); newB[k] += Color.blue(p); n[k]++
            }
            for (k in 0 until layerCount) if (n[k] > 0) {
                centers[k * 3] = (newR[k] / n[k]).toInt()
                centers[k * 3 + 1] = (newG[k] / n[k]).toInt()
                centers[k * 3 + 2] = (newB[k] / n[k]).toInt()
            }
        }
        return layer
    }

    /** 某一层像素到底板色的平均距离（0..255 口径，即曼哈顿距离 / 3）。该层为空时返回 0。 */
    private fun meanDistanceFromPlate(
        pixels: IntArray,
        layer: IntArray,
        which: Int,
        plateR: Int,
        plateG: Int,
        plateB: Int,
    ): Int {
        var sum = 0L
        var n = 0
        for (i in pixels.indices) {
            if (layer[i] != which || Color.alpha(pixels[i]) < MIN_SHAPE_ALPHA) continue
            sum += colorDistance(pixels[i], plateR, plateG, plateB) / 3
            n++
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /**
     * 斜坡的零点 = **形那两层自己**散到多低：非底板、且不透明的像素到底板中位色的距离
     * 直方图取 [GLYPH_FLOOR_PCT] 分位（0..255 口径）。
     *
     * 为什么不用「底板层散到多高」当零点（前一版就是这么锚的）：那样一来「与底板只差一点点」
     * 的那层形整片落在零点以内，被当底抹掉 —— 用户报的「同一图标内颜色太相近会被画成
     * 同一个颜色」就是它。是不是底由 [assignLayers] 的归层说了算，这里只问「形有多淡」。
     *
     * 取低分位而不是最小值：抗锯齿边、渐变最淡的那一头总会产出几个几乎等于底色的像素，
     * 最小值会被它们拽到 0，斜坡又变回"从零起坡"。
     */
    private fun glyphFloor(
        pixels: IntArray,
        layer: IntArray,
        plateR: Int,
        plateG: Int,
        plateB: Int,
    ): Int {
        val hist = IntArray(257)
        var n = 0
        for (i in pixels.indices) {
            if (layer[i] < 1) continue
            val p = pixels[i]
            if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
            val d = minOf(255, colorDistance(p, plateR, plateG, plateB) / 3)
            hist[d]++
            n++
        }
        if (n == 0) return 0
        val want = maxOf(1, (n.toLong() * GLYPH_FLOOR_PCT / 100).toInt())
        var acc = 0
        for (d in hist.indices) {
            acc += hist[d]
            if (acc >= want) return d
        }
        return 255
    }

    /**
     * 距离 → alpha 的分段线性映射。三个控制点：形层的距离下界 [glyphFloor] 处 0 →
     * 近底那一层处 [TONE_MID] → 远底那一层处 255，再往外饱和。
     *
     * ⚠ 零点**不是** 0：抗锯齿边与底板渐变最淡的那一头都贴着 0，从 0 起坡等于把
     *   底板尾巴整片涂成中色 —— 判据台上 steamlink 那一版框内填满 78%，就是「大色块」。
     *   但零点也**不能**摆在底板自己的色散上，那会把与底板相近的形整片抹掉
     *   （「同一图标内颜色太相近被画成同一个颜色」就是它），所以零点取 [glyphFloor]：
     *   形那两层的距离下界。是不是底不归它管，归 [assignLayers] 的归层管。
     *   近底那一层若整层落在零点以下（几乎与底同色），中档就不出现，
     *   退化成一条 0 → 255 的直线。
     *
     * 三档因此是**控制点**而不是分箱 —— 平色区域照旧平色（同色像素距离相同），
     * 渐变恢复成过渡。
     */
    private fun toneAlpha(distance: Int, dFloor: Int, dNear: Int, dFar: Int): Int {
        if (dFar <= 0) return 255
        val zero = minOf(dFloor, dFar - 1).coerceAtLeast(0)
        if (distance <= zero) return 0
        if (dNear <= zero || dNear >= dFar) {
            return if (distance >= dFar) 255 else (distance - zero) * 255 / (dFar - zero)
        }
        return when {
            distance < dNear -> (distance - zero) * TONE_MID / (dNear - zero)
            distance < dFar -> TONE_MID + (distance - dNear) * (255 - TONE_MID) / (dFar - dNear)
            else -> 255
        }
    }

    /**
     * 点缀层的平均色到「底板色 → 主体色」连线的垂直距离（RGB 欧氏空间）。
     *
     * 现在只作为**观测量**打进日志（`三层·离轴NN=混合带/独立色`）—— 它曾经在
     * plateSilhouette 里做过"共线就把这层并回主体"的判断，插值恢复之后不需要并层了：
     * 混合带在斜坡上自然穿过中点。留着这个数，是为了真机分布出来时看得懂分层结果。
     *
     * 返回 -1 = 有一层是空的（量不出来，调用方按"没有中间档"处理）。
     */
    private fun accentOffAxis(
        pixels: IntArray,
        layer: IntArray,
        accent: Int,
        plateR: Int,
        plateG: Int,
        plateB: Int,
    ): Double {
        var ar = 0L; var ag = 0L; var ab = 0L; var an = 0
        var mr = 0L; var mg = 0L; var mb = 0L; var mn = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            if (Color.alpha(p) < MIN_SHAPE_ALPHA) continue
            val l = layer[i]
            if (l == accent) {
                ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p); an++
            } else if (l >= 1) {
                mr += Color.red(p); mg += Color.green(p); mb += Color.blue(p); mn++
            }
        }
        if (an == 0 || mn == 0) return -1.0
        val vx = mr / mn - plateR; val vy = mg / mn - plateG; val vz = mb / mn - plateB
        val wx = ar / an - plateR; val wy = ag / an - plateG; val wz = ab / an - plateB
        val cx = vy.toDouble() * wz - vz.toDouble() * wy
        val cy = vz.toDouble() * wx - vx.toDouble() * wz
        val cz = vx.toDouble() * wy - vy.toDouble() * wx
        val line = sqrt((vx * vx + vy * vy + vz * vz).toDouble())
        if (line <= 0.0) return -1.0
        return sqrt(cx * cx + cy * cy + cz * cz) / line
    }

    /** 从 256 桶直方图里取中位数对应的档（0..255）。[total] 是不透明像素总数，>0。 */
    private fun medianOf(histogram: IntArray, total: Int): Int {
        val half = (total + 1) / 2
        var run = 0
        for (i in histogram.indices) {
            run += histogram[i]
            if (run >= half) return i
        }
        return 255
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
