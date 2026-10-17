package com.iamcanincan.opticon.circle

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 「图案实际占的那块」= 裁圆的裁剪窗口，也是取色那一路要照着重排的那个窗口。
 *
 * ## 为什么单独一个文件、而且是**纯函数**
 * 这条判据有**两个消费方**，注意它们量的**不是同一张**：
 *  - [MaskedDrawable]：彩色那支，量的是整个图标光栅化到 [SCAN_EDGE]（自适应图标的子层按 1.5 倍
 *    铺出去、裁掉四边 —— `AdaptiveIconDrawable.draw()` 的画法），桌面上那个圆里的内容是它裁的；
 *  - [MonochromeSynth]：染色那支，量的是**喂给模型的那一张**（`raster` 的像素），把已经算好的剪影
 *    按它自己的窗口搬到位（只搬位置、只改尺寸，不动浓度）。
 * ⚠ **两档口径的倍数差约 1.5 倍，不能互相对表**：同一张元宝，彩色侧 `→ 满幅（不放大）`，染色侧
 *   `切2.23×`；2026-10-14 到 15 那晚我三次拿一档的数去改另一档的门槛，每次都返工。装机上以真机
 *   那一档为准（判据行末 `几何=`），离线 `_model.png` 量的数只对染色那档成立。
 * 这里刻意不碰 `Bitmap` / `Drawable`：判据台（`_harness/`）编不动 Android 那两个类，
 * 只有纯函数才能被台子直接拿去量 —— 一条判据能不能改，取决于有没有台子能量它。
 *
 * ## 三条出口，语义完全不同（别当成"两种"）
 *  - `null` —— 满幅（外框占到画布 [BOX_FULL_RATIO] 以上）或任何"宁可不动"的情形。
 *    彩色那支据此**逐位不变**；染色那支据此**不重排**。
 *  - [plate] = false —— 裁得动的：按**不透明圆盘**内切，填满、圆外透明，不收边、不铺白底。
 *  - [plate] = true —— 裁不动的（按圆裁下去只剩 [KEEP_RATIO] 以下）：彩色那支不裁、缩进圆里
 *    并补白底；染色那支同样按这个窗口重排，但**那块"白"是桌面的主题盘，不该由我们画**。
 *
 * ⚠ **窗口就是窗口，圆不在这里。** 圆是彩色那支自己 `DST_OUT` 出来的；染色那支的形状由桌面的
 *   mask 决定。把圆烘进取色的输入，模型会把圆边当成一块底去判极性 —— 那是另一族 bug 的成因。
 */
internal object IconCropWindow {

  /**
   * 量测格子边长。**彩色那支要按它自己光栅化**（自适应图标的固有尺寸是 -1，必须先把小画布
   * 撑到这个尺寸，[of] 内部只会降采样、不会放大），所以在这里露出来 —— 别让第二个地方再抄一遍 128。
   */
  internal val scanEdge: Int get() = SCAN_EDGE

  /** 那条线换算成百分数，只给日志用（同理：别让调用方自己写 60）。 */
  internal val keepRatioPercent: Int get() = (KEEP_RATIO * 100f).roundToInt()

  /**
   * 裁圆判"这块大色块是要整块保住的图案"的那条线：**缩进圆里那一档，且按圆裁下去还剩到这么多%**。
   *
   * 为什么需要它（2026-10-15 主人定，`org.mewx.wenku8`）：彩色那支把蓝书整块缩进圆里保护起来，
   * 染色那支却把同一块蓝当底摘掉、只剩书签当墨（`框内=0.07`）—— 一个模块两套主张，屏幕上就是
   * 「应该蓝色部分上色」。这条一开，极性跟着档位走：wenku8 `底占 0.91→0.00`、`框内 0.07→0.93`，
   * **整本书成墨、书签是镂空**（主人原话：「就是整本书成墨、书签是镂空，继续吧」）。
   *
   * ⚠ 线摆在 **50**，依据是真机 plate 档那四张"裁了只剩"：wenku8 **55** / contacts 三张 **49** /
   *   mipush **32** / easytier **0** —— 50 上面只有 wenku8 一张。⚠ **那是彩色侧的读数。**
   *   2026-10-16 装机第一次按**判据侧**逐张量（判据行新增的 `判剩=`）：wenku8 **52**、
   *   contacts **49 / 49 / 47**、kodi 72、mipush 32、easytier 0 —— 两侧最大漂移 **3 个点**。
   *   🔴 **所以空档不是原先记的 6 个点，是 3 个点**（52 vs 49），而 contacts 差 **1 个点**就翻。
   *   线不动（现在仍是 wenku8 独占、主人没发话），但下次真机多出一张 50~55% 的，这条会连它一起翻；
   *   **届时摊分布要摊判据侧那一本**，别拿彩色那行的数对表。
   *
   * ⚠⚠ **这条线上还挂着一位前提：`!spiky`（2026-10-16，Kodi「还是整块上色了」）。**
   *   `plate` 档有**两条入口**（见 [COVER_BOX_FLOOR]）：`keep`（"圆会咬掉一大块形状"，`keepPct < 60`）
   *   与 `spiky`（"圆盘比外框小太多"，**纯几何**）。标定那四张样本**全在 keep 入口**；spiky 入口说的是
   *   几何适配、对像素归属一个主张都没有，极性跟着它走就是"这张图里没有底"。kodi 就是被漏进来的
   *   （`spiky`、`keepPct=72` 过了 50 这条线）—— 所以本条是**三位一起**，不是单独那一个百分数。
   *
   * 🔴 **为什么这条从 `MonochromeSynth` 搬进这里（2026-10-16 全量检查抓到的）**：判据台第十六闸
   *   （`_harness/PlateSubKt.kt`）原先**自己手写了一遍**这个式子。于是"把生产里的 `!spiky` 删掉"
   *   闸上的 pin **一个字都不动** —— 那道闸测的是镜像，不是生产，而它当天还"变异测试通过"过一次。
   *   搬进这个文件 = 生产与台子共用同一份，且这个文件**在 `_harness/build.sh` 的编译清单里**。
   *   ⚠ 今后改这条线，**两位（阈值 + `!spiky`）一起看**，并且必须重做一次变异：改这里、看 pin 动不动。
   */
  internal const val PLATE_SUBJECT_MIN = 50

  /**
   * 染色几何搬动的**上限**：倍数超过这个数的图标整张不搬（不是缩到上限 —— 搬一半会得到一张
   * 他没见过也没验收过的中间尺寸）。
   *
   * 线摆在 **1.75 与 1.90 中间**，两端各留 0.07~0.15 的余量，贴的是真机同一版（19:45 那一版，
   * 2026-10-15 00:23 重放那一趟）的两张邻居：下面最近的一张是 uu远程 `缩1.75×`（他说没问题），
   * 上面最近的一张是 openlist `缩1.90×`（他点名的两张之一，另一张元宝 `切2.23×`）。
   *
   * ⚠ **这一档不是只动那两张。** 与验收那一版配对（两趟都出现的 90 个包）：`>1.82` 一共 **22 个包**
   *   被退回不搬，绝大多数是快捷方式 —— 设置·电量 2.86 / 时钟·秒表与新建计时器 2.35 / 搜索·语音 2.35 /
   *   YouTube·短视频 1.98 / 相册·手气不错 1.95 / 漫画·历史 1.86 一类。桌面上看得见的启动图标里，
   *   最大的是 uu远程 `缩1.75×`（留），所以这一刀在他眼里就是那两张缩回去。
   *   余量一处：**不用管**。相册·截屏正好 `缩1.82×`，压线、随重放取整落到"不搬"那一侧 —— 主人
   *   2026-10-15 判「相册不用管它，原本就适配取色了」：相册**本体**（`mipmap`）确实从不走这条合成
   *   （自带 monochrome 层，原样透传，真机日志里它一张判据行都没有），这三张是长按菜单里的
   *   **快捷方式** drawable。**线就停在 1.82，别为了压线那张挪到 1.85。**
   *
   * 🔴 **2026-10-17 从 `MonochromeSynth` 搬进来**：判据台编得动这个文件、编不动那个。
   *   以前 1.82 这个数在 `PlateSubKt` 与 `ReframeDiffKt` 里**各抄了一份**，改这里那两处不会跟着动。
   */
  internal const val GEO_ZOOM_CAP = 1.82f

  /**
   * 装机那条链的**唯一入口**：量窗口 + 卡上限。返回 `null` = 这张不搬（真满幅、超上限、或裁圆关着
   * 时由调用方自己判）。生产、第十六闸、reframe 差异台三处**都必须走这里** ——
   * 各自重写一遍 `of(...).takeIf { zoom <= 自己那份 1.82f }`，闸测的就又是镜像。
   */
  internal fun measure(px: IntArray, edge: Int): Window? =
    of(px, edge, edge)?.takeIf { it.zoom <= GEO_ZOOM_CAP }

  /**
   * 裁剪窗口，坐标是**被量那张图的 0..1 比例**（与像素尺寸无关，所以换边长可比）。
   *
   * @param plate true = 这一档"裁不动"（不裁、缩进、彩色那支补白底）。
   * @param keepPct 按圆裁下去还剩几成图案（判据自己报出来，供日志与语料账用）。
   * @param zoom 这个窗口换算成的放大/缩小倍数（>1 = 放大填满，<1 = 缩进）。
   * @param spiky 这个 `plate` 档是不是由**「圆盘比外框小太多」**那条入口进来的（见 [COVER_BOX_FLOOR]）。
   *   ⚠ **这一位不参与裁圆自己的任何判断**，它是给**下游**用的：染色那支的极性（`plateIsSubject`）只认
   *   `plate` + `keepPct`，而那两条线上跑着**两条含义完全不同**的入口 —— keep 入口说的是"圆会咬掉一大块
   *   形状，这块大色块是**要整块保住的图案**"（极性该跟着它走）；spiky 入口说的是"拿内切圆当窗口会把整幅
   *   放大到溢出画布"——**纯几何**，一个像素归属都没主张。2026-10-16 `org.xbmc.kodi` 就是死在混用上：
   *   它 spiky 入口进来、`keepPct=72` 过了 50 那条线，染色侧于是被告诉"这张图里没有底" → H3 参照=空气
   *   → 整块灰白菱形盘都成墨（主人报「kodi 还是整块上色了」）。
   *   不记这一位就只能靠"keepPct 高 ⇒ 大概是 spiky"去猜，那是拿一个**余量不明的数**代替结构事实。
   *
   * ⚠ 后面那几项**只进日志**，不参与判断。留着它们是因为这两行日志是装机后的唯一读数
   *   （`_harness` 的语料账与 README 记录的排查路径都按字段 grep）—— 抽成共用之后，
   *   格式一个字都不能变，否则"哪一档开火几张"那种账当场就点不成了。
   */
  class Window(
    val l: Float, val t: Float, val r: Float, val b: Float,
    val plate: Boolean, val keepPct: Int, val zoom: Float,
    val boxW: Int, val boxH: Int, val rBox: Float, val rCover: Float,
    val scanW: Int, val scanH: Int, val bodyOverFar: Float,
    val spiky: Boolean,
    /**
     * **未取整**的那条 keep 判据本身（`keep < keepTotal * KEEP_RATIO`）。
     *
     * 为什么单独带一位：日志以前用 `keepPct < 60` 反推"这张是走 keep 进来的"，而 `keepPct` 是
     * `round(keep*100/keepTotal)` —— 落在 `[59.5, 60)` 的那批会被印成"没过线"，而分支实际走了 keep。
     * 拿取整值去反推一个由未取整值决定的分支，就是让日志去猜代码；这一位把猜换成事实。
     * ⚠ 它**不参与任何判断**，只进日志（极性那条判据仍按 `keepPct` 取整值比 50，那是已验收的行为）。
     */
    val byKeep: Boolean,
  ) {
    /**
     * 这一档要不要把**染色的极性**接管过去（"这张图里没有底，整块都是要保住的图案"）。
     *
     * ⚠ **这是唯一一份算式** —— 生产（`MonochromeSynth`）与判据台（`_harness/PlateSubKt`）都调它。
     *   分成两份写过的代价见 [PLATE_SUBJECT_MIN] 上面那段。
     */
    val polaritySubject: Boolean get() = plate && keepPct >= PLATE_SUBJECT_MIN && !spiky

    /**
     * 把窗口从**视口空间**换算到**图层空间**（108dp 画布，可见区只占中间那 `1/(1+2f)`）。
     *
     * 为什么需要：[of] 量的是"整个图标画进视口"那张图（彩色那支看到的就是那个），而单色掩码活在
     * 图层画布里 —— 桌面画图层时按 `1+2f` 倍铺出去再裁边（`AdaptiveIconDrawable.draw()`）。
     * 不换算就把视口空间的窗口套到图层空间的掩码上，两个 1.5 正好互相抵消，**重排等于没做**
     * （2026-10-14 第二版就是这么错的：元宝块头 53.5% → 52.7%）。
     *
     * [zoom] 原样保留 —— 它是彩色那支那个放大倍数，日志与语料账要报的是"桌面上动了多少"。
     *
     * ⚠ 换算**不动 `zoom`**：同一个数字在换算前后代表两套格子（差 `(1+2f)` 倍），所以拿它当阈值
     *   之前必须先确定它是哪一档量的。装机这一版染色量与搬都在图层那一张上、彩色在视口那一张上，
     *   两行日志里的倍数不能直接互相对表；这个换算现在只有判据台在用。
     */
    fun inLayer(f: Float): Window = Window(
      (l + f) / (1f + 2f * f), (t + f) / (1f + 2f * f),
      (r + f) / (1f + 2f * f), (b + f) / (1f + 2f * f),
      plate, keepPct, zoom, boxW, boxH, rBox, rCover, scanW, scanH, bodyOverFar, spiky, byKeep,
    )
  }

  /**
   * 量一张 ARGB 图，返回裁剪窗口；`null` = 不动。
   *
   * [px] 是**行主序**的 `w*h` 个 ARGB 像素（`Bitmap.getPixels` 的原样，非预乘）。
   * 边长超过 [SCAN_EDGE] 的会先就近降采样到它 —— 这一趟的成本与像素数同阶，而两个消费方给的
   * 尺寸不一定相同（彩色那支自己就画到 128；染色那支拿到的是图层尺寸），降采样让两边跑在同一套格子上。
   *
   * ## 降采样之后过一层按内容的缓存（2026-10-17）
   * 桌面每绑一次图标都 `getConstantState().newDrawable()` 交出一个**新实例**，而"只量一次"那个标记
   * 是**按实例**存的 —— 真机量到 **608 次量测 / 103 个图标 = 每个图标 5.9 次**（中位 0.73 ms/次）。
   * 拆开看：`of()` 里判据占 0.56 ms、光栅化只占约 0.17 ms（`CostKt` 现量），所以省掉重复判据是大头。
   *
   * 键为什么是**格子内容哈希**而不是图标身份：身份（包名/资源 id）要额外带上"应用更新"那一层才不会串，
   * 带漏了就是**静默把旧窗口给新图**；而内容哈希天然不会 —— 图变了键就变。代价是每次要先画图+哈希（≈0.19 ms），
   * 换掉的是 0.56 ms 的判据，净省约 3.7 倍；这一条推翻了我一开始"键必须不画图就能算"的推断（那是猜的，没量）。
   *
   * ⚠ **缓存的是 `scan` 那一步，不是整趟**：降采样在缓存之前跑（不然省不掉），所以 384 那一路
   *   每次都付降采样的钱 —— 那是正确性要的钱（键必须是判据真正吃的那张格子）。
   * ⚠ **`null` 也进缓存**（负缓存）：满幅是真机上的多数（345/486 行），它恰恰是"什么都不做"这个答案，
   *   不缓存就等于多数图标白量。
   * ⚠ **64 位哈希会撞**：撞了就是把**别的图标**的窗口交出去（屏幕上=裁错一圈，无声）。上限 256 条、
   *   每条约 100 B，生日风险极低，但不能靠"极低"过关 —— 反证钉在 `_harness/WinCacheKt.kt`
   *   （同一张必命中、改一格必不命中旧键、两张交替各拿各的窗口、满幅负缓存），改这块缓存后跑它。
   */
  fun of(px: IntArray, w: Int, h: Int): Window? {
    if (w <= 0 || h <= 0 || px.size < w * h) return null
    val shrink = minOf(1f, SCAN_EDGE.toFloat() / maxOf(w, h))
    val sw = maxOf(1, (w * shrink).roundToInt())
    val sh = maxOf(1, (h * shrink).roundToInt())
    val s = if (sw == w && sh == h) px else downsample(px, w, h, sw, sh)
    val key = gridKey(s, sw, sh)
    synchronized(winCache) {
      if (winCache.containsKey(key)) {
        winHits++
        return winCache[key]
      }
    }
    val t0 = System.nanoTime()
    val win = scan(s, sw, sh)
    val dt = System.nanoTime() - t0
    synchronized(winCache) {
      winMisses++
      winScanNs += dt
      if (dt > winScanMaxNs) winScanMaxNs = dt
      winCache[key] = win
    }
    return win
  }

  // ── 上面那层缓存的容器与账（都是纯 JVM 的，判据台也编得动）──

  /** 一屏图标 ~60，桌面 + 抽屉 + 设置三联各一份也 200 出头；`Window` 是 14 个字段的对象。 */
  private const val WIN_CACHE_MAX = 256

  /** 访问序 LRU：超上限丢最久没用的那条（不是整表清空 —— 清空会让下一屏全部现量，白付）。 */
  private val winCache = object : LinkedHashMap<Long, Window?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<Long, Window?>): Boolean = size > WIN_CACHE_MAX
  }

  /** 命中/未命中与"命中省下的判据时间"用累计值估（miss 的平均 × hits），只为日志可见，不参与任何判断。 */
  @Volatile var winHits = 0L; private set
  @Volatile var winMisses = 0L; private set
  private var winScanNs = 0L
  private var winScanMaxNs = 0L

  /** 平均一次判据多久（miss 实测的均值；一次都没跑过就是 0，日志里会显成"量不到"）。 */
  val winScanAvgNs: Long get() = synchronized(winCache) { if (winMisses == 0L) 0L else winScanNs / winMisses }
  val winScanMaxMsVal: Long get() = synchronized(winCache) { winScanMaxNs / 1_000_000 }

  /** 当前条数 —— 反证台用它验"LRU 真有上限"（没这个读数，"有界"就只是注释里的一句承诺）。 */
  val winCacheSize: Int get() = synchronized(winCache) { winCache.size }

  /** 清掉整张缓存（装机身份换了、或配置里"强制重算"时用）。 */
  fun clearWindowCache() = synchronized(winCache) { winCache.clear() }

  /** 内容哈希：FNV-1a 64 走完整张格子 —— 一格一改就必须换键（这是"不会把旧窗口给新图"的全部依据）。 */
  private fun gridKey(px: IntArray, sw: Int, sh: Int): Long {
    var h = -3750763034362895579L   // FNV-64 offset basis
    for (p in px) {
      h = (h xor (p.toLong() and 0xffffffffL)) * 1099511628211L
    }
    return h * 31L + sw.toLong() * 4096L + sh.toLong()
  }

  /** 就近降采样（不平均：这一趟要的是"哪里有没有像素"，平均会把抗锯齿毛边抬过门槛）。 */
  private fun downsample(px: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray {
    val out = IntArray(sw * sh)
    for (y in 0 until sh) {
      val sy = minOf(h - 1, (y * h.toFloat() / sh).roundToInt().coerceAtLeast(0))
      for (x in 0 until sw) {
        val sx = minOf(w - 1, (x * w.toFloat() / sw).roundToInt().coerceAtLeast(0))
        out[y * sw + x] = px[sy * w + sx]
      }
    }
    return out
  }

  /** 在已经 ≤[SCAN_EDGE] 的格子上跑三条出口。 */
  private fun scan(px: IntArray, sw: Int, sh: Int): Window? {
    // ① 图案在哪（外框）：一行/一列要有足够像素才算数，挡掉孤立噪点把框撑开
    var x0 = sw; var x1 = -1; var y0 = sh; var y1 = -1
    val rowMin = maxOf(BOX_ROW_MIN_PIXELS, sw / BOX_ROW_DIVISOR)
    val colMin = maxOf(BOX_ROW_MIN_PIXELS, sh / BOX_ROW_DIVISOR)
    for (y in 0 until sh) {
      var n = 0
      for (x in 0 until sw) if ((px[y * sw + x] ushr 24) >= BOX_ALPHA_MIN) n++
      if (n >= rowMin) { if (y < y0) y0 = y; y1 = y }
    }
    for (x in 0 until sw) {
      var n = 0
      for (y in 0 until sh) if ((px[y * sw + x] ushr 24) >= BOX_ALPHA_MIN) n++
      if (n >= colMin) { if (x < x0) x0 = x; x1 = x }
    }
    if (x1 < 0 || y1 < 0) return null
    val bw = x1 - x0 + 1
    val bh = y1 - y0 + 1

    // ② 圆能填到多大：以图案中心为心，圆盘整个落在不透明区里的最大半径
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    val rBox = minOf(bw, bh) / 2f
    val rCover = coveredRadius(px, sw, sh, cx, cy)
    val counts = keepCounts(px, sw, sh, cx, cy, rCover)
    val keep = counts[0]
    val keepTotal = counts[1]
    val keepPct = if (keepTotal > 0) (keep * 100f / keepTotal).roundToInt() else 0

    // ③ 裁下去会丢掉太多形状（宽幅、铃铛、十字、环…）→ 不裁：整幅缩进圆里。
    //    判据是"圆里还能留下几成图案"，基准是正方形按圆形裁的 π/4 = 78.5%。
    //    ⚠ **第二条入口**（2026-10-15 主人裁决）："不透明圆盘比外框小太多"的也走这一档 ——
    //      `rCover` 当窗口只对**圆盘状**的图案成立（放大之后那个圆盘正好填满圆，这才是"内切填满"的本意）；
    //      对**字母 / 尖角**那一类会把整幅放大到溢出画布、被圆切掉两端。真机 `org.xbmc.kodi`：
    //      外框 92~97%、`rCover` 只有外框的 0.72 → 放大 1.49 倍 → 外框比画布宽 38%。
    //      主人原话：「我觉得这个可以用那个缩小并加白底的那个算法」。
    val spiky = rCover < rBox * COVER_BOX_FLOOR
    // keep 判据**只在这里算一次**（未取整），分支与日志共用同一个布尔 —— 别让日志再拿 `keepPct` 反推。
    val byKeep = keepTotal > 0 && keep < keepTotal * KEEP_RATIO
    if (keepTotal > 0 && (byKeep || spiky)) {
      val ext = extents(px, sw, sh, cx, cy)
      val far = ext[0]
      val body = ext[1]
      if (far < 1f) return null
      // 三条约束取最保守（= 图案最小）的那个：主体进安全区 / 尖角不贴边 / 墨量对齐。
      // ⚠ 取 max 意味着墨量对齐只能往小里对齐，永远不许把外缘切掉（主人 2026-10-14：
      //   "图案就是得完整的，不能光看占比"）。所以"外缘先顶到圆边"的那几种不会被放大。
      val rBox = maxOf(far / TIP_FLOOR, body / SAFE_ZONE)
      val rInk = sqrt(keepTotal / (4f * PLATE_INK))
      val rWin = maxOf(rBox, rInk) * SHRINK_EXTRA
      return window(cx, cy, rWin, sw, sh, plate = true, keepPct = keepPct,
        boxW = bw, boxH = bh, rBox = rBox, rCover = rCover, bodyOverFar = body / far, spiky = spiky,
        byKeep = byKeep)
    }

    if (rCover < 1f) return null
    // ④ 裁得好的：按不透明圆盘内切、填满圆。不收边、不铺白底。
    //    满幅的那些直接不动（逐位不变）—— 它的内切圆本来就被自己填满，再放大那一两个百分点
    //    会让整屏图标都悄悄长大。
    if (bw >= sw * BOX_FULL_RATIO && bh >= sh * BOX_FULL_RATIO) return null
    return window(cx, cy, rCover, sw, sh, plate = false, keepPct = keepPct,
      boxW = bw, boxH = bh, rBox = rBox, rCover = rCover, bodyOverFar = 0f, spiky = false,
      byKeep = false)   // 走到这里 keep 必然不成立（上面那个 if 就是它或 spiky 才进）
  }

  private fun window(
    cx: Int, cy: Int, r: Float, sw: Int, sh: Int, plate: Boolean, keepPct: Int,
    boxW: Int, boxH: Int, rBox: Float, rCover: Float, bodyOverFar: Float, spiky: Boolean,
    byKeep: Boolean,
  ): Window {
    val l = cx + 0.5f - r
    val t = cy + 0.5f - r
    val right = cx + 0.5f + r
    val bottom = cy + 0.5f + r
    val zoom = maxOf(sw / (right - l), sh / (bottom - t))
    return Window(l / sw, t / sh, right / sw, bottom / sh, plate, keepPct, zoom,
      boxW, boxH, rBox, rCover, sw, sh, bodyOverFar, spiky, byKeep)
  }

  /**
   * 交付剪影的**归一缩放**（只缩不放，返回 ≤1）：让墨的主体落在盘的 [SAFE_ZONE] 以内。
   *
   * 这条**不是**"跟裁圆走" —— 裁圆对绝大多数图标是空操作（它们的背景本来就是一块实心盘，
   * 整图标看就是满幅，没什么可调）。这里量的是**交付出来的墨自己**：28 张真机剪影实测占盘
   * 从 55% 一路铺到 150%（150% = 顶到图层四边、被盘切一圈），这条散布没有任何现成尺子在管。
   *
   * 用的尺子是现成的两条：[SAFE_ZONE]（内容本该落在可见区里 66dp 的安全区内）与 [TIP_TAIL]
   * （尖角可以探出去 —— 按"体量"而不是最远那个像素算，否则一个尖角就把整幅压小）。
   * **只缩不放**是主人那句「别的一堆图标又放大了」的落实：本来就在区里的一个像素都不动。
   *
   * ⚠ [plateSide] 必须是**盘的边长**（可见区），不是整张图层的画布边长 —— 分母用错会把一堆
   *   读成"100% 占满"，那张"大小不一"的账当场就是假的（本仓第一版错在这里）。
   * @param alpha 交付掩码的 alpha（0..255），行主序 `w*h`。
   */
  fun silhouetteScale(alpha: IntArray, w: Int, h: Int, plateSide: Int): Float {
    if (plateSide <= 0) return 1f
    val ext = extents(alpha, w, h, w / 2, h / 2, shift = 0)
    val body = ext[1]
    if (body < 1f) return 1f
    return minOf(1f, SAFE_ZONE * (plateSide / 2f) / body)
  }

  /** 以画布中心为锚把 [px] 整体缩到 [s]（最近邻，理由同 [reframe]）。[s] ≥1 时原样返回。 */
  fun scaleAbout(px: IntArray, w: Int, h: Int, s: Float): IntArray {
    if (s >= 0.999f) return px
    val out = IntArray(px.size)
    val cx = (w - 1) / 2f
    val cy = (h - 1) / 2f
    for (y in 0 until h) {
      val sy = ((y - cy) / s + cy).toInt()
      if (sy !in 0 until h) continue
      val row = sy * w
      for (x in 0 until w) {
        val sx = ((x - cx) / s + cx).toInt()
        if (sx in 0 until w) out[y * w + x] = px[row + sx]
      }
    }
    return out
  }

  /**
   * 把 [px] 按 [win] 重排成"窗口铺满目标矩形"的那张图（cover + 窗口中心对齐目标中心），
   * 与 [MaskedDrawable] 画彩色那支时用的**同一个仿射**。双线性采样，返回新数组。
   *
   * ⚠ **染色那一路搬过的掩码，目标矩形必须是"可见区"（画布中间那 2/3），不是整张画布。**
   *   单色那一层是 108dp 的画布，桌面按 `1+2f` 倍铺进去再裁四边 —— 窗口铺满整张画布，落到屏幕上
   *   就是**又放大 1.5 倍、四边贴出屏幕**。这不是推演，是量出来的（2026-10-15，装机两版对同一张
   *   `me.ele` 的交付掩码：占盘 0.65 → 1.00，墨 0.09 → 0.19）。没搬过的那一张不在此列 —— 图层
   *   本来就是应用自己按 108dp 排好的，缩它等于改 49 张满幅图标。
   *
   * ## 采样必须是最近邻 —— 因为现在**判据吃的就是这张重排后的图**
   * 2026-10-15 主人把这一刀搬到判据之前（「取色判断得按调整后的图标来，不然到时候出错了」）：
   * 交出去的那张 = 判过的那张，不再事后 resample。代价是采样不能再用双线性 —— 双线性会造出
   * **源图里根本没有的中间浓度**，而底色场/分档吃的正是浓度分布。那条路量过：双线性让
   * `com.microsoft.xboxone.smartglass` 在 384 上**换支**、`底占 0.45→0.00`（整张交成一块黑饼），
   * 源尺寸漂移面 81%→84%；最近邻则 `branch`/`platecut` 与基线**逐字相同**。
   * ⚠ 最近邻不做抗锯齿：重排完边缘是阶梯。桌面那 1.5 倍缩放会抹掉一部分，但 `淡`/`soft`
   *   那一列会变 —— 所以这一版的账必须重量，不许引用双线性那版的数。
   */
  fun reframe(
    px: IntArray, w: Int, h: Int, win: Window,
    tx: Int = 0, ty: Int = 0, tw: Int = w, th: Int = h,
  ): IntArray {
    val boxW = ((win.r - win.l) * w).coerceAtLeast(1f)
    val boxH = ((win.b - win.t) * h).coerceAtLeast(1f)
    val scale = maxOf(tw / boxW, th / boxH)
    val cxSrc = (win.l + win.r) / 2f * w
    val cySrc = (win.t + win.b) / 2f * h
    val cxDst = tx + tw / 2f
    val cyDst = ty + th / 2f
    val out = IntArray(px.size)
    for (y in 0 until h) {
      // 目标像素 → 源坐标（draw() 那一步的逆变换：先减目标心、除 scale、再加回窗口心）。
      // ⚠ **最近邻**：判据吃的就是这张，插值会凭空造出浓度（理由见函数上那段文档）。
      val sy = ((y + 0.5f - cyDst) / scale + cySrc).toInt()
      if (sy !in 0 until h) continue
      val row = sy * w
      for (x in 0 until w) {
        val sx = ((x + 0.5f - cxDst) / scale + cxSrc).toInt()
        if (sx in 0 until w) out[y * w + x] = px[row + sx]
      }
    }
    return out
  }


  /**
   * [0] = 半径 r 的圆盘里还剩多少"有像素"的格；[1] = 图案总共有多少格。
   * 两者之比就是"按这个圆裁下去，图案还剩几成" —— 判"裁不裁得动"用的就是它，
   * 比"半径比"诚实：细胳膊的十字半径不小，但裁完只剩中间那一根。
   */
  private fun keepCounts(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Float): IntArray {
    var keep = 0
    var total = 0
    val rr = r * r
    for (y in 0 until h) for (x in 0 until w) {
      if ((px[y * w + x] ushr 24) < BOX_ALPHA_MIN) continue
      total++
      val dx = x - cx
      val dy = y - cy
      if (dx * dx + dy * dy <= rr) keep++
    }
    return intArrayOf(keep, total)
  }

  /**
   * 图案的"体量"有多远：[0] = 最远像素的半径；[1] = 外侧只占 ≤[TIP_TAIL] 的那些尖角之外的半径。
   *
   * 为什么要两个：只取最远像素的话，一个尖角就把整幅图往里推（三角/星形被压小）。
   * 用**像素**而不是外框：三角的外框角是空的，按外框算会白缩一圈。
   * 一趟扫完（半径直方图），与像素数同阶。
   *
   * [shift] = 24 时吃的是 ARGB 数组；= 0 时吃的是"alpha 单独一行一个数"的数组
   * （交付掩码归一那条尺子 [silhouetteScale] 用的就是后者）。**只这一份实现**，两处共用。
   */
  private fun extents(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, shift: Int = 24): FloatArray {
    var far = 0f
    val hist = IntArray(minOf(w + h, 4096))
    var total = 0
    for (y in 0 until h) for (x in 0 until w) {
      if ((px[y * w + x] ushr shift) < BOX_ALPHA_MIN) continue
      val dx = (x - cx).toFloat()
      val dy = (y - cy).toFloat()
      val r = sqrt(dx * dx + dy * dy)
      if (r > far) far = r
      hist[minOf(hist.size - 1, r.toInt())]++
      total++
    }
    var tail = 0
    var body = far
    for (r in hist.indices.reversed()) {
      tail += hist[r]
      if (tail > total * TIP_TAIL) { body = r + 1f; break }
    }
    return floatArrayOf(far, body)
  }

  /**
   * 以 (cx,cy) 为心，圆盘能撑到多大仍然**整个落在不透明区里**（画布外算不透明区之外）。
   *
   * 这就是"内切圆"的半径：拿它当裁剪窗口，放大后圆内不会剩透明格 —— 主人要的"圆要完整，
   * 中间不要有空隙"就是它。距离用两遍 chamfer（近似欧氏，够用），复杂度与像素数同阶。
   *
   * ⚠ 返回 0 说明**中心自己就是空的**（环/甜圈那类镂空图案）—— 调用方会退回按外框内切。
   */
  private fun coveredRadius(px: IntArray, w: Int, h: Int, cx: Int, cy: Int): Float {
    val inf = (w + h).toFloat()
    val d = FloatArray(px.size)
    for (i in d.indices) d[i] = if ((px[i] ushr 24) >= BOX_COVER_ALPHA) inf else 0f
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      if (d[i] == 0f) continue
      var v = d[i]
      if (x > 0) v = minOf(v, d[i - 1] + 1f)
      if (y > 0) v = minOf(v, d[i - w] + 1f)
      if (x > 0 && y > 0) v = minOf(v, d[i - w - 1] + 1.414f)
      if (x < w - 1 && y > 0) v = minOf(v, d[i - w + 1] + 1.414f)
      d[i] = v
    }
    for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
      val i = y * w + x
      if (d[i] == 0f) continue
      var v = d[i]
      if (x < w - 1) v = minOf(v, d[i + 1] + 1f)
      if (y < h - 1) v = minOf(v, d[i + w] + 1f)
      if (x < w - 1 && y < h - 1) v = minOf(v, d[i + w + 1] + 1.414f)
      if (x > 0 && y < h - 1) v = minOf(v, d[i + w - 1] + 1.414f)
      d[i] = v
    }
    // 画布外按"不透明区之外"算：离边框的距离就是半径上限
    val edge = minOf(cx, cy, w - 1 - cx, h - 1 - cy).toFloat()
    return minOf(d[cy * w + cx], edge) - 1f
  }
}

/**
 * 量图案窗口时把图降采样到的边长：**两条路都用这一个数**。
 *  · 彩色那支本来就光栅化到它（`AdaptiveIconDrawable` 的固有尺寸是 -1，量到固定边长才能与
 *    "这次画多大"解耦 —— 真机同一张图标会以 79/95/96/192/283 几种尺寸被画，尺寸一飘，
 *    "裁完剩几成"这种比例数就在边界上瞎切）；
 *  · 染色那支吃的是 384 的一层，[IconCropWindow.of] 自己降下来。
 *
 * ⚠ 从 192/256 降到 128（2026-10-14）：这一趟的成本与像素数同阶，而它对每个图标都是
 *   净多出来的（真机 `量一张` 中位 2.64ms → 0.60ms）。128 的坐标精度是 1/128 ≈ 0.8%，
 *   对"留 4%~8% 的边"这件事没有可感影响。
 */
private const val SCAN_EDGE = 128

/** 算"有像素"的 alpha 门槛：把抗锯齿毛边（真机上量到 1..7）排除在外。 */
private const val BOX_ALPHA_MIN = 8

/** 一行/一列至少要有这么多个像素才算数 —— 挡掉孤立噪点把外框撑到整块画布。 */
private const val BOX_ROW_MIN_PIXELS = 2

/** 上面那道门槛也随分辨率走（本仓吃过绝对格数门槛在低分辨率上不开火的亏）。 */
private const val BOX_ROW_DIVISOR = 64

/** 外框占到画布这个比例以上就当成满幅：不动缩放，与改前逐位一致。 */
private const val BOX_FULL_RATIO = 0.99f

/**
 * 判"圆内还有没有空隙"的 alpha 门槛：**过半不透明**才算填上了。
 * 低于它的像素在屏幕上就是半透明的 —— 那正是"圆不完整"看上去的样子。
 */
private const val BOX_COVER_ALPHA = 128

/**
 * **裁下去会丢掉几成图案**才判定"不适合裁"。
 *
 * 凭什么定这个数：正方形图案按圆形裁（内切）留下 π/4 = **78.5%** —— 那就是所有正常图标
 * 的样子，也是"观感协调"的基准。留不下 [KEEP_RATIO] 的，说明圆会咬掉一大块形状
 * （宽幅 banner 只留 ~20%、铃铛 ~57%、十字/环更少），那就别裁：缩一缩装进圆里。
 * 真机空档：这一档最高 56%，下一档最低 72%（收版那档样本 78%）—— 线摆在中间有余量。
 */
private const val KEEP_RATIO = 0.6f

/**
 * 「不透明圆盘 ÷ 外框内切圆」低于这条线 = 图案是**字母 / 尖角**那一类，不是圆盘 —— 也走"缩进 + 补白"。
 *
 * 为什么要有它：`rCover` 当裁剪窗口只对**圆盘状**的图案成立（放大之后圆盘正好填满圆）。
 * 外框大而圆盘小的图案走同一档就会把整幅放大到溢出画布：真机 `org.xbmc.kodi` 外框 92~97%、
 * `rCover` 只有外框的 **0.72** → 放大 **1.49 倍** → 放大后外框比画布宽 **38%** → 圆把两端各切掉约 19%
 * （主人判"不好看"，2026-10-15）。
 *
 * 线摆在 **0.80**：语料 703 张里「内切填满」那一档共 28 张，该比值最低 **0.89**（outlook）、
 * 其余 ≥0.94 —— Kodi 的 0.72 掉在空档里，0.80 是空档中点，**语料一张都不动**。
 * 量具：`_harness/WinKt.kt`（单张或整目录过真判据，打印档位 / zoom / rCover）。
 */
private const val COVER_BOX_FLOOR = 0.80f

/**
 * "裁不动"那类留多少边 —— 从**平台自己的安全区**推出来的，不是拍脑袋：
 * 自适应图标的内容本应落在可见区里那个 **66dp 的安全区圆**内，可见区是 72dp，
 * 所以安全区直径 = 可见区的 **66/72 = 91.7%**，即半径要留 8.3%。
 */
private const val SAFE_ZONE = 66f / 72f

/**
 * 绑的是"图案的体量"而不是"最远那一个像素"：外侧占比 ≤ 这个数的像素（尖角、细刺）可以探出安全区。
 * 为什么：只按最远像素算的话，**一个尖角就把整幅图往里推** —— 三角被压成小一号的三角，
 * 而星形/三角这类本来就该"主体收进来、尖角对外透气"（主人："每个图标之间也可能不太一样"）。
 */
private const val TIP_TAIL = 0.02f

/**
 * **不贴边**：图案最远的那一像素只到半径的这个比例。只用于"裁不动"那类 ——
 * 防止它们的尖角顶到圆上（"最好不要贴着图标的边缘，挺难看的"）。
 */
private const val TIP_FLOOR = 0.93f

/**
 * "裁不动"那类的**总缩量**（在 [TIP_FLOOR] / [SAFE_ZONE] / [PLATE_INK] 之上再乘一刀）。
 * 口径来自真机量测（2026-10-14）：乘 1.03 时图案占白圆直径 **81%**，主人仍判"大了"；
 * 这一档乘 1.19 → 约 **70%**（主人判"这个尺寸差不多"）。每 +0.1 大约再小 5 个点。
 *
 * 第二个来源（2026-10-15 读 Lawndesk 时核到）：AOSP 把**老式（非自适应）**图标塞进自适应容器时用的
 * `FixedScaleDrawable.LEGACY_ICON_SCALE = .7f * .6667f`（`LauncherIcons.java:253` 套在包装器前景层上）
 * —— 层是可见区的 `1+2×extraInset` 倍，除回去正好**可见区的 0.70**。同一个数、两条互不相干的来路
 * （一条我从真机量的，一条 AOSP 自己用的）。⚠ 量的**不是同一样东西**：他们量整张位图的外框，
 * 我们量墨迹的外框 —— 所以是旁证，不是推导。
 * ⚠ 只管这一类；裁得好的图标不受它影响。
 */
private const val SHRINK_EXTRA = 1.19f

/**
 * "裁不动"那类的**墨量目标**：把图案的不透明面积对齐到面板的这个比例。
 *
 * 为什么除了外缘还要管墨量（主人："不规则图标大小不一，也应该有大概的判断再决定缩小多少"）：
 * 只把外缘套在同一个圈上，**块头**差得很远，挤在一起看就是大小不一。
 *
 * ⚠ 它**只能把胖的收下来，不能把瘦的放大**（窗口取三条尺子的最大值）—— 所以那句
 *   "对齐之后它们才是一个视觉尺寸"对宽幅 / 星形那一类不成立。
 * ⚠ **真机上这条尺子从没绑过**：装机那几张开火的图标，外缘那条都比它严（16:45 那一版
 *   缩量与上一版逐张相同）。而 `_circab.cjs` 现在跑出的墨量（20/22/23/50）与当初记在
 *   注释里的 18/29/38 **不是同一次量测**，两组别混用。见 docs/KNOWN-LIMITS.md。
 */
private const val PLATE_INK = 0.5f
