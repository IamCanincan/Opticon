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
 *
 * ⚠ 字段一律 `val`：这个实例会被 [ModuleRuntime] 存成「本进程当前生效的配置」，
 * 而读配置的那条路在 system_server 里是**多个 binder 线程**同时进来的。
 * 不可变对象 + volatile 引用就够保证大家看到同一份完整快照；
 * 写成 var 的话既没有安全发布保证，也没人负责它改完后的可见性。
 * （全仓确认过：没有任何地方赋值这些字段，改 val 不破坏任何调用点。）
 */
data class ModuleOptions(

    /** 裁圆图标的总开关：关掉后所有进程都拿原图标，不再套圆形遮罩 */
    val circleEnabled: Boolean = true,

    /**
     * 主题图标（Android 16「强制取色」）的**极性修正**总开关。
     *
     * 桌面会替**未适配**的应用现场生成一张单色遮罩（`MonochromeIconFactory`），它的算法是
     * **亮度当 alpha** —— 假定「亮的就是墨」。深色字形 + 浅色底的图标因此被画成
     * "字形是洞、底色是形"，看着整个反掉。
     *
     * 本模块的做法：**遮罩仍由桌面出，我们只决定翻不翻**。判据是「这张图的墨比它的底暗吗」，
     * 与过去那条合成路共用同一份 `MonochromeMask`（同一个 `groundPass`），不另写一套
     * "哪边是图形"。判不出来的图**不动**（= 桌面本来的样子）并单独计数。
     *
     * ⚠ 过去这里还有第二条路（自己合成一个单色层交上去）。它已经不上屏了 —— 判据留着，
     *   出图那半在 `MonochromeSynth` / `MonochromeMask.build` 里，判据台与真机重放还在跑它。
     *
     * 关掉它 = 什么都不动，桌面完全照它自己那套处理 —— 只有在这个 ROM / 桌面版本上实测
     * **不该动**（厂商已经修过极性，或某个图标包本身就反着出）时才需要关。
     * 它和 [circleEnabled] 是两个独立开关：裁圆和遮罩极性没有因果关系。
     */
    val monoInvert: Boolean = true,

    /**
     * **按应用的反色覆盖**（调试用）：包名 → 这张要不要翻。表里没有的包走默认策略。
     *
     * 为什么要有这张表：判据的方向现在还没有可信依据（真机回传的两张遮罩，一张像"亮=墨"、
     * 一张像"暗=墨"，互相打脸），而他需要在真机上一张一张看到结果。这张表就是"判据先交给人
     * 定"的临时通道 —— 它必须跟着配置一起跨进程（被注入的桌面读不到本应用的 SharedPreferences
     * 文件），所以序列化成一段文本走 provider 那一列，见 [ModulePrefs.KEY_MONO_OVERRIDES]。
     */
    val monoOverrides: Map<String, Boolean> = emptyMap(),

    /** 通知图标的总开关：关掉后通知小图标保持系统原样 */
    val notifyEnabled: Boolean = true,

    /**
     * 已经做主题适配（单色）的图标原样放过 —— 这是「适配过的不要动」的核心。
     */
    val preserveTinted: Boolean = true,

    /**
     * 跳过系统的统一着色、保住图标原色。
     *
     * 默认关：默认策略是 FORCE_MONOCHROME（压成单色剪影），那种形状本来
     * 就该由系统按主题上色，保色反而会把刚压好的单色又染回彩色。
     * 只有走 USE_LAUNCHER_ICON（塞彩色应用图标）时才需要打开。
     * ⚠ 必须与 [replacement] 成对改，见类注释。
     */
    val keepOriginalColor: Boolean = false,

    /**
     * 替换策略，取值见下面的常量。
     *
     * 默认走 FORCE_MONOCHROME（把应用自己给的小图标就地压成单色剪影），
     * 与 [ModulePrefs.DEFAULT_MODE] 一致。
     * ⚠ 改这里必须同时改 [keepOriginalColor]，见类注释。
     */
    val replacement: Int = FORCE_MONOCHROME
) {

    /**
     * 这一张遮罩到底翻不翻。
     *
     * 顺序是硬的：总开关关掉 = 一律不动（那是"完全交回桌面"的逃生口，按应用覆盖也不能越过它）；
     * 然后按应用的覆盖；都没有才落到 [default]（调试期 = 一律翻）。
     */
    fun flipFor(pkg: String?, default: Boolean): Boolean {
        if (!monoInvert) return false
        if (pkg != null) monoOverrides[pkg]?.let { return it }
        return default
    }

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
        "circle=$circleEnabled monoInvert=$monoInvert notify=$notifyEnabled" +
            " mode=$replacementName keepColor=$keepOriginalColor preserveTinted=$preserveTinted" +
            // 按应用的反色表有没有跨进程传过来，全靠这一项：界面上标了、这里却是 0，
            // 那就是 provider 那一列没读到（而不是"读到了但值为空"）。
            " 反色表=${monoOverrides.size}"

    companion object {
        /** 未适配 → 直接换成彩色的应用启动图标 */
        const val USE_LAUNCHER_ICON = 0

        /** 未适配 → 把应用自己给的那个小图标就地压成单色 */
        const val FORCE_MONOCHROME = 1

        /** 未适配 → 用桌面应用图标生成单色剪影（保留明暗，不是纯剪影） */
        const val LAUNCHER_ICON_MONOCHROME = 2
    }
}
