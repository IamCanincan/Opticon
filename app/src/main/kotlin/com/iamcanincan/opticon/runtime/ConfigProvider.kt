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
 * - 清单里声明 `exported` 加 `readPermission="android.permission.STATUS_BAR"`，
 *   这是签名级权限，只有系统组件持有，普通应用查不进来；
 * - 代码里再按调用方包名校验一次作为第二道闸（见 [isTrustedCaller]）；
 * - 只读，暴露的内容仅是「两个功能开关 + 图标模式」这类非敏感开关。
 *
 * ⚠ **读不到配置的进程会用默认值（两个功能都开）**：作用域是可以由用户在管理器里
 * 额外勾选的（第三方桌面等），那些进程既不在下面的白名单里、也没有 STATUS_BAR 权限，
 * 于是拿不到配置。对它们而言「关掉开关」不生效，行为等于默认值。
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
            add(if (prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, true)) 1 else 0)
            add(if (prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, true)) 1 else 0)
            add(prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.DEFAULT_MODE))
        }
        return cursor
    }

    /**
     * 第二道闸：只认模块自己的作用域进程。
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

        /** 与 `META-INF/xposed/scope.list` 一致（system_server 的包名就是 `android`） */
        private val TRUSTED_PACKAGES = setOf(
            "android",
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
            ModulePrefs.COLUMN_NOTIFY_ENABLED,
            ModulePrefs.COLUMN_MODE
        )
    }
}
