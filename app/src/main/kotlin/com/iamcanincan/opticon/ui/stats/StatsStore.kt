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
 */
internal object StatsStore {
  private const val PREFS = "opticon_stats"
  private const val KEY_SNAPSHOT = "snapshot"
  private const val KEY_AT = "at"

  fun save(context: Context, snapshot: String, at: Long) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_SNAPSHOT, snapshot)
      .putLong(KEY_AT, at)
      .apply()
  }

  /** 读一份快照；没有数据时返回 (null, 0)。 */
  fun load(context: Context): Pair<String?, Long> {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return p.getString(KEY_SNAPSHOT, null) to p.getLong(KEY_AT, 0L)
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
