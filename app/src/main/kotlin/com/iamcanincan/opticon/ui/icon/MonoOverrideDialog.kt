package com.iamcanincan.opticon.ui.icon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.circle.ACTION_CLEAR_ICON_CACHE
import com.iamcanincan.opticon.runtime.ModulePrefs

/**
 * **按应用合成**的调试面板：一张 `包名 → 合成 / 交回桌面 / 跟随默认` 的表。
 *
 * ## 为什么要有它，而不是我把判据调对
 * 判据的方向现在没有可信依据：真机带回的两张桌面遮罩，一张像「亮=墨」、另一张像「暗=墨」，
 * 互相打脸。这种时候继续调阈值就是猜。这个面板把决定权交回人眼 —— 一张一张定，定的结果
 * 立刻在真机上生效，同时变成一份**带标签的样本**（存在 [ModulePrefs.KEY_MONO_OVERRIDES]，
 * 跟着配置通道跨进程走），下一轮判据拿它当对答案的考卷，而不是凭我的印象。
 *
 * ## 改完为什么要自己按「清缓存并重启桌面」
 * 主题图标是桌面**预渲染好再缓存**的，配置只影响下一次合成；而 `adb force-stop` 杀不动
 * SystemUI 这类常驻进程（这台机器实测过，只有整机重启才行）。这个广播是签名权限保护的，
 * 只有本模块自己发得出去。面板不自动发 —— 那等于"改个设置就把桌面杀一遍"，得他主动按。
 */
@Composable
internal fun MonoOverrideDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { ModulePrefs.of(context) }
  var overrides by remember {
    mutableStateOf(ModulePrefs.parseOverrides(prefs.getString(ModulePrefs.KEY_MONO_OVERRIDES, null)))
  }
  var query by remember { mutableStateOf("") }
  val apps = remember { launchablePackages(context) }

  fun store(next: Map<String, Boolean>) {
    overrides = next
    prefs.edit()
      .putString(ModulePrefs.KEY_MONO_OVERRIDES, ModulePrefs.serializeOverrides(next))
      .apply()
  }

  // null = 跟随默认 = 从表里删掉。整串重写而不是增量改：表小，且 provider 一次查一行。
  fun set(pkg: String, value: Boolean?) {
    val next = HashMap(overrides)
    if (value == null) next.remove(pkg) else next[pkg] = value
    store(next)
  }

  val shown = if (query.isBlank()) apps else apps.filter {
    it.name.contains(query, true) || it.pkg.contains(query, true)
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("按应用合成（调试）") },
    confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    text = {
      Column {
        OutlinedTextField(
          value = query, onValueChange = { query = it },
          label = { Text("搜应用名或包名") },
          singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
          Text("已标 ${overrides.size} 个", style = MaterialTheme.typography.labelMedium)
          OutlinedButton(onClick = { store(emptyMap()) }) { Text("清空") }
          OutlinedButton(onClick = { bounceLaunchers(context) }) { Text("清缓存重启") }
        }
        // 「改了没反应」的头一种原因不是 bug：默认就是全部合成，于是「合」和「随」是同一件事，
        // 只有「交回」会真的不一样。这句话必须写在面板上，不该靠人自己推。
        Text(
          "默认=全部由我们合成 → 标「合」与「随」结果相同，只有「交回」会变（交回后那张会是" +
            "桌面自己的反色）；改完要按「清缓存重启」，光重启只重建极少数几张",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 固定高度：AlertDialog 的内容槽没有给定的高度上限，用 weight 会直接塌成 0。
        LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
          items(shown, key = { it.pkg }) { app ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.bodyMedium)
                val marked = overrides[app.pkg]
                Text(
                  // 每一行直接写出**这一张最后归谁画**，比"标了什么"有用：标了「合」而默认也是合，
                  // 就该看得见"同默认"三个字，而不是去猜为什么没变化。
                  // 前面那一截是它自己给不给单色层：已适配的应用我们一个像素都不碰，
                  // 那一行会一直显示"不生效" —— 这是"标了却没反应"的头一种解释。
                  app.pkg + "　" + when (app.mono) {
                    true -> "自带单色层→不生效"
                    false -> "未适配"
                    null -> "非自适应图标"
                  } + "　生效=" + if (marked == null) "合成(随默认)"
                  else if (marked) "合成(同默认)" else "交回桌面",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              TinyChoice("合", overrides[app.pkg] == true) { set(app.pkg, true) }
              TinyChoice("回", overrides[app.pkg] == false) { set(app.pkg, false) }
              TinyChoice("随", overrides[app.pkg] == null) { set(app.pkg, null) }
            }
          }
        }
      }
    }
  )
}

/** 桌面系的应用：清缓存与重启只发给它们（与 IconScreen 上那组运维按钮同一批包名）。 */
private fun bounceLaunchers(context: Context) {
  for (pkg in listOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")) {
    context.sendBroadcast(Intent(ACTION_CLEAR_ICON_CACHE).setPackage(pkg))
  }
}

/** 三态里的小按钮：选中实心、未选中描边。一行放三个，所以宽度压到最小。 */
@Composable
private fun TinyChoice(text: String, selected: Boolean, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding = PaddingValues(horizontal = 8.dp),
    modifier = Modifier.height(32.dp)
  ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

private class AppEntry(val pkg: String, val name: String, val mono: Boolean?)

/**
 * 这个应用**自己**给不给单色层。给 = 我们的功能对它本来就没有作用对象（硬规则：已适配的一个
 * 像素都不碰），此时「正/反/随」怎么标都不会有变化 —— 这句话以前只能靠日志推，现在写在行上。
 *
 * 用反射：`getMonochrome()` 是 API 33 才有的，而本包 minSdk 26；拿不到方法就当"不知道"。
 */
private fun shipsMonochrome(pm: PackageManager, pkg: String): Boolean? = runCatching {
  val d = pm.getApplicationIcon(pkg) as? AdaptiveIconDrawable ?: return@runCatching null
  AdaptiveIconDrawable::class.java.getMethod("getMonochrome").invoke(d) != null
}.getOrNull()

/** 只列桌面上能看见的应用 —— 只有它们会被问单色层，列别的东西没有意义。 */
private fun launchablePackages(context: Context): List<AppEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
    .distinct().sorted()
    .map { pkg ->
      // 一次开面板要为每个应用解一次图标 —— 百来个应用、只在调试面板里，值得换那一列的可信度。
      AppEntry(pkg, runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
      }.getOrDefault(pkg), shipsMonochrome(pm, pkg))
    }
}
