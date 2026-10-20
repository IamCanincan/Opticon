package com.iamcanincan.opticon.circle

/**
 * 「这一条 `label` 是不是某个包的」——剥出来的理由与本仓 `MonoKey` / `IconCropWindow` / `MonoVeto` 同一条：
 * 不碰 `Bitmap` / `Drawable` 的算式才编得进 `_harness/`，判据才有尺。
 *
 * `label` 有两种形式（来源不同，形状不同）：
 *  · `包名/类型/入口名` —— 走 `Resources.getDrawable*` 那几条口子，能问到资源名；
 *  · `pm:包名`（或 `pm:包名/…`）—— PackageManager / `LauncherApps.getShortcutIconDrawable`
 *    那几条口子里参数表只有包名，根本没有资源可问（见 `packageFromArgs`）。
 *
 * ⚠ 2026-10-20 之前按包的闸全部只写成 `label.startsWith("包名/")`，于是**所有快捷方式
 * 对任何按包开的刀永久隐形** —— 装机量出来 `?` 里 95% 是非自适应，而 B 类那几张"只剩虚线框"
 * 恰好全是快捷方式。这一台子的第 4 条负控制钉的就是这个洞：拿旧写法去配 `pm:` 必须**不命中**。
 */
internal object IconLabel {

  fun matchesPkg(label: String, pkg: String): Boolean =
    label.startsWith("$pkg/") || label == "pm:$pkg" || label.startsWith("pm:$pkg/")
}
