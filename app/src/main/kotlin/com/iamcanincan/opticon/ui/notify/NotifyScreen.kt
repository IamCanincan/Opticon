package com.iamcanincan.opticon.ui.notify

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * - 只暴露一个真正的选择 —— 图标模式；另有一个总开关。每次改动立即落盘，
 *   模块侧下次被挂钩时就能读到，不需要点"保存"。
 */
@Composable
fun NotifyScreen() {
    val context = LocalContext.current
    // 默认配置由外壳（OpticonApp）在首次打开时落盘，这里只读。
    val prefs = remember { ModulePrefs.of(context) }

    var enabled by remember { mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, true)) }
    var mode by remember {
        mutableIntStateOf(prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.DEFAULT_MODE))
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
        StatusStrip(enabled = enabled, mode = mode)

        SectionLabel(stringResource(R.string.notify_section_mode))
        ModeOption(
            icon = R.drawable.ic_mode_mono,
            title = stringResource(R.string.notify_mode_mono_title),
            description = stringResource(R.string.notify_mode_mono_desc),
            previewColorful = false,
            selected = mode == ModulePrefs.MODE_MONOCHROME,
            onClick = {
                mode = ModulePrefs.MODE_MONOCHROME
                prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_MONOCHROME).apply()
            }
        )
        ModeOption(
            icon = R.drawable.ic_mode_color,
            title = stringResource(R.string.notify_mode_color_title),
            description = stringResource(R.string.notify_mode_color_desc),
            previewColorful = true,
            selected = mode == ModulePrefs.MODE_LAUNCHER_ICON,
            onClick = {
                mode = ModulePrefs.MODE_LAUNCHER_ICON
                prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON).apply()
            }
        )

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
 * 状态条：一行讲清「开没开、用的哪个模式」，第二行是生效时机的说明。
 * 用 container 色和下面的设置卡区分开，但不做成一整块大卡片。
 */
@Composable
private fun StatusStrip(enabled: Boolean, mode: Int) {
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
                    text = if (enabled) {
                        stringResource(R.string.notify_status_on, modeName(mode))
                    } else {
                        stringResource(R.string.notify_status_off)
                    },
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

/** 图标模式的名字。状态条和模式选项共用同一份字符串，避免两处写岔 */
@Composable
private fun modeName(mode: Int): String = stringResource(
    if (mode == ModulePrefs.MODE_MONOCHROME) R.string.notify_mode_mono_title
    else R.string.notify_mode_color_title
)

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
private fun ModeOption(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    /** 效果预览里「处理后」是彩色（true）还是单色（false） */
    previewColorful: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon = icon, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.78f)
                    else scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                EffectPreview(colorful = previewColorful, selected = selected)
            }
            Spacer(Modifier.width(12.dp))
            RadioDot(selected = selected)
        }
    }
}

/**
 * 「处理前 → 处理后」的小示意。一眼能看到「把灰白小图标换成什么」。
 *
 * 处理前用一个灰色方块代表「未适配主题的原始小图标」（通知栏里看到的就是
 * 这种灰白一片）。处理后根据模式显示彩色圆（保色模式）或单色圆（系统上色）。
 */
@Composable
private fun EffectPreview(colorful: Boolean, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 处理前：灰白方块（模拟 Android 12+ 强上色后的"看不出是谁"状态）
        Box(
            Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.outlineVariant)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "→",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.outline
        )
        Spacer(Modifier.width(8.dp))
        // 处理后
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .let {
                    if (colorful) it.background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5),
                                Color(0xFF03A9F4), Color(0xFF009688), Color(0xFFE91E63)
                            )
                        )
                    )
                    else it.background(if (selected) scheme.primary else scheme.outline)
                }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (colorful) R.string.notify_effect_keep_color else R.string.notify_effect_tinted
            ),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}

/** 自绘单选点：选中时是实心圆 + 内点，未选中时是描边圆环 */
@Composable
private fun RadioDot(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (selected) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(scheme.onPrimary))
        }
    } else {
        // 未选中的环：外层 outline 画环，内层用卡片底色挖空（卡片此时就是 surface）
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(scheme.outline),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(15.dp).clip(CircleShape).background(scheme.surface))
        }
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

@Composable
private fun ScopeCard() {
    val scheme = MaterialTheme.colorScheme
    // 与「模块」页的说明卡同一种语言（ElevatedCard）；这里额外用 surfaceVariant 底，
    // 是为了和上面的「设置项」区分开 —— 它是解释性的，不是可操作的。
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 卡片本身是 surfaceVariant，底板必须换个色，否则两者同色、底板等于不存在
            IconBadge(
                icon = R.drawable.ic_scope,
                selected = false,
                containerColor = scheme.surface
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.notify_scope_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.notify_scope_body),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 怎么确认它真的生效了 —— 通知栏看不出差异时，日志是唯一可靠的判据。 */
@Composable
private fun VerifyCard() {
    val scheme = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(icon = R.drawable.ic_verify, selected = false)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.notify_verify_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.notify_verify_body),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
