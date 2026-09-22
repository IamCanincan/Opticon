package com.iamcanincan.opticon.entry

import android.os.Handler
import android.os.Looper
import com.iamcanincan.opticon.circle.hookIcons
import com.iamcanincan.opticon.circle.hookSystemServer
import com.iamcanincan.opticon.notify.SystemUiHooks
import com.iamcanincan.opticon.runtime.ModuleRuntime
import com.iamcanincan.opticon.runtime.logE
import com.iamcanincan.opticon.runtime.logI
import com.iamcanincan.opticon.runtime.logW
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口，由 `META-INF/xposed/java_init.list` 指向。
 *
 * ## 一个模块、两套挂钩
 * 本模块做两件互不相关的事，按**进程**分发：
 *
 * | 进程 | 装什么 |
 * |---|---|
 * | `system_server` | 裁圆：在图标信息离开 PMS 之前打标记（[hookSystemServer]） |
 * | launcher / settings / SystemUI / 其它被勾选的应用 | 裁圆：认标记、套圆形遮罩（[hookIcons]） |
 * | `com.android.systemui` | 除裁圆外，再装通知图标那套（[SystemUiHooks]） |
 *
 * 通知是系统界面画出来的，挂别的地方没意义；裁圆则相反 —— 谁解析图标谁就得被注入。
 *
 * ## 元数据落在哪（LibXposed / API 102）
 * | 内容 | 位置 |
 * |---|---|
 * | 模块名 | AndroidManifest 的 `android:label` |
 * | 模块描述 | AndroidManifest 的 `android:description`（管理器读 `ApplicationInfo.descriptionRes`）|
 * | 作用域 | `META-INF/xposed/scope.list` |
 * | 模块配置 | `META-INF/xposed/module.prop` |
 *
 * `module.prop` 里**不要**写 `description=` —— 那是给 API <= 93 旧框架用的写法，
 * 留着会让管理器把模块当「兼容模式」处理（作用域退化成列出全部已装应用）。
 *
 * ## 为什么 module.prop 一行注释都没有
 * `META-INF/xposed/` 下的文件是**原样打进 APK** 的（AAPT2 不处理非 res 目录），
 * 写在里面的任何文字用户解包就能看到。字段说明一律放在本文件的 KDoc 或 README。
 *
 * ## 各字段的含义
 * - `staticScope=false`：作用域**不固定**。`scope.list` 里的 10 个是推荐清单，用户可以在
 *   管理器里额外勾选任意应用（第三方桌面、文件管理、带分享面板的应用等，只要它自己会画
 *   别的应用的图标就有效）。写死成 `true` 会让清单外的应用永远拿不到模块 ——
 *   这正是「覆盖不全」的根因：不是 hook 通道不够，而是那些进程根本没被注入。
 * - `autoHotReload=false`（框架默认值，显式写出来）：更新 APK 不会原地换 hook，
 *   改完代码必须重启目标进程。本模块 hook 的是 launcher / systemui 这类常驻进程，
 *   热加载会让新旧 hook 状态混在一起。
 * - `exceptionMode=protective`（框架默认值，显式写出来）：hook 里抛出的异常被框架吞掉，
 *   不让单个图标加载失败带崩 launcher / systemui。
 */
class OpticonModule : XposedModule() {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /**
         * 通知侧装钩的重试次数与间隔。
         *
         * 第一次 packageReady 有可能早于 ClassLoader 就绪（框架还在
         * `LoadedApk.createOrUpdateClassLoaderLocked` 里），那时目标类一个都 load 不出来。
         * 实测正常时 ~90ms 后就都能拿到了，20 × 250ms 留足余量。
         */
        const val MAX_INSTALL_ATTEMPTS = 20
        const val INSTALL_RETRY_MS = 250L
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 模块被加载进某个进程时先报一声。
     *
     * 这一行是排查时的分水岭：有它说明框架确实加载了模块，问题在后面的挂钩；
     * 没它就说明框架压根没把模块放进这个进程，再怎么查挂钩都是白费力气。
     * 所以哪怕什么都还没做，也要先把这行打出来。
     */
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        logI("module loaded in ${param.processName} (systemServer=${param.isSystemServer})")
    }

    /**
     * system_server 是 PMS（包管理服务）所在进程，所有应用的图标信息都是它生成的。
     *
     * 这里比 `onPackageReady` 早得多：在 PMS 开始往外发图标之前就把标记逻辑装上，
     * 客户端拿到的 id 从头就是被打过标记的。只靠 app 进程里补救的话，PMS 侧已经
     * 建好的那些缓存（Settings 应用列表就是从这里来的）永远是原图。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // system_server 也要读配置：它决定了「给不给图标 id 打标记」。
        // 不读的话这里永远拿默认值（= 开），关掉裁圆开关时它照样打标记 ——
        // 虽然客户端那侧 clipToCircle 会兜住（返回原图标），但那是多绕一步，
        // 而且未注入的进程会白白经手一遍伪造 id。
        ModuleRuntime.attach(this)
        hookSystemServer(this, param)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return

        // 两个功能都按同一份配置走，先挂上模块实例、读一次开关。
        // 之后不再需要重启：每次取图标 / 处理通知时 ModuleRuntime.options() 会按 TTL 重读，
        // 界面里改了开关立刻生效。
        ModuleRuntime.attach(this)

        // 裁圆：所有被注入的进程都要装（谁解析图标谁就要认得那个标记）。
        hookIcons(this, param)

        if (param.packageName == SYSTEM_UI) {
            logI("attaching to SystemUI (api=${getApiVersion()}, framework=${getFrameworkName()})")
            installNotifyHooks(param.classLoader, attempt = 1)
        }
    }

    /**
     * 装通知侧的挂钩，装不上就隔一会儿再试。
     *
     * 不能把「框架会再派发一次 packageReady」当成可以依赖的约定 —— 实测确实会派发
     * 2~3 次，但第二次是否够早、是否一定发生都不由我们决定。自己重试才稳。
     *
     * 重试是幂等的（`SystemUiHooks` 内部按方法和 id 判重），重复调用不会把同一条钩子挂两遍。
     *
     * ⚠ 但「已经装上了」要立刻收工：后几次派发带来的 ClassLoader 可能是坏的
     * （实测第三次派发 5 个目标类全 missing），照着重试就是空转 20 轮、刷 5 秒日志。
     */
    private fun installNotifyHooks(classLoader: ClassLoader, attempt: Int) {
        if (SystemUiHooks.isInstalled()) return
        if (SystemUiHooks.install(this, classLoader, quiet = attempt > 1)) return
        if (attempt >= MAX_INSTALL_ATTEMPTS) {
            logE(
                "notify hook install gave up after $attempt attempts",
                IllegalStateException("SystemUI target classes never became resolvable")
            )
            return
        }
        logW("notify hook install attempt $attempt failed, retrying in ${INSTALL_RETRY_MS}ms")
        mainHandler.postDelayed({ installNotifyHooks(classLoader, attempt + 1) }, INSTALL_RETRY_MS)
    }
}
