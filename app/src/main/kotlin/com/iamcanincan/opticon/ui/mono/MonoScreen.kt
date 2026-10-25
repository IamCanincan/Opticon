package com.iamcanincan.opticon.ui.mono

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.FeatureSwitchCard
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.SampleTile
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
      Section(text = stringResource(R.string.section_effect)) { MonoEffectCard() }
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

/**
 * 单色层示意：左=原图标（浅底深形），右=剪影（深形为墨，浅色区域交给桌面主题盘）。
 * 与裁圆页的效果卡共用 [SampleTile]，两页的示意语言一致。
 */
@Composable
private fun MonoEffectCard() {
  val scheme = MaterialTheme.colorScheme
  OpticonCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SampleTile(
        modifier = Modifier.weight(1f),
        title = stringResource(R.string.mono_sample_original_title),
        detail = stringResource(R.string.mono_sample_original_detail),
      ) {
        Box(
          modifier =
            Modifier.size(40.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(scheme.surfaceVariant)
              .border(1.dp, scheme.outline, RoundedCornerShape(14.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
      }
      SampleTile(
        modifier = Modifier.weight(1f),
        title = stringResource(R.string.mono_sample_silhouette_title),
        detail = stringResource(R.string.mono_sample_silhouette_detail),
      ) {
        Icon(
          imageVector = Icons.Default.RadioButtonUnchecked,
          contentDescription = null,
          tint = scheme.onSurface,
          modifier = Modifier.size(30.dp),
        )
      }
    }
    Spacer(modifier = Modifier.height(18.dp))
    Text(
      text = stringResource(R.string.mono_effect_title),
      style = MaterialTheme.typography.titleMedium,
      color = scheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.mono_effect_body),
      style = MaterialTheme.typography.bodyMedium,
      color = scheme.onSurfaceVariant,
    )
  }
}
