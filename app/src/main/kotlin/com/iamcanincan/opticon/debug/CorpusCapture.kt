package com.iamcanincan.opticon.debug

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.iamcanincan.opticon.circle.MonoDump
import com.iamcanincan.opticon.runtime.TAG
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * adb 触发的**语料抓取**：把本机每个可启动应用的图标，用**手机自己的**渲染器光栅化成 PNG，
 * 落到本应用的 external files 目录，`adb pull` 就能进判据台。
 *
 * ## 为什么要有它（以及为什么不在桌面里抓）
 * [MonoDump] 那条通道带回来的是「桌面恰好问到的那几张」—— 只有可疑的或点名的，而且必须等桌面
 * 真的重渲染一次。于是一轮能捞到的张数被可疑判据卡着、被日志预算卡着，更要命的是：**它只覆盖
 * 他恰好翻到的那一屏**。而 `MonoDump` 文件头写的那句话一直没被解决 —— 真机图标里有一成多是
 * adaptive **vector** XML 或 webp，JVM 这边一张都解不出来，所以每一轮"更细致"其实还是在对着
 * **别的图**调参。这里不判、不猜、不改任何东西，只是把 Android 已经画好的那张位图存下来，
 * 判据台从此吃的是**模型本来会吃的那张**。
 *
 * ## 触发方式：标记文件，而且只有 adb 写得动它
 * 一开始想的是「加一个 `exported=false` 的 Activity，用 `am start` 起」—— 真机否掉了：
 * 这台 Xperia（Android 16）的 shell uid **没有** `START_ANY_ACTIVITY`，起非导出组件直接
 * `SecurityException: not exported from uid 10310`。改成导出 Activity 或给 `ConfigProvider`
 * 加 `call()` 都能起，但那等于把一个第三方应用也够得着的入口放进发布包里（配置通道本来就是
 * 谁都能查的，见 `ConfigProvider` 文件头）—— 而它换来的只是省掉他点一下界面。
 *
 * 于是换成现在这条：**他自己的外部目录里放一个 `corpus_request.txt`，本进程一被叫醒就认领**。
 * 这个目录（`/sdcard/Android/data/<本包>/files`）从 Android 11 起只有本应用自己和 adb 写得动，
 * 所以入口事实上是 adb 专属的，而 APK 里**一个导出的组件都没多**。叫醒进程用
 * `adb shell content query --uri content://com.iamcanincan.opticon.config` —— 它走的是本来就
 * 存在的配置查询，不新增任何权限面。
 *
 * ⚠ 诊断工具，不参与任何判断：只有 [requestIfPresent] 这一个入口，界面上没有按钮，
 *  认领之后立刻删掉标记文件（跑一轮抓一轮，不会反复重跑）。
 *
 * ⚠ 输出目录 `corpus/` **必须由本应用自己建**：先用 `adb shell mkdir` 建过的话，那个目录属主是
 *   shell，本应用往里写是 EACCES —— 真机实测那一轮 122 张全跳光（日志报了，但原因是我 probe
 *   留下的目录）。所以重跑之前先 `adb shell rmdir`，让应用自己 mkdir。
 */
internal object CorpusCapture {

  /** 标记文件名；内容可带 `edge=<n>`、`pkg=<子串>` 两行，空文件 = 全量 + 默认边长。 */
  private const val REQUEST = "corpus_request.txt"
  private const val DIR = "corpus"
  private const val DEFAULT_EDGE = 384
  private const val MIN_EDGE = 64
  private const val MAX_EDGE = 512
  private const val PROGRESS_EVERY = 20

  @Volatile
  private var busy = false

  /** 「就绪」那行每进程只报一次（配置查询每秒可能好几次）。 */
  @Volatile
  private var announced = false

  /**
   * 有没有标记文件？有就认领（删掉）并起一轮抓取。
   *
   * 在 `ConfigProvider.onCreate()` 与 `query()` 两处都查：只挂 onCreate 的话，本进程**本来就活着**
   * 的时候标记会一直躺着没人认领，而"没抓到"和"没这个应用"在日志里长得一样 —— 这个坑本仓踩过
   * 三次（见 README 的静默失效那几条）。一次 `File.exists()` 的量级是微秒，配置查询每秒最多一次，
   * 不值得为此再加节流。
   *
   * ⚠ 调用方必须 `runCatching` 包起来：诊断把自己赖以工作的通道弄坏是不可接受的。
   *
   * ⚠ `@Synchronized` 不是摆设：真机实测两条配置查询在同一个 binder 线程组里几乎同时进来，
   *   两边都看到 `busy == false`，于是同一轮抓了两次（14:47:08 那两条「认领标记」就是它们）。
   *   认领这件事必须是一次性的。
   */
  @Synchronized
  fun requestIfPresent(context: Context) {
    val root = context.getExternalFilesDir(null)
    if (!announced) {
      // 报路径、报就绪：这一行证明的是「新代码真在这个进程里」，以及它找的到底是哪个目录。
      // 少了这一行，"标记没人认领"就分不清是没这段代码、找错目录，还是读不到文件。
      announced = true
      Log.d(TAG, "Corpus: 通道就绪 ${if (root == null) "（外部目录不可用）" else File(root, REQUEST).absolutePath}")
    }
    if (busy || root == null) return
    val marker = File(root, REQUEST)
    if (!marker.isFile) return
    // 触发靠的是**存在性**：stat 与删除只需要父目录的权限，而那目录是本应用自己的。
    // 内容不一定读得到 —— 真机实测 adb 写进本应用目录的文件属主是 `shell:ext_data_rw`，
    // 本进程 `open` 它就是 EACCES（14:46 那一轮就是这么跑的：认领成功、参数全默认）。
    // 所以要带参数就得 `adb shell chmod 666 <标记>`（试过，改完立刻读出 `pkg=` 那行）。
    // 读不到不致命：退回全量 + 默认边长，照样把语料抓回来 —— 不能因为参数读不动就连图都不要。
    val body = runCatching { marker.readText() }.fold(
      onSuccess = { it },
      onFailure = {
        Log.w(TAG, "Corpus: 标记读不到，按默认值跑 —— ${it.javaClass.simpleName}: ${it.message}")
        ""
      },
    )
    // 先删再跑：中途崩了也不会有人下一秒又起一轮（重复认领会把日志和 CPU 都刷屏）。
    marker.delete()
    val edge = Regex("edge\\s*=\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
      ?.coerceIn(MIN_EDGE, MAX_EDGE) ?: DEFAULT_EDGE
    val filter = Regex("pkg\\s*=\\s*(\\S+)").find(body)?.groupValues?.get(1).orEmpty()
    busy = true
    Log.d(TAG, "Corpus: 认领标记 edge=$edge filter=\"$filter\"")
    Thread {
      val skipped = runCatching { capture(context, edge, filter) }.fold(
        onSuccess = { it },
        onFailure = {
          Log.w(TAG, "Corpus: 抓取崩了 ${it.javaClass.simpleName}: ${it.message}")
          -1
        },
      )
      busy = false
      Log.d(TAG, "Corpus: 收工 skipped=$skipped")
    }.start()
  }

  /** 抓一轮，返回跳过的包数。 */
  private fun capture(context: Context, edge: Int, filter: String): Int {
    val pm = context.packageManager
    val dir = File(context.getExternalFilesDir(null), DIR)
    if (!dir.isDirectory && !dir.mkdirs()) {
      Log.w(TAG, "Corpus: 建不出目录 ${dir.absolutePath}")
      return -1
    }
    val entries = launchable(pm, filter)
    if (entries.isEmpty()) {
      Log.w(TAG, "Corpus: 一个包都没匹配上（filter=\"$filter\"）")
      return -1
    }
    Log.d(TAG, "Corpus: 开始 ${entries.size} 个包 edge=$edge → ${dir.absolutePath}")
    val index = StringBuilder("pkg\tlabel\tfile\tedge\tsig\tcls\tfg\tbg\tmono\tkb\n")
    var written = 0
    var skipped = 0
    for (info in entries) {
      val pkg = info.packageName
      val drawable = runCatching { pm.getApplicationIcon(info) }.getOrNull()
      val bmp = drawable?.let { runCatching { raster(it, edge) }.getOrNull() }
      if (drawable == null || bmp == null) {
        skipped++
        Log.w(TAG, "Corpus: 跳过 $pkg（取不到图标或画不出）")
        continue
      }
      // 像素先取出来算指纹，再压 PNG —— 指纹口径必须与 [MonoDump.sigOf] 完全一致，
      // 否则同一个图标在两份语料里长成两个名字，"这张已经在台上了"就判不出来。
      val pixels = IntArray(edge * edge)
      bmp.getPixels(pixels, 0, edge, 0, 0, edge, edge)
      val sig = MonoDump.sigOf(pixels, edge, edge)
      val bytes = png(bmp)
      bmp.recycle()
      val file = "${sanitize(pkg)}_${resName(pm, info)}_$sig.png"
      val writeError = runCatching { File(dir, file).writeBytes(bytes) }.exceptionOrNull()
      if (writeError != null) {
        skipped++
        Log.w(TAG, "Corpus: 写不进 $file —— ${writeError.javaClass.simpleName}: ${writeError.message}")
        continue
      }
      index.append(
        listOf(
          pkg, label(pm, info), file, edge, sig, drawable.javaClass.simpleName,
          layerName(drawable, true), layerName(drawable, false), shipsMono(drawable),
          bytes.size / 1024,
        ).joinToString("\t")
      ).append('\n')
      written++
      if (written % PROGRESS_EVERY == 0) Log.d(TAG, "Corpus: 已写 $written/${entries.size} 张（跳过 $skipped）")
    }
    val indexFile = if (filter.isEmpty()) "index.tsv" else "index_${sanitize(filter)}.tsv"
    runCatching { File(dir, indexFile).writeBytes(index.toString().toByteArray()) }
      .onFailure { Log.w(TAG, "Corpus: 索引写不进去 —— ${it.javaClass.simpleName}: ${it.message}") }
    Log.d(TAG, "Corpus: 写了 $written 张 / 跳过 $skipped / 索引 $indexFile")
    return skipped
  }

  /**
   * 只列桌面上能看见的应用 —— 与「按应用合成」那张调试面板（`ui.icon.MonoOverrideDialog`）
   * 同一口径（`queries` 已经把这类可见性放开了，见 AndroidManifest）。
   */
  private fun launchable(pm: PackageManager, filter: String): List<ApplicationInfo> =
    runCatching {
      pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.distinct().sorted()
        .filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }
        .mapNotNull { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
    }.getOrDefault(emptyList())

  private fun label(pm: PackageManager, info: ApplicationInfo): String =
    runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
      .replace('\t', ' ').replace('\n', ' ')

  /** `mipmap/ic_launcher_round` 这种可读的资源名；解不出来标 `?`（没有它，同一包的两张分不开）。 */
  private fun resName(pm: PackageManager, info: ApplicationInfo): String {
    if (info.icon == 0) return "none"
    return runCatching {
      pm.getResourcesForApplication(info).getResourceEntryName(info.icon)
    }.getOrDefault("?")
  }

  /** 背景层/前景层的类名 —— 这一列就是「这张是 vector 还是 bitmap」的直接答案。 */
  private fun layerName(drawable: Drawable, background: Boolean): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    val layer = runCatching { if (background) adaptive.background else adaptive.foreground }.getOrNull()
    return layer?.javaClass?.simpleName ?: "null"
  }

  /** 应用自己给不给单色层。给了的（已适配）在判据台里是一整类，必须分得出来。 */
  private fun shipsMono(drawable: Drawable): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    return runCatching {
      if (AdaptiveIconDrawable::class.java.getMethod("getMonochrome").invoke(adaptive) != null) "有" else "无"
    }.getOrDefault("?")
  }

  /**
   * 光栅化，与 `MonochromeSynth.rasterize` 同一口径（`setBounds(0,0,edge,edge)` 后直接画）。
   *
   * 那边要还原 bounds 是因为同一个 drawable 会被桌面反复画；这里每次都是 `getApplicationIcon`
   * 新解出来的一张，画完就扔，所以不需要那套还原。
   */
  private fun raster(drawable: Drawable, edge: Int): Bitmap {
    val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, edge, edge)
    drawable.draw(Canvas(bmp))
    return bmp
  }

  private fun png(bmp: Bitmap): ByteArray =
    ByteArrayOutputStream(64 * 1024).use {
      bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
      it.toByteArray()
    }

  /** 文件名里的字符要挡住路径分隔：filter 是 adb 传进来的，不能让它拼出目录。 */
  private fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")
}
