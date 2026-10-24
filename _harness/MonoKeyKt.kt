package harness

import com.iamcanincan.opticon.circle.MonoKey
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 第十五闸：掩码缓存的**内容键**与**文件格式**（[MonoKey]，装机那侧是 [MonoCache]）。
 *
 * 为什么这一条必须有闸：缓存的两种错法都是**看得见但量不到**的 ——
 *  · 键撞了 = 交出**别的图标**的掩码。装机日志上它长得像"这台桌面图标就是这样"，
 *    判据台 14 闸一张都不会红（判据根本没跑）。
 *  · 文件坏 = 交出一张破的。`缓存=` 那行只会说"命中了"。
 * 这两件事真机上没有出口，所以在这里钉：把「命中」拆成「键对不对」+「格式对不对」两条可判的。
 *
 * ⚠ 自检三道，缺一条就非零退出（本仓吃过"永远不响的尺"的亏）：
 *   ① 语料少于 10 张 → 分母不对，别拿空表当结论；
 *   ② **负例必须真的响**：格式那一段特意喂 6 种坏输入，任何一种"解开了"就是这把尺自己在撒谎；
 *   ③ 成本那一段要真量到 build 的数（0 ms 说明被测代码被 JIT 抬走了）。
 *
 * 用法：`harness.MonoKeyKtKt <语料目录> [--edge=384] [--cost=8]`
 */
private var bad = 0
private var checks = 0
private var sink = 0L

/**
 * 在**另一条线程**上跑一次合成。缓存的前提是"同一批输入永远算出同一张"，而
 * [MonochromeMask] 用 ThreadLocal 的复用暂存 —— 所以"换条线程"是一笔真实的账，
 * 不是假想：真机上这条加载线程固定，别的桌面（或我们以后把锁拆了）就不是了。
 */
private fun runOnOtherThread(px: IntArray, edge: Int): IntArray? {
  var out: IntArray? = null
  val t = Thread { out = MonochromeMask.build(px, edge, edge, true).mask }
  t.start(); t.join()
  return out
}

private fun check(name: String, ok: Boolean, detail: String = "") {
  checks++
  if (!ok) {
    bad++
    println("!! $name 红 $detail")
  }
}

fun main(args: Array<String>) {
  val dir = File(args.getOrNull(0) ?: "")
  if (!dir.isDirectory) { println("!! 语料目录不存在：$dir"); kotlin.system.exitProcess(1) }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val costN = args.firstOrNull { it.startsWith("--cost=") }?.substringAfter("=")?.toInt() ?: 8
  val detN = args.firstOrNull { it.startsWith("--det=") }?.substringAfter("=")?.toInt() ?: 12

  val files = dir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  if (files.size < 10) {
    println("!! 语料只有 ${files.size} 张（<10）—— 分母不对，这一闸不算跑过")
    kotlin.system.exitProcess(1)
  }

  fun load(f: File): IntArray? {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    return px
  }
  val imgs = files.mapNotNull { load(it) }
  if (imgs.size < 10) { println("!! 解不开（${imgs.size}/${files.size}）"); kotlin.system.exitProcess(1) }
  println("KEYKt 语料 ${imgs.size} 张 @edge=$edge")

  // ── ① 确定性：同一份输入两次必须同一个键（不然"命中"这件事没有定义）──
  imgs.forEachIndexed { i, px ->
    val a = MonoKey.keyOf(px, null, edge, true, false, true)
    val b = MonoKey.keyOf(px, null, edge, true, false, true)
    check("确定性#$i", a == b)
  }

  // ── ② 单像素敏感：改任意一个像素，键必须变 ──
  // 挑的位置覆盖四个角、四条边的中点、画布中心、最后一个格子 —— 边界那几处最容易漏。
  val probe = intArrayOf(0, 1, edge - 1, edge / 2, edge * edge / 2, edge * edge - 1,
    edge * (edge / 2), edge * (edge / 2) + edge - 1, edge * (edge - 1), edge - 1)
  var probed = 0
  imgs.forEach { px ->
    val base = MonoKey.keyOf(px, null, edge, true, false, true)
    for (at in probe) {
      val old = px[at]
      val mutated = IntArray(px.size).also { px.copyInto(it) }
      // 三种改法：动 alpha、动一个通道的最低位、整个换掉 —— 最低位那种最容易"看不见"。
      for (delta in intArrayOf(0x00000001, 0x01000000, 0xFF000000.toInt())) {
        mutated[at] = old xor delta
        probed++
        check("单像素敏感 at=$at delta=$delta", MonoKey.keyOf(mutated, null, edge, true, false, true) != base)
      }
      mutated[at] = old
    }
  }
  println("   单像素敏感 探了 $probed 次")

  // ── ③ 六个输入每一位都要进键：少一位就是"换了设置还给旧答案"──
  run {
    val px = imgs[0]
    val under = imgs[1]
    val k = MonoKey.keyOf(px, under, edge, true, true, true)
    check("edge 进键", MonoKey.keyOf(px, under, edge, true, true, true) !=
      MonoKey.keyOf(px, under, edge / 2, true, true, true))
    check("adaptive 进键", MonoKey.keyOf(px, under, edge, false, true, true) != k)
    check("flatten 进键", MonoKey.keyOf(px, under, edge, true, false, true) != k)
    check("circle 进键", MonoKey.keyOf(px, under, edge, true, true, false) != k)
    // 快捷方式位（2026-10-25）：同一组像素以快捷方式身份进来时几何不同（细环那张不重排）
    check("shortcut 进键", MonoKey.keyOf(px, under, edge, true, true, true, true) != k)
    check("under 进键", MonoKey.keyOf(px, null, edge, true, true, true) != k)
    // 空数组与"根本没有这一层"必须分开：否则 `rasterizeLayer` 失败那张会撞上正常那张
    check("under 空!=无", MonoKey.keyOf(px, IntArray(0), edge, true, true, true) !=
      MonoKey.keyOf(px, null, edge, true, true, true))
    // 同一组像素挂在 px 还是挂在 under 上也要分开
    check("px/under 不串", MonoKey.keyOf(px, px, edge, true, true, true) !=
      MonoKey.keyOf(px, under, edge, true, true, true))
  }

  // ── ③b 纯色 `under` 的键（`keyOfFlat`）：同样六个输入一位不少、且与数组那一路分开 ──
  run {
    val px = imgs[0]
    val c = 0xfff40009.toInt()
    val k = MonoKey.keyOfFlat(px, c, edge, true, true, true)
    check("flat 确定性", k == MonoKey.keyOfFlat(px, c, edge, true, true, true))
    check("flat edge 进键", MonoKey.keyOfFlat(px, c, edge / 2, true, true, true) != k)
    check("flat adaptive 进键", MonoKey.keyOfFlat(px, c, edge, false, true, true) != k)
    check("flat flatten 进键", MonoKey.keyOfFlat(px, c, edge, true, false, true) != k)
    check("flat circle 进键", MonoKey.keyOfFlat(px, c, edge, true, true, false) != k)
    check("flat shortcut 进键", MonoKey.keyOfFlat(px, c, edge, true, true, true, true) != k)
    check("flat underColor 进键", MonoKey.keyOfFlat(px, c xor 0x00010000, edge, true, true, true) != k)
    // 一个像素都不能漏
    val mut = IntArray(px.size).also { px.copyInto(it) }
    mut[edge * (edge / 2) + edge / 2] = mut[edge * (edge / 2) + edge / 2] xor 1
    check("flat 单像素敏感", MonoKey.keyOfFlat(mut, c, edge, true, true, true) != k)
    // 与"把同色铺成整张数组"那一路必须分开：两条路的键空间不重叠
    check("flat != 数组", k != MonoKey.keyOf(px, IntArray(px.size) { c }, edge, true, true, true))
  }

  // ── ④ 语料两两不撞：这是 64 位哈希在这台机器上唯一能被量的性质 ──
  //   ⚠ 前提是"两张图内容不同"。语料里 `com.perol.pixez` 与 `com.perol.play.pixez` 的图标
  //   **逐字节相同**（同一份资源装成两个包名）—— 同一张图同键是**正确**行为，不是碰撞。
  //   2026-10-25 这条闸报过一次假红（还把两边的下标都印成当前那个，见下面的修复），
  //   所以先按内容去重，再断言"不同内容不撞"。
  run {
    val uniq = ArrayList<IntArray>()
    val buckets = HashMap<Int, MutableList<IntArray>>()
    var dup = 0
    for (px in imgs) {
      val bucket = buckets.getOrPut(px.contentHashCode()) { ArrayList() }
      if (bucket.any { it.contentEquals(px) }) { dup++; continue }
      bucket.add(px); uniq.add(px)
    }
    val keys = HashMap<Long, Int>()
    var collide = 0
    uniq.forEachIndexed { i, px ->
      val k = MonoKey.keyOf(px, null, edge, true, false, true)
      val prev = keys.put(k, i)
      if (prev != null) { collide++; println("   撞：#$prev 与 #$i 同键（内容不同）") }
    }
    check("语料互不撞（${uniq.size} 张不同内容，另有 $dup 张同内容重复）", collide == 0, "撞了 $collide 对")
    println("   互不撞：${imgs.size} 张 → ${uniq.size} 张不同内容 → ${keys.size} 个不同的键")
    // 纯色 under 那一路同样两两不撞（同一个颜色、同一档边长）
    val flat = HashMap<Long, Int>()
    var flatCollide = 0
    uniq.forEachIndexed { i, px ->
      val kk = MonoKey.keyOfFlat(px, 0xfff40009.toInt(), edge, true, false, true)
      if (flat.put(kk, i) != null) flatCollide++
    }
    check("flat 语料互不撞（${uniq.size} 张不同内容）", flatCollide == 0, "撞了 $flatCollide 对")
  }

  // ── ⑤ 文件格式：往返必须逐字节相同，六种坏输入六种都要拒 ──
  run {
    for (e in intArrayOf(96, 192, edge, 378, 423)) {
      val stride = (e + 3) / 4 * 4
      val body = ByteArray(stride * e) { ((it % 251) * 7 and 0xff).toByte() }
      val enc = MonoKey.encode(stride, e, body)
      if (enc.size != MonoKey.HEADER_BYTES + body.size) check("头部长度 @$e", false, "${enc.size}")
      val dec = MonoKey.decode(enc, e, stride)
      check("往返 @$e", dec != null && dec.contentEquals(body))
      // 负例：每种都必须解成 null
      check("坏-截断 @$e", MonoKey.decode(enc.copyOf(enc.size - 1), e, stride) == null)
      check("坏-多一字节 @$e", MonoKey.decode(enc.copyOf(enc.size + 1), e, stride) == null)
      check("坏-头不足 @$e", MonoKey.decode(enc.copyOf(4), e, stride) == null)
      check("坏-边长不符 @$e", MonoKey.decode(enc, e + 4, stride) == null)
      // ⚠ 这一条一开始写成 `decode(enc, e, e)`，在 96/192/384 三档上**根本没构造出坏的输入** ——
      //   那三档 (e+3)/4*4 == e，行步长与边长同一个数，解开来完全合法。尺子在撒谎比没尺子更糟：
      //   它把"这一条没在测"报成了"这一条通过了"。要坏就得给一个**不等于存进去的那个**的值。
      check("坏-行步长不符 @$e", MonoKey.decode(enc, e, stride + 4) == null)
      check("坏-行步长为 0 @$e", MonoKey.decode(enc, e, 0) == null)
      check("坏-头部自相矛盾 @$e", MonoKey.decode(MonoKey.encode(e - 1, e, ByteArray((e - 1) * e)), e, e - 1) == null)
    }
  }

  // ── ⑦ 确定性：**整层缓存的前提**，而它此前没有闸 ──
  //   缓存说的不是"这张图算对了"，是"**同样这六个输入，以后每次都会算出同一张**"。
  //   十四道旧闸量的是形状好坏 —— 形状好但两次不同，照样交出上一张的掩码（屏幕上就是别的时刻那张）。
  //   `MonochromeMask` 用 ThreadLocal 复用的暂存数组，所以两种串法都要试：
  //     a) 同一线程**夹着别的图**再跑一遍（查"上一张的残留没清干净"）；
  //     b) **换一条线程**跑同一张（查"这条线程的 scratch 起点不一样"）。
  //   真机上 1894 次请求都在一条加载线程，但 a) 与 b) 都不是免费的：桌面哪天换个线程，
  //   或者我们自己改了 scratch 的清理次序，这一条就会响。
  run {
    val det = imgs.indices.step(maxOf(1, imgs.size / detN)).take(detN)
    var diffSeq = 0; var diffThread = 0
    for (i in det) {
      val a = MonochromeMask.build(imgs[i], edge, edge, true).mask
      MonochromeMask.build(imgs[(i + 1) % imgs.size], edge, edge, true)   // 夹一张别的
      val b = MonochromeMask.build(imgs[i], edge, edge, true).mask
      if ((a == null) != (b == null) || (a != null && b != null && !a.contentEquals(b))) diffSeq++
      val c = runOnOtherThread(imgs[i], edge)
      if ((a == null) != (c == null) || (a != null && c != null && !a.contentEquals(c))) diffThread++
    }
    check("同线程夹一张再跑逐字节相同（${det.size} 张）", diffSeq == 0, "$diffSeq 张不同")
    check("换一条线程跑逐字节相同（${det.size} 张）", diffThread == 0, "$diffThread 张不同")
    println("   确定性：夹跑不同 $diffSeq 张 / 换线程不同 $diffThread 张（样本 ${det.size} 张）")
  }

  // ── ⑥ 成本：键必须比模型便宜两个数量级，否则这一层白加 ──
  run {
    val set = imgs.take(costN)
    // 预热，别让 JIT 把数抬走
    repeat(2) { set.forEach { MonoKey.keyOf(it, null, edge, true, false, true) } }
    val tk = ArrayList<Long>()
    repeat(5) {
      for (px in set) {
        val t0 = System.nanoTime()
        val h = MonoKey.keyOf(px, null, edge, true, false, true)
        tk.add(System.nanoTime() - t0)
        sink += h
      }
    }
    repeat(2) { for (px in set) MonochromeMask.build(px, edge, edge, true) }
    val tb = ArrayList<Long>()
    for (px in set) {
      val t0 = System.nanoTime()
      val o = MonochromeMask.build(px, edge, edge, true)
      tb.add(System.nanoTime() - t0)
      sink += (o.report.length.toLong() + if (o.mask == null) 0 else 1)
    }
    val mk = tk.sorted()[tk.size / 2]; val mb = tb.sorted()[tb.size / 2]
    println("   成本：键中位 ${mk / 1000}µs  模型中位 ${mb / 1_000_000}ms  比值 " +
      (if (mb > 0) "%.4f%%".format(mk * 100.0 / mb) else "量不到"))
    // ns 口径下 0 就是 0：量不到数=被 JIT 抬走了，不是"快得看不见"。
    check("两趟都量到了数", mb > 0L && mk > 0L, "键 ${mk}ns / 模型 ${mb}ns")
    // 这一条是这一层的**存在理由**：键贵过一个数量级，缓存就是负收益（每次 bind 都付）。
    check("键比模型便宜 1000 倍", mb == 0L || mk * 1000 < mb, "键 ${mk}ns / 模型 ${mb}ns")
  }

  println(if (bad == 0) "KEYKt OK：$checks 条全绿" else "KEYKt 红：$bad/$checks 条违规")
  if (bad != 0) kotlin.system.exitProcess(1)
}
