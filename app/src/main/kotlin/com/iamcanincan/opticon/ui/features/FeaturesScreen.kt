package com.iamcanincan.opticon.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.GroupGap
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.MenuRow
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.SubPage
import com.iamcanincan.opticon.ui.groupRowShape

/**
 * 「功能」一级页 = **开关面板**（2026-11-07 主人：「这界面功能怎么还重复了」）。
 *
 * 之前这一页和主页都在列同样三个功能（连次数和开/关胶囊都一样），是重复。现在分工写死：
 * - 主页**看**：状态结论与次数（只读；点一行进详情）；
 * - 功能页**改**：三个开关直接切，外加一个「全部开启 / 全部关闭」。
 *
 * 每一行右侧还有一枚箭头，点它进这个功能的二级页（效果示意与说明都在那里）；
 * 行上其余地方吃的是"切开关"，两者不抢手势。
 */
@Composable
internal fun FeaturesScreen(onOpen: (SubPage) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var crop by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, ModulePrefs.DEFAULT_CIRCLE_ENABLED))
  }
  var mono by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_MONO_INVERT, ModulePrefs.DEFAULT_MONO_INVERT))
  }
  var notify by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, ModulePrefs.DEFAULT_NOTIFY_ENABLED))
  }
  val allOn = crop && mono && notify

  fun write(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

  ScreenList {
    item { PageRole(stringResource(R.string.features_role)) }

    item { GroupTitle(stringResource(R.string.features_group)) }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        val rows =
          listOf(
            Entry(SubPage.CROP, Icons.Default.Circle, R.string.tab_crop, R.string.home_sub_crop, crop),
            Entry(SubPage.MONO, Icons.Default.InvertColors, R.string.tab_mono, R.string.home_sub_mono, mono),
            Entry(SubPage.NOTIFY, Icons.Default.Notifications, R.string.tab_notify, R.string.home_sub_notify, notify),
          )
        rows.forEachIndexed { i, row ->
          SwitchRow(
            entry = row,
            shape = groupRowShape(i, rows.size),
            onDetail = { onOpen(row.page) },
            onToggle = { value ->
              when (row.page) {
                SubPage.CROP -> {
                  crop = value
                  write(ModulePrefs.KEY_CIRCLE_ENABLED, value)
                }
                SubPage.MONO -> {
                  mono = value
                  write(ModulePrefs.KEY_MONO_INVERT, value)
                }
                else -> {
                  notify = value
                  write(ModulePrefs.KEY_NOTIFY_ENABLED, value)
                }
              }
            },
          )
        }
      }
    }

    // 一次全开 / 全关：三个开关逐个点也行，但"全关"是排查时的常用动作。
    item {
      MenuRow(
        icon = Icons.Default.PowerSettingsNew,
        title = stringResource(if (allOn) R.string.features_all_off else R.string.features_all_on),
        subtitle = stringResource(R.string.features_all_hint),
        shape = groupRowShape(0, 1),
        onClick = {
          val target = !allOn
          crop = target
          mono = target
          notify = target
          write(ModulePrefs.KEY_CIRCLE_ENABLED, target)
          write(ModulePrefs.KEY_MONO_INVERT, target)
          write(ModulePrefs.KEY_NOTIFY_ENABLED, target)
        },
        iconTint = MaterialTheme.colorScheme.primary,
        showChevron = false,
      )
    }

    // 这一页只有四行，剩下的空白用一条真正有用的说明补上：开关多久生效（主页那两条讲的是
    // "数字从哪来"和"为什么没变化"，这里答的是"点了之后什么时候起作用"）。
    item { GroupTitle(stringResource(R.string.features_note_group)) }

    item {
      MenuRow(
        icon = Icons.Default.Schedule,
        title = stringResource(R.string.features_effect_title),
        subtitle = stringResource(R.string.features_effect_body),
        shape = groupRowShape(0, 1),
        onClick = {},
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        showChevron = false,
      )
    }
  }
}

private data class Entry(
  val page: SubPage,
  val icon: ImageVector,
  val title: Int,
  val subtitle: Int,
  val on: Boolean,
)

/**
 * 一行开关：整行可点 = 切开关；右侧箭头进详情，最右才是 `Switch`（只做展示 ——
 * 整行已经是同一个开关语义，读屏只报一站）。
 */
@Composable
private fun SwitchRow(
  entry: Entry,
  shape: androidx.compose.ui.graphics.Shape,
  onDetail: () -> Unit,
  onToggle: (Boolean) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  OpticonCard(shape = shape, padding = 0.dp) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .toggleable(value = entry.on, role = Role.Switch, onValueChange = onToggle)
          .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 用和详情页一样的图标方块：这一页只有四行，空着的那半屏靠视觉重量撑一点。
      IconTile(
        icon = entry.icon,
        containerColor = if (entry.on) scheme.primary else scheme.surfaceContainerHighest,
        contentColor = if (entry.on) scheme.onPrimary else scheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(entry.title),
          style = MaterialTheme.typography.titleMedium,
          color = scheme.onSurface,
        )
        Text(
          text = stringResource(entry.subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onDetail) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = stringResource(R.string.features_open_detail),
          tint = scheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
      }
      Switch(checked = entry.on, onCheckedChange = null)
    }
  }
}
