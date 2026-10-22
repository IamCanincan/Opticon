package com.iamcanincan.opticon.notify

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.Collections
import kotlin.math.roundToInt
import com.iamcanincan.opticon.runtime.ModuleRuntime

/**
 * 通知小图标的修复主体。
 *
 * 判定顺序：
 * 1. 图标已经是单色（说明应用做过主题适配）→ 跳过；
 * 2. 其余把应用自己给的那个小图标就地压成单色剪影，交给系统按主题上色。
 */
object NotificationIconPatch {

    fun patch(sbn: StatusBarNotification, context: Context) {
        try {
            val notification = sbn.notification ?: return
            val pkg = sbn.packageName
            // 每次处理通知都取一次当前选项（内部按 TTL 重读远程配置），
            // 所以界面里改了开关后，新通知立刻按新设置处理，不需要重启
            val options = ModuleRuntime.options()
            if (!options.notifyEnabled) return

            val smallIcon = notification.smallIcon ?: return
            val beforeType = iconType(smallIcon)

            // 2) 未适配的：就地压成单色（已经单色的在 forceMonochrome 里原样放过）
            // ⚠ 这里原来还有一步「先解码一次、判是不是灰度、是就返回」，由
            //   `options.preserveTinted` 把闸 —— 而那个字段**没有任何通道会设它**，恒为真，
            //   且 forceMonochrome 里本来就有同一条 isGrayscale 检查。等于同一件事做两遍、
            //   还多解一次码。删的是重复，不是行为。
            // 只有真的换了才打日志；跳过的情况不打，免得日志里分不清「换了」和「没动」
            if (forceMonochrome(smallIcon, notification, context, pkg)) {
                ModuleRuntime.logI("patched $pkg $beforeType->${iconType(notification.smallIcon)}")
            }
            // 取框账（30 秒最多一行）：这条改动装在机器上唯一能对外的凭据就在这一行里
            IconBitmap.boundsDump()?.let { ModuleRuntime.logI(it) }
        } catch (t: Throwable) {
            ModuleRuntime.logE("patch failed", t)
        }
    }

    /**
     * 日志里显示的图标类型。
     *
     * Icon.getType() 是 API 28 才公开的，minSdk 26 下直连会 NoSuchMethodError，
     * 所以这里显式判版本。拿不到就显示 ?，只影响日志，不影响替换逻辑。
     */
    private fun iconType(icon: Icon?): String {
        if (icon == null) return "null"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "?"
        return icon.type.toString()
    }


    private fun forceMonochrome(smallIcon: Icon, notification: Notification, context: Context, pkg: String): Boolean {
        // ① **缓存**（2026-10-22 深夜）：同一条通知每次更新都会重新进来，而结果只取决于（包, 资源）。
        //   命中就把上次那个 Icon 装回去 —— 不重新解码、也不重新合成。
        val resId = runCatching { smallIcon.resId }.getOrDefault(0)
        val cacheKey = if (resId > 0) "$pkg:$resId" else null
        if (cacheKey != null) {
            val hit = shapeCache[cacheKey]
            if (hit != null) {
                cacheHits++
                reportCache()
                return IconBitmap.applySmallIcon(hit, notification)
            }
        }
        // 解不出来也要说话（每包一次）：这是本函数里**唯一没有账**的静默出口，
        //   排查"这家的通知图标为什么没变"时，没有这一行就分不清"没跑""解不出""跳过了"。
        val bitmap = IconBitmap.decode(smallIcon, context) ?: run {
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("未解码 $pkg", true) == null)
                ModuleRuntime.logI("通知尺 未解码 首见 $pkg")
            return false
        }
        cacheMisses++
        // ② **源退化必须在任何形状处理之前判**（2026-10-22 深夜改回）：1×1 的源被 decode 撑成整格后，
        //   颜色可能是彩的 —— 把它放进"彩色"分支就等于这一族没有兜底，屏幕上是整块实心色块。
        //   ⚠ 判据必须在**撑之前**取：`decode` 已经把 1×1 画成 96×96 了，事后量尺寸就全是一样大。
        val srcEdge = IconBitmap.resourceEdge(context, smallIcon)
        if (srcEdge in 0..2 || bitmap.width <= 2 || bitmap.height <= 2) {
            val app = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
            if (app == null) { noteRuler(pkg, "退化无兜底", bitmap, null); return false }
            val fromApp = IconBitmap.rasterize(app)
            val monoApp = if (ToneCheck.isGrayscale(fromApp)) fromApp else IconBitmap.monochrome(fromApp)
            if (monoApp == null) { noteRuler(pkg, "退化无兜底", bitmap, null); return false }
            val shapedApp = IconBitmap.fitInk(monoApp, SIZE_TARGET) ?: monoApp
            val icon = Icon.createWithBitmap(shapedApp)
            if (cacheKey != null) shapeCache[cacheKey] = icon
            val ok = IconBitmap.applySmallIcon(icon, notification)
            if (ok) noteRuler(pkg, "代用应用图标", bitmap, shapedApp)
            return ok
        }
        // ③ **字带对彩色图标同样生效**（2026-10-22 深夜改回）：旧流程把 `stripped` 当成
        //   `monochrome` 的输入，彩色图标上的字带一样会被去掉；"彩色反正要走 monochrome"这句
        //   只说明颜色会被重画，不说明字带会消失 —— 挪到颜色判断之后是可见行为变化，不是优化。
        //   真正的热路径优化是第 ① 步的缓存：同一个（包, 资源）只扫这一次。
        val stripped = IconBitmap.stripTextBand(bitmap)
        if (stripped == null && ToneCheck.isGrayscale(bitmap)) {
            // 诊断（每包一次）：把"字带判据"看到的几何打出来 —— 不打印就只能猜它为什么没命中。
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("带诊断 $pkg", true) == null)
                ModuleRuntime.logI("通知带诊断 $pkg ${IconBitmap.bandDebug(bitmap)}" +
                    " ｜icon type=${iconType(smallIcon)} resId=$resId" +
                    " resPkg=${runCatching { smallIcon.resPackage }.getOrNull()}" +
                    " ｜drawable=${runCatching { IconBitmap.describeResource(context, smallIcon) }.getOrNull()}")
            noteRuler(pkg, "已适配", bitmap, null)
            return false
        }
        val base = stripped ?: bitmap
        // 压不出像样的形状（全空/全满）就保持原样，别交一张更糟的图出去
        val monochrome = if (ToneCheck.isGrayscale(base)) base else IconBitmap.monochrome(base)
        if (monochrome == null) {
            noteRuler(pkg, "放弃", bitmap, null)
            return false
        }
        val shaped = IconBitmap.fitInk(monochrome, SIZE_TARGET) ?: monochrome
        val icon = Icon.createWithBitmap(shaped)
        if (cacheKey != null) shapeCache[cacheKey] = icon
        val applied = IconBitmap.applySmallIcon(icon, notification)
        if (applied) noteRuler(pkg, if (stripped != null) "清理字带" else "被补", bitmap, shaped)
        return applied
    }

    /**
     * 形状缓存：键 = `包:资源id`（只对 RESOURCE 图标生效）；上限 32 个 Icon（96² 约 36 KB/个）。
     *
     * ⚠ 必须同步：`IconBitmap.monochrome` 的注释里记着 SystemUI 的通知处理会并发进来，
     *   而 access-order 的 `LinkedHashMap.get` 本身会改链表。计数与"60 秒一行"只影响诊断，
     *   但缓存结构被并发改坏会直接把通知路径打崩，所以整张表挂在一把锁上。
     *
     * 已知边界：键不含应用版本。应用更新后若复用同一个资源 id 而图变了，SystemUI 不重启
     *   就会一直用旧图 —— 重启后自然消失；这条代价换的是"同一条通知每次刷新都不再重解码"。
     */
    private val shapeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Icon>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?): Boolean = size > 32
        }
    )
    private var cacheHits = 0
    private var cacheMisses = 0
    private var lastCacheReport = 0L

    /** 缓存命中/未中的账：60 秒最多一行 —— 没有它，"优化了"与"没优化"在屏幕上分不出。 */
    private fun reportCache() {
        val now = System.currentTimeMillis()
        if (now - lastCacheReport < 60_000) return
        lastCacheReport = now
        ModuleRuntime.logI("通知补丁 缓存 命中=$cacheHits 未中=$cacheMisses 在册=${shapeCache.size}")
    }

    /**
     * 大小尺的账：**"强制适配的那批要和已适配的那批一样大"这句话，缺的就是这两列的分布**。
     * 每一行 = 一个源形状（按 `族 + 源尺寸 + 尺` 去重，最多 200 行，之后不再扫像素）：
     *   `通知尺 已适配 源 24x24 尺=79%`  /  `通知尺 被补 源 48x48 尺=100% → 出 96x96 尺=92%`
     * 尺 = 墨外接框长边 ÷ 画布长边（槽位是 22dp 的 fitCenter，所以观感大小就是这个比值）。
     * 消息一次成型：**不拆成"前段" + "后段 %.2f".format(…)** —— 那样占位符会整体错位（本仓记过一次）。
     */
    private fun noteRuler(pkg: String, kind: String, src: android.graphics.Bitmap, out: android.graphics.Bitmap?) {
        if (rulerSeen.size >= RULER_MAX) return
        // ⚠ **键里必须带包名**（2026-10-22 现撞）：旧键是 `kind + 尺寸`，于是"已适配 96x96"被第一家的
        //   那一行占住，后面**所有**同尺寸的应用都不再打印 —— 排查 flyingbirdlite 时账里根本没有它，
        //   这一位就成了"看不出这家我们做了什么"。按包名记，每家一行。
        val key = kind + " " + pkg + " " + src.width + "x" + src.height
        if (rulerSeen.putIfAbsent(key, true) != null) return
        val inPct = (IconBitmap.sizeRuler(src) * 100).roundToInt()
        val tail = if (out == null) "" else
            " → 出 " + out.width + "x" + out.height + " 尺=" + (IconBitmap.sizeRuler(out) * 100).roundToInt() + "%"
        ModuleRuntime.logI("通知尺 $kind 源 ${src.width}x${src.height} 尺=$inPct%$tail 首见 $pkg")
    }

    /** 账的上限：这一族不该长成一本无限账（超出后停止扫描，也就不再印）。 */
    private const val RULER_MAX = 200

    /**
     * 「被补」那一族归一后的目标尺（墨框长边 / 画布长边）。
     *
     * 0.95 的来历：框架 `stat_*` 那 100 张（= 应用自己做好、系统直接用的那一族）尺分布
     * **0.403–1.000、中位 0.949**。取中位而不是"占满整格"：参照族自己就跨 2.5×，
     * 推到 1.0 会落到它的上边缘之外。已适配那一族**不经过**这个常数（见调用处）。
     */
    private const val SIZE_TARGET = 0.95f
    private val rulerSeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
}
