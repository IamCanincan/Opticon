package com.iamcanincan.opticon.runtime

/**
 * 模块的行为开关，两个功能共用一份。
 *
 * 默认值 = 没有配置时的兜底行为，**必须等价于「两个功能都开 + 通知走单色」** ——
 * 与 [ModulePrefs.DEFAULT_MODE] 保持同一个语义。否则配置通道全挂时
 * （provider / 直接读文件 / 远程配置全失败）模块会按别的组合跑，
 * 而界面、README、`seedIfAbsent` 都写着默认值是那一套，
 * 出现「用户从没改过设置、实际行为却和界面上显示的不一样」。
 *
 * 界面里改了设置后，值由 [ModulePrefs] 从 SharedPreferences 读出来覆盖 ——
 * 模块侧通过本应用暴露的只读 ContentProvider 跨进程取。
 *
 * 注意 [keepOriginalColor] 与 [replacement] 必须成对：彩色图标要保色，
 * 单色剪影要交给系统上色。界面只让用户选「模式」，这一对由模式推导。
 */
data class ModuleOptions(

    /** 裁圆图标的总开关：关掉后所有进程都拿原图标，不再套圆形遮罩 */
    var circleEnabled: Boolean = true,

    /** 通知图标的总开关：关掉后通知小图标保持系统原样 */
    var notifyEnabled: Boolean = true,

    /**
     * 已经做主题适配（单色）的图标原样放过 —— 这是「适配过的不要动」的核心。
     */
    var preserveTinted: Boolean = true,

    /**
     * 跳过系统的统一着色、保住图标原色。
     *
     * 默认关：默认策略是 FORCE_MONOCHROME（压成单色剪影），那种形状本来
     * 就该由系统按主题上色，保色反而会把刚压好的单色又染回彩色。
     * 只有走 USE_LAUNCHER_ICON（塞彩色应用图标）时才需要打开。
     * ⚠ 必须与 [replacement] 成对改，见类注释。
     */
    var keepOriginalColor: Boolean = false,

    /**
     * 替换策略，取值见下面的常量。
     *
     * 默认走 FORCE_MONOCHROME（把应用自己给的小图标就地压成单色剪影），
     * 与 [ModulePrefs.DEFAULT_MODE] 一致。
     * ⚠ 改这里必须同时改 [keepOriginalColor]，见类注释。
     */
    var replacement: Int = FORCE_MONOCHROME
) {

    /** 替换策略的可读名字，只用于日志 */
    val replacementName: String
        get() = when (replacement) {
            USE_LAUNCHER_ICON -> "launcher-icon"
            FORCE_MONOCHROME -> "monochrome"
            LAUNCHER_ICON_MONOCHROME -> "launcher-icon-mono"
            else -> "unknown($replacement)"
        }

    /**
     * 一行日志摘要。
     *
     * 排配置通道的问题全靠它 —— 只打「读到了没有」分不清
     * 「读到了但值是默认的」和「根本没读到、退回默认了」。
     */
    fun describe(): String =
        "circle=$circleEnabled notify=$notifyEnabled mode=$replacementName" +
            " keepColor=$keepOriginalColor preserveTinted=$preserveTinted"

    companion object {
        /** 未适配 → 直接换成彩色的应用启动图标 */
        const val USE_LAUNCHER_ICON = 0

        /** 未适配 → 把应用自己给的那个小图标就地压成单色 */
        const val FORCE_MONOCHROME = 1

        /** 未适配 → 用桌面应用图标生成单色剪影（保留明暗，不是纯剪影） */
        const val LAUNCHER_ICON_MONOCHROME = 2
    }
}
