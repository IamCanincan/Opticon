package com.iamcanincan.opticon.ui.mono

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InvertColors
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section

/**
 * 「单色」页：只讲这一件事 —— Android 16 强制取色时，给未适配应用补一张极性正确的剪影。
 *
 * 2026-10-26 三个功能分页：从原来的「图标」页拆出来。开关仍是整张卡可点，
 * 正文单独占整宽（开关不再挤在长正文右侧，见下面那段注释）；说明用共享 [NoteList]。
 */
@Composable
fun MonoScreen(onNotify: (String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var enabled by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_MONO_INVERT, ModulePrefs.DEFAULT_MONO_INVERT))
  }
  val savedHint = stringResource(R.string.switch_saved_hint)

  val toggle = Modifier.toggleable(
    value = enabled,
    role = Role.Switch,
    onValueChange = { value ->
      enabled = value
      prefs.edit().putBoolean(ModulePrefs.KEY_MONO_INVERT, value).apply()
      onNotify(savedHint)
    },
  )

  ScreenList {
    item {
      // ⚠ 开关放**标题那一行**，正文另起一行占满整宽。
      //   原来三样东西挤在同一个 Row 里：开关挂在长正文右侧、垂直居中，于是正文被压成一条窄栏、
      //   开关孤零零浮在半空 —— 真机截图里这是最像"没做完"的一张卡（2026-09-30 复核）。
      OpticonCard(modifier = toggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconTile(
            icon = Icons.Default.InvertColors,
            // 功能开关卡统一用主色底板；灰底板留给纯说明性的卡。
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(14.dp))
          Text(
            text = stringResource(R.string.mono_switch_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Switch(checked = enabled, onCheckedChange = null)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.mono_switch_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    item {
      Section(text = stringResource(R.string.section_notes)) {
        NoteList(
          listOf(
            R.string.note_mono_scope,
            R.string.note_mono_cache,
            R.string.note_themed_icon,
          )
        )
      }
    }
  }
}
