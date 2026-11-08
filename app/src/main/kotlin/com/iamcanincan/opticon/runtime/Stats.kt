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

  /** 待发的事件行上限：一次全量重画就是 685 行，攒到这么多就先发一批。 */
  private const val EVENT_BATCH = 200

  /** 已记过的图标键上限（每进程一份）：`sig|edge`，避免同一张图被反复记。 */
  private const val EVENT_SEEN_MAX = 4096

  /** 键 = `<进程>/<分支>`，值 = 次数。 */
  private val counts = ConcurrentHashMap<String, Int>()
  private val total = AtomicLong(0)

  /** 待随下一次广播一起发走的事件行（每张图标一条）。 */
  private val pending = ArrayList<String>()
  private val seen = HashSet<String>()

  fun bump(key: String) {
    val k = procName() + "/" + key
    counts.merge(k, 1) { a, b -> a + b }
    if (total.incrementAndGet() % FLUSH_EVERY == 0L) flush()
  }

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
  fun event(dedupeKey: String, line: String) {
    // ⚠ 攒满这一批时要**当场发走**：只清空不发送 = 这批明细凭空丢了。
    val batch = synchronized(pending) {
      if (seen.size >= EVENT_SEEN_MAX) seen.clear()
      if (!seen.add(dedupeKey)) return
      pending.add(procName() + "	" + line)
      if (pending.size >= EVENT_BATCH) flushLocked() else ""
    }
    if (batch.isNotEmpty()) runCatching { ModuleRuntime.publishStats(snapshot(), batch) }
  }

  /** 快照：`键=值;键=值`（按值降序，应用侧直接画条形图）。 */
  fun snapshot(): String =
    counts.entries.sortedByDescending { it.value }.take(24)
      .joinToString(";") { it.key + "=" + it.value }

  private fun flush() {
    val events = synchronized(pending) { flushLocked() }
    runCatching { ModuleRuntime.publishStats(snapshot(), events) }
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

  /** 宿主进程名（`Application.getProcessName()`，API 28+ 静态可用，不需要 Context）。 */
  private fun procName(): String =
    runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?"
}
