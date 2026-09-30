package com.iamcanincan.opticon.runtime

/**
 * 模块的行为开关，两个功能共用一份。
 *
 * 默认值 = 没有配置时的兜底行为，**必须等价于「两个功能都开」** ——
 * 否则配置通道全挂时（provider / 直接读文件 / 远程配置全失败）模块会按别的组合跑，
 * 而界面、README、`seedIfAbsent` 都写着默认值是那一套，
 * 出现「用户从没改过设置、实际行为却和界面上显示的不一样」。
 *
 * 界面里改了设置后，值由 [ModulePrefs] 从 SharedPreferences 读出来覆盖 ——
 * 模块侧通过本应用暴露的只读 ContentProvider 跨进程取。
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
     * **按应用的合成旁路**（调试用）：包名 → 这张归谁画。`true`/没登记 = 我们合成，
     * `false` = 交回桌面自己压遮罩（屏幕上就是反的）。
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
    val preserveTinted: Boolean = true
) {

    /**
     * 这个应用的单色层要不要由我们合成。
     *
     * 纯合成模式下默认全是"合成"；表里只登记例外 —— 标成 `false` 的那个应用**不合成**，
     * 交回桌面自己出遮罩（在屏幕上就是反的）。留着这条退路的理由：合成在某一张上错了、
     * 而人不记得它本来长什么样时，这是唯一不改代码就能收回的手段。
     *
     * 顺序是硬的：总开关关掉 = 一张都不合成（那是"完全交回桌面"的逃生口，旁路表越过不了它）；
     * 然后按应用；都没登记才落到默认的"合成"。
     *
     * ⚠ 认不出包名的图标（`IconNames` 打成 `?`）旁不了 —— 那张表的键就是这个包名。
     */
    fun synthesizeFor(pkg: String?): Boolean {
        if (!monoInvert) return false
        if (pkg != null) monoOverrides[pkg]?.let { return it }
        return true
    }

    /**
     * 一行日志摘要。
     *
     * 排配置通道的问题全靠它 —— 只打「读到了没有」分不清
     * 「读到了但值是默认的」和「根本没读到、退回默认了」。
     */
    fun describe(): String =
        "circle=$circleEnabled monoInvert=$monoInvert notify=$notifyEnabled" +
            " preserveTinted=$preserveTinted" +
            // 旁路表有没有跨进程传过来，全靠这一项：界面上标了、这里却是 0，
            // 那就是 provider 那一列没读到（而不是"读到了但值为空"）。
            // 表小（手工标的量级）时把键一起打出来 —— 图标那边打的是它自己认出的包名，
            // 两个数同屏才能一眼看出"没生效"是**键对不上**还是**那张根本没重新合成**。
            " 合成表=${monoOverrides.size}" +
            (if (monoOverrides.isEmpty() || monoOverrides.size > 6) "" else
                " ${monoOverrides.entries.joinToString(",") { "${it.key}=${it.value}" }}")
}
