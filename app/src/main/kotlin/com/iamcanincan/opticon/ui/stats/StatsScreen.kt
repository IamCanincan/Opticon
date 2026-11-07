package com.iamcanincan.opticon.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.GroupTitle
import com.iamcanincan.opticon.ui.OpticonCard
import com.iamcanincan.opticon.ui.PageRole
import com.iamcanincan.opticon.ui.ScreenList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「统计」页（2026-11-08 改成图表，主人：「以图表形式更为简洁直观，但是要有 md3e 风格」）。
 *
 * 两段：
 * 1. **总览**：一个 M3E 的环（粗描边 + 圆头 + 段间留缝），三段 = 三个功能占的比重，
 *    中间是总次数；右边是三行图例（色点 + 名称 + 次数 + 占比）。
 * 2. **按功能**：每个功能一张卡，一条堆叠条表示组内来源（桌面 / 系统界面 / 各分支），
 *    下面一行小字给出每个来源的次数。
 *
 * 原来的实现是"每个分支一行条形"，八行数字看着像表格 —— 信息没少，但一眼读不出
 * "哪个功能在干活、谁占大头"。图表把这两件事放到第一屏，明细退到第二段。
 *
 * 数据仍是宿主每 20 次计数回报一次，所以这里不做实时刷新，只给一个「刷新」按钮。
 */
@Composable
fun StatsScreen() {
  val context = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  val loaded = remember(tick) { StatsStore.load(context) }
  val groups = remember(loaded) { groupByFeature(loaded.first) }
  val totals = groups.map { (_, rows) -> rows.sumOf { it.second } }
  val grand = totals.sum()

  ScreenList {
    item { PageRole(stringResource(R.string.stats_role)) }

    item { SummaryCard(groups, totals, grand, loaded.second, onRefresh = { tick++ }) }

    item {
      Text(
        text = stringResource(R.string.stats_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      )
    }

    // 没数据时下面一大片是空的：补一条"怎么会才有数字"的说明卡。
    if (groups.isEmpty()) {
      item {
        OpticonCard(padding = 0.dp) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.Schedule,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.stats_empty_title),
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = stringResource(R.string.stats_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    } else {
      item { GroupTitle(stringResource(R.string.stats_by_feature)) }
      groups.forEachIndexed { i, (feature, rows) ->
        item(key = "g:$feature") {
          FeatureChart(feature = feature, rows = rows, grand = grand, color = chartColor(i))
        }
      }
    }
  }
}

/**
 * 三段图的配色：主色 → 三级色 → 次色。
 *
 * ⚠ 顺序是量出来的：这套动态取色里 primary 与 secondary 都是青系，50% / 49% 两段挨在一起时
 *   屏幕上分不出界（2026-11-08 真机截图）。primary 与 tertiary 的色相差足够大，
 *   所以最大的两段用它们；第三段（通常很小）才用 secondary。
 */
@Composable
private fun chartColor(index: Int): Color {
  val scheme = MaterialTheme.colorScheme
  return when (index % 3) {
    0 -> scheme.primary
    1 -> scheme.tertiary
    else -> scheme.secondary
  }
}

private data class Slice(val label: String, val value: Int, val color: Color)

/**
 * 总览卡：环 + 图例 + 刷新。
 *
 * 环用 `drawArc` 画（粗描边、圆头、每段之间留 4° 的缝），这是 M3E 进度指示器那一套语言；
 * 进来时扫过的角度从 0 长到该段的角度，参数取主题的 motionScheme，不写死时长。
 */
@Composable
private fun SummaryCard(
  groups: List<Pair<String, List<Pair<String, Int>>>>,
  totals: List<Int>,
  grand: Int,
  at: Long,
  onRefresh: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val slices = groups.mapIndexed { i, (feature, _) -> Slice(feature, totals[i], chartColor(i)) }

  var grown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { grown = true }
  val sweep by animateFloatAsState(
    targetValue = if (grown) 1f else 0f,
    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    label = "ring",
  )

  OpticonCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.stats_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.weight(1f))
      TextButton(onClick = onRefresh) { Text(stringResource(R.string.stats_refresh)) }
    }
    Text(
      text = if (at == 0L) stringResource(R.string.stats_empty)
      else stringResource(R.string.stats_at, timeText(at)),
      style = MaterialTheme.typography.bodySmall,
      color = scheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(18.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Ring(slices = slices, grand = grand, sweep = sweep)
      Spacer(modifier = Modifier.width(18.dp))
      Column(modifier = Modifier.weight(1f)) {
        if (slices.isEmpty()) {
          Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = scheme.onSurfaceVariant,
          )
        }
        slices.forEachIndexed { i, s ->
          if (i > 0) Spacer(modifier = Modifier.height(10.dp))
          LegendRow(slice = s, grand = grand)
        }
      }
    }
  }
}

/**
 * 环：粗描边 + **4dp 小圆角**的段端 + 段间 3° 缝；中间是总数。
 *
 * 为什么不用 `StrokeCap.Round`：那是按描边半径倒圆（18dp 描边 = 每端一个 9dp 的半圆），
 * 段看起来像胶囊，主人 2026-11-08 判「这样也太丑了吧」；但纯平头又太方。
 * 所以这里自己拼路径：外弧 → 外端角 → 径向面 → 内端角 → 内弧（反向）→ 内起角 → 径向面 → 闭合，
 * 四个角各用一段二次贝塞尔倒 [CORNER_RADIUS]。
 *
 * 短的段（1% 那种）会被缝和两个圆角吃成负数：改画一个圆点，半径随占比缩。
 */
@Composable
private fun Ring(slices: List<Slice>, grand: Int, sweep: Float) {
  val scheme = MaterialTheme.colorScheme
  val thickness = 18.dp
  val description = if (slices.isEmpty()) "" else buildString {
    append(grand).append(" 次处理；")
    slices.forEachIndexed { i, s ->
      if (i > 0) append("，")
      append(s.label).append(" ").append(s.value).append(" 次")
    }
  }
  Box(
    modifier = Modifier
      .size(126.dp)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(126.dp)) {
      val strokeWidth = thickness.toPx()
      val radius = (size.minDimension - strokeWidth) / 2f
      val c = Offset(size.width / 2f, size.height / 2f)

      // 轨道：没有数的时候也得看得见这是个"环"，而不是一片空白。
      drawCircle(
        color = scheme.surfaceContainerHighest,
        radius = radius,
        center = c,
        style = Stroke(width = strokeWidth),
      )
      if (grand <= 0 || sweep <= 0.01f) return@Canvas
      val live = slices.filter { it.value > 0 }
      if (live.isEmpty()) return@Canvas

      val inner = radius - strokeWidth / 2f
      val outer = radius + strokeWidth / 2f
      val corner = 4.dp.toPx()

      // 只有一段 = 整个环：不画缝，直接一个填满的圆环。
      if (live.size == 1 && 360f * sweep >= 359f) {
        drawCircle(
          color = live[0].color,
          radius = radius,
          center = c,
          style = Stroke(strokeWidth),
        )
        return@Canvas
      }

      val gapDeg = 3f
      var start = -90f
      slices.forEach { s ->
        val full = 360f * s.value.toFloat() / grand.toFloat()
        if (s.value > 0) {
          val visual = full * sweep
          val bodySweep = visual - gapDeg
          // 角半径最多占这段弧长的 1/4：否则圆角会互相吃掉，画出来是个梭形。
          val arcLen = outer * Math.toRadians(bodySweep.coerceAtLeast(0f).toDouble()).toFloat()
          val k = minOf(corner, arcLen / 4f, strokeWidth / 2f)
          if (bodySweep > 0.5f && k > 0.2f) {
            val path = ringSegment(
              cx = c.x,
              cy = c.y,
              inner = inner,
              outer = outer,
              startDeg = start + gapDeg / 2f,
              sweepDeg = bodySweep,
              corner = k,
            )
            drawPath(path = path, color = s.color)
          } else {
            // 太短：画一个点，半径按"这段占多少"缩，别把 1% 画成一个正式段。
            val mid = Math.toRadians((start + visual / 2f).toDouble())
            val dotR = (strokeWidth / 2f) * (visual / 6f).coerceIn(0.3f, 0.8f)
            drawCircle(
              color = s.color,
              radius = dotR,
              center = Offset(
                c.x + radius * kotlin.math.cos(mid).toFloat(),
                c.y + radius * kotlin.math.sin(mid).toFloat(),
              ),
            )
          }
        }
        start += full
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "$grand",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
      )
      Text(
        text = stringResource(R.string.stats_total_unit),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
      )
    }
  }
}


/**
 * 一段"环形带"的路径，四个角各倒 [corner] 的圆角（不是圆头）。
 *
 * 顺序：外弧 → 外端角 → 径向面 → 内端角 → 内弧（反向）→ 内起角 → 径向面 → 外起角 → 闭合。
 * 角度偏移按 `corner / 半径` 换算：外弧退得少、内弧退得多，两头的倒角半径看起来才一样。
 */
private fun ringSegment(
  cx: Float,
  cy: Float,
  inner: Float,
  outer: Float,
  startDeg: Float,
  sweepDeg: Float,
  corner: Float,
): Path {
  fun pt(r: Float, deg: Float) = Offset(
    cx + r * kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat(),
    cy + r * kotlin.math.sin(Math.toRadians(deg.toDouble())).toFloat(),
  )

  val path = Path()
  val kOutDeg = Math.toDegrees((corner / outer).toDouble()).toFloat()
  val kInDeg = Math.toDegrees((corner / inner).toDouble()).toFloat()
  val a0 = startDeg
  val a1 = startDeg + sweepDeg
  val outerRect = Rect(cx - outer, cy - outer, cx + outer, cy + outer)
  val innerRect = Rect(cx - inner, cy - inner, cx + inner, cy + inner)

  val start = pt(outer, a0 + kOutDeg)
  path.moveTo(start.x, start.y)
  path.arcTo(outerRect, a0 + kOutDeg, sweepDeg - 2f * kOutDeg, forceMoveTo = false)

  val outerEndCorner = pt(outer, a1)
  val outerEnd = pt(outer - corner, a1)
  path.quadraticTo(outerEndCorner.x, outerEndCorner.y, outerEnd.x, outerEnd.y)

  val innerEnd = pt(inner + corner, a1)
  path.lineTo(innerEnd.x, innerEnd.y)

  val innerEndCorner = pt(inner, a1)
  val innerEndAfter = pt(inner, a1 - kInDeg)
  path.quadraticTo(innerEndCorner.x, innerEndCorner.y, innerEndAfter.x, innerEndAfter.y)

  path.arcTo(innerRect, a1 - kInDeg, -(sweepDeg - 2f * kInDeg), forceMoveTo = false)

  val innerStartCorner = pt(inner, a0)
  val innerStart = pt(inner + corner, a0)
  path.quadraticTo(innerStartCorner.x, innerStartCorner.y, innerStart.x, innerStart.y)

  val outerStart = pt(outer - corner, a0)
  path.lineTo(outerStart.x, outerStart.y)

  val outerStartCorner = pt(outer, a0)
  path.quadraticTo(outerStartCorner.x, outerStartCorner.y, start.x, start.y)

  path.close()
  return path
}

@Composable
private fun LegendRow(slice: Slice, grand: Int) {
  val scheme = MaterialTheme.colorScheme
  val pct = if (grand <= 0) 0 else (slice.value * 100f / grand).roundToInt()
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(slice.color))
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = slice.label,
      style = MaterialTheme.typography.bodyMedium,
      color = scheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = stringResource(R.string.stats_percent, pct),
      style = MaterialTheme.typography.labelMedium,
      color = scheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 10.dp),
    )
    Text(
      text = "${slice.value}",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = slice.color,
    )
  }
}

/**
 * 明细压成一行：同一个进程下的分支合并（不在每一段前都重复一遍"系统界面 · "）。
 *
 * 例：`桌面 1205；系统界面：已适配 114 · 被补 4 · 代用应用图标 1`
 */
private fun compactDetail(rows: List<Pair<String, Int>>): String =
  rows.groupBy { it.first.substringBefore(" · ") }.entries.joinToString("；") { (proc, list) ->
    val inner = list.joinToString(" · ") { (label, n) ->
      val sub = label.substringAfter(" · ", "")
      if (sub.isEmpty()) "$n" else "$sub $n"
    }
    if (list.size == 1 && list.first().first == proc) "$proc $inner" else "$proc：$inner"
  }

/**
 * 一个功能的卡：标题 + 总次数 + 一条**堆叠条**（组内各来源按次数分段）+ 来源明细一行。
 *
 * 分段用同一个颜色的不同透明度：同属一个功能，不需要第二套色相；
 * 段与段之间留 2dp，两端走 50% 圆角（M3E 的胶囊语言）。
 */
@Composable
private fun FeatureChart(
  feature: String,
  rows: List<Pair<String, Int>>,
  grand: Int,
  color: Color,
) {
  val scheme = MaterialTheme.colorScheme
  val total = rows.sumOf { it.second }
  val pct = if (grand <= 0) 0 else (total * 100f / grand).roundToInt()

  OpticonCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      // 与总览环里那一段同色：两段之间不用再靠文字对位。
      Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = feature,
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${total}",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = stringResource(R.string.stats_times),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onSurfaceVariant,
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(14.dp)
        .semantics { contentDescription = rows.joinToString("，") { (label, n) -> "$label $n 次" } },
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      rows.forEachIndexed { i, (_, n) ->
        // 段长按"占本组总数的比例"画；最薄的留一点可见宽度，别把一个小分支画没。
        val weight = (n.toFloat() / total.toFloat()).coerceAtLeast(0.02f)
        Box(
          modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = (1f - i * 0.22f).coerceAtLeast(0.28f))),
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // 明细一行：`桌面 1205 · 系统界面 121`。条上的分段与这里的顺序一致。
    Text(
      text = compactDetail(rows) + " · " + stringResource(R.string.stats_share, pct),
      style = MaterialTheme.typography.bodySmall,
      color = scheme.onSurfaceVariant,
    )
  }
}

/**
 * `桌面/裁圆=1200;系统界面/通知/被补=12` → 按功能分组、组内按次数降序。
 *
 * 键里带进程名是刻意的：裁圆在桌面里发生、通知在系统界面里发生，混在一起会把同名的
 * 分支各加一遍，看起来像"裁了一万次"。
 */
private fun groupByFeature(snapshot: String?): List<Pair<String, List<Pair<String, Int>>>> {
  if (snapshot.isNullOrBlank()) return emptyList()
  val byFeature = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
  for (part in snapshot.split(';')) {
    val eq = part.lastIndexOf('=')
    if (eq <= 0) continue
    val n = part.substring(eq + 1).toIntOrNull() ?: continue
    val key = part.substring(0, eq)
    val slash = key.indexOf('/')
    val proc = if (slash > 0) key.substring(0, slash) else "?"
    val branch = if (slash > 0) key.substring(slash + 1) else key
    val feature = branch.substringBefore('/')
    val detail = if (branch.contains('/')) branch.substringAfter('/') else ""
    val label = friendlyProcess(proc) + if (detail.isEmpty()) "" else " · $detail"
    byFeature.getOrPut(feature) { ArrayList() }.add(label to n)
  }
  return byFeature.entries
    .map { it.key to it.value.sortedByDescending { p -> p.second } }
    .sortedByDescending { e -> e.second.sumOf { p -> p.second } }
}

private fun friendlyProcess(proc: String): String = when (proc) {
  "com.android.launcher3", "com.google.android.apps.nexuslauncher" -> "桌面"
  "com.android.systemui" -> "系统界面"
  "com.android.settings" -> "设置"
  else -> proc
}

private fun timeText(at: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
