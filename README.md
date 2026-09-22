# Opticon

**Opti**mize + **Icon** —— 一个 Xposed 模块，专治「图标不对劲」。

两处毛病，一个根源：**系统替你决定了图标长什么样，而那个样子不对**。

| 功能 | 解决什么 | 默认 |
|---|---|---|
| **强制圆形图标** | 桌面 / 应用列表 / 最近任务里的应用图标形状由 ROM 的 mask 决定（圆角方、水滴、方），不一定是圆。这一路把它**一律裁成正圆** | 开 |
| **修复通知小图标** | Android 12 起系统会强行把通知小图标统一着色，没做单色适配的应用会显示成一坨认不出是谁的灰白色块。这一路把它**换回能认出是谁的样子** | 开（系统黑白） |

一个管**桌面上的应用图标**被套上了不想要的形状，一个管**通知栏里的小图标**被染成了
认不出的颜色 —— 位置不同，干的都是同一件事：**把图标优化回它该有的样子**。

两个功能各有独立开关（只想开一个也行），改完**不需要重启**，
新加载的图标按新设置走。

## 安装

1. 从 [Releases](https://github.com/IamCanincan/Opticon/releases) 下载最新 APK 并安装
   （或按下面的「构建」自行编译）。
2. 在 LSPosed / Vector（或其它支持 LibXposed API 102 的框架）里启用模块。
   作用域**不写死**（`staticScope=false`）：下面这 10 个是模块自带的推荐清单，
   **任何会显示应用图标的应用都能在管理器里额外勾选** —— 第三方桌面、文件管理器、
   带分享面板的社交应用等，只要它自己会画别的应用的图标，勾上就生效：

   ```
   android                                  系统服务，解析应用信息
   system                                   系统进程，部分 ROM 在这里解析图标
   com.android.launcher3                    桌面与应用抽屉（类原生 ROM，必选）
   com.google.android.apps.nexuslauncher    桌面与应用抽屉（Pixel / Nexus，必选）
   com.android.systemui                     状态栏、最近任务、通知
   com.android.settings                     设置里的应用列表
   com.google.android.settings.intelligence 设置搜索与建议里的应用图标
   com.android.intentresolver               分享与打开方式的选择列表
   com.android.permissioncontroller         权限弹窗里的应用图标
   com.google.android.apps.wellbeing        数字健康的应用使用列表
   ```

   两个桌面进程按 ROM 二选一即可：类原生 / AOSP 用 `com.android.launcher3`，
   Pixel / Nexus 用 `com.google.android.apps.nexuslauncher`。勾选不存在的那个
   不会报错，只是没有进程会加载它。

   > **通知功能只需要 `com.android.systemui`** —— 通知是系统界面画出来的。
   > 上面其余几条是给图标裁圆用的。

3. **重启**（框架不会热加载模块）。

> 作用域要覆盖所有会解析图标的进程。模块会给应用图标的资源 id 打标记，只有被标记的进程
> 才认得这个标记；漏掉的进程会拿到无法解析的 id。
>
> 早期版本把作用域写死（`staticScope=true`），清单外的应用**永远**拿不到模块 —— 这正是
> 「覆盖不全」的根因：不是 hook 通道不够，而是那些进程根本没被注入。现已改为
> `staticScope=false`，任何应用都能自行勾选。

## 界面

应用有桌面图标，打开是**四页**，底部一个**悬浮药丸**导航 —— 页面按**内容归属**分，
不按「东西多不多」：

| 页面 | 内容 |
|---|---|
| **图标** | 裁圆总开关、效果示意、只跟裁圆有关的注意事项 |
| **通知** | 通知总开关、图标模式（系统黑白 / 彩色桌面图标）、怎么确认生效 |
| **模块** | 两个功能**共用**的东西：作用域清单、启用步骤、验证命令、运维按钮（清图标缓存 / 重启进程） |
| **关于** | 检查更新与版本信息 |

- **左右滑动也能切页**，和点底栏是双向联动的（当前页由 Pager 统一持有）。
- 底栏是**悬浮药丸**：内容会从它后面滚过去，选中项展开文字、未选中只留图标。
  四格是刻意定的 —— 三格时药丸太短，撑不起悬浮药丸该有的形态。
- **各页不再自带品牌头**：顶栏已经有 App 名，每页再来一遍标记 + 版本是重复。
  页面直接从内容开始，当前在哪一页由底栏高亮表示。
- 作用域清单用**分段按钮**（必选 + 推荐 / 全部）左右切换，而不是一个文案会变的
  "展开 / 收起"按钮 —— 这两档是并列的，分段控件更直白。
- 动效一律取自 `MaterialTheme.motionScheme`（MD3 Expressive 的弹簧参数），
  没有硬编码时长的 `tween`：Tab 选中胶囊的展开、颜色过渡、列表展开都跟着主题走。

作用域清单放在「模块」页而不是某个功能页，是因为它本来就有两半：
launcher / settings / system_server 那几条是给裁圆用的，而 `com.android.systemui`
同时服务两个功能（它既画通知，也画状态栏和最近任务的图标）。

> 界面全部走 Material 3 Expressive：圆角一律用 `MaterialTheme.shapes.*` 的语义档位、
> 内容卡一律 `ElevatedCard`、配色只用 `colorScheme` 的语义角色（不写死颜色），
> 所以浅色 / 深色两套主题都成立。

设置写进自己的 SharedPreferences，模块侧通过本应用暴露的**只读 ContentProvider**
（`com.iamcanincan.opticon.config`，读取需要签名级权限 `android.permission.STATUS_BAR`）
跨进程读取，按 1 秒 TTL 重读 —— 所以改完开关立刻生效，不用重启进程。

## 验证

```bash
adb logcat -s Opticon
```

两个功能共用这一个 tag，日志正文里带前缀区分是哪一路。

**图标裁圆**：正常时每个作用域进程会打印

```
module loaded in com.android.settings (systemServer=false)
attach in com.android.settings: framework=... api=102
options: circle=true notify=true mode=monochrome keepColor=false preserveTinted=true (source=provider)
install in com.android.settings, sdk 36
Hooked com.android.settings
```

桌面进程还会多一行 `PixelLauncher: 2 createBadgedIconBitmap hooked`（Pixel 上进程名是
`com.google.android.apps.nexuslauncher`）。

**通知小图标**：出现 `patched <包名> 2->1 via=monochrome` 就是替换成功了
（图标类型从「资源」变成「位图」，`via=` 后面是本次实际用的模式）。

`options:` 那行里的 `source=` 表示配置是从哪条通道读到的：正常情况下是 `provider`。
若显示别的来源，说明 provider 没查到，模块在用备选通道或默认值。

通道挂不上时还会打印原因（`... not found` / `ctors=0 hooked=0`），
这些告警只说明**该 ROM 上没有这个类**（例如某些 ROM 的 SystemUI 里 splash 相关类被 R8 削成空壳），
不影响别的通道。

如果只看到 `No icon loader is found, nothing is hooked`，说明当前系统里几个加载通道
（`Resources.getDrawableForDensity` / `Resources.getDrawable` 与
`ApplicationPackageManager.getDrawableInternal` / `getDrawable`）都没挂上，
模块会主动放弃打标记，此时不会有任何图标被改动（也不会弄坏图标）。

## 功能一：强制圆形图标

### 原理

1. 在 `ApplicationInfo` / `ActivityInfo` / `ServiceInfo` / `ProviderInfo` / `ResolveInfo` 构造完成后，
   把图标资源 id 从 `0x7f……` 挪到 `0x6e……`（一个不会有真实资源的 package id）作为标记。
2. 在 `Resources.getDrawableForDensity()`（以及新版本上的 `ApplicationPackageManager.getDrawableInternal()`）
   出口认出这个标记，换回原始 id 调用原方法拿到图标，再包成 `CircleIconDrawable` 返回。
   `android.R.drawable.sym_def_app_icon`（解析不出应用图标时用的兜底）和
   `getArchivedAppIcon`（归档应用）也会被同样裁圆。
3. **批量通道**：上面两步只能盖到「逐个拿图标」的代码路径。Settings 应用列表、分享页、
   权限页拿到的图标是 PMS 一次性序列化过来的一整包 `PackageInfo` / `ResolveInfo`，
   走的是完全不同的路径：`Parcel.readTypedList` / `createTypedArray`、
   `BaseParceledListSlice` 构造、`PackageInfoCommonUtils.generate*Info`。只 hook 第 1 步
   的构造器的话，这些列表里的图标根本不会经过第 2 步的加载出口。
   这几条也都挂上同样的「打标记」逻辑，加上 `markingIcons` ThreadLocal 防重入，
   覆盖才完整。
4. **出口还原**：`PackageItemInfo.writeToParcel` / `ResolveInfo.writeToParcel` 在写 Parcel
   之前把 `0x6e……` 还原成 `0x7f……`。**这条是安全闸门**：标记过的图标信息会经 Binder
   传给任意应用，而没被注入的应用没有还原能力 —— 拿到一个自己资源表里不存在的 id 会直接
   `Resources$NotFoundException` 崩在 `onCreate`（真机上表现为第三方应用闪退）。
5. **system_server 提前注入**：`onSystemServerStarting` 让模块在 PMS 启动前
   就装上，客户端拿到的 id 从头就是带标记的。`onPackageReady` 的「android」包名也走同一套
   装机函数，`installed` flag 防重复。
6. **最近任务 / 概览**：`com.android.quickstep.TaskIconCache.getBitmapInfo` 在把
   `BitmapDrawable` 包成 `BitmapInfo` 之前，先替换成 `clipToCircle` 的结果。
7. **冷启动 splash**：`SplashscreenContentDrawer$ColorCache$IconColor` 构造时如果
   发现背景是透明的，强制把 `mIsBgComplex` 标成 `true`，让系统把整张图标画出来
   而不是只画不透明区域（圆形图标圆外透明正好落进这个判定）。
8. **设置页自适应包装**（Android 15+）：`com.android.settings.Utils.getAdaptiveIcon`
   会把非自适应图标自己套一层形状，先把入参换成裁好的，它就原样返回。
9. **`CircleIconDrawable` 继承 `AdaptiveIconDrawable`，形状自己画圆，不依赖系统 mask**。
   结构是 `背景透明 + 前景 = 套过圆形遮罩的原图标`：
   - 只提供遮罩层，前景 / 背景两个装饰层留空 —— 形状完全由那个圆决定。
   - 遮罩用 `EVEN_ODD` 的「整块矩形 + 圆」现造（重叠处计数为偶 → 成为洞，
     填出来的正是圆外那一圈），配合 `DST_OUT` 把圆外擦掉。
   - 缩放由外层 `ViewportDrawable` 负责，`getIntrinsicWidth/Height` 报 `原尺寸 / scale`。
     **缩放别在画的时候自己算** —— 那样 `AdaptiveIconDrawable` 报出的固有尺寸会只有 2/3。
   - **必须重写 `draw()` 且不调 `super.draw()`**：父类 `draw()` 会在合成之后再套一次
     系统 mask，形状就变回 ROM 决定的那个（圆角方 / 水滴 / 方）。
10. **Android 16+（BAKLAVA）** 额外 hook `BaseIconFactory.createBadgedIconBitmap`（Pixel / AOSP Launcher3），
    把 `IconOptions.drawFullBleed` 设成 `false`，让 launcher 不再加自己的白圆背景板。
    旧版 Android 没有这个开关，hook 自动 no-op。
11. **防重入**：`markingIcons`（打标记时用，防止生成 Info 的几条路径互相嵌套重复打）
    和 `replacingIcon`（图标加载时用，防止 `Resources` / `APM` 在同一条链上双层包装）
    两个 ThreadLocal。

### 开关关掉时

裁圆的判定收在**一处** —— `clipToCircle()`：所有替换通道最终都汇到这里，
开关关掉就把原图标原样交回去（图标 id 本身是换回原始值之后才解析的，
拿到的就是应用原本的图标）。

⚠ **读不到配置的进程会用默认值（功能全开）**：作用域可以由用户在管理器里额外勾选，
那些进程不在 provider 的可信名单里、也没有 `STATUS_BAR` 权限，拿不到配置。
对它们而言「关掉开关」不生效。真正决定全局的是 **system_server**：它在名单里，
读到关闭就不给图标 id 打标记，下游任何进程拿到的都是未经处理的原图标。

### 运维按钮（纯 LSP，不 root 不 shell）

界面里的三个按钮走的是「App 发广播点名 → **目标进程里的模块**自己执行」：

- **清除缓存并重启桌面**：删 launcher 自己数据目录里的 `app_icons.db*`，然后自杀重启
- **重启 SystemUI** / **重启其他进程**：白名单进程收到就自杀重启

白名单 = launcher3 / nexuslauncher / systemui / settings / settings.intelligence /
intentresolver / permissioncontroller / wellbeing。
⚠ **绝不能含 `system` / `system_server`** —— 杀它们等于软重启整机。

> 测之前**必须先把目标进程重启过**，否则它跑的是旧模块代码、接收器还没注册
> （会误判成「按钮没生效」）。

## 功能二：修复通知小图标

### 两种模式

| 模式 | 做法 | 适合 |
|---|---|---|
| **系统黑白通知**（默认） | 把 App 自己给的小图标压成单色剪影，交给系统按主题着色 | 想和其它通知风格统一 |
| **彩色桌面图标** | 换成 App 在桌面上那个图标，颜色原样保留 | 想让通知一眼认出是谁 |

两个模式的区别只在「未适配的图标怎么处理」；已经是单色 / 灰度的图标，两种模式下都不动。

### 它改的是哪个图标

模块改的是通知对象里的小图标字段（`Notification.smallIcon`）—— 就是被系统染色成一坨的那个。
它是通知内容区图标的来源，也是状态栏图标的来源，所以**不要在状态栏那行上找差异**：
真正直观的变化在通知内容区那个小图标，从灰白色块变回能认出是哪个 App 的样子。

配套还会阻止系统把替换后的图标重新染回单色（挂钩 `IconManager#setIcon`、
`StatusBarIconView#updateIconColor`、`Notification.Builder#processSmallIconColor`）。

### 几个关键约束

- **只动没适配的**：用和系统同款的灰度判定检测图标是否已做单色适配，已适配的直接跳过 ——
  免得把人家本来正确的图标改坏。
- **状态栏只取 alpha 通道渲染小图标** ⇒ 交出去的位图必须有意义的透明背景；
  不透明彩色图标必然变纯色块。
- **自适应图标光栅化是「遮罩贴边」的** ⇒ `fill()` 对整张是空操作，只对**前景层**才有意义。
- **尺寸填满**：自适应图标光栅化后内容只占画布中间约 61%，四周是空的。模块会先裁掉空白
  再放大填满，否则缩到通知里那点尺寸会又小又糊。
- 仅注入 `com.android.systemui`，不碰其他进程。

### 改模式为什么不生效

替换只发生在通知行**首次** `inflateViews` 时，**已经在通知栏里的那条不会变** ——
要等它重新加载（重新发出，或重启系统界面）。

## 应用图标

三层 adaptive icon（背景 / 前景 / 单色层），字符是 **Oi**：背景固定为品牌淡粉 `#FCE4EC`（Pink 50），**全 Android 版本一致，不跟随壁纸取色**
+ 大号黑字；monochrome 层只留白色剪影，染色后就是主题色的字形。

- 字形从 Google Sans Flex（可变字体）取轮廓算出的像素级 path（`wght=700 / opsz=18 / wdth=100`），
  **别手改坐标**，用 `<group android:pivotX/Y="54" android:scaleX/Y="k">` 缩放。
  - **底板颜色固定** `@color/opticon_icon_background` = 品牌淡粉 `#FCE4EC`（Pink 50），
    全 Android 版本一致，**不跟随壁纸取色**。
    （早期曾在 API 31+ 改用 `system_accent1_100` 跟随壁纸，后按用户要求改回全版本固定淡粉。）
    真正会随主题染色的只有 monochrome 层（见下）。
- Android 13+ 的启动器打开「主题图标」后，会拿 monochrome 层按壁纸取色染色。
- **⚠ `<monochrome>` 元素必须放在 `mipmap-anydpi-v33/`**：它是 API 33+ 才有的元素，
  放在没有版本限定符的目录里会被启动器忽略，主题图标就不生效了。
  所以 `mipmap-anydpi/` 和 `mipmap-anydpi-v33/` 两份必须同时存在、内容只差这一行。
- **改几何必须三处同步**（`ic_launcher_foreground` / `ic_launcher_monochrome` /
  `ic_brand_mark`），否则主题图标剪影会和桌面图标对不上。
- 界面里一律用 `ic_brand_mark`（黑色 + Compose tint）：启动器那两份是硬编码深色、
  且按图标比例算的缩放，直接贴到界面上会「深色模式下看不见 + 字号小得看不清」。

## 已知取舍

- 快捷设置磁贴（`BIND_QUICK_SETTINGS_TILE`）画的是小尺寸单色图形，被排除在外。
- **强制动态取色下的遮罩是一律翻转的**：桌面为未适配应用生成的遮罩，Opticon 全部翻一次，
  不逐个判断它「原本是不是反的」。绝大多数未适配图标因此变正确；但如果你有个别图标翻完
  反而变成实心块 / 空心，说明它原本的极性就是对的，目前没有针对单个应用的例外机制。
- 通知栏小图标、快捷方式以外的小图标走的是别的资源，不受影响。
- **桌面图标 & 点击过渡动画**：`CircleIconDrawable.getConstantState()` 实现非 null，
  `FloatingIconView.getIconResult()` 取 `newDrawable()` 时不会 NPE，点击不崩。
- **system_server 注入时机**：boot 时框架 daemon 经常晚于 `system_server`，
  此时 `onSystemServerStarting` 没机会执行。实际效果看 daemon 何时起来 ——
  客户端 `onPackageReady` 里的批量通道通常已经够用。重启设备或调整框架启动时机可以改善。
- 界面里的「检查更新」是**本应用唯一的联网行为**，而且只在点了按钮之后才发起，
  没有后台轮询、没有统计上报；挂钩代码（跑在被注入进程里的部分）完全不联网。
  请求先直连 `api.github.com`，失败时回退到公共加速镜像 `gh-proxy.com`。

> 排查提示：如果 App 报「解析不了域名」，而 `adb shell` 里 `curl` 同一个地址是通的，
> 那不是 DNS 问题 —— 是 `netpolicy` 里这个 uid 的陈旧记录把它设成了 `REJECT_ALL`
> （记录是在 App 还没有 `INTERNET` 权限时建立的，`adb install -r` 不会刷新它）。
> **卸载后全新安装**即可重建，重启无效。

## 构建

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug
```

需要 Android SDK 37（在 `local.properties` 里配好 `sdk.dir`）与 JDK 17+。

- `assembleRelease` 产出的包用 debug 密钥签名（`signingConfigs.getByName("debug")`），
  方便直接装；正式发布请换自己的 keystore。
- release 开了 R8（`isMinifyEnabled` + `isShrinkResources`）。入口类
  `com.iamcanincan.opticon.entry.OpticonModule` 由 proguard `-keep` 保名 ——
  它同时出现在 `META-INF/xposed/java_init.list` 里，改包名时**三处必须一起改**
  （`namespace` / `applicationId`、proguard `-keep`、`java_init.list`）。
- 依赖版本集中在 `gradle/libs.versions.toml`。⚠ `material3` 必须显式写版本，
  不能用 BOM 托管：MD3E 的公开 API（`MaterialExpressiveTheme`）在稳定版里还是 internal。

## API 102（libxposed）合规性

- 声明 `minApiVersion=102` / `targetApiVersion=102`。框架只按 `targetApiVersion` 决定加载方式
  （>= 101 即走 `META-INF/xposed/java_init.list` 的现代路径）。
- 只调用 `io.github.libxposed.api.*`，不依赖 `hiddenapibypass`。
- `staticScope=false`：作用域不固定，用户可在管理器里额外勾选任意应用。
- `autoHotReload=false`（框架默认值，显式写出）：改动代码后必须重启目标进程。
- `exceptionMode=protective`（框架默认值，显式写出）：hook 里抛出的异常由框架吞掉，
  不让单个图标加载失败带崩 launcher / systemui —— 这两个都是常驻关键进程。

### 模块元数据分别落在哪

| 内容 | 位置 |
|---|---|
| 模块名 | `AndroidManifest` 的 `android:label`（`@string/app_name`）|
| **模块描述** | `AndroidManifest` 的 `android:description`（`@string/xposed_description`）|
| 作用域 | `META-INF/xposed/scope.list` |
| 模块配置 | `META-INF/xposed/module.prop` |
| Java 入口 | `META-INF/xposed/java_init.list` |

管理器读的是 `ApplicationInfo.descriptionRes`，**不是** `module.prop` 里的 `description=`。
后者是 API <= 93 的旧写法，混着写会让管理器把模块当「兼容模式」处理，
作用域列表会退化成列出全部已装应用。Manifest 里也不放任何 `xposed*` meta-data。

另：`META-INF/xposed/` 下的文件是**原样打进 APK** 的（AAPT2 不处理非 res 目录），
所以 `module.prop` 里一行注释都没有 —— 写什么用户解包就能看到。
字段说明放在 `OpticonModule` 的 KDoc 和本文档。

## 代码结构

```
com.iamcanincan.opticon
├── entry/OpticonModule        模块入口：按进程分发两套挂钩
├── circle/                    ← 功能一：应用图标裁圆
│   ├── IconHooks              各条 hook 通道的安装与拦截逻辑
│   └── CircleIconDrawable     圆形 drawable（自适应图标 + 圆形遮罩）
├── notify/                    ← 功能二：通知小图标
│   ├── SystemUiHooks          往 SystemUI 里装挂钩
│   ├── NotificationIconPatch  要不要替换、换成什么的判定
│   ├── IconBitmap             位图的取、裁、合成
│   └── ToneCheck              单色（已适配）判定
├── runtime/                   两个功能共用
│   ├── ModuleRuntime          进程内共享状态：宿主 Context、配置读取（多通道回退 + TTL）
│   ├── ModulePrefs            配置的键、默认值、界面侧的读写
│   ├── ModuleOptions          运行期行为开关
│   ├── ConfigProvider         只读配置 provider（跨进程把设置交给模块）
│   ├── MemberLookup           反射取成员的薄封装
│   └── OpticonLog             统一的日志 tag
├── ui/
│   ├── MainActivity           入口 Activity（同时是桌面图标）
│   ├── OpticonApp             外壳：顶栏 + 悬浮药丸导航 + Pager（左右滑动切页）
│   ├── Common                 各页共用：ScreenHero / Section / SectionLabel /
│   │                          VersionChip / IconTile / FloatingNavSpace
│   ├── icon/IconScreen        「图标」页（裁圆）
│   ├── notify/NotifyScreen    「通知」页（通知小图标）
│   ├── module/ModuleScreen    「模块」页（作用域 / 步骤 / 验证 / 运维）
│   ├── about/AboutScreen      「关于」页（检查更新 / 版本信息）
│   └── theme/Theme            M3E 主题（动态取色）
└── update/UpdateChecker       检查更新（唯一的联网点）
```

## 许可

MIT —— 见 [LICENSE](LICENSE)。

## 更新日志

### v1.0.3（2026-09-22）
- 修复：点「清除缓存并重启桌面 / 重启 SystemUI / 重启其他进程」后弹出的提示（Snackbar）被底部悬浮导航栏挡住、截断的问题。提示现在显示在底栏上方，完整可见。
- 调整：桌面图标底板**全 Android 版本固定品牌淡粉** `#FCE4EC`，不再在 Android 12+ 跟随壁纸取色（移除了 `values-v31/colors.xml` 的 `system_accent1_100` 覆盖）。

### v1.0.2（2026-09-22）
- 整合发布：将「应用图标强制裁圆」与「通知小图标修复」两个既有模块整合为一个 Xposed 模块，四页悬浮药丸界面，并统一文案口径（Opticon = Optimize + Icon，两件事落在不同位置，但干的都是把图标优化回该有的样子）。
