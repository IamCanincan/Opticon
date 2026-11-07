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
      OpticonCard(
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
      ) {
        Column(modifier = Modifier.padding(22.dp)) {
          Text(
            text = stringResource(R.string.home_role),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "$total",
                fontSize = 60.sp,
                lineHeight = 64.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalContentColor.current,
              )
              Text(
                text = stringResource(R.string.home_total_unit),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.72f),
              )
            }
            Column(horizontalAlignment = Alignment.End) {
              SplitStat("裁圆", cropCount)
              Spacer(modifier = Modifier.height(6.dp))
              SplitStat("通知", notifyCount)
            }
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
      // 分组列表（2026-11-07 主人："各个块之间应是小圆角相近但并未贴在一起，首尾为大圆角"）：
      // 首行只圆上面两角、末行只圆下面两角、中间行四个小圆角；行间留 3dp 缝，不贴在一起。
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val rows = listOf(
          Triple(Icons.Default.Circle, R.string.tab_crop to R.string.home_sub_crop, options.circleEnabled),
          Triple(Icons.Default.InvertColors, R.string.tab_mono to R.string.home_sub_mono, options.monoInvert),
          Triple(Icons.Default.Notifications, R.string.tab_notify to R.string.home_sub_notify, options.notifyEnabled),
        )
        rows.forEachIndexed { i, row ->
          StatusTile(
            icon = row.first,
            title = stringResource(row.second.first),
            subtitle = stringResource(row.second.second),
            on = row.third,
            shape = groupedShape(i, rows.size),
          )
        }
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
private fun StatusTile(
  icon: ImageVector,
  title: String,
  subtitle: String,
  on: Boolean,
  shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraLarge,
) {
  val scheme = MaterialTheme.colorScheme
  // MD3E 的弹簧过渡：开关状态变化时整块颜色平滑过渡，不是硬切。
  val bg = androidx.compose.animation.animateColorAsState(
    targetValue = if (on) scheme.primaryContainer else scheme.surfaceContainerHigh,
    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 380f),
    label = "tileBg",
  ).value
  OpticonCard(
    shape = shape,
    containerColor = bg,
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

/** 分组列表的圆角：首行圆上两角、末行圆下两角，中间四个小圆角。 */
private fun groupedShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
  val big = 28.dp
  val small = 8.dp
  return when {
    count == 1 -> RoundedCornerShape(big)
    index == 0 -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
    index == count - 1 -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
    else -> RoundedCornerShape(small)
  }
}

/** 头图右侧的分项：数字大一号、标签小一号，右对齐，与左边的大总数形成层次。 */
@Composable
private fun SplitStat(label: String, value: Int) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = LocalContentColor.current.copy(alpha = 0.75f),
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = "$value",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      color = LocalContentColor.current,
    )
  }
}
