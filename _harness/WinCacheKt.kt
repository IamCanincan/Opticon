package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import kotlin.system.exitProcess

/**
 * 窗口缓存的**反证台**（2026-10-17，随缓存一起加）。
 *
 * 缓存这类东西的失败模式是**静默的**：键撞了或串了，屏幕上就是"某个图标裁错一圈"，
 * 而判据一个字都不会报错 —— 与本仓"按实例缓存 0 命中"那次同一族。所以这里不测"快不快"，
 * 只测四条**必须成立**的：
 *   ① 同一张量两次：第二次必须走缓存，且结果与第一次**逐字段相同**；
 *   ② 改一格像素：必须**不算命中**（否则"应用更新了还给旧窗口"）；
 *   ③ 两张不同的图交替量：各自拿回**自己的**窗口，一次都不能串；
 *   ④ 满幅（判据答 `null`）也要能负缓存：第二次仍 `null`、且走的是缓存。
 * 外加一条 LRU 上限（塞爆之后不许无界长）。
 *
 * 用法：`harness.WinCacheKtKt`（不需要语料，图是现造的）
 */
private var bad = 0
private var checks = 0

private fun ok(name: String, cond: Boolean, detail: String = "") {
  checks++
  if (!cond) { bad++; println("!! $name 红 $detail") } else println("   ✓ $name $detail")
}

/** 造一张"圆盘 + 尖角"的假图标：半径 r 的实心盘，外加上角上一块 —— 换 r 就是换形状。 */
private fun disc(edge: Int, r: Int, corner: Boolean = false): IntArray {
  val a = IntArray(edge * edge)
  val c = (edge - 1) / 2f
  for (y in 0 until edge) for (x in 0 until edge) {
    val d = kotlin.math.hypot((x - c).toDouble(), (y - c).toDouble())
    if (d <= r) a[y * edge + x] = 0xFF000000.toInt() or (x * 7 + y * 13 and 0xffffff)
    else if (corner && x >= edge - r / 3 && y <= r / 4) a[y * edge + x] = 0xFF303030.toInt()
  }
  return a
}

private fun same(a: IconCropWindow.Window?, b: IconCropWindow.Window?): Boolean =
  if (a == null || b == null) a == b else
    a.l == b.l && a.t == b.t && a.r == b.r && a.b == b.b && a.zoom == b.zoom &&
      a.plate == b.plate && a.keepPct == b.keepPct && a.spiky == b.spiky

fun main(args: Array<String>) {
  val e = 128
  val a = disc(e, 44, corner = true)
  val b = disc(e, 61)
  val full = IntArray(e * e) { 0xFF102030.toInt() }   // 整张不透明 = 满幅，判据答 null

  fun hit0(): Long = IconCropWindow.winHits

  println("== 窗口缓存反证 ==")
  // ① 同一张两次：必须命中且逐字段相同
  IconCropWindow.clearWindowCache()
  val a1 = IconCropWindow.of(a, e, e)
  val h0 = hit0()
  val a2 = IconCropWindow.of(a, e, e)
  ok("① 第二次走缓存", IconCropWindow.winHits > h0, "命中 ${h0}→${IconCropWindow.winHits}")
  ok("① 命中结果与现算逐字段相同", same(a1, a2), "窗口=${a1?.let { "%.3f/%.3f %dx".format(it.l, it.r, it.keepPct) } ?: "null"}")

  // ② 改一格像素：必须不算命中
  val a3 = a.clone().also { it[7] = 0xFF00FF00.toInt() }
  val before = IconCropWindow.winHits
  val a3w = IconCropWindow.of(a3, e, e)
  ok("② 改一格不命中旧键", IconCropWindow.winHits == before, "命中数没涨才对（涨=把旧窗口给了新图）")
  ok("② 改一格确实算出新窗口", a3w != null, "窗口=${a3w?.keepPct}%")

  // ③ 两张交替：各拿各的，一次都不能串
  val b1 = IconCropWindow.of(b, e, e)
  val aA = IconCropWindow.of(a, e, e)
  val bB = IconCropWindow.of(b, e, e)
  ok("③ 交替不串（A 始终拿 A 的）", same(a1, aA), "A=${aA?.keepPct}% B=${bB?.keepPct}%")
  ok("③ 交替不串（B 始终拿 B 的）", same(b1, bB))
  ok("③ 两张窗口确实不同（这台子才有牙）", !same(a1, b1), "A ${a1?.keepPct}% vs B ${b1?.keepPct}%")

  // ④ 满幅负缓存
  val f0 = IconCropWindow.of(full, e, e)
  val hb = IconCropWindow.winHits
  val f1 = IconCropWindow.of(full, e, e)
  ok("④ 满幅答 null", f0 == null && f1 == null)
  ok("④ null 也走缓存（负缓存生效）", IconCropWindow.winHits > hb, "命中 ${hb}→${IconCropWindow.winHits}")

  // ⑤ 400 张**保证互不相同**的图（第 i 格打一个独一无二的值）：
  //    新算次数必须**恰好等于 400** —— 少一次就是两张不同输入撞了同一个键（那会把别的图标的窗口交出去）。
  //    同时验 LRU 真有上限：条数不许超过缓存容量。
  val m0 = IconCropWindow.winMisses
  val distinct = ArrayList<IntArray>(400)
  for (i in 0 until 400) {
    val px = disc(e, 20 + i % 100, corner = i % 2 == 0)
    px[i % (e * e)] = 0xFF000000.toInt() or (i + 1)     // 唯一指纹，确保两两不同
    distinct.add(px)
  }
  for (px in distinct) IconCropWindow.of(px, e, e)
  val newMiss = IconCropWindow.winMisses - m0
  ok("⑤ 400 张互不相同 → 400 次新算（少一次=键撞了）", newMiss == 400L, "实际 $newMiss")
  ok("⑤ LRU 真有上限（条数不超 256）", IconCropWindow.winCacheSize in 1..256,
    "条数=${IconCropWindow.winCacheSize}")
  val backA = IconCropWindow.of(a, e, e)
  ok("⑤ 被挤出后重算仍逐字相同", same(a1, backA), "A 回来还是同一个窗口=${backA?.keepPct}%")

  println("   平均一次判据 ${(IconCropWindow.winScanAvgNs / 1000).coerceAtLeast(1)}µs，最狠 ${IconCropWindow.winScanMaxMsVal} ms")
  println(if (bad == 0) "WinCacheKt OK：$checks 条全绿" else "WinCacheKt 红：$bad/$checks 条")
  if (bad != 0) exitProcess(1)
}
