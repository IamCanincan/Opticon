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
 *   混在一起会让"自适应次数"被两个进程各加一遍。
 */
/** 统计快照的广播动作（应用侧 `StatsReceiver` 收）。 */
const val ACTION_STATS = "com.iamcanincan.opticon.STATS"

internal object Stats {
  /** 「每这么多次 bump 广播一次」——单色通知一天几十条、自适应一次重启上千次，20 是折中。 */
  private const val FLUSH_EVERY = 20L

  /** 键 = `<进程>/<分支>`，值 = 次数。 */
  private val counts = ConcurrentHashMap<String, Int>()
  private val total = AtomicLong(0)

  /**
   * 进程名**只取一次**（2026-11-10 全量检查）。
   *
   * 原来 `bump()` 每次都现取：API 28+ 是 `Application.getProcessName()` 的一次静态调用，
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

  // 2026-11-16：`timing()` / `event()` 两条通道随统计页的饼图、耗时账与导出一起下线。
  // 它们的唯一消费者是那个页面；留着就只是在桌面的热路径上白攒字符串与原子数。

  /** 快照：`键=值;键=值`（按值降序）。 */
  fun snapshot(): String =
    counts.entries.sortedByDescending { it.value }.take(24)
      .joinToString(";") { it.key + "=" + it.value }

  private fun flush() {
    runCatching { ModuleRuntime.publishStats(snapshot()) }
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
