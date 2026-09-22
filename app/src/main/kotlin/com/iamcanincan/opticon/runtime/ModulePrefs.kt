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
 * ## 两个功能、两个开关
 * 应用图标（裁圆）与通知图标（修复）各有自己的总开关 —— 两件事同属「优化图标」，
 * 只是落在不同的位置，开关分开是为了让你按需只开其中一个。通知侧还多一个「模式」选择，
 * 由模式推导 [ModuleOptions.keepOriginalColor] —— 替换策略和是否保色必须成对，
 * 拆成两个独立开关迟早会被配成互相矛盾的组合。
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
    const val COLUMN_NOTIFY_ENABLED = "notify_enabled"
    const val COLUMN_MODE = "mode"

    const val KEY_CIRCLE_ENABLED = "circle_enabled"
    const val KEY_NOTIFY_ENABLED = "notify_enabled"
    const val KEY_MODE = "mode"

    /** 彩色桌面图标：换成应用在桌面上的图标，保留原色 */
    const val MODE_LAUNCHER_ICON = 0

    /** 系统黑白：把应用原始小图标压成单色，交给系统按主题上色 */
    const val MODE_MONOCHROME = 1

    /**
     * 没存过配置时用的模式（默认单色）。
     *
     * 界面初始值、[seedIfAbsent]、[read]、[fromValues] 和 [ConfigProvider] 都取这一个常量 ——
     * 这几处只要有一处写了另一个默认值，改默认模式时漏掉它，
     * 就会出现「界面显示彩色、模块按单色跑」这种两边不一致的怪现象。
     */
    const val DEFAULT_MODE = MODE_MONOCHROME

    /** 界面侧入口 */
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首次打开界面时把默认值写下去，让配置文件存在（模块读不到文件时会退回同样的默认值） */
    fun seedIfAbsent(prefs: SharedPreferences) {
        if (prefs.contains(KEY_MODE)) return
        prefs.edit()
            .putBoolean(KEY_CIRCLE_ENABLED, true)
            .putBoolean(KEY_NOTIFY_ENABLED, true)
            .putInt(KEY_MODE, DEFAULT_MODE)
            .apply()
    }

    /** 把存下来的配置翻译成模块行为。[ModuleOptions.keepOriginalColor] 由模式推导，不单独存 */
    fun read(prefs: SharedPreferences): ModuleOptions = derive(
        circleEnabled = prefs.getBoolean(KEY_CIRCLE_ENABLED, true),
        notifyEnabled = prefs.getBoolean(KEY_NOTIFY_ENABLED, true),
        mode = prefs.getInt(KEY_MODE, DEFAULT_MODE)
    )

    /**
     * 从直接解析配置文件得到的键值对构造。
     *
     * XML 里所有值都是字符串（`<int name="mode" value="1" />`），
     * 解析失败或键缺失时一律退回默认值 —— 和 [read] 的兜底行为保持一致。
     */
    fun fromValues(values: Map<String, String>): ModuleOptions = derive(
        circleEnabled = values[KEY_CIRCLE_ENABLED]?.trim()?.toBooleanStrictOrNull() ?: true,
        notifyEnabled = values[KEY_NOTIFY_ENABLED]?.trim()?.toBooleanStrictOrNull() ?: true,
        mode = values[KEY_MODE]?.trim()?.toIntOrNull() ?: DEFAULT_MODE
    )

    /** 从 provider 查出来的原始值构造。列里存的是整数，调用方已转好类型 */
    fun fromRaw(circleEnabled: Boolean, notifyEnabled: Boolean, mode: Int): ModuleOptions =
        derive(circleEnabled, notifyEnabled, mode)

    private fun derive(circleEnabled: Boolean, notifyEnabled: Boolean, mode: Int): ModuleOptions {
        val monochrome = mode == MODE_MONOCHROME
        return ModuleOptions(
            circleEnabled = circleEnabled,
            notifyEnabled = notifyEnabled,
            replacement = if (monochrome) ModuleOptions.FORCE_MONOCHROME else ModuleOptions.USE_LAUNCHER_ICON,
            keepOriginalColor = !monochrome
        )
    }
}
