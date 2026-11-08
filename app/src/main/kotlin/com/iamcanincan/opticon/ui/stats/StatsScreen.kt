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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
fun StatsScreen(onNotify: (String) -> Unit) {
  val context = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  // 版本号 = 订阅宿主上报；tick 仍留给「刷新」（读盘而不等广播）。
  val rev = StatsStore.revision.intValue
  val loaded = remember(rev, tick) { StatsStore.load(context) }
  val groups = remember(loaded) { groupByFeature(loaded.first) }
  val totals = groups.map { (_, rows) -> rows.sumOf { it.second } }
  val grand = totals.sum()
  val perf = remember(rev, tick) { StatsStore.loadPerf(context) }
  val scope = rememberCoroutineScope()
  val exported = stringResource(R.string.stats_export_done)
  val nothing = stringResource(R.string.stats_export_empty)

  ScreenList {
    item { PageRole(stringResource(R.string.stats_role)) }

    item {
      SummaryCard(
        groups = groups,
        totals = totals,
        grand = grand,
        at = loaded.second,
        perf = perf,
        onRefresh = { tick++ },
        onExport = {
          // 导出就是把明细文件复制到外部目录（adb pull 直接可拉）。
          // 文件几百 KB 以内，放 IO 线程；结果用 Snackbar 说清楚路径。
          scope.launch {
            val path = withContext(Dispatchers.IO) { StatsStore.exportEvents(context) }
            onNotify(if (path != null) exported.format(path) else nothing)
          }
        },
      )
    }

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
 * 图表配色：**跟随动态取色**（2026-11-08 主人：「你还是用动态取色吧，太丑了」）。
 *
 * 顺序 主色 → 三级色 → 次色：这套壁纸取出来的 primary 与 secondary 都是青系，
 * 把 tertiary 放在第二位，两个大扇形之间才有色相差；第三段（通常很小）用 secondary。
 * 饼不再切缝（相邻块直接相接），所以"灰"不会再被读成一道缝。
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
  perf: String?,
  onRefresh: () -> Unit,
  onExport: () -> Unit,
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
      TextButton(onClick = onExport) { Text(stringResource(R.string.stats_export)) }
      TextButton(onClick = onRefresh) { Text(stringResource(R.string.stats_refresh)) }
    }
    Text(
      text = if (at == 0L) stringResource(R.string.stats_empty)
      else stringResource(R.string.stats_at, timeText(at)),
      style = MaterialTheme.typography.bodySmall,
      color = scheme.onSurfaceVariant,
    )

    perfLines(perf).forEachIndexed { i, line ->
      Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (i == 0) 2.dp else 1.dp),
      )
    }

    Spacer(modifier = Modifier.height(18.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Pie(slices = slices, grand = grand, sweep = sweep)
      Spacer(modifier = Modifier.width(18.dp))
      Column(modifier = Modifier.weight(1f)) {
        // 总数从环心挪到图例上方：饼图没有洞，字压上去读不清。
        Row(verticalAlignment = Alignment.Bottom) {
          Text(
            text = "$grand",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.stats_total_unit),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
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
 * 饼图：整饼 + **等宽直线缝**（2026-11-08 主人：「每个扇形应平行地分开」→「你这也太不规则了」）。
 *
 * 试过三种"分开"：
 *  · 角度缝 —— 所有缝在圆心汇成一点，看着是裂了；
 *  · 每块沿角平分线外移（爆开式）—— 圆形轮廓被拆散，主人判"太不规则"；
 *  · **现在这种**：饼本身是一个完整的圆（轮廓规整），每道边界上盖一条 2dp 的直线缝，
 *    缝是一根等宽直条，两边平行、从圆心到外缘一样宽，外缘只留一个等宽的缺口。
 *
 * 缝的颜色取卡片底色（饼画在卡里，等于"挖掉"），所以深浅两套主题都不用另配色。
 * 进来时按主题 motionScheme 从 0 扫到满。
 */
@Composable
private fun Pie(slices: List<Slice>, grand: Int, sweep: Float) {
  val scheme = MaterialTheme.colorScheme
  val description = if (slices.isEmpty()) "" else buildString {
    append(grand).append(" 次处理；")
    slices.forEachIndexed { i, s ->
      if (i > 0) append("，")
      append(s.label).append(" ").append(s.value).append(" 次")
    }
  }
  Box(modifier = Modifier.size(126.dp).semantics { contentDescription = description }) {
    Canvas(modifier = Modifier.size(126.dp)) {
      val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
      val radius = size.minDimension / 2f

      // 底盘：没有数时是满圆灰饼，读起来像"占比图的空态"，而不是一块空白。
      drawCircle(color = scheme.surfaceContainerHighest, radius = radius, center = c)
      if (grand <= 0 || sweep <= 0.01f) return@Canvas

      // 画出来的角度用"显示值"：1% 只有 3.6°，被两道 2dp 的缝一夹就成了一条线
      // （真机截图确认过），所以小于 8° 的段在画面上抬到 8°。图例里的百分比仍按真实值写。
      val shown = slices.map { s ->
        val raw = 360f * s.value.toFloat() / grand.toFloat()
        if (s.value > 0 && raw < 8f) maxOf(raw, 8f) else raw
      }
      val shownTotal = shown.sum()

      var start = -90f
      slices.forEachIndexed { i, s ->
        if (s.value > 0) {
          val deg = shown[i] * 360f / shownTotal
          // 相邻两块之间重叠 0.4°：不画缝了，但两段各自抗锯齿会在接缝留一条发丝线，
          // 重叠一点就没了（颜色差异足够大，不需要再靠缝区分）。
          drawArc(
            color = s.color,
            startAngle = start,
            sweepAngle = (deg + 0.4f) * sweep,
            useCenter = true,
          )
        }
        start += shown[i] * 360f / shownTotal
      }


    }
  }
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

/**
 * `crop:1200000/4;mono:34000000/2;monoCold:…/…` →
 * 第一行 `耗时 单色 23.2ms/张（780 次）· 裁圆 2.7ms/张（861 次）`
 * 第二行 `单色构成 快键 0.1ms×624 · 缓存 4.9ms×156 · 冷合成 108ms×… · 等锁 1.0ms`
 *
 * 为什么要第二行：一个「单色 23.2ms/张」的均值把**等锁、快键命中（亚毫秒）、
 * 内容缓存命中（几毫秒）、真冷合成（上百毫秒）** 四档混在一起，看到 23ms 没法判断
 * 下一刀该砍哪里。分档也只有配上**张数**才读得懂（平均值会被少数慢图拉走）。
 */
private fun perfLines(perf: String?): List<String> {
  if (perf.isNullOrBlank()) return emptyList()
  val names = mapOf(
    "crop" to "裁圆", "mono" to "单色",
    "monoFast" to "快键", "monoCache" to "缓存", "monoCold" to "冷合成",
    "monoWait" to "等锁", "monoInst" to "实例命中",
  )
  val avg = HashMap<String, Pair<Double, Long>>()
  for (seg in perf.split(';')) {
    val k = seg.substringBefore(':', "")
    val v = seg.substringAfter(':', "")
    val ns = v.substringBefore('/').toLongOrNull() ?: continue
    val n = v.substringAfter('/', "0").toLongOrNull() ?: continue
    if (n <= 0) continue
    avg[k] = (ns.toDouble() / n / 1_000_000.0) to n
  }
  fun ms(k: String, withCount: Boolean): String? {
    val (m, n) = avg[k] ?: return null
    val text = (if (m >= 100) "%.0f" else "%.1f").format(m) + "ms"
    return (names[k] ?: k) + " " + if (withCount) "$text（$n 次）" else "$text×$n"
  }
  val head = listOfNotNull(ms("mono", true), ms("crop", true))
  val split = listOfNotNull(
    ms("monoFast", false), ms("monoCache", false), ms("monoCold", false),
    ms("monoWait", false), ms("monoInst", false),
  )
  return listOfNotNull(
    head.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "耗时 "),
    split.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "单色构成 "),
  )
}

private fun timeText(at: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
