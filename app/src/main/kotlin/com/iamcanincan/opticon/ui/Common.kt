package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * 几个配置页共用的小组件。
 *
 * 放在这里而不是各自复制一份：分页之后「小标题 + 它领的那张卡」这个组合
 * 两边都要用，各写一份迟早会长歪（间距、字号会不会一致）。
 * 后来列表脚手架、主卡也同理 —— 「图标」「模块」「关于」三页抄的是同一份东西。
 *
 * ⚠ 「通知」页**不**用这里的多数组件：它的底板尺寸、间距、卡片语言都不一样，
 *   细节见各个组件上的说明。别顺手把它一起统一了，那是改视觉不是去重。
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

/**
 * 一页的列表脚手架：左右 16dp、顶部只留 4dp（顶栏已经让过位），底部让出药丸那一段。
 *
 * 「图标」「模块」「关于」三页原本是三份逐字节相同的 `LazyColumn(...)`，
 * 抽出来一份，改间距才不会只改到其中两页。
 *
 * ⚠ 末尾那段 `FloatingNavSpace` **必须**留在 contentPadding 里，别改成给内容区
 *   整体减高度（原因见 [FloatingNavSpace]：那样药丸下面会多出一条背景带）。
 *
 * 组与组之间 20dp；小标题与它自己的卡片之间只有 10dp（见 [Section]），
 * 这样「标题领着哪张卡」一眼能看出来。
 *
 * ⚠ 「通知」页用的是竖排 `Column` + `verticalScroll`，间距 14dp、左右 20dp，
 *   跟这里不一致 —— 那是视觉差异，不是漏改，别拿 [ScreenList] 去套它。
 */
@Composable
internal fun ScreenList(content: LazyListScope.() -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = 16.dp,
      end = 16.dp,
      top = 4.dp,
      // 末尾让出悬浮药丸那一段，否则滚到底时最后一张卡会被它压住
      bottom = 24.dp + FloatingNavSpace,
    ),
    verticalArrangement = Arrangement.spacedBy(20.dp),
    content = content,
  )
}

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
 * 「图标」「模块」「关于」三页都用它（有的直接用，有的经 [HeroCard] 用）——
 * 所以放在共享文件里，而不是留在某一页当私有函数
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

/**
 * 一页开头的「主卡」：整张卡铺一个 container 色，里面是
 * 反色图标方块 + 标题 + 一句说明，右侧可选挂一个控件（一般是 `Switch`）。
 *
 * 「图标」页的总开关卡和「模块」页的状态卡本来就是同一个东西（后者只是不挂开关），
 * 连边距和字号都一样，所以合成一份。
 *
 * ⚠ 方块是**反着上色**的：底板用 [contentColor]、图标用 [containerColor]。
 *   整张卡已经是 container 色了，方块再顺色就跟卡片糊成一片、等于没有方块。
 *
 * ⚠ [trailing] 是挂在**这一行**最右边的（原来两处都是这么摆的，这里保持原样），
 *   不传 [trailing] 时连那 8dp 的右侧间隔也不生成 —— 模块页的状态卡右边直接收口。
 *   至于「开关该不该待在这一行」，两页没留过说明，别当成定死的约束。
 *
 * @param containerColor 卡片底色，同时当图标的颜色（见上面的反色说明）
 * @param contentColor 卡上的文字色，同时当图标方块的底色
 * @param trailing 右侧控件，为 null 时整行不留右侧间隔
 */
@Composable
internal fun HeroCard(
  icon: ImageVector,
  containerColor: Color,
  contentColor: Color,
  title: String,
  body: String,
  trailing: (@Composable () -> Unit)? = null,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = MaterialTheme.shapes.large,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      IconTile(
        icon = icon,
        containerColor = contentColor,
        contentColor = containerColor,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = contentColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = body,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor,
        )
      }
      if (trailing != null) {
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
      }
    }
  }
}

/**
 * 仓库地址：「关于」页的跳转用它。顶栏那颗源码按钮用的是 `OpticonApp` 里另存的一份，
 * 原因见下面那条 ⚠（同包同名会撞）—— 改地址要**两处一起改**。
 *
 * ⚠ 名字**不能**叫 `REPO_URL`：`OpticonApp` 与本文件同属 `ui` 包，它自己留了一份
 *   `private const val REPO_URL`，这边再来一个同名顶层声明会直接撞成
 *   「Conflicting declarations」编不过。那页在重构范围外、不让动，所以共享的这份让名。
 *
 * ⚠ 也就是说地址目前**仍是两处**：这里 + `OpticonApp` 那份 private const。
 *   改链接要两边都翻 —— 等哪天 `OpticonApp` 可以动了，把它那份并过来再改回原名。
 */
internal val RepoUrl = "https://github.com/IamCanincan/Opticon"
