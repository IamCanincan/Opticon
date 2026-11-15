package com.iamcanincan.opticon.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.GroupGap
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.MenuRow
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.clearIconCache
import com.iamcanincan.opticon.ui.restartOtherProcesses
import com.iamcanincan.opticon.ui.restartSystemUi
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.SubPage
import com.iamcanincan.opticon.ui.groupRowShape

/**
 * 「功能」一级页 = **开关面板**（2026-11-07 主人：「这界面功能怎么还重复了」）。
 *
 * 分工写死：
 * - 主页**看**：状态结论与次数，点一行进该功能的二级页（说明与效果都在那里）；
 * - 功能页**改**：三个开关直接切，外加一个「全部开启 / 全部关闭」。
 *
 * ⚠ 开关**只有这里一处**（2026-11-12）：三个功能的二级页里不再放开关；反过来，
 *   这一页也不再挂进详情的箭头 —— 每个功能的入口只有主页那一行，避免两个入口各改一遍。
 */
@Composable
internal fun FeaturesScreen(onNotify: (String) -> Unit) {
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
  val applied = stringResource(R.string.switch_applied)

  fun write(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

  /**
   * 开关之后立刻把后续动作做完（2026-11-08 主人：「开关后自动清缓存等一系列操作」）。
   *
   * **一刀切：三个开关里任意一个动了，三条运维操作全做**（2026-11-16 主人定的）。
   *
   * 为什么不再按功能分档：我按"谁真的会读这个开关"分过一次，判动态取色与系统界面无关
   * （`monoInvert` 全仓只有 `CircleIconDrawable.getMonochrome()` 与只装在桌面里的
   * `hookSynthesizedMonochrome` 两个读取点，通知那路读的是 `notifyEnabled`）——
   * **真机当场推翻**：开着时只重启桌面没问题，关掉时图标变不回来，必须重启系统界面。
   * 代码里读不到那个开关，不等于那个进程手上没有按旧设置画好的结果。
   *
   * 代价是切单色通知时也会删一次桌面图标缓存、桌面闪一下并重画全部图标（掩码缓存冷的时候
   * 这一趟约 45 秒）。主人认了这个代价，换"不会再漏掉某个进程"。
   */
  fun apply() {
    clearIconCache(context)
    restartSystemUi(context)
    restartOtherProcesses(context)
    onNotify(applied)
  }

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
              apply()
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
          apply()
        },
        // 中性底：上面三个开关"开着"就是主色方块，这个按钮再上主色会被读成第四个开着的东西。
        tile = true,
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
        tile = true,
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
 * 一行开关：只有最右的 `Switch` 吃点击（2026-11-08 主人：「应只有开关按钮部分点击才能开关」）；
 * 行内其余部分是展示。进详情的箭头已收掉（2026-11-12）：详情只从主页那行进。
 */
@Composable
private fun SwitchRow(
  entry: Entry,
  shape: androidx.compose.ui.graphics.Shape,
  onToggle: (Boolean) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  OpticonCard(shape = shape, padding = 0.dp) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
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
      // ⚠ 2026-11-08 主人「应只有开关按钮部分点击才能开关，不然太灵敏了」——
      //   整行不是 toggleable，点在文字上不会把开关翻过去。
      // 文字列是 weight(1f)，不留这道缝就会顶到开关上（自适应那行说明会折行，最明显）。
      Spacer(modifier = Modifier.width(10.dp))
      Switch(checked = entry.on, onCheckedChange = onToggle)
    }
  }
}
