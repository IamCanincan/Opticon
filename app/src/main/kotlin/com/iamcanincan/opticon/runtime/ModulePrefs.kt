package com.iamcanincan.opticon.runtime

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块配置的存储约定 —— 界面进程与被注入进程之间的唯一通道。
 *
 * 界面（App 进程）用普通的 [Context.getSharedPreferences] 写；模块（跑在 SystemUI /
 * launcher / system_server 等进程里）通过本应用暴露的只读 ContentProvider
 * （见 [ConfigProvider]）读。**两边必须用同一个文件名**，否则模块读到的永远是默认值，
 * 界面改了也不生效。
 *
 * ## 三个开关
 * 应用图标（裁圆）与通知图标（修复）各有自己的总开关 —— 两件事同属「优化图标」，
 * 只是落在不同的位置，开关分开是为了让你按需只开其中一个。第三个开关
 * （[KEY_MONO_INVERT]）管的是 Android 16 强制取色下**主题图标单色遮罩**的整条处理链
 * ——**未适配的图标由本模块合成一张极性正确的单色层**，关掉就完全不介入（见 [ModuleOptions.monoInvert]）；
 * 它和裁圆没有因果关系，所以不跟着 [KEY_CIRCLE_ENABLED] 走。
 *
 * ⚠ 默认值必须和 [ModuleOptions] 的无参默认值逐字段一致：后者是配置通道**全挂**时的
 * 兜底（provider 查不到、文件读不到、远程配置拿不到）。两边不一致就会出现
 * 「用户从没改过设置、实际行为却和界面上显示的不一样」。
 */
object ModulePrefs {

    /** SharedPreferences 文件名，两边共用 */
    const val FILE = "opticon"

    /**
     * 配置通道 provider 的 authority 后缀。
     *
     * 完整 authority = 包名 + 这个后缀（清单里写死，模块侧按同一规则拼）。
     * 之所以不用 LibXposed 的 getRemotePreferences：实测部分框架返回空对象、
     * openRemoteFile 也找不到文件，配置根本传不到模块 —— 详见 [ConfigProvider]。
     */
    const val AUTHORITY_SUFFIX = ".config"

    /** provider 返回的列名，模块侧按这些名字取值 */
    const val COLUMN_CIRCLE_ENABLED = "circle_enabled"
    const val COLUMN_MONO_INVERT = "mono_invert"
    const val COLUMN_NOTIFY_ENABLED = "notify_enabled"

    const val KEY_CIRCLE_ENABLED = "circle_enabled"
    const val KEY_MONO_INVERT = "mono_invert"
    const val KEY_NOTIFY_ENABLED = "notify_enabled"

    /**
     * 各开关没存过时的默认值。
     *
     * ⚠ 必须与 [ModuleOptions] 的无参默认值逐字段一致，且**界面、[seedIfAbsent]、[read]、
     * [fromValues]、[ConfigProvider] 一律只引用这几个常量**。任何一处自己另写字面量，
     * 改默认值时漏掉它，就会出现「界面显示开着、模块按关掉跑」这类两边不一致的怪现象 ——
     * 而配置通道全挂时兜底的正是这几个值。
     */
    const val DEFAULT_CIRCLE_ENABLED = true
    const val DEFAULT_MONO_INVERT = true
    const val DEFAULT_NOTIFY_ENABLED = true

    /**
     * 「这确实是一份 Opticon 配置」的判据键。
     *
     * ⚠ 这条判据存在的理由只有一个：**空的东西长得和默认配置一模一样**。
     * 某些框架的 `getRemotePreferences` 返回"空但非 null"的实例、名字试错时也可能撞上一个空文件，
     * 直接拿去 [read] / [fromValues] 会得到一份全默认值，看起来完全合法，于是把用户真设置顶掉
     * （见 `ModuleRuntime.readFromPreferences` 那段实证）。所以三条备用通道都先问一句：
     * **里面到底有没有我们的键？**
     *
     * 以前这个判据是「有没有 `mode` 键」，那是**一个魔法键扛四件事**：删掉任何一键就得同时改四处，
     * 漏一处就是静默失效。改成「任意一个已知键」——[seedIfAbsent] 从来是整组一起写的，
     * 所以两种问法在今天**逐文件等价**，但不再依赖某一个键还活着。
     */
    val CONFIG_KEYS = setOf(KEY_CIRCLE_ENABLED, KEY_MONO_INVERT, KEY_NOTIFY_ENABLED)

    /** 这批键里有没有一个是我们的？（备用通道共用的那一道判据） */
    fun looksLikeConfig(keys: Collection<String>): Boolean = keys.any { it in CONFIG_KEYS }

    /** 界面侧入口 */
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首次打开界面时把默认值写下去，让配置文件存在（模块读不到文件时会退回同样的默认值） */
    fun seedIfAbsent(prefs: SharedPreferences) {
        if (looksLikeConfig(prefs.all.keys)) return
        prefs.edit()
            .putBoolean(KEY_CIRCLE_ENABLED, DEFAULT_CIRCLE_ENABLED)
            .putBoolean(KEY_MONO_INVERT, DEFAULT_MONO_INVERT)
            .putBoolean(KEY_NOTIFY_ENABLED, DEFAULT_NOTIFY_ENABLED)
            .apply()
    }

    /** 把存下来的配置翻译成模块行为 */
    fun read(prefs: SharedPreferences): ModuleOptions = ModuleOptions(
        circleEnabled = prefs.getBoolean(KEY_CIRCLE_ENABLED, DEFAULT_CIRCLE_ENABLED),
        monoInvert = prefs.getBoolean(KEY_MONO_INVERT, DEFAULT_MONO_INVERT),
        notifyEnabled = prefs.getBoolean(KEY_NOTIFY_ENABLED, DEFAULT_NOTIFY_ENABLED)
    )

    /**
     * 从直接解析配置文件得到的键值对构造。
     *
     * XML 里所有值都是字符串（`<boolean name="notify_enabled" value="true" />`），
     * 解析失败或键缺失时一律退回默认值 —— 和 [read] 的兜底行为保持一致。
     */
    fun fromValues(values: Map<String, String>): ModuleOptions = ModuleOptions(
        circleEnabled =
            values[KEY_CIRCLE_ENABLED]?.trim()?.toBooleanStrictOrNull() ?: DEFAULT_CIRCLE_ENABLED,
        monoInvert =
            values[KEY_MONO_INVERT]?.trim()?.toBooleanStrictOrNull() ?: DEFAULT_MONO_INVERT,
        notifyEnabled =
            values[KEY_NOTIFY_ENABLED]?.trim()?.toBooleanStrictOrNull() ?: DEFAULT_NOTIFY_ENABLED
    )

    /** 从 provider 查出来的原始值构造。列里存的是整数，调用方已转好类型 */
    fun fromRaw(
        circleEnabled: Boolean,
        monoInvert: Boolean,
        notifyEnabled: Boolean,
    ): ModuleOptions = ModuleOptions(
        circleEnabled = circleEnabled,
        monoInvert = monoInvert,
        notifyEnabled = notifyEnabled
    )
}
