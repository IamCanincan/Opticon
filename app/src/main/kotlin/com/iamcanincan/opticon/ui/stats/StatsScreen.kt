package com.iamcanincan.opticon.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.SectionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「统计」页：把宿主进程数出来的分支次数画成条形图。
 *
 * 数据每分钟级才更新一次（宿主每 20 次计数广播一次快照），所以这里不做实时刷新，
 * 只提供一个「刷新」按钮 —— 不为了一个数字去起协程/定时器。
 */
@Composable
fun StatsScreen() {
  val context = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  val loaded = remember(tick) { StatsStore.load(context) }
  val groups = remember(loaded) { groupByProcess(loaded.first) }

  ScreenList {
    item {
      OpticonCard {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = stringResource(R.string.stats_role),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = if (loaded.second == 0L) stringResource(R.string.stats_empty)
            else stringResource(R.string.stats_at, timeText(loaded.second)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(onClick = { tick++ }) {
            Text(stringResource(R.string.stats_refresh))
          }
        }
      }
    }
    item {
      Text(
        text = stringResource(R.string.stats_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      )
    }
    groups.forEach { (proc, rows) ->
      item(key = "h:$proc") { SectionLabel(friendlyProcess(proc)) }
      items(rows.size, key = { i -> "$proc:${rows[i].first}" }) { i ->
        val (label, n) = rows[i]
        BarRow(label = label, value = n, max = rows.maxOf { it.second })
      }
    }
  }
}

/** 一行：`分支名`、次数、以及按组内最大值归一化的条。 */
@Composable
private fun BarRow(label: String, value: Int, max: Int) {
  val frac = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    }
    Spacer(modifier = Modifier.height(4.dp))
    Box(
      modifier = Modifier.fillMaxWidth().height(10.dp)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(5.dp)),
    ) {
      Box(
        modifier = Modifier.fillMaxWidth(frac).height(10.dp)
          .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
      )
    }
  }
}

/**
 * `桌面/裁圆=1200;系统界面/通知/被补=12` → 按进程分组、组内按次数降序。
 *
 * 键里带进程名是刻意的：裁圆在桌面里发生、通知在系统界面里发生，混在一起会把同名的
 * 分支各加一遍，看起来像"裁了一万次"。
 */
private fun groupByProcess(snapshot: String?): List<Pair<String, List<Pair<String, Int>>>> {
  if (snapshot.isNullOrBlank()) return emptyList()
  val byProc = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val key = part.substring(0, eq)
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val slash = key.indexOf('/')
    val proc = if (slash > 0) key.substring(0, slash) else "?"
    val branch = if (slash > 0) key.substring(slash + 1) else key
    byProc.getOrPut(proc) { ArrayList() }.add(branch to n)
  }
  return byProc.entries
    .map { it.key to it.value.sortedByDescending { p -> p.second } }
    .sortedByDescending { it.second.take(1).sumOf { p -> p.second } }
}

/** 包名对普通读者没有意义：桌面 / 系统界面 / 设置，其余保留原名。 */
private fun friendlyProcess(proc: String): String = when (proc) {
  "com.android.launcher3", "com.google.android.apps.nexuslauncher" -> "桌面"
  "com.android.systemui" -> "系统界面"
  "com.android.settings" -> "设置"
  else -> proc
}

private fun timeText(at: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
