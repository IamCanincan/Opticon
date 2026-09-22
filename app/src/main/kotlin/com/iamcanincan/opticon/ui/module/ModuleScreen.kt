package com.iamcanincan.opticon.ui.module

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.FloatingNavSpace
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.Section
import com.iamcanincan.opticon.ui.SectionLabel
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF
import kotlinx.coroutines.launch

/** 桌面包名（类原生 / Pixel 两套），发广播点名用。 */
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
 * 「模块」页：两个功能共用的东西 —— 作用域、启用步骤、验证方式、运维操作。
 *
 * 为什么单独成页：作用域清单里既有给裁圆用的进程、也有给通知用的（SystemUI），
 * 它是**模块级**的；启用步骤和验证方式同理。塞进「图标」页会让那一页变成
 * 一个什么都有的长列表，「设置」和「说明书」混在一起。
 *
 * 检查更新与版本信息在「关于」页 —— 那类"看看就好"的内容跟这一页的
 * "怎么装、怎么验、怎么修"不是一回事。
 */
@Composable
fun ModuleScreen(snackbarHostState: SnackbarHostState) {
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
    item { StatusCard() }

    item { Section(text = stringResource(R.string.section_scope)) { ScopeGroup() } }

    item { Section(text = stringResource(R.string.section_steps)) { StepsCard() } }

    item { Section(text = stringResource(R.string.section_verify)) { VerifyCard(snackbarHostState) } }

    item { Section(text = stringResource(R.string.section_tools)) { ToolsCard(snackbarHostState) } }
  }
}

/**
 * 状态卡。模块本身是后台模块，没有 API 能读自己在 LSPosed 里的启用状态，
 * 所以这里只说明"去哪里确认"，不假装能显示开关状态。
 */
@Composable
private fun StatusCard() {
  Card(
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    shape = MaterialTheme.shapes.large,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Power,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.status_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.status_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
    }
  }
}

/** 作用域清单：一张卡片里 10 行，每行 = 图标方块 + 标题 + 描述 + 标签。 */
@Composable
private fun ScopeGroup() {
  val scopes =
    listOf(
      ScopeItem(
        Icons.Default.Android,
        R.string.scope_system_title,
        R.string.scope_system_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Memory,
        R.string.scope_systemprocess_title,
        R.string.scope_systemprocess_desc,
        ScopeTag.OPTIONAL,
      ),
      ScopeItem(
        Icons.Default.Apps,
        R.string.scope_launcher_title,
        R.string.scope_launcher_desc,
        ScopeTag.REQUIRED,
      ),
      ScopeItem(
        Icons.Default.Home,
        R.string.scope_launcher_pixel_title,
        R.string.scope_launcher_pixel_desc,
        ScopeTag.REQUIRED,
      ),
      ScopeItem(
        Icons.Default.Dashboard,
        R.string.scope_systemui_title,
        R.string.scope_systemui_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Settings,
        R.string.scope_settings_title,
        R.string.scope_settings_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Search,
        R.string.scope_settings_intelligence_title,
        R.string.scope_settings_intelligence_desc,
        ScopeTag.OPTIONAL,
      ),
      ScopeItem(
        Icons.Default.Share,
        R.string.scope_intentresolver_title,
        R.string.scope_intentresolver_desc,
        ScopeTag.OPTIONAL,
      ),
      ScopeItem(
        Icons.Default.Security,
        R.string.scope_permissioncontroller_title,
        R.string.scope_permissioncontroller_desc,
        ScopeTag.OPTIONAL,
      ),
      ScopeItem(
        Icons.Default.Schedule,
        R.string.scope_wellbeing_title,
        R.string.scope_wellbeing_desc,
        ScopeTag.OPTIONAL,
      ),
    )

  // 10 行铺满整页，真正要看的（必选 + 推荐）反而不显眼。
  // 默认只展开这两档，"可选"折叠成一行按钮——它是"想要更全"时才翻的。
  var expanded by remember { mutableStateOf(false) }
  val visible = if (expanded) scopes else scopes.filter { it.tag != ScopeTag.OPTIONAL }

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column {
      Text(
        text = stringResource(R.string.scope_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      HorizontalDivider()
      visible.forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        ScopeRow(item)
      }
      HorizontalDivider()
      // 用**左右分段按钮**而不是一个"展开 / 收起"的文字按钮：
      // 这两个视图是**并列的两档**（常用 / 全部），分段控件一看就知道能左右切；
      // 文字按钮的文案还会随状态变（"展开其余 5 项" ↔ "收起"），不如两档常驻清楚。
      SingleChoiceSegmentedButtonRow(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
      ) {
        SegmentedButton(
          selected = !expanded,
          onClick = { expanded = false },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          label = { Text(stringResource(R.string.scope_segment_essential)) },
        )
        SegmentedButton(
          selected = expanded,
          onClick = { expanded = true },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          label = { Text(stringResource(R.string.scope_segment_all)) },
        )
      }
    }
  }
}

/**
 * 作用域的档位。多个作用域共享同一档位 —— 每种标签只在 strings.xml 里定义一次，
 * 不重复（否则 lint 的 DuplicateStrings 会报）。
 */
private enum class ScopeTag(val labelRes: Int, val highlighted: Boolean) {
  REQUIRED(R.string.scope_tag_required, highlighted = true),
  RECOMMENDED(R.string.scope_tag_recommended, highlighted = false),
  OPTIONAL(R.string.scope_tag_optional, highlighted = false),
}

private data class ScopeItem(
  val icon: ImageVector,
  val titleRes: Int,
  val descRes: Int,
  val tag: ScopeTag,
)

@Composable
private fun ScopeRow(item: ScopeItem) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconTile(
      icon = item.icon,
      // "必选"用主色底强调，其余用中性 surface 底
      containerColor =
        if (item.tag.highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor =
        if (item.tag.highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(item.titleRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = stringResource(item.descRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Surface(
      shape = MaterialTheme.shapes.extraSmall,
      color =
        if (item.tag.highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      Text(
        text = stringResource(item.tag.labelRes),
        style = MaterialTheme.typography.labelSmall,
        color =
          if (item.tag.highlighted) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}

/** 启用步骤：标题行 + 4 个步骤（序号 + 主文案 + 小字提示）。 */
@Composable
private fun StepsCard() {
  val steps =
    listOf(
      R.string.step_1 to R.string.step_1_hint,
      R.string.step_2 to R.string.step_2_hint,
      R.string.step_3 to R.string.step_3_hint,
      R.string.step_4 to R.string.step_4_hint,
    )

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column {
      // 「启用步骤」已经在外层 SectionLabel 里写了，卡内不再重复。
      steps.forEachIndexed { index, (titleRes, hintRes) ->
        if (index > 0) HorizontalDivider()
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
          Surface(
            shape = MaterialTheme.shapes.extraSmall,
            // 步骤是有顺序的：用主色底把它和"并列关系的列表"区分开。
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = stringResource(hintRes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** 验证卡：等宽字体的 logcat 命令 + 一键复制。 */
@Composable
private fun VerifyCard(snackbarHostState: SnackbarHostState) {
  val command = stringResource(R.string.verify_logcat)
  val copiedMessage = stringResource(R.string.verify_copied)
  // 用 LocalClipboard 而不是已废弃的 LocalClipboardManager：新 API 的写入是 suspend 的，
  // 所以要放进协程里；顺带把「复制成功」的提示也放进去，保证它一定在写入之后才弹。
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      // 「验证是否生效」已经在外层 SectionLabel 里写了。
      Text(
        text = stringResource(R.string.verify_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(12.dp))
      // 整块可点：原来只有右侧一个 48dp 的图标按钮能按，命中面积太小。
      Surface(
        onClick = {
          scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, command)))
            snackbarHostState.showSnackbar(copiedMessage)
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
