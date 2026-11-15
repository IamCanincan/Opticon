package com.iamcanincan.opticon.ui

import android.content.Context
import android.content.Intent
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF

/**
 * 界面里那几条"让改动立刻生效"的广播，抽成一处。
 *
 * 为什么需要这一层：2026-11-08 主人要求「开关后自动清缓存等一系列操作」——
 * 开关只写 SharedPreferences 的话，**已经画好的东西不会自己变**（桌面把图标位图缓存在
 * 自己的数据库里、系统界面把通知行缓存到重建为止），用户得再跑去「运维操作」按一次，
 * 很容易以为开关没生效（这正是他被问过好几次的那件事）。
 *
 * ⚠ 三条广播都带 `FLAG_RECEIVER_FOREGROUND`：我们按下按钮时，桌面 / 系统界面多半是
 *   后台缓存进程（Android 14+ 还会被冻结），普通广播会被直接丢掉 —— 2026-11-07 真机现场
 *   抓到的就是「三个别的进程都打出 clear request ignored，桌面一声不响」。
 *
 * 执行者永远是**目标进程里注入的模块**：这里只是发一条点名广播，所以不需要 root，
 * 也不执行任何 shell 命令。
 */
private const val SYSTEMUI = "com.android.systemui"

/** 「重启其他」覆盖的进程：都是会显示别的应用图标、杀掉后系统会自动拉起的 UI 进程。 */
private val OTHER_RESTARTABLE =
  listOf(
    "com.android.settings",
    "com.google.android.settings.intelligence",
    "com.android.intentresolver",
    "com.android.permissioncontroller",
    // 数字健康也画应用图标、也在作用域清单里；2026-11-12 补进这一键。
    "com.google.android.apps.wellbeing",
  )

/**
 * 清掉桌面的图标位图缓存并重启桌面（自适应 / 动态取色改完都要这一步）。
 *
 * ⚠ **只发一条，别再加"双保险"**。这里原本还显式点两个桌面包名各发一条，注释写的是
 * 「重复送达无害」—— 那句话是错的：接收侧每收到一条就删一次 `app_icons.db` 并
 * `killProcess`，于是按一下按钮桌面被连杀好几次，每次都要重画全部图标。
 * 2026-11-16 真机日志里 4 条 `CacheClear: manual request` 分属 4 个不同 pid，就是它。
 *
 * 留隐式那一条就够了：接收器是模块在各进程里动态注册的，隐式广播照样送达
 * （同一趟日志里 systemui / permissioncontroller 都打出了 `clear request ignored`），
 * 而会动手的只有 `IconHooks.isLauncher` 认的那两个桌面包名 —— 覆盖面与显式点名完全相同。
 */
internal fun clearIconCache(context: Context) {
  runCatching {
    context.sendBroadcast(
      Intent(ACTION_CLEAR_ICON_CACHE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    )
  }
}

/** 重启系统界面（通知开关改完要这一步：已显示的通知行不会自己重绘）。 */
internal fun restartSystemUi(context: Context) {
  runCatching {
    context.sendBroadcast(
      Intent(ACTION_RESTART_SELF).setPackage(SYSTEMUI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    )
  }
}

/** 重启设置 / 分享面板 / 权限控制器这些"会画别的应用图标"的进程。 */
internal fun restartOtherProcesses(context: Context) {
  runCatching {
    for (pkg in OTHER_RESTARTABLE) {
      context.sendBroadcast(
        Intent(ACTION_RESTART_SELF).setPackage(pkg).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
      )
    }
  }
}
