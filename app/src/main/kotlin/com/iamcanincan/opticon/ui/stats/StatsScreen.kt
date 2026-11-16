package com.iamcanincan.opticon.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.ScreenList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「统计」页：**三个数字**，只回答一个问题 —— 它到底在这台机器上干没干活。
 *
 * 2026-11-16 主人问「统计页有用吗」之后收敛成这一层：饼图、占比、按来源拆分、耗时账、
 * 明细导出全部下线（`Stats.timing/event`、`StatsStore.savePerf/appendEvents/exportEvents`
 * 两端一起删）。理由很简单：**那些读数不改变任何决定** —— 占比是拿自己的数算自己，
 * 明细我诊断时用的是 logcat 与 dump。留着的这一格反过来真的有用：某个功能一直是 0，
 * 就说明它的作用域没勾、或进程没重启，那是唯一能在界面上自证"没生效"的地方。
 *
 * 数据仍由宿主每 20 次计数广播一次，页面订阅 `StatsStore.revision` 自己更新。
 */
@Composable
fun StatsScreen() {
  val context = LocalContext.current
  val rev = StatsStore.revision.intValue
  val (snapshot, at) = remember(rev) { StatsStore.load(context) }
  val byFeature = remember(snapshot) { totalsByFeature(snapshot) }
  val grand = byFeature.values.sum()

  ScreenList {
    item { PageRole(stringResource(R.string.stats_role)) }

    item {
      OpticonCard {
        Text(
          text = stringResource(R.string.stats_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (at == 0L) stringResource(R.string.stats_empty)
          else stringResource(R.string.stats_at, timeText(at)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        FEATURES.forEachIndexed { i, (key, labelRes) ->
          if (i > 0) Spacer(modifier = Modifier.height(12.dp))
          CountRow(
            label = stringResource(labelRes),
            count = byFeature[key] ?: 0,
            color = featureColor(i),
          )
        }
      }
    }

    // 全是 0 时这一屏就只剩三个 0，得有一句话说清"为什么是 0、下一步看哪里"。
    if (grand == 0) {
      item {
        GroupTitle(stringResource(R.string.stats_empty_title))
        OpticonCard {
          Text(
            text = stringResource(R.string.stats_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      item {
        OpticonCard(padding = 0.dp) {
          Text(
            text = stringResource(R.string.stats_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          )
        }
      }
    }
  }
}

/** 固定三行、固定顺序：数字一变行不变，扫一眼就知道谁没动。 */
private val FEATURES = listOf(
  "自适应" to R.string.tab_crop,
  "动态取色" to R.string.tab_mono,
  "单色通知" to R.string.tab_notify,
)

@Composable
private fun featureColor(index: Int): Color {
  val scheme = MaterialTheme.colorScheme
  return when (index % 3) {
    0 -> scheme.primary
    1 -> scheme.tertiary
    else -> scheme.secondary
  }
}

@Composable
private fun CountRow(label: String, count: Int, color: Color) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(
      modifier = Modifier.size(10.dp).clip(CircleShape).background(color),
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = "$count",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = stringResource(R.string.stats_times),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * `桌面/自适应=1200;系统界面/通知/被补=12` → 每个功能的总次数。
 *
 * 键的第二段才是功能名（第一段是进程，第三段是分支明细）—— 进程必须留着分桶，
 * 否则同一个功能在桌面与系统界面各数一遍会被合并成"两倍"。
 */
private fun totalsByFeature(snapshot: String?): Map<String, Int> {
  if (snapshot.isNullOrBlank()) return emptyMap()
  val out = HashMap<String, Int>()
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val key = part.substring(0, eq)
    val branch = key.substringAfter('/', key)
    val feature = when (val raw = branch.substringBefore('/')) {
      "单色" -> "动态取色"
      "通知" -> "单色通知"
      else -> raw
    }
    out[feature] = (out[feature] ?: 0) + n
  }
  return out
}

private fun timeText(at: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
