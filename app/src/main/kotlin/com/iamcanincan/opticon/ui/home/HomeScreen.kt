package com.iamcanincan.opticon.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section
import com.iamcanincan.opticon.ui.SectionLabel
import com.iamcanincan.opticon.ui.stats.StatsStore

/**
 * **主页**：一屏看全"装了什么版本、三个开关是什么状态、后台处理了多少"。
 *
 * 与「设置」页的分工：设置是"怎么配、怎么查问题"（作用域、日志、运维按钮）；
 * 主页只回答"现在是什么状态"，并且每个块都能一键跳到对应的详细页。
 *
 * ⚠ 开关状态直接读本应用自己的 SharedPreferences（`ModulePrefs.of`）：界面侧写的就是这份，
 *   宿主侧读的也是这份（经 provider 转发）。这里不做"实时监听"——切页回来时重组一次就够。
 */
@Composable
fun HomeScreen(onOpenFeatures: () -> Unit, onOpenStats: () -> Unit) {
  val context = LocalContext.current
  val options = ModulePrefs.read(ModulePrefs.of(context))
  val snapshot = StatsStore.load(context)

  ScreenList {
    item {
      OpticonCard {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = stringResource(R.string.home_hello, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.home_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item {
      SectionLabel(stringResource(R.string.home_features))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureRow(
          icon = Icons.Default.Circle,
          title = stringResource(R.string.tab_crop),
          on = options.circleEnabled,
        )
        FeatureRow(
          icon = Icons.Default.InvertColors,
          title = stringResource(R.string.tab_mono),
          on = options.monoInvert,
        )
        FeatureRow(
          icon = Icons.Default.Notifications,
          title = stringResource(R.string.tab_notify),
          on = options.notifyEnabled,
        )
      }
    }

    item {
      Section(text = stringResource(R.string.home_stats)) {
        Column(modifier = Modifier.padding(16.dp)) {
          val (snap, at) = snapshot
          if (snap.isNullOrBlank()) {
            Text(
              text = stringResource(R.string.stats_empty),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            for ((label, n) in summarize(snap)) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text("$n", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
              }
            }
            if (at > 0L) {
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = stringResource(R.string.home_stats_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }

    item {
      FilledTonalButton(
        onClick = onOpenStats,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
      ) { Text(stringResource(R.string.home_open_stats)) }
    }
    item {
      FilledTonalButton(
        onClick = onOpenFeatures,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
      ) { Text(stringResource(R.string.home_open_features)) }
    }
  }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, on: Boolean) {
  OpticonCard {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconTile(
        icon = icon,
        containerColor = if (on) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.size(12.dp))
      Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
      Box(
        modifier = Modifier
          .background(
            if (on) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(50),
          )
          .padding(horizontal = 10.dp, vertical = 3.dp),
      ) {
        Text(
          text = stringResource(if (on) R.string.home_on else R.string.home_off),
          style = MaterialTheme.typography.labelMedium,
          color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 快照按功能汇总（跨进程相加）：`桌面/裁圆=1200;系统界面/通知/被补=12` →
 * `裁圆 = 1200`、`通知 · 被补 = 12`。主页只给前三行，详情去统计页。
 */
private fun summarize(snapshot: String): List<Pair<String, Int>> {
  val sums = LinkedHashMap<String, Int>()
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val key = part.substring(0, eq)
    val branch = key.substringAfter('/', key)
    val label = branch.replace("/", " · ")
    sums[label] = (sums[label] ?: 0) + n
  }
  return sums.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
}
