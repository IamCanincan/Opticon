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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
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
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.GroupGap
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.groupRowShape
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.stats.StatsStore

/**
 * **主页 = 状态页**，版式对齐 KernelSU 管理器的首页（2026-11-07 主人："还是太丑了"）：
 *
 * - 顶部一张**紧凑状态卡**（图标 + 一行结论 + 一行数据 + 右侧小胶囊），不是一大块空数字；
 * - 下面是**灰图标 + 标题/副标题**的列表，图标不再带彩色底块；
 * - 卡片统一 20dp 圆角、行间 8dp 缝 —— 与他机上那页的规格一致。
 *
 * 分工不变：主页只读状态；开关在「功能」，明细在「统计」，配置与排障在「设置」。
 */
@Composable
fun HomeScreen() {
  val context = LocalContext.current
  val options = ModulePrefs.read(ModulePrefs.of(context))
  val (snapshot, at) = StatsStore.load(context)
  val (cropCount, notifyCount, total) = splitCounts(snapshot)
  val onCount = listOf(options.circleEnabled, options.monoInvert, options.notifyEnabled).count { it }

  ScreenList {
    item { PageRole(stringResource(R.string.home_role)) }

    // 状态卡：图标 + 一行结论 + 一行数据 + 右侧胶囊（同 KernelSU 的"工作中 / 版本 / LKM"）
    item {
      OpticonCard(
        padding = 0.dp,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        border = null,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(28.dp),
          )
          Spacer(modifier = Modifier.size(14.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.home_state_ok),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = if (at == 0L) stringResource(R.string.home_total_none)
              else stringResource(R.string.home_state_data, total, cropCount, notifyCount),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
          }
          Box(
            modifier = Modifier
              .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
              .padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            Text(
              text = stringResource(R.string.home_state_chip, onCount),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
      }
    }

    item { GroupTitle(stringResource(R.string.home_features)) }

    // 功能列表：灰图标 + 标题 + 副标题 + 右侧开/关小胶囊
    item {
      // 首尾大圆角、中间小圆角，行间只留 2dp（2026-11-07 主人：小圆角的边要贴得更近点）。
      Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        val rows = listOf(
          FeatureSpec(Icons.Default.Circle, R.string.tab_crop, R.string.home_sub_crop, options.circleEnabled, cropCount),
          FeatureSpec(Icons.Default.InvertColors, R.string.tab_mono, R.string.home_sub_mono, options.monoInvert, -1),
          FeatureSpec(Icons.Default.Notifications, R.string.tab_notify, R.string.home_sub_notify, options.notifyEnabled, notifyCount),
        )
        rows.forEachIndexed { i, row ->
          FeatureRow(row, groupRowShape(i, rows.size))
        }
      }
    }

    item { GroupTitle(stringResource(R.string.home_tips)) }

    // 底部这一组是"常见疑问"：原来是页面底部一行小字，下面半屏全是空白（主人："每个板块都好空"）。
    // 顶栏收回普通高度后，两行提示也能完整落在药丸上方（之前大标题栏时第二行会被压住）。
    item {
      Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        TipRow(
          icon = Icons.Default.Refresh,
          title = R.string.home_tip_stale_title,
          body = R.string.home_tip_stale_body,
          shape = groupRowShape(0, 3),
        )
        TipRow(
          icon = Icons.Default.Info,
          title = R.string.home_tip_count_title,
          body = R.string.home_tip_count_body,
          shape = groupRowShape(1, 3),
        )
        TipRow(
          icon = Icons.Default.Info,
          title = R.string.home_tip_off_title,
          body = R.string.home_tip_off_body,
          shape = groupRowShape(2, 3),
        )
      }
    }
  }
}

/**
 * 一行功能：**灰色图标**（不带彩色底块）+ 标题/副标题 + 右侧状态胶囊。
 * 版式与 KernelSU 的「管理员版本 / 内核版本」那几行一致。
 */
/** 一行功能的数据：图标、标题、副标题、开关状态、次数（-1 = 这一项没有计数）。 */
private data class FeatureSpec(
  val icon: ImageVector,
  val title: Int,
  val subtitle: Int,
  val on: Boolean,
  val count: Int,
)

@Composable
private fun FeatureRow(spec: FeatureSpec, shape: androidx.compose.ui.graphics.Shape) {
  val scheme = MaterialTheme.colorScheme
  val icon = spec.icon
  val title = spec.title
  val subtitle = spec.subtitle
  val on = spec.on
  OpticonCard(shape = shape, border = null, padding = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (on) scheme.primary else scheme.outline,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.size(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(title),
          style = MaterialTheme.typography.titleMedium,
          color = scheme.onSurface,
        )
        Text(
          text = stringResource(subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
        )
      }
      if (spec.count >= 0) {
        Text(
          text = stringResource(R.string.home_count, spec.count),
          style = MaterialTheme.typography.labelMedium,
          color = if (on) scheme.primary else scheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 10.dp),
        )
      }
      Box(
        modifier = Modifier
          .background(
            if (on) scheme.primaryContainer else scheme.surfaceContainerHighest,
            RoundedCornerShape(50),
          )
          .padding(horizontal = 10.dp, vertical = 3.dp),
      ) {
        Text(
          text = stringResource(if (on) R.string.home_on else R.string.home_off),
          style = MaterialTheme.typography.labelMedium,
          color = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
      }
    }
  }
}


/** 提示行：灰图标 + 标题 + 一行解释，与功能行同一套版式（没有右侧胶囊）。 */
@Composable
private fun TipRow(icon: ImageVector, title: Int, body: Int, shape: androidx.compose.ui.graphics.Shape) {
  OpticonCard(shape = shape, border = null, padding = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.size(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
          text = stringResource(body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
/** 章节标题：KSU 用 titleSmallEmphasized + 主色，左 16dp、下 8dp —— 照抄。 */
@Composable
private fun GroupTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmallEmphasized,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
  )
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
