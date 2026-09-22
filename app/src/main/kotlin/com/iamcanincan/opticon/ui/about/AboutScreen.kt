package com.iamcanincan.opticon.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.FloatingNavSpace
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.Section
import com.iamcanincan.opticon.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

/**
 * 「关于」页：检查更新与版本信息。
 *
 * 从「模块」页拆出来的 —— 底栏加到四格之后，「模块」页只留跟"怎么装、怎么验、
 * 怎么修"直接相关的内容（作用域 / 步骤 / 验证 / 运维），
 * 更新与版本这类"看看就好"的信息独立成页。
 */
@Composable
fun AboutScreen() {
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  val version = BuildConfig.VERSION_NAME
  val context = LocalContext.current
  var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

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
  ) {
    item { Section(text = stringResource(R.string.section_update)) {
      UpdateCard(
        version = version,
        state = update,
        onCheck = {
          update = UpdateState.Checking
          scope.launch {
            // 联网不能跑在主线程，扔到 IO 再回来更新界面状态。
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
            update = result.toState(context, version)
          }
        },
        onOpenPage = { url -> uriHandler.openUri(url) },
      )
    } }

    item { Section(text = stringResource(R.string.tab_about)) { AboutCard() } }
  }
}



/** 关于：版本/许可 + 跳转 GitHub 的按钮。 */
@Composable
private fun AboutCard() {
  val uriHandler = LocalUriHandler.current

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(
          icon = Icons.Default.Info,
          containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.about_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
      Button(
        onClick = { uriHandler.openUri(REPO_URL) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
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

private sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data class Done(val message: String, val url: String? = null, val ok: Boolean = true) :
    UpdateState
}


private fun UpdateChecker.Result.toState(ctx: android.content.Context, current: String): UpdateState =
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
  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
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
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.update_current, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.update_disclaimer),
        style = MaterialTheme.typography.bodySmall,
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
