package com.iamcanincan.opticon.debug

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
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
    // ⚠ 删不掉必须**当场退出**：`ConfigProvider.query` 每次都会来这一眼看（每个被注入进程 1Hz），
    //   而 busy 在这轮跑完就归 false —— 标记还在的话下一秒又被认领一次，整台机器被反复全量光栅化。
    //   删不动不是臆想：标记的属主可能是 `shell:ext_data_rw`（见上面那段）。
    if (!marker.delete()) {
      Log.w(TAG, "Corpus: 标记删不掉（${marker.absolutePath}），这一轮不抓 —— 否则每秒都会被重新认领")
      return
    }
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
    val index = StringBuilder("pkg\tlabel\tfile\tedge\tsig\tcls\tfg\tbg\tmono\tkb\t" +
      "fgAlpha\tbgAlpha\tmonoFile\tmonoAlpha\tbgColor\tfgFile\tbgFile\tmodelFile\tmodelBgFile\n")
    var written = 0
    var skipped = 0
    for (info in entries) {
      val pkg = info.packageName
      val drawable = runCatching { pm.getApplicationIcon(info) }.getOrNull()
      val bmp = drawable?.let { runCatching { rasterModel(it, edge) }.getOrNull() }
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
      // **自带单色层的那 61 张，把设计师画的那一层也原样带回来** —— 那是「彩色图标 → 正确遮罩」
      // 的免费标注对（同一张图、同一个 384 口径，一边彩色一边人画的墨）。模型从来没对着它们量过，
      // 而这是唯一能把"像不像"从我的口味变成可对表数字的东西。文件名带同一个 `sig`，配对靠它。
      val mono = dumpMono(dir, drawable, edge, pkg, resName(pm, info), sig)
      // ⚠ **上面那张 `file` 是合成图，不是模型看到的图。** 自适应图标在运行时只把
      //   **前景层**交给模型（`MonochromeSynth` 取 `source.foreground`），而盘在背景层里 ——
      //   真机 91 个自适应图标的前景层**中位 74% 是透明**，也就是说合成图里那一大块实心的盘，
      //   模型从来没见过。审计线一直对着合成图打分，等于给一个运行时不存在的输入挑毛病
      //   （2026-10-06 发现；`MonoDump` 那条通道没这个问题，它带的就是模型吃进去的那一张）。
      //   所以这里**另存一张 `_fg.png` = 前景层**，文件名与合成图同 `sig`，配对靠它。
      val fgFile = dumpForeground(dir, drawable, edge, file)
    // **新列一律加在行尾**（`bgFile` 是第 17 列）：老列的位置一个都不动，
    // 按列号读的脚本（`_chan3.cjs`、`_screen2.cjs`、`YardKt`）才不用重算 —— 这条规矩
    // 与 `ConfigProvider` 那处"写侧按位置、读侧按列名"的教训同一个来源。
    val bgFile = dumpBackground(dir, drawable, edge, file)
      // ⚠ **新列一律加在行尾**（这里是第 18、19 列）：老列的位置一个都不动，
      //   按列号读的脚本才不用重算 —— 与 `ConfigProvider` 那条"写侧按位置、读侧按列名"同一来源。
      val model = dumpModelInput(dir, drawable, edge, file)
      index.append(
        listOf(
          pkg, label(pm, info), file, edge, sig, drawable.javaClass.simpleName,
          // ⚠ 顺序是**前景在前**（表头 `fg bg`）：`layerName(…, true)` 取的是 background，
          //   第一版把两列写反了 —— 类名列反了不会有人发现，正是这类"看起来有数、其实是错的"。
          layerName(drawable, false), layerName(drawable, true), shipsMono(drawable),
          bytes.size / 1024,
          // —— 以下五列一律**加在行尾**：老列的位置一个都不动，按列号读的脚本才不用重算。
          // 前景层与单色层的 alpha 分布：这一列回答的是"平台其实已经把图形与盘分开给了我们，
          // 我们为什么要从合成图里把它猜回来"（见 README「更通用的路」那条）。
          alphaStat(drawable, edge, false), alphaStat(drawable, edge, true),
          mono.first, mono.second, bgColor(drawable, edge), fgFile, bgFile, model.first, model.second,
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
   * 只列桌面上能看见的应用 —— 只有它们会被桌面拿去问单色层，列别的东西没有意义
   * （`queries` 已经把这类可见性放开了，见 AndroidManifest）。
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
   * **自适应图标要按装机那条路画：分别画两个子层，不经过父层。**
   *
   * 为什么（2026-10-11，主人问"全覆盖"的账时才发现整批语料的构图都是错的）：
   * `AdaptiveIconDrawable.draw()` **自己会按图标遮罩裁一刀** —— 所以 `raster(父, edge)` 画出来的
   * 是"屏幕上那块圆角牌"（translate 实心 78%、四角透明）。而 `MonochromeSynth.rasterizeLayers`
   * 是把 `background`、`foreground` **各自 `setBounds(0,0,edge,edge)` 后分别画**，从不走父层 →
   * 模型吃的是**满幅未裁**的那一张（同一张 translate 实心 100%、四角不透明）。
   * 两边差 1.5 倍构图，于是**所有拿 `corpus_runtime` 量的覆盖率账量的都不是装机那张**。
   * 这里改成与那边同一笔法：先画背景层、再画前景层，都不裁。
   */
  private fun rasterModel(drawable: Drawable, edge: Int): Bitmap {
    val adaptive = drawable as? AdaptiveIconDrawable
    val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    if (adaptive == null) return raster(drawable, edge).also { }
    val canvas = Canvas(bmp)
    runCatching { adaptive.background }.getOrNull()?.let { bg ->
      bg.setBounds(0, 0, edge, edge); bg.draw(canvas)
    }
    runCatching { adaptive.foreground }.getOrNull()?.let { fg ->
      fg.setBounds(0, 0, edge, edge); fg.draw(canvas)
    } ?: run { bmp.recycle(); throw IllegalStateException("没有前景层") }
    return bmp
  }

  /**
   * 光栅化，与 `MonochromeSynth.rasterize` 同一口径（`setBounds` 后直接画）。
   *
   * 那边要还原 bounds 是因为同一个 drawable 会被桌面反复画；这里每次都是 `getApplicationIcon`
   * 新解出来的一张，画完就扔，所以不需要那套还原。`at` 默认整幅（合成图），取单层时传 [layerBounds]。
   */
  private fun raster(drawable: Drawable, edge: Int, at: Rect = Rect(0, 0, edge, edge)): Bitmap {
    val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    drawable.bounds = at
    drawable.draw(Canvas(bmp))
    return bmp
  }

  /**
   * 自适应图标**子层**的画布：AOSP 把背景/前景按 1.5× 铺进去，可见区是中间 2/3。
   *
   * 校验：子层跨度 `edge + 2·(edge/4)` = 1.5·edge，于是窗口 `[0,edge]` 两侧各让出 `edge/4` 个
   * 子层像素 = `edge/6` 个窗口像素 —— 与 `MonochromeSynth.visibleInset`（`edge·f/(1+2f)`，f=1/4）
   * 算出来的 edge/6 逐位一致。取单层时不给这个 bounds，就会拿到"比屏幕上小一圈、四周留空"的那一层。
   */
  private fun layerBounds(edge: Int): Rect {
    val q = edge / 4
    return Rect(-q, -q, edge + q, edge + q)
  }

  /** 一层的 alpha 分布：`空%/实%`。**"整张不透明"等于这一层没有形状信息** —— 那一列就是分它的。 */
  private fun alphaOf(bmp: Bitmap): String {
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    var zero = 0
    var solid = 0
    for (p in px) {
      when (p ushr 24) {
        0 -> zero++
        255 -> solid++
        else -> {}
      }
    }
    val n = px.size
    return "${zero * 100 / n}/${solid * 100 / n}"
  }

  /**
   * 背景层**自己声明的盘色** —— `flat/med` 前缀 + `#RRGGBB`，渐变或半透明再跟 `/实心%`。
   *
   * 为什么要单独取这一列：判据台六把尺子（占比 / 厚度 / 围合 / 环散 / 轮廓归一 / 调子闸门三条）
   * 全在**从合成图的像素里猜"哪块是盘"**，而自适应图标的背景层就是设计者**写下来的那块盘**。
   * 这不是第七个阈值，是换一种证据：`ColorDrawable` 直接给一个整数；Vector/Bitmap 渲染后取中位色。
   * 对满幅插画它匹配不到任何像素（画里没有那个颜色），所以它**只在真有盘时起作用**，
   * 不像阈值那样必须把两类图分开。
   * ⚠ 只是**取证**：不参与任何判断，也不改变交出去的掩码。
   */
  private fun bgColor(drawable: Drawable, edge: Int): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    val layer = runCatching { adaptive.background }.getOrNull() ?: return "null"
    return runCatching {
      (layer as? android.graphics.drawable.ColorDrawable)?.color?.let { return "flat%06X".format(it and 0xFFFFFF) }
      val n = 96
      val bmp = raster(layer, n, layerBounds(n))
      val px = IntArray(n * n)
      bmp.getPixels(px, 0, n, 0, 0, n, n)
      val op = px.filter { (it ushr 24) >= 128 }
      if (op.isEmpty()) return "empty"
      val mid = pick@{ sh: Int -> op.map { (it shr sh) and 0xff }.sorted()[op.size / 2] }
      "med%02X%02X%02X/%d".format(mid(16), mid(8), mid(0), op.size * 100 / px.size)
    }.getOrDefault("err")
  }

  /** 把**前景层**单独存成 `<合成图名去掉.png>_fg.png` —— 那才是运行时交给模型的一张。 */
  private fun dumpForeground(dir: File, drawable: Drawable, edge: Int, composite: String): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    val layer = runCatching { adaptive.foreground }.getOrNull() ?: return "null"
    val name = composite.removeSuffix(".png") + "_fg.png"
    return runCatching {
      if (!File(dir, name).exists()) File(dir, name).writeBytes(png(raster(layer, edge, layerBounds(edge))))
      name
    }.getOrDefault("err")
  }

  /** 把**背景层**也单独存成 `<合成图名去掉.png>_bg.png`。
   *
   * 为什么现在要补这一张（2026-10-09）：乙类（自适应 + 背景层是图片/矢量）在真机上走
   * `rasterizeLayers`，**两层合并**才是模型吃的那一张，而"哪一格是盘"这件事平台其实是知道的 ——
   * 就是背景层本身。但**"背景层是纯色盘"与"图形整个画在背景层里"分不开**就会切错：
   * 把"前景层为空 ⇒ 是盘"喂进模型，`deskclock / audiofx / telegram` 三张修好了，
   * 而 `chrome`（`51.7%→0.0%`）、`jelly`（本来 12.1% 与人画完全一致）被整个切光 ——
   * 因为它们的图形本来就画在背景层里。`bgAlpha=0/100` 这一列分不开这两种（19 张乙类全是 `0/100`）。
   * 有了这一张才能问："背景层自己是不是**一块均匀色**"——那是盘，否则是图形。
   * ⚠ 与 `_fg` 同一套 `layerBounds` 几何（视口），所以它能与合成图逐格对齐。
   */
  private fun dumpBackground(dir: File, drawable: Drawable, edge: Int, composite: String): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    val layer = runCatching { adaptive.background }.getOrNull() ?: return "null"
    val name = composite.removeSuffix(".png") + "_bg.png"
    return runCatching {
      if (!File(dir, name).exists()) File(dir, name).writeBytes(png(raster(layer, edge, layerBounds(edge))))
      name
    }.getOrDefault("err")
  }

  /**
   * **模型实际吃进去的那一张**存成 `<合成图名>_model.png`，走合成那一路时另存
   * `<合成图名>_modelbg.png` = 那一条分支交给 `MonochromeMask.build(…, under=…)` 的背景层。
   *
   * 为什么现有三张里**没有一张**是装机那一张（2026-10-11 深夜，量"全覆盖"的账时撞出来的）：
   * · `file` 是**两层满幅叠加**的合成图，可装机在「背景层是 `ColorDrawable`」那一路**只把前景层**
   *   交给模型（盘由桌面自己补，见 `MonochromeSynth` 的 `flatten` 那句）—— 真机 91 张自适应里 34 张走这条；
   * · `_fg.png` / `_bg.png` 是**可见区**那一张（按 1.5× 铺进去之后取中间 2/3，见 [layerBounds]），
   *   而模型吃的是那一层**自己 `setBounds(0,0,edge,edge)` 画满**的版本 —— **差整整 1.5 倍构图**。
   * 于是任何拿 `_bg.png` 当 `under` 喂 `盘刀` 的台子，比的是**错位 1.5 倍的两张**；任何拿合成图
   * 当输入的台子，对那 34 张量的是模型从没见过的输入（盘被当成图形的一部分）。
   * 这一张不做判断、不裁、不缩 —— 它唯一的责任是「与 `MonochromeMask.build()` 收到的像素逐位相同」。
   *
   * 分支与 `MonochromeSynth` 逐句对齐：`flatten = adaptive && background !is ColorDrawable`；
   * `flatten` → 两层各自满幅叠加，且 `under` = 背景层满幅那一张；否则输入 = 前景层满幅，无 `under`。
   * 非自适应图标没有这个分叉，返回 `same,-` = 与 `file` 同一张，不重复存。
   */
  private fun dumpModelInput(dir: File, drawable: Drawable, edge: Int, composite: String): Pair<String, String> {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return Pair("same", "-")
    val bg = runCatching { adaptive.background }.getOrNull() ?: return Pair("null", "-")
    val fg = runCatching { adaptive.foreground }.getOrNull() ?: return Pair("null", "-")
    val stem = composite.removeSuffix(".png")
    val flatten = bg !is ColorDrawable
    val name = "${stem}_model.png"
    // 索引里写不写 `under` 只由**分支**决定，不由"这张这次重没重画"决定 —— 否则同一张图在
    // 两轮抓取里会一个是 `-` 一个有文件名，按列名 join 的台子就查不到它（`if (!exists)` 那一支）。
    val underName = if (flatten) "${stem}_modelbg.png" else "-"
    return runCatching {
      if (!File(dir, name).exists()) {
        // 与 `rasterizeLayers` / `rasterize(foreground)` 同一笔法：满幅、不经父层。这里每次都是
        // `getApplicationIcon` 新解出来的一张，画完就扔，所以不需要那边那套还原 bounds。
        val input = if (flatten) {
          val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
          val c = Canvas(bmp)
          bg.setBounds(0, 0, edge, edge); bg.draw(c)
          fg.setBounds(0, 0, edge, edge); fg.draw(c)
          val u = raster(bg, edge)
          File(dir, underName).writeBytes(png(u)); u.recycle()
          bmp
        } else raster(fg, edge)
        File(dir, name).writeBytes(png(input)); input.recycle()
      }
      Pair(name, underName)
    }.getOrDefault(Pair("err", underName))
  }

  private fun alphaStat(drawable: Drawable, edge: Int, background: Boolean): String {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-"
    val layer = runCatching { if (background) adaptive.background else adaptive.foreground }.getOrNull()
      ?: return "null"
    return runCatching { alphaOf(raster(layer, edge, layerBounds(edge))) }.getOrDefault("err")
  }

  /**
   * 把设计师**自己画的那一层单色**原样存下来，返回（文件名, alpha 分布）。
   *
   * 为什么要它：`mono=有` 的那 61 张按硬规则我们一个像素都不碰，但它们是「彩色图标 → 人认为正确的
   * 遮罩」的**成对样本**，同一张图、同一个 384 口径。判据台从来没对着它们量过一次 —— 那是唯一能把
   * "像不像"从我的口味变成可对表数字的东西（见 README「更通用的路」）。
   * ⚠ 只是**取证**，不参与任何判断，也不改变交出去的掩码。
   */
  private fun dumpMono(
    dir: File, drawable: Drawable, edge: Int, pkg: String, res: String, sig: String,
  ): Pair<String, String> {
    val adaptive = drawable as? AdaptiveIconDrawable ?: return "-" to "-"
    val mono = runCatching {
      AdaptiveIconDrawable::class.java.getMethod("getMonochrome").invoke(adaptive) as? Drawable
    }.getOrNull() ?: return "-" to "-"
    val bmp = runCatching { raster(mono, edge, layerBounds(edge)) }.getOrNull() ?: return "err" to "-"
    val stat = alphaOf(bmp)
    val bytes = png(bmp)
    bmp.recycle()
    val name = "${sanitize(pkg)}_${res}_${sig}_mono.png"
    return if (runCatching { File(dir, name).writeBytes(bytes) }.isSuccess) name to stat else "写失败" to stat
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
