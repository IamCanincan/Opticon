package com.iamcanincan.opticon.ui.notify

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.CommandRow
import com.iamcanincan.opticon.ui.HeroCard
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section

/**
 * 「通知」页。
 *
 * 2026-10-26 按"界面要统一整齐"并回其它三页的脚手架：
 * [ScreenList]（16dp 边距 / 20dp 组距）、[Section] 小标题、[HeroCard] 状态卡。
 * 卡片仍是 [OpticonCard]；左侧图标底板统一 40dp（与共享 `IconTile` 同尺寸），
 * 图标本身保留本页的 drawable（它画的是可选项的语义，和 `IconTile` 不是一回事）。
 *
 * 本页只有一个开关 —— 通知小图标修不修。每次改动立即落盘，
 * 模块侧下次被挂钩时就能读到，不需要点"保存"。
 */
@Composable
fun NotifyScreen(onNotify: (String) -> Unit) {
    val context = LocalContext.current
    // 默认配置由外壳（OpticonApp）在首次打开时落盘，这里只读。
    val prefs = remember { ModulePrefs.of(context) }

    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, ModulePrefs.DEFAULT_NOTIFY_ENABLED))
    }

    ScreenList {
        item { StatusCard(enabled = enabled) }

        item {
            Section(text = stringResource(R.string.notify_section_switch)) {
                // 开关的语义在**整张卡**上（`toggleable` + `Switch(onCheckedChange = null)`）：
                // 名字不要另挂 `contentDescription`，也不要加 `mergeDescendants` —— 真机上那两种写法
                // 都会多出一个同尺寸的第二语义节点（读屏分两站念）。三条做法的实测记录见
                // IconScreen 里 MasterSwitchCard 那段注释。
                OpticonCard(
                    padding = 0.dp,
                    modifier = Modifier.toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = {
                            enabled = it
                            prefs.edit().putBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, it).apply()
                        },
                    )
                ) {
                    SwitchRow(
                        icon = R.drawable.ic_power,
                        title = stringResource(R.string.notify_switch_title),
                        description = stringResource(R.string.notify_switch_desc),
                        checked = enabled,
                    )
                }
            }
        }

        item {
            Section(text = stringResource(R.string.notify_section_scope)) { ScopeCard() }
        }

        item {
            Section(text = stringResource(R.string.notify_section_verify)) { VerifyCard(onNotify) }
        }
    }
}

/**
 * 状态卡：与「图标」「模块」两页开头的 HeroCard 同一形状（图标方块 + 标题 + 一句说明），
 * 只换语义与颜色。2026-10-26 统一整齐：原来这里是本页自己铺的一块 Surface，边距与字号
 * 都和另外两页对不上。
 */
@Composable
private fun StatusCard(enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    HeroCard(
        icon = Icons.Default.Notifications,
        containerColor = if (enabled) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        title = stringResource(if (enabled) R.string.notify_status_on else R.string.notify_status_off),
        body = stringResource(R.string.notify_status_hint),
    )
}

/** 可选项左侧的图标底板。与共享 `IconTile` 同尺寸（40dp），图标本身仍用本页 drawable。 */
private val IconBadgeSize = 40.dp

/**
 * @param selected 选中态：底板走 primary、图标走 onPrimary。
 */
@Composable
private fun IconBadge(
    @DrawableRes icon: Int,
    selected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    // 中性底板与共享 IconTile 同一档（surfaceContainerHighest），打开时才是主色。
    val bg = if (selected) scheme.primary else scheme.surfaceContainerHighest
    Box(
        modifier = Modifier
            .size(IconBadgeSize)
            .clip(MaterialTheme.shapes.small)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    checked: Boolean,
) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 底板跟着开关走：打开时是品牌色，关闭时是中性灰，状态一眼可见
        IconBadge(icon = icon, selected = checked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        // 只做展示：语义（Role.Switch + 标题 + 状态）由外层整张卡承担
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 本页的「说明卡」：图标底板 + 标题 + 一句说明，整张卡只是解释、不能点。
 *
 * 2026-10-26 统一整齐：内边距与其它页的 `OpticonCard` 对齐（卡片本身 0dp、内容 16dp，
 * 避免 16+16 的双层缩进）；底板 40dp、标题 titleMedium 默认字重，与共享 `IconTile` 同尺寸。
 * 本页仍保留自己的 drawable 图标（语义是可选项，不是通用图标）。
 */
@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    footer: (@Composable () -> Unit)? = null,
) {
    OpticonCard(padding = 0.dp) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(
                icon = icon,
                selected = false,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (footer != null) {
                    Spacer(Modifier.height(12.dp))
                    footer()
                }
            }
        }
    }
}

@Composable
private fun ScopeCard() {
    InfoCard(
        icon = R.drawable.ic_scope,
        title = stringResource(R.string.notify_scope_title),
        description = stringResource(R.string.notify_scope_body),
    )
}

/** 怎么确认它真的生效了 —— 通知栏看不出差异时，日志是唯一可靠的判据。 */
@Composable
private fun VerifyCard(onNotify: (String) -> Unit) {
    InfoCard(
        icon = R.drawable.ic_verify,
        title = stringResource(R.string.notify_verify_title),
        description = stringResource(R.string.notify_verify_body)
    ) {
        CommandRow(command = stringResource(R.string.verify_logcat), onNotify = onNotify)
    }
}
