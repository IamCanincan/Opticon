package com.iamcanincan.opticon.ui.icon

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

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
 * 「图标」页的内容：圆形图标的总开关、作用域清单、启用步骤、验证与运维。
 *
 * Scaffold / 顶栏 / 底部导航由外层 `OpticonApp` 统一提供，这里只出列表内容 ——
 * 两个 Tab 共用同一个外壳，才有统一的滚动行为和同一个 snackbar。
 *
 * 顶栏与底部导航让出的空间由外壳扣掉（`OpticonApp` 里的 `Modifier.padding(inner)`），
 * 这里只管列表自己的边距。
 */
@Composable
fun IconScreen(snackbarHostState: SnackbarHostState) {
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  val version = BuildConfig.VERSION_NAME
  val context = LocalContext.current
  var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
    // 组与组之间 20dp；小标题与它自己的卡片之间只有 10dp（见 [Section]），
    // 这样「标题领着哪张卡」一眼能看出来。
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    item { HeroHeader() }
    item { MasterSwitchCard() }
    item { StatusCard() }

    item { Section(text = stringResource(R.string.section_scope)) { ScopeGroup() } }

    item { Section(text = stringResource(R.string.section_steps)) { StepsCard() } }

    item { Section(text = stringResource(R.string.section_verify)) { VerifyCard(snackbarHostState) } }

    item { Section(text = stringResource(R.string.section_tools)) { ToolsCard(snackbarHostState) } }

    item { Section(text = stringResource(R.string.section_notes)) { NotesCard() } }

    item {
      Section(text = stringResource(R.string.section_update)) {
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
      }
    }

    item { Section(text = stringResource(R.string.section_about)) { AboutCard() } }
  }
}

/** 小标题 + 它领的那张卡。分成一个 item，才能把组内间距压到比组间距小。 */
@Composable
private fun Section(text: String, content: @Composable () -> Unit) {
  Column {
    SectionLabel(text = text)
    Spacer(modifier = Modifier.height(10.dp))
    content()
  }
}

/**
 * 首屏色块：只放「品牌标记方块 + 一句话说明 + 版本 chip」。
 * App 名已经常驻在顶栏里，这里再写一遍纯属重复，所以让位给副标题。
 */
@Composable
private fun HeroHeader() {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      // 在 primaryContainer 背景上，品牌标记方块用 onPrimaryContainer 反色，更突出。
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.hero_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        VersionChip()
      }
    }
  }
}

/** Hero 内部的版本 chip：在 primaryContainer 上用 onPrimaryContainer 的低 alpha。 */
@Composable
private fun VersionChip() {
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
        shape = RoundedCornerShape(12.dp),
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
        shape = RoundedCornerShape(12.dp),
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

/** 分组小标题（小字、次要色、带左内边距）。 */
@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp),
  )
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
  val hiddenCount = scopes.size - visible.size

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
      TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            if (expanded) stringResource(R.string.scope_show_less)
            else stringResource(R.string.scope_show_all, hiddenCount)
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
    IconBadge(
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

/** 卡片左侧的图标方块：圆角方块 + 居中图标。 */
@Composable
private fun IconBadge(
  icon: ImageVector,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  Surface(shape = RoundedCornerShape(12.dp), color = containerColor, modifier = Modifier.size(40.dp)) {
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
  val clipboard = LocalClipboardManager.current
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
          clipboard.setText(AnnotatedString(command))
          scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
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
          shape = RoundedCornerShape(12.dp),
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

/** 说明要点：带圆点的列表。 */
@Composable
private fun NotesCard() {
  val notes =
    listOf(
      R.string.note_adaptive,
      R.string.note_tiles,
      R.string.note_boot,
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

/** 关于：版本/许可 + 跳转 GitHub 的按钮。 */
@Composable
private fun AboutCard() {
  val uriHandler = LocalUriHandler.current

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(
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
        IconBadge(
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
