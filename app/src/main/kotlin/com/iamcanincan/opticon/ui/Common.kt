package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R


/**
 * 两个页面共用的小组件。
 *
 * 放在这里而不是各自复制一份：分页之后「小标题 + 它领的那张卡」这个组合
 * 两边都要用，各写一份迟早会长歪（间距、字号会不一致）。
 */

/** 小标题 + 它领的那张卡。分成一个 item，才能把组内间距压到比组间距小。 */
@Composable
internal fun Section(text: String, content: @Composable () -> Unit) {
  Column {
    SectionLabel(text = text)
    Spacer(modifier = Modifier.height(10.dp))
    content()
  }
}


/** 分组小标题（小字、次要色、带左内边距）。 */
@Composable
internal fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp),
  )
}

/**
 * 两个功能页 Hero 里的版本 chip。
 *
 * 两个页面的 Hero 规格必须一致（品牌标记方块 + 一行副标题 + 版本 chip）：
 * 否则切 Tab 时头部高度会跳，看着像两个不相干的界面。
 *
 * 颜色按「宿主底色是 primaryContainer」写 —— 两处 Hero 都是这个底，
 * 用 onPrimaryContainer 的低 alpha 才能保证浅色 / 深色下都看得见。
 */
@Composable
internal fun VersionChip() {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Text(
      text = stringResource(R.string.hero_version_chip, BuildConfig.VERSION_NAME),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}
