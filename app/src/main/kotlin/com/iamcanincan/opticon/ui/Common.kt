package com.iamcanincan.opticon.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import kotlinx.coroutines.launch


/**
 * 几个配置页共用的小组件。
 *
 * 放在这里而不是各自复制一份：分页之后「小标题 + 它领的那张卡」这个组合
 * 两边都要用，各写一份迟早会长歪（间距、字号会不会一致）。
 * 后来列表脚手架、主卡也同理 —— 「图标」「模块」「关于」三页抄的是同一份东西。
 *
 * ⚠ 「通知」页 2026-10-26 起也并回了 [ScreenList]/[Section]/[HeroCard]（主人：「界面要统一整齐」）；
 *   它仍保留自己的 `IconBadge`（drawable 语义图标，尺寸统一 40dp）和 `InfoCard` 形状。
 *   [CommandRow] 是两页共用的验证命令块。
 */

/**
 * 页面底部为悬浮药丸导航让出的空间。
 *
 * ⚠ 这**不是**给内容区整体减高度用的 —— 那样会在药丸下方留出一条空白背景带，
 * 看着像"底栏后面还垫了块背景"。正确做法是：内容区照常铺满到屏幕底部
 * （滚动时从药丸后面穿过，这才是悬浮感），只在**列表自己的 contentPadding**
 * 里加上这一段，保证滚到底时最后一项不会被药丸压住。
 *
 * ⚠ 药丸自己还要额外让开系统导航栏（见 `OpticonApp` 里那句 `navigationBarsPadding()`）。
 * 内容区的底边是由 `Scaffold` 的 innerPadding 顶到**导航栏上沿**的，而药丸在那之上
 * 再抬 20dp，所以它相对内容底边占到 `56 + 20 = 76dp`；下面 [ScreenList] 还多给 24dp，
 * 合计 120dp —— 96dp 这个数是按"药丸高度 + 20dp 边距 + 余量"留的，别往下砍到 76 以下。
 *
 * = 药丸高度（约 56dp）+ 它距**导航栏**的边距（20dp）+ 一点余量。
 *   （"距屏幕底部"是 [OpticonApp] 加上 `navigationBarsPadding()` 之前的旧口径，
 *   那个位置上药丸有一半压在系统导航栏底下，已改。）
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
 * ⚠ 「通知」页 2026-10-26 起也用这份脚手架（16dp / 20dp）；它原来那套 20dp 边距、
 *   14dp 内边距已并回，别再用"通知页不一样"当理由另写一套。
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

/**
 * 全应用统一的卡片：一档底色 + **1dp `outlineVariant` 描边** + 内边距。
 *
 * ## 为什么不是 `ElevatedCard`
 * `ElevatedCard` 的默认底是 `surfaceContainerLow` **加一层阴影** —— 而深色主题下阴影几乎
 * 看不见，`surfaceContainerLow` 又只比页面背景亮一档，于是整页卡片糊成一坨：真机深色下
 * 卡片边界基本读不出来（2026-09-30 真机截图复核）。描边不依赖阴影、也不依赖"深色里那一档
 * 明度差能不能看见"，两套主题下都成立。
 *
 * ⚠ 这条约定其实早就写在「通知」页的文件头（"卡片统一 24dp 圆角 + 1dp outlineVariant 描边"），
 *   只是当时**没有任何一张卡真的实现了它**。现在这里是全应用唯一的卡片构造入口。
 *
 * ⚠ 内层元素（[IconTile] 的底板、命令块、缩略色块）用 `surfaceContainerHighest`，
 *   比卡片再亮一档 —— 改这里的底色就要回去核那几个内层色，别让它们和卡面撞成同一个色。
 *
 * @param padding 卡内统一内边距。分隔线要通到卡边的（作用域清单 / 步骤卡）传 `0.dp`，
 *   由行自己带左右内边距。
 */
@Composable
internal fun OpticonCard(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  padding: Dp = 16.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors =
      CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(modifier = Modifier.padding(padding), content = content)
  }
}

/**
 * 等宽命令块 + 一键复制。**模块页和通知页共用**（2026-10-26）。
 *
 * 为什么抽出来：两页的"怎么确认生效"都要用户跑 `adb logcat -s Opticon`，原来模块页可复制、
 * 通知页只在段落里写了一遍命令 —— 同一件事两种做法，排查时还得手敲。形状沿用模块页那块：
 * `surfaceContainerHighest`（比卡面亮一档，见 [OpticonCard] 的说明）+ 等宽字体 + 右侧复制图标；
 * 点击整块生效（原来只有右侧 48dp 图标能按，命中面积太小）。
 *
 * ⚠ 复制成功走外壳的 Snackbar（[onNotify]）：`setClipEntry` 是 suspend 的，提示必须排在写入之后，
 *   不能先弹再写（写失败会变成假阳性）。
 */
@Composable
internal fun CommandRow(command: String, onNotify: (String) -> Unit) {
  val copiedMessage = stringResource(R.string.verify_copied)
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  Surface(
    onClick = {
      scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, command)))
        onNotify(copiedMessage)
      }
    },
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = command,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Icon(
        imageVector = Icons.Default.ContentCopy,
        contentDescription = stringResource(R.string.verify_copy),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/** 小标题 + 它领的那张卡。分成一个 item，才能把组内间距压到比组间距小。 */
@Composable
internal fun Section(text: String, content: @Composable () -> Unit) {
  Column {
    SectionLabel(text = text)
    Spacer(modifier = Modifier.height(12.dp))
    content()
  }
}

/**
 * 「说明」要点：带圆点的列表（裁圆 / 单色两页共用）。
 *
 * 2026-10-26 从图标页搬出来：三个功能分页之后，两页都要这块，不能再各抄一份。
 */
@Composable
internal fun NoteList(notes: List<Int>) {
  OpticonCard {
    Column {
      notes.forEach { res ->
        Row(modifier = Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
          // Surface 小圆点比 "•" 字符精致：在浅色下是一个清晰可见的小点，
          // 在深色下用 onSurfaceVariant 的 alpha 自动变得柔和。
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 9.dp, end = 12.dp).size(6.dp),
          ) {}
          Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/**
 * 分组小标题：主色小竖条 + 标题字。
 *
 * 原来只是一行 `labelLarge` + `onSurfaceVariant` —— 和卡里的正文（`bodySmall`、同一个次要色）
 * 在字号与颜色上几乎同重，于是"这一段讲什么"在屏幕上读不出来，整页看着就是一堆同样大小的
 * 灰字（2026-09-30 真机复核）。加一条 4dp 主色竖条把层级立起来。
 */
@Composable
internal fun SectionLabel(text: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(start = 4.dp),
  ) {
    Box(
      modifier =
        Modifier.size(width = 4.dp, height = 16.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/**
 * 卡片左侧的图标方块：圆角方块 + 居中图标。
 *
 * 「图标」「模块」「关于」三页都用它（有的直接用，有的经 [HeroCard] 用）——
 * 所以放在共享文件里，而不是留在某一页当私有函数
 * （搬页的时候会跟着断掉，编译才报）。
 *
 * ⚠ 「通知」页里有个**同名**的 `IconBadge`，签名不同（它收 drawable 资源 id
 * 和选中态），尺寸已统一到 40dp。那个留在通知页私有即可，别把两个合并 —— 它们服务的视觉语义不一样：
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
 * ⚠ [modifier] 是给**整张卡**的。带开关的那张卡要传 `Modifier.toggleable(role = Role.Switch)`：
 *   整行可点既是更好的触控目标，也是读屏唯一能拿到名字的形态 —— 详见 `SwitchRow` 那段说明。
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
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = MaterialTheme.shapes.large,
  ) {
    // 右上角一枚低透明度的大图标当水印：整张卡原来只有"色块 + 一行字"，
    // 加一层同语义的图形就有了纵深（2026-10-26 美观性）。10% 与正文拉开，不抢内容。
    Box(modifier = Modifier.clip(MaterialTheme.shapes.large)) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor.copy(alpha = 0.10f),
        modifier =
          Modifier.size(132.dp)
            .align(Alignment.CenterEnd)
            .offset(x = 30.dp),
      )
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
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
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
}

/**
 * 三个功能页共用的**功能开关卡**（2026-10-26 统一）。
 *
 * 主色 container + 反色图标方块 + 标题 / 开关一行 + **整宽说明** + 同语义水印。
 * 为什么不直接用 [HeroCard]：HeroCard 的正文和标题挤在同一个 Row 里，正文会被右侧开关压窄；
 * 功能说明比模块状态长（单色那条尤其），必须让正文占满整宽。这里保留 HeroCard 的色彩与水印语言，
 * 只把正文放到下一行 —— 三个功能页因此长得一模一样。
 *
 * 语义：整张卡 `toggleable`（读屏只报一站），内部 Switch 只做展示。
 */
@Composable
internal fun FeatureSwitchCard(
  icon: ImageVector,
  title: String,
  body: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val toggle = Modifier.toggleable(
    value = checked,
    role = Role.Switch,
    onValueChange = onCheckedChange,
  )
  OpticonCard(
    modifier = toggle,
    containerColor = scheme.primaryContainer,
    contentColor = scheme.onPrimaryContainer,
    padding = 0.dp,
  ) {
    Box(modifier = Modifier.clip(MaterialTheme.shapes.large)) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = scheme.onPrimaryContainer.copy(alpha = 0.10f),
        modifier =
          Modifier.size(132.dp)
            .align(Alignment.CenterEnd)
            .offset(x = 30.dp),
      )
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconTile(
            icon = icon,
            containerColor = scheme.onPrimaryContainer,
            contentColor = scheme.primaryContainer,
          )
          Spacer(modifier = Modifier.width(14.dp))
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Switch(checked = checked, onCheckedChange = null)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onPrimaryContainer.copy(alpha = 0.82f),
        )
      }
    }
  }
}

/**
 * 功能示意卡中的一格：统一的 68dp 底板 + 居中样本 + 标题/细节两行。
 * 裁圆与单色的「效果」卡共用，保证两页的示意语言一致（2026-10-26）。
 */
@Composable
internal fun SampleTile(
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Surface(
      modifier = Modifier.fillMaxWidth().height(68.dp),
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(1.dp))
    Text(
      text = detail,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

/**
 * 功能示意卡的外框：样本（调用方提供）+ 标题 + 客观说明。
 * 单色 / 通知两页共用，保证卡片内边距与文字层级一致（2026-10-26）。
 */
@Composable
internal fun EffectShowcaseCard(
  title: String,
  body: String,
  samples: @Composable () -> Unit,
) {
  OpticonCard {
    samples()
    Spacer(modifier = Modifier.height(18.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 打开外部链接，**打不开也不崩**。
 *
 * ⚠ 为什么必须包这一层：[androidx.compose.ui.platform.AndroidUriHandler.openUri] 只捕
 * `ActivityNotFoundException`，而且捕到之后**在 catch 块里重新抛一个 `IllegalArgumentException`**
 * —— 本机对着 `ui-android 1.12.1` 的字节码核过（`athrow` 就在异常表 target 里）。
 * 于是"这台设备上没有能打开 https 的应用"（去浏览器化的 ROM、受限的工作资料、模拟器）
 * 会从 `onClick` 一路穿出去**把应用带崩**。对一台装了 Xposed 的机器来说，
 * 为了精简而删掉浏览器的配置并不罕见 —— 本模块的用户画像正好撞在这条上。
 *
 * ⚠ 三个调用点（顶栏那颗源码按钮、「关于」页两块里的按钮）全都得走这里；
 * 新增跳转也一律走这里，别直接调 `uriHandler.openUri`。
 *
 * @return 真的交出去了返回 true；调用方据此决定要不要提示（别静默失败）。
 */
internal fun openExternalUrl(uriHandler: UriHandler, url: String): Boolean =
  runCatching { uriHandler.openUri(url) }.isSuccess

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
