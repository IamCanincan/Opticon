package com.iamcanincan.opticon.notify

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.RemoteViews
import com.iamcanincan.opticon.runtime.MemberLookup
import com.iamcanincan.opticon.runtime.ModuleRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 往 SystemUI 里装挂钩。
 *
 * 这里挂的全是私有类/私有方法，任何一环在不同版本上都可能改名或改签名，
 * 所以每个挂钩都是「找不到就记日志跳过」，绝不让 SystemUI 因为装钩失败而崩。
 * 装上的钩子会以 <方法名> hooked 打进日志，真机排障时看这个就知道缺了哪一段。
 *
 * ## 钩子体是热路径，别在里面现找成员
 * 下面几个拦截器每条通知、每次图标绘制都要跑一遍，所以 Field / Method / 资源 id
 * 一律在**装钩那一刻**解析好、用闭包捕获（见 [installOnly] 与 [SysUiId]）：
 * `getDeclaredField` 一次不算贵，乘上「通知数 × 重绘次数 × 4~6 处」就不是钱了。
 * 每一项都保留了「解析不出来就运行时按名字现查」的退路，缓存只加速、不改变结果。
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

    /** 取 SystemUI 自己的内部资源 id 时要报的包名（见 [SysUiId]） */
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
     * 装钩期的一次性解析。
     *
     * ⚠ 异常一律吞在这里：`install()` 这条链路外面没有第二层保护（框架的 protective
     * 只兜钩子体，不兜装钩），一次解析失手抛出去 = 后面几条钩子一个都装不上，
     * 通知全裸奔，比不缓存糟得多。
     * 解析不出来就返回 null，闭包里的运行时代码自动退回「按名字现查」的老路子 ——
     * 缓存只负责省掉重复查找，永远不负责让一条本来能用的钩子失效。
     *
     * ⚠ **每一项都要带个名字**（`what`）：解析失败过去是完全静默的，于是换一台 ROM
     * 只要 SystemUI 把 `mContext` / `mCurrentSetColor` 挪进父类、或把 `ContrastColorUtil`
     * 换了类，这几条就整片退回按名现查 —— 而按名那一版过去**只查自己一层类**（已在
     * `MemberLookup` 统一成沿父类链），于是"保色"这类功能会既失效又没有任何一行日志。
     * 现在 [install] 收尾会把没解析出来的项一次报完。
     */
    /** 装钩期**没解析出来**的成员，攒着由 [install] 一次性报出（见 [installOnly]）。 */
    private val unresolved = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun <T : Any> installOnly(what: String, lookup: () -> T?): T? =
        runCatching(lookup).getOrNull().also { if (it == null) unresolved.add(what) }

    /**
     * 只解析一次的 SystemUI 内部资源 id。
     *
     * 原来这两处 `getIdentifier` 写在钩子体里，每次绘制 / 每次重建 RemoteViews 都要现查一遍，
     * 而它要遍历宿主的 asset 包名表，不是廉价调用。id 在进程生命周期内是常量，查到一次就存着。
     *
     * ⚠ 为什么不在装钩那一刻查（明明别的东西都是装钩期定的）：装钩发生在 packageReady，
     * 那时宿主还在 `attachBaseContext` 阶段，`ActivityThread.currentApplication()` 常常是 null
     * （同一个坑见 IconHooks.hookProcessControlReceiver 的注释），而 `ModuleRuntime.hostContext`
     * 要等 inflateViews 第一次被调用才会被填上 —— 也就是说装钩期根本拿不到 Resources。
     * 硬在那时候查只会得到 0，而 0 会被当成「这台机器没这个 id」永久生效，
     * 等于亲手把一条本来能用的保色钩子装死。所以退一步：第一次真拿到 Resources 时解析，
     * 代价只落在第一条通知上。
     *
     * 解析成 0 也照样缓存：真没这个 id 时，每次现查同样得到 0、同样进不去保色分支，
     * 结果与每次现查完全一致，只是少跑了后面那些次。
     */
    private class SysUiId(private val name: String) {

        /** -1 = 还没解析过；>=0 一律视为终值（含 0） */
        @Volatile
        private var resolved = UNRESOLVED

        /** DiscouragedApi 的抑制跟着 getIdentifier 一起挪进来了：这些 id 不在编译资源里，只能按名字反查 */
        @SuppressLint("DiscouragedApi")
        fun get(resources: Resources): Int {
            val cached = resolved
            if (cached != UNRESOLVED) return cached
            // 异常照样就地吞：这一句跑在钩子体里，抛出去整条钩子都会被 protective 跳过，
            // 白丢一次保色。查不到就按「这台机器没有这个 id」处理
            val id = runCatching { resources.getIdentifier(name, "id", SYSTEM_UI_PACKAGE) }
                .getOrDefault(0)
            resolved = id
            return id
        }

        private companion object {
            const val UNRESOLVED = -1
        }
    }

    /** setIcon 打的「这图标别给我统一着色」标记（见 [installColorRetention]） */
    private val iconIsPreLId = SysUiId("icon_is_pre_L")

    /** processSmallIconColor 要清留白的左侧图标位（见 [installSmallIconColor]） */
    private val leftIconId = SysUiId("left_icon")

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
        val ok = try {
            installRowInflation(module, classLoader) && run { installColorRetention(module, classLoader); true }
        } finally {
            // 早退也要报：`installRowInflation` 返回 false 就是通知整条功能没了，
            // 那恰恰是最需要知道"哪些成员没解析出来"的一次。
            if (!quiet) flushUnresolved()
        }
        return ok
    }

    /** 一次装钩里没解析出来的成员，**一行**报完（重试的那 19 次不报，免得刷重复的）。 */
    private fun flushUnresolved() {
        val miss = unresolved.toList(); unresolved.clear()
        if (miss.isEmpty()) {
            ModuleRuntime.logI("notify 装钩期成员解析：全部命中")
            return
        }
        ModuleRuntime.logW(
            "notify 装钩期 ${miss.size} 项没解析出来 → 运行期退回按名字现查（可能仍然失效）：${miss.joinToString(", ")}"
        )
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

        // 钩子体每条通知都要跑，反射成员在这里一次定下来，闭包捕获。
        // 解析不出来（entryClass 为 null 的兜底路径 / 类还没就绪 / 字段改名）就是 null，
        // 运行时自动退回下面的按名现查 —— 缓存只加速，不会让本来能换图的钩子失效。
        val sbnField = installOnly("NotificationEntry.<StatusBarNotification 字段>") {
            entryClass?.let { MemberLookup.findFieldByType(it, StatusBarNotification::class.java) }
        }
        val binderContextField = installOnly("NotificationRowBinderImpl.mContext") {
            MemberLookup.findField(binderClass, "mContext")
        }

        for (method in overloads) {
            method.isAccessible = true
            // 框架会对同一个进程多次回调 onPackageReady（实测 Vector 会调 2~3 次），
            // 不判重就会把同一个方法挂多次，导致每条通知被重复处理
            if (!hookedMethods.add(method)) continue
            // id 必须各不相同，多个重载不能共用同一个 hook id
            val hookId = "inflateViews[${method.parameterTypes.joinToString { it.simpleName }}]"

            module.hook(method).setId(hookId).setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                captureSystemContext(chain.thisObject, binderContextField)

                for (arg in chain.args) {
                    if (arg == null) continue
                    // entryClass 为 null 时放行所有参数，靠能否取出 sbn 来判断
                    if (entryClass != null && !entryClass.isInstance(arg)) continue
                    runCatching {
                        val sbn = readEntryField(arg, sbnField) as? StatusBarNotification ?: return@runCatching
                        val context = ModuleRuntime.hostContext ?: return@runCatching
                        // TYPE_RESOURCE 的图标必须用目标应用自己的 Resources 解码。
                        //
                        // ⚠ 这里**不能**带 CONTEXT_INCLUDE_CODE：
                        // ActivityThread.getPackageInfo 只在设了这个 flag 时才去
                        // mPackages 里取/建 LoadedApk，并走 updateApplicationInfo →
                        // createOrUpdateClassLoaderLocked（本仓 ModuleRuntime 的注释里
                        // 记着这条路径会再派发一次 packageReady，把自己重入进来）。
                        // 不带它则走 mResourcePackages 那份「只有资源、没有代码」的缓存，
                        // 类加载器根本不建。对侧就是纯资源解码：IconBitmap.fromResources
                        // 只用 context.resources，NotificationIconPatch 只用 packageManager，
                        // 两者都不需要从目标 APK 里 loadClass。
                        // 框架自己也这么判：Icon.java 里 CONTEXT_INCLUDE_CODE 只在
                        // sameAppWithProcess 时才加，跨应用取图标一律不带。
                        //
                        // CONTEXT_IGNORE_SECURITY 留着不花成本：ActivityThread 是先算
                        // includeCode 再算 securityViolation，标准实现里这里已经用不上它，
                        // 但 ROM 侧的判断改没改我们说不好，留个保险丝。
                        val targetContext = runCatching {
                            context.createPackageContext(
                                sbn.packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )
                        }.getOrDefault(context)
                        NotificationIconPatch.patch(sbn, targetContext)
                    }.onFailure { ModuleRuntime.logE("inflateViews patch failed", it) }
                }
                chain.proceed()
            }
        }
        ModuleRuntime.logI("inflateViews hooked (${overloads.size} overload(s))")
        // 这一行是给 isInstalled() 用的收工信号：漏了它，OpticonModule 那道
        // 「已经装上了就别再试」的闸门永远不放行，每次 packageReady 都会
        // 重跑一遍 reportEnvironment（7 次 loadClass）和上面的重载扫描。
        rowInflationInstalled = true
        return true
    }

    /**
     * 从 inflateViews 的参数里把 sbn 抠出来。
     *
     * [sbnField] 是装钩期按类型解析好的字段，走它只需要一次 field.get；
     * 它为 null（NotificationEntry 没解析出来）、或它取回 null 时，退回原来那两次现查
     * （按类型扫字段表、再按名字试 `mSbn`），顺序和老代码一模一样。
     * 留着兜底不是洁癖：运行时传进来的可能是 entry 的子类，字段要正好声明在子类上，
     * 装钩期按父类找就是找不到 —— 那种情况下老代码能换图，新代码也必须能。
     */
    private fun readEntryField(arg: Any, sbnField: Field?): Any? =
        (if (sbnField != null) MemberLookup.readField(arg, sbnField) else null)
            ?: MemberLookup.readFieldByType(arg, StatusBarNotification::class.java)
            ?: MemberLookup.readField(arg, "mSbn")

    /**
     * 顺一个 SystemUI 的 Context 出来，后面取包名资源要用。
     *
     * [contextField] 同 [readEntryField] 的字段：装钩期解析好了就只跑一次 field.get，
     * 每条通知都 getDeclaredField 一次是纯浪费。取不到仍退回按名字现查。
     */
    private fun captureSystemContext(binder: Any?, contextField: Field?) {
        val context = binder?.let {
            ((if (contextField != null) MemberLookup.readField(it, contextField) else null)
                ?: MemberLookup.readField(it, "mContext")) as? Context
        } ?: ModuleRuntime.hostContext
        if (context != null) ModuleRuntime.hostContext = context
    }

    /**
     * 保色相关挂钩。分三处，缺一处就会出现「图换了但被染成灰白」的半成品效果：
     * 1. IconManager#setIcon —— 打上 icon_is_pre_L 标记，跳过统一着色；
     * 2. StatusBarIconView#updateIconColor —— 非单色图标不强制设色；
     * 3. Notification.Builder#processSmallIconColor —— 去掉外圈圆底和留白。
     *
     * 用到的两个 SystemUI 内部资源 id（icon_is_pre_L、left_icon）不在本模块的编译资源里，
     * 只能按名字反查 —— 收在 [SysUiId] 里全进程各查一次，别在钩子体里每次现查。
     */
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
            // 这条钩子每次重绘图标都要跑，所以资源 id 走 [iconIsPreLId]（全进程只查一次），
            // 不再在钩子体里现 getIdentifier
            module.hook(setIcon).setId("setIcon").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                if (shouldKeepColor()) {
                    for (arg in chain.args) {
                        if (arg is View) {
                            val tagId = iconIsPreLId.get(arg.context.resources)
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

        // 走到这里这条钩子确定要装了，把钩子体**每次重绘**都要用的四个成员一次解析好（见文件头）。
        // ContrastColorUtil 是 framework 类，ClassLoader 按双亲委派只会给出同一个 Class，
        // 所以装钩期在它上面解析出的 Method，与运行时在 getInstance 返回的实例上现找的是同一个；
        // 反射 invoke 走虚分派，实例真是子类且有 override 也照样调到子类的实现。
        // 任何一项解析不出来就是 null，闭包里退回按名字现查 —— 缓存不改变任何一条判据。
        val notificationField = installOnly("StatusBarIconView.mNotification") {
            MemberLookup.findField(iconView, "mNotification")
        }
        val setColorField = installOnly("StatusBarIconView.mCurrentSetColor") {
            MemberLookup.findField(iconView, "mCurrentSetColor")
        }
        val getInstanceMethod = installOnly("ContrastColorUtil.getInstance") {
            MemberLookup.findMethod(contrastUtil, "getInstance", Context::class.java)
        }
        val isGrayscaleMethod = installOnly("ContrastColorUtil.isGrayscaleIcon") {
            MemberLookup.findMethod(contrastUtil, "isGrayscaleIcon", Context::class.java, Icon::class.java)
        }

        module.hook(method).setId("updateIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
            if (shouldKeepColor()) {
                runCatching {
                    val view = chain.thisObject as View
                    val sbn = (notificationField?.let { MemberLookup.readField(view, it) }
                        ?: MemberLookup.readField(view, "mNotification")) as? StatusBarNotification
                    if (sbn != null && sbn.packageName != "android") {
                        val context = view.context
                        val instance = (getInstanceMethod?.let { MemberLookup.invokeStatic(it, arrayOf(context)) }
                            ?: MemberLookup.invokeStatic(
                                contrastUtil, "getInstance", arrayOf(context), Context::class.java
                            )) ?: return@runCatching
                        val grayArgs = arrayOf(context, sbn.notification.smallIcon)
                        val isGrayscale = (isGrayscaleMethod?.let { MemberLookup.invoke(instance, it, grayArgs) }
                            ?: MemberLookup.invoke(
                                instance, "isGrayscaleIcon", grayArgs,
                                Context::class.java, Icon::class.java
                            )) as? Boolean
                        if (isGrayscale == false) {
                            // 写失败 = 保色没生效（图标会被系统重新染色）。静默失败最难查，所以打出来。
                            // 兜底那步不会因为「快路径写了但返回 false」再写一遍：
                            // writeField 失败给出的是 false，不是 null，掉不进 `?:` 右边
                            val written = setColorField?.let { MemberLookup.writeField(view, it, 0) }
                                ?: MemberLookup.writeField(view, "mCurrentSetColor", 0)
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

        // 这条钩子每次重建通知 RemoteViews 都要跑，成员照旧在装钩期定下来（见文件头）。
        // Builder 里那个 colorUtil 的类型就是框架的 ContrastColorUtil，所以 isGrayscaleIcon
        // 在它上面解析；万一某个 ROM 换成了别的类，反射 invoke 会失败，
        // 失败就落到下面按名现查那条兜底上 —— 结果与今天完全一致，只是那一次多跑一步。
        val builderContextField = installOnly("Notification.Builder.mContext") {
            MemberLookup.findField(builderClass, "mContext")
        }
        val getColorUtilMethod = installOnly("Notification.Builder.getColorUtil") {
            MemberLookup.findMethod(builderClass, "getColorUtil")
        }
        val isGrayscaleMethod = installOnly("ContrastColorUtil#builder.isGrayscaleIcon") {
            MemberLookup.findClass(CONTRAST_UTIL, classLoader)
                ?.let { MemberLookup.findMethod(it, "isGrayscaleIcon", Context::class.java, Icon::class.java) }
        }

        module.hook(method).setId("processSmallIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
            if (shouldKeepColor()) {
                runCatching {
                    val context = ((builderContextField?.let { MemberLookup.readField(chain.thisObject, it) }
                        ?: MemberLookup.readField(chain.thisObject, "mContext")) as? Context)
                        ?: ModuleRuntime.hostContext
                    val smallIcon = chain.getArg(0) as Icon
                    val contentView = chain.getArg(1) as RemoteViews
                    val colorUtil = (getColorUtilMethod?.let { MemberLookup.invoke(chain.thisObject, it) }
                        ?: MemberLookup.invoke(chain.thisObject, "getColorUtil")) ?: return@runCatching
                    val grayArgs = arrayOf(context, smallIcon)
                    val isGrayscale = (isGrayscaleMethod?.let { MemberLookup.invoke(colorUtil, it, grayArgs) }
                        ?: MemberLookup.invoke(
                            colorUtil, "isGrayscaleIcon", grayArgs,
                            Context::class.java, Icon::class.java
                        )) as? Boolean
                    if (isGrayscale == false && context != null) {
                        contentView.setInt(android.R.id.icon, "setBackgroundResource", android.R.color.transparent)
                        contentView.setViewPadding(android.R.id.icon, 0, 0, 0, 0)
                        // left_icon 是 SystemUI 内部的资源 id，全进程只查一次（见 [leftIconId]）
                        val leftIcon = leftIconId.get(context.resources)
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
