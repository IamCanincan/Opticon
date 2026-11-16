package com.iamcanincan.opticon.ui.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.FeatureInfoCard
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.SampleTile
import com.iamcanincan.opticon.ui.ScreenList

/**
 * 「自适应」页：只讲这一件事 —— 非自适应（老式）图标做成自适应图标，以及会影响它的系统行为说明。
 *
 * 2026-10-26 三个功能分页：单色与通知各自独立成页；作用域 / 步骤 / 验证 / 运维在「模块」页。
 * 2026-11-13 换口径：原来这一页讲"裁成正圆"，主人定了「图标裁圆应该去掉，转为将非自适应图标
 * 优化为自适应图标」—— 现在讲的是包装（填满正方形 + 边缘延伸 / 稀疏铺白底 + 系统 mask）。
 * 2026-11-14 起快捷方式整个放行，效果卡第四格与「原样」同款画法。
 *
 * 顶栏与底部导航让出的空间由外壳扣掉（`OpticonApp` 里的 `Modifier.padding(inner)`），
 * 这里只管列表自己的边距 —— 各页共用同一份脚手架，见 [ScreenList]。
 *
 * ⚠ 开关不在这里（2026-11-12）：只有「功能」页那一行能改，这页只说明这个功能做什么。
 */
@Composable
fun CropScreen() {
  ScreenList {
    // 「功能」页那一行的开关决定开不开；这里只说明这个功能是什么（单一入口，2026-11-12）。
    item {
      FeatureInfoCard(
        icon = Icons.Default.Circle,
        title = stringResource(R.string.circle_switch_title),
        body = stringResource(R.string.circle_switch_body),
      )
    }
    item { GroupTitle(stringResource(R.string.section_effect)) }
    item { EffectCard() }
    item { GroupTitle(stringResource(R.string.section_notes)) }
    item {
      NoteList(
        listOf(
          R.string.note_adaptive,
          R.string.note_tiles,
          R.string.note_launcher3,
        )
      )
    }
  }
}



/**
 * 「效果」卡：一块四格样品板 —— 原样 / 自适应 / 透明底图标 / 快捷方式，下面一句结论。
 *
 * 补这张卡是因为图标页原本只有「开关 + 说明」两块，首屏下半截全是空的；
 * 而且「改完之后到底变成什么样」光靠文字不好讲 —— 画出来一目了然。
 *
 * 2026-10-26：从"两张大图 + 一行两小图"改成**等大四格**。上一版主图 56dp、例外 44dp，
 * 两档例外像临时挂在下面；四格共用同一块 `surfaceContainerHighest` 底板、同一套两行字，
 * 才像一条规则。白盘在浅色卡上也要有边界，所以用真白 + `outlineVariant` 描边。
 *
 * ⚠ 样本只是**示意图**，不是真实图标渲染：原样那块用中性圆角方表示老式图标（一张方图）；
 * 自适应那格用主色圆示意「铺满图层、系统裁形」；透明底那格用白盘示意「缩进内切圆 + 铺白底」；
 * 快捷方式那格与「原样」同款 —— 那一路整个放行，我们一个像素不碰。
 */
@Composable
private fun EffectCard() {
  val scheme = MaterialTheme.colorScheme
  OpticonCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      EffectTile(
        modifier = Modifier.weight(1f),
        style = EffectStyle.ORIGINAL,
        title = stringResource(R.string.effect_caption_before),
        detail = stringResource(R.string.effect_sample_original_detail),
      )
      EffectTile(
        modifier = Modifier.weight(1f),
        style = EffectStyle.WRAPPED,
        title = stringResource(R.string.tab_crop),
        detail = stringResource(R.string.effect_sample_cropped_detail),
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      EffectTile(
        modifier = Modifier.weight(1f),
        style = EffectStyle.TRANSPARENT,
        title = stringResource(R.string.effect_sample_shrink_title),
        detail = stringResource(R.string.effect_caption_shrink),
      )
      EffectTile(
        modifier = Modifier.weight(1f),
        style = EffectStyle.SHORTCUT,
        title = stringResource(R.string.effect_sample_shortcut_title),
        detail = stringResource(R.string.effect_caption_shortcut),
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

private enum class EffectStyle { ORIGINAL, WRAPPED, TRANSPARENT, SHORTCUT }

/**
 * 效果板的一格：统一的 68dp 底板 + 40dp 样本 + 两行字。
 *
 * 底板用 `surfaceContainerHighest`（与 `IconTile`、命令块同一档）—— 卡片本身是
 * `surfaceContainerLow`，正好隔一档，白盘和主色圆都能立住。
 */
@Composable
private fun EffectTile(
  modifier: Modifier = Modifier,
  style: EffectStyle,
  title: String,
  detail: String,
) {
  val scheme = MaterialTheme.colorScheme
  SampleTile(modifier = modifier, title = title, detail = detail) {
    when (style) {
      // 用可识别的图形（相机 / 全景 / 闪电）代替抽象色块：一眼能看出"这是一张图"，
      // 也把原样与自适应后的关系说清楚（同一个相机，铺满图层后由系统裁形）。
      EffectStyle.ORIGINAL ->
        Box(
          modifier =
            Modifier.size(40.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(scheme.surfaceVariant)
              .border(1.dp, scheme.outline, RoundedCornerShape(14.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
          )
        }
      // 自适应化：不留底盘，原图铺满图层，由系统按形状裁一刀（相机大小与左边一样）。
      EffectStyle.WRAPPED ->
        Box(
          modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.primary),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.size(22.dp),
          )
        }
      // 透明底图标（稀疏内容那一支）：缩进内切圆 + 铺白底。白盘是烘进前景的、不是背景层，
      // 所以画真白 + 描边；图案比满幅那格小一档，示意"缩进"。
      EffectStyle.TRANSPARENT ->
        Box(
          modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White)
            .border(1.dp, scheme.outlineVariant, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Panorama,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }
      // 快捷方式：整个放行（2026-11-14），与「原样」同一款画法，只换图形。
      EffectStyle.SHORTCUT ->
        Box(
          modifier = Modifier.size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
          )
        }
    }
  }
}

