package com.iamcanincan.opticon.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.RepoUrl
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section
import com.iamcanincan.opticon.ui.openExternalUrl
import com.iamcanincan.opticon.update.UpdateChecker

/**
 * 「关于」页：检查更新与版本信息。
 *
 * 从「模块」页拆出来的 —— 底栏加到四格之后，「模块」页只留跟"怎么装、怎么验、
 * 怎么修"直接相关的内容（作用域 / 步骤 / 验证），
 * 更新与版本这类"看看就好"的信息独立成页。
 *
 * ⚠ 更新状态与检查动作都由外壳传进来（见 [UpdateState] 上的说明）—— 这页会被销毁。
 *   [onNotify] 同理：Snackbar 的作用域在外壳，这页只负责在跳转失败时把话递上去。
 */
@Composable
internal fun AboutScreen(state: UpdateState, onCheck: () -> Unit, onNotify: (String) -> Unit) {
  val uriHandler = LocalUriHandler.current
  val version = BuildConfig.VERSION_NAME
  val openFailed = stringResource(R.string.open_link_failed)
  // ⚠ 跳转一律走共享的 [openExternalUrl]：Compose 的 `openUri` 在没有浏览器的设备上会抛
  //   `IllegalArgumentException`（理由见那个函数的说明）—— 直接调就是崩应用。
  val open: (String) -> Unit = { url ->
    if (!openExternalUrl(uriHandler, url)) onNotify(openFailed)
  }

  ScreenList {
    item { Section(text = stringResource(R.string.section_update)) {
      UpdateCard(
        version = version,
        state = state,
        onCheck = onCheck,
        onOpenPage = open,
      )
    } }

    item { Section(text = stringResource(R.string.section_privacy)) { PrivacyCard() } }

    item { Section(text = stringResource(R.string.tab_about)) { AboutCard(onOpen = open) } }
  }
}



/**
 * 隐私说明。
 *
 * 写进界面而不只写在 README 里：用户判断一个 Xposed 模块能不能装，
 * 最关心的就是它联不联网、有没有上报。这句话放在「关于」页比藏在文档里有用。
 */
@Composable
private fun PrivacyCard() {
  val scheme = MaterialTheme.colorScheme
  OpticonCard {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
      IconTile(
        icon = Icons.Default.Lock,
        // 中性底板：琥珀（tertiary）留给"要动手、有代价"的运维操作，
        // 隐私说明是纯信息，不该抢那个颜色（同一套语言见「图标」页）。
        containerColor = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(14.dp))
      Column {
        Text(
          text = stringResource(R.string.privacy_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        Text(
          text = stringResource(R.string.privacy_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 关于：版本/许可 + 跳转 GitHub 的按钮。
 *
 * ⚠ 这里**不重复 App 名**：顶栏已经有它了，每页再来一遍标记 + 版本是重复
 *   （README「界面」一节记的就是这条）。所以这块只放**图标本体 + 版本 + 说明 + 入口**，
 *   内容居中 —— 本页本来就短，居中的一块比左上角一行更能撑住这一页。
 *
 * ⚠ [onOpen] 由页面传进来（内部走 [openExternalUrl]），不在这里直接拿 `LocalUriHandler`：
 *   失败要能弹提示，而这页自己没有 Snackbar 的作用域（那是外壳的）。
 */
@Composable
private fun AboutCard(onOpen: (String) -> Unit) {
  OpticonCard {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // 桌面图标本体（背景 + 前景两个 vector）当品牌标记：与桌面上那个图标同源，
      // 底色取 `@color/opticon_icon_background`（品牌淡粉，全 Android 版本固定不跟随壁纸）。
      // ⚠ 必须带描边：那抹淡粉在**浅色**卡面上几乎与卡同色（#FCE4EC on 近白），
      //   没有这条线就只剩一个悬空的黑字。
      Box(
        modifier =
          Modifier.size(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colorResource(R.color.opticon_icon_background))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
      ) {
        Image(
          painter = painterResource(R.drawable.ic_launcher_foreground),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.about_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(18.dp))
      // 容器色（tonal）而不是实心主色：这一页的主操作是上面「检查更新」那一个，
      // 两个都用实心主色的话，"哪个是重点"在屏幕上读不出来。
      FilledTonalButton(
        onClick = { onOpen(RepoUrl) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.OpenInNew,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.action_open_repo))
      }
    }
  }
}

/**
 * 检查更新的状态机。
 *
 * ⚠ 状态和发起检查的那次 launch 都**归外壳（`OpticonApp`）持有**，不在这里 ——
 * 这页是 `HorizontalPager` 的一页，切走就离开组合，`remember` 会被清掉、
 * `rememberCoroutineScope` 会被取消。放在这里的后果是：切一下页结论就没了，
 * 而且后台那条 HttpURLConnection 的协程被取消后并不会中断，IO 线程照旧白等最长约 40 秒。
 */
internal sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data class Done(val message: String, val url: String? = null, val ok: Boolean = true) :
    UpdateState
}


internal fun UpdateChecker.Result.toState(ctx: android.content.Context, current: String): UpdateState =
  when (this) {
    is UpdateChecker.Result.Newer ->
      UpdateState.Done(ctx.getString(R.string.update_result_newer, version, current), url)
    is UpdateChecker.Result.UpToDate ->
      if (version == current) UpdateState.Done(ctx.getString(R.string.update_result_uptodate, current))
      else UpdateState.Done(ctx.getString(R.string.update_result_uptodate_ahead, current, version))

    // 仓库还没发过 Release：正常状态，不是故障，所以 ok=true（走中性配色）
    UpdateChecker.Result.NoRelease ->
      UpdateState.Done(ctx.getString(R.string.update_result_no_release))

    is UpdateChecker.Result.Failed ->
      UpdateState.Done(ctx.getString(R.string.update_result_failed, reason), ok = false)
  }


/**
 * 更新卡片。
 *
 * 结果做成**常驻**的一块 chip，而不是一闪而过的 Snackbar —— 用户点完要是走神了，
 * 回头还能看见结论；失败时也能分清是没网、被限流还是仓库没发过 Release。
 */
@Composable
private fun UpdateCard(
  version: String,
  state: UpdateState,
  onCheck: () -> Unit,
  onOpenPage: (String) -> Unit,
) {
  OpticonCard {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(
          icon = Icons.Default.Update,
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
          Text(
            text = stringResource(R.string.update_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.update_current, version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.update_disclaimer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val done = state as? UpdateState.Done
      if (done != null) {
        Spacer(modifier = Modifier.height(12.dp))
        ResultChip(done)
      }

      Spacer(modifier = Modifier.height(16.dp))
      val url = done?.url
      Row(verticalAlignment = Alignment.CenterVertically) {
        val checking = state is UpdateState.Checking
        // 单按钮时填满（与 AboutCard 一致）；有「打开发布页」时主按钮按 weight 占满剩余宽度。
        Button(
          onClick = onCheck,
          enabled = !checking,
          modifier =
            if (url == null) Modifier.fillMaxWidth() else Modifier.weight(1f),
          shape = MaterialTheme.shapes.large,
        ) {
          if (checking) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text(
            text =
              if (checking) stringResource(R.string.update_checking)
              else stringResource(R.string.update_title)
          )
        }
        if (url != null) {
          Spacer(modifier = Modifier.width(10.dp))
          OutlinedButton(onClick = { onOpenPage(url) }, shape = MaterialTheme.shapes.large) {
            Text(text = stringResource(R.string.update_go_download))
          }
        }
      }
    }
  }
}


/** 检查结果：成功走 secondaryContainer，失败走 errorContainer，一眼能分清 */
@Composable
private fun ResultChip(done: UpdateState.Done) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color =
      if (done.ok) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.errorContainer,
    contentColor =
      if (done.ok) MaterialTheme.colorScheme.onSecondaryContainer
      else MaterialTheme.colorScheme.onErrorContainer,
  ) {
    Text(
      text = done.message,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
    )
  }
}
