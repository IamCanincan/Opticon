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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.FloatingNavSpace

/**
 * 设置界面。
 *
 * 视觉约定（改样式时照着来，别再各写各的）：
 * - 卡片统一 24dp 圆角 + 1dp outlineVariant 描边，背景 surface；只有「已选中」
 *   和「状态条」这类需要突出的才用 container 色。
 * - 每个区块的标题走 [SectionLabel]；每条可选项左侧都有 44dp 的图标底板。
 * - 本页只有一个开关 —— 通知小图标修不修。每次改动立即落盘，
 *   模块侧下次被挂钩时就能读到，不需要点"保存"。
 */
@Composable
fun NotifyScreen() {
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusStrip(enabled = enabled)

        SectionLabel(stringResource(R.string.notify_section_behavior))
        // 内容卡一律 ElevatedCard —— 与「图标」「模块」两页同一种卡片语言。
        // 之前这里是「白底 + 细边框」的 Surface，三页摆在一起像是两个不同的应用。
        ElevatedCard(shape = MaterialTheme.shapes.large) {
            SwitchRow(
                icon = R.drawable.ic_power,
                title = stringResource(R.string.notify_switch_title),
                description = stringResource(R.string.notify_switch_desc),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, it).apply()
                }
            )
        }

        ScopeCard()
        VerifyCard()
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.notify_status_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/** 可选项左侧的图标底板。宽度固定，开关组的分隔线缩进按它算 */
private val IconBadgeSize = 44.dp

/**
 * @param selected 选中态：底板走 primary、图标走 onPrimary。
 * @param containerColor 底板色覆盖。默认按 [selected] 自动选；
 *   当宿主卡片本身就是 surfaceVariant 时（比如 [ScopeCard]）必须显式传一个
 *   和卡片不同的颜色，否则底板和卡片同色、等于没有底板。
 */
@Composable
private fun IconBadge(
    @DrawableRes icon: Int,
    selected: Boolean,
    containerColor: Color = Color.Unspecified
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (containerColor != Color.Unspecified) containerColor
    else if (selected) scheme.primary
    else scheme.surfaceVariant
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
    onCheckedChange: (Boolean) -> Unit
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 本页的「说明卡」：图标底板 + 标题 + 一句说明，整张卡只是解释、不能点。
 *
 * 「作用域」和「验证」两张卡原来是一份东西抄了两遍，只差图标资源、两句文案
 * 和卡片底色 —— 形状留这一份，两处差异用参数带出来。
 *
 * ⚠ 底板用的是**本页私有的** [IconBadge]（44dp 底板 + 23dp 图标），不是共享文件里
 *   那个 `IconTile`（40dp + 22dp）。那两个是刻意不合并的（理由见 IconTile 上的说明），
 *   所以这张卡也跟着留在本页 —— 别顺手搬去 Common.kt 给别的页复用，
 *   一搬就等于替那两页把底板尺寸定了。
 *
 * @param cardColors 卡片配色，默认就是 `ElevatedCard` 本来的那套。
 *   只有想额外压一层底色把它跟"可操作的那些"分开时才传（见 [ScopeCard]）。
 * @param badgeContainerColor 底板底色覆盖，原样转给 [IconBadge]。卡片自己就是
 *   surfaceVariant 时必须显式传一个跟卡片不同的颜色，否则底板等于不存在。
 */
@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    cardColors: CardColors = CardDefaults.elevatedCardColors(),
    badgeContainerColor: Color = Color.Unspecified
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = cardColors
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(
                icon = icon,
                selected = false,
                containerColor = badgeContainerColor
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ScopeCard() {
    val scheme = MaterialTheme.colorScheme
    InfoCard(
        icon = R.drawable.ic_scope,
        title = stringResource(R.string.notify_scope_title),
        description = stringResource(R.string.notify_scope_body),
        // 与「模块」页的说明卡同一种语言（ElevatedCard）；这里额外用 surfaceVariant 底，
        // 是为了和上面的「设置项」区分开 —— 它是解释性的，不是可操作的。
        cardColors = CardDefaults.elevatedCardColors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant
        ),
        // 卡片本身是 surfaceVariant，底板必须换个色，否则两者同色、底板等于不存在
        badgeContainerColor = scheme.surface
    )
}

/** 怎么确认它真的生效了 —— 通知栏看不出差异时，日志是唯一可靠的判据。 */
@Composable
private fun VerifyCard() {
    InfoCard(
        icon = R.drawable.ic_verify,
        title = stringResource(R.string.notify_verify_title),
        description = stringResource(R.string.notify_verify_body)
    )
}
