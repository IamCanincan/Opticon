package com.iamcanincan.opticon.ui.module

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF
import com.iamcanincan.opticon.ui.CommandRow
import com.iamcanincan.opticon.ui.HeroCard
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section
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
 * 「模块」页：三个功能共用的东西 —— 作用域、启用步骤、验证方式、运维操作。
 *
 * 为什么单独成页：作用域清单里既有给图标那两路用的进程、也有给通知用的（SystemUI），
 * 它是**模块级**的；启用步骤和验证方式同理。塞进「图标」页会让那一页变成
 * 一个什么都有的长列表，「设置」和「说明书」混在一起。
 *
 * 检查更新与版本信息在「关于」页 —— 那类"看看就好"的内容跟这一页的
 * "怎么装、怎么验、怎么修"不是一回事。
 */
@Composable
fun ModuleScreen(onNotify: (String) -> Unit) {
  ScreenList {
    item { StatusCard() }

    item { Section(text = stringResource(R.string.section_scope)) { ScopeGroup(onNotify) } }

    item { Section(text = stringResource(R.string.section_steps)) { StepsCard() } }

    item { Section(text = stringResource(R.string.section_verify)) { VerifyCard(onNotify) } }

    // 2026-10-26 三个功能分页：清缓存 / 重启这些跨功能操作从「裁圆」页搬到模块页。
    item { Section(text = stringResource(R.string.section_tools)) { ToolsCard(onNotify) } }
  }
}

/**
 * 状态卡。模块本身是后台模块，没有 API 能读自己在 LSPosed 里的启用状态，
 * 所以这里只说明"去哪里确认"，不假装能显示开关状态。
 */
@Composable
private fun StatusCard() {
  HeroCard(
    icon = Icons.Default.Power,
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    title = stringResource(R.string.status_title),
    body = stringResource(R.string.status_body),
  )
}

/** 作用域清单：一张卡片里 10 行，每行 = 图标方块 + 标题 + 描述 + 标签。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeGroup(onNotify: (String) -> Unit) {
  val scopes =
    listOf(
      ScopeItem(
        Icons.Default.Android,
        R.string.scope_system_title,
        R.string.scope_system_desc,
        ScopeTag.RECOMMENDED,
        copyText = "android",
      ),
      ScopeItem(
        Icons.Default.Memory,
        R.string.scope_systemprocess_title,
        R.string.scope_systemprocess_desc,
        ScopeTag.OPTIONAL,
        copyText = "system",
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
  //
  // ⚠ 用 `rememberSaveable` 而不是 `remember`：这一档**没有落盘的地方**（不像那几个开关能
  //   回头从 SharedPreferences 读回来），而 Pager 会销毁离屏的页（见 OpticonApp 的说明），
  //   所以 `remember` 会让"切走再切回来"和转屏把它静默地弹回「必选 + 推荐」——
  //   用户刚点过「全部」，回来发现又折起来了。
  var expanded by rememberSaveable { mutableStateOf(false) }
  val visible = if (expanded) scopes else scopes.filter { it.tag != ScopeTag.OPTIONAL }

  // ⚠ 展开 / 收起要**跟着主题动**：README「界面」一节把「列表展开」也算在 `motionScheme` 里，
  //   而这里原来是一帧跳过去的（`visible` 直接换列表）。`animateContentSize` 让卡片高度
  //   按 Expressive 的弹簧参数长出来 —— 参数取自主题，不写死时长。
  OpticonCard(padding = 0.dp) {
    Column(
      modifier =
        Modifier.animateContentSize(
          animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
        )
    ) {
      Text(
        text = stringResource(R.string.scope_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      )
      HorizontalDivider()
      visible.forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        ScopeRow(item, onNotify)
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
  /** 复制到剪贴板的包名；null = 标题本身就是包名。system_server 那行标题带说明，需单独给。 */
  val copyText: String? = null,
)

@Composable
private fun ScopeRow(item: ScopeItem, onNotify: (String) -> Unit) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val copyLabel = stringResource(R.string.scope_copy)
  val pkg = item.copyText ?: stringResource(item.titleRes)
  val copied = stringResource(R.string.scope_copied, pkg)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = copyLabel) {
        scope.launch {
          clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, pkg)))
          onNotify(copied)
        }
      }
      .padding(horizontal = 16.dp, vertical = 12.dp),
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
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(item.titleRes),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 整行可点复制包名；这枚小图标只作提示，语义由 row 的 onClickLabel 承担。
        Icon(
          imageVector = Icons.Default.ContentCopy,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(14.dp),
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = stringResource(item.descRes),
        style = MaterialTheme.typography.bodyMedium,
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

  OpticonCard(padding = 0.dp) {
    Column {
      // 「启用步骤」已经在外层 SectionLabel 里写了，卡内不再重复。
      steps.forEachIndexed { index, (titleRes, hintRes) ->
        if (index > 0) HorizontalDivider()
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
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

/**
 * 验证卡：说明 + 共享的等宽命令块（[CommandRow]，通知页用的是同一块）。
 *
 * ⚠ [onNotify] 走外壳的作用域 —— Snackbar 要挂满整个展示时长，而页面切走就被销毁。
 */
@Composable
private fun VerifyCard(onNotify: (String) -> Unit) {
  val command = stringResource(R.string.verify_logcat)

  OpticonCard {
    Column {
      // 「验证是否生效」已经在外层 SectionLabel 里写了。
      Text(
        text = stringResource(R.string.verify_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(12.dp))
      CommandRow(command = command, onNotify = onNotify)
    }
  }
}

/**
 * 运维操作卡：清缓存 / 重启状态栏 / 重启设置等进程。
 *
 * 2026-10-26 三个功能分页：这些操作跨所有功能（清的是桌面那份渲染缓存），
 * 从「裁圆」页搬到「模块」页。删除和重启都由**目标进程里的模块**执行 ——
 * App 这里只是发一条广播点名，所以不需要 root，也不会执行任何 shell 命令。
 */
@Composable
private fun ToolsCard(onNotify: (String) -> Unit) {
  val context = LocalContext.current
  val cacheSent = stringResource(R.string.cache_sent)
  val restartSent = stringResource(R.string.restart_sent)

  OpticonCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconTile(
        icon = Icons.Default.CleaningServices,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = stringResource(R.string.cache_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.cache_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.tools_body),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))
    // ⚠ 三个操作**只有一个是常规要用的**（清缓存重启桌面），它拿容器色（tonal）；
    //   另外两个是"出问题时才点"的，用描边按钮。
    FilledTonalButton(
      onClick = {
        // 桌面进程里注册的是动态接收器，隐式广播送得到；
        // 再显式点两个已知桌面的包名，双保险（重复送达无害：第二次 DB 已经不存在了）。
        runCatching {
          context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE))
          context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE).setPackage(LAUNCHER_AOSP))
          context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE).setPackage(LAUNCHER_PIXEL))
        }
        onNotify(cacheSent)
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

    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = {
          runCatching {
            context.sendBroadcast(Intent(ACTION_RESTART_SELF).setPackage(SYSTEMUI))
          }
          onNotify(restartSent)
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
          onNotify(restartSent)
        },
        modifier = Modifier.weight(1f),
        shape = MaterialTheme.shapes.large,
      ) {
        Text(text = stringResource(R.string.action_restart_others))
      }
    }

    Spacer(modifier = Modifier.height(14.dp))
    Text(
      text = stringResource(R.string.tools_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
