package com.iamcanincan.opticon.ui.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.FeatureSwitchCard
import com.iamcanincan.opticon.ui.NoteList
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.Section

/**
 * 「裁圆」页：只讲这一件事 —— 桌面图标裁成正圆，以及会影响它的系统行为说明。
 *
 * 2026-10-26 三个功能分页：单色与通知各自独立成页；作用域 / 步骤 / 验证 / 运维在「模块」页。
 *
 * 顶栏与底部导航让出的空间由外壳扣掉（`OpticonApp` 里的 `Modifier.padding(inner)`），
 * 这里只管列表自己的边距 —— 各页共用同一份脚手架，见 [ScreenList]。
 *
 * ⚠ [onNotify] 由外壳提供、走外壳的协程作用域：这页是 Pager 的一页，切走就被销毁，
 * 用页面自己的 scope 发 Snackbar 会在切页那一刻被取消掉。
 */
@Composable
fun CropScreen(onNotify: (String) -> Unit) {
  ScreenList {
    item { MasterSwitchCard(onNotify) }
    item { Section(text = stringResource(R.string.section_effect)) { EffectCard() } }
    item {
      Section(text = stringResource(R.string.section_notes)) {
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
}



/**
 * 裁圆总开关。
 *
 * 写进 SharedPreferences 即可，模块侧按 TTL 自行重读（见 `ModuleRuntime`），
 * 无需广播或重启进程。
 *
 * ⚠ 关闭后，**已绘制在桌面上的图标不会立即恢复**：桌面存在图标缓存，
 * 需在「模块」页的运维操作中清除缓存或重启桌面。
 */
@Composable
private fun MasterSwitchCard(onNotify: (String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var enabled by remember {
    mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, ModulePrefs.DEFAULT_CIRCLE_ENABLED))
  }
  val savedHint = stringResource(R.string.switch_saved_hint)

  FeatureSwitchCard(
    icon = Icons.Default.Circle,
    title = stringResource(R.string.circle_switch_title),
    body = stringResource(R.string.circle_switch_body),
    checked = enabled,
    onCheckedChange = { value ->
      enabled = value
      prefs.edit().putBoolean(ModulePrefs.KEY_CIRCLE_ENABLED, value).apply()
      // 桌面持有预渲染缓存；提示用户清除后才会反映新设置。
      onNotify(savedHint)
    },
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
 * 但已经缓存下来的图标要等重建，用「模块」页运维操作里的清缓存按钮。
 */
/**
 * 「效果」卡：一块四格样品板 —— 原样 / 裁圆 / 裁不动 / 快捷方式，下面一句结论。
 *
 * 补这张卡是因为图标页原本只有「开关 + 说明」两块，首屏下半截全是空的；
 * 而且「改完之后到底变成什么样」光靠文字不好讲 —— 画出来一目了然。
 *
 * 2026-10-26：从"两张大图 + 一行两小图"改成**等大四格**。上一版主图 56dp、例外 44dp，
 * 两档例外像临时挂在下面；四格共用同一块 `surfaceContainerHighest` 底板、同一套两行字，
 * 才像一条规则。白盘在浅色卡上也要有边界，所以用真白 + `outlineVariant` 描边。
 *
 * ⚠ 样本只是**示意图**，不是真实图标渲染：原样那块用中性圆角方表示系统可能给的任何形状
 * （圆角方 / 水滴 / 方）；其余三格用主色示意内容。
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
        style = EffectStyle.CROPPED,
        title = stringResource(R.string.effect_caption_after),
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
        style = EffectStyle.SHRUNK,
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

private enum class EffectStyle { ORIGINAL, CROPPED, SHRUNK, SHORTCUT }

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
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Surface(
      modifier = Modifier.fillMaxWidth().height(68.dp),
      shape = MaterialTheme.shapes.medium,
      color = scheme.surfaceContainerHighest,
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (style) {
          // 用可识别的图形（相机 / 全景 / 闪电）代替抽象色块：一眼能看出"这是一张图"，
          // 也把原样与裁圆的关系说清楚（同一个相机，换形状）。
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
          EffectStyle.CROPPED ->
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
          EffectStyle.SHRUNK ->
            PlateSample {
              Icon(
                imageVector = Icons.Default.Panorama,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
              )
            }
          EffectStyle.SHORTCUT ->
            PlateSample {
              Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
              )
            }
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = scheme.onSurface,
    )
    Spacer(modifier = Modifier.height(1.dp))
    Text(
      text = detail,
      style = MaterialTheme.typography.labelSmall,
      color = scheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

/** 圆内的白盘：真白 + 描边（浅色卡上不描边就融进去了），内容由调用方放。 */
@Composable
private fun PlateSample(content: @Composable () -> Unit) {
  Box(
    modifier =
      Modifier.size(40.dp)
        .clip(CircleShape)
        .background(Color.White)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

