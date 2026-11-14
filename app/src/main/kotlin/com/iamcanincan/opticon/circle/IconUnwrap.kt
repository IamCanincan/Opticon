package com.iamcanincan.opticon.circle

import android.graphics.drawable.Drawable

/**
 * 「给我**没被我们包装过**的那张原图」这一件事的唯一口径。
 *
 * 为什么要单独一个文件、还要挂进判据台的编译清单（`_harness/build.sh` 的 `SRC`）：
 * 这个函数原本写在 [CircleIconDrawable] 里，而那个类要用 `BuildConfig`、`ModuleRuntime`、
 * 一整套 Android 绘图 API —— 判据台编不动它。于是 `notify/IconBitmap.kt`（台子要编，通知语料
 * 第 25 闸吃它）一调 `unwrappedIcon` 就 `unresolved reference`，**整台从 `d1ec3e5` 起编不过**，
 * 后面六段全没跑（2026-11-15 才发现， regress 第 1 段 `set -e` 直接吞掉，只留下一行"shell 雷 ✓"）。
 * 把那三处调用改成台子自己抄一份是**镜像**，正是本仓第十五/十六闸反复钉死的东西；
 * 所以这里把依赖收窄到一个只吃 `Drawable` 的接口上：生产与台子编的是同一份函数体。
 *
 * 谁实现这个接口 = 谁被剥壳。目前只有 [CircleIconDrawable] 一家。
 */
internal interface IconSourceCarrier {
  /** 原图标（包装之前那一张），通知的剪影必须从它的 alpha 压出来。 */
  val source: Drawable
}

/**
 * 取回**没包装过**的原图（不是 [IconSourceCarrier] 就原样返回）。
 *
 * 给通知那两条消费方用：SystemUI 里 `PackageManager.getApplicationIcon` 与
 * `Resources.getDrawable*` 也会被这套钩子包起来，而通知的剪影必须从**原图**的 alpha 取 ——
 * 拿"按形状裁过一刀"的那份去压，剪影会缺角。
 */
internal fun unwrappedIcon(d: Drawable): Drawable = if (d is IconSourceCarrier) d.source else d
