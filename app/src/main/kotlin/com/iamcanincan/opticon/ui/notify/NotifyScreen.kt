package com.iamcanincan.opticon.ui.notify

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.CommandRow
import com.iamcanincan.opticon.ui.EffectShowcaseCard
import com.iamcanincan.opticon.ui.FeatureInfoCard
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.SampleTile
import com.iamcanincan.opticon.ui.ScreenList

/**
 * 「通知」页。
 *
 * 2026-10-26 三个功能分页后：说明卡与裁圆 / 单色共用 [FeatureInfoCard]，
 * 说明区与其它页共用 [ScreenList]/[GroupTitle]。
 *
 * 开关只在「功能」页那一行（2026-11-12 单一入口）；这里只讲通知图标做了什么、怎么验证。
 */
@Composable
fun NotifyScreen(onNotify: (String) -> Unit) {
    ScreenList {
        item {
            FeatureInfoCard(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.notify_switch_title),
                body = stringResource(R.string.notify_switch_body),
            )
        }

        item { GroupTitle(stringResource(R.string.section_effect)) }

        item { NotifyEffectCard() }

        item { GroupTitle(stringResource(R.string.section_scope)) }

        item { ScopeCard() }

        item { GroupTitle(stringResource(R.string.section_verify)) }

        item { VerifyCard(onNotify) }
    }
}

/**
 * 通知小图标示意：左=灰白色块（无法辨识），右=修复后的应用图标剪影。
 * 与单色页共用 [EffectShowcaseCard] / [SampleTile]，三页示意语言一致。
 */
@Composable
private fun NotifyEffectCard() {
    val scheme = MaterialTheme.colorScheme
    EffectShowcaseCard(
        title = stringResource(R.string.notify_effect_title),
        body = stringResource(R.string.notify_effect_body),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SampleTile(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.effect_caption_before),
                detail = stringResource(R.string.notify_sample_before_detail),
            ) {
                Box(
                    modifier =
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(scheme.outlineVariant)
                    )
                }
            }
            SampleTile(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.notify_sample_after_title),
                detail = stringResource(R.string.notify_sample_after_detail),
            ) {
                Box(
                    modifier =
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
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
