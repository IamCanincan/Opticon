// 「可见圆里一格透明都没有 ⇒ 这块自画单色层交出去必是一块饼」这条闸的尺。
// 用法：`sh run.sh harness.VetoKtKt <目录…> --want=<该开火的包名,逗号分隔>`
//   （目录里是 `*_mono.png` = 应用自画的单色层；可以给多个目录，**并成一个分母**一起判）
//
// 为什么要有这一台：装机那条路随时可能不在（设备 unauthorized / 主人不方便重启桌面），
// 而这条闸一旦误伤就是**改掉一张本来正常的主题图标**。所以它的射程必须能在离线用真图量出来。
// 三件事一起钉：① 开火集合 = 只有该开火的那几张；② 窗口是**可见圆**不是整张画布（第三条负控制）；
// ③ 一张都没读到 / 金标准那张不在目录里 ⇒ 非零退出（不许交空结论）。
//
// ⚠ 2026-10-22 两处改动，都是被真图逼出来的：
//  · **`--want` 变成必填**（原来硬写死 `[org.lineageos.etar]`）：飞鸟的自画层今天进了 `adapted_model`，
//    于是"要 = [etar]"当场判红 —— 那不是回归，是**期望值写死在工具里、分母长大了**。期望挪到调用方 + pin。
//  · **`_two/` 那份手做的飞鸟不再需要**：同一张现在有真抓的那份在库里（`--also=` 留着，但闸门不靠它）。
//    原来那份在 `.gitignore` 的 `/_*/` 底下 ⇒ 干净克隆上这一闸根本跑不了，是一个静默的复现洞。
package harness

import com.iamcanincan.opticon.circle.IconLabel
import com.iamcanincan.opticon.circle.MonoVeto
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private fun gridOf(f: File, e: Int): IntArray {
  val src = ImageIO.read(f) ?: error("读不出 " + f.name)
  val bmp = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
  val g = bmp.createGraphics()
  g.drawImage(src, 0, 0, e, e, null)   // 与设备上 Drawable.draw 同一条：双线性缩放到探针尺寸
  g.dispose()
  return IntArray(e * e).also { bmp.getRGB(0, 0, e, e, it, 0, e) }
}

private fun cov(px: IntArray, e: Int): Double {
  val c = (e - 1) / 2f
  val r2 = (e * MonoVeto.DISC) * (e * MonoVeto.DISC)
  var n = 0; var k = 0
  for (y in 0 until e) { val dy = y - c; for (x in 0 until e) {
    val dx = x - c; if (dx * dx + dy * dy > r2) continue; n++
    if (((px[y * e + x] ushr 24) and 0xff) > 0) k++ } }
  return k.toDouble() / n
}

fun main(args: Array<String>) {
  val e = MonoVeto.EDGE
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  // 期望值**必须由调用方给**（见文件头）：没给就退出，不许拿工具里那份硬编码去过任何分母。
  val wantArg = args.firstOrNull { it.startsWith("--want=") }?.substringAfter('=')
  if (wantArg == null) {
    println("!! 缺 --want=<该开火的包名,逗号分隔> —— 期望值写死在工具里，分母一长大就会假红（今天就是这么撞的）")
    kotlin.system.exitProcess(2)
  }
  val want = wantArg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.sorted()

  // ---- 三条控制：正 / 负 / 窗口 ----
  val opaque = IntArray(e * e) { 0xFF000000.toInt() or (it and 0xFFFFFF) }
  val oneHole = opaque.copyOf().also { it[(e / 2) * e + (e / 2)] = 0 }
  val cornersClear = opaque.copyOf().also { g ->
    val c = (e - 1) / 2f
    for (i in 0 until e * e) {
      val x = i % e; val y = i / e; val dx = x - c; val dy = y - c
      if (dx * dx + dy * dy > (e * MonoVeto.DISC) * (e * MonoVeto.DISC)) g[i] = 0   // 圆外全清
    }
  }
  var bad = 0; var checks = 0
  fun expect(name: String, got: Boolean, want: Boolean) {
    val ok = got == want
    checks++; if (!ok) bad++
    println("  ${if (ok) "✓" else "✗ 红"} $name → $got（要 $want）")
  }
  println("== 控制 ==")
  expect("整块不透明", MonoVeto.wholePlate(opaque, e), true)
  expect("圆心挖一格洞", MonoVeto.wholePlate(oneHole, e), false)
  // 这一条才是"窗"的负控制：整张画布看它 66% 是空的，可见圆里却一格不缺。
  // 拿旧那条「按整张画布的不透明覆盖率」当尺的，会在这里答 false。
  expect("只有圆外是空的（窗必须是可见圆）", MonoVeto.wholePlate(cornersClear, e), true)

  // ---- 按包配名那条算式的尺（2026-10-20 补：它当天是没有尺就落的地）----
  val pkg = "com.xunmeng.pinduoduo"
  expect("资源名形式命中", IconLabel.matchesPkg("$pkg/mipmap/ic_launcher", pkg), true)
  expect("pm: 形式命中（快捷方式那一支）", IconLabel.matchesPkg("pm:$pkg", pkg), true)
  expect("pm: 带后缀也命中", IconLabel.matchesPkg("pm:$pkg/one_click_search", pkg), true)
  // 防"恒真"：换一个包必须配不上
  expect("别的包不许命中", IconLabel.matchesPkg("pm:$pkg", "com.jingdong.app.mall"), false)
  // 防少了那道斜杠（`com.a.bc` 会被 `startsWith("com.a.b")` 误配）
  expect("同前缀的另一个包不许命中", IconLabel.matchesPkg("com.a.bc/mipmap/x", "com.a.b"), false)
  // ⚠ 这条是**只有旧写法会红**的负控制：旧算式 `label.startsWith("$pkg/")` 对 `pm:` 恒为 false，
  //   而"所有快捷方式对按包刀永久隐形"就是它造成的。新算式必须 true。
  val oldWay = "pm:$pkg".startsWith("$pkg/")
  expect("旧算式配 pm: 必须是配不上（钉的就是那个洞）", !oldWay && IconLabel.matchesPkg("pm:$pkg", pkg), true)

  // ---- 快捷方式标签的拼法（2026-10-20：`getShortcutIconDrawable` 现代签名是 (ShortcutInfo, UserHandle)，
  //      而旧 `packageFromArgs` 三个分支对它全不匹配 ⇒ 登记成空串。这几条钉的就是那一支。----
  val scId = "shortcuts_identity_code"
  val scLabel = IconLabel.shortcutLabel(pkg, scId)
  expect("带 id 的标签形状", scLabel == "pm:$pkg/$scId", true)
  expect("桌面库 componentName 与标签同形（斜杠后就是 id）", scLabel.removePrefix("pm:") == "$pkg/$scId", true)
  expect("按包的闸对带 id 的形式仍然认", IconLabel.matchesPkg(scLabel, pkg), true)
  expect("同包的另一个快捷方式也认（整包一起开没丢）",
    IconLabel.matchesPkg(IconLabel.shortcutLabel(pkg, "order"), pkg), true)
  // 防"只要带 pm: 就放行"：别的包的快捷方式必须配不上
  expect("别的包的快捷方式不许命中",
    IconLabel.matchesPkg(IconLabel.shortcutLabel("com.jingdong.app.mall", scId), pkg), false)
  expect("id 为空退回整包形式", IconLabel.shortcutLabel(pkg, null) == "pm:$pkg", true)
  // ⚠ 这条是**只有"没补 ShortcutInfo 那一支"才会红**的负控制：旧代码返回空串，
  //   而空串过不了任何按包的闸 —— 也就是说"登记成空"与"根本没登记"在闸眼里是同一件事。
  expect("空串过不了按包的闸（钉今天那个洞：登记成空 = 永久隐形）",
    IconLabel.shortcutLabel(null, scId) == "" && !IconLabel.matchesPkg("", pkg), true)

  // ---- 真图射程（可多个目录，并成一个分母）----
  println("\n== ${dirs.joinToString("+") { it.name }} 里的应用自画单色层 ==")
  val files = dirs.flatMap { d ->
    (d.listFiles { f -> f.isFile && f.name.endsWith("_mono.png") }?.toList() ?: emptyList())
  }
  if (files.isEmpty()) {
    println("!! 一个 `*_mono.png` 都没找到 —— 分母是空的（缺语料不等于「这台机器上没有自画层」）")
    kotlin.system.exitProcess(1)
  }
  if (files.size < 40) { println("!! 只找到 ${files.size} 张（<40）—— 这台子在比一个薄目录"); kotlin.system.exitProcess(1) }
  val rows = files.sortedBy { it.name }.map { f ->
    val px = gridOf(f, e)
    Triple(f.name.removeSuffix("_mono.png"), cov(px, e), MonoVeto.wholePlate(px, e))
  }
  rows.filter { it.third }.forEach { println("  开火 ${it.first}  可见圆有墨 ${(it.second * 100).toFixed(1)}%") }
  val top = rows.sortedByDescending { it.second }.take(6)
  println("  最高的六张：" + top.joinToString(" ") { it.first.substringBefore('_') + " " + (it.second * 100).toFixed(1) + "%" })

  if (!rows.any { it.first.startsWith("org.lineageos.etar") }) {
    println("!! 金标准 org.lineageos.etar 不在这份目录里 —— 这一跑不能证明任何事"); kotlin.system.exitProcess(1)
  }
  val fired = rows.filter { it.third }.map { it.first.substringBefore('_') }.distinct().sorted()
  // **这台子自己看得见多少**：开火线是"可见圆里一格透明都没有"（= 覆盖率 100.0%），
  // 所以"离误伤还差几张"= 覆盖率落在 **95%~99.99%** 之间的张数。现量 0 ⇒ 线与下一张之间是空的；
  // 哪天这里不再是 0，就说明有一张卡在刀口上，必须逐张看图再动。
  val nearBand = rows.count { !it.third && it.second >= 0.95 }
  println("\n开火集合 = $fired   要 = $want   近线带(95%~100%) = $nearBand 张")
  // `--also=<某张自画层.png>`：把名单里那张（飞鸟）也过**同一把尺**。它不在任何语料目录里，
  // 而"删掉名单不许退步"这一条恰恰要它开火 —— 换一台仪器量它，等于没量。
  val also = args.firstOrNull { it.startsWith("--also=") }?.substringAfter('=')
  var alsoFires = true
  if (also != null) {
    val px = gridOf(File(also), e)
    alsoFires = MonoVeto.wholePlate(px, e)
    println("额外那张 ${File(also).name.substringBefore('_')}：可见圆有墨 ${(cov(px, e) * 100).toFixed(1)}% → " +
      (if (alsoFires) "开火 ✓" else "✗ 不开火 —— 名单删了它就退步"))
  }
  if (fired != want || bad > 0 || !alsoFires) { println("!! 射程与预期不符"); kotlin.system.exitProcess(1) }
  println("OK ${rows.size} 张自画层里只开火 ${fired.size} 张，控制 $checks/$checks 绿")
  // 机器行（回归台钉的是这一整行，逐字比）：分母张数 + 开火集 + **近线带**（这台子自己的分辨率）。
  println("VETO n=${rows.size} fired=[${fired.joinToString(",")}] want=[${want.joinToString(",")}] " +
    "near=$nearBand 控制=$checks/$checks 目录=[${dirs.joinToString(",") { it.name }}]")
}

private fun Double.toFixed(n: Int): String = String.format(java.util.Locale.ROOT, "%.${n}f", this)
