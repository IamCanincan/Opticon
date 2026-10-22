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

    /**
     * 通知**进来**的那一刻（2026-10-22 加）。
     *
     * 为什么非它不可：状态栏左上那一排小图标走的是 `StatusBarIconView`，**不是**通知行的 inflate ——
     *   而且它在通知发布时就建好了，比"行 inflate"早。只挂行的话，状态栏那颗永远是原始图标
     *   （flyingbirdlite 那个 1×1 就是这么在那里当"一整块"的）。在这里把 `Notification.mSmallIcon`
     *   改掉，状态栏和通知行**拿到的是同一张**。
     */
    private const val NOTIFICATION_LISTENER =
        "com.android.systemui.statusbar.NotificationListener"

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
        ROW_BINDER_MODERN, ROW_BINDER_LEGACY, NOTIFICATION_ENTRY, NOTIFICATION_LISTENER
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
        val row = installRowInflation(module, classLoader)
        // 状态栏那一条（不依赖行钩子；行钩子没装上它也该装上）
        val posted = installNotificationPosted(module, classLoader)
        return row || posted
    }

    /**
     * 在**通知进来那一刻**把 `Notification.mSmallIcon` 换掉 —— 状态栏那排小图标和通知行
     * 之后都从同一个字段取值，所以一处改动两处都对。
     *
     * 幂等：`NotificationIconPatch.patch` 在通知的 extras 里写"已处理"标记 —— 行钩子拿到的
     * 是同一份通知，一次 Bundle 查询就放过。**没有标记的**（标记机制之前就在屏上的通知、
     * 或我们在"未解码 / 无兜底"这类可重试分支上没写标记的）仍会走完整的 decode + 两趟
     * 96² 扫描，代价有界（每次行重绑一次），别按"几乎为零"去估它。
     */
    private fun installNotificationPosted(module: XposedModule, classLoader: ClassLoader): Boolean {
        val cls = MemberLookup.findClass(NOTIFICATION_LISTENER, classLoader) ?: return false
        var hooked = false
        for (m in cls.declaredMethods) {
            if (m.name != "onNotificationPosted") continue
            if (m.parameterTypes.size != 2) continue
            if (!StatusBarNotification::class.java.isAssignableFrom(m.parameterTypes[0])) continue
            val ok = runCatching {
                m.isAccessible = true
                module.hook(m).setId("notify:posted").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                    runCatching {
                        val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                        val ctx = ModuleRuntime.hostContext
                        if (sbn != null && ctx != null) {
                            // 目标上下文按需建：标记/缓存命中时 patch 根本不会碰这个 lambda。
                            NotificationIconPatch.patch(sbn, "帖") {
                                runCatching {
                                    ctx.createPackageContext(sbn.packageName, Context.CONTEXT_IGNORE_SECURITY)
                                }.getOrDefault(ctx)
                            }
                        }
                    }.onFailure { ModuleRuntime.logE("onNotificationPosted patch failed", it) }
                    chain.proceed()
                }
            }.onFailure { ModuleRuntime.logE("onNotificationPosted hook failed", it) }.isSuccess
            if (ok) {
                ModuleRuntime.logI(
                    "notify: onNotificationPosted hooked (${m.parameterTypes.joinToString { it.simpleName }})"
                )
                hooked = true
            }
        }
        return hooked
    }

    /** 装钩期成员解析的结果是否已经报过。只报一次：重试的那 19 轮解析的是同一批成员。 */
    @Volatile
    private var resolutionReported = false

    /**
     * 成员解析的结果，在**解析真的跑过之后**报一次（[installRowInflation] 里调的）。
     *
     * ⚠ 这里改过两处，两处原本都是"日志在说谎"：
     * 1. **报的时机**。原来它挂在 [install] 的 `finally` 里、且只在 `quiet=false` 那几轮报 ——
     *    而第一次 packageReady 常常早到 ClassLoader 还没就绪（目标类全 missing、直接早退），
     *    真正解析成员、真正把钩子装上的是**第 2 轮起**（`quiet = true`）。于是
     *    「N 项没解析出来」这条明细在正常流程里**永远不会打**，这条通道等于没有。
     * 2. **"全部命中"不能张嘴就来**。早退那几次压根没走到解析，队列本来就是空的，原来照样打一句
     *    `全部命中` —— 而它紧跟在 `missing class: …` 后面，正是查"缺了什么"的人最先看到的那行。
     *    现在只有解析跑过、且队列为空，才说这句话。
     */
    private fun reportResolved() {
        if (resolutionReported) return
        resolutionReported = true
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

        // 解析到此结束：结果在这里报（见 [reportResolved] —— 报了才说明解析真的跑过）
        reportResolved()

        var failed = 0
        for (method in overloads) {
            // 框架会对同一个进程多次回调 onPackageReady（实测 Vector 会调 2~3 次），
            // 不判重就会把同一个方法挂多次，导致每条通知被重复处理
            if (hookedMethods.contains(method)) continue
            // id 必须各不相同，多个重载不能共用同一个 hook id
            val hookId = "inflateViews[${method.parameterTypes.joinToString { it.simpleName }}]"

            // ⚠ **先装成、再登记**（原来顺序反了：先 `hookedMethods.add` 再装）。装钩这一步一旦
            // 抛出来（libxposed 的 `HookFailedError` 是 Error、`intercept` 也不声明受检异常，
            // 没有哪一层拦得住），后果是连锁的：① 这个方法被记成"已挂上"，下一轮回调直接跳过；
            // ② 那次抛出会把 `installNotifyHooks` 里的 `postDelayed` 一起带走，重试根本排不上；
            // ③ 框架自己再派发一次 packageReady 时循环空转，却照样打 `inflateViews hooked
            // (N overload(s))` 并把 `rowInflationInstalled` 置真 —— 钩子一个都没有，日志和
            // `isInstalled()` 都说成功，而且重试就此收工。框架的 protective 只兜**钩子体**、
            // 不兜装钩（本文件开头那句"异常一律吞在这里"原本只对成员查找成立），
            // 所以这里自己兜住，失败如实报数。
            if (!installInflateHook(module, method, entryClass, sbnField, binderContextField, hookId)) {
                failed++
                continue
            }
            hookedMethods.add(method)
        }
        // 一个钩子都没挂上 = 这条通道在本进程没生效，必须让调用方按原样重试（返回 false）。
        // 绝不能报成功 —— 那正是上面那段说的"日志说成功、功能其实没有"。
        if (hookedMethods.isEmpty()) {
            ModuleRuntime.logE(
                "inflateViews 一个都没装上（${overloads.size} 个重载全失败）",
                IllegalStateException("all inflateViews overloads failed to hook"),
            )
            return false
        }
        if (failed == 0) {
            ModuleRuntime.logI("inflateViews hooked (${overloads.size} overload(s))")
        } else {
            ModuleRuntime.logW(
                "inflateViews hooked (${overloads.size - failed}/${overloads.size} overload(s) 挂上，$failed 个失败)"
            )
        }
        // 这一行是给 isInstalled() 用的收工信号：漏了它，OpticonModule 那道
        // 「已经装上了就别再试」的闸门永远不放行，每次 packageReady 都会
        // 重跑一遍 reportEnvironment（3 次 loadClass）和上面的重载扫描。
        rowInflationInstalled = true
        return true
    }

    /**
     * 装上一条 `inflateViews` 钩子。[hookId] 由调用方按参数表算好（多个重载不能共用一个 id）。
     *
     * @return 真的挂上了返回 true。`isAccessible` / `hook` / `intercept` 任何一步抛出来都算失败 ——
     *   这一整段**不在框架 protective 的兜底范围内**（它只兜钩子体），抛出去会一路穿到
     *   `onPackageReady`，连重试都排不上，所以这里自己兜住并把失败如实报进日志。
     */
    private fun installInflateHook(
        module: XposedModule,
        method: java.lang.reflect.Method,
        entryClass: Class<*>?,
        sbnField: Field?,
        binderContextField: Field?,
        hookId: String,
    ): Boolean = runCatching {
        method.isAccessible = true
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
                    NotificationIconPatch.patch(sbn, "行") {
                        runCatching {
                            context.createPackageContext(
                                sbn.packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )
                        }.getOrDefault(context)
                    }
                }.onFailure { ModuleRuntime.logE("inflateViews patch failed", it) }
            }
            chain.proceed()
        }
    }.onFailure {
        ModuleRuntime.logE("inflateViews hook failed: $hookId", it)
    }.isSuccess

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
