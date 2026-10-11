package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Color
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

  /** 每进程最多带回来几张。PNG 压完一张十几 KB、六到十八行日志，所以这个数可以比想象中大。 */
  private const val MAX_ICONS = 48

  /** 一条日志的安全长度：logd 单条约 4000 字节，留足余量免得截断。 */
  private const val CHUNK = 3000

  private var sent = 0

  /** 一次进程允许发多少 KB 的 PNG。logcat 是共享通道，超了就停，别把别的日志挤掉。 */
  private var budget = BUDGET_KB

  /** 停手这件事要报，而且**只报一次**（不然每被拦一张一行，把日志本身挤掉了）。 */
  private var stopReported = false

  /** 点名的图标被拦下时每个标签报一次：这一条就是"为什么它一张都没回来"的答案。 */
  private val dropped = HashSet<String>()

  /**
   * 只留给**点名名单**的那一块预算（KB）。
   *
   * 为什么要有这一条：普通可疑图是按渲染先后顺序抢预算的。真机实测（2026-09-29 20:09 那一轮）
   * 桌面一起来，前 30 张在 **4 秒**里花掉 1249 KB → 预算见底，而 `com.cnspeedtest.globalspeed`
   * 第一次出现是在 `20:09:51`，**晚了 0.7 秒** → 它被看到了 3 次、一张没带回来，而当时那条
   * `if (budget <= 0) return` 是**静默**的，于是"点名要抓的应用没被抓"和"这个应用没被渲染过"
   * 在日志里长得一模一样。这是本仓第三次撞上"静默失效"（前两次：12 处 `if (hooked > 0)` 没有
   * else、装钩期解析失败不报名字），所以这里两件事一起做：**拦住要报名字** + **给名单留一块**。
   * 保留额只影响名单之外的图：它们最多花到剩 [WATCH_RESERVE_KB]，够再收七八张点名的。
   */
  private const val WATCH_RESERVE_KB = 300

  /** 一次进程的总预算。改这个数之前先想清楚：这条通道抢的是全设备共享的 logd。 */
  private const val BUDGET_KB = 1200

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

  /**
   * 带一张。**只带 src，不带 mask** —— 掩码是模型从 src 算出来的，重放那边跑同一份代码就能
   * 自己得到，双份发只是把日志量翻倍（而日志量正是这条通道的瓶颈：上一版 14 个图标里有 2 个
   * 的 src 因为片数太多被 logd 丢掉）。带上 mask 的唯一价值是"核对重放是否等价"，
   * 那个用日志里那行判据数字对就够了。
   *
   * 全程 runCatching：这是诊断，诊断把自己跑挂的图标弄坏是不可接受的（这里跑在桌面加载图标的
   * 路上，抛出去就是那个图标没了）。失败只打一行原因。
   */
  @Synchronized
  fun maybe(
    id: Int, label: String, src: Bitmap, pixels: IntArray, ramp: IntArray?, w: Int, h: Int,
    why: String, watched: Boolean,
  ) {
    // 普通可疑图花到剩 [WATCH_RESERVE_KB] 就停；点名的那张可以花到底。
    if (sent >= MAX_ICONS || budget - (if (watched) 0 else WATCH_RESERVE_KB) <= 0) {
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
    if (!seen.add(signature(pixels, w, h))) return
    sent++
    val n = sent
    runCatching {
      val png = pngOf(src)
      // 一张照片底图标能压到 200KB+，一条通道被它占满就没有别人的位置了：按 KB 记预算，
      // 超了就停（宁可少带几个，也不要带回来的全是截断的半张）。
      budget -= png.size / 1024
      emit(n, id, "src", why, png)
      // ⚠ **原来这里就停了：只发输入，不发交出去的那一张。** 于是"设备上到底画成什么样"
      //   从日志里拿不回来，只能亮屏截屏 —— 而桌面在休眠时就什么都不知道（2026-10-11 主人报
      //   translate「半边黑」，我修完只能看到 `框内 0.47→0.06` 这一格数，看不到那张墨）。
      //   掩码是**纯 alpha**，压出来比彩色那张小（实测一轮：src 平均 42KB、out 平均 24KB）。
      //   解码侧只取 alpha 通道：PNG 的 RGBA 是直通 alpha，Android 内部转预乘再转回来时
      //   RGB 会有舍入误差，**alpha 不会** —— 而这一张只有 alpha 有意义。
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
        emit(n, id, "out", why, op)
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

  private fun emit(n: Int, id: Int, what: String, why: String, bytes: ByteArray) {
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val parts = (b64.length + CHUNK - 1) / CHUNK
    Log.d(TAG, "Mono: dump#$n BEGIN id=$id what=$what parts=$parts ${if (why.isEmpty()) "" else why}")
    for (p in 0 until parts) {
      val from = p * CHUNK
      val to = minOf(b64.length, from + CHUNK)
      // 分片行里**必须带上 what**：一个图标要发 src 与 mask 两份，两边共用同一个 dump#N。
      // 上一版没带，重放时两份的分片混进同一个桶、片数对不上，14 个图标全部被当成截断丢掉。
      Log.d(TAG, "Mono: dump#$n $what P" + String.format(Locale.ROOT, "%03d", p) + "/" +
        String.format(Locale.ROOT, "%03d", parts - 1) + " " + b64.substring(from, to))
    }
    Log.d(TAG, "Mono: dump#$n END id=$id what=$what bytes=" + bytes.size)
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
