package com.iamcanincan.opticon.ui.mono

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.EffectShowcaseCard
import com.iamcanincan.opticon.ui.FeatureInfoCard
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.SampleTile
import com.iamcanincan.opticon.ui.ScreenList

/**
 * 「单色」页：仅涉及 Android 16 强制动态取色下，为未适配应用补一张极性正确的单色剪影。
 *
 * 2026-10-26 三个功能分页：从原「图标」页拆出。说明卡与裁圆 / 通知共用 [FeatureInfoCard]，
 * 注意事项用共享 [NoteList]。
 *
 * 开关只在「功能」页那一行（2026-11-12 单一入口）；这里只讲这个功能做什么。
 */
@Composable
fun MonoScreen() {
  ScreenList {
    item {
      FeatureInfoCard(
        icon = Icons.Default.InvertColors,
        title = stringResource(R.string.mono_switch_title),
        body = stringResource(R.string.mono_switch_body),
      )
    }

    item { GroupTitle(stringResource(R.string.section_effect)) }

    item { MonoEffectCard() }

    item { GroupTitle(stringResource(R.string.section_notes)) }

    item {
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

/**
 * 单色层示意：左=原图标（浅底深形），右=剪影（深形为墨，浅色区域交给桌面主题盘）。
 * 与裁圆页的效果卡共用 [SampleTile] / [EffectShowcaseCard]，两页的示意语言一致。
 */
@Composable
private fun MonoEffectCard() {
  val scheme = MaterialTheme.colorScheme
  EffectShowcaseCard(
    title = stringResource(R.string.mono_effect_title),
    body = stringResource(R.string.mono_effect_body),
  ) {
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
  }
}
