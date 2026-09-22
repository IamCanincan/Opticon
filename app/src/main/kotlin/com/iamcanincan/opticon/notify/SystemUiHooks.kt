package com.iamcanincan.opticon.notify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.RemoteViews
import com.iamcanincan.opticon.runtime.MemberLookup
import com.iamcanincan.opticon.runtime.ModuleRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 往 SystemUI 里装挂钩。
 *
 * 这里挂的全是私有类/私有方法，任何一环在不同版本上都可能改名或改签名，
 * 所以每个挂钩都是「找不到就记日志跳过」，绝不让 SystemUI 因为装钩失败而崩。
 * 装上的钩子会以 <方法名> hooked 打进日志，真机排障时看这个就知道缺了哪一段。
 */
object SystemUiHooks {

    private val EXCEPTION_MODE = XposedInterface.ExceptionMode.PROTECTIVE

    private const val ROW_BINDER_MODERN =
        "com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl"
    private const val ROW_BINDER_LEGACY =
        "com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl"
    private const val NOTIFICATION_ENTRY =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val ICON_MANAGER =
        "com.android.systemui.statusbar.notification.icon.IconManager"
    private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
    private const val STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
    private const val CONTRAST_UTIL = "com.android.internal.util.ContrastColorUtil"

    /** 已挂过的方法，防止框架重复回调时重复挂钩 */
    // 线程安全集合：安装路径虽然目前只走主线程，但框架的回调时机不由我们决定，
    // 用并发集合换掉 HashSet，避免万一并发时的静默数据损坏（零成本）。
    private val hookedMethods = java.util.concurrent.ConcurrentHashMap.newKeySet<java.lang.reflect.Method>()

    /**
     * 已装过的挂钩 id。
     *
     * 框架会多次回调 onPackageReady（实测 Vector 一个进程回调 2~3 次）。
     * inflateViews 可以按 Method 对象判重（同一个方法对象只会拿到一次），
     * 但下面这几个是「按名字现找」的，找不到-object 级别的稳定标识，
     * 只能按 id 判重 —— 否则同一条钩子会被装两遍，拦截逻辑跟着跑两遍。
     * （三处拦截都是幂等的，不会出错，但白白多跑一次，日志也会重复刷。）
     */
    private val installedHookIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 主挂钩（inflateViews）是否已就位。
     *
     * 给调用方判断「还要不要再重试」用。框架会对同一个进程多次派发 packageReady，
     * 而后几次带来的 ClassLoader 可能根本用不了（实测第三次派发 5 个目标类全 missing）——
     * 那时若还照着重试，就是为一个永远好不了的 ClassLoader 空转 5 秒。
     * 挂钩一旦装上就跟 ClassLoader 无关了，装过即收工。
     */
    @Volatile
    private var rowInflationInstalled = false

    fun isInstalled(): Boolean = rowInflationInstalled

    /** 挂载时会逐个探测这些类是否存在，缺的会打进日志 */
    private val TARGET_CLASSES = listOf(
        ROW_BINDER_MODERN, ROW_BINDER_LEGACY, NOTIFICATION_ENTRY,
        ICON_MANAGER, STATUS_BAR_ICON, STATUS_BAR_ICON_VIEW, CONTRAST_UTIL
    )

    /**
     * 装挂钩。返回「主挂钩是否已就位」。
     *
     * ⚠ 返回 false 时调用方**必须重试**：第一次 packageReady 有可能来得太早 ——
     * 那时框架还在建 ClassLoader（`LoadedApk.createOrUpdateClassLoaderLocked`），
     * 目标类一个都 load 不出来。实测开机那次就是 5 个类全 missing、一个钩都没装上。
     *
     * 重试是幂等的：`hookedMethods` / `installedHookIds` 会挡掉重复挂钩。
     *
     * @param quiet 重试时传 true，避免每轮都把环境探测那几行重复刷一遍
     */
    fun install(module: XposedModule, classLoader: ClassLoader, quiet: Boolean = false): Boolean {
        if (!quiet) reportEnvironment(classLoader)
        if (!installRowInflation(module, classLoader)) return false
        installColorRetention(module, classLoader)
        return true
    }

    /**
     * 挂载前先把环境探一遍，把「哪些类没找到」直接打进日志。
     *
     * SystemUI 的类名历代改得很勤，而这一步离线没法验证。真机上只要看一眼
     * `adb logcat -s Opticon` 开头的 missing: 就知道要换哪个名字，不用猜。
     */
    private fun reportEnvironment(classLoader: ClassLoader) {
        ModuleRuntime.logI("device sdk=${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        val missing = TARGET_CLASSES.filter { MemberLookup.findClass(it, classLoader) == null }
        if (missing.isEmpty()) {
            ModuleRuntime.logI("all target classes resolved")
        } else {
            for (name in missing) ModuleRuntime.logW("missing class: $name")
        }
    }

    /**
     * 通知行 inflate 之前把小图标换掉。
     * 这是主挂钩点：换图发生在这里，后面几个钩只是保证换完的图不被染回单色。
     *
     * 返回是否已就位（false = 目标类还 load 不出来，调用方要重试）。
     */
    private fun installRowInflation(module: XposedModule, classLoader: ClassLoader): Boolean {
        val binderClass = MemberLookup.findClass(ROW_BINDER_MODERN, classLoader)
            ?: MemberLookup.findClass(ROW_BINDER_LEGACY, classLoader)
        if (binderClass == null) {
            ModuleRuntime.logW("NotificationRowBinderImpl not resolvable yet")
            return false
        }
        // entry 类是「认出哪个参数是通知条目」的钥匙；认不出来也不能就此放弃，
        // 下面退化为「带参数的 inflateViews 都挂上，运行时再逐个参数试着取 sbn」
        val entryClass = MemberLookup.findClass(NOTIFICATION_ENTRY, classLoader)
        if (entryClass == null) ModuleRuntime.logW("NotificationEntry unresolved, falling back to parameter probing")

        val candidates = binderClass.declaredMethods.filter { it.name == "inflateViews" }
        val overloads = candidates.filter { method ->
            if (entryClass != null) method.parameterTypes.contains(entryClass)
            else method.parameterTypes.isNotEmpty()
        }
        if (overloads.isEmpty()) {
            ModuleRuntime.logE("inflateViews not found", NoSuchMethodException("inflateViews"))
            return false
        }

        for (method in overloads) {
            method.isAccessible = true
            // 框架会对同一个进程多次回调 onPackageReady（实测 Vector 会调 2~3 次），
            // 不判重就会把同一个方法挂多次，导致每条通知被重复处理
            if (!hookedMethods.add(method)) continue
            // id 必须各不相同，多个重载不能共用同一个 hook id
            val hookId = "inflateViews[${method.parameterTypes.joinToString { it.simpleName }}]"

            module.hook(method).setId(hookId).setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                captureSystemContext(chain.thisObject)

                for (arg in chain.args) {
                    if (arg == null) continue
                    // entryClass 为 null 时放行所有参数，靠能否取出 sbn 来判断
                    if (entryClass != null && !entryClass.isInstance(arg)) continue
                    runCatching {
                        val sbn = (MemberLookup.readFieldByType(arg, StatusBarNotification::class.java)
                            ?: MemberLookup.readField(arg, "mSbn")) as? StatusBarNotification ?: return@runCatching
                        val context = ModuleRuntime.hostContext ?: return@runCatching
                        // TYPE_RESOURCE 的图标必须用目标应用自己的 Resources 解码
                        val targetContext = runCatching {
                            context.createPackageContext(
                                sbn.packageName,
                                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                            )
                        }.getOrDefault(context)
                        NotificationIconPatch.patch(sbn, targetContext)
                    }.onFailure { ModuleRuntime.logE("inflateViews patch failed", it) }
                }
                chain.proceed()
            }
        }
        ModuleRuntime.logI("inflateViews hooked (${overloads.size} overload(s))")
        return true
    }

    /** 顺一个 SystemUI 的 Context 出来，后面取包名资源要用 */
    private fun captureSystemContext(binder: Any?) {
        val context = binder?.let { MemberLookup.readField(it, "mContext") as? Context }
            ?: ModuleRuntime.hostContext
        if (context != null) ModuleRuntime.hostContext = context
    }

    /**
     * 保色相关挂钩。分三处，缺一处就会出现「图换了但被染成灰白」的半成品效果：
     * 1. IconManager#setIcon —— 打上 icon_is_pre_L 标记，跳过统一着色；
     * 2. StatusBarIconView#updateIconColor —— 非单色图标不强制设色；
     * 3. Notification.Builder#processSmallIconColor —— 去掉外圈圆底和留白。
     *
     * 要用 getIdentifier 取 SystemUI 内部的资源 id（icon_is_pre_L、left_icon），
     * 这些 id 不在本模块的编译资源里，只能按名字反查，故抑制 DiscouragedApi。
     */
    @SuppressLint("DiscouragedApi")
    private fun installColorRetention(module: XposedModule, classLoader: ClassLoader) {
        val iconManager = MemberLookup.findClass(ICON_MANAGER, classLoader)
        val entryClass = MemberLookup.findClass(NOTIFICATION_ENTRY, classLoader)
        val statusBarIcon = MemberLookup.findClass(STATUS_BAR_ICON, classLoader)
        val statusBarIconView = MemberLookup.findClass(STATUS_BAR_ICON_VIEW, classLoader)

        val setIcon = if (iconManager != null && entryClass != null && statusBarIcon != null && statusBarIconView != null) {
            MemberLookup.methodWithParams(iconManager, "setIcon", entryClass, statusBarIcon, statusBarIconView)
        } else {
            null
        }

        if (setIcon == null) {
            if (iconManager != null) logCandidates(iconManager, "setIcon")
        } else if (installedHookIds.add("setIcon")) {
            module.hook(setIcon).setId("setIcon").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                if (shouldKeepColor()) {
                    for (arg in chain.args) {
                        if (arg is View) {
                            val tagId = arg.context.resources.getIdentifier("icon_is_pre_L", "id", "com.android.systemui")
                            if (tagId != 0) arg.setTag(tagId, true)
                        }
                    }
                }
                chain.proceed()
            }
            ModuleRuntime.logI("setIcon hooked")
        }

        // 注意：这里不能因为 StatusBarIconView 找不到就整个返回 —— processSmallIconColor
        // 是独立的一条保色路径，少挂它就只能退回到「换了图但带灰底」的效果
        if (statusBarIconView != null) {
            hookUpdateIconColor(module, classLoader, statusBarIconView)
        }

        installSmallIconColor(module, classLoader)
    }

    private fun hookUpdateIconColor(module: XposedModule, classLoader: ClassLoader, iconView: Class<*>) {
        // 灰度判定要用系统的 ContrastColorUtil，拿不到就没法判断，这一钩直接跳过
        val contrastUtil = MemberLookup.findClass(CONTRAST_UTIL, classLoader)
        if (contrastUtil == null) {
            ModuleRuntime.logW("ContrastColorUtil not found, updateIconColor skipped")
            return
        }
        // 先找方法、再登记 id。反过来的话，方法没找到也把 id 占了，
        // 后面几轮重试会直接 return，那行诊断日志就永远打不出来
        val method = MemberLookup.methodWithParams(iconView, "updateIconColor")
        if (method == null) {
            logCandidates(iconView, "updateIconColor")
            return
        }
        if (!installedHookIds.add("updateIconColor")) return
        module.hook(method).setId("updateIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
            if (shouldKeepColor()) {
                runCatching {
                    val view = chain.thisObject as View
                    val sbn = MemberLookup.readField(view, "mNotification") as? StatusBarNotification
                    if (sbn != null && sbn.packageName != "android") {
                        val context = view.context
                        val instance = MemberLookup.invokeStatic(
                            contrastUtil, "getInstance", arrayOf(context), Context::class.java
                        ) ?: return@runCatching
                        val isGrayscale = MemberLookup.invoke(
                            instance, "isGrayscaleIcon",
                            arrayOf(context, sbn.notification.smallIcon),
                            Context::class.java, Icon::class.java
                        ) as? Boolean
                        if (isGrayscale == false) {
                            // 写失败 = 保色没生效（图标会被系统重新染色）。静默失败最难查，所以打出来
                            val written = MemberLookup.writeField(view, "mCurrentSetColor", 0)
                            if (!written) {
                                ModuleRuntime.logW(
                                    "mCurrentSetColor not writable on ${view.javaClass.simpleName}," +
                                        " icon may still be tinted"
                                )
                            }
                        }
                    }
                }.onFailure { ModuleRuntime.logE("updateIconColor failed", it) }
            }
            chain.proceed()
        }
        ModuleRuntime.logI("updateIconColor hooked")
    }

    @SuppressLint("DiscouragedApi")
    private fun installSmallIconColor(module: XposedModule, classLoader: ClassLoader) {
        val builderClass = MemberLookup.findClass("android.app.Notification\$Builder", classLoader)
            ?: return
        // 嵌套类的二进制名必须用 $ 分隔，写成 . 会让 loadClass 返回 null
        val paramsClass = MemberLookup.findClass("android.app.Notification\$StandardTemplateParams", classLoader)

        val exact = if (paramsClass != null) {
            runCatching {
                MemberLookup.declaredMethod(
                    builderClass, "processSmallIconColor",
                    Icon::class.java, RemoteViews::class.java, paramsClass
                )
            }.getOrNull()
        } else {
            null
        }
        val method = exact ?: fallbackSmallIconColor(builderClass)
        if (method == null) {
            logCandidates(builderClass, "processSmallIconColor")
            return
        }
        if (!installedHookIds.add("processSmallIconColor")) return

        module.hook(method).setId("processSmallIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
            if (shouldKeepColor()) {
                runCatching {
                    val context = (MemberLookup.readField(chain.thisObject, "mContext") as? Context)
                        ?: ModuleRuntime.hostContext
                    val smallIcon = chain.getArg(0) as Icon
                    val contentView = chain.getArg(1) as RemoteViews
                    val colorUtil = MemberLookup.invoke(chain.thisObject, "getColorUtil")
                        ?: return@runCatching
                    val isGrayscale = MemberLookup.invoke(
                        colorUtil, "isGrayscaleIcon",
                        arrayOf(context, smallIcon), Context::class.java, Icon::class.java
                    ) as? Boolean
                    if (isGrayscale == false && context != null) {
                        contentView.setInt(android.R.id.icon, "setBackgroundResource", android.R.color.transparent)
                        contentView.setViewPadding(android.R.id.icon, 0, 0, 0, 0)
                        val leftIcon = context.resources.getIdentifier("left_icon", "id", "com.android.systemui")
                        if (leftIcon != 0) contentView.setViewPadding(leftIcon, 0, 0, 0, 0)
                    }
                }.onFailure { ModuleRuntime.logE("processSmallIconColor failed", it) }
            }
            chain.proceed()
        }
        ModuleRuntime.logI("processSmallIconColor hooked")
    }

    /**
     * StandardTemplateParams 是私有嵌套类，取不到时不能就此放弃：
     * 按「名字 + 三参数 + 首参是 Icon」这条弱特征再找一次，够用了。
     */
    private fun fallbackSmallIconColor(builderClass: Class<*>): Method? {
        val method = builderClass.declaredMethods.firstOrNull {
            it.name == "processSmallIconColor" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Icon::class.java
        }
        if (method != null) ModuleRuntime.logW("processSmallIconColor resolved by signature fallback")
        return method
    }

    private fun shouldKeepColor(): Boolean {
        val options = ModuleRuntime.options()
        return options.notifyEnabled && options.keepOriginalColor
    }

    /**
     * 方法没按预期签名找到时，把同名方法的**真实签名**打进日志。
     *
     * SystemUI 的私有方法签名会随版本漂移，而且这些类不在本模块的编译依赖里，
     * 离线没法查。与其猜，不如让真机自己把答案打出来：
     * 看到 `(NotificationEntry, StatusBarIcon, StatusBarIconView, boolean)` 就知道该补哪个参数。
     */
    private fun logCandidates(owner: Class<*>, methodName: String) {
        val all = owner.declaredMethods.filter { it.name == methodName }
        if (all.isEmpty()) {
            ModuleRuntime.logW("$methodName absent on ${owner.simpleName}")
            return
        }
        val shapes = all.joinToString(" | ") { method ->
            "(${method.parameterTypes.joinToString { it.simpleName }})"
        }
        ModuleRuntime.logW("$methodName signature mismatch on ${owner.simpleName}, found: $shapes")
    }
}
