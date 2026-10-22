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
        val bitmap = IconBitmap.decode(smallIcon, context) ?: return false
        // 已经是单色的就不用再压一遍
        if (ToneCheck.isGrayscale(bitmap)) {
            noteRuler(pkg, "已适配", bitmap, null)
            return false
        }
        // 压不出像样的形状（全空/全满）就保持原样，别交一张更糟的图出去
        val monochrome = IconBitmap.monochrome(bitmap)
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
        if (applied) noteRuler(pkg, "被补", bitmap, shaped)
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
        val key = kind + " " + src.width + "x" + src.height
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
