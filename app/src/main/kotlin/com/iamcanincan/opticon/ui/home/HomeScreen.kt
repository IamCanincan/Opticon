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
import androidx.compose.material3.LocalContentColor
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
  val (cropCount, notifyCount, total) = splitCounts(snapshot)

  ScreenList {
    item {
      OpticonCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Column(modifier = Modifier.padding(22.dp)) {
          Text(
            text = stringResource(R.string.home_role),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigStat("裁圆", cropCount, Modifier.weight(1f))
            BigStat("通知", notifyCount, Modifier.weight(1f))
          }
          Text(
            text = if (at == 0L) stringResource(R.string.home_total_none)
            else (if (total > 0) "共 $total 次 · " else "") + stringResource(R.string.home_total_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
          )
        }
      }
    }

    item { SectionLabel(stringResource(R.string.home_features)) }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusTile(Icons.Default.Circle, stringResource(R.string.tab_crop),
          stringResource(R.string.home_sub_crop), options.circleEnabled)
        StatusTile(Icons.Default.InvertColors, stringResource(R.string.tab_mono),
          stringResource(R.string.home_sub_mono), options.monoInvert)
        StatusTile(Icons.Default.Notifications, stringResource(R.string.tab_notify),
          stringResource(R.string.home_sub_notify), options.notifyEnabled)
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
private fun StatusTile(icon: ImageVector, title: String, subtitle: String, on: Boolean) {
  val scheme = MaterialTheme.colorScheme
  OpticonCard(
    containerColor = if (on) scheme.primaryContainer else scheme.surfaceContainerHigh,
    contentColor = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier.size(46.dp)
          .background(
            if (on) scheme.onPrimaryContainer.copy(alpha = 0.12f)
            else scheme.onSurface.copy(alpha = 0.08f),
            RoundedCornerShape(12.dp),
          ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
      }
      Spacer(modifier = Modifier.size(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = LocalContentColor.current.copy(alpha = 0.72f),
        )
      }
      Text(
        text = stringResource(if (on) R.string.home_on else R.string.home_off),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** 快照按功能分成两堆（裁圆 / 通知），再各给一个总数。 */
private fun splitCounts(snapshot: String?): Triple<Int, Int, Int> {
  if (snapshot.isNullOrBlank()) return Triple(0, 0, 0)
  var crop = 0
  var notify = 0
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val branch = part.substring(0, eq).substringAfter('/')
    when {
      branch.startsWith("裁圆") -> crop += n
      branch.startsWith("通知") -> notify += n
    }
  }
  return Triple(crop, notify, crop + notify)
}

/** 头图里的一个大数字 + 下面一行小字。 */
@Composable
private fun BigStat(label: String, value: Int, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(
      text = "$value",
      fontSize = 40.sp,
      fontWeight = FontWeight.SemiBold,
      color = LocalContentColor.current.copy(alpha = if (value > 0) 1f else 0.45f),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = LocalContentColor.current.copy(alpha = 0.72f),
    )
  }
}
