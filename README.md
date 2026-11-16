# Opticon

**Opti**mize + **Icon** —— 一个 Xposed 模块，把图标优化回它该有的样子。

| 功能 | 解决什么 | 默认 |
|---|---|---|
| **自适应** | 老式（非自适应）图标没有背景层与安全区，只能被系统整张套形状。模块把它做成自适应图标：先填满正方形，再按可见占比分两支 —— 占比高的按边缘颜色延伸补满透明边角，稀疏线条缩进内切圆铺白底；形状交给桌面的「图标形状」设置。自适应图标与快捷方式原样不动 | 开 |
| **动态取色** | Android 16 的主题图标把「亮度当 alpha」，深色字形 + 浅色底的图标整个画反。模块自己合成一张极性正确的单色层交上去，桌面那套算法根本不进；应用自带单色层的一个像素不碰 | 开 |
| **单色通知** | 没做单色适配的通知小图标被系统染成认不出的灰白色块。模块把它压成能认出是谁的单色剪影，交给系统按主题着色；已适配的图标一律不动 | 开 |

三个功能各有独立开关，改完不用重启 —— 模块会自动清缓存并重启相关进程，新图标按新设置走。

当前发布：**[v1.2.0](https://github.com/IamCanincan/Opticon/releases/tag/v1.2.0)**（2026-11-16，versionCode 14）。

## 安装

要求 Android 8.0+，以及 LSPosed / Vector 等支持 **LibXposed API 102** 的框架。

1. 从 [Releases](https://github.com/IamCanincan/Opticon/releases) 下载 APK 安装。
2. 在管理器里启用模块并勾选作用域。推荐清单：

   ```
   android                                  系统服务，解析应用信息
   system                                   系统进程，部分 ROM 在这里解析图标
   com.android.launcher3                    桌面（类原生 ROM，与下一条二选一）
   com.google.android.apps.nexuslauncher    桌面（Pixel / Nexus，与上一条二选一）
   com.android.systemui                     状态栏、最近任务、通知
   com.android.settings                     设置里的应用列表
   com.google.android.settings.intelligence 设置搜索与建议
   com.android.intentresolver               分享与打开方式
   com.android.permissioncontroller         权限弹窗
   com.google.android.apps.wellbeing        数字健康
   ```

   作用域不写死：**任何会显示别的应用图标的进程**（第三方桌面、文件管理器、分享面板等）都可以额外勾选，勾上就生效；没勾的进程显示原图标，不会崩。
   单色通知只需要 `com.android.systemui`；动态取色要桌面 + 系统界面两条。
3. 重启手机（框架不热加载模块）。

## 界面

四个一级页（主页 / 功能 / 统计 / 设置）+ 七张二级页，Material 3 Expressive，支持预测式返回手势。

- **主页**：运行状态 + 三个功能的处理次数，点一行进该功能的说明与效果页。
- **功能**：三个开关（只有开关本身可点）。任意一个动了都会自动做全套运维操作，图标随即重画。
- **统计**：三个功能各自的处理次数 —— 哪一格是 0 就说明它没生效。
- **设置**：作用域 / 启用与验证 / 运维操作 / 关于。

桌面自己的「主题图标」开关一变，模块也会自动让图标重画 —— 走桌面进程内刷新，不杀任何进程，样式面板可以一直开着。

### 运维按钮（纯 LSP，不 root 不 shell）

「清除缓存并重启桌面」「重启 SystemUI」「重启其他进程」三个按钮，由目标进程里的模块自己执行，入口由 signature 级权限把闸（只有本模块签名的应用能发）。不重启目标进程就测按钮会误判 —— 旧进程里还没有接收器。

## 已知取舍

都是量死后有意不修的边界，完整过程见 [docs/KNOWN-LIMITS.md](docs/KNOWN-LIMITS.md)：

- 一层 alpha 只能表达一个量：与底同色相又同亮度的细节画不出来。
- 满幅插画按自身明暗铺三档层次，极性固定「暗 = 墨」；亮主体压暗底的插画会被反。
- 自带单色层的应用一个像素不碰；快捷方式原样放行，由桌面自己画。
- 应用声明了 `roundIcon` 时桌面用它（平台画得就对）、应用信息页用 `icon`（模块接管），两屏天然不同源。
- 稀疏线条类图标会带一块白底；不留底盘，透明边角由图标自己的边缘色延伸补满。
- 剪影按内容缓存（内存 + 磁盘），热重启几乎全部命中；改了 `circle/` 代码后的第一次重启要现算一遍，会慢一些。

## 构建

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

需要 Android SDK 37 与 JDK 17+（`local.properties` 配 `sdk.dir`）。签名读仓库根的 `keystore.properties`；文件不存在时回退 debug key 并打警告 —— 能装但**不能对外发布**。换签名后不能覆盖安装，须先卸载。

## 开发

- 判据台在 `_harness/`：纯 JVM 跑仓里那份真 `MonochromeMask.kt`，`sh regress.sh` 一条命令跑 25 道回归闸，任何一项比基线差就非零退出；装机后另有「剪影幅面账」（`silgate.sh`）对设备产物复核。玩法见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。
- 模块只调用 `io.github.libxposed.api.*`；`staticScope=false`、`exceptionMode=protective`（单个图标失败不带崩宿主）。
- 每一刀的完整过程（为什么收、为什么拒）见 [docs/devlog/](docs/devlog/)。

## 许可

MIT —— 见 [LICENSE](LICENSE)。变更摘要见 [CHANGELOG.md](CHANGELOG.md)。