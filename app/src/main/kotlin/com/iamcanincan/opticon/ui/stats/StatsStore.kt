package com.iamcanincan.opticon.ui.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
  }

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
    Log.i("Opticon", "StatsAck: 收到统计快照 ${snap.length} 字符")
  }
}
