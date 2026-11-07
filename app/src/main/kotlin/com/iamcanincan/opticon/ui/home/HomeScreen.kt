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
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.SectionLabel
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
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(20.dp),
        border = null,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
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
              .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                RoundedCornerShape(50),
              )
              .padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            Text(
              text = stringResource(R.string.home_state_chip, onCount),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
    }

    item { SectionLabel(stringResource(R.string.home_features)) }

    // 功能列表：灰图标 + 标题 + 副标题 + 右侧开/关小胶囊
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureRow(Icons.Default.Circle, R.string.tab_crop, R.string.home_sub_crop, options.circleEnabled)
        FeatureRow(Icons.Default.InvertColors, R.string.tab_mono, R.string.home_sub_mono, options.monoInvert)
        FeatureRow(Icons.Default.Notifications, R.string.tab_notify, R.string.home_sub_notify, options.notifyEnabled)
      }
    }

    item {
      Text(
        text = stringResource(R.string.home_hint, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
      )
    }
  }
}

/**
 * 一行功能：**灰色图标**（不带彩色底块）+ 标题/副标题 + 右侧状态胶囊。
 * 版式与 KernelSU 的「管理员版本 / 内核版本」那几行一致。
 */
@Composable
private fun FeatureRow(icon: ImageVector, title: Int, subtitle: Int, on: Boolean) {
  val scheme = MaterialTheme.colorScheme
  OpticonCard(shape = RoundedCornerShape(20.dp), border = null) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (on) scheme.onSurfaceVariant else scheme.outline,
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
