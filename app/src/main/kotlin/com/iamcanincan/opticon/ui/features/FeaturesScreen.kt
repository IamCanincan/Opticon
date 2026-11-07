package com.iamcanincan.opticon.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
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
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.crop.CropScreen
import com.iamcanincan.opticon.ui.mono.MonoScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen

/**
 * 「功能」页：三个开关页合成一页，顶部一排 MD3E 的 `FilledTonalToggleButton` 切换。
 *
 * 为什么用 ToggleButton 而不是 Chip：它是 Material 3 Expressive 的签名组件 —— 选中时
 * 圆角从方形**弹成圆形**（`ToggleButtonDefaults.shapes()` 自带的形变），比 Chip 的
 * 变色更能让人一眼看出"现在是哪一页"。
 *
 * 为什么合：底栏只有四格（主页 / 功能 / 统计 / 设置），三个功能页各自占一格会把"统计"
 * 和"设置"挤掉；而它们本来就是同一件事的三个开关，放在一起才看得清"哪些开了"。
 *
 * ⚠ 三个子页各自是 `ScreenList`（自带底栏让位），所以这里只给它们 `weight(1f)` 的高度，
 *   不再套一层滚动 —— 套两层会让底栏的"下滑收起"判据读错滚动容器。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeaturesScreen(onNotify: (String) -> Unit) {
  var sub by remember { mutableIntStateOf(0) }
  Column(modifier = Modifier.fillMaxSize()) {
    PageRole(stringResource(R.string.features_role))
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SubTab(0, Icons.Default.Circle, R.string.tab_crop, sub, Modifier.weight(1f)) { sub = it }
      SubTab(1, Icons.Default.InvertColors, R.string.tab_mono, sub, Modifier.weight(1f)) { sub = it }
      SubTab(2, Icons.Default.Notifications, R.string.tab_notify, sub, Modifier.weight(1f)) { sub = it }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubTab(
  index: Int,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: Int,
  sub: Int,
  modifier: Modifier = Modifier,
  onPick: (Int) -> Unit,
) {
  FilledTonalToggleButton(
    checked = sub == index,
    onCheckedChange = { onPick(index) },
    modifier = modifier,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
    Text(stringResource(label))
  }
}
