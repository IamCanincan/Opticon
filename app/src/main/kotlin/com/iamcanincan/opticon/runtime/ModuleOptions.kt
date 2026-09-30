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
     * 本模块的做法：**不给它出遮罩的机会**。钩 `AdaptiveIconDrawable.getMonochrome()`，应用自带
     * 单色层就原样透传，没有就自己合成一张极性正确的剪影交上去 —— 桌面只在 getter 返回 null
     * 时才 new 那个 Factory，所以合成一接上，"亮度当 alpha" 那条路根本不进。
     * 极性怎么判与判据台共用同一份 `MonochromeMask`（同一个 `groundPass`），不另写一套"哪边是图形"。
     *
     * ⚠ 旧版的第一条路是**翻转兜底**（让桌面先算错、我们再整张翻回来），已被合成取代；
     *   `MonochromeMask.polarity()` 现在只剩判据台与性质在用，业务侧无调用者 —— 别拿它当
     *   "屏幕上那条路还在翻转"的证据。
     *
     * 关掉它 = 什么都不动，桌面完全照它自己那套处理 —— 只有在这个 ROM / 桌面版本上实测
     * **不该动**（厂商已经修过极性，或某个图标包本身就反着出）时才需要关。
     * 它和 [circleEnabled] 是两个独立开关：裁圆和遮罩极性没有因果关系。
     */
    val monoInvert: Boolean = true,

    /** 通知图标的总开关：关掉后通知小图标保持系统原样 */
    val notifyEnabled: Boolean = true,

) {

    /**
     * 一行日志摘要。
     *
     * 排配置通道的问题全靠它 —— 只打「读到了没有」分不清
     * 「读到了但值是默认的」和「根本没读到、退回默认了」。
     */
    fun describe(): String =
        "circle=$circleEnabled monoInvert=$monoInvert notify=$notifyEnabled"
}
