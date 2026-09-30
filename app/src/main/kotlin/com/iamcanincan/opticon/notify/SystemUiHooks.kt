package com.iamcanincan.opticon.notify

import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import com.iamcanincan.opticon.runtime.MemberLookup
import com.iamcanincan.opticon.runtime.ModuleRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * 往 SystemUI 里装挂钩。
 *
 * 这里挂的全是私有类/私有方法，任何一环在不同版本上都可能改名或改签名，
 * 所以每个挂钩都是「找不到就记日志跳过」，绝不让 SystemUI 因为装钩失败而崩。
 * 装上的钩子会以 <方法名> hooked 打进日志，真机排障时看这个就知道缺了哪一段。
 *
 * ## 钩子体是热路径，别在里面现找成员
 * 拦截器每条通知都要跑一遍，所以 Field 一律在**装钩那一刻**解析好、用闭包捕获
 * （见 [installOnly]）：`getDeclaredField` 一次不算贵，乘上「通知数 × 重绘次数」就不是钱了。
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

    /** 已挂过的方法，防止框架重复回调时重复挂钩 */
    // 线程安全集合：安装路径虽然目前只走主线程，但框架的回调时机不由我们决定，
    // 用并发集合换掉 HashSet，避免万一并发时的静默数据损坏（零成本）。
    private val hookedMethods = java.util.concurrent.ConcurrentHashMap.newKeySet<java.lang.reflect.Method>()

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

    /** 挂载前会逐个探测这些类是否存在，缺的会打进日志 */
    private val TARGET_CLASSES = listOf(
        ROW_BINDER_MODERN, ROW_BINDER_LEGACY, NOTIFICATION_ENTRY
    )

    /**
     * 装钩期的一次性解析。
     *
     * ⚠ 异常一律吞在这里：`install()` 这条链路外面没有第二层保护（框架的 protective
     * 只兜钩子体，不兜装钩），一次解析失手抛出去 = 唯一那条钩子也装不上，
     * 通知全裸奔，比不缓存糟得多。
     * 解析不出来就返回 null，闭包里的运行时代码自动退回「按名字现查」的老路子 ——
     * 缓存只负责省掉重复查找，永远不负责让一条本来能用的钩子失效。
     *
     * ⚠ **每一项都要带个名字**（`what`）：解析失败过去是完全静默的，于是换一台 ROM
     * 只要 SystemUI 把 `mContext` 挪进父类，这几条就整片退回按名现查 —— 而按名那一版
     * 过去**只查自己一层类**（已在 `MemberLookup` 统一成沿父类链），于是换图这类功能会
     * 既失效又没有任何一行日志。现在 [install] 收尾会把没解析出来的项一次报完。
     */
    /** 装钩期**没解析出来**的成员，攒着由 [install] 一次性报出（见 [installOnly]）。 */
    private val unresolved = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun <T : Any> installOnly(what: String, lookup: () -> T?): T? =
        runCatching(lookup).getOrNull().also { if (it == null) unresolved.add(what) }

    /**
     * 装挂钩。返回「主挂钩是否已就位」。
     *
     * ⚠ 返回 false 时调用方**必须重试**：第一次 packageReady 有可能来得太早 ——
     * 那时框架还在建 ClassLoader（`LoadedApk.createOrUpdateClassLoaderLocked`），
     * 目标类一个都 load 不出来。实测开机那次就是 5 个类全 missing、一个钩都没装上。
     *
     * 重试是幂等的：`hookedMethods` 会挡掉重复挂钩。
     *
     * @param quiet 重试时传 true，避免每轮都把环境探测那几行重复刷一遍
     */
    fun install(module: XposedModule, classLoader: ClassLoader, quiet: Boolean = false): Boolean {
        if (!quiet) reportEnvironment(classLoader)
        return try {
            installRowInflation(module, classLoader)
        } finally {
            // 早退也要报：`installRowInflation` 返回 false 就是通知整条功能没了，
            // 那恰恰是最需要知道"哪些成员没解析出来"的一次。
            if (!quiet) flushUnresolved()
        }
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
     * 换图发生在这里，这也是本模块在 SystemUI 里唯一的挂钩点 —— 压出来的单色剪影
     * 本来就该由系统按主题上色，后面没有什么需要再护住的。
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
                        // 只用 context.resources，NotificationIconPatch 也只解手上那个 Icon，
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
        // 重跑一遍 reportEnvironment（3 次 loadClass）和上面的重载扫描。
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
}
