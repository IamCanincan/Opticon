package com.iamcanincan.opticon.ui.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import androidx.compose.runtime.mutableIntStateOf
import com.iamcanincan.opticon.runtime.ACTION_STATS

/**
 * 统计页的数据仓库。
 *
 * 数据由**宿主进程**数好后通过广播送来（见 `runtime/Stats.kt` 与 `ModuleRuntime.publishStats`），
 * 应用侧只负责落盘与展示。落盘用 SharedPreferences：一次快照几十字节、几十秒才来一次，
 * 不值得为它起数据库。
 *
 * ⚠ **快照是按进程各发一份的**（桌面一份、系统界面一份，各自数自己的），原来只存最后收到的那一份 ——
 *   2026-11-08 真机现场：切一次单色让桌面重启并上报之后，`systemui` 那几条（通知的已适配 / 被补）
 *   被整份盖掉，"统计"页上通知那一组直接消失。现在按进程分开存：**每个进程各留它最新的一份**，
 *   读的时候拼起来 —— 任何一个进程上报都不会再把另一个的账冲掉。
 */
internal object StatsStore {
  /**
   * 每收到一份宿主快照就 +1。页面读它一次就等于订阅了统计的更新 ——
   * 宿主每 20 次计数广播一次，所以"运行中 / 等待上报"和那几个数字不用切页就能自己变
   * （2026-11-08：原来只在组合时读一次，待在主页上数字永远停在上一次进页的时刻）。
   */
  internal val revision = mutableIntStateOf(0)
  private const val PREFS = "opticon_stats"

  /** 最近一次（任意进程）上报的时刻，主页"运行中 / 等待上报"判的是它。 */
  private const val KEY_AT = "at"

  /** 每个进程一行：`p:<进程名>` → `<上报时刻>;<键=值;键=值>`。 */
  private const val PREFIX = "p:"

  /** 旧版只存一份快照。留着当迁移来源，下一次上报后就不再写它。 */
  private const val LEGACY = "snapshot"

  /**
   * 超过一天没再上报的进程，它的账不再算进来。
   *
   * 为什么要有这条：进程重启后计数从 0 开始（`Stats` 在内存里），旧的那一份会永远停在上次的数字上；
   * 两个进程又都是"用到才活着"的（系统界面尤其），不清理的话"共 N 次"会一直虚高。
   */
  private const val STALE_MS = 24L * 60 * 60 * 1000

  fun save(context: Context, snapshot: String, at: Long) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val byProc = LinkedHashMap<String, StringBuilder>()
    for (part in snapshot.split(';')) {
      if (!part.contains('=')) continue
      val proc = part.substringBefore('=').substringBefore('/')
      byProc.getOrPut(proc) { StringBuilder() }.append(part).append(';')
    }
    val edit = prefs.edit().putLong(KEY_AT, at).remove(LEGACY)
    for ((proc, sb) in byProc) {
      edit.putString(PREFIX + proc, "$at;" + sb.toString().trimEnd(';'))
    }
    edit.apply()
    revision.intValue++
  }

  /**
   * 追加宿主发来的**每图标明细**（`\u001e` 分隔，字段用制表符）。
   *
   * 落盘而不是只存内存：算法改进要看"这台机器上哪些图走了哪条支"，那必须能攒住、能导出。
   * 量级量过：一条约 0.1~0.3 KB，一次全量重画约 685 条。
   * ⚠ 文件封顶 [EVENT_LOG_MAX]（超了只留后半截）—— 常态几天才几 KB，封顶是防"常年不清理"。
   */
  fun appendEvents(context: Context, events: String) {
    runCatching {
      val f = File(context.filesDir, EVENT_LOG)
      FileOutputStream(f, true).use { out ->
        for (line in events.split('\u001e')) {
          if (line.isBlank()) continue
          out.write(line.toByteArray(Charsets.UTF_8))
          out.write('\n'.code)
        }
      }
      if (f.length() > EVENT_LOG_MAX) {
        val text = f.readText()
        val keep = text.takeLast((EVENT_LOG_MAX / 2).toInt())
        f.writeText(keep.substringAfter('\n', keep))
      }
    }
  }

  /** 把明细复制到外部目录（`adb pull` 能直接拉），返回路径；没有文件返回 null。 */
  fun exportEvents(context: Context): String? = runCatching {
    val src = File(context.filesDir, EVENT_LOG)
    if (!src.exists() || src.length() == 0L) return null
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val dst = File(dir, "opticon-events.log")
    src.copyTo(dst, overwrite = true)
    dst.absolutePath
  }.getOrNull()

  private const val KEY_PERF = "perf"

  /** 宿主报来的耗时账（`crop:累计ns/张数;mono:…`），只用来在统计页显示"每张多少 ms"。 */
  fun savePerf(context: Context, perf: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PERF, perf).apply()
  }

  fun loadPerf(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PERF, null)

  private const val EVENT_LOG = "opticon-events.log"
  private const val EVENT_LOG_MAX = 5L * 1024 * 1024

  /** 读**所有进程**最新一份快照拼起来的视图；没有数据时返回 (null, 0)。 */
  fun load(context: Context): Pair<String?, Long> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val at = prefs.getLong(KEY_AT, 0L)
    val now = System.currentTimeMillis()
    val parts = ArrayList<String>()
    for ((key, value) in prefs.all) {
      if (!key.startsWith(PREFIX)) continue
      val raw = value as? String ?: continue
      val stamp = raw.substringBefore(';').toLongOrNull() ?: 0L
      if (now - stamp > STALE_MS) continue
      val rows = raw.substringAfter(';', "")
      if (rows.isNotBlank()) parts.add(rows)
    }
    if (parts.isEmpty()) {
      // 迁移：旧版那一份还在就先拿来显示，等下一个进程上报时自然被替换。
      return prefs.getString(LEGACY, null) to at
    }
    return parts.joinToString(";") to at
  }
}

/**
 * 接住宿主发来的统计快照。
 *
 * ⚠ 免鉴权（导出、无权限）：宿主进程不持有本应用的签名权限，只收得进导出广播。
 *   代价是任何应用都能伪造一份统计 —— 它只影响"统计"页上的数字，不做任何动作。
 */
class StatsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ACTION_STATS) return
    val snap = intent.getStringExtra("stats") ?: return
    val at = intent.getLongExtra("at", System.currentTimeMillis())
    StatsStore.save(context, snap, at)
    // 每张图标一条的明细（宿主攒批发来）。没有就跳过 —— 老版本宿主不发这个字段。
    intent.getStringExtra("perf")?.let { if (it.isNotBlank()) StatsStore.savePerf(context, it) }
    val events = intent.getStringExtra("events")
    if (!events.isNullOrEmpty()) StatsStore.appendEvents(context, events)
    Log.i("Opticon", "StatsAck: 收到统计快照 ${snap.length} 字符、明细 ${events?.length ?: 0} 字符")
  }
}
