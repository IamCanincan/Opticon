package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


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
