package com.iamcanincan.opticon.notify

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.Collections
import com.iamcanincan.opticon.circle.uncroppedIcon
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import com.iamcanincan.opticon.runtime.ModuleRuntime

/**
 * 通知小图标的修复主体。
 *
 * 判定顺序：
 * 1. 图标已经是单色（说明应用做过主题适配）→ 跳过；
 * 2. 其余把应用自己给的那个小图标就地压成单色剪影，交给系统按主题上色。
 */
object NotificationIconPatch {

    /**
     * @param contextProvider **按需**创建目标应用的上下文（`createPackageContext` 本身不便宜）。
     *   标记命中、缓存命中这两条路一次都不该为它付钱 —— 现量它曾经排在标记检查**之前**，
     *   行钩子每次重绑都白建一个 Context。用提供者而不是现成 Context，就是把这笔钱挪到真正要解码时。
     */
    /**
     * @param force 跳过通知上的"已处理"标记。只有 SystemUI 重启后的图标工厂（`IconManager`）
     *   会传 `true`：那一批通知是框架重新给的，标记可能不在、图标却是原始资源；
     *   形状/跳过缓存仍会让已判过的内容一次查表返回，不会重复压图。
     */
    fun patch(sbn: StatusBarNotification, source: String, force: Boolean = false,
              contextProvider: () -> Context) {
        val p0 = System.nanoTime()
        try {
            val notification = sbn.notification ?: return
            val pkg = sbn.packageName
            // 每条通知都试一次（原子位，已装即返回）：不能只放在 `forceMonochrome` ——
            // 已适配的通知在它之前就被跳过，可能很久都等不到一次。
            ensurePackageWatch()
            // **处理标记**（2026-10-22 深夜第二版）：两条钩子处理的是**同一份通知**，标记位随
            //   `Notification.extras` 走 —— 行钩子 `inflateViews` 再把这份通知送进来时，一次
            //   Bundle 查询就放过，不必 decode + 两趟 96² 全图扫描，也不必建目标上下文。
            //   （先试过"按 Bitmap 引用认"，现量一次都没命中，才换成这个通知级的标记。）
            //   只在"已经做过决定"的分支里写（换图成功 / 已适配 / 放弃，**含缓存命中那一支**）；
            //   未解码与无兜底不写 —— 否则一次临时失败会把这份通知永久钉死。
            val marked = !force && notification.extras?.getBoolean(EXTRA_PATCHED, false) == true
            // 入口账：来源（帖=onNotificationPosted / 行=inflateViews）+ 图标类型 + 标记在不在。
            // 没有这一行，"行钩子有没有进来""标记有没有写进去"两件事都只能猜 ——
            // 第一版的标记漏写就是靠它现撞的（帖与行是同一个对象、同一个 extras，却读到 false）。
            // ⚠ **自己一份 64 条上限**，不占 `通知尺` 那 200 行的预算（否则长跑会把主账挤掉）。
            //   上限到了之后连键都不再拼 —— 这条在每次 patch 上都会跑，字符串也是钱。
            if (entrySeen.size < ENTRY_MAX) {
                val type = iconType(notification.smallIcon)
                val entryKey = "$source $pkg $type $marked"
                if (entrySeen.putIfAbsent(entryKey, true) == null) {
                    ModuleRuntime.logI("通知补丁 入口 $source $pkg type=$type 标记=$marked")
                }
            }
            if (marked) {
                // 快路径单独计时：冷启动那几次首次处理是 ms 级，会把整段均值糊住。
                // 这一格量的是**从 patch 进门到标记命中返回**（含入口账与 Bundle 查询）。
                markHits++
                fastNanos.addAndGet(System.nanoTime() - p0)
                fastCalls.incrementAndGet()
                reportCache()
                return
            }
            // 每次处理通知都取一次当前选项（内部按 TTL 重读远程配置），
            // 所以界面里改了开关后，新通知立刻按新设置处理，不需要重启
            val options = ModuleRuntime.options()
            if (!options.notifyEnabled) return

            val smallIcon = notification.smallIcon ?: return
            val beforeType = iconType(smallIcon)

            // 2) 未适配的：就地压成单色（已经单色的在 forceMonochrome 里原样放过）
            // ⚠ 这里原来还有一步「先解码一次、判是不是灰度、是就返回」，由
            //   `options.preserveTinted` 把闸 —— 而那个字段**没有任何通道会设它**，恒为真，
            //   且 forceMonochrome 里本来就有同一条 isGrayscale 检查。等于同一件事做两遍、
            //   还多解一次码。删的是重复，不是行为。
            // 只有真的换了才打日志；跳过的情况不打，免得日志里分不清「换了」和「没动」
            val t0 = System.nanoTime()
            val changed = forceMonochrome(smallIcon, notification, contextProvider, pkg)
            workNanos.addAndGet(System.nanoTime() - t0)
            workCalls.incrementAndGet()
            if (changed) {
                ModuleRuntime.logI("patched $pkg $beforeType->${iconType(notification.smallIcon)}")
            }
            // 取框账（30 秒最多一行）：这条改动装在机器上唯一能对外的凭据就在这一行里
            IconBitmap.boundsDump()?.let { ModuleRuntime.logI(it) }
        } catch (t: Throwable) {
            ModuleRuntime.logE("patch failed", t)
        }
    }

    /**
     * 日志里显示的图标类型。
     *
     * Icon.getType() 是 API 28 才公开的，minSdk 26 下直连会 NoSuchMethodError，
     * 所以这里显式判版本。拿不到就显示 ?，只影响日志，不影响替换逻辑。
     */
    private fun iconType(icon: Icon?): String {
        if (icon == null) return "null"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "?"
        return icon.type.toString()
    }


    private fun forceMonochrome(
        smallIcon: Icon,
        notification: Notification,
        contextProvider: () -> Context,
        pkg: String
    ): Boolean {
        val f0 = System.nanoTime()
        ensurePackageWatch()
        // ① **两级缓存**（2026-10-22 深夜第二版）：同一条通知每次更新都会重新进来，而结论只取决于（包, 资源）。
        //   正向：我们已经造好的那张 Icon，命中直接装回 —— 不重新解码、也不重新合成。
        //   负向：上次判成「已适配 / 放弃」的，这次**连解码都不做** —— 已适配是大多数
        //   （framework 的 stat_* 与做过主题适配的应用），也恰好是更新最频繁的那批。
        // `Icon.getResId` 是 API 28 才公开的；26/27 上没有这个方法，按"没有资源 id"兜底。
        val resId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            runCatching { smallIcon.resId }.getOrDefault(0) else 0
        val cacheKey = if (resId > 0) "$pkg:$resId" else null
        if (cacheKey != null) {
            val hit = shapeCache[cacheKey]
            if (hit != null) {
                shapeHits++
                // ⚠ **命中也要写标记**：不写的话，这条通知在行钩子那份副本里没有标记
                //   （现撞：帖路径命中缓存 ⇒ 换上了 type=1 的图却没标记 ⇒ 行钩子照旧重扫两趟）。
                val applied = IconBitmap.applySmallIcon(hit, notification)
                if (applied) markPatched(notification)
                fastNanos.addAndGet(System.nanoTime() - f0)
                fastCalls.incrementAndGet()
                reportCache()
                return applied
            }
            if (skipCache.containsKey(cacheKey)) {
                skipHits++
                // 同上：结论是"不用动"，但"已经判过"这件事要让行钩子看得见。
                markPatched(notification)
                fastNanos.addAndGet(System.nanoTime() - f0)
                fastCalls.incrementAndGet()
                reportCache()
                return false
            }
        }
        // 解不出来也要说话（每包一次）：这是本函数里**唯一没有账**的静默出口，
        //   排查"这家的通知图标为什么没变"时，没有这一行就分不清"没跑""解不出""跳过了"。
        // ⚠ 目标上下文到这里才建：上面两条缓存命中都不需要它。
        val context = contextProvider()
        val bitmap = IconBitmap.decode(smallIcon, context) ?: run {
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("未解码 $pkg", true) == null)
                ModuleRuntime.logI("通知尺 未解码 首见 $pkg")
            return false
        }
        cacheMisses++
        // ② **源退化必须在任何形状处理之前判**（2026-10-22 深夜改回）：1×1 的源被 decode 撑成整格后，
        //   颜色可能是彩的 —— 把它放进"彩色"分支就等于这一族没有兜底，屏幕上是整块实心色块。
        //   ⚠ 判据必须在**撑之前**取：`decode` 已经把 1×1 画成 96×96 了，事后量尺寸就全是一样大。
        val srcEdge = IconBitmap.resourceEdge(context, smallIcon)
        if (srcEdge in 0..2 || bitmap.width <= 2 || bitmap.height <= 2) {
            val shapedApp = appIconFallback(context, pkg)
            if (shapedApp == null) { noteRuler(pkg, "退化无兜底", bitmap, null); return false }
            val icon = Icon.createWithBitmap(shapedApp)
            if (cacheKey != null) shapeCache[cacheKey] = icon
            val ok = IconBitmap.applySmallIcon(icon, notification)
            if (ok) {
                noteRuler(pkg, "代用应用图标", bitmap, shapedApp)
                markPatched(notification)
            }
            return ok
        }
        // ③ **字带对彩色图标同样生效**（2026-10-22 深夜改回）：旧流程把 `stripped` 当成
        //   `monochrome` 的输入，彩色图标上的字带一样会被去掉；"彩色反正要走 monochrome"这句
        //   只说明颜色会被重画，不说明字带会消失 —— 挪到颜色判断之后是可见行为变化，不是优化。
        //   真正的热路径优化是第 ① 步的缓存：同一个（包, 资源）只扫这一次。
        val stripped = IconBitmap.stripTextBand(bitmap)
        // ⚠ **只在"没剥字带"这一支复用灰度判决**（2026-10-23）：那一支 `base` 就是 `bitmap` 本身，
        //   旧写法把它扫了两遍（条件一次、`base` 再一次）。**不能**跨到"剥成功"那一支去复用 ——
        //   字带本身可能是彩色的，剥掉后整张会从彩色变灰，必须按剥后那张重判。
        val gray = if (stripped == null) ToneCheck.isGrayscale(bitmap) else false
        if (stripped == null && gray) {
            // 诊断（每包一次）：把"字带判据"看到的几何打出来 —— 不打印就只能猜它为什么没命中。
            if (rulerSeen.size < RULER_MAX && rulerSeen.putIfAbsent("带诊断 $pkg", true) == null)
                ModuleRuntime.logI("通知带诊断 $pkg ${IconBitmap.bandDebug(bitmap)}" +
                    " ｜icon type=${iconType(smallIcon)} resId=$resId" +
                    " resPkg=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { smallIcon.resPackage }.getOrNull() else "api<28"}" +
                    " ｜drawable=${runCatching { IconBitmap.describeResource(context, smallIcon) }.getOrNull()}")
            noteRuler(pkg, "已适配", bitmap, null)
            if (cacheKey != null) skipCache[cacheKey] = true
            markPatched(notification)
            return false
        }
        val base = stripped ?: bitmap
        // 剥成功的那一支按剥后那张重判（理由见上）；没剥的那一支直接用刚才那一次判决。
        val plain = if (stripped == null) gray else ToneCheck.isGrayscale(base)
        // 压不出像样的形状（全空/全满）就保持原样，别交一张更糟的图出去
        val monochrome = if (plain) base else IconBitmap.monochrome(base)
        if (monochrome == null) {
            // **再退一步**（2026-10-23 主人问「3 不能优化了吗」）：合成不出剪影（全空/全满）时，
            //   用应用自己的图标兜底 —— 和 1×1 源退化那一支同一个办法。
            //   这类原图往往就是一块实心色，留着比换成应用图标更难看。
            val shapedApp = appIconFallback(context, pkg)
            if (shapedApp != null) {
                val icon = Icon.createWithBitmap(shapedApp)
                if (cacheKey != null) shapeCache[cacheKey] = icon
                val applied = IconBitmap.applySmallIcon(icon, notification)
                if (applied) {
                    noteRuler(pkg, "代用应用图标", bitmap, shapedApp)
                    markPatched(notification)
                }
                return applied
            }
            noteRuler(pkg, "放弃", bitmap, null)
            if (cacheKey != null) skipCache[cacheKey] = true
            markPatched(notification)
            return false
        }
        val shaped = IconBitmap.fitInk(monochrome, SIZE_TARGET) ?: monochrome
        val icon = Icon.createWithBitmap(shaped)
        if (cacheKey != null) shapeCache[cacheKey] = icon
        val applied = IconBitmap.applySmallIcon(icon, notification)
        if (applied) {
            noteRuler(pkg, if (stripped != null) "清理字带" else "被补", bitmap, shaped)
            markPatched(notification)
        }
        return applied
    }

    /**
     * 最后一道兜底：用**应用自己的图标**顶上去（单色化 + 归一 0.95）。
     *
     * 两个调用点：① 1×1 源退化（怎么处理都是块）；② 合成不出剪影（全空/全满，原图往往也是一块实心色）。
     * 返回 null 表示连应用图标都拿不到、或它自己也压不出剪影 —— 调用方按 `退化无兜底` / `放弃` 记。
     */
    private fun appIconFallback(context: Context, pkg: String): android.graphics.Bitmap? {
        val app = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        // SystemUI 里这个出口也被裁圆钩子包成圆（`clip from=PM.getApplicationIcon
        // host=com.android.systemui`）；拿裁过的圆去压剪影，兜底会变成一块实心饼。
        val fromApp = IconBitmap.rasterize(uncroppedIcon(app))
        val monoApp = if (ToneCheck.isGrayscale(fromApp)) fromApp else IconBitmap.monochrome(fromApp)
        if (monoApp == null) return null
        return IconBitmap.fitInk(monoApp, SIZE_TARGET) ?: monoApp
    }

    /**
     * 在通知上写"本模块已处理"的标记（随 extras 一起被复制到行钩子那份副本里）。
     * 写失败不影响图标本身 —— 只是下一次副本进来会重新判一遍（正确但慢一点）。
     */
    private fun markPatched(notification: Notification) {
        runCatching {
            val extras = notification.extras ?: Bundle().also { notification.extras = it }
            extras.putBoolean(EXTRA_PATCHED, true)
        }
    }

        /** `hostContext` 就绪后装一次包更新监听；没就绪就复位，下一次通知再试。 */
    private val pkgWatch = AtomicBoolean(false)

    @Suppress("UnspecifiedRegisterReceiverFlag")
    fun ensurePackageWatch() {
        if (!pkgWatch.compareAndSet(false, true)) return
        val ctx = runCatching { ModuleRuntime.hostContext }.getOrNull()
        if (ctx == null) { pkgWatch.set(false); return }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val pkg = intent?.data?.encodedSchemeSpecificPart ?: return
                invalidate(pkg)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            true
        }.getOrElse {
            ModuleRuntime.logW("通知缓存：包更新监听装不上（${it.javaClass.simpleName}），旧形状要等 SystemUI 重启")
            false
        }
        if (ok) ModuleRuntime.logI("通知缓存：包更新监听已装") else pkgWatch.set(false)
    }

    /**
     * 应用更新 / 卸载：清掉这个包的正向形状与负向结论。
     *
     * 这是"键不含应用版本"那条已知边界的正解：**不把版本塞进每次通知都要拼的热键**，
     * 只在包真的变了时清一次。缓存键仍是 `包:资源id`，命中路径一个额外调用都不加。
     */
    private fun invalidate(pkg: String) {
        val pre = "$pkg:"
        synchronized(shapeCache) { shapeCache.keys.removeAll { it.startsWith(pre) } }
        synchronized(skipCache) { skipCache.keys.removeAll { it.startsWith(pre) } }
        ModuleRuntime.logI("通知缓存：$pkg 变了，清掉旧形状/旧结论")
    }

    /**
     * 正向形状缓存：键 = `包:资源id`（只对 RESOURCE 图标生效）；上限 32 个 Icon（96² 约 36 KB/个）。
     *
     * ⚠ 必须同步：`IconBitmap.monochrome` 的注释里记着 SystemUI 的通知处理会并发进来，
     *   而 access-order 的 `LinkedHashMap.get` 本身会改链表。计数与"60 秒一行"只影响诊断，
     *   但缓存结构被并发改坏会直接把通知路径打崩，所以整张表挂在一把锁上。
     *
     * **键不含应用版本**，由 [ensurePackageWatch] 在包更新/卸载时把这两张表里该包的条目清掉 ——
     * 应用更新复用同一个资源 id 也拿不到旧图，且**命中路径不增加任何调用**。
     * 监听装不上时退回旧边界：等 SystemUI 重启才换新图（日志里会写明）。
     */
    private val shapeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Icon>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?): Boolean = size > 32
        }
    )

    /**
     * 负向缓存：**上次的结论就是"什么都不用做"**（已适配 / 放弃）。
     * 只存字符串，上限 256 条（≈10 KB）；与正向缓存同一套口径，也由包更新监听一起清。
     * 为什么值得：已适配那一族恰好是更新最频繁的（常驻通知、媒体通知），而每次重判都要
     * 解码 + 两趟 96² 扫描 —— 复判的是一个**不会再变**的结论。
     */
    private val skipCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 256
        }
    )

    private var shapeHits = 0
    private var skipHits = 0
    private var markHits = 0
    private var cacheMisses = 0
    private var lastCacheReport = 0L

    /** `forceMonochrome` 的累计耗时：没有这一格，"优化了"只能靠结构推断，没法看它到底落在多少µs。 */
    private val workNanos = java.util.concurrent.atomic.AtomicLong()
    private val workCalls = java.util.concurrent.atomic.AtomicLong()

    /** 命中路径（标记 / 形状 / 跳过）单独计时：冷启动的首次处理是 ms 级，别和稳态混在一个均值里。 */
    private val fastNanos = java.util.concurrent.atomic.AtomicLong()
    private val fastCalls = java.util.concurrent.atomic.AtomicLong()

    /** 缓存四档计数 + 实际耗时的账：60 秒最多一行 —— 没有它，"优化了"与"没优化"在屏幕上分不出。 */
    private fun reportCache() {
        val now = System.currentTimeMillis()
        if (now - lastCacheReport < 60_000) return
        lastCacheReport = now
        val calls = workCalls.get()
        val avgUs = if (calls == 0L) 0L else workNanos.get() / calls / 1000
        val fast = fastCalls.get()
        val fastUs = if (fast == 0L) 0L else fastNanos.get() / fast / 1000
        ModuleRuntime.logI(
            "通知补丁 缓存 形状=$shapeHits 跳过=$skipHits 标记=$markHits 未中=$cacheMisses" +
                " 均=${avgUs}µs($calls) 快=${fastUs}µs($fast) 在册=${shapeCache.size}/${skipCache.size}"
        )
    }

    /**
     * 大小尺的账：**"强制适配的那批要和已适配的那批一样大"这句话，缺的就是这两列的分布**。
     * 每一行 = 一个源形状（按 `族 + 源尺寸 + 尺` 去重，最多 200 行，之后不再扫像素）：
     *   `通知尺 已适配 源 24x24 尺=79%`  /  `通知尺 被补 源 48x48 尺=100% → 出 96x96 尺=92%`
     * 尺 = 墨外接框长边 ÷ 画布长边（槽位是 22dp 的 fitCenter，所以观感大小就是这个比值）。
     * 消息一次成型：**不拆成"前段" + "后段 %.2f".format(…)** —— 那样占位符会整体错位（本仓记过一次）。
     */
    private fun noteRuler(pkg: String, kind: String, src: android.graphics.Bitmap, out: android.graphics.Bitmap?) {
        if (rulerSeen.size >= RULER_MAX) return
        // ⚠ **键里必须带包名**（2026-10-22 现撞）：旧键是 `kind + 尺寸`，于是"已适配 96x96"被第一家的
        //   那一行占住，后面**所有**同尺寸的应用都不再打印 —— 排查 flyingbirdlite 时账里根本没有它，
        //   这一位就成了"看不出这家我们做了什么"。按包名记，每家一行。
        val key = kind + " " + pkg + " " + src.width + "x" + src.height
        if (rulerSeen.putIfAbsent(key, true) != null) return
        val inPct = (IconBitmap.sizeRuler(src) * 100).roundToInt()
        val tail = if (out == null) "" else
            " → 出 " + out.width + "x" + out.height + " 尺=" + (IconBitmap.sizeRuler(out) * 100).roundToInt() + "%"
        ModuleRuntime.logI("通知尺 $kind 源 ${src.width}x${src.height} 尺=$inPct%$tail 首见 $pkg")
    }

    /** 账的上限：这一族不该长成一本无限账（超出后停止扫描，也就不再印）。 */
    private const val RULER_MAX = 200

    /**
     * 「被补」那一族归一后的目标尺（墨框长边 / 画布长边）。
     *
     * 0.95 的来历：框架 `stat_*` 那 100 张（= 应用自己做好、系统直接用的那一族）尺分布
     * **0.403–1.000、中位 0.949**。取中位而不是"占满整格"：参照族自己就跨 2.5×，
     * 推到 1.0 会落到它的上边缘之外。已适配那一族**不经过**这个常数（见调用处）。
     */
    private const val SIZE_TARGET = 0.95f

    /**
     * 写在 `Notification.extras` 里的"已处理"标记。键带包名，避免和应用的 extras 撞名。
     * 选 extras 而不是位图引用：现量两条钩子拿到的是**同一个通知对象**（同一个 extras），
     * 而按位图引用认一次都没命中 —— 用通知级标记更贴近语义（"这份通知已经判过"），
     * 也不依赖 Icon 内部怎么存位图。
     */
    private const val EXTRA_PATCHED = "com.iamcanincan.opticon.SMALL_ICON_PATCHED"

    /** 入口账自己的上限：只占诊断，不和 `通知尺` 抢行数。 */
    private const val ENTRY_MAX = 64
    private val entrySeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val rulerSeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
}
