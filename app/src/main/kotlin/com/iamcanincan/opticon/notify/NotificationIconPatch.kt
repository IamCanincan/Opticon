package com.iamcanincan.opticon.notify

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
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
            if (forceMonochrome(smallIcon, notification, context)) {
                ModuleRuntime.logI("patched $pkg $beforeType->${iconType(notification.smallIcon)}")
            }
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

    private fun forceMonochrome(smallIcon: Icon, notification: Notification, context: Context): Boolean {
        val bitmap = IconBitmap.decode(smallIcon, context) ?: return false
        // 已经是单色的就不用再压一遍
        if (ToneCheck.isGrayscale(bitmap)) return false
        // 压不出像样的形状（全空/全满）就保持原样，别交一张更糟的图出去
        val monochrome = IconBitmap.monochrome(bitmap) ?: return false
        return IconBitmap.applySmallIcon(Icon.createWithBitmap(monochrome), notification)
    }
}
