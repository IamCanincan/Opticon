package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.util.Base64
import android.util.Log
import com.iamcanincan.opticon.runtime.TAG
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 把「模型实际吃进去的那一张」和「模型吐出来的那一张」**原样**打包进日志。
 *
 * ## 为什么要这条通道
 * 判据台只有 11 张代理图，而他报的那批应用（12306 / 中国移动 / 元宝 / 皎月连 / 闲鱼 / termux）
 * 发的是 adaptive **vector** XML 或 **webp** —— JVM 这边一张都解不出来。于是每一轮"更细致"
 * 其实都是在对着**别的图**调参，改完只能等他说一句「还是不行」。
 * 这条通道把真机上光栅化之后的像素带回来：矢量已经画成位图、webp 已经解过码，
 * 拿到的就是模型看到的**同一张**。判据台对着它重放，出的预览图就是屏幕上那张的等价物。
 *
 * ## 为什么走日志而不是写文件
 * 被注入的进程（桌面）没有 root、写不了共享存储，而 logcat 已经是这个项目验证过的唯一出口
 * （见 README「验证」）。PNG 是无损的，一张 384² 的图标压完几十 KB，按 3000 字符切片发，
 * 每个图标十几到几十行 —— 所以**必须限量**：每进程最多 [MAX_ICONS] 个，而且只挑可疑的那几个
 * （见 [suspicious]），不是每个图标都 dump。
 *
 * ⚠ 这是诊断通道，**不参与任何判断**，也不改变交出去的掩码。
 */
internal object MonoDump {

  /**
   * 自动「可疑」转储的开关（2026-10-25）：默认**关**。
   *
   * 为什么：每次冷启动都会把几十张可疑图压 PNG + base64 灌进 logcat（真机 `dump#40` 那一串），
   * 既占冷路径（一张十几到几十毫秒），也把 256KB 的环形缓冲冲掉 —— 快键/命中窗那些真正要看的
   * 读数被挤没了。**点名**（[WATCH]）那几张仍然照带：它们才是排查具体问题时要的东西。
   * 需要自动扫新问题时，把这一个常量改成 true 重编诊断包即可，不改别的。
   */
  internal const val AUTO_SUSPICIOUS = false

  /** 每进程最多带回来几张。PNG 压完一张十几 KB、六到十八行日志，所以这个数可以比想象中大。 */
  private const val MAX_ICONS = 48

  /** 点名名单的独立张数上限：不受共享预算约束（点名的图标本来就只有那几家）。 */
  private const val MAX_WATCHED = 60
  private var watchedSent = 0

  /** 一条日志的安全长度：logd 单条约 4000 字节，留足余量免得截断。 */
  private const val CHUNK = 3000

  /**
   * **这一份 dump 是谁发的** —— 打在每个 `dump#N` 后面（`p=<十六进制 pid>`）。
   *
   * 为什么必须带：[sent] 是**每进程**各自从 1 开始数的，而一次抓取日志里从来不只一个进程
   * （模块的作用域里有桌面、也有 SystemUI / 设置；桌面自己被杀重启时，旧进程那一段还在文件里）。
   * 只按 `(dump#N, what)` 重组，两个进程就会写成同一组 —— 而且两边的分片**内容是不同的**
   * （不同尺寸、不同 drawable），后到的那片覆盖先到的那片。
   * 真机四份日志实测（`live2 / live4 / live / cap1`）：
   *   live2 = 3 个进程 **102 组**，旧口径只认得出 **36** 组，其中 **7** 组是跨进程混拼；
   *   live  = 4 个进程 **122 组**，旧口径 **44** 组，混拼 **10**；
   *   cap1  = 只有 1 个进程 → 旧口径 67/68 全对，**这就是上一轮误判"logd 吞了 15% 的 BEGIN 行"的原因**：
   *           单进程那份什么也没丢，多进程那三份丢的是我自己的键，不是通道。
   * 每条分片行多 7~9 个字符（一轮约 +12KB / 2.2MB），换来的是"这一张到底是谁画的"能回答。
   */
  private val PROC = Integer.toHexString(Process.myPid())

  private var sent = 0

  /** 一次进程允许发多少 KB 的 PNG。logcat 是共享通道，超了就停，别把别的日志挤掉。 */
  private var budget = BUDGET_KB

  /** 停手这件事要报，而且**只报一次**（不然每被拦一张一行，把日志本身挤掉了）。 */
  private var stopReported = false

  /** 点名的图标被拦下时每个标签报一次：这一条就是"为什么它一张都没回来"的答案。 */
  private val dropped = HashSet<String>()

  /** 被**去重**挡下的那些，每个去重键报一次（不然桌面每滚一屏就多一行，把日志本身挤掉）。 */
  private val dupSeen = HashSet<Long>()

  /**
   * 只留给**点名名单**的那一块预算（KB）。
   *
   * 为什么要有这一条：普通可疑图是按渲染先后顺序抢预算的。真机实测（2026-10-11 20:34 那一轮）
   * 桌面一起来，前 23 张花掉 1207 KB → 预算见底（`dump 不抓 … 预算剩 -7KB`），而
   * **translate 就在那一轮被点名、一张都没回来** —— 于是"设备上到底画成什么样"只能靠猜，
   * 猜错的那次就是被主人否掉、已 revert 的那一刀。
   * 2026-10-12 把总预算抬到 [BUDGET_KB]、保留额抬到这一条：普通图能花的绝对量不变，
   * 但名单里那 24 个包从此有一块谁也抢不走的地 —— 而且它们现在每个可能带**两种尺寸**各一张
   * （见 [dedupeKey]），要的正是这一块。
   *
   * ⚠ **2026-10-12 夜再抬到 1500，因为名单每张多了 `under` 那一份**。按 cap1（唯一一份
   * 单进程、67/68 全拼回来的一轮）实测的张数与尺寸：名单 26 张各带 `src 41KB + out 16KB + under 6KB`
   * ≈ **1.6MB**，而那一轮普通可疑图只花了 15 张 ×41KB ≈ **0.6MB** —— 所以把保留额从 900 抬到 1500
   * （普通图剩 900KB ≈ 22 张，比实际用到的多 7 张），总预算不动。
   * 不抬的代价是看得见的：名单尾部那几张会连 `src` 都发不出来，而那正是判一刀成败的东西。
   */
  private const val WATCH_RESERVE_KB = 1500

  /** 一次进程的总预算。改这个数之前先想清楚：这条通道抢的是全设备共享的 logd。 */
  private const val BUDGET_KB = 2400

  /**
   * 已经带过的图标签名。⚠ 必须有：桌面一次加载里同一个图标会被问很多次（不同 Drawable 实例、
   * 换主题、动画帧），上一版 6 个名额全被同一类「白剪影 + 透明底」的健康图标占满，
   * 真正要看的那几张一张没带回来。签名取 8×8 降采样的 alpha 指纹 —— 只要"形状一样"就不重带。
   */
  private val seen = HashSet<Long>()

  /**
   * 这一张值不值得带回来。
   *
   * ⚠ 只挑**可疑的**，而"可疑"不等于"数字大"：上一版把 `框内>0.15 且 空洞<0.005` 也算可疑，
   *   结果带回来的全是透明底上的白剪影 —— 那类图标的正确答案**就是**它自己的轮廓
   *   （刻底报「底占满了整框」= 正确地拒绝去刻），数字难看但结果是对的。
   *   现在只带：判据不成立的（要看为什么）、占框明显偏大的（可能还剩一块底）、
   *   碎成一片的（该连的没连）、淡层明显偏厚的（底没摘干净）。
   *
   * ⚠ **最后加的一条：新开的那一支（H0）接手的，无条件带。** 这四条老门槛量的全是"旧失败形状"，
   *   而 H0 的产物形状是"底腾空、只剩被围住的字形"（`框内 .04~.24`）—— 比"偏大"那条还偏小，
   *   一条都不触发。真机实测：装机后 H0 接手了 23 个 drawable，带回来的像素 **0 张**；
   *   判据数字看不出这一支刻得对不对（它自己的数全绿），只能看像素。
   */
  fun suspicious(o: MonochromeMask.Output): Boolean =
    o.mask == null || o.boxFill > 0.55f || o.components >= 10 || o.soft > 0.08f || o.hyp == 0 ||
      // 「底与墨同色」退掉过至少一支的那几张**无条件带回来**：这一道换的是谁接手，而接手人换了
      // 之后上面每一条旧判据都可能不再报警 —— 上一轮 H0 接手 22 张、像素回来 0 张，就是这么瞎的。
      o.veto != 0
  /**
   * 用户点名的包：**不看可不可疑，无条件带回来**。
   *
   * 这一批改出来的事实是「他说这 7 张反色，而判据对 7 张全说了不翻，且余量都不小」——
   * 那只有一种可能：**我对桌面那张遮罩长什么样的模型是错的**。这种错从判据数字里看不出来
   * （数字全是我自己算的），只能看桌面自己出的那张 `mAlphaBitmap`。所以这里要的不只是原图，
   * 是**桌面的产物**。
   */
  private val WATCH = setOf(
    // 2026-09-24 他点名的这一批：现在全部判成"不翻"，而屏幕上都是反的。
    "com.azure.authenticator", "com.baidu.tieba", "com.neowizgames.game.browndust2",
    "com.ss.android.ugc.aweme", "com.google.android.apps.translate",
    "com.fdg.flashplay.farsee", "com.huawei.appmarket", "com.handsgo.jiakao.android",
    "com.tmri.app.main", "com.moondroplab.moondrop.moondrop_app", "com.predidit.kazumi",
    "com.sankuai.meituan", "com.openlist.mobile",
    // 2026-09-27 他点名的三个「反色」：小步点 / 中国移动是"没找到底→涂满"，
    // 小黑盒是"判成已适配→交回桌面"。后两个之前一张像素都没回来（一个不在可疑名单里，
    // 另一个在**放弃合成**那条 return 上，压根走不到出图的地方）—— 只能靠猜，就猜错了。
    "run.xbud.android", "com.greenpoint.android.mc10086", "com.max.xiaoheihe",
    // 2026-09-29 他又点名两个「细节不足」。yiban 的像素在语料里（判据能直接重放），
    // globalspeed **一张都没回来过** —— 它 `字均=179` 却不触发任何旧的可疑判据，
    // 于是只能看着数字猜。加进来：下次清缓存重渲染时把原图带回来。
    "com.yiban.app", "com.cnspeedtest.globalspeed",
    // ⚠ **2026-10-01：主人点名的 12 张里有 8 张从来不在名单上** —— 这一条是那一整晚走错语料的
    // 直接原因。不在名单 = 它的像素一张都没回来过，于是只能拿 `CorpusCapture`（capture 通道，
    // 让应用自己画一遍）那批去判"屏幕上是什么样"。事后对过：**同一个图标两条通道给的像素不是一套**
    // —— 点名的 7 张里 7/7 数不同、4/7 连分支（H）都换；而 dump 通道这张能逐字对上真机 logcat
    // 的 `底占/框内`。所以"我判的到底是不是屏幕上那张"这个问题，唯一的答案就是把这张带回来。
    // 补齐 12 张（`com.sina.weibo` 到这一轮为止**两条通道都没有过一张像素**，它报的「底部奇怪」
    // 从来没被判过）。**这一条只改诊断通道，不改任何掩码**：加完 8 个包之后六段回归台逐位不变。
    "cn.com.zjol", "com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.sony.songpal.mdr",
    "me.ele", "com.sina.weibo", "com.tencent.hunyuan.app.chat", "com.able.wisdomtree",
    // 2026-10-14「取色吃调整好的图标」这一轮：这 5 张是**乙会改输入、而之前一张像素都没回来过**的。
    // 要把仓里那把尺（第十一闸源尺寸账）重钉到"设备真喂给模型的那张"上，就必须拿到它们的
    // `pxIn` —— 现在判据台用的是我仿造的输入语料，**那份不能当基线**（口径是我猜的）。
    // 其余 4 张（azure / tieba / openlist / 元宝 / 拼多多）上面已经在名单里了。
    "com.netease.uuremote", "com.termux", "com.microsoft.xboxone.smartglass",
    "org.chromium.webapk", "com.example.appui",
    // 2026-10-20「淡线一族」这一轮：把宿主 `cacheDir` 里我们**亲手交出去的 238 张掩码**摊开，
    // 均α 落在 2~10 的有 **80 张**、`α=255` 一格都没有的 **15 张**（`_harness/_a8dist.cjs`）。
    // 这一族里绝大多数是**动态快捷方式/部件**，而它们以前一张像素都没回来过 ——
    // 与 10-01 那条"点名的 12 张里 8 张从来不在名单上"是同一个错法的另一侧。
    // ⚠ 只补**淡的那几个包**（`com.cainiao.wireless` 那张 `identity_code` 均α 1.39 是代表样本），
    //   不补全族：预算 2400KB 抢的是全设备共享的 logd（见 [BUDGET_KB] 那段）。
    // 要回答的问题只有一个：**"细淡线"是我们判底判错，还是源本来就淡** —— 没有 `what=src` 就分不开。
    "com.cainiao.wireless", "com.dianping.v1", "com.spotify.music", "com.coolapk.market",
    "com.openai.chatgpt", "com.absinthe.libchecker", "com.taobao.idlefish",
    "com.microsoft.office.outlook", "com.autonavi.minimap",
  )

  fun watched(label: String): Boolean = WATCH.any { label.contains(it) }

  /** 8×8 的指纹：**形状 + 颜色**都要一样才算同一张。 */
  private fun signature(pixels: IntArray, w: Int, h: Int): Long {
    var hash = 1469598103934665603L
    for (gy in 0 until 8) for (gx in 0 until 8) {
      val x = (gx + 0.5f) * w / 8
      val y = (gy + 0.5f) * h / 8
      val p = pixels[y.toInt() * w + x.toInt()]
      // ⚠ 只按 alpha 指纹会撞车：真机实测 `bd40c4d749c7` 同时是 小步点 与 中国移动 ——
      //   两个都是"圆盘里坐一个字形"，形状指纹逐位相同，于是去重把后带的那张吞掉了
      //   （67 张接手只带回 40 张，这就是主因）。颜色才是它们之间的区别，必须参加。
      for (sh in 0 until 4) {
        hash = hash xor ((p ushr sh) and 0xff).toLong()
        hash *= 1099511628211L
      }
    }
    return hash
  }

  /**
   * 同一个指纹的短十六进制形式，**进日志**用。
   *
   * 为什么要把指纹打出来：桌面有些图标是别人的快捷方式，`label` 解不出包名，日志里就是一串
   * `?`（真机实测：H0 接手的 23 张里 18 张叫 `?`）。判据行与带回来的 PNG 从此没有对得上的
   * 字段 —— 而 `dump#N BEGIN` 那行带的是同一个 `sig`，重放出来的文件名里也就带着它。
   */
  fun sigOf(pixels: IntArray, w: Int, h: Int): String =
    java.lang.Long.toHexString(signature(pixels, w, h)).take(12)

  /** 形状指纹 ⊕ 前景层尺寸：同一张图标的两种尺寸是两个键，各自能带回来一张。 */
  private fun dedupeKey(pixels: IntArray, w: Int, h: Int, label: String): Long =
    signature(pixels, w, h) * 1000003L +
      label.substringAfter("fg=", "").substringBefore(" intrinsic", "").hashCode()

  /**
   * 带一张。**发 src，点名的再发 out 与 under**。
   *
   * ⚠ **掩码（out）本来是不发的** —— 它是模型从 src 算出来的，重放那边跑同一份代码就能自己得到，
   *   双份发只是把日志量翻倍。2026-10-11 改成"点名的发"：重放跑的是**我改过之后的代码**，
   *   要看"设备上到底画成什么样"必须拿设备那一张，不能拿我以为它画成的那一张。
   * ⚠ **`under`（背景层）是 2026-10-12 补的，补的是"复放不忠实"这一件事**：`flatten` 那一路
   *   模型吃的是 `build(合成图, 层底, under)` 三个输入，而通道以前只带合成图 ——
   *   少一个 `under`，`盘刀/反相` 这两刀在判据台上就不是设备上那一刀（translate 那刀的成败正押在这里）。
   *   实测一张背景层压完 **中位 5.8KB**（`rec_model` 里 20 张 `_modelbg.png`；同一图标的合成图 40.8KB），
   *   所以它比 out（16.1KB）还便宜，只给点名的带、走的还是保留额那一条。
   *
   * 全程 runCatching：这是诊断，诊断把自己跑挂的图标弄坏是不可接受的（这里跑在桌面加载图标的
   * 路上，抛出去就是那个图标没了）。失败只打一行原因。
   */
  @Synchronized
  fun maybe(
    id: Int, label: String, src: Bitmap, pixels: IntArray, ramp: IntArray?, under: IntArray?,
    plateIsLayer: Boolean, w: Int, h: Int, why: String, watched: Boolean,
  ) {
    // 普通可疑图花到剩 [WATCH_RESERVE_KB] 就停；点名的那张可以花到底。
    // ⚠ **点名名单只受张数约束、不受共享预算**（2026-10-23 现撞：预算被非点名的吃光，
    //   Pinduoduo 在 WATCH 里却一次没带回来，日志只剩"不抓 … 预算剩 -37KB"）。
    val stop = if (watched) watchedSent >= MAX_WATCHED
      else sent >= MAX_ICONS || budget - WATCH_RESERVE_KB <= 0
    if (stop) {
      // ⚠ 这一条 else 就是这一版要加的东西：静默 return 让"抓不到"看起来像"没渲染过"。
      if (watched && dropped.add(label))
        Log.w(TAG, "Mono: dump 不抓 $label —— 抓取已停（发了 $sent 张 / 预算剩 ${budget}KB / 上限 $MAX_ICONS 张）")
      if (!stopReported && (sent >= MAX_ICONS || budget <= 0)) {
        stopReported = true
        Log.d(TAG, "Mono: dump 收工（$sent 张 / 剩 ${budget}KB / 上限 $MAX_ICONS 张）" +
          "→ 普通图标不再抓，只有点名名单还能花保留的那 ${WATCH_RESERVE_KB}KB")
      }
      return
    }
    // 点名的那几张按「形状 **＋ 前景层尺寸**」去重，普通可疑图仍按纯形状。
    // 为什么分开处理：桌面会把同一张图标按两种尺寸再画一遍（`@432x432` 与 `@425x425`），
    // 而 8×8 指纹给这两张**同一个** `sig` —— 按纯形状去重就等于"第二张永远带不回来"，
    // 于是"设备与判据台对不上"这类问题根本没法判（见 [MonochromeSynth] 里 `variant` 那一段）。
    // 只对名单里的包放宽：多一种尺寸就多一张 PNG，全放开会把预算吃光。
    val key = if (watched) dedupeKey(pixels, w, h, label) else signature(pixels, w, h)
    if (!seen.add(key)) {
      // ⚠ **这一条 else 是补的，原来这里是一个静默 `return`。** 于是"这个图标一张都没带回来"和
      //   "它压根没被渲染过"在日志里长得一模一样 —— 本仓**第四次**撞上"提前 return 不留痕"
      //   （前三次：12 处 `if (hooked > 0)` 没有 else、装钩期解析失败不报名字、`dump 不抓` 那条）。
      //   2026-10-12 那一轮：translate 有 7 次渲染、`Mono: dump` 一行都没有、连"不抓"也没报 ——
      //   就是被这一行吞的（第一次带过之后，后面每次都被"已经见过"悄悄挡掉）。
      // 只给点名的报、而且每个去重键只报一次：这条通道和全设备的 logd 抢地方。
      if (watched && dupSeen.add(key))
        Log.d(TAG, "Mono: dump 跳过（同一种尺寸已带过）$label")
      return
    }
    sent++
    if (watched) watchedSent++
    val n = sent
    // 复放要还原设备这一次的**输入**，除了像素还得知道两位：`层底`（plateIsLayer）与
    // `层`（这一张到底有没有背景层）。少了后者，"没带 under"就分不清是**它本来没有**还是
    // **被预算拦下了** —— 而这两种在日志里长得一模一样，正是本仓反复撞的那类静默。
    // 它们打在 BEGIN 的注记里而不是新加一列判据数字：判据行是十一道闸的读数口径，
    // 动一列就得重钉一片基线。
    val note = "$why 层底=${if (plateIsLayer) 1 else 0} 层=${if (under != null) 1 else 0}"
    runCatching {
      val png = pngOf(src)
      // 一张照片底图标能压到 200KB+，一条通道被它占满就没有别人的位置了：按 KB 记预算，
      // 超了就停（宁可少带几个，也不要带回来的全是截断的半张）。
      budget -= png.size / 1024
      emit(n, id, "src", note, png)
      // **背景层那一张（`under`）**：只给点名的、只给真的有背景层的（`flatten` 那一路才有），
      // 彩色原样发 —— 这一张的**颜色**就是要看的东西（它就是那张盘）。
      // ⚠ 排在 `out` 前面是有意为之：两个抢同一块预算时保**输入**（少了它复放就是错的），
      //   不保输出（有 src + under + 层底，判据台自己算得出掩码，而设备那一张另有 `反相/分档` 两列对）。
      //   实测两个都便宜到不必取舍：under 6KB、out 16KB。
      if (watched && under != null && under.size >= w * h && budget > WATCH_RESERVE_KB / 2) {
        val ub = Bitmap.createBitmap(under, w, h, Bitmap.Config.ARGB_8888)
        val up = pngOf(ub)
        ub.recycle()
        budget -= up.size / 1024
        emit(n, id, "under", note, up)
      }
      // ⚠ **原来这里就停了：只发输入，不发交出去的那一张。** 于是"设备上到底画成什么样"
      //   从日志里拿不回来，只能亮屏截屏 —— 而桌面在休眠时就什么都不知道（2026-10-11 主人报
      //   translate「半边黑」，我修完只能看到 `框内 0.47→0.06` 这一格数，看不到那张墨）。
      //   掩码是**纯 alpha**（解码侧只取 alpha：PNG 的 RGBA 是直通 alpha，Android 转预乘再转回来时
      //   RGB 会有舍入误差而 alpha 不会 —— 而这一张只有 alpha 有意义），压出来比彩色那张小。
      // ⚠ **只给点名的那张发。** 第一版对所有图都发，每张多花一半预算，1200KB 提前见底 ——
      //   到 17:51 连 translate / 京东 / 华为 / 微博 这些**点名名单自己的输入都抓不回来了**。
      //   要看"设备交出什么"的只有点名那几张；可疑但不是点名的，输入才是诊断要的东西。
      val mask = ramp
      if (watched && mask != null && mask.size >= w * h && budget > WATCH_RESERVE_KB / 2) {
        val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mp = IntArray(mask.size) { i -> (mask[i] and -0x1000000) or 0x00FFFFFF }
        mb.setPixels(mp, 0, w, 0, 0, w, h)
        val op = pngOf(mb)
        mb.recycle()
        budget -= op.size / 1024
        emit(n, id, "out", note, op)
      }
    }.onFailure {
      Log.w(TAG, "Mono: dump#$n failed: ${it.javaClass.simpleName}: ${it.message}")
    }
  }

  /**
   * PNG 是无损的，优先按原尺寸发 —— 重放要复现的就是模型实际看到的那张。
   *
   * ⚠ 但整张插画（王者荣耀那种 192 位图放大到 384）无损压完有 200KB+，base64 分片上百行，
   *   logd 会直接丢片：实测 14 张里 3 张就是这么废的（`只收到 60/102 片`）。所以超过
   *   [MAX_PNG_BYTES] 就先降到 192 再压 —— 层次与形状照样看得出，尺寸不变性另有
   *   `SelfTest` 第 8 条钉着，不靠这里。
   */
  private fun pngOf(bmp: Bitmap): ByteArray {
    var out = compress(bmp)
    if (out.size > MAX_PNG_BYTES && bmp.width > SMALL_EDGE) {
      val small = Bitmap.createScaledBitmap(bmp, SMALL_EDGE, SMALL_EDGE, true)
      out = compress(small)
      if (small !== bmp) small.recycle()
    }
    return out
  }

  private fun compress(bmp: Bitmap): ByteArray =
    ByteArrayOutputStream(64 * 1024).use {
      bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
      it.toByteArray()
    }

  /** 超过这个大小就降采样发。 */
  private const val MAX_PNG_BYTES = 90 * 1024
  private const val SMALL_EDGE = 192

  // 命中路径的 `dumpCached`（2026-10-23 为 Pinduoduo 调查加的）已退役：
  //   命中探针实测它一条要 PNG 编码两张 384² + base64 + 十几行日志，~17ms/次；
  //   而它只服务于"已经缓存的图标怎么复现"—— 调查结束后，冷路径的 [maybe] 仍带同样的三件，
  //   且只在每次内容变化后跑一次。见 devlog 2026-10-23「命中 dump 退场」。
  private fun emit(n: Int, id: Int, what: String, why: String, bytes: ByteArray) {
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val parts = (b64.length + CHUNK - 1) / CHUNK
    // 三行都带 `p=`（这一份是谁发的，见 [PROC]）：重组按 `(p, dump#N, what)` 分组，
    // 少一处就意味着那一种行丢了这一组拼不回来 —— 而 BEGIN 恰恰是三行里最容易被漏读的
    //（它带着文件名，读不到就整组蒸发，上一轮就是这么把账算到 logd 头上的）。
    Log.d(TAG, "Mono: dump#$n p=$PROC BEGIN id=$id what=$what parts=$parts $why")
    for (p in 0 until parts) {
      val from = p * CHUNK
      val to = minOf(b64.length, from + CHUNK)
      // 分片行里**必须带上 what**：一个图标要发 src / out / under 三份，共用同一个 dump#N。
      // 上一版没带，重放时两份的分片混进同一个桶、片数对不上，14 个图标全部被当成截断丢掉。
      Log.d(TAG, "Mono: dump#$n p=$PROC $what P" + String.format(Locale.ROOT, "%03d", p) + "/" +
        String.format(Locale.ROOT, "%03d", parts - 1) + " " + b64.substring(from, to))
    }
    Log.d(TAG, "Mono: dump#$n p=$PROC END id=$id what=$what bytes=" + bytes.size)
  }

  /** 掩码的 ASCII 预览（`#` 实心 / `+` 中浓 / `·` 淡）—— 不带 PNG 时也能在日志里看个形状。 */
  fun ascii(ramp: IntArray, w: Int, h: Int) {
    val cols = 40
    for (ry in 0 until cols) {
      val line = StringBuilder(cols)
      val y0 = ry * h / cols
      for (rx in 0 until cols) {
        val x0 = rx * w / cols
        val a = Color.alpha(ramp[y0 * w + x0])
        line.append(when {
          a >= 250 -> '#'
          a >= 128 -> '+'
          a > 0 -> '·'
          else -> '.'
        })
      }
      Log.d(TAG, "Mono: ascii $ry|$line")
    }
  }
}
