package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp


/**
 * 两个页面共用的小组件。
 *
 * 放在这里而不是各自复制一份：分页之后「小标题 + 它领的那张卡」这个组合
 * 两边都要用，各写一份迟早会长歪（间距、字号会不会一致）。
 */

/**
 * 页面底部为悬浮药丸导航让出的空间。
 *
 * ⚠ 这**不是**给内容区整体减高度用的 —— 那样会在药丸下方留出一条空白背景带，
 * 看着像"底栏后面还垫了块背景"。正确做法是：内容区照常铺满到屏幕底部
 * （滚动时从药丸后面穿过，这才是悬浮感），只在**列表自己的 contentPadding**
 * 里加上这一段，保证滚到底时最后一项不会被药丸压住。
 *
 * = 药丸高度（约 56dp）+ 它距屏幕底部的边距（20dp）+ 一点余量。
 */
internal val FloatingNavSpace = 96.dp

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
 * 卡片左侧的图标方块：圆角方块 + 居中图标。
 *
 * 「模块」页和「关于」页都用它 —— 所以放在共享文件里，而不是留在某一页当私有函数
 * （搬页的时候会跟着断掉，编译才报）。
 *
 * ⚠ 「通知」页里有个**同名**的 `IconBadge`，签名不同（它收 drawable 资源 id
 * 和选中态）。那个留在通知页私有即可，别把两个合并 —— 它们服务的视觉语义不一样：
 * 这个画的是「语义图标」，那个画的是「可选项的图标底板」。
 */
@Composable
internal fun IconTile(
  icon: ImageVector,
  containerColor: Color,
  contentColor: Color,
) {
  Surface(shape = MaterialTheme.shapes.small, color = containerColor, modifier = Modifier.size(40.dp)) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}
