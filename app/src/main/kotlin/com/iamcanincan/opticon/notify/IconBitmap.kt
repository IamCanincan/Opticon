package com.iamcanincan.opticon.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import kotlin.math.roundToInt
import com.iamcanincan.opticon.circle.uncroppedIcon
import com.iamcanincan.opticon.runtime.MemberLookup


/**
 * 图标位图的取与造 —— **通知那半边**。
 *
 * 这里只做像素层面的事：把各种形态的来源（Drawable / 资源 id / Icon）画成位图，
 * 以及把没适配的压成单色剪影。是否该替换由上层决定。
 *
 * ⚠ 桌面的主题图标**不从这里取形状**了：那一套在 `circle/MonochromeMask.kt`。
 * 两边以前共用 `alphaSilhouette` / `binarizeSilhouette` / `plateSilhouette` 三个入口，
 * 但它们的约束根本不是一条 —— 通知交出去的是完整位图（RGB + alpha），桌面交出去的只有一条
 * alpha。共用三个入口的代价是每条判据都要"对两边都成立"，最后变成两边都不准。现在各自一份。
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
     * 压出来的形状占整张画布的比例，低于下限（几乎全空）或高于上限（几乎全满）都算失败。
     *
     * 状态栏只认 alpha，交出去一张空图就是一个看不见的洞，交出去一张满图就是一个纯色块，
     * 两者都比原图标更糟。这种情况宁可放弃替换、保持原样。
     *
     * ## 这里是**画布**口径，桌面那一侧是**外接框**口径 —— 不要"统一"掉
     * 桌面那一侧不能把内容放大填满画布（坐标系要对齐 108dp），内容只落在画布中央约 47%，
     * 所以它必须按外接框量 —— 否则「整个框被填满」这种最坏的掩码按画布只量到 0.47，
     * 看着完全健康却一路放行（真机那个 `out=0.47 cc=1 top=1.00` 的白块就是这么过的）。
     * 那一条判据现在整个住在 `circle/MonochromeMask.kt` 里，两边各自持有一个数，
     * 改之前先确认自己改的是哪一侧。
     */
    private const val MIN_SHAPE_RATIO = 0.02f
    private const val MAX_SHAPE_RATIO = 0.85f

    /** 二值化时每个像素的归类，用于连通性判断 */
    private const val CLASS_TRANSPARENT = 0
    private const val CLASS_DARK = 1
    private const val CLASS_LIGHT = 2

    /**
     * 把 Drawable 画成指定边长的方形位图。
     *
     * 关键点：显式调用 setBounds 而不是依赖 intrinsicWidth/Height —— 自适应图标
     * （AdaptiveIconDrawable）的固有尺寸常常是 -1，依赖它就会算出非法尺寸、整条替换链路静默失效。
     * ⚠ 但**非方形有固有尺寸时不能拉成正方**：那一步由 [iconBounds] 等比 fit + 居中，
     *   见那里的现场读数（框架自己 91 张状态栏 PNG 里 53 张是非方形）。
     */
    fun rasterize(drawable: Drawable, size: Int = ICON_EDGE): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val b = iconBounds(iw, ih, size)
        noteBounds(iw, ih)
        drawable.setBounds(b[0], b[1], b[2], b[3])
        drawable.draw(canvas)
        return bmp
    }

    /**
     * 取框账（2026-10-21）：**没有它，这条改动装在机器上也无法证明** —— `iconBounds` 是纯函数，
     * R8 会把名字改掉，dex 里也没有旧字面量可对。这里只统计**本进程**：一共取了几次框、
     * 其中多少种源是非方形（非方形 = 以前会被拉成正方、现在等比 fit 的那一批）。
     *
     * ⚠ 这一份文件**不打日志**：判据台（`_harness/build.sh`）编的就是它，而它的源码清单里没有
     *   `runtime/ModuleRuntime` —— 在这里调日志等于把性质台编译打断。所以这里只**造句子**，
     *   由调用方（`SystemUiHooks`，那里才有日志通道）决定要不要印。
     * @return 该印的时候返回一行文本，不该印的时候返回 null（30 秒一行，且计数没动也不印）。
     */
    fun boundsDump(): String? {
        val n = boundsTotal.get()
        if (n == 0L || n == lastDumpCount) return null
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < 30_000L && lastDumpAt != 0L) return null
        lastDumpAt = now; lastDumpCount = n
        val kinds = stretchedKinds.size
        return "通知取框 累计 $n 次，非方形源 $kinds 种" +
          (if (kinds == 0) "" else "（${stretchedKinds.keys.joinToString(" ")}）")
    }

    /** 每取一次框记一笔（[rasterize] 调）。非方形按 `宽x高` 去重，量的是"几种形状"。 */
    private fun noteBounds(iw: Int, ih: Int) {
        boundsTotal.incrementAndGet()
        if (iw > 0 && ih > 0 && iw != ih) stretchedKinds.put("${iw}x${ih}", true)
    }

    private val boundsTotal = java.util.concurrent.atomic.AtomicLong()
    private val stretchedKinds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastDumpAt = 0L
    @Volatile private var lastDumpCount = 0L

    /**
     * 一张通知小图标该画在方形画布里的哪个框 —— `[left, top, right, bottom]`。
     *
     * 为什么这不是"顺手美化"：**状态栏那个槽位是固定的**（`dimen/status_bar_icon_size` 默认档 22dp，
     * 本机 630dpi ⇒ ≈87px），而槽位是 `fitCenter` ⇒ 交出去的位图是 48 还是 96 会被同一个系数吃掉，
     * **唯独"拉成正方形"吃不掉**：一条 20×42 的窄图形被拉到 96×96，再等比塞进槽位，
     * 它就占满了整格 —— 观感就是"这个图标比别的大一圈、还被拉胖了"。
     *
     * 射程是量的，不是猜的（2026-10-21，`_noticink.cjs` 摊 framework-res 里的 `stat_*`）：
     * **91 张 PNG 里 53 张画布非方形**（20×42 / 39×51 / 22×38 这类），矢量那 9 张只有 1 张非方形。
     * 而本文件 [boundToIconEdge] 的注释早就写过「等比缩放，不是拉成正方形：把非方形图标拉变形
     * 等于改了它的形状」—— 那一条只管位图载体，资源那条路一直在反着做。
     *
     * 方形（`iw == ih`）与**没有固有尺寸**（`<= 0`，AdaptiveIconDrawable 那类）两种情况
     * 原样交回整幅 ⇒ 这一改对它们**一个像素都不动**，射程只在非方形那一批。
     */
    internal fun iconBounds(iw: Int, ih: Int, size: Int): IntArray {
        if (iw <= 0 || ih <= 0 || iw == ih) return intArrayOf(0, 0, size, size)
        val scale = size.toFloat() / maxOf(iw, ih)
        val w = maxOf(1, (iw * scale).roundToInt())
        val h = maxOf(1, (ih * scale).roundToInt())
        val left = (size - w) / 2
        val top = (size - h) / 2
        return intArrayOf(left, top, left + w, top + h)
    }

    /** TYPE_RESOURCE 类型的 Icon 需要用正确的 Resources 解码，不能走 Drawable 兜底 */
    fun fromResources(context: Context, resId: Int): Bitmap {
        val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
        // SystemUI 里的 Resources 出口也被裁圆钩子挂着：拿裁过的圆去压剪影会变成一块饼。
        return if (drawable != null) rasterize(uncroppedIcon(drawable))
          else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
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
     * 只有**不透明像素不到七成**时才走 alpha（= 透明像素多于三成）—— 图形大到把画布铺满
     * 七成以上时，alpha 描述的是整块底板的轮廓，照搬会压出一个白方块。详见 [binarize]。
     *
     * ⚠ 这一条以前写的是「透明像素 > 总数/20 **且** 不透明不到七成」—— 前半句是**恒真的死条件**
     *   （不到七成 ⟺ 透明多于 30%，而 30% 永远大于 5%），留着它只会让「透明下限是 5%」这句话
     *   看着像真的。真正在管这件事的只有 [SOLID_SHAPE_LIMIT] 一个数（2026-09-30 复核）。
     *   本 KDoc 那句「透明像素占多数」也是那时候留下的旧口径，一并改成实际判据。
     *
     * 压不出像样的形状时返回 null，由上层保持原样 —— 详见 [MIN_SHAPE_RATIO]。
     *
     * ⚠ **「哪些像素算图形」的判定与「写出去的是什么」必须是同一口径**：判定一律是
     *   `alpha >= MIN_SHAPE_ALPHA`（[binarize] 那边同理，判据是 `class != CLASS_TRANSPARENT`）。
     *   两件事一旦混在一起，掩码的真实覆盖率就会和返回的 `shapeCount` 差一个数量级，
     *   而调用方正是拿 `shapeCount` 在判「压出来的形状合不合理」—— 判据和产物对不上，等于没判。
     *   曾经这里有一个 `preserveAlpha` 参数（保留抗锯齿 alpha、不二值化），是给桌面主题图标
     *   用的；那条路换成 [MonochromeMask] 之后没人传 true 了，参数与它的分支一起删（2026-09-30）。
     *
     * ⚠ 带 @Synchronized：下面那四块复用缓冲区是进程级可变状态，而 SystemUI 的通知处理
     * 会并发进来（binarize → keepDarkSide → outsideMask / borderConnectedRatio 一路都在用）。
     * 本函数是它们唯一的入口，锁在这一层就锁住了全部四块缓冲，
     * 与 [ToneCheck.isGrayscale] 用的是同一套做法（详见 [classBufferCache] 的说明）。
     * 其余函数（[rasterize] / [decode] / [fromResources]）不共任何状态，不用跟着上锁。
     */
    @Synchronized
    fun monochrome(input: Bitmap): Bitmap? {
        // ⚠ 先按 [ICON_EDGE] 归一，**再**开数组 —— 下面所有按面积走的成本（两块 w×h 的
        //   IntArray、Otsu、连通性洪泛，以及那四块 grow-only 缓冲）都直接等于这个尺寸。
        //   不归一的话这里是没有上界的：`decode` 只有 TYPE_RESOURCE 走 rasterize（= 96），
        //   TYPE_BITMAP / TYPE_DATA / TYPE_URI 三条都把**原尺寸**位图直接递进来。
        val src = boundToIconEdge(input)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val outputPixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var transparentCount = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) transparentCount++
        }

        val total = pixels.size
        val solidRatio = (total - transparentCount).toFloat() / total
        val useAlpha = solidRatio < SOLID_SHAPE_LIMIT
        val shapeCount = if (useAlpha) {
            // 透明底上的图形：alpha 本身就是最准的轮廓
            alphaShape(pixels, outputPixels)
        } else {
            // 实心图标：alpha 只描述外轮廓，照搬就是一坨白块，改用二值化挖图形
            binarize(pixels, outputPixels, w, h)
        }

        val shapeRatio = shapeCount.toFloat() / total
        if (shapeRatio < MIN_SHAPE_RATIO || shapeRatio > MAX_SHAPE_RATIO) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 把要分析的位图压到 [ICON_EDGE] 以内（长边对齐），已经够小就**原样返回**。
     *
     * 为什么需要这一步：`decode` 的几条载体的尺寸**不受我们控制** ——
     * TYPE_RESOURCE 走 [rasterize]，尺寸由我们定；而 TYPE_BITMAP（应用直接塞进来的位图）、
     * TYPE_DATA / TYPE_URI（整张 PNG 全尺寸解码）交上来的就是原始尺寸。而这里往下的每一笔
     * 开销都按面积算：两块 `w×h` 的 IntArray、Otsu 的整张直方图、两次连通性洪泛，**外加
     * 那四块 grow-only 缓冲会把面积永久留在 SystemUI 里**（10 B/像素，见 [classBufferCache]）。
     * 一张 4000×3000 的小图标就是 ~120MB 常驻 + 两次 ~48MB 临时分配，而且全发生在通知
     * 刷新那条线程上 —— 那是 OOM / ANR 的量级，不是"慢一点"。
     *
     * ⚠ 等比缩放，不是拉成正方形：状态栏只按 alpha 取形，把非方形图标拉变形等于改了它的形状。
     * ⚠ **只有超限才缩放**，常规尺寸（含 [rasterize] 出来的 96）一个像素都不动 —— 这条改动
     *   对既有行为是零影响，它是给那三条没有上界的载体兜底。
     */
    private fun boundToIconEdge(input: Bitmap): Bitmap {
        val longEdge = maxOf(input.width, input.height)
        if (longEdge <= ICON_EDGE) return input
        val scale = ICON_EDGE.toFloat() / longEdge
        val w = maxOf(1, (input.width * scale).roundToInt())
        val h = maxOf(1, (input.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(input, w, h, true)
    }

    /**
     * [monochrome] 的 **alpha 那一支**：透明底上的图形，轮廓就是它自己的 alpha。
     *
     * ⚠ 这里曾经写着「单独抽出来是因为桌面侧也要走同一份」—— 那句话**已经不成立了**：
     *   桌面主题图标那条路整个搬进了 `circle/MonochromeMask.kt`，它有自己的 `A_SOLID` 与
     *   自己的形状模型（见本文件头 23-27 行）。两边现在是各自一份，别再按"共用"去改它。
     *
     * 判据只有一条：**够不够实**（`alpha >= MIN_SHAPE_ALPHA`）。绝不能写成「有没有 alpha」——
     * 桌面图标的前景层常常自带一层很淡的柔光 / 投影 / 半透明底板
     * （alpha 1 ~ MIN_SHAPE_ALPHA-1，实测能占到画布的 60% 以上）。把它们一并收进掩码，
     * 掩码的真实覆盖率会是这里数出来的十几倍 —— 上色之后整块图标被一层淡色铺满，
     * 看上去就是一个纯色块。
     *
     * 曾经这条分支写成 `alpha > 0 && (...)` —— 判据用 128、写出去的从 1 起步，两边口径不一致，
     * 就是这么踩进去的。
     *
     * @return 涂了多少个像素（调用方拿它在判「压出来的形状合不合理」）
     */
    private fun alphaShape(pixels: IntArray, outputPixels: IntArray): Int {
        var count = 0
        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            if (alpha < MIN_SHAPE_ALPHA) continue
            count++
            outputPixels[i] = Color.WHITE
        }
        return count
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
                output[i] = Color.WHITE
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
                else -> icon.loadDrawable(context)?.let { rasterize(uncroppedIcon(it)) }
            }
        } else {
            icon.loadDrawable(context)?.let { rasterize(uncroppedIcon(it)) }
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
     * 量一把这张小图标有多"大"：**墨外接框的长边 ÷ 画布长边**（α>0 算墨），返回 0..1。
     *
     * 为什么按长边、不按面积：状态栏槽位是固定 22dp 的 `fitCenter`，等比缩放后**长边贴边**，
     * 所以观感大小就是这个比值（面积会把"窄"和"小"混成一个数 —— 本仓那条"混了相反两方向的总量会被一头换一头骗过去"）。
     *
     * 只在**去重后的每一种新形状**上算一次（调用方负责去重）：这一趟是 `w×h ≤ 9216` 格的一次扫描，
     * 而通知刷新在 SystemUI 主线程上，不能每张都扫。
     * 整张透明返回 `0.0`（那种交给第八闸盯着，这里不另立一本账）。
     */
    fun sizeRuler(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0.0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in px.indices) {
            if ((px[i] ushr 24) == 0) continue
            val x = i % w; val y = i / w
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
        }
        if (x1 < 0) return 0.0
        return maxOf(x1 - x0 + 1, y1 - y0 + 1).toDouble() / maxOf(w, h)
    }

    /**
     * 去掉"文字带"：主体之外那一条**横贯整幅、但列方向有缝**的细带 ——
     * 典型就是图标下面那行字（`flyingbirdlite` 的 「FLYINGBIRD」）。留着的后果不是"变小"，
     * 而是**在 22dp 上糊成一条灰带**（实测把它的 96² 缩到 22px 看过）。
     *
     * 判据（2026-10-22 对着真图量的，96² 上）：字带占 **第 74~83 行**、横跨 x=1..95、
     * 但每行只有 ~31 格有墨 ⇒ **列覆盖 ≈ 0.33**（字与字之间有空）。而"实心下划线"列覆盖是 1.0 ——
     * 这就是下载/上传那两帧不被误伤的那道分界（它们就是"箭头 + 一条实线"）。
     * `≥3 块` 那种写法在真机解出来的位图上会**漏**（字母挨在一起就成了一块），所以改判"几何 + 列覆盖"。
     * 框架 91 张 `stat_*` 在这条判据下 **0 命中**。
     *
     * 命中 ⇒ 返回去掉那几行的**副本**；没命中 ⇒ `null`（调用方按原路走，一个像素都不动）。
     */
    fun stripTextBand(input: Bitmap): Bitmap? {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0 || w * h > (1 shl 16)) return null
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        val on = BooleanArray(w * h) { ((px[it] ushr 24) and 0xff) >= MIN_SHAPE_ALPHA }
        // ① 主块（最大连通块）的行范围 —— 只要 top/bot，不必收格子
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var mainArea = 0; var mainTop = 0; var mainBot = 0
        for (start in 0 until w * h) {
            if (!on[start] || seen[start]) continue
            var sp = 0; var n = 0; var top = h; var bot = -1
            stack[sp++] = start; seen[start] = true
            while (sp > 0) {
                val c = stack[--sp]
                n++
                val y = c / w
                if (y < top) top = y
                if (y > bot) bot = y
                val x = c - y * w
                if (x > 0 && on[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
                if (x < w - 1 && on[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
                if (y > 0 && on[c - w] && !seen[c - w]) { seen[c - w] = true; stack[sp++] = c - w }
                if (y < h - 1 && on[c + w] && !seen[c + w]) { seen[c + w] = true; stack[sp++] = c + w }
            }
            if (n > mainArea) { mainArea = n; mainTop = top; mainBot = bot }
        }
        if (mainArea == 0) return null
        // ② 主块**之外**的行：取上、下两侧里"有墨的那一段"，各按判据试一次
        val maxBandH = maxOf(2, (mainBot - mainTop + 1) * 30 / 100)
        for (side in 0..1) {
            val y0 = if (side == 0) 0 else mainBot + 1
            val y1 = if (side == 0) mainTop - 1 else h - 1
            if (y1 < y0) continue
            var r0 = -1; var r1 = -1; var minX = w; var maxX = -1; var area = 0
            for (y in y0..y1) for (x in 0 until w) {
                if (!on[y * w + x]) continue
                area++
                if (r0 < 0) r0 = y
                r1 = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
            if (area == 0) continue
            val bandH = r1 - r0 + 1
            val bandW = maxX - minX + 1
            if (bandH > maxBandH) continue
            if (bandW * 100 < w * 60) continue              // 字横贯整幅；短横线不算
            // ⚠ **墨密度**而不是"列覆盖"：真机解出来的位图经过缩放 + 抗锯齿，字与字之间的缝会被糊平，
            //   列覆盖会一路涨到接近实线（2026-10-22 现撞：改成密度前这条在真机上**一次都没命中**）。
            //   密度对糊更钝：字 ≈0.33、实线下划线 ≈1.0。
            if (area * 100 > bandW * bandH * 60) continue    // 太实 = 是下划线，不是字
            if (area * 100 < (mainArea + area) * 5) continue  // 太小 = 噪点，不动
            if (area * 100 > mainArea * 150) continue         // 比主体还大 = 这不是"字带"
            val out = px.copyOf()
            for (y in r0..r1) for (x in 0 until w) out[y * w + x] = 0
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(out, 0, w, 0, 0, w, h)
            return result
        }
        return null
    }

    /** 诊断用：这条资源解出来的 drawable **是什么类、多大**（一句话，不含像素）。 */
    fun describeResource(context: Context, icon: Icon): String = runCatching {
        // `Icon.getResId` 是 API 28+；26/27 上返回"不可用"，别让诊断本身崩在旧机上。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runCatching "api<28"
        val d = ResourcesCompat.getDrawable(context.resources, icon.resId, null)
        "${d?.javaClass?.simpleName} ${d?.intrinsicWidth}x${d?.intrinsicHeight}"
    }.getOrElse { "err:${it.message}" }

    /**
     * 这条资源解出来的 drawable 的**最小固有边**（px）；解不出返回 -1。
     *
     * 为什么需要：有的应用把通知小图标交成 **1×1**（`flyingbirdlite` 现量：`drawable/ic` 在这个
     * 包上下文里解出来就是 1×1）。`rasterize` 会把它撑满整格 ⇒ 屏幕上是一整块实心方块。
     * **必须在撑之前判**——撑完就只剩一张 96×96、信息已经没了。
     */
    fun resourceEdge(context: Context, icon: Icon): Int = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runCatching -1
        val d = ResourcesCompat.getDrawable(context.resources, icon.resId, null) ?: return -1
        minOf(d.intrinsicWidth, d.intrinsicHeight)
    }.getOrDefault(-1)

    /** 诊断用：把 [stripTextBand] 看到的几何打出来（一条字符串），只在"已适配"那一支按包名打一次。 */
    fun bandDebug(input: Bitmap): String {
        val w = input.width; val h = input.height
        if (w <= 0 || h <= 0 || w * h > (1 shl 16)) return "size ${w}x$h"
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        val on = BooleanArray(w * h) { ((px[it] ushr 24) and 0xff) >= MIN_SHAPE_ALPHA }
        val seen = BooleanArray(w * h); val stack = IntArray(w * h)
        var mainArea = 0; var mainTop = 0; var mainBot = 0; var comps = 0
        for (start in 0 until w * h) {
            if (!on[start] || seen[start]) continue
            comps++
            var sp = 0; var n = 0; var top = h; var bot = -1
            stack[sp++] = start; seen[start] = true
            while (sp > 0) {
                val c = stack[--sp]; n++
                val y = c / w
                if (y < top) top = y
                if (y > bot) bot = y
                val x = c - y * w
                if (x > 0 && on[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
                if (x < w - 1 && on[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
                if (y > 0 && on[c - w] && !seen[c - w]) { seen[c - w] = true; stack[sp++] = c - w }
                if (y < h - 1 && on[c + w] && !seen[c + w]) { seen[c + w] = true; stack[sp++] = c + w }
            }
            if (n > mainArea) { mainArea = n; mainTop = top; mainBot = bot }
        }
        val below0 = mainBot + 1
        var a = 0; var r0 = -1; var r1 = -1; var mnx = w; var mxx = -1
        for (y in below0 until h) for (x in 0 until w) if (on[y * w + x]) {
            a++; if (r0 < 0) r0 = y; r1 = y
            if (x < mnx) mnx = x; if (x > mxx) mxx = x
        }
        val bw = if (mxx >= 0) mxx - mnx + 1 else 0
        val bh = if (r1 >= 0) r1 - r0 + 1 else 0
        val dens = if (bw > 0 && bh > 0) a.toDouble() / (bw * bh) else 0.0
        return "size=${w}x$h comps=$comps main=${mainArea}@${mainTop}..${mainBot} 下带 area=$a ${bw}x${bh} 密度=${
            "%.2f".format(dens)} 宽占比=${"%.2f".format(if (w > 0) bw.toDouble() / w else 0.0)}"
    }

    /**
     * 把**墨的外接框长边**缩到 `target × 画布长边`，等比、居中，画布边长保持 `max(w,h)`（方形）。
     *
     * 为什么需要它：槽位是 22dp 的 `fitCenter`，所以观感大小 = [sizeRuler] 那个比值。
     *   参照族（应用自己做好 / 框架 `stat_*`）那 100 张的尺分布是 **0.40–1.00、中位 0.949** ——
     *   "看着差不多大"只能按**中位**对，不能按"占满整格"对（那会推到参照的上边缘之外）。
     *   而这一刀**只给"被补"那一族用**：应用自己已经是单色的（`已适配`）一个像素都不许动。
     *
     * 幂等性：尺已经等于 target 的输入会原样返回（`scale≈1` 时直接返回输入，不重画）。
     * 整张透明 / 尺寸非法 → `null`（调用方按"放弃"处理，别交一张空图出去）。
     */
    fun fitInk(input: Bitmap, target: Float): Bitmap? {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0 || target <= 0f) return null
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in px.indices) {
            if ((px[i] ushr 24) == 0) continue
            val x = i % w; val y = i / w
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val side = maxOf(w, h)
        val scale = (target * side) / maxOf(bw, bh)
        // 已经落在目标上（±1 格）就别重画：省一次分配，也让"重复处理同一张"逐字节相同。
        if (kotlin.math.abs(scale - 1f) <= 1f / maxOf(bw, bh)) return input
        val dw = maxOf(1, (bw * scale).roundToInt())
        val dh = maxOf(1, (bh * scale).roundToInt())
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dx = (side - dw) / 2
        val dy = (side - dh) / 2
        canvas.drawBitmap(input, Rect(x0, y0, x1 + 1, y1 + 1), Rect(dx, dy, dx + dw, dy + dh), paint)
        return out
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
    fun applySmallIcon(icon: Icon, notification: Notification): Boolean {
        val field = smallIconField ?: return false
        return MemberLookup.writeField(notification, field, icon)
    }

    /**
     * `Notification.mSmallIcon` 的 [java.lang.reflect.Field]，**第一次调用时解析一次**。
     *
     * 这条在每条通知（含缓存命中）上都要跑；按名现查每次都要 `getDeclaredField`，
     * 正是 `MemberLookup` 类文档里说的「两套入口」该走快的那一套。
     * 解析失败（某些 ROM 改过这个字段）⇒ 返回 false，上层照旧不报 `patched`。
     */
    private val smallIconField: java.lang.reflect.Field? by lazy {
        MemberLookup.findField(Notification::class.java, "mSmallIcon")
    }

}
