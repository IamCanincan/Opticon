package com.iamcanincan.opticon.ui.icon

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.circle.ACTION_RESTART_SELF
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.HeroCard
import com.iamcanincan.opticon.ui.IconTile
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section

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
 * 「图标」页：这台手机**桌面图标**上的两件事 —— 裁圆，以及未适配图标的单色层合成 ——
 * 各自的开关，加上会影响它们的系统行为说明。
 *
 * 作用域清单 / 启用步骤 / 验证都是**模块级**内容（跟「图标」无关，通知那一半也要用），
 * 已经移到「模块」页，见 [com.iamcanincan.opticon.ui.module.ModuleScreen]。
 *
 * 顶栏与底部导航让出的空间由外壳扣掉（`OpticonApp` 里的 `Modifier.padding(inner)`），
 * 这里只管列表自己的边距 —— 三页共用同一份脚手架，见 [ScreenList]。
 *
 * ⚠ [onNotify] 由外壳提供、走外壳的协程作用域：这页是 Pager 的一页，切走就被销毁，
 * 用页面自己的 scope 发 Snackbar 会在切页那一刻被取消掉。
 */
@Composable
fun IconScreen(onNotify: (String) -> Unit) {
  ScreenList {
    item { MasterSwitchCard() }
    item { Section(text = stringResource(R.string.section_effect)) { EffectCard() } }
    item { Section(text = stringResource(R.string.section_mono)) { MonoInvertCard() } }
    item { Section(text = stringResource(R.string.section_tools)) { ToolsCard(onNotify) } }

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
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, ModulePrefs.DEFAULT_CIRCLE_ENABLED))
  }

  val title = stringResource(R.string.circle_switch_title)

  // ⚠ 开关的语义：**整张卡 `toggleable`、`Switch` 只做展示**（`onCheckedChange = null`）。
  //   顺带把触控目标从 52dp 的开关扩到整张卡。
  //
  //   2026-09-30 真机逐项核对了语义树，记下三条试过的做法，别再走回头路：
  //   ① `contentDescription` 挂在 `Switch` 上 → 它落到可勾选节点的**子节点**上，
  //      读屏分两站念，而"开关，已开启"那一站没有名字；
  //   ② 再加 `mergeDescendants = true` → Compose 会**另建一个同 bounds 的节点**承载那个
  //      描述（实测：checkable 的节点 desc 仍为空，旁边多出一个 `d='强制圆形图标'` 的同尺寸
  //      节点）—— 等于把①的毛病换了个位置，还是两站；
  //   ③ 现在这样（只 `toggleable`）→ 一个可勾选节点盖住整张卡、状态是 `checked`、
  //      角色 `Role.Switch`，标题与说明是它的非聚焦子节点（`focusable=false`，不会多出停顿）。
  //      读屏念这一站时会带上子树里的标题与说明，这就是我们要的形态。
  val toggle = Modifier.toggleable(
    value = enabled,
    role = Role.Switch,
    onValueChange = { value ->
      enabled = value
      prefs.edit().putBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, value).apply()
    },
  )

  HeroCard(
    icon = Icons.Default.Circle,
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    title = title,
    body = stringResource(R.string.circle_switch_body),
    modifier = toggle,
    trailing = { Switch(checked = enabled, onCheckedChange = null) },
  )
}


/**
 * 主题图标的极性修正开关。
 *
 * Android 16「强制动态取色」下，桌面会给**没适配单色图标**的应用现场生成一张单色遮罩
 * （`MonochromeIconFactory`），而它的算法是**亮度当 alpha** —— 假定「亮的就是墨」。
 * 深色字形 + 浅色底的图标因此变成「字形是洞、底色是形」，看着整个反掉。
 *
 * 模块的做法是**不给它出遮罩的机会**：钩 `getMonochrome()`，未适配的应用由本模块现场合成一张
 * 极性正确的剪影交上去，桌面那条"亮度当 alpha"的路径根本不进。极性的判据与判据台跑的是同一份
 * `MonochromeMask`，界面上不再多给一个"强度"之类的旋钮 —— 那是口味，不是这里要答的问题。
 *
 * 已经自己提供了 monochrome 层的应用**不会走到这一步**（桌面只在取不到单色层时才 new
 * 那个 Factory；我们的钩子取到非 null 就直接透传，一个像素都不碰），所以这个开关只管
 * 未适配的那批图标。
 *
 * 关掉它是逃生口，不是「看个人口味」：**整条合成就停**，桌面回到自己那套算法。只有某个 ROM / 桌面版本上实测
 * **不该动**（厂商已经修过极性，或图标包本身就是反着出的）时才需要关。
 * 判定在桌面进程里按 TTL 重读配置，切开关不用重启进程 ——
 * 但已经缓存下来的图标要等重建，用下面「运维」里的清缓存按钮。
 */
@Composable
private fun MonoInvertCard() {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var enabled by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_MONO_INVERT, ModulePrefs.DEFAULT_MONO_INVERT))
  }

  // 同上：标题既当卡上的字，也当开关的读屏名字（整行 toggleable，见 MasterSwitchCard 那段）
  val title = stringResource(R.string.mono_switch_title)

  val toggle = Modifier.toggleable(
    value = enabled,
    role = Role.Switch,
    onValueChange = { value ->
      enabled = value
      prefs.edit().putBoolean(ModulePrefs.KEY_MONO_INVERT, value).apply()
    },
  )

  // ⚠ 开关放**标题那一行**，正文另起一行占满整宽。
  //   原来三样东西挤在同一个 Row 里：开关挂在长正文右侧、垂直居中，于是正文被压成一条窄栏、
  //   开关孤零零浮在半空 —— 真机截图里这是最像"没做完"的一张卡（2026-09-30 复核）。
  OpticonCard(modifier = toggle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconTile(
        icon = Icons.Default.InvertColors,
        // 功能开关卡统一用主色底板（与「通知」页那个开关、模块页的「必选」同一套语言）：
        // 灰底板留给纯说明性的卡，琥珀（tertiary）只留给"要动手、有代价"的运维操作。
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Switch(checked = enabled, onCheckedChange = null)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.mono_switch_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 「效果」卡：一张形状示意图 + 一句覆盖范围说明。
 *
 * 补这张卡是因为图标页原本只有「开关 + 说明」两块，首屏下半截全是空的；
 * 而且「改完之后到底变成什么样」光靠文字不好讲 —— 画出来一目了然。
 *
 * ⚠ 两个色块只是**示意图**，不是真实图标渲染：左边用中性底表示「系统给的形状」
 * （圆角方 / 水滴 / 方都可能是它），右边用主色圆表示裁完的结果。
 *
 * 2026-09-30 改版：原来左边那块**没有描边**，深色下就是一个和卡面几乎同色的方块，
 * 看着像"破了个洞"；箭头是一个裸 `Text("→")`（字号不跟主题、读屏还会把它念出来），
 * 两块下面也没有说明谁是谁。现在左块加 1dp 描边、箭头换成装饰性图标、
 * 两块各带一行小字（原样 / 裁圆），说明文字移到示意图**下方**占满整宽。
 */
@Composable
private fun EffectCard() {
  val scheme = MaterialTheme.colorScheme
  OpticonCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      ShapeSample(
        label = stringResource(R.string.effect_caption_before),
        fill = scheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
        border = scheme.outlineVariant,
      )
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = scheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp).size(20.dp),
      )
      ShapeSample(
        label = stringResource(R.string.effect_caption_after),
        fill = scheme.primary,
        shape = CircleShape,
        border = null,
      )
    }
    Spacer(modifier = Modifier.height(14.dp))
    // 第二行：说明里写的两档例外也要看得见（2026-10-26）。样本比主图小一号，
    // 白盘用真白色 + outlineVariant 描边（白纸上不描边就看不见），内容块用主色示意。
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
    ) {
      ExceptionSample(
        kind = ExceptionKind.WIDE,
        label = stringResource(R.string.effect_caption_shrink),
      )
      ExceptionSample(
        kind = ExceptionKind.GLYPH,
        label = stringResource(R.string.effect_caption_shortcut),
      )
    }
    Spacer(modifier = Modifier.height(18.dp))
    Text(
      text = stringResource(R.string.effect_title),
      style = MaterialTheme.typography.titleMedium,
      color = scheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.effect_body),
      style = MaterialTheme.typography.bodyMedium,
      color = scheme.onSurfaceVariant,
    )
  }
}

/**
 * 效果示意图里的一个样本：一块形状 + 它下面那行小字。
 *
 * ⚠ 圆角是**画出来的图形**、不是卡片的圆角档位，所以调用方直接传具体形状
 * （`RoundedCornerShape(16.dp)` 表示"某个 ROM 给的圆角方"）——跟着 `shapes` 档位走
 * 反而失去这个意思。
 */
@Composable
private fun ShapeSample(label: String, fill: Color, shape: Shape, border: Color?) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier =
        Modifier.size(56.dp)
          .clip(shape)
          .background(fill)
          .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 效果示意图第二行：两档"不按满幅裁"的样子 —— 白盘 + 内容。 */
private enum class ExceptionKind { WIDE, GLYPH }

@Composable
private fun ExceptionSample(kind: ExceptionKind, label: String) {
  val scheme = MaterialTheme.colorScheme
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier =
        Modifier.size(44.dp)
          .clip(CircleShape)
          .background(Color.White)
          .border(1.dp, scheme.outlineVariant, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      // 左：宽幅内容缩进后仍是一条横块；右：透明底快捷方式里的小图案，按原大小放。
      Box(
        modifier =
          (if (kind == ExceptionKind.WIDE) Modifier.size(width = 26.dp, height = 11.dp)
           else Modifier.size(14.dp))
            .clip(if (kind == ExceptionKind.WIDE) RoundedCornerShape(3.dp) else RoundedCornerShape(4.dp))
            .background(scheme.primary)
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = scheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 2,
    )
  }
}

/** 说明要点：带圆点的列表。 */
@Composable
private fun NotesCard() {
  // 只放**这一页那两个功能自己**（裁圆 / 单色层）的注意事项。模块级的那些（框架 daemon 启动时机之类）
  // 属于「模块」页的启用步骤，别在这里再抄一遍。
  val notes =
    listOf(
      R.string.note_adaptive,
      R.string.note_tiles,
      R.string.note_mono_cache,
      R.string.note_launcher3,
      R.string.note_themed_icon,
    )

  OpticonCard {
    Column {
      // 「说明」已经在外层 SectionLabel 里写了。
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
 * 运维操作卡：清缓存 / 重启 SystemUI / 重启其他进程。
 *
 * 删除和重启都由**目标进程里的模块**执行 —— App 这里只是发一条广播点名，
 * 所以不需要 root，也不会执行任何 shell 命令。
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
    //   另外两个是"出问题时才点"的，用描边按钮。原来三个里有一个实心主色、两个描边，
    //   而最响的那个还夹在两句说明中间 —— 重点和从属关系在屏幕上读不出来。
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
