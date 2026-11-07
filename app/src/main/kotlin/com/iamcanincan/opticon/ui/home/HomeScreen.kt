package com.iamcanincan.opticon.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.GroupGap
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.MenuRow
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.SubPage
import com.iamcanincan.opticon.ui.timeHm
import com.iamcanincan.opticon.ui.groupRowShape
import com.iamcanincan.opticon.ui.ScreenList
import com.iamcanincan.opticon.ui.stats.StatsStore

/**
 * **主页 = 状态页**，版式对齐 KernelSU 管理器的首页（2026-11-07 主人："还是太丑了"）：
 *
 * - 顶部一张**紧凑状态卡**（图标 + 一行结论 + 一行数据 + 右侧小胶囊），不是一大块空数字；
 * - 下面是**灰图标 + 标题/副标题**的列表，图标不再带彩色底块；
 * - 卡片统一 20dp 圆角、行间 8dp 缝 —— 与他机上那页的规格一致。
 *
 * 分工不变：主页只读状态；开关在「功能」，明细在「统计」，配置与排障在「设置」。
 */
@Composable
internal fun HomeScreen(onOpen: (SubPage) -> Unit) {
  val context = LocalContext.current
  val options = ModulePrefs.read(ModulePrefs.of(context))
  val (snapshot, at) = StatsStore.load(context)
  val (cropCount, monoCount, notifyCount, total) = splitCounts(snapshot)
  val onCount = listOf(options.circleEnabled, options.monoInvert, options.notifyEnabled).count { it }
  val scheme = MaterialTheme.colorScheme
  // 「运行中」必须是读数、不能是写死的一句话：模块被关掉 / 钩子没注入时，宿主就不会再回报计数。
  // 判据用已有的「上次上报时间」，不新开探针 —— 桌面与系统界面每处理 20 次才回报一次，
  // 所以窗口给 10 分钟：短于它多半是刚好在忙，长于它就是真的没动静了。
  val fresh = at != 0L && System.currentTimeMillis() - at < FRESH_MS

  ScreenList {
    item { PageRole(stringResource(R.string.home_role)) }

    // 状态卡：图标 + 一行结论 + 一行数据 + 右侧胶囊（同 KernelSU 的"工作中 / 版本 / LKM"）
    item {
      OpticonCard(
        padding = 0.dp,
        containerColor = if (fresh) scheme.primaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (fresh) scheme.onPrimaryContainer else scheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        border = null,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = if (fresh) Icons.Default.CheckCircle else Icons.Default.Schedule,
            contentDescription = null,
            tint = if (fresh) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
          )
          Spacer(modifier = Modifier.size(14.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(if (fresh) R.string.home_state_ok else R.string.home_state_idle),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = when {
                at == 0L -> stringResource(R.string.home_total_none)
                fresh -> stringResource(R.string.home_state_total, total, timeHm(at))
                else -> stringResource(R.string.home_state_stale, timeHm(at), total)
              },
              style = MaterialTheme.typography.bodyMedium,
              color =
                (if (fresh) scheme.onPrimaryContainer else scheme.onSurfaceVariant).copy(alpha = 0.8f),
            )
          }
          Box(
            modifier = Modifier
              .background(scheme.primary, RoundedCornerShape(50))
              .padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            Text(
              text = stringResource(R.string.home_state_chip, onCount),
              style = MaterialTheme.typography.labelMedium,
              color = scheme.onPrimary,
            )
          }
        }
      }
    }

    item { GroupTitle(stringResource(R.string.home_features)) }

    // 功能列表：点一行进对应功能的二级页（开关与效果都在那里）。
    item {
      // 首尾大圆角、中间小圆角，行间只留 2dp（2026-11-07 主人：小圆角的边要贴得更近点）。
      Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        val rows = listOf(
          FeatureSpec(SubPage.CROP, Icons.Default.Circle, R.string.tab_crop, R.string.home_sub_crop, options.circleEnabled, cropCount),
          FeatureSpec(SubPage.MONO, Icons.Default.InvertColors, R.string.tab_mono, R.string.home_sub_mono, options.monoInvert, monoCount),
          FeatureSpec(SubPage.NOTIFY, Icons.Default.Notifications, R.string.tab_notify, R.string.home_sub_notify, options.notifyEnabled, notifyCount),
        )
        rows.forEachIndexed { i, row ->
          FeatureRow(row, groupRowShape(i, rows.size)) { onOpen(row.page) }
        }
      }
    }

    item { GroupTitle(stringResource(R.string.home_tips)) }

    // 底部这一组是"常见疑问"：原来是页面底部一行小字，下面半屏全是空白（主人："每个板块都好空"）。
    // 顶栏收回普通高度后，两行提示也能完整落在药丸上方（之前大标题栏时第二行会被压住）。
    item {
      Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        TipRow(
          icon = Icons.Default.Refresh,
          title = R.string.home_tip_stale_title,
          body = R.string.home_tip_stale_body,
          shape = groupRowShape(0, 2),
        )
        TipRow(
          icon = Icons.Default.Info,
          title = R.string.home_tip_count_title,
          body = R.string.home_tip_count_body,
          shape = groupRowShape(1, 2),
        )
      }
    }
  }
}

/**
 * 一行功能：**灰色图标** + 标题/副标题 + 右侧次数（只读）。
 *
 * ⚠ 这里**不再挂「开 / 关」胶囊**：那是「功能」页的事（2026-11-07 主人：「这界面功能怎么还重复了」）。
 *   主页只回答"发生了什么"（次数），开关归功能页；两页因此不再摆同一块东西。
 */
/** 一行功能的数据：图标、标题、副标题、开关状态、次数（-1 = 这一项没有计数）。 */
private data class FeatureSpec(
  val page: SubPage,
  val icon: ImageVector,
  val title: Int,
  val subtitle: Int,
  val on: Boolean,
  val count: Int,
)

@Composable
private fun FeatureRow(
  spec: FeatureSpec,
  shape: androidx.compose.ui.graphics.Shape,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  MenuRow(
    icon = spec.icon,
    title = stringResource(spec.title),
    subtitle = stringResource(spec.subtitle),
    shape = shape,
    onClick = onClick,
    // 开着的用主色图标、关着的用灰图标 —— 状态压缩到图标颜色上，不再占一块胶囊。
    iconTint = if (spec.on) scheme.primary else scheme.outline,
    trailing =
      if (spec.count >= 0) {
        {
          Text(
            text = stringResource(R.string.home_count, spec.count),
            style = MaterialTheme.typography.labelMedium,
            color = if (spec.on) scheme.primary else scheme.onSurfaceVariant,
          )
        }
      } else null,
  )
}


/** 提示行：灰图标 + 标题 + 一行解释，与功能行同一套版式（没有右侧胶囊）。 */
@Composable
private fun TipRow(icon: ImageVector, title: Int, body: Int, shape: androidx.compose.ui.graphics.Shape) {
  OpticonCard(shape = shape, border = null, padding = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.size(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
          text = stringResource(body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** 「近期有回报」的窗口：宿主每 20 次计数才回报一次，10 分钟内有回报就算在跑。 */
private const val FRESH_MS = 10 * 60 * 1000L

/**
 * 快照按功能分成三堆（裁圆 / 单色 / 通知），再给一个总数。
 *
 * ⚠ 单色 2026-11-08 才有账：进账点在 `MonochromeSynth.store`（新合成一张单色层记一次），
 *   所以它是「我们替多少张未适配图标做了单色层」；应用自带单色层的那一批我们不碰、也不计。
 */
private fun splitCounts(snapshot: String?): Stats4 {
  if (snapshot.isNullOrBlank()) return Stats4(0, 0, 0, 0)
  var crop = 0
  var mono = 0
  var notify = 0
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val branch = part.substring(0, eq).substringAfter('/')
    when {
      branch.startsWith("裁圆") -> crop += n
      branch.startsWith("单色") -> mono += n
      branch.startsWith("通知") -> notify += n
    }
  }
  return Stats4(crop, mono, notify, crop + mono + notify)
}

/** 三堆 + 总数。解构按声明顺序，调用点写 `val (crop, mono, notify, total) = …`。 */
private data class Stats4(
  val crop: Int,
  val mono: Int,
  val notify: Int,
  val total: Int,
)
