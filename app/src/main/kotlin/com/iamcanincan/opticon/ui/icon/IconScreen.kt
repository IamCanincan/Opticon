package com.iamcanincan.opticon.ui.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.FloatingNavSpace
import com.iamcanincan.opticon.ui.Section
import com.iamcanincan.opticon.ui.SectionLabel
import com.iamcanincan.opticon.runtime.ModulePrefs
import android.content.Intent
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF
import kotlinx.coroutines.launch

private const val LAUNCHER_AOSP = "com.android.launcher3"
private const val LAUNCHER_PIXEL = "com.google.android.apps.nexuslauncher"
private const val SYSTEMUI = "com.android.systemui"

/** 「重启其他」覆盖的进程：都是会显示别的应用图标、杀掉后系统会自动拉起的 UI 进程。 */
private val OTHER_RESTARTABLE =
  listOf(
    "com.android.settings",
    "com.google.android.settings.intelligence",
    "com.android.intentresolver",
    "com.android.permissioncontroller",
  )

/**
 * 「图标」页：只管裁圆这件事 —— 总开关，以及会影响它的那些系统行为说明。
 *
 * 作用域清单 / 启用步骤 / 验证 / 运维 / 检查更新 / 关于都是**模块级**内容
 * （跟「图标」无关，通知那一半也要用），已经移到「模块」页，见
 * [com.iamcanincan.opticon.ui.module.ModuleScreen]。
 *
 * 顶栏与底部导航让出的空间由外壳扣掉（`OpticonApp` 里的 `Modifier.padding(inner)`），
 * 这里只管列表自己的边距。
 */
@Composable
fun IconScreen(snackbarHostState: SnackbarHostState) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = 16.dp,
      end = 16.dp,
      top = 4.dp,
      // 末尾让出悬浮药丸那一段，否则滚到底时最后一张卡会被它压住
      bottom = 24.dp + FloatingNavSpace,
    ),
    // 组与组之间 20dp；小标题与它自己的卡片之间只有 10dp（见 [Section]），
    // 这样「标题领着哪张卡」一眼能看出来。
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    item { MasterSwitchCard() }
    item { Section(text = stringResource(R.string.section_effect)) { EffectCard() } }
    item { Section(text = stringResource(R.string.section_tools)) { ToolsCard(snackbarHostState) } }

    item { Section(text = stringResource(R.string.section_notes)) { NotesCard() } }
  }
}



/**
 * 圆形图标的总开关。
 *
 * 写进 SharedPreferences 就完事 —— 模块侧按 TTL 自己重读（见 `ModuleRuntime`），
 * 不需要广播、也不需要重启进程。
 *
 * ⚠ 关掉之后**已经画在桌面上的图标不会立刻变回去**：桌面有图标缓存，
 * 要等缓存重建（用下面「运维」里的清缓存按钮，或重启桌面）。
 */
@Composable
private fun MasterSwitchCard() {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var enabled by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, true))
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = MaterialTheme.shapes.large,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.circle_switch_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.circle_switch_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Switch(
        checked = enabled,
        onCheckedChange = { value ->
          enabled = value
          prefs.edit().putBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, value).apply()
        },
      )
    }
  }
}


/**
 * 「效果」卡：一张形状示意图 + 一句覆盖范围说明。
 *
 * 补这张卡是因为图标页原本只有「开关 + 说明」两块，首屏下半截全是空的；
 * 而且「改完之后到底变成什么样」光靠文字不好讲 —— 画出来一目了然。
 * 表达方式与「通知」页的模式预览一致（处理前 → 处理后）。
 *
 * ⚠ 两个色块只是**示意图**，不是真实图标渲染：左边用中性底表示「系统给的形状」
 * （圆角方 / 水滴 / 方都可能是它），右边用主色圆表示裁完的结果。
 */
@Composable
private fun EffectCard() {
  val scheme = MaterialTheme.colorScheme
  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 处理前：圆角方 —— 代表「ROM 决定的那个形状」。
      // ⚠ 这里的圆角是**画出来的图形**、不是卡片圆角档位，所以刻意写死：
      //   它要看起来像"某个 ROM 给的图标形状"，跟着主题 shapes 走反而失去这个意思。
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(RoundedCornerShape(13.dp))
          .background(scheme.surfaceContainerHighest)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = "→",
        style = MaterialTheme.typography.titleMedium,
        color = scheme.outline,
      )
      Spacer(modifier = Modifier.width(10.dp))
      // 处理后：正圆，用主色把它和左边明显区分开
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(scheme.primary)
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.effect_title),
          style = MaterialTheme.typography.titleSmall,
          color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.effect_body),
          style = MaterialTheme.typography.bodySmall,
          color = scheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** 说明要点：带圆点的列表。 */
@Composable
private fun NotesCard() {
  // 只放**裁圆自己**的注意事项。模块级的那些（框架 daemon 启动时机之类）
  // 属于「模块」页的启用步骤，别在这里再抄一遍。
  val notes =
    listOf(
      R.string.note_adaptive,
      R.string.note_tiles,
      R.string.note_launcher3,
      R.string.note_themed_icon,
    )

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)) {
      // 「说明」已经在外层 SectionLabel 里写了。
      notes.forEach { res ->
        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
          // Surface 小圆点比 "•" 字符精致：在浅色下是一个清晰可见的小点，
          // 在深色下用 onSurfaceVariant 的 alpha 自动变得柔和。
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 7.dp, end = 12.dp).size(6.dp),
          ) {}
          Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}


/**
 * 运维操作卡：清缓存 / 重启 SystemUI / 重启其他进程。
 *
 * 删除和重启都由**目标进程里的模块**执行 —— App 这里只是发一条广播点名，
 * 所以不需要 root，也不会执行任何 shell 命令。
 */
@Composable
private fun ToolsCard(snackbarHostState: SnackbarHostState) {
  val context = LocalContext.current
  val cacheSent = stringResource(R.string.cache_sent)
  val restartSent = stringResource(R.string.restart_sent)
  val scope = rememberCoroutineScope()

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.tertiaryContainer,
          modifier = Modifier.size(40.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.CleaningServices,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.size(22.dp),
            )
          }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
          text = stringResource(R.string.cache_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.cache_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(12.dp))
      Button(
        onClick = {
          // 桌面进程里注册的是动态接收器，隐式广播送得到；
          // 再显式点两个已知桌面的包名，双保险（重复送达无害：第二次 DB 已经不存在了）。
          runCatching {
            context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE))
            context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE).setPackage(LAUNCHER_AOSP))
            context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE).setPackage(LAUNCHER_PIXEL))
          }
          scope.launch { snackbarHostState.showSnackbar(cacheSent) }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
      ) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.action_clear_launcher))
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = stringResource(R.string.tools_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(12.dp))
      Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
          onClick = {
            runCatching {
              context.sendBroadcast(Intent(ACTION_RESTART_SELF).setPackage(SYSTEMUI))
            }
            scope.launch { snackbarHostState.showSnackbar(restartSent) }
          },
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.large,
        ) {
          Text(text = stringResource(R.string.action_restart_systemui))
        }
        Spacer(modifier = Modifier.width(10.dp))
        OutlinedButton(
          onClick = {
            // 其余会画别的应用图标的进程。杀掉后系统会把它们拉起来，下次用到时重新注入。
            runCatching {
              for (pkg in OTHER_RESTARTABLE) {
                context.sendBroadcast(Intent(ACTION_RESTART_SELF).setPackage(pkg))
              }
            }
            scope.launch { snackbarHostState.showSnackbar(restartSent) }
          },
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.large,
        ) {
          Text(text = stringResource(R.string.action_restart_others))
        }
      }

      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = stringResource(R.string.tools_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
