package com.iamcanincan.opticon.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 各功能的**分支计数**，供应用侧的「统计」页画图。
 *
 * 为什么要在宿主进程里数、再广播出去：计数发生在被注入的进程（桌面 / 系统界面），
 * 应用读不到它们的内存，也读不到它们的数据目录。实测能通的只剩一条：宿主主动把快照
 * 通过广播发给模块应用（与 `CacheAck` 同一条路）。
 *
 * 成本口径：一次 `merge` + 一次自增；每 [FLUSH_EVERY] 次才序列化并广播一次。
 * 不起定时器（宿主里起定时器是长期开销），也不落盘（写成本比这里高两个数量级）。
 *
 * ⚠ 计数是**每个进程各一份**：桌面与系统界面各数各的，键里带进程名区分 ——
 *   混在一起会让"裁圆次数"被两个进程各加一遍。
 */
/** 统计快照的广播动作（应用侧 `StatsReceiver` 收）。 */
const val ACTION_STATS = "com.iamcanincan.opticon.STATS"

internal object Stats {
  /** 「每这么多次 bump 广播一次」——通知一天几十条、裁圆一次重启上千次，20 是折中。 */
  private const val FLUSH_EVERY = 20L

  /** 待发的事件行上限：一次全量重画就是 685 行，攒到这么多就先发一批。 */
  private const val EVENT_BATCH = 200

  /** 已记过的图标键上限（每进程一份）：`sig|edge`，避免同一张图被反复记。 */
  private const val EVENT_SEEN_MAX = 4096

  /** 耗时账：`crop` / `mono` 各自的累计纳秒与张数（真机上的"每张多少 ms"靠它）。 */
  private val perfNs = ConcurrentHashMap<String, AtomicLong>()
  private val perfN = ConcurrentHashMap<String, AtomicLong>()

  /** 键 = `<进程>/<分支>`，值 = 次数。 */
  private val counts = ConcurrentHashMap<String, Int>()
  private val total = AtomicLong(0)

  /** 待随下一次广播一起发走的事件行（每张图标一条）。 */
  private val pending = ArrayList<String>()
  private val seen = HashSet<String>()

  /**
   * 进程名**只取一次**（2026-11-10 全量检查）。
   *
   * 原来 `bump()` / `event()` 每次都现取：API 28+ 是 `Application.getProcessName()` 的一次静态调用，
   * 26/27 更贵 —— 要读一次 `/proc/self/cmdline`。而进程名在进程生命周期内根本不会变，
   * 一次全量重画（~685 张）就是几百次白取。前缀 `"<进程>/"` 也一并存着，省掉每次的第二次拼接。
   */
  private val procNameCached: String by lazy { procName() }
  private val procPrefix: String by lazy { procNameCached + "/" }

  fun bump(key: String) {
    val k = procPrefix + key
    counts.merge(k, 1) { a, b -> a + b }
    if (total.incrementAndGet() % FLUSH_EVERY == 0L) flush()
  }

  /**
   * 记一次耗时（纳秒）。**只累加两个原子数**，热路径上可以忽略；
   * 具体"每张多少 ms"由应用侧拿 累计/张数 算。
   */
  fun timing(kind: String, ns: Long) {
    runCatching {
      perfNs.getOrPut(kind) { AtomicLong() }.addAndGet(ns)
      perfN.getOrPut(kind) { AtomicLong() }.incrementAndGet()
    }
  }

  /** `裁圆:累计ns/张数;单色:…` —— 随统计广播一起回去。 */
  private fun perfSnapshot(): String =
    perfNs.entries.joinToString(";") { (k, v) -> k + ":" + v.get() + "/" + (perfN[k]?.get() ?: 0L) }

  /**
   * 记一条「这张图标走了哪条路」——**同一张图（sig + 边）只记一次**。
   *
   * 为什么要有这条：计数只说明"跑了几次"，看不出某张图走了哪条支、几个读数是什么。
   * 这条记下来之后，应用侧攒成文件、一键导出，就能拿真机的实际情况去改进算法
   * （代价与量级见 devlog：一条约 94~250 字节，一次全量重画约 685 条）。
   *
   * ⚠ 去重表与待发队列都只在宿主进程内存里，进程一重启就清空 —— 这是刻意的：
   *   重启之后桌面会把所有图标重新问一遍，那正好是"再记一遍"的时机。
   * ⚠ 热路径：每次只做一次 HashMap 查 + 少量字符串拼接；攒够 [EVENT_BATCH] 才真发。
   */
  /**
   * @param flushNow 立刻发走（不等攒满）。通知那条路一次只有几张图，靠 20 次的计数
   *   可能要等十几条通知才凑够一次 flush —— 那几行会一直压在内存里。
   */
  fun event(dedupeKey: String, flushNow: Boolean = false, line: () -> String) {
    // ⚠ 整个函数**绝不允许抛**：它挂在图标绘制/通知处理的热路径上，
    //   真出了什么事（OOM、拼串、广播）也只能丢一条记录，不能让图标画不出来。
    runCatching {
      // ⚠ 攒满这一批时要**当场发走**：只清空不发送 = 这批明细凭空丢了。
      val batch = synchronized(pending) {
        if (seen.size >= EVENT_SEEN_MAX) seen.clear()
        if (!seen.add(dedupeKey)) return
        // ⚠ **行是懒的**（2026-11-10 全量检查）：这条记录每张图一行、`buildString` 里还有三四个
        //   `"%.2f".format()`（String.format 是这个热路径上最贵的一步），而去重能把其中绝大多数
        //   丢掉 —— 真机一个图标会被重建 5.9 次，等于 5/6 的格式化白做。先查去重表，命中就一行都不拼。
        pending.add(procPrefix + line())
        if (flushNow || pending.size >= EVENT_BATCH) flushLocked() else ""
      }
      if (batch.isNotEmpty()) ModuleRuntime.publishStats(snapshot(), batch, perfSnapshot())
    }
  }

  /** 快照：`键=值;键=值`（按值降序；应用侧再按功能分组画饼图与堆叠条）。 */
  fun snapshot(): String =
    counts.entries.sortedByDescending { it.value }.take(24)
      .joinToString(";") { it.key + "=" + it.value }

  private fun flush() {
    val events = synchronized(pending) { flushLocked() }
    runCatching { ModuleRuntime.publishStats(snapshot(), events, perfSnapshot()) }
  }

  /**
   * 取走待发的事件行（含调用方自己的 flush）。行之间用 `\u001e`（记录分隔符）拼 ——
   * 日志行里本来就有空格、竖线和中文，用换行/竖线当分隔迟早被某条读数撞上。
   */
  private fun flushLocked(): String {
    if (pending.isEmpty()) return ""
    val out = pending.joinToString("\u001e")
    pending.clear()
    return out
  }

  /**
   * 宿主进程名，不需要 Context。
   *
   * `Application.getProcessName()` 是 **API 28** 才有的静态方法，而 minSdk 是 26 —— 原来的
   * 单行写法在 26/27 上会抛 `NoSuchMethodError`，被 `runCatching` 吞成 `?`，统计页的
   * 「按进程分」就全落到一个 `?/…` 桶里，而且**日志里看不出来**（这正是全量 lint 抓到的那条）。
   * 26/27 退回读 `/proc/self/cmdline`（进程读自己的 proc 恒可读，取第一个 NUL 之前那一段）。
   */
  private fun procName(): String =
    runCatching {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        android.app.Application.getProcessName()
      } else {
        java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
      }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "?"
}
