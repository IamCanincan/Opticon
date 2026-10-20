package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.util.Log
import com.iamcanincan.opticon.runtime.TAG
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 压好的单色掩码按**内容**再认一次 —— 桌面重启时图标「加载好久」的那笔账。
 *
 * ## 为什么要新加一层
 * [MonochromeSynth] 原来只按 Drawable **实例**缓存。真机账（`yi_press.txt`，pid 7614，
 * 2026-10-15 03:38 装机那次重启）：一次会话 **1594 次合成 / 209 种内容**，同一张图标最多
 * 被重压 **148 次**（高德 148、美团 142、抖音 138），而这些重复行的
 * `edge + sig + fg 类 + intrinsic + adaptive` **逐字段相同** —— 是同一张图，不是巧合：
 * 桌面每次 bind 都 `getConstantState().newDrawable()` 交出一个**新实例**，于是实例缓存
 * 结构上命中不了（原类文档那句「图标对象由桌面的 IconCache 长期持有」不成立）。
 * 合成串行在一把锁上（[MonochromeSynth.render] 是 `@Synchronized`），所以那 1594 次排队
 * 铺满 **760 秒**；只算一遍是 83 秒。
 *
 * ## 两层
 * · **内存**：LRU，按字节封顶（见 [MAX_MEM_BYTES]）。命中就免掉整条模型。
 * · **磁盘**：宿主的 `cacheDir/opticon-mono/<装机身份>/`。命中免掉模型、只付一次文件读；
 *   它才是「每次**重启**都要加载好久」的解药 —— 内存这一层活不过进程。
 *   跨重启的账量过：相邻两次重启的内容集合 **210/211 相同**，所以磁盘几乎全是命中。
 *
 * ## 键与文件格式在 [MonoKey]
 * 那两件是"给错就画错图标"的部分，剥成纯函数在判据台上逐条钉过（`SelfTest`），不在这里重复。
 * ⚠ 目录名带**装机身份**（[renderId]，认 `lastUpdateTime` 不认 versionName）：算法每装一次就变，
 * 版本号只在正式发版时动 —— 与桌面图标缓存戳记同一条理由（见 IconHooks 里 `renderId` 的注释）。
 * 这条要是写错，接下来几轮迭代会看到**上一版画的那张**。拿不到装机身份时整层磁盘关掉：
 * 宁可不快，不可给旧答案。
 *
 * ## 负缓存
 * 「判据不成立、放弃合成」那张也记（只记内存）：它同样被重压 148 次，一次模型都省不掉的话
 * 就是纯浪费。降级出口与今天**完全一致**（返回 null，桌面走它自己的 Factory）。
 */
internal object MonoCache {

  /**
   * 内存这一层的字节上限。8 MB ≈ 55 张 384²（ALPHA_8 一张 147 KB）。
   *
   * ⚠ **不追求装下整个抽屉**：209 种内容是 30 MB，常驻在桌面进程里不划算。内存 miss 落到磁盘
   * （几毫秒），不落到模型（三百多毫秒）—— 所以这一层被扫穿是无害的，也正因为如此可以用最便宜的
   * 「弹出最旧那一张」，不用算加权。
   */
  private const val MAX_MEM_BYTES = 8L * 1024 * 1024

  /**
   * 内存层的**条数**上限。4096 条，对 209 种内容的抽屉留了二十倍余量。
   *
   * ⚠ 为什么按字节封了还要按条数封：**负缓存条目一个字节都不占**（`mask == null`，bytes=0），
   * 所以 [MAX_MEM_BYTES] 那道闸对它们**完全无效** —— 淘汰循环 `while (memBytes > cap)` 永远不触发，
   * 一张永远压不出来的图标每被问一次就多一个键，桌面在后台批量预载整个抽屉的那条路径上能一直涨。
   * 每个条目本身几十字节 + 一个键，看着不多，但"没有上限"这三个字在常驻进程里就是问题。
   */
  private const val MAX_MEM_ENTRIES = 4096

  /**
   * 磁盘那层的字节上限。48 MB ≈ 340 张 384²：一个抽屉 209 种 = 30 MB，留三成余量给
   * 多尺寸 / 新增应用。超了按**最旧**删（`lastModified`）—— 这里没有访问计数，而"这轮没再被
   * 问到的那张"正是最该扔的。落在宿主 `cacheDir` 里，系统存储紧张会自己清：它是缓存，扔掉只慢不坏。
   * ⚠ **界面上那条「剪影缓存在哪里」写着 48 MB**（`note_mono_cache`）—— 改这个数要同步改文案，
   *   那是给用户看的，不能飘。
   */
  private const val MAX_DISK_BYTES = 48L * 1024 * 1024

  /** 每写这么多次才摊一次目录清理（每次 bind 都 `listFiles` 付不起）。 */
  private const val SWEEP_EVERY = 64

  private const val DIR_NAME = "opticon-mono"

  private const val FROM_MEM = "内存"
  private const val FROM_DISK = "磁盘"

  /** 一条记录。[mask] 为 null = **负缓存**。[bytes] 只为内存层的记账，位图被弹出去要还多少。 */
  data class Entry(val mask: Bitmap?, val bytes: Long, val from: String)

  /** accessOrder = true → 迭代顺序就是访问顺序，头部即最久没被碰的那一张。 */
  private val mem = LinkedHashMap<Long, Entry>(64, 0.75f, true)

  private var memBytes = 0L

  /** 磁盘那一层的目录；解不出来就一直为 null（= 这一层关掉）。 */
  private var dir: File? = null

  private var dirTries = 0

  private val hits = AtomicLong()
  private val misses = AtomicLong()
  private val diskHits = AtomicLong()
  private var writesSinceSweep = 0

  /** 写失败的警告只放行前 3 条（清过 cacheDir 之后每个图标都会失败一次，那不该刷屏）。 */
  private var writeWarned = 0

  /** 归属索引文件名（见 [noteOwner]）。与掩码同目录，但 [sweep] 既不删它也不计进预算。 */
  private const val OWNER_INDEX = "index.tsv"

  /** 本进程已经记过归属的 (键, 边长)：一次会话里同一张会被 bind 上百次，只记第一遍。 */
  private val seenOwner = HashSet<String>()

  /** 内容键：见 [MonoKey.keyOf]，那六样就是掩码的全部输入。 */
  fun keyOf(
    px: IntArray, under: IntArray?, edge: Int, adaptive: Boolean, flatten: Boolean, circle: Boolean,
  ): Long = MonoKey.keyOf(px, under, edge, adaptive, flatten, circle)

  /**
   * 查这一张。命中返回 [Entry]（`mask == null` 是负缓存，调用方照旧返回 null），没命中返回 null。
   *
   * 顺序：内存 → 磁盘。磁盘命中把这张放回内存，于是同一次会话里后面那一两百次 bind 只走内存。
   */
  @Synchronized
  fun lookup(key: Long, edge: Int): Entry? {
    mem[key]?.let {
      hits.incrementAndGet()
      // ⚠ 汇总行**两层都要打**：原来只在磁盘那一支打，于是"全内存命中"的一趟重启（真机第二次就是这样：
      //   1880 命中里只有 480 次落盘）**一行都不出**，命中率这件事在日志里等于没记。
      note()
      return it.copy(from = FROM_MEM)
    }
    val loaded = readDisk(key, edge)
    if (loaded == null) {
      misses.incrementAndGet()
      return null
    }
    put(key, loaded, FROM_DISK)
    hits.incrementAndGet()
    diskHits.incrementAndGet()
    note()
    return Entry(loaded, loaded.byteCount.toLong(), FROM_DISK)
  }

  /**
   * 记下这一张。[mask] 为 null 时只进内存（磁盘只存真掩码，见类文档的负缓存那段）。
   *
   * [label] 只用来写归属索引（见 [OWNER_INDEX]），**不参与键、不参与判定** —— 所以默认空串，
   * 传不传都不会改变任何一张掩码的像素。
   */
  @Synchronized
  fun store(key: Long, edge: Int, mask: Bitmap?, label: String = "") {
    if (mask == null) {
      put(key, null, "")
      return
    }
    put(key, mask, "")
    writeDisk(key, edge, mask, label)
  }

  private fun put(key: Long, mask: Bitmap?, from: String) {
    mem.remove(key)?.let { memBytes -= it.bytes }
    val bytes = mask?.byteCount?.toLong() ?: 0L
    mem[key] = Entry(mask, bytes, from)
    memBytes += bytes
    // 弹出最旧的，直到回到两道上限之内（字节与条数 —— 后者管的是 bytes=0 的负缓存条目，见 [MAX_MEM_ENTRIES]）。
    while (mem.size > 1 && (memBytes > MAX_MEM_BYTES || mem.size > MAX_MEM_ENTRIES)) {
      val it = mem.entries.iterator()
      if (!it.hasNext()) break
      val e = it.next()
      it.remove()
      memBytes -= e.value.bytes
    }
  }

  /**
   * 目录：`<宿主的 cacheDir>/opticon-mono/<装机身份>/`。
   *
   * 用**宿主**的 cacheDir 是因为我们就跑在那个进程里，拿自己的 Context 写自己的目录；
   * 换到模块的 dataDir 就是跨 UID 写，那是权限拒绝。
   * ⚠ 也因此：按「清除桌面图标缓存」那一下**不会**清掉这里（它删的是桌面的 `app_icons.db`）。
   * 换装新构建靠目录名换掉一整层，见 [pruneOldBuilds]。
   *
   * ⚠ **"还没就绪"不算失败**（主人 2026-10-11 量过同一条坑：attach 阶段 `currentApplication()` 常常是 null）：
   * 头几次 bind 可能全落在那个窗口里，按次数计上限会把这一层**永久**关掉而只在日志里留三行。
   * 所以只有"拿到了 Context 却建不出目录 / 拿不到装机身份"这种**确定失败**才计数；
   * `app == null` 只是没就绪，下次再来。
   * ⚠ 反过来，写失败要**把目录忘掉**：系统的 `cacheDir` 会被清（存储紧张、用户清缓存、别的工具），
   * 抓住一个已消失的 `dir` 不放 = 这个进程余下每一次写都抛，而每次写都抛就是每次图标一行警告。
   */
  private fun dir(): File? {
    dir?.let { return it }
    if (dirTries >= 3) return null
    // ⚠ 顺序是**先 Context 后要身份**，反过来会把这一层永久关掉：`renderId()` 内部第一件事就是
    //   `currentApplication()`，而 attach 阶段那会儿它就是 null —— 于是"没就绪"会被当成"拿不到装机身份"
    //   计数，头三次 bind 一眨眼就把 3 次额度烧光，之后整个进程再试过不了。
    val app = runCatching { currentApplication() }.getOrNull() ?: return null   // 没就绪：不计数，下次再来
    val root = runCatching { app.cacheDir }.getOrNull()
    if (root == null) {
      dirTries++
      Log.w(TAG, "Mono: 掩码缓存拿不到宿主 cacheDir，磁盘那一层不开")
      return null
    }
    val id = runCatching { renderId() }.getOrNull()
    if (id == null) {
      dirTries++
      Log.w(TAG, "Mono: 掩码缓存拿不到装机身份，磁盘那一层暂不开（只会慢，不会给旧答案）")
      return null
    }
    // ⚠ 目录名里带上 **SDK_INT**：装机身份只认我们自己的安装包，而**系统 OTA 不动我们**
    //   （`lastUpdateTime` 是"我们这个包"的）。像素变了键自然变，但 ALPHA_8 的 `rowBytes`、
    //   ColorSpace 语义这类"像素没变、交付口径变了"的东西只有系统侧会动 —— 那一条键盖不住。
    //   多一个目录名后缀的钱，比"OTA 之后继续发上一套框架下压出来的掩码"便宜。
    val d = File(File(root, DIR_NAME), "${id}_api${android.os.Build.VERSION.SDK_INT}".replace(Regex("[^A-Za-z0-9._-]"), "_"))
    if (!runCatching { d.mkdirs(); d.isDirectory }.getOrDefault(false)) {
      dirTries++
      Log.w(TAG, "Mono: 掩码缓存目录建不出来（$d），磁盘那一层不开")
      return null
    }
    pruneOldBuilds(d.parentFile, d)
    return d.also { dir = it }
  }

  /** 换装新构建 → 上一版那一整目录都不该再被看见。删不掉也只是占着磁盘，不影响正确性。 */
  private fun pruneOldBuilds(root: File?, keep: File) {
    val kids = runCatching { root?.listFiles() }.getOrNull() ?: return
    for (k in kids) if (k.isDirectory && k.absolutePath != keep.absolutePath) {
      runCatching { k.deleteRecursively() }
    }
  }

  /** 边长进文件名：同一张内容在 192 与 384 两档是两张掩码，各自存。 */
  private fun name(key: Long, edge: Int) = "${MonoKey.hex(key)}_$edge.a8"

  private fun file(key: Long, edge: Int): File? = dir()?.let { File(it, name(key, edge)) }

  /** 解不开 / 对不上就删掉当没命中，绝不让坏文件上一个屏（判据全在 [MonoKey.decode]）。 */
  private fun readDisk(key: Long, edge: Int): Bitmap? {
    val f = file(key, edge) ?: return null
    if (!f.exists()) return null
    val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
    val mask = runCatching {
      val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ALPHA_8)
      val body = MonoKey.decode(bytes, edge, bmp.rowBytes) ?: error("格式核对不过")
      bmp.copyPixelsFromBuffer(ByteBuffer.wrap(body))
      bmp
    }.getOrNull()
    if (mask == null) {
      Log.w(TAG, "Mono: 掩码缓存解不开（${f.name}），删掉当没命中")
      runCatching { f.delete() }
    }
    return mask
  }

  private fun writeDisk(key: Long, edge: Int, mask: Bitmap, label: String) {
    val d = dir() ?: return
    val to = File(d, name(key, edge))
    if (to.exists()) return
    val bytes = runCatching {
      val body = ByteArray(mask.rowBytes * mask.height)
      mask.copyPixelsToBuffer(ByteBuffer.wrap(body))
      MonoKey.encode(mask.rowBytes, edge, body)
    }.getOrNull() ?: return
    // 先写临时名再 rename：桌面进程随时会被杀，半个文件比没有文件糟得多。
    // 同目录 rename 是原子的，于是 [readDisk] 要么看见完整的、要么根本看不见。
    val tmp = File(d, to.name + ".tmp")
    val ok = runCatching { tmp.writeBytes(bytes); tmp.renameTo(to) }.getOrDefault(false)
    if (ok) noteOwner(d, key, edge, label) else {
      runCatching { tmp.delete() }
      // ⚠ 忘掉这个目录，下一张重新解析：`cacheDir` 会被系统或用户清掉，而抓住一个已经不存在的
      //   路径不放 = 这个进程余下每一次写都抛 → **每个图标一行警告**。清缓存不是故障，是常态。
      dir = null
      if (writeWarned++ < 3) Log.w(TAG, "Mono: 掩码缓存写不进去（${to.name}），重找目录；再失败就只关这一层")
    }
    if (++writesSinceSweep >= SWEEP_EVERY) {
      writesSinceSweep = 0
      sweep(d)
    }
  }

  /**
   * 归属索引：`<键>\t<边长>\t<标签>` 一行一行追加到同目录的 [OWNER_INDEX]。
   *
   * ## 为什么要有它
   * `键=` 是**纯内容哈希**（[MonoKey.keyOf] 那六样里没有身份），所以一把键可以属于多个组件；
   * 而桌面库 `app_icons.db` **没有资源名列** ⇒ 想回答"缓存里这张是谁的"，
   * 以前只有一个来源：清图标缓存那一次当场抓到的 logcat（`_delivery.cjs`）。
   * 那个窗口会滚 —— 今天量过一次：1,449 行 `Mono:` 里 937 行的标签是资源名形式，配不上组件。
   * ⇒ 让缓存自己带上归属，**离线随时能查**，不用再花主人一次手势。
   *
   * ## 三条约束
   * · **不参与判定**：只写不读，任何一张掩码的像素与它无关；`label` 为空就整行不写。
   * · **每进程每键一次**：`seenOwner` 挡住重复（一次会话里同一张会被 bind 上百次）。
   * · **失败静默**：这是诊断数据，不值得为它冒任何一次异常或日志刷屏；写不进就什么也不做。
   *   跨进程 append 不锁（桌面可能多个 profile 同时在写），最坏是两行交错 —— 读的一方
   *   （`_harness/_owner.cjs`）按"切出来正好三段"过滤，混行的那一截直接丢。
   */
  private fun noteOwner(d: File, key: Long, edge: Int, label: String) {
    if (label.isEmpty() || label == "?") return
    if (!seenOwner.add("$key:$edge")) return
    runCatching { File(d, OWNER_INDEX).appendText("${MonoKey.hex(key)}\t$edge\t$label\n") }
  }

  /** 按最旧删到上限以下。删失败（别的进程正拿着）就当没发生，下一轮再试。 */
  private fun sweep(d: File) {
    val kids = runCatching { d.listFiles() }.getOrNull() ?: return
    var total = 0L
    val files = ArrayList<File>()
    for (k in kids) {
      if (k.name.endsWith(".tmp")) { runCatching { k.delete() }; continue }
      if (!k.isFile) continue
      // ⚠ 归属索引**不删也不计进体积**：它不是掩码，被按最旧删掉就等于把"这张是谁的"又扔回
      //   那个会滚的 logcat 窗口；而它一行才几十字节，计进预算只会挤掉真掩码。
      if (k.name == OWNER_INDEX) continue
      total += k.length()
      files.add(k)
    }
    if (total <= MAX_DISK_BYTES) return
    files.sortBy { it.lastModified() }
    for (f in files) {
      if (total <= MAX_DISK_BYTES) break
      val n = f.length()
      if (runCatching { f.delete() }.getOrDefault(false)) total -= n
    }
    Log.d(TAG, "Mono: 掩码磁盘缓存超上限，按最旧删到 ${total / 1024} KB")
  }

  /** 汇总行：命中率是这一层唯一要看的数（逐张的留痕在 [MonochromeSynth.noteCached]）。 */
  private fun note() {
    val h = hits.get()
    if (h % 40 == 0L) {
      Log.d(TAG, "Mono: 掩码缓存 命中=$h 未命中=${misses.get()} 其中磁盘=$diskHits")
    }
  }
}
