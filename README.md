# Opticon

**Opti**mize + **Icon** —— 一个 Xposed 模块，把图标优化回它该有的样子。

| 功能 | 解决什么 | 默认 |
|---|---|---|
| **自适应** | 将老式应用图标和快捷方式包装成自适应图标。先识别主体和衬底，再沿轮廓补边，或把稀疏图形放入安全区并加对比底色。最终形状由桌面的「图标形状」设置决定 | 开 |
| **动态取色** | Android 16 的主题图标把「亮度当 alpha」，深色字形 + 浅色底的图标整个画反。模块自己合成一张极性正确的单色层交上去，桌面那套算法根本不进；应用自带单色层的一个像素不碰 | 开 |
| **单色通知** | 没做单色适配的通知小图标被系统染成认不出的灰白色块。模块把它压成能认出是谁的单色剪影，交给系统按主题着色；已适配的图标一律不动 | 开 |

三个功能各有独立开关，改完不用重启 —— 模块会自动清缓存并重启相关进程，新图标按新设置走。

当前发布：**[v1.2.0](https://github.com/IamCanincan/Opticon/releases/tag/v1.2.0)**（2026-11-16，versionCode 14）。

## 安装

APK 最低支持 Android 8.0。运行模块需要支持 **LibXposed API 102** 的框架，已在 **Vector 2.2 + LineageOS 24.0（Android 17）** 真机验证。原版 LSPosed 1.9.2 和只提供 API 101 的 Vector 2.1 不满足模块的 API 要求。

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

   第三方桌面、文件管理器和分享面板等可按需加入作用域，以使用通用的自适应图标处理。动态取色的专用 Hook、最近任务图标与桌面主题刷新目前面向上表的两个桌面包名。
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
- 优先使用应用自带的有效单色层。缺失或接近整块底盘的单色层由模块自动生成。
- 桌面和系统设置各自请求图标资源。应用提供不同版本的图案时，仍可能出现宿主间的外观差异。
- 稀疏线条类图标使用对比底色。连续轮廓的图标沿自身边缘补色。
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
