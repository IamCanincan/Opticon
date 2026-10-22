package com.iamcanincan.opticon.notify

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
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
        // 解不出来也要说话（每包一次）：这是本函数里**唯一没有账**的静默出口，
        //   排查"这家的通知图标为什么没变"时，没有这一行就分不清"没跑""解不出""跳过了"。
        val bitmap = IconBitmap.decode(smallIcon, context) ?: run {
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("未解码 $pkg", true) == null)
                ModuleRuntime.logI("通知尺 未解码 首见 $pkg")
            return false
        }
        // 已经是单色的就不用再压一遍 —— **但先看有没有"文字带"**（2026-10-22）：
        //   flyingbirdlite 的小图标是"鸟 + 一行 FLYINGBIRD"，纯灰度 ⇒ 被当成"已适配"放过，
        //   而那一行字在 22dp 上糊成一条灰带（主人报的"空出一块/一个小白点"）。
        //   判据射程量过：框架 91 张 stat_* 一张都不命中，所以只在这一种形状上动手。
        val stripped = IconBitmap.stripTextBand(bitmap)
        // 🔴 **源退化**（2026-10-22，flyingbirdlite 现量）：有的应用把通知小图标交成 **1×1** 的位图，
        //   系统把它撑满整格 ⇒ 屏幕上是一整块实心方块（主人报的"空出一块"）。这种源怎么处理都是块，
        //   所以改用**这个应用自己的图标**顶上（单色化 + 归一到 0.95）。
        //   ⚠ 判据必须在**撑之前**取：`decode` 已经把 1×1 画成 96×96 了，事后量尺寸就全是一样大。
        val srcEdge = IconBitmap.resourceEdge(context, smallIcon)
        if (bitmap.width <= 2 || bitmap.height <= 2 || (srcEdge in 0..2)) {
            val app = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
            if (app == null) {
                noteRuler(pkg, "退化无兜底", bitmap, null)
                return false
            }
            val fromApp = IconBitmap.rasterize(app)
            val monoApp = if (ToneCheck.isGrayscale(fromApp)) fromApp else IconBitmap.monochrome(fromApp)
            if (monoApp == null) {
                noteRuler(pkg, "退化无兜底", bitmap, null)
                return false
            }
            val shapedApp = IconBitmap.fitInk(monoApp, SIZE_TARGET) ?: monoApp
            val okApp = IconBitmap.applySmallIcon(Icon.createWithBitmap(shapedApp), notification)
            if (okApp) noteRuler(pkg, "代用应用图标", bitmap, shapedApp)
            return okApp
        }
        if (stripped == null && ToneCheck.isGrayscale(bitmap)) {
            // 诊断（每包一次）：把"字带判据"看到的几何打出来 —— 不打印就只能猜它为什么没命中。
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("带诊断 $pkg", true) == null)
                ModuleRuntime.logI("通知带诊断 $pkg ${IconBitmap.bandDebug(bitmap)}" +
                    " ｜icon type=${iconType(smallIcon)} resId=${runCatching { smallIcon.resId }.getOrDefault(-1)}" +
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
        // **大小归一（2026-10-22，主人"通知小图标的大小还是不太对"+"全都做了"）**：
        //   只动"被补"这一族 —— 墨框长边缩到 [SIZE_TARGET]×画布、居中、等比。
        //   目标 0.95 的来历：框架 `stat_*` 那 100 张的尺分布中位是 **0.949**（跨 0.40–1.00），
        //   "和已适配的看着差不多大"只能按中位对。**已适配的被调用方提前 return 了，碰不到这里。**
        val shaped = IconBitmap.fitInk(monochrome, SIZE_TARGET) ?: monochrome
        val applied = IconBitmap.applySmallIcon(Icon.createWithBitmap(shaped), notification)
        if (applied) noteRuler(pkg, if (stripped != null) "清理字带" else "被补", bitmap, shaped)
        return applied
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
