package com.iamcanincan.opticon.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.crop.CropScreen
import com.iamcanincan.opticon.ui.mono.MonoScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen

/**
 * 「功能」页：三个开关页合成一页，顶部一排小标签切换。
 *
 * 为什么合：底栏只有四格（主页 / 功能 / 统计 / 设置），三个功能页各自占一格会把"统计"
 * 和"设置"挤掉；而它们本来就是同一件事的三个开关，放在一起才看得清"哪些开了"。
 *
 * ⚠ 三个子页各自是 `ScreenList`（自带底栏让位），所以这里只给它们 `weight(1f)` 的高度，
 *   不再套一层滚动 —— 套两层会让底栏的"下滑收起"判据读错滚动容器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen(onNotify: (String) -> Unit) {
  var sub by remember { mutableIntStateOf(0) }
  Column(modifier = Modifier.fillMaxSize()) {
    // 每页第一行先说自己是谁干什么。
    Text(
      text = stringResource(R.string.features_role),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val labels = listOf(R.string.tab_crop, R.string.tab_mono, R.string.tab_notify)
      labels.forEachIndexed { i, res ->
        FilterChip(
          selected = sub == i,
          onClick = { sub = i },
          label = { Text(stringResource(res)) },
        )
      }
    }
    Box(modifier = Modifier.weight(1f)) {
      when (sub) {
        0 -> CropScreen(onNotify = onNotify)
        1 -> MonoScreen(onNotify = onNotify)
        else -> NotifyScreen(onNotify = onNotify)
      }
    }
  }
}
