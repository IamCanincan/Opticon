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
/** 统计快照的广播动作（应用侧  收）。 */
const val ACTION_STATS = "com.iamcanincan.opticon.STATS"

internal object Stats {
  /** 「每这么多次 bump 广播一次」——通知一天几十条、裁圆一次重启上千次，20 是折中。 */
  private const val FLUSH_EVERY = 20L

  /** 键 = `<进程>/<分支>`，值 = 次数。 */
  private val counts = ConcurrentHashMap<String, Int>()
  private val total = AtomicLong(0)

  fun bump(key: String) {
    val k = procName() + "/" + key
    counts.merge(k, 1) { a, b -> a + b }
    if (total.incrementAndGet() % FLUSH_EVERY == 0L) flush()
  }

  /** 快照：`键=值;键=值`（按值降序，应用侧直接画条形图）。 */
  fun snapshot(): String =
    counts.entries.sortedByDescending { it.value }.take(24)
      .joinToString(";") { it.key + "=" + it.value }

  private fun flush() {
    runCatching { ModuleRuntime.publishStats(snapshot()) }
  }

  /** 宿主进程名（`Application.getProcessName()`，API 28+ 静态可用，不需要 Context）。 */
  private fun procName(): String =
    runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?"
}
