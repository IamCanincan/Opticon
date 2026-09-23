package com.iamcanincan.opticon.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * 把界面里选的配置暴露给模块。
 *
 * 模块活在 SystemUI / launcher / settings / system_server 这些进程里，既读不到本应用的
 * 私有目录（被系统隔离挡掉，表现为 ENOENT），也用不了 LibXposed 的远程配置 ——
 * 实测部分框架的 `getRemotePreferences` 返回空对象、`openRemoteFile` 找不到任何文件，
 * 配置根本传不过去。ContentProvider 是 Android 标准的跨进程通道，
 * 不依赖任何框架实现，各框架上都能用。
 *
 * 安全：
 * - 只读，能查出来的内容仅是「三个开关 + 图标模式」这类非敏感配置；
 * - **唯一的入口闸是按调用方包名认自家人**（见 [isTrustedCaller]），名单与
 *   `META-INF/xposed/scope.list` 逐条对齐；不在名单里的应用查它，拿到的是一张开游标。
 *
 * ⚠ 清单里**刻意不加** `android:readPermission`。原本加的是
 * `android.permission.STATUS_BAR`（系统签名级），理由写的是「只有系统组件持有，
 * 普通应用查不进来」—— 这话本身对，但漏了一件事：作用域清单里的应用不全都算系统组件。
 * 实测 `com.google.android.apps.wellbeing` 就不持有该权限，于是它每个 TTL 查一次、
 * 每次被 SecurityException 挡在门外，最后只能按默认值干活（= 界面上把开关关掉对它
 * 不生效，另外每秒往 logcat 刷一行失败日志）。那道锁拦不住它本来想拦的人，
 * 却把自己人拦下了，而包名白名单覆盖的正是同一条判断。撤掉后 wellbeing 立刻读到配置。
 *
 * ⚠ 反过来说：撤掉之后 [isTrustedCaller] 就是**唯一**那道闸，别再看轻它 ——
 * 调作用域清单时必须同步改 [TRUSTED_PACKAGES]。
 *
 * ⚠ **读不到配置的进程会用默认值（功能全开）**：作用域可以由用户在管理器里额外勾选
 * （第三方桌面等），那些进程不在白名单里，对它们而言「关掉开关」不生效，行为等于默认值。
 * 真正决定全局的是 system_server：它在白名单里，读到关闭就不给图标 id 打标记，
 * 下游任何进程拿到的都是未经处理的原图标 —— 见 `IconHooks`。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        if (!isTrustedCaller()) return cursor
        val ctx = context ?: return cursor
        val prefs = ModulePrefs.of(ctx)
        // ⚠ 别用 addRow(arrayOf<Any>(...))：Int 与 Boolean 混在一起 Kotlin 推不出公共类型。
        // 用 newRow() 逐项 add。
        cursor.newRow().apply {
            add(if (prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, ModulePrefs.DEFAULT_CIRCLE_ENABLED)) 1 else 0)
            add(if (prefs.getBoolean(ModulePrefs.KEY_MONO_INVERT, ModulePrefs.DEFAULT_MONO_INVERT)) 1 else 0)
            add(if (prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, ModulePrefs.DEFAULT_NOTIFY_ENABLED)) 1 else 0)
            add(prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.DEFAULT_MODE))
        }
        return cursor
    }

    /**
     * 唯一的入口闸：只认模块自己的作用域进程。
     *
     * 不能用 uid 硬判 —— SystemUI 在这台设备上跑在 10154 而不是 1000，
     * 所以按「调用方 uid 归属哪个包」来判断。
     *
     * 名单与 `META-INF/xposed/scope.list` 对齐：裁圆要在 launcher / settings /
     * system_server 这些进程里读开关，通知要在 SystemUI 里读，缺一个就有一路拿不到配置。
     */
    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return true
        val packages = context?.packageManager?.getPackagesForUid(uid) ?: return false
        return packages.any { it in TRUSTED_PACKAGES }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException(READ_ONLY)

    companion object {

        /**
         * 与 `META-INF/xposed/scope.list` **逐条对齐**（改一处就要改另一处）。
         *
         * 里面有两个特殊包名：
         * - `android` 是 system_server 的包名 —— 它决定「给不给图标 id 打标记」，
         *   读不到配置就等于永远开着；
         * - `system` 与 `android` 同属 uid 1000，实际上会被上面 `SYSTEM_UID` 那条
         *   直接放行。这里仍然列出来，是为了让这份名单能和 `scope.list` 一眼对上 ——
         *   靠隐式放行的话，以后调作用域时很容易漏掉它。
         */
        private val TRUSTED_PACKAGES = setOf(
            "android",
            "system",
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.google.android.settings.intelligence",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.apps.wellbeing"
        )

        private const val READ_ONLY = "Opticon 的配置通道是只读的"

        /** 列名与 [ModulePrefs.COLUMN_*] 一一对应，顺序也要一致 */
        val COLUMNS = arrayOf(
            ModulePrefs.COLUMN_CIRCLE_ENABLED,
            ModulePrefs.COLUMN_MONO_INVERT,
            ModulePrefs.COLUMN_NOTIFY_ENABLED,
            ModulePrefs.COLUMN_MODE
        )
    }
}
