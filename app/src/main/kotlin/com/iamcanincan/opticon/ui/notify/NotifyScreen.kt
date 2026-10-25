package com.iamcanincan.opticon.ui.notify

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.CommandRow
import com.iamcanincan.opticon.ui.FloatingNavSpace
import com.iamcanincan.opticon.ui.OpticonCard

/**
 * 「通知」页。
 *
 * 视觉约定（改样式时照着来，别再各写各的）：
 * - 卡片一律走共享的 [OpticonCard]（`MaterialTheme.shapes.large` 圆角 + 1dp
 *   `outlineVariant` 描边 + 一档底色）—— 「24dp 圆角 / 背景 surface」那句话是旧口径，
 *   当时没有任何一张卡真的实现过它（2026-09-30 统一，见 [OpticonCard] 的说明）。
 *   需要突出的只有「状态条」这类，它仍然用 container 色自己铺一块。
 * - **本页不设区块小标题**（状态条 + 开关卡 + 两张说明卡就是全部），所以共享的
 *   `ui.SectionLabel` 在这里用不上。这里曾经留过一份**私有同名副本**、且从没被调用过
 *   （2026-09-30 删除）—— 别再造第二份。每条可选项左侧的 44dp 图标底板是 [IconBadge]。
 * - 本页只有一个开关 —— 通知小图标修不修。每次改动立即落盘，
 *   模块侧下次被挂钩时就能读到，不需要点"保存"。
 */
@Composable
fun NotifyScreen(onNotify: (String) -> Unit) {
    val context = LocalContext.current
    // 默认配置由外壳（OpticonApp）在首次打开时落盘，这里只读。
    val prefs = remember { ModulePrefs.of(context) }

    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, ModulePrefs.DEFAULT_NOTIFY_ENABLED))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 末尾让出悬浮药丸那一段，否则滚到底时最后一张卡会被它压住
            .padding(top = 12.dp, bottom = 36.dp + FloatingNavSpace),
        // 与另外三页的 ScreenList 统一成 20dp：这页原来刻意用 14dp，但本页只有四块，
        // 结果上半截挤、下半截大片空白（真机截图里最明显的一页）。
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        StatusStrip(enabled = enabled)

        // 内容卡一律 OpticonCard（一档底色 + 1dp 描边）—— 与「图标」「模块」「关于」同一种卡片语言。
        // ⚠ 开关的语义在**整张卡**上（`toggleable` + `Switch(onCheckedChange = null)`）：
        //   名字不要另挂 `contentDescription`，也不要加 `mergeDescendants` —— 真机上那两种写法
        //   都会多出一个同尺寸的第二语义节点（读屏分两站念）。三条做法的实测记录见
        //   IconScreen 里 MasterSwitchCard 那段注释。
        OpticonCard(
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

        ScopeCard()
        VerifyCard(onNotify)
    }
}

/**
 * 状态条：一行讲清「开没开」，第二行是生效时机的说明。
 * 用 container 色和下面的设置卡区分开，但不做成一整块大卡片。
 */
@Composable
private fun StatusStrip(enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) scheme.primaryContainer else scheme.surfaceVariant,
        contentColor = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标：和开关行同款 ic_power，启用时主色、停用时中性灰。
            // 替换 10dp 小圆点——图标更醒目，开关行上下视觉对齐。
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (enabled) scheme.primary else scheme.outline
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(
                        if (enabled) R.string.notify_status_on else R.string.notify_status_off
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.notify_status_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 可选项左侧的图标底板。宽度固定，开关组的分隔线缩进按它算 */
private val IconBadgeSize = 44.dp

/**
 * @param selected 选中态：底板走 primary、图标走 onPrimary。
 */
@Composable
private fun IconBadge(
    @DrawableRes icon: Int,
    selected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary else scheme.surfaceVariant
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
            modifier = Modifier.size(23.dp),
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
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 底板跟着开关走：打开时是品牌色，关闭时是中性灰，状态一眼可见
        IconBadge(icon = icon, selected = checked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
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
 * 「作用域」和「验证」两张卡原来是一份东西抄了两遍，只差图标资源、两句文案
 * 和卡片底色 —— 形状留这一份。
 *
 * ⚠ 底板用的是**本页私有的** [IconBadge]（44dp 底板 + 23dp 图标），不是共享文件里
 *   那个 `IconTile`（40dp + 22dp）。那两个是刻意不合并的（理由见 IconTile 上的说明），
 *   所以这张卡也跟着留在本页 —— 别顺手搬去 Common.kt 给别的页复用，
 *   一搬就等于替那两页把底板尺寸定了。
 *
 * ⚠ 2026-09-30：原来这两张卡的底写死成 `surfaceVariant`、底板写死成 `surface`
 *   （"把解释性的和可操作的分开"）。那套区分现在由**底板颜色**承担：
 *   功能开关卡的底板是主色、说明卡是中性色 —— 而卡片本身与全应用一致，
 *   不再每张卡一套底色（三页摆在一起时，那才是"像是两个应用"的来源）。
 */
@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    footer: (@Composable () -> Unit)? = null,
) {
    OpticonCard {
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
