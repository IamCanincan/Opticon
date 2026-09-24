package com.iamcanincan.opticon.circle

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageItemInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.iamcanincan.opticon.runtime.ModuleRuntime
import com.iamcanincan.opticon.runtime.TAG
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

// 应用资源 id 都住在 0x7f 这个 package 里。把图标 id 挪到一个不会有真实资源的 package，
// 它到达 Resources 时就能被认出来；调用原方法之前会换回 0x7f，所以解析逻辑不受影响。
private const val REAL_PACKAGE_ID = 0x7f000000
private const val MARKED_PACKAGE_ID = 0x6e000000

// 应用图标解析不出来时系统用的那个默认图标，它同样不是圆形的。
private const val DEFAULT_APP_ICON = android.R.drawable.sym_def_app_icon

private fun Int.isMarkedIcon() = (this and 0xff000000.toInt()) == MARKED_PACKAGE_ID

private fun Int.marked() = (this and 0x00ffffff) or MARKED_PACKAGE_ID

private fun Int.unmarked() = (this and 0x00ffffff) or REAL_PACKAGE_ID

private fun Int.isAppResource() = (this and 0xff000000.toInt()) == REAL_PACKAGE_ID

/**
 * 打标记的重入保护。
 *
 * 生成图标信息（构造 / Parcel 反序列化 / PMS 生成）的几条路径会互相嵌套：
 * 外层生成 `PackageInfo` 时内部又会构造 `ApplicationInfo`，而 `PackageInfo` 里装的正是
 * 这些 `ApplicationInfo`。没有这层保护就会出现"打过标记又被当作新 id 再处理一遍"。
 */
private val markingIcons = ThreadLocal.withInitial { false }

/**
 * 图标生成的重入保护。
 *
 * `Resources.getDrawableForDensity()` 和 `ApplicationPackageManager.getDrawableInternal()`
 * 可能出现在同一条调用链上：外层 proceed 之后会跑到内层。只让最外层生成图标，
 * 否则同一个图标会被包装两次（圆套圆、尺寸再缩一次），表现就是"同一个图标
 * 有时大有时小"。
 */
private val replacingIcon = ThreadLocal.withInitial { false }


private inline fun runMarkingIcons(block: () -> Unit) {
  if (markingIcons.get() == true) return
  markingIcons.set(true)
  try {
    block()
  } finally {
    markingIcons.set(false)
  }
}

/** 同一进程里 `onSystemServerStarting` 和 `onPackageReady` 可能都来，别装两遍。 */
@Volatile private var installed = false

fun hookSystemServer(
  xposed: XposedInterface,
  param: XposedModuleInterface.SystemServerStartingParam,
) = install(xposed, param.classLoader, "android")

fun hookIcons(xposed: XposedInterface, param: XposedModuleInterface.PackageReadyParam) =
  install(xposed, param.classLoader, param.packageName)

/**
 * 图标替换分两步：先在**图标信息**上把 icon 资源 id 打标记，再在**图标加载出口**上
 * 认出这个标记并把结果裁成圆形。两步缺一不可，所以加载出口挂不上时就整体放弃 ——
 * 否则被打过标记的 id 会在别的进程里解析失败。
 *
 * ⚠ 「在别的进程里解析失败」的后果比"图标变空白"严重得多：伪造的 `0x6e…` 传到没有
 * 本模块的进程后，`Resources.getDrawable` 会抛 `Resources$NotFoundException`，而
 * `Activity.initWindowDecorActionBar` 这类调用点就在 `Activity.onCreate` 里 —— **直接崩应用**。
 * 所以 [hookParcelWriteRestore] 会在 Parcel 出口统一还原，把这条泄漏掐断。
 */
private fun install(xposed: XposedInterface, classLoader: ClassLoader, packageName: String) {
  if (installed) return
  Log.d(TAG, "install in $packageName, sdk ${Build.VERSION.SDK_INT}")

  if (!hookIconLoaders(xposed, classLoader)) {
    Log.w(TAG, "No icon loader is found, nothing is hooked")
    return
  }
  installed = true

  // 出口还原必须先挂：它是唯一能挡住"标记 id 泄漏到没注入的进程"的闸门。
  hookParcelWriteRestore(xposed)
  hookMarkedIconIds(xposed)
  hookBatchIconIds(xposed, classLoader)
  hookShortcutIcons(xposed)
  hookArchivedAppIcon(xposed, classLoader)
  hookPackageManagerIconGetters(xposed, classLoader)
  // 进程控制（清缓存 / 重启自己）：内部按白名单过滤，只有安全的 UI 进程才挂。
  hookProcessControlReceiver(packageName)

  if (packageName == "com.android.launcher3" ||
      packageName == "com.google.android.apps.nexuslauncher"
  ) {
    hookPixelLauncher(xposed, classLoader)
    hookTaskIcons(xposed, classLoader)
    // 合成优先：它一旦接上，桌面就不进 Factory，下面那条翻转不会被触发。
    // 合成挂不上 / 抛异常时返回 null，桌面照旧走 Factory，翻转照常兜底。
    hookSynthesizedMonochrome(xposed)
    hookForcedThemedMono(xposed, classLoader)
  }
  if (packageName == "com.android.systemui") hookSplashScreenIcon(xposed, classLoader)
  if (packageName == "com.android.settings") {
    hookSettingsAdaptiveIcon(xposed, classLoader)
    hookBatteryIcons(xposed, classLoader)
  }

  Log.d(TAG, "Hooked $packageName")
}

/** 手动"清图标缓存并重启桌面"的广播 action：App 发出，桌面进程里的本模块接收。 */
const val ACTION_CLEAR_ICON_CACHE = "com.iamcanincan.opticon.CLEAR_ICON_CACHE"

/** 手动"重启自己"的广播 action：App 按包名点名，对应进程里的本模块收到后自杀重启。 */
const val ACTION_RESTART_SELF = "com.iamcanincan.opticon.RESTART_SELF"

/**
 * 上面两条 action 的**入口闸**：只有持有本权限的应用能让被注入的进程去清缓存 / 自杀重启。
 *
 * 接收器必须是导出的（发送方是另一个 uid —— 本模块 App），所以「谁能发」这件事只能由
 * 权限来管。它是 `signature` 级、由本 APK 自己声明（见 AndroidManifest），
 * 于是**只有与本包同签名的应用**能拿到 —— 少了这一道，任何第三方应用发一条
 * `RESTART_SELF` 就能反复杀掉桌面和 SystemUI，`CLEAR_ICON_CACHE` 能反复删桌面图标缓存。
 *
 * ⚠ 只在 `registerReceiver` 一侧要求；`sendBroadcast` 不要再传这个权限名，
 * 那是检查**接收方**是否持有 —— 宿主进程（SystemUI / launcher）并不持有它。
 */
const val PERMISSION_PROCESS_CONTROL = "com.iamcanincan.opticon.permission.PROCESS_CONTROL"

/**
 * 允许响应"重启自己"的进程白名单。
 *
 * 这些都是普通 UI 进程：杀掉之后系统会立刻把它们拉起来，对用户没有副作用
 * （桌面闪一下、状态栏重建一下就完事）。
 *
 * ⚠ **绝不能放进 `system` / `system_server`** —— 杀它们等于软重启整机，
 * 用户只是想刷个图标，不该把整台手机重启一遍。
 */
private val RESTARTABLE_PACKAGES =
  setOf(
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.android.systemui",
    "com.android.settings",
    "com.google.android.settings.intelligence",
    "com.android.intentresolver",
    "com.android.permissioncontroller",
    "com.google.android.apps.wellbeing",
  )

/** 桌面进程。清缓存只在这两个里做 —— 别把别的进程的数据库给删了。 */
private fun isLauncher(packageName: String) =
  packageName == "com.android.launcher3" ||
    packageName == "com.google.android.apps.nexuslauncher"

/**
 * 桌面自己数据目录的根路径。
 *
 * 我们是**跑在桌面进程里**的，用的是桌面的 UID，所以它自己目录下的文件随便读写，
 * 不需要任何额外权限（也不该去申请）。
 */
private fun launcherDataDir(packageName: String): String =
  "/data/user/${Process.myUid() / 100000}/$packageName"

/**
 * 删掉桌面的图标缓存 `app_icons.db*`。
 *
 * **只删图标缓存**，绝不碰 `launcher.db` / `launcher_4_by_5.db` —— 那是桌面布局，
 * 删了图标排列就全没了。
 *
 * @return 真删掉了返回 true；DB 本来就不存在返回 false（这不算失败）。
 */
private fun deleteIconCache(packageName: String): Boolean {
  val db = File("${launcherDataDir(packageName)}/databases/app_icons.db")
  if (!db.exists()) return false
  if (!runCatching { db.delete() }.getOrDefault(false)) {
    Log.w(TAG, "IconCache: cannot delete ${db.path}")
    return false
  }
  for (suffix in listOf("-journal", "-wal", "-shm")) {
    runCatching { File(db.path + suffix).delete() }
  }
  return true
}

// 这里原本有一套"模块加载时按 APK 戳记自动清一次图标缓存"的逻辑，已经**按用户要求移除**：
// 清不清、什么时候清一律交给界面上的手动按钮，模块不再自作主张删桌面的数据库。
// 手动清的实现见 [deleteIconCache] 与广播 [ACTION_CLEAR_ICON_CACHE]。

/** 当前进程的 Application（模块侧拿 Context 的通用办法，反射 ActivityThread）。 */
private fun currentApplication(): Application? =
  runCatching {
      val activityThread = Class.forName("android.app.ActivityThread")
      val method = activityThread.getDeclaredMethod("currentApplication")
      method.isAccessible = true
      method.invoke(null) as? Application
    }
    .getOrNull()

/**
 * 注册"进程控制"广播接收器：让 App 能请求**清缓存** / **重启本进程**。
 *
 * **为什么要绕这一圈**：App 自己进程的 UID **读不到**桌面（launcher3）的数据目录，
 * 也杀不掉别的进程（要 root 或 shell 才行）。而本模块是跑在**目标进程里**的，
 * 用的是那个进程自己的 UID —— 删自己的文件、自杀重启，全都名正言顺，
 * **不需要 root，也不用执行任何 shell 命令**（参考项目这一排按钮是走 root shell 的）。
 *
 * **为什么清完缓存必须重启桌面**：桌面把图标位图缓存在**内存**里，只删 DB 它不会重新读盘，
 * 用户看到的还是旧图 —— 这正是"清了缓存却没变化"的根因。杀掉进程让系统把它拉起来，
 * 才会真正按当前逻辑重建图标。
 *
 * **发送方必须持有 [PERMISSION_PROCESS_CONTROL]**，见 [registerProcessControlReceiver]。
 */
private fun hookProcessControlReceiver(packageName: String) {
  if (packageName !in RESTARTABLE_PACKAGES) return

  // 我们还在 attachBaseContext 阶段，`ActivityThread.currentApplication()` 这时常常是 null。
  // 先试一次，拿不到就起个线程等它就绪 —— 这比 hook `Application.onCreate` 可靠得多：
  // 子类里的 `super.onCreate()` 会被内联进子类，hook 基类方法根本不会被走到
  // （deoptimize 也救不回来：实测注册“成功”但一次都没触发）。
  val app = currentApplication()
  if (app != null) {
    registerProcessControlReceiver(app, packageName)
    return
  }

  Thread {
      var ready: Application? = null
      for (i in 0 until 100) {
        ready = currentApplication()
        if (ready != null) break
        runCatching { Thread.sleep(100) }
      }
      if (ready == null) {
        Log.w(TAG, "ProcessControl: no Application in $packageName, receiver not registered")
        return@Thread
      }
      Handler(Looper.getMainLooper()).post { registerProcessControlReceiver(ready, packageName) }
    }
    .apply { isDaemon = true }
    .start()
}

// lint 看不出"高版本走带 flag 的重载"这个分支，只盯着旧版那条无 flag 的调用。
// 实际上 flag 已经按 SDK 版本给了，这里精准抑制即可（比在 lint.xml 里全局关掉好）。
@SuppressLint("UnspecifiedRegisterReceiverFlag")
private fun registerProcessControlReceiver(context: Context, packageName: String) {
  val receiver =
    object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent?) {
        when (intent?.action) {
          ACTION_CLEAR_ICON_CACHE -> {
            // 只有桌面进程执行：删的是"自己"的图标缓存，别的进程别跟着删。
            if (!isLauncher(packageName)) {
              Log.w(TAG, "ProcessControl: clear request ignored in $packageName")
              return
            }
            val deleted = deleteIconCache(packageName)
            Log.d(TAG, "CacheClear: manual request, db deleted=$deleted, restarting launcher")
            Process.killProcess(Process.myPid())
          }
          ACTION_RESTART_SELF -> {
            Log.d(TAG, "Restart: manual request, restarting $packageName")
            Process.killProcess(Process.myPid())
          }
          else -> Log.w(TAG, "ProcessControl: unknown action ${intent?.action}")
        }
      }
    }
  val filter =
    IntentFilter().apply {
      addAction(ACTION_CLEAR_ICON_CACHE)
      addAction(ACTION_RESTART_SELF)
    }
  // 权限加在**接收侧**：AMS 按发送方 uid 是否持有 PROCESS_CONTROL 决定这条广播送不送得到。
  // 不写这个参数的话，任何应用都能让宿主进程自杀重启（见 [PERMISSION_PROCESS_CONTROL]）。
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, filter, PERMISSION_PROCESS_CONTROL, null, Context.RECEIVER_EXPORTED)
  } else {
    context.registerReceiver(receiver, filter, PERMISSION_PROCESS_CONTROL, null)
  }
  Log.d(TAG, "ProcessControl: receiver registered in $packageName")
}

/**
 * 图标的加载出口。应用图标最终都会到 `Resources.getDrawableForDensity()`：
 * `Resources.getDrawable(id, theme)` 和 `PackageItemInfo.loadIcon()` 都转调它。
 * 较新的安卓版本改成在 `ApplicationPackageManager` 里解析 item 图标。
 */
private fun hookIconLoaders(xposed: XposedInterface, classLoader: ClassLoader): Boolean {
  var hooked = false
  for (method in declaredMethods(Resources::class.java, "getDrawableForDensity")) {
    hooked = hookIconLoader(xposed, method) || hooked
  }
  // deprecated 入口（资源 ID + 主题）：launcher 的快捷面板 / AllApps 数据加载
  // 走的就是这两个重载，没走 `getDrawableForDensity`。
  for (method in declaredMethods(Resources::class.java, "getDrawable")) {
    if (hookIconLoader(xposed, method)) {
      Log.d(TAG, "IconLoaders: Resources.getDrawable (${method.parameterTypes.joinToString { it.simpleName }}) hooked")
      hooked = true
    }
  }
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader)
  if (packageManager != null) {
    for (method in declaredMethods(packageManager, "getDrawableInternal")) {
      hooked = hookIconLoader(xposed, method) || hooked
    }
    // Settings 等应用拿其它包图标走这个：签名 (String, int, ApplicationInfo) 等。
    for (method in declaredMethods(packageManager, "getDrawable")) {
      if (hookIconLoader(xposed, method)) {
        Log.d(TAG, "IconLoaders: APM.getDrawable (${method.parameterTypes.joinToString { it.simpleName }}) hooked")
        hooked = true
      }
    }
  }
  return hooked
}

/**
 * 约定：**没有改动过参数一律走零参 chain.proceed()**。
 * Chain.getArgs() 返回的是 java.util.List，`proceed(args.toTypedArray())` 会先把整份
 * 参数表拷成一个新数组 —— 而这条钩挂在 Resources.getDrawable* 上，进程里**每一张**
 * drawable（不只是应用图标）都要过一遍，白拷一次就是每张图一次分配。
 * 只有真的要换参数时才构造数组（下面的 restored、以及各 proceedWith 调用点）。
 */
private fun hookIconLoader(xposed: XposedInterface, method: Method): Boolean =
  runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args

        // 已经在生成图标了（同一条调用链的内层），交给最外层处理。
        // 参考项目把这道闸放在最前面 —— 默认图标那条分支同样要挡住，
        // 否则嵌套时同一个图标会被裁两次。
        if (replacingIcon.get() == true) return@intercept chain.proceed()

        // 被标记的图标 id 是唯一认得出来的参数，找它比记住参数下标可靠。
        val index = args.indexOfFirst { (it as? Int)?.isMarkedIcon() == true }
        if (index < 0) {
          // 解析不出应用图标时系统会退回这个默认图标，它同样不是圆的。
          if (args.indexOfFirst { (it as? Int) == DEFAULT_APP_ICON } < 0) {
            return@intercept chain.proceed()
          }
          val fallback =
            chain.proceed() as? Drawable ?: return@intercept null
          return@intercept clipToCircle(fallback)
        }

        replacingIcon.set(true)
        try {
          val restored = args.toMutableList()
          restored[index] = (args[index] as Int).unmarked()
          val icon =
            chain.proceedWith(chain.thisObject, restored.toTypedArray()) as? Drawable
              ?: return@intercept null
          // 只给日志用的名字。取的是**还原后的真实 id** —— args 里那份是打过标记的假 id，
          // 拿它问资源名只会得到异常或错名字。只在「这确实是个被标记的应用图标 id」这条
          // 分支上做，热路径的其余 drawable 一次都不问。
          IconNames.remember(icon, resourceName(chain.thisObject as? Resources, restored[index] as Int))
          clipToCircle(icon)
        } finally {
          replacingIcon.set(false)
        }
      }
      true
    }
    .getOrDefault(false)

/** 归档应用的图标走的是独立的接口，不经过上面那两个出口。 */
private fun hookArchivedAppIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(packageManager, "getArchivedAppIcon")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val icon = chain.proceed() as? Drawable ?: return@intercept null
        clipToCircle(icon)
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "ArchivedAppIcon: $hooked hooked")
}

/**
 * 一些场景（电池页、某些缓存接口）不拿 resId 而是直接拿 Drawable —— 走
 * `getApplicationIcon` / `getActivityIcon` / `getDefaultActivityIcon` 等，
 * 它们直接返回 `Drawable`，没法用"resId 打标记"那套。这条路径用结果包装：
 * 不管原方法返回什么 Drawable，都套成 `CircleIconDrawable`（adaptive 图标
 * 系统自己会画圆，不动；其它一律裁圆）。
 */
private fun hookPackageManagerIconGetters(xposed: XposedInterface, classLoader: ClassLoader) {
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader) ?: return
  var hooked = 0
  for (name in listOf("getApplicationIcon", "getActivityIcon", "getDefaultActivityIcon", "loadItemIcon")) {
    for (method in declaredMethods(packageManager, name)) {
      runCatching {
        xposed.hook(method).intercept { chain ->
          val icon = chain.proceed() as? Drawable ?: return@intercept null
          clipToCircle(icon)
        }
        hooked++
      }
    }
  }
  if (hooked > 0) Log.d(TAG, "PM IconGetters: $hooked hooked")
}

/**
 * **跨进程出口还原**：把 icon 的标记换回真实 id 之后再写进 Parcel。
 *
 * 打过标记的 id（`0x6e…`）只有**本进程**的图标加载出口认得。system_server 在 PMS 里
 * 给 `PackageItemInfo` 打标记之后，这些对象会经 Binder 传给任意 app —— 接收方如果
 * 不在作用域里（没有我们的还原 hook），拿到的伪造 package id 在它自己的资源表里
 * 根本不存在，一解析就抛 `Resources$NotFoundException`，而且是**在 `Activity.onCreate`
 * 里抛的，直接把应用带崩**，不是"图标变空白"那么轻。
 *
 * 真机案例：华为应用市场 `MainActivity` 的 ActionBar 默认图标走
 * `Activity.initWindowDecorActionBar` → `PhoneWindow.setDefaultIcon` →
 * `Context.getDrawable(activityInfo.icon)`，拿到 `0x6e…` 连续两次冷启动都崩。
 *
 * 所以写进 Parcel 前必须还原成合法的 `0x7f…`。客户端收到后由 `readTypedList` /
 * `BaseParceledListSlice` / 构造器 hook 重新打标记，已注入进程的裁圆功能不受影响。
 */
private fun hookParcelWriteRestore(xposed: XposedInterface) {
  var hooked = 0

  // 所有 PackageItemInfo 子类（ApplicationInfo / ActivityInfo / ServiceInfo /
  // ProviderInfo）的 writeToParcel 都会调到基类这一层，一处覆盖全部。
  for (method in declaredMethods(PackageItemInfo::class.java, "writeToParcel")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val info =
          chain.thisObject as? PackageItemInfo
            ?: return@intercept chain.proceed()
        val saved = info.icon
        if (saved.isMarkedIcon()) info.icon = saved.unmarked()
        try {
          chain.proceed()
        } finally {
          info.icon = saved
        }
      }
      hooked++
    }
  }

  // ResolveInfo 不是 PackageItemInfo 的子类，它自己还带一份 icon / iconResourceId。
  for (method in declaredMethods(ResolveInfo::class.java, "writeToParcel")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val info =
          chain.thisObject as? ResolveInfo
            ?: return@intercept chain.proceed()
        val savedIcon = info.icon
        val savedResId = getIntField(info, "iconResourceId")
        if (savedIcon.isMarkedIcon()) {
          info.icon = savedIcon.unmarked()
          setIntField(info, "iconResourceId", savedIcon.unmarked())
        }
        try {
          chain.proceed()
        } finally {
          info.icon = savedIcon
          if (savedResId != null) setIntField(info, "iconResourceId", savedResId)
        }
      }
      hooked++
    }
  }

  if (hooked > 0) Log.d(TAG, "ParcelWrite: $hooked hooked (unmark before Binder)")
}

/**
 * 在图标信息**构造**时打标记。这是最基础的一条路径，覆盖 launcher / systemui /
 * settings 里逐个构造出来的 `ApplicationInfo` / `ActivityInfo` / `ResolveInfo`。
 */
private fun hookMarkedIconIds(xposed: XposedInterface) {
  val itemClasses =
    listOf(
      ApplicationInfo::class.java,
      ActivityInfo::class.java,
      ServiceInfo::class.java,
      ProviderInfo::class.java,
    )
  for (clazz in itemClasses) {
    for (ctor in declaredConstructors(clazz)) {
      runCatching {
        xposed.hook(ctor).intercept { chain ->
          val result = chain.proceed()
          val info = chain.thisObject as? PackageItemInfo ?: return@intercept result
          runMarkingIcons { markIcon(info) }
          result
        }
      }
    }
  }

  for (ctor in declaredConstructors(ResolveInfo::class.java)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed()
        runMarkingIcons { markResolveInfo(chain.thisObject as? ResolveInfo) }
        result
      }
    }
  }

  // PackageInfo 里的 applicationInfo / activities 等字段多数是构造之后才填的，
  // 但"构造完立刻用"的场合也有，先打一遍。批量通道里还会再打一次。
  for (ctor in declaredConstructors(PackageInfo::class.java)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed()
        markInfo(chain.thisObject as? PackageInfo)
        result
      }
    }
  }
}

/**
 * 批量 / 跨进程通道 —— **应用列表真正走的是这里**，不是上面那条逐个构造的路径。
 *
 * Settings 的应用列表、分享页、权限页拿到的图标是 PMS 一次性序列化过来的一整包
 * `PackageInfo` / `ResolveInfo`。只 hook 构造的话，这些列表里的图标根本不会经过
 * 我们的加载出口，于是仍然显示原图。
 */
private fun hookBatchIconIds(xposed: XposedInterface, classLoader: ClassLoader) {
  var hooked = 0

  for (method in declaredMethods(Parcel::class.java, "readTypedList")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val result = chain.proceed()
        markAll(result as? List<*>)
        result
      }
      hooked++
    }
  }

  for (method in declaredMethods(Parcel::class.java, "createTypedArray")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val result = chain.proceed()
        markAll((result as? Array<*>)?.asIterable())
        result
      }
      hooked++
    }
  }

  if (hooked > 0) Log.d(TAG, "Parcel: $hooked hooked")

  hookParceledListSlice(xposed, classLoader)
  hookPackageInfoCommonUtils(xposed, classLoader)
}

/**
 * 跨进程传大列表用的容器。一次性把整张列表打标记，比逐个处理快得多。
 */
private fun hookParceledListSlice(xposed: XposedInterface, classLoader: ClassLoader) {
  val base = classOf("android.content.pm.BaseParceledListSlice", classLoader) ?: return
  val mList = fieldOf(base, "mList") ?: return
  var hooked = 0
  for (ctor in declaredConstructors(base)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed()
        markAll(runCatching { mList.get(chain.thisObject) as? List<*> }.getOrNull())
        result
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "ParceledListSlice: $hooked hooked")
}

/**
 * PMS 生成图标信息的地方（system_server 进程）。在系统侧就打好标记，
 * 客户端拿到的一定是带标记的 id，覆盖面比在客户端补救大得多。
 */
private fun hookPackageInfoCommonUtils(xposed: XposedInterface, classLoader: ClassLoader) {
  val utils = classOf("com.android.internal.pm.parsing.PackageInfoCommonUtils", classLoader)
    ?: return
  val names =
    listOf(
      "generate",
      "generateApplicationInfo",
      "generateActivityInfo",
      "generateServiceInfo",
      "generateProviderInfo",
    )
  var hooked = 0
  for (name in names) {
    for (method in declaredMethods(utils, name)) {
      runCatching {
        xposed.hook(method).intercept { chain ->
          val result = chain.proceed()
          markInfo(result)
          result
        }
        hooked++
      }
    }
  }
  if (hooked > 0) Log.d(TAG, "PackageInfoCommonUtils: $hooked hooked")
}

/**
 * 最近任务 / 概览里的卡片图标。`TaskIconCache` 先把图标读成 `BitmapDrawable`，
 * 再包成 `BitmapInfo`；在这个入口上换成裁好的圆形即可。
 */
private fun hookTaskIcons(xposed: XposedInterface, classLoader: ClassLoader) {
  val cache = classOf("com.android.quickstep.TaskIconCache", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(cache, "getBitmapInfo")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val index = args.indexOfFirst { it is Drawable }
        if (index < 0) return@intercept chain.proceed()
        val replaced = args.toMutableList()
        replaced[index] = clipToCircle(args[index] as Drawable)
        chain.proceedWith(chain.thisObject, replaced.toTypedArray())
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "TaskIconCache: $hooked hooked")
}

/**
 * Android 16+ (BAKLAVA) 的 Pixel / AOSP Launcher3 在 `BaseIconFactory.createBadgedIconBitmap`
 * 里读取 `IconOptions.drawFullBleed`：true 时会**在 launcher 内部给图标再加一层白色背景板
 * 并把内容缩到 safe zone**，false 时按图标原样画（full-bleed）。
 *
 * 我们已经把图标处理成"圆形、内容填满、圆外透明"——这时 launcher 再加白圆板 +
 * safe-zone 缩小就会让用户看到"白圆 + 缩小"（即上一版模块的"白边"观感）。
 * 把 `drawFullBleed` 设成 false 让 launcher 不再加它的背景板，我们的圆形就直接呈现在桌面。
 *
 * 旧 Android 版本没有这个开关，本 hook 自动 no-op。
 */
private fun hookPixelLauncher(xposed: XposedInterface, classLoader: ClassLoader) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

  // Launcher3 的类在**不同 ROM 上包名不同**：
  // - 类原生 / AOSP（LineageOS、Sony 等）：进程 `com.android.launcher3`，
  //   内部类沿用 `com.android.launcher3.*`
  // - Pixel / Nexus Launcher：进程 `com.google.android.apps.nexuslauncher`，
  //   内部类**可能**被重打包到 `com.google.android.apps.nexuslauncher.*`
  // 两个都试一遍，哪个存在用哪个。
  val launcherPkgs = listOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")
  val baseIconFactoryClass =
    firstClassOf(launcherPkgs.map { "$it.icons.BaseIconFactory" }, classLoader)
      ?: run {
        Log.w(TAG, "Launcher: BaseIconFactory not found")
        return
      }
  val iconOptionsClass =
    firstClassOf(
      launcherPkgs.map { pkg -> pkg + ".icons.BaseIconFactory" + '$' + "IconOptions" },
      classLoader,
    )
      ?: run {
        Log.w(TAG, "Launcher: BaseIconFactory.IconOptions not found")
        return
      }
  val drawFullBleedField =
    fieldOf(iconOptionsClass, "drawFullBleed")
      ?: run {
        // 这个开关是承重的：拿不到它，桌面图标四角会出现黑色方角。留痕。
        Log.w(TAG, "Launcher: IconOptions.drawFullBleed not found")
        return
      }

  var hooked = 0
  for (method in
    baseIconFactoryClass.declaredMethods.filter { it.name == "createBadgedIconBitmap" }) {
    method.isAccessible = true
    runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val iconOptions = args.getOrNull(1)
        // 裁圆关掉时不要动这个开关：它存在的意义是让 launcher 别再加自己的白圆底板，
        // 那是我们圆形图标的配套设置，原样图标不需要它。
        if (ModuleRuntime.options().circleEnabled &&
          iconOptions != null && iconOptionsClass.isInstance(iconOptions)
        ) {
          // ⚠ 必须用 set() 而不是 setBoolean()：drawFullBleed 是**装箱**的
          // java.lang.Boolean，而 Field.setBoolean 只接受基本类型 boolean，
          // 对装箱字段抛 IllegalArgumentException("Not a primitive field")。
          // 之前用 setBoolean 时每一张桌面图标都会在保护模式里被吞掉一次异常
          // （真机日志里累计 9994 次），hook 被整体跳过 —— 这个开关从头到尾没生效过。
          // Field.set() 会自动拆箱，基本类型和装箱类型都成立。
          drawFullBleedField.set(iconOptions, false)
        }
        chain.proceed()
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "PixelLauncher: $hooked createBadgedIconBitmap hooked")
}

/**
 * 冷启动 splash 屏上的那张图标。
 *
 * 系统会分析图标的背景色，背景透明时它判定"图标没有可当背景的部分"，只画不透明区域。
 * 我们把图标裁成了圆（圆外透明），正好落进这个判定。强制标记"背景是复杂的"，
 * 让系统把整张图标画出来。
 *
 * ⚠ ROM 相关：Sony（XQ-DQ72 / Android 16）的 SystemUI 里这套 wm.shell 类被 R8 削成了
 * 空壳 —— `IconColor` 只剩 5 个字段，**连 `<init>` 都没有**（`ctors=0`），所以永远不会被
 * 实例化，这条 hook 在那里是空操作（钩子注册数为 0 是正常的，不是 bug）。
 * 参考项目在这台机器上同样如此。类完整的 ROM 上会正常注册。
 */
private fun hookSplashScreenIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  val iconColor =
    classOf(
      "com.android.wm.shell.startingsurface.SplashscreenContentDrawer\$ColorCache\$IconColor",
      classLoader,
    )
      ?: run {
        Log.w(TAG, "SplashScreen: IconColor class not found")
        return
      }
  val mBgColor = fieldOf(iconColor, "mBgColor")
  val mIsBgComplex = fieldOf(iconColor, "mIsBgComplex")
  // 失败必须留痕：这里曾经静默 return，查了半天才发现是 ROM 侧类被削过。
  if (mBgColor == null || mIsBgComplex == null) {
    Log.w(TAG, "SplashScreen: fields not found, skip")
    return
  }
  val ctors = declaredConstructors(iconColor)
  var hooked = 0
  var lastError: Throwable? = null
  for (ctor in ctors) {
    runCatching {
        xposed.hook(ctor).intercept { chain ->
          val result = chain.proceed()
          // 这是为圆形图标（圆外透明）准备的判定：原样图标不该被它影响。
          if (!ModuleRuntime.options().circleEnabled) return@intercept result
          runCatching {
            // 同 drawFullBleed：用 get()/set() 而不是 getBoolean()/setBoolean()，
            // 字段是基本类型还是装箱类型都能工作。
            if (mIsBgComplex.get(chain.thisObject) as? Boolean == true) return@runCatching
            val bgColor = mBgColor.get(chain.thisObject) as? Int ?: return@runCatching
            if (bgColor == 0) {
              mIsBgComplex.set(chain.thisObject, true)
            }
          }
          result
        }
        hooked++
      }
      .onFailure { lastError = it }
  }
  if (hooked > 0) Log.d(TAG, "SplashScreen: $hooked hooked")
  else Log.w(TAG, "SplashScreen: ctors=${ctors.size} hooked=0 err=${lastError?.message}")
}

/**
 * Android 15+ 的设置页会把图标再过一遍 `Utils.getAdaptiveIcon()`：非自适应图标会被它
 * **套上系统自己的形状（含系统自带的背景板）**。
 *
 * 这里在系统处理之前就把图标换成已经是 `AdaptiveIconDrawable` 的形态 —— 系统看到
 * "已经是 adaptive" 就不会再套它自己的那一层，图标按我们给的样子呈现。
 *
 * 必须 `deoptimize()`：这个方法会被内联优化掉，不 deoptimize 的话 hook 根本不会触发
 * （实测加之前日志里一次都没命中）。
 */
private fun hookSettingsAdaptiveIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
  val utils = classOf("com.android.settings.Utils", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(utils, "getAdaptiveIcon")) {
    runCatching {
      xposed.deoptimize(method)
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val sig = args.joinToString { it?.javaClass?.simpleName ?: "null" }
        val index = args.indexOfFirst { it is Drawable }
        if (index < 0) {
          Log.d(TAG, "Settings.getAdaptiveIcon($sig) no-drawable")
          return@intercept chain.proceed()
        }
        val inIcon = args[index] as Drawable
        Log.d(
          TAG,
          "Settings.getAdaptiveIcon($sig) idx=$index in=${inIcon.javaClass.simpleName} adaptive=${inIcon is AdaptiveIconDrawable}",
        )
        val replaced = args.toMutableList()
        replaced[index] = clipToCircle(inIcon)
        chain.proceedWith(chain.thisObject, replaced.toTypedArray())
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "Settings: $hooked getAdaptiveIcon hooked (deoptimized)")
}

/**
 * 电池用量页的图标**不走** `PackageManager`，也不走 `Resources.getDrawable`：
 * 它先把图标装进 `BatteryDiffEntry.mAppIcon`（旧版是 `BatteryEntry.mIcon`），
 * UI 再从 `getAppIcon()` 或者直接从字段里取。整条链上没有任何我们挂过的出口 ——
 * 实测打开电池页时 `clipToCircle` 一次都没被调用（trace 日志 0 条）。
 *
 * 所以这里对着这两个类直接下手：
 * - `getAppIcon()`：包装返回值
 * - `loadLabelAndIcon()` / `loadNameAndIcon()`：跑完之后把字段里的图标也换掉，
 *   这样"直接读字段"的地方同样是圆的
 */
private fun hookBatteryIcons(xposed: XposedInterface, classLoader: ClassLoader) {
  var hooked = 0

  val diffEntry =
    classOf("com.android.settings.fuelgauge.batteryusage.BatteryDiffEntry", classLoader)
  if (diffEntry != null) {
    for (name in listOf("getAppIcon", "getBadgeIconForUser")) {
      for (method in declaredMethods(diffEntry, name)) {
        runCatching {
          runCatching { xposed.deoptimize(method) }
          xposed.hook(method).intercept { chain ->
            val icon = chain.proceed() as? Drawable
            if (icon == null) null else clipToCircle(icon)
          }
          hooked++
        }
      }
    }
    for (name in listOf("loadLabelAndIcon", "loadNameAndIconForUid")) {
      for (method in declaredMethods(diffEntry, name)) {
        runCatching {
          runCatching { xposed.deoptimize(method) }
          xposed.hook(method).intercept { chain ->
            val result = chain.proceed()
            wrapIconField(chain.thisObject, "mAppIcon")
            result
          }
          hooked++
        }
      }
    }
  }

  val entry = classOf("com.android.settings.fuelgauge.batteryusage.BatteryEntry", classLoader)
  if (entry != null) {
    for (method in declaredMethods(entry, "loadNameAndIcon")) {
      runCatching {
        runCatching { xposed.deoptimize(method) }
        xposed.hook(method).intercept { chain ->
          val result = chain.proceed()
          wrapIconField(chain.thisObject, "mIcon")
          result
        }
        hooked++
      }
    }
  }

  if (hooked > 0) Log.d(TAG, "Battery: $hooked hooked")
}

/**
 * 把 `owner` 里叫 `name` 的 Drawable 字段换成裁圆后的版本。
 *
 * 已经是 `AdaptiveIconDrawable` 的不动 —— 自己包出来的 [CircleIconDrawable] 也是 adaptive，
 * 所以这个判断同时避免了对同一个图标反复包。
 */
private fun wrapIconField(owner: Any?, name: String) {
  if (owner == null) return
  runCatching {
    val field = fieldOf(owner.javaClass, name) ?: return
    val icon = field.get(owner) as? Drawable ?: return
    if (icon !is AdaptiveIconDrawable) field.set(owner, clipToCircle(icon))
  }
}

private fun hookShortcutIcons(xposed: XposedInterface) {
  var hooked = 0
  for (method in declaredMethods(LauncherApps::class.java, "getShortcutIconDrawable")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val icon = chain.proceed() as? Drawable ?: return@intercept null
        clipToCircle(icon)
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "Shortcut: $hooked hooked")
}

private fun markAll(items: Iterable<*>?) {
  val list = items ?: return
  // ⚠ 这一圈**不能**再套 runMarkingIcons：markInfo 的每个分支自己就带这道闸，
  // 而它是 inline + 非局部返回 —— 外层先置位，里面每次调用都直接 return，
  // 整条批量通道会一个 id 都不打地静默空转（就是之前的实锤 bug）。
  // 逐条上锁才是想要的粒度：条目之间要互相放行，同一条内部的嵌套才要挡住。
  for (item in list) markInfo(item)
}

private fun markInfo(info: Any?) {
  when (info) {
    is PackageInfo ->
      runMarkingIcons {
        info.applicationInfo?.let(::markIcon)
        info.activities?.forEach(::markIcon)
        info.services?.forEach(::markIcon)
        info.providers?.forEach(::markIcon)
      }
    is PackageItemInfo -> runMarkingIcons { markIcon(info) }
    is ResolveInfo -> runMarkingIcons { markResolveInfo(info) }
    // 无障碍服务列表：服务信息藏在 resolveInfo 里
    is AccessibilityServiceInfo -> runMarkingIcons { markResolveInfo(info.resolveInfo) }
    else -> {
      if (info == null) return
      runMarkingIcons { markNestedInfo(info) }
    }
  }
}

/**
 * 外层对象本身不是 `PackageItemInfo`、图标信息**藏在字段里**的那些类型。
 *
 * 只按外层类型判断的话，这些列表一个都覆盖不到 —— 最近任务、冷启动 splash
 * 传的正是这类对象：
 * - `TaskInfo.topActivityInfo`：`RunningTaskInfo` / `RecentTaskInfo` 都继承它
 *   （最近任务 / 概览 / 分屏选择器）
 * - `LaunchActivityItem.mInfo`：启动 Activity 时带的那份 `ActivityInfo`，
 *   **冷启动 splash 屏上的图标就是从这里来的**
 * - `LauncherActivityInfoInternal.mActivityInfo`：`LauncherApps` 内部传递用
 *
 * 一律用"类名字符串 + 反射字段"取，不写 `is TaskInfo` 这种直接引用 ——
 * 这些类在旧版本设备上不存在，直接引用会在类加载时炸掉。
 */
private val nestedInfoFields by lazy {
  listOf(
      "android.app.TaskInfo" to "topActivityInfo",
      "android.app.servertransaction.LaunchActivityItem" to "mInfo",
      "android.content.pm.LauncherActivityInfoInternal" to "mActivityInfo",
    )
    .mapNotNull { (className, fieldName) ->
      runCatching {
        val clazz = Class.forName(className)
        clazz to (fieldOf(clazz, fieldName) ?: throw NoSuchFieldException(fieldName))
      }
        .getOrNull()
    }
}

private fun markNestedInfo(info: Any) {
  for ((clazz, field) in nestedInfoFields) {
    if (!clazz.isInstance(info)) continue
    val inner = runCatching { field.get(info) }.getOrNull() ?: continue
    markInfo(inner)
  }
}

private fun markIcon(info: PackageItemInfo) {
  // 快捷设置磁贴画的是小尺寸单色图形，不是应用图标。
  if (info is ServiceInfo && info.permission == Manifest.permission.BIND_QUICK_SETTINGS_TILE) return
  markIconResId(info)
  // 组件自己没声明图标时，系统会回退到 `applicationInfo.icon` —— 那个 id 走的是
  // 同一条解析路径，也得打标记。参考项目的 `componentInfosTransform` 同样把这一层
  // 一起处理（itemInfos + applicationInfo）；漏掉它，这类"图标为 0 的组件"就是方的。
  if (info is ComponentInfo) info.applicationInfo?.let(::markIconResId)
}

private fun markIconResId(info: PackageItemInfo) {
  // 总开关：关掉之后不再打标记，下游任何进程拿到的都是没被碰过的原始 id。
  // 这是「关闭」最彻底的一层 —— system_server 里读到关闭，连标记都不会产生。
  // 配置按 TTL 重读，所以界面里关掉后很快生效。
  if (!ModuleRuntime.options().circleEnabled) return
  val icon = info.icon
  if (icon != 0 && icon.isAppResource()) info.icon = icon.marked()
}

private fun markResolveInfo(info: ResolveInfo?) {
  val resolveInfo = info ?: return
  if (!ModuleRuntime.options().circleEnabled) return
  // 组件自己的 icon 在构造时已经打过标记，这里原样继承即可。
  val component =
    resolveInfo.activityInfo ?: resolveInfo.serviceInfo ?: resolveInfo.providerInfo ?: return
  val icon = component.icon
  if (icon == 0) return
  resolveInfo.icon = icon
  setIntField(resolveInfo, "iconResourceId", icon)
}

private fun declaredMethods(clazz: Class<*>, name: String): List<Method> =
  clazz.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

private fun declaredConstructors(clazz: Class<*>): List<Constructor<*>> =
  clazz.declaredConstructors.toList().onEach { it.isAccessible = true }

/**
 * Android 16「强制动态取色」下，**没适配单色图标的应用**会被桌面现场生成一张单色遮罩
 * （`com.android.launcher3.icons.MonochromeIconFactory`），而这张遮罩的**极性是反的**：
 * 深色字形 + 浅色底的图标会变成"字形是洞、底色是形"，看着整个反掉。
 *
 * ## 这是**兜底**，首选方案是 [hookSynthesizedMonochrome]
 * 那一条钩在更上游：它让 `AdaptiveIconDrawable.getMonochrome()` 直接返回一个极性正确的
 * 合成层，桌面因此根本不会 new 这个 Factory（桌面只在这个 getter 返回 null 时才生成）——
 * 算错的那条路压根不进，比"让它算错再翻回来"干净。
 *
 * 本函数保留下来是为了**合成没接上时仍能工作**：合成钩挂不上（framework 类的钩在
 * 部分框架上受限）、或者合成抛异常返回 null 时，桌面照旧走 Factory，
 * 这里再翻一次。**两条路互斥，不需要开关去切** —— 合成一旦生效，这里就不会被触发。
 *
 * **为什么除了用户开关之外不再判别的条件**：桌面只在 `AdaptiveIconDrawable.getMonochrome()`
 * 为 null（= 应用没提供单色图标）时才 new 这个 Factory —— **已适配的应用根本不会走到这里**。
 * 所以"只翻未适配的"这件事由调用点天然保证，这里不需要再判断前景/背景亮度差。
 * 唯一的开关是界面上的「修正单色图标的极性」（[ModuleRuntime.options] 的 `monoInvert`），
 * 它是逃生口：个别 ROM / 图标包本身就该保持原样时用它关掉，不是在问用户口味。
 *
 * **链路（已逐条对过字节码）**：`generateMono()` 把结果写进 `mAlphaBitmap`，
 * `wrap()` 返回包住本对象的 `InsetDrawable`，`MonoIconThemeController.toAlphaBitmap()`
 * 把它画进一张 ALPHA_8 位图 —— 所以改 `mAlphaBitmap` 一定传到屏幕上，中间没有二次翻转。
 */
private fun hookForcedThemedMono(xposed: XposedInterface, classLoader: ClassLoader) {
  val factory =
    classOf("com.android.launcher3.icons.MonochromeIconFactory", classLoader)
      ?: run {
        // 没有这个类 = 这个 ROM / 桌面版本还没有强制取色，不是 bug。
        Log.w(TAG, "Mono: MonochromeIconFactory not found")
        return
      }
  val alphaField = fieldOf(factory, "mAlphaBitmap")
  if (alphaField == null) {
    Log.w(TAG, "Mono: mAlphaBitmap not found, skip")
    return
  }

  var hooked = 0
  for (method in declaredMethods(factory, "generateMono")) {
    runCatching {
        xposed.hook(method).intercept { chain ->
          // void 方法：intercept 期望返回 Any!，先存下来最后 return，别用 return@intercept。
          val result = chain.proceed()
          val self = chain.thisObject
          if (self == null) {
            Log.w(TAG, "Mono: thisObject is null")
            return@intercept result
          }
          runCatching {
              // 反色开关（界面上「修正单色图标极性」）。关掉就是把桌面自己生成的那张
              // 遮罩原样留着 —— 只在这个 ROM / 桌面版本实测不该翻的时候才需要关。
              // 按调用时判断而不是装钩时判断，切开关才不需要重启桌面。
              if (!ModuleRuntime.options().monoInvert) return@runCatching
              val bitmap = alphaField.get(self) as? Bitmap
              if (bitmap == null) Log.w(TAG, "Mono: mAlphaBitmap is null")
              else if (bitmap.config != Bitmap.Config.ALPHA_8)
                Log.w(TAG, "Mono: unexpected config ${bitmap.config}, skip")
              else {
                invertAlphaMask(bitmap)
                val total = monoInverted.incrementAndGet()
                if (total % 40 == 0) Log.d(TAG, "Mono: $total mono masks inverted")
              }
            }
            .onFailure { Log.w(TAG, "Mono: failed: ${it.message}") }
          result
        }
        hooked++
      }
      .onFailure { Log.w(TAG, "Mono: hook failed: ${it.message}") }
  }
  if (hooked > 0) Log.d(TAG, "Mono: $hooked generateMono hooked")
  else Log.w(TAG, "Mono: generateMono NOT hooked")
}

/**
 * 首选方案：给没适配的应用**凭空合成一个单色层**，让桌面那条算错的路径根本不进。
 *
 * 桌面只在 `AdaptiveIconDrawable.getMonochrome()` 返回 null 时才去 new 那个 Factory
 * 现场生成掩码（那条路算出来的极性经常是反的，见 [hookForcedThemedMono]）。
 * 所以这里只要**补一个非 null**，桌面就走正常路径 —— 算错的机会根本不出现。
 *
 * 比"让它算错再翻回来"好在三处：不用等它先算一遍再翻（省一次全像素往返）、
 * 不依赖 `mAlphaBitmap` 这个私有字段名、极性判据在我们手里（能正确处理
 * 深色底+白色字形的图标，而桌面对那类图标正是会算反的）。
 *
 * ⚠ hook 的是 framework 的类，部分框架对 bootclasspath 上的类挂钩有限制；
 * 挂不上就只打一条警告，[hookForcedThemedMono] 那道翻转会照常兜底。
 * 合成器自己抛异常时也返回 null，同样落到兜底上 —— **不会让图标变得更糟**。
 */
private fun hookSynthesizedMonochrome(xposed: XposedInterface) {
  val method = runCatching {
    AdaptiveIconDrawable::class.java.getDeclaredMethod("getMonochrome")
  }.getOrNull()
  if (method == null) {
    Log.w(TAG, "Mono: getMonochrome not found，走翻转兜底")
    return
  }
  runCatching {
      xposed.hook(method).intercept { chain ->
        // 应用自己提供了单色图标：原样透传，一个像素都不碰。
        val original = chain.proceed() as? Drawable
        if (original != null) return@intercept original
        // 开关关掉 = 什么都不补，桌面照它自己的方式处理（逃生口）
        if (!ModuleRuntime.options().monoInvert) return@intercept null
        val self = chain.thisObject as? AdaptiveIconDrawable
        if (self == null) {
          Log.w(TAG, "Mono: synth thisObject is null")
          return@intercept null
        }
        // CircleIconDrawable **自己重写了 getMonochrome**（见那个类的说明），
        // 本钩拦不到它；万一框架把 override 也一起拦了，这里也交给它自己的实现 ——
        // 用本对象（裁圆之后的）压剪影只会得到一个实心圆饼，那是错的。
        if (self is CircleIconDrawable) return@intercept null
        MonochromeSynth.forIcon(self)?.let { synth ->
          val total = monoSynthesized.incrementAndGet()
          if (total % 40 == 0) Log.d(TAG, "Mono: $total mono layers synthesized")
          return@intercept synth
        }
        null
      }
      Log.d(TAG, "Mono: getMonochrome hooked")
    }
    .onFailure { Log.w(TAG, "Mono: getMonochrome hook failed: ${it.message}") }
}

/** 累计合成了多少张单色层。热路径，按 40 条汇总一次，不逐条打。 */
private val monoSynthesized = AtomicInteger(0)

/** 单色遮罩翻转：ALPHA_8，一个像素一字节，a → 255 - a。
 *
 * 每张图标一次全像素往返本身无法避免（要进 JNI 再回来），
 * 但用来传像素的那块 `byte[]` 不必每次新建 —— 热路径上每分钟数百张，
 * `ByteArray` 是 Eden 区短命大对象，回收压力会显在 GC 抖动上。
 * 用按大小**就地扩容**的缓存复用：旧数组装得下就接着用，装不下就换一块
 * 稍大的（按 1.25 倍，避免下一张又恰好只大一个字节、再分配一次）。
 *
 * 多线程：每个 binder 线程各自一份，互不干扰、零锁。
 */
private val invertBuffer = ThreadLocal.withInitial { ByteArray(0) }

private fun invertAlphaMask(bitmap: Bitmap) {
  val need = bitmap.rowBytes * bitmap.height
  var arr = invertBuffer.get()
  if (arr.size < need) {
    // 1.25 倍上限，避免下一张恰好大一字节时又扩一次
    arr = ByteArray(maxOf(need, arr.size + (arr.size shr 2)))
  }
  val buffer = ByteBuffer.wrap(arr, 0, need)
  bitmap.copyPixelsToBuffer(buffer)
  for (i in 0 until need) {
    arr[i] = (255 - (arr[i].toInt() and 0xff)).toByte()
  }
  buffer.position(0)
  bitmap.copyPixelsFromBuffer(buffer)
  invertBuffer.set(arr)
}

/** 累计翻了多少张遮罩。热路径，按 40 条汇总一次，不逐条打。 */
private val monoInverted = AtomicInteger(0)

private fun classOf(name: String, classLoader: ClassLoader): Class<*>? =
  runCatching { Class.forName(name, true, classLoader) }.getOrNull()

/**
 * 按候选名依次尝试，返回**第一个存在**的类。
 *
 * Launcher3 的内部类在不同 ROM 上包名不同（AOSP 的 `com.android.launcher3.*` vs
 * Pixel 的 `com.google.android.apps.nexuslauncher.*`），所以类名不能写死一个。
 */
private fun firstClassOf(names: List<String>, classLoader: ClassLoader): Class<*>? =
  names.firstNotNullOfOrNull { classOf(it, classLoader) }

/**
 * 按名字找字段，**沿父类链往上找**（与参考项目的 `field()` 一致）。
 *
 * ROM 侧的类是会被 R8 改写的：字段可能被挪到父类、方法可能被内联。只查本类
 * `getDeclaredField` 会漏掉这些情况，而且漏掉时是**静默**的 —— 不报错、不打日志。
 */
private fun fieldOf(clazz: Class<*>, name: String): Field? {
  var current: Class<*>? = clazz
  while (current != null && current != Any::class.java) {
    val found = runCatching { current!!.getDeclaredField(name) }.getOrNull()
    if (found != null) return found.apply { isAccessible = true }
    current = current.superclass
  }
  return null
}

private fun setIntField(obj: Any, name: String, value: Int) = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
}

/** 读 int 字段；字段在旧版本 / 被 R8 改过的类上可能不存在，取不到就返回 null。 */
private fun getIntField(obj: Any, name: String): Int? = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj) as Int
}
  .getOrNull()

/**
 * 「这个 Drawable 是哪个应用的图标」对照表 —— **只给日志用**，不参与任何判断。
 *
 * 为什么非要它：这一路的反馈全都长这样——「12123 / termux / 闲鱼 不对」。而 `Mono: diag`
 * 行里只有 `AdaptiveIconDrawable fg=VectorDrawable@425x425` 这种 drawable 签名，两边对不上
 * 号，每一轮排查都只能靠猜（上一轮能定位到淘宝，纯属它的特征恰好独占一类）。名字进日志之后，
 * 报一个应用名就能直接捞到它那一行，判据定得对不对当场可见。
 *
 * ⚠ 认不出是常态，不是错误：launcher 复制图标（`newDrawable` / `mutate`）之后实例会变，
 *   所以除了实例还记一份 `ConstantState`；两条都不命中就打 `?`。**绝不为了记名字去改
 *   应用那份图标对象** —— 那正是本模块吃过亏的地方（bounds 被动过 = 图标画歪 / 画糊）。
 *
 * 内存：WeakHashMap。图标对象由 launcher 的 IconCache 长期持有，但换主题、卸载应用之后
 * 会被丢掉，用强引用等于把整批 drawable 钉在桌面进程里。
 */
internal object IconNames {

  private val byDrawable = WeakHashMap<Drawable, String>()

  private val byState = WeakHashMap<Drawable.ConstantState, String>()

  @Synchronized
  fun remember(icon: Drawable, name: String) {
    if (name.isEmpty()) return
    if (byDrawable.size >= LIMIT) { byDrawable.clear(); byState.clear() }
    byDrawable[icon] = name
    icon.constantState?.let { byState[it] = name }
  }

  /** 命中就返回 `包名/类型/入口名`，认不出返回 `?`。 */
  @Synchronized
  fun of(icon: Drawable): String {
    byDrawable[icon]?.let { return it }
    icon.constantState?.let { byState[it] }?.let { return it }
    if (icon is AdaptiveIconDrawable) {
      val fg = icon.foreground
      if (fg != null) {
        byDrawable[fg]?.let { return it }
        fg.constantState?.let { byState[it] }?.let { return it }
      }
    }
    return "?"
  }

  private const val LIMIT = 4096
}

/** 资源 id → 可读名字。取不到（跨包资源、被回收的 id）就交空串，让调用方跳过登记。 */
private fun resourceName(res: Resources?, id: Int): String = runCatching {
  if (res == null) "" else "${res.getResourcePackageName(id)}/${res.getResourceTypeName(id)}/${res.getResourceEntryName(id)}"
}.getOrDefault("")
