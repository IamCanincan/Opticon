package com.iamcanincan.opticon.ui.mono

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.FeatureSwitchCard
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section

/**
 * 「单色」页：仅涉及 Android 16 强制动态取色下，为未适配应用补一张极性正确的单色剪影。
 *
 * 2026-10-26 三个功能分页：从原「图标」页拆出。开关卡使用与裁圆 / 通知相同的
 * [FeatureSwitchCard]，说明使用共享 [NoteList]。
 */
@Composable
fun MonoScreen(onNotify: (String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var enabled by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_MONO_INVERT, ModulePrefs.DEFAULT_MONO_INVERT))
  }
  val savedHint = stringResource(R.string.switch_saved_hint)

  ScreenList {
    item {
      FeatureSwitchCard(
        icon = Icons.Default.InvertColors,
        title = stringResource(R.string.mono_switch_title),
        body = stringResource(R.string.mono_switch_body),
        checked = enabled,
        onCheckedChange = { value ->
          enabled = value
          prefs.edit().putBoolean(ModulePrefs.KEY_MONO_INVERT, value).apply()
          onNotify(savedHint)
        },
      )
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
