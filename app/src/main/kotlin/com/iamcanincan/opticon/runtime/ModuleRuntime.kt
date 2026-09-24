package com.iamcanincan.opticon.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import io.github.libxposed.api.XposedInterface
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * 挂钩运行期的共享状态，活在**被注入的宿主进程**里。
 *
 * 本模块没有自己的进程：裁圆跑在 launcher / settings / system_server / SystemUI 里，
 * 通知跑在 SystemUI 里。每个进程各有自己的一份本对象（进程间不共享内存），
 * 各自独立去读一次配置。
 *
 * 生命周期与宿主进程一致，不存在 Activity 泄漏问题，故抑制 StaticFieldLeak。
 */
@SuppressLint("StaticFieldLeak")
object ModuleRuntime {

    /**
     * 从挂钩点上顺出来的宿主上下文（SystemUI / launcher / …，看本进程是谁）。
     *
     * SystemUI 侧是在 inflateViews 钩子里写的，读的一方是 provider 那条查询的后台线程，
     * 所以必须 volatile —— 否则后台线程可能永远看不到它被赋过值，provider 这一路白瞎。
     */
    @Volatile
    var hostContext: Context? = null

    /** 模块实例，用来读界面写下的配置 */
    private var module: XposedInterface? = null

    /**
     * 本进程当前生效的配置快照。
     *
     * [ModuleOptions] 是不可变的，所以 volatile 发布一份引用就够了：
     * 拿到引用的线程看到的必然是完整一致的一份值，不会读到改一半的。
     */
    @Volatile
    private var current: ModuleOptions = ModuleOptions()

    @Volatile
    private var lastReadAt = 0L
    private var lastSource: String? = null

    /**
     * 重读的互斥闸。
     *
     * options() 在热路径上被调，而 system_server 里 markIconResId 是**多个 binder 线程**
     * 同时进来的。没有这道闸的话，TTL 一到这批线程会各自放行、各自去跑一遍回退探测，
     * 一次图标潮就能重复读十几遍配置。抢不到闸的直接用旧快照返回，绝不等。
     */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 配置的重读间隔。
     *
     * 界面里改了设置要尽快生效，但没必要每次取图标都去读一次 ——
     * 1 秒足够盖住「改完设置 → 回到桌面 / 收到通知」这个动作，
     * 也不会让高频的图标加载反复读。
     */
    private const val OPTIONS_TTL_MS = 1_000L

    /**
     * 三条旧回退通道的重探间隔。
     *
     * ⚠ 这三条腿是**在调用线程上**跑的（provider 那条已经甩给后台线程了）。
     * provider 还没答话的每一轮都会走到这里，里面最多四次文件 open 加一次远程配置查询 ——
     * 而调用线程常常是宿主主线程。实测在 LSPosed 之外的框架上 provider 是唯一能通的，
     * 也就是说这段会每秒在主线程上白跑一遍，永远跑不通。退避到这个间隔。
     *
     * provider 一通（正常情况）就完全走不到这段。
     */
    private const val FALLBACK_PROBE_MS = 30_000L

    /** 上一次真去跑回退探测的时刻；0 = 还没跑过，第一次一定要跑（不能因为刚开机就跳过） */
    @Volatile
    private var lastFallbackProbeAt = 0L

    /** 候选文件名，按可能性排序。SharedPreferences 落盘时就是 `<名字>.xml` */
    private val REMOTE_FILE_CANDIDATES = listOf(
        "${ModulePrefs.FILE}.xml",
        ModulePrefs.FILE,
        "shared_prefs/${ModulePrefs.FILE}.xml",
        "files/${ModulePrefs.FILE}.xml"
    )

    /**
     * provider 查询的结果，由后台线程写、主线程读。
     *
     * ContentResolver 查询有可能把模块应用进程拉起来（几百毫秒），而 [options]
     * 是在宿主主线程上被图标加载 / 通知处理调用的 —— 阻塞在这里会拖慢桌面和通知栏。
     * 所以查询放后台，这里只存最近一次结果。
     */
    @Volatile
    private var providerOptions: ModuleOptions? = null

    @Volatile
    private var providerQueryAt = 0L

    @Volatile
    private var providerQueryInFlight = false

    private val providerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opticon-config")
    }

    /**
     * 挂上模块实例并读一次配置。
     *
     * 注意这里只是「读」，不涉及挂钩：挂钩装好之后跟开关无关，
     * 所以换开关既不用重新挂钩，也不用重启进程。
     */
    fun attach(module: XposedInterface) {
        // 同一进程里 onSystemServerStarting 与 onPackageReady 都可能调到这里，
        // 只让第一次打日志 —— 否则每次派发都刷一条一样的 "attach in ..."。
        val first = this.module == null
        this.module = module
        if (first) {
            logI(
                "attach in ${hostProcessName()}: framework=${runCatching { module.frameworkName }.getOrNull()}" +
                    " ${runCatching { module.frameworkVersion }.getOrNull()}" +
                    " api=${runCatching { module.apiVersion }.getOrNull()}"
            )
        }
        refresh(force = true)
    }

    /**
     * 当前生效的选项。
     *
     * 会按 [OPTIONS_TTL_MS] 重读配置，所以界面里切换开关后**新加载的图标 / 新收到的通知
     * 立刻按新设置处理**，不需要重启。
     *
     * 已经在桌面或通知栏里的那条不会跟着变 —— 它们是在首次加载那一刻定型的，
     * 之后不会重来。要它们变，得重建（桌面用「清除图标缓存」，通知等它重新发出）。
     */
    fun options(): ModuleOptions {
        // ⚠ 读配置绝不能把调用方带崩。这个函数在**热路径**上被调：
        // 每张图标（clipToCircle / 打标记）、每条通知都要过一次。
        // 一旦这里抛出去，轻则那张图标没裁、重则 launcher 抛异常（保护模式虽然会吞，
        // 但那条 hook 就被整体跳过了）。所以异常一律就地吃掉，退回上一次的值 ——
        // 配置读不到时用旧值，比"整个功能失效"好得多。
        return try {
            refresh(force = false)
            current
        } catch (t: Throwable) {
            logW("options() failed, keeping last: ${t::class.java.simpleName}: ${t.message}")
            current
        }
    }

    private fun refresh(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReadAt < OPTIONS_TTL_MS) return

        // 到点了也只让一个线程真去读；没抢到闸的立刻返回，调用方继续用上一份快照。
        // 这里绝不能等：provider 那条腿是个 binder 调用，可能把模块进程拉起来（几百毫秒），
        // 而调用方是宿主主线程上的图标加载 / 通知渲染。
        if (!refreshing.compareAndSet(false, true)) return
        try {
            // 先记账再读：这一轮没读到新东西也算「读过了」，否则下一个热路径调用
            // 又会立刻重试，等于没有 TTL。
            lastReadAt = now

            val m = module ?: return
            val read = readOptions(m) ?: return

            if (force || read.options != current || read.source != lastSource) {
                current = read.options
                lastSource = read.source
                logI("options: ${read.options.describe()} (source=${read.source})")
            }
        } finally {
            refreshing.set(false)
        }
    }

    /** 配置值 + 它的来源，来源要打进日志才好排障 */
    private class Read(val options: ModuleOptions, val source: String)

    /**
     * 按可靠性从高到低依次尝试。
     *
     * 排在最前的是 provider —— 它是唯一一条实测能通的：模块读不到模块应用的私有目录
     * （系统隔离，表现为 ENOENT），LibXposed 的 getRemotePreferences 在部分框架上
     * 返回空对象、openRemoteFile 也找不到文件。后面几条保留着，在 LSPosed 上仍然管用。
     */
    private fun readOptions(m: XposedInterface): Read? {
        scheduleProviderQuery(m)
        providerOptions?.let { return Read(it, "provider") }

        // provider 还没答话，才轮到这三条旧腿。它们会真的去开文件，所以不能每个 TTL 就跑一次
        // （见 [FALLBACK_PROBE_MS]）。返回 null 不算失败：current 里的旧值继续用。
        val now = SystemClock.elapsedRealtime()
        if (lastFallbackProbeAt != 0L && now - lastFallbackProbeAt < FALLBACK_PROBE_MS) return null
        lastFallbackProbeAt = now

        readPrefsFileDirectly(m)?.let { return Read(it, "direct-file") }
        readFromRemoteFile(m)?.let { return Read(it, "openRemoteFile") }
        readFromPreferences(m)?.let { return Read(it, "getRemotePreferences") }
        return null
    }

    /** 到点了就把 provider 查询丢到后台线程，本函数不阻塞 */
    private fun scheduleProviderQuery(m: XposedInterface) {
        val now = SystemClock.elapsedRealtime()
        if (providerQueryInFlight || now - providerQueryAt < OPTIONS_TTL_MS) return
        providerQueryAt = now
        providerQueryInFlight = true
        // 线程池理论上可能拒绝（进程正在收尾），别让这一下把热路径打断；
        // 拒绝了就把 inFlight 放回去，下个 TTL 再试。
        runCatching {
            providerExecutor.execute {
                try {
                    providerOptions = readFromProvider(m)
                } finally {
                    providerQueryInFlight = false
                }
            }
        }.onFailure {
            providerQueryInFlight = false
            logW("provider query rejected: ${it::class.java.simpleName}: ${it.message}")
        }
    }

    /**
     * 内容没变就不再重复打的日志。
     *
     * provider 查询按 TTL 每秒一轮，成功路径原本每次都打一行 `provider ok: …` ——
     * 于是每个被注入的进程每秒往 logcat 写一行、内容一模一样，真机实测 4 秒 4 行。
     * 排查时真正要看的不是「它还在跑」，而是「它现在给的是什么值」，所以只在值
     * **变了**的时候打（从成功切到失败、失败原因换一种，都算变了）。
     *
     * 只在 provider 那条单线程执行器上读写，不需要额外同步。
     */
    private var lastLoggedState: String? = null

    private fun logStateChange(message: String, warn: Boolean = false) {
        if (message == lastLoggedState) return
        lastLoggedState = message
        if (warn) logW(message) else logI(message)
    }

    /**
     * 查模块应用暴露的只读配置 provider。
     *
     * 这是跨进程拿配置的正路：provider 跑在模块应用进程里，读的是它自己的
     * SharedPreferences，所以既不受宿主进程的隔离限制，也不依赖框架实现。
     *
     * 每一条提前返回都要留痕 —— 这条通道曾经「静默地什么都不返回」，
     * 结果配置一直用默认值而没人发现，排查时必须有据可查。
     * 留痕走 [logStateChange]：状态没变就不重复刷。
     */
    private fun readFromProvider(m: XposedInterface): ModuleOptions? {
        val context = hostContext()
        if (context == null) {
            logStateChange("provider skipped: no host context yet", warn = true)
            return null
        }
        val pkg = runCatching { m.moduleApplicationInfo?.packageName }.getOrNull()
        if (pkg == null) {
            logStateChange("provider skipped: module package name unavailable", warn = true)
            return null
        }
        val uri = Uri.parse("content://$pkg${ModulePrefs.AUTHORITY_SUFFIX}/config")
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    // provider 对非可信调用方返回空游标（包名校验没过）
                    logStateChange("provider returned no row (caller not trusted?)", warn = true)
                    return@use null
                }
                val options = ModulePrefs.fromRaw(
                    circleEnabled = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_CIRCLE_ENABLED)
                    ) != 0,
                    monoInvert = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_MONO_INVERT)
                    ) != 0,
                    notifyEnabled = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_NOTIFY_ENABLED)
                    ) != 0,
                    mode = cursor.getInt(cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_MODE)),
                    monoOverridesRaw = cursor.getString(
                        cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_MONO_OVERRIDES)
                    )
                )
                logStateChange("provider ok: ${options.describe()}")
                options
            }
        }.onFailure {
            logStateChange(
                "provider query failed: ${it::class.java.simpleName}: ${it.message}",
                warn = true
            )
        }.getOrNull()
    }

    /**
     * 拿宿主进程自己的 Context。
     *
     * 顺序：
     * 1. 挂钩点上顺出来的那个（最可靠，一定是宿主的 Context）；
     * 2. `ActivityThread.currentApplication()` —— 它的包名就是宿主包名
     *    （SystemUI / launcher3 / android），正是 provider 校验需要的调用方身份。
     *
     * **刻意不走 `getSystemContext()` + `createPackageContext()`**：那个系统上下文的包名是
     * `android`，要再包一层才可用；而 `createPackageContext` 会进到 `LoadedApk.updateApplicationInfo`
     * → `createOrUpdateClassLoaderLocked`，框架在这个点上还会再派发一次 packageReady。
     * 于是我们自己的调用把自己重入进来，且此时 ClassLoader 还没建好、所有目标类都找不到。
     * 实测过：开机那次 `attaching to SystemUI` 之后 5 个类全 missing、钩子一个都没装上，
     * 就是这条路径造成的。绕开它，开机第一次派发就能正常装钩。
     *
     * 拿不到就返回 null（provider 这条腿这轮跳过，下次 TTL 到点再试），不要退而用 `android` 上下文 ——
     * 那样只会换来一条 `SecurityException: Given calling package android does not match caller's uid`。
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun hostContext(): Context? {
        hostContext?.let { return it }
        applicationContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val currentApplication = activityThread.getDeclaredMethod("currentApplication")
            currentApplication.isAccessible = true
            currentApplication.invoke(current) as? Context
        }.onFailure {
            logW("application context unavailable: ${it::class.java.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** 宿主进程的 Application，包名就是宿主包名 */
    private val applicationContext: Context?
        get() = cachedApplicationContext ?: runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val method = activityThread.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            (method.invoke(current) as? Context)?.also { cachedApplicationContext = it }
        }.getOrNull()

    @Volatile
    private var cachedApplicationContext: Context? = null

    /** 只在日志里用：本进程是谁，便于判断「这一路配置是在哪儿读的」 */
    private fun hostProcessName(): String =
        runCatching { (hostContext ?: applicationContext)?.packageName }.getOrNull() ?: "?"

    /**
     * 直接按绝对路径读模块应用的配置文件。
     *
     * 每次都能拿到最新内容，不经过任何进程内缓存。但模块应用和宿主是两个 uid，
     * 实测被系统隔离挡掉（ENOENT），所以只能当备胎。
     */
    private fun readPrefsFileDirectly(m: XposedInterface): ModuleOptions? {
        val dataDir = runCatching { m.moduleApplicationInfo?.dataDir }.getOrNull() ?: return null
        val file = File(dataDir, "shared_prefs/${ModulePrefs.FILE}.xml")
        if (!file.canRead()) return null
        val values = runCatching { file.inputStream().use { parsePrefsXml(it) } }
            .onFailure { logW("direct read '$file' failed: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull() ?: return null
        return values.takeIf { it.containsKey(ModulePrefs.KEY_MODE) }?.let { ModulePrefs.fromValues(it) }
    }

    /**
     * 走框架的远程文件通道。
     *
     * 只认「解析出来的键里有 KEY_MODE」的候选文件 —— 否则名字试错时
     * 一个空文件也会被当成有效配置，把用户的设置悄悄重置成默认值。
     */
    private fun readFromRemoteFile(m: XposedInterface): ModuleOptions? {
        for (name in REMOTE_FILE_CANDIDATES) {
            val values = runCatching {
                m.openRemoteFile(name)?.use { parsePrefsXml(FileInputStream(it.fileDescriptor)) }
            }.onFailure {
                logW("openRemoteFile('$name') failed: ${it::class.java.simpleName}: ${it.message}")
            }.getOrNull() ?: continue
            if (!values.containsKey(ModulePrefs.KEY_MODE)) {
                logW("remote file '$name' has no ${ModulePrefs.KEY_MODE}, skipping (parsed=$values)")
                continue
            }
            return ModulePrefs.fromValues(values)
        }
        return null
    }

    private fun readFromPreferences(m: XposedInterface): ModuleOptions? {
        val prefs = runCatching { m.getRemotePreferences(ModulePrefs.FILE) }
            .onFailure { logW("getRemotePreferences threw: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()
        if (prefs == null) {
            logW("getRemotePreferences returned null")
            return null
        }
        // ⚠ 某些框架的 getRemotePreferences 返回的是「空但非 null」的实例（实证）。
        // 空实例经 ModulePrefs.read 会得到一份**默认值**，看起来跟真配置一模一样 ——
        // 于是 provider 还没回应时，模块会拿默认值当用户设置用，把真实设置顶掉。
        // 只认「里面真有 mode 键」的对象，和 readFromRemoteFile 用同一把尺子。
        val hasMode = runCatching { prefs.contains(ModulePrefs.KEY_MODE) }
            .onFailure { logW("remote preferences unreadable: ${it::class.java.simpleName}: ${it.message}") }
            .getOrDefault(false)
        if (!hasMode) {
            logW("getRemotePreferences has no '${ModulePrefs.KEY_MODE}', treating as unavailable")
            return null
        }
        return runCatching { ModulePrefs.read(prefs) }
            .onFailure { logW("remote preferences unreadable: ${it.message}") }
            .getOrNull()
    }

    /** SharedPreferences 的 XML 就是一串 `<类型 name="键" value="值" />`，把键值对抠出来即可 */
    private fun parsePrefsXml(input: InputStream): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val out = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val key = parser.getAttributeValue(null, "name")
                val value = parser.getAttributeValue(null, "value")
                if (key != null && value != null) out[key] = value
            }
            event = parser.next()
        }
        return out
    }

    // 日志统一打到共享的 tag，两个功能在 logcat 里挨在一起，便于对照。
    // ⚠ 别写成 `= logI(message)` —— 那会解析成这个成员函数本身，无限递归。
    fun logI(message: String) = Log.i(TAG, message)

    fun logW(message: String) = Log.w(TAG, message)

    fun logE(message: String, cause: Throwable) = Log.e(TAG, message, cause)
}
