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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.SectionLabel
import com.iamcanincan.opticon.ui.stats.StatsStore

/**
 * **主页 = 状态页**。这一页只回答一个问题：现在是什么状态。
 *
 * 分工（改这一页之前先读）：
 * - 主页：三个功能开没开 + 后台总共处理了多少 —— 只读，不放任何设置项；
 * - 功能：开关本身与效果说明；
 * - 统计：每个分支各多少；
 * - 设置：作用域、排查、运维按钮。
 *
 * 所以这里刻意不放"去调开关 / 看完整统计"两个按钮，也不列分支明细 —— 那两样分别在
 * 功能页与统计页，搬过来只会让三页重复（2026-11-07 主人："每个页面都应有分工"）。
 */
@Composable
fun HomeScreen() {
  val context = LocalContext.current
  val options = ModulePrefs.read(ModulePrefs.of(context))
  val (snapshot, at) = StatsStore.load(context)
  val total = totalCount(snapshot)

  ScreenList {
    item {
      OpticonCard {
        Column(modifier = Modifier.padding(18.dp)) {
          Text(
            text = stringResource(R.string.home_role),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(verticalAlignment = Alignment.Bottom) {
            Text(
              text = if (total > 0) "$total" else "—",
              fontSize = 44.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
              text = stringResource(R.string.home_total_unit),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 8.dp),
            )
          }
          Text(
            text = if (at == 0L) stringResource(R.string.home_total_none)
            else stringResource(R.string.home_total_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item { SectionLabel(stringResource(R.string.home_features)) }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusTile(Icons.Default.Circle, stringResource(R.string.tab_crop), options.circleEnabled)
        StatusTile(Icons.Default.InvertColors, stringResource(R.string.tab_mono), options.monoInvert)
        StatusTile(Icons.Default.Notifications, stringResource(R.string.tab_notify), options.notifyEnabled)
      }
    }

    item {
      Text(
        text = stringResource(R.string.home_hint, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
      )
    }
  }
}

/** 一行状态：图标 + 功能名 + 开/关。开关状态用整块颜色表达，不靠读文字。 */
@Composable
private fun StatusTile(icon: ImageVector, title: String, on: Boolean) {
  val scheme = MaterialTheme.colorScheme
  OpticonCard(
    containerColor = if (on) scheme.primaryContainer else scheme.surfaceContainerHigh,
    contentColor = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier.size(40.dp)
          .background(
            if (on) scheme.onPrimaryContainer.copy(alpha = 0.12f)
            else scheme.onSurface.copy(alpha = 0.08f),
            RoundedCornerShape(12.dp),
          ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
      }
      Spacer(modifier = Modifier.size(14.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = stringResource(if (on) R.string.home_on else R.string.home_off),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** 快照里所有分支的次数之和（跨进程相加），只用于主页那个大数字。 */
private fun totalCount(snapshot: String?): Int {
  if (snapshot.isNullOrBlank()) return 0
  var sum = 0
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    sum += part.substring(eq + 1).toIntOrNull() ?: 0
  }
  return sum
}
