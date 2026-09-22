package com.iamcanincan.opticon.ui.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val prefs = remember { ModulePrefs.of(context).also { ModulePrefs.seedIfAbsent(it) } }

    var enabled by remember { mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_NOTIFY_ENABLED, true)) }
    var mode by remember {
        mutableIntStateOf(prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.DEFAULT_MODE))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NotifyHero()
        StatusStrip(enabled = enabled, mode = mode)

        SectionLabel("图标模式")
        ModeOption(
            icon = R.drawable.ic_mode_mono,
            title = "系统黑白通知",
            description = "把应用自己给的小图标压成单色剪影，由系统按主题统一上色，风格和其它通知一致。",
            previewColorful = false,
            selected = mode == ModulePrefs.MODE_MONOCHROME,
            onClick = {
                mode = ModulePrefs.MODE_MONOCHROME
                prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_MONOCHROME).apply()
            }
        )
        ModeOption(
            icon = R.drawable.ic_mode_color,
            title = "彩色桌面图标",
            description = "把还没做主题适配的彩色小图标，换成应用在桌面上那个图标，颜色原样保留。",
            previewColorful = true,
            selected = mode == ModulePrefs.MODE_LAUNCHER_ICON,
            onClick = {
                mode = ModulePrefs.MODE_LAUNCHER_ICON
                prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON).apply()
            }
        )

        SectionLabel("行为")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            SwitchRow(
                icon = R.drawable.ic_power,
                title = "修复通知小图标",
                description = "关闭后所有通知都不处理",
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
 * 首屏色块：品牌标记方块 + 一句话说明。
 * App 名已经常驻在顶栏里，这里再写一遍纯属重复，所以让位给副标题
 * （与「图标」页的 HeroHeader 同一个结构）。
 *
 * ⚠ 别改回直接用 ic_launcher_foreground：那份字形硬编码 #031019，
 *   深色模式下画在深底上等于隐形；而且它带 group scale 1.2 是按「占图标 36%」
 *   算的，放进界面的小方框会小得看不清。界面一律用 ic_brand_mark。
 */
@Composable
private fun NotifyHero() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = scheme.onPrimaryContainer.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = "把未适配主题的通知小图标换成能认出是谁的样子",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
        }
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
        shape = RoundedCornerShape(20.dp),
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
                    if (enabled) "已启用 · ${mode.modeName}" else "已停用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "改动立即生效，新收到的通知会按新模式显示；已经在通知栏里的那条要等它重新加载",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 图标模式的中文名。两处（状态条、模式名）共用，避免写岔 */
private val Int.modeName: String
    get() = if (this == ModulePrefs.MODE_MONOCHROME) "系统黑白通知" else "彩色桌面图标"

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
            .clip(RoundedCornerShape(14.dp))
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
    val shape = RoundedCornerShape(24.dp)
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
            if (colorful) "保留原色" else "按主题统一上色",
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant)
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
                    "关于作用域",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "通知是系统界面画出来的，所以这一路只注入「系统界面」（com.android.systemui）。"
                        + "作用域整体不固定 —— 图标裁圆需要覆盖桌面、设置等更多进程，"
                        + "你在框架里额外勾选别的应用不会影响这里。"
                        + "如果发现不生效，到框架的模块详情页点一次「应用」"
                        + "（右上角开关 → 底部「应用」），然后重启设备。",
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(icon = R.drawable.ic_verify, selected = false)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "怎么确认生效",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "下拉通知栏看内容区那个小图标：原来是灰白色块的，现在应该能认出是哪个应用。"
                        + "状态栏那一行本来就被系统压成单色，别在那里找差异。\n\n"
                        + "日志确认（adb logcat -s Opticon）：出现 "
                        + "patched <包名> 2->1 via=monochrome 就是替换成功了。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
