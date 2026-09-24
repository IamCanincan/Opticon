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
| **图标** | 裁圆总开关、单色遮罩极性开关、效果示意、只跟裁圆有关的注意事项、运维按钮（清图标缓存 / 重启进程） |
| **通知** | 通知总开关、图标模式（系统黑白 / 彩色桌面图标）、怎么确认生效 |
| **模块** | 两个功能**共用**的东西：作用域清单、启用步骤、验证命令 |
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
（`com.iamcanincan.opticon.config`，只读，按调用方包名认自家人）
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

**桌面主题图标（强制取色）**：每个图标一行，行首带**包名/资源名**，报一个应用名就能
直接 grep 到它那一行：

```
Mono: diag#7 edge=384 com.tencent.hunyuan.app.chat AdaptiveIconDrawable fg=BitmapDrawable@108x108 …
  支=刻底 底占=0.52 容ΔE=6 实ΔE=76 框内=0.33 cc=3 top=0.95 小片=0.00 空洞=0.15 淡=0.01 → 已交出掩码
```

`支=` 走的哪一支（`透明底` = 图标自带的 alpha；`刻底` = 按离底距离刻；`无` = 放弃合成）、
`底占` 被摘掉的底占框比例、`容ΔE` 这一轮底板自己的散度（洪泛容差）、`实ΔE` 归一用的实心
参照、`框内` 交出去的掩码占框比例、`空洞/top/cc/小片` 形状体检、`淡` 只落在 alpha<128 那一层
的像素。放弃合成时是另一行 `放弃合成：<原因>`，其中「整张图标已是灰度」是**正常**的
「应用自己适配过、一个像素都没碰」。
⚠ 这些数只在**图标被重新加载时**产生：桌面缓存里是预渲染好的位图，光重启桌面进程只会
重算新加载的那几个，要看全量分布必须先按界面里的「清除缓存并重启桌面」。

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
那些进程不在 provider 的包名白名单里，拿不到配置。
对它们而言「关掉开关」不生效。真正决定全局的是 **system_server**：它在名单里，
读到关闭就不给图标 id 打标记，下游任何进程拿到的都是未经处理的原图标。

### 单色遮罩的极性（Android 16 强制取色）

Android 16 的「强制动态取色」会给**没做单色适配**的应用现场生成一张单色遮罩
（`com.android.launcher3.icons.MonochromeIconFactory`），而这张遮罩的**极性是反的**：
深色字形 + 浅色底的图标会变成「字形是洞、底色是形」，看着整个反掉。

现在有两条路，**互斥、不需要开关**：

1. **首选：合成**（`MonochromeSynth` + `MonochromeMask`）。钩 `AdaptiveIconDrawable.getMonochrome()`，
   在它返回 null（= 应用没提供单色层）时**直接补一个极性正确的单色层**。桌面只在这个
   getter 返回 null 时才去 new 那个 Factory，所以合成一接上，那条算错的路径根本不进。
   分工：**`MonochromeSynth` 只管画布与坐标**（取哪一层、按桌面要的尺寸出图、非自适应图标算完
   之后才缩进可见区、ALPHA_8 的 rowBytes），**`MonochromeMask` 只管形状** —— 一个纯数组进出的
   对比度模型：先估「这一像素*周围*那块底」（局部底色场），再按 CIELAB 量它离那块底差多少，
   最后按这张图自己的对比范围归成一条 alpha 斜坡。判据与来路见「已知取舍」。
   ⚠ 它与通知小图标**不再是同一份实现**（以前共用四个入口，共用逼着每条判据对两边都成立，
   结果两边都不准）：通知交出去的是完整位图、且先 `fill` 把内容放大填满画布；桌面只有一条
   alpha、且必须对齐 108dp 画布。两边现在只剩 `rasterize` / `foregroundOf` 这种没有判据的事共用。
2. **兜底：翻转**。合成接不上（框架拦不到 `getMonochrome()`、或合成返回 null）时，
   桌面照旧走 Factory，模块再把那张 ALPHA_8 掩码就地翻一次（`a → 255 - a`）。

「已适配的不动」这件事不用判条件 —— 桌面只在 `getMonochrome()` 为 null 时才 new 这个
Factory，自己带单色层的应用根本走不到这条路径；合成那条同理：getter 非 null 就直接透传，
一个像素都不碰。

这个功能整体是**独立开关**（图标页 →「修正单色图标的极性」），和裁圆分开：
极性跟形状没有因果关系。默认开着，与之前版本的行为一致；它是逃生口 ——
只有在某个 ROM / 桌面版本上实测**不该动**（厂商自己修过极性、或某个图标包本就反着出）
时才需要关。**关掉之后合成与翻转一起停**，桌面完全照它自己的方式处理。
判定按 TTL 在调用时读，切开关不用重启桌面（已缓存的图标仍要重建）。

### 运维按钮（纯 LSP，不 root 不 shell）

界面里的三个按钮走的是「App 发广播点名 → **目标进程里的模块**自己执行」：

- **清除缓存并重启桌面**：删 launcher 自己数据目录里的 `app_icons.db*`，然后自杀重启
- **重启 SystemUI** / **重启其他进程**：白名单进程收到就自杀重启

白名单 = launcher3 / nexuslauncher / systemui / settings / settings.intelligence /
intentresolver / permissioncontroller / wellbeing。
⚠ **绝不能含 `system` / `system_server`** —— 杀它们等于软重启整机。

接收器是**动态注册且必须导出**的（发送方是另一个 uid），所以入口由权限把闸：
本 APK 自己声明一个 `signature` 级权限
`com.iamcanincan.opticon.permission.PROCESS_CONTROL`，`registerReceiver` 一侧要求它。
同签名才拿得到 —— 没有这一道的话，任何第三方应用发一条
`com.iamcanincan.opticon.RESTART_SELF` 就能反复杀桌面和 SystemUI。
注意权限只加在接收侧：`sendBroadcast` 再传一次会变成检查**接收方**是否持有，
而宿主进程并不持有它。

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
- **改几何必须两处同步**（`ic_launcher_foreground` / `ic_launcher_monochrome`），
  否则主题图标剪影会和桌面图标对不上。
  （早先还有第三份 `ic_brand_mark` 专供界面使用，已在「界面大改」那次连同
   `ScreenHero` / `VersionChip` 一起删掉 —— 界面现在用 `Icons.Default.*` 矢量图标。）

## 已知取舍

- 快捷设置磁贴（`BIND_QUICK_SETTINGS_TILE`）画的是小尺寸单色图形，被排除在外。
- **刻底板不在 256 上做**（虽然桌面最终就是把这一层渲成 `iconBitmapSize`＝256 的 ALPHA 位图，
  我们按 384 出图等于多算 2.25 倍像素）。想过，量过，**不做**：掩码帧一旦按 256 出，
  `MonochromeLayer.draw()` 要把它画进 384 的图层 bounds（一次双线性放大），桌面再 384→256
  缩回来 —— 净效果是**两次重采样、更软**，正对着「太糊」那条反馈。省下的开销买不来画质。
- **浓度是一条连续斜坡，不是几档离散色**。最早规定过「图标就该有三种颜色（深浅由系统给、
  中间档取两档中值）」，后来两次放宽（「上限不管了，每处都单独上色」、「渐变恢复成渐变」），
  现在由 `MonochromeMask` 的两点标定直接给：底 → 0、这张图自己的形层分位 → 255、中间连续。
  ⚠ 这条不是"想加档就加"的老难题 —— 那个两难（档数一多就把渐变底切成好几个形层）建立在
  「底是一个颜色」的前提上，换成**局部底色场**之后前提没了。
- **不做「同色相不同亮度」以外的额外层次通道**。一层 alpha 只能表达一个量：与这张图自己的
  底**不可分**的东西（同色相同亮度）本来就画不出来，任何只用 alpha 的做法都一样。
  模型对此的处理是照实给出低浓度（而不是删掉或硬造一档），日志里 `淡=` 就是这一层。
- **兜底的「整张翻转」不逐个判断极性**：合成接不上时（框架拦不到 `getMonochrome()`、
  或合成返回 null），桌面为未适配应用生成的遮罩仍会被 Opticon **整张翻一次**，
  不去判断它「原本是不是反的」。绝大多数未适配图标因此变正确；个别图标翻完反而变成
  实心块 / 空心，说明它原本的极性就是对的 —— 目前没有针对单个应用的例外机制。
  ⚠ 这是**兜底路径**才有的取舍。首选路径是合成（见下面那条），走合成时桌面压根不进
  `MonochromeIconFactory`，这条「一律翻转」也就不适用了。
- 通知栏小图标、快捷方式以外的小图标走的是别的资源，不受影响。
- **桌面图标 & 点击过渡动画**：`CircleIconDrawable.getConstantState()` 实现非 null，
  `FloatingIconView.getIconResult()` 取 `newDrawable()` 时不会 NPE，点击不崩。
- **system_server 注入时机**：boot 时框架 daemon 经常晚于 `system_server`，
  此时 `onSystemServerStarting` 没机会执行。实际效果看 daemon 何时起来 ——
  客户端 `onPackageReady` 里的批量通道通常已经够用。重启设备或调整框架启动时机可以改善。
- 界面里的「检查更新」是**本应用唯一的联网行为**，而且只在点了按钮之后才发起，
  没有后台轮询、没有统计上报；挂钩代码（跑在被注入进程里的部分）完全不联网。
  请求先直连 `api.github.com`，失败时回退到公共加速镜像 `gh-proxy.com`。
- **`allowBackup="true"` 且没写备份规则** —— 这是**有意识的选择，不是疏漏**：
  本应用的数据只有 `ModulePrefs` 的两个布尔 + 一个模式枚举，以及「检查更新」的缓存，
  没有凭据、没有用户内容，被备份带上云不构成实际风险。而模块的**作用域**由框架自己的
  管理器保存（不在本应用数据里），所以「跟着设备迁移」也不会带出一份错误的作用域。
  真要收紧就把这一项改成 `false`，功能不受任何影响。
- **强制取色：合成优先，翻转兜底**：Android 16 桌面在「强制动态取色」下会为没适配的
  应用现场生成掩码，而它的极性经常是反的。现在首选路径是**合成**（`MonochromeSynth`）：
  钩 `AdaptiveIconDrawable.getMonochrome()`，在它返回 null 时补一个极性正确的单色层，
  桌面因此根本不进 `MonochromeIconFactory`；原先那道「把桌面算出来的掩码整张翻回来」
  保留为**兜底** —— 合成挂不上或抛异常时自动接管。两条路互斥，不需要开关。

  合成走的是 `circle/MonochromeMask.kt` 里那一个**对比度模型**（纯数组进、纯数组出，
  判据台跑的就是这一份），只问一句「**这个像素与它*周围*那块底差多少**」：
  1. **底 = 内容框四个角上的那几族颜色，铺成一张局部色场。** 每个角往里找一个落在底上的窗口
     （圆角/圆形图标角点是空的，所以沿对角往里走），得到 ≤4 个颜色家族；洪泛一次穿过**所有**
     家族 —— 底是它们的并集。⚠ 不是"哪个候选摘掉的面积多就选哪个"：真机的底常常是**几块拼的**
     （高德四块、云闪付红白两段、饿了么橙蓝两段），只选一个家族，剩下那块底整块变成墨。
     然后把每格的底色**传播**到整张画布：一个像素的参照色 = 离它最近的那块底，不是"它落在
     哪个格子里" —— 按格子取会让字形在格界上跳一级浓度，也会把两块底之间的抗锯齿混色算成墨。
     中缝另有一条按位置的吸收（两侧底色差得开、且这块够细才吞）。
     「拟合场 → 重算距离 → 重新洪泛 → 吸收中缝」跑 3 轮，容差每轮由底板自己的散度定
     （下限 `TOL_MIN` 兜着：网格一细，散度会被量成 0，洪泛随之只容得下逐像素同色，渐变尾端
     整个留在掩码里 —— 这是所有局部背景模型的通病）。渐变底在每个格子里都有自己那一份，
     所以**渐变不需要并层、不需要档内插值补偿**。
  2. **差 = CIELAB ΔE76**，整数查表，再按像素自己的 alpha 收一道覆盖率。不用 RGB 曼哈顿距离，
     也不只用亮度。
  3. **alpha = 两点标定 + 一条单调曲线**。零点 = 底板自己的散度（这张图量出来的，不是绝对值），
     实心点 = 形层距离的 66 分位，中间过 `t^0.75`。于是「一整块色板」不是被闸门拦掉的，
     是**产生不出来** —— 底板与自己比必然差 0。
  只剩一支例外：**透明底**。底是「没有东西」时色场没有可比对象，差退成覆盖率 = 图标自带的
  alpha（原样保留、不做形态学）；但会再补一步**内部镂空**：参照物换成图形自己的主色，
  只把**被主体围住**、又与局部主色差得多的那部分按差多少镂空（贴着背景的那一半是图形的
  另一部分，不算镂空）。
  ⚠ 这条链与通知小图标**不再是同一份实现**：通知交出去的是完整位图、且先 `fill` 把内容
  放大填满画布；桌面只有一条 alpha、且必须对齐 108dp 画布。以前共用四个入口，共用逼着
  每条判据对两边都成立，结果两边都不准 —— 现在两边只剩 `rasterize` / `foregroundOf`
  这种没有判据的事是共用的。
  还有**最后一级**：这张图根本没有"底"可摘（整张插画、照片底 —— 四族底也只摘得掉一小块）
  时，不退回桌面，而是把**它自己的明暗层次**搬到 alpha 上（越暗越实，端点按这张图的
  p5/p95 量）。只有光栅化失败、或整张连 12 个 L\* 都没散开（一张纯色，确实没有层次可搬）
  才返回 null。
  ⚠ 但**回落不是中性的**：桌面那条 Factory 算的就是错极性，所以放弃率上升是回归信号。
  界面上的「修正单色图标的极性」开关是逃生口：关掉就什么都不补，桌面照它自己的方式处理。

> 排查提示：如果 App 报「解析不了域名」，而 `adb shell` 里 `curl` 同一个地址是通的，
> 那不是 DNS 问题 —— 是 `netpolicy` 里这个 uid 的陈旧记录把它设成了 `REJECT_ALL`
>
> 主题图标看着不对（一片色 / 缺边 / 形状反）时，日志里有 `Mono: diag#N` 一组行：
> - **判据行**（每个图标一行、不打上限）：`支=` 走的哪一支、`底占` 被摘掉的底、
>   `容ΔE` 这一轮底板自己的散度（= 洪泛容差）、`实ΔE` 归一用的实心参照、
>   `框内` 交出去的掩码占**内容外接框**的比例、`空洞/top/cc/小片` 形状体检、
>   `淡` 只落在 alpha<128 那一层的像素、`镂空` 透明底那一支刻出的内部镂空；
> - **重诊断行**（每进程限 12 条）：输入 alpha 的直方图 `a0/a1/a2/a255`、掩码的
>   `out`（≥128 占比）与 `fill`（按 alpha 加权的等效覆盖率）、`cc` / `top`，
>   外加一张 40×40 的 ASCII 缩略图（`#` 不透明、`.` 透明，再限 8 张）。
> 判读：`框内` 就是判据自己吃的那一个口径 —— 上一轮「白块」的教训是数打在**画布**上、
> 一整块底板只量到 0.47、看着完全健康。`fill` 明显大于 `out` = 掩码里塞了一大片淡色；
> `cc=1`、`top=1.00`、`空洞=0.00` 是「一整块实心」的特征，那说明**输入**本身就是一块底。
> `容ΔE` 与 `实ΔE` 是这次新加的两个数：前者是"这张图的底散到多远"，后者是"这张图自己
> 认为多差算实心"，两个都是**逐图量出来的**，不是全局常数 —— 观感不对时先看这两个数
> 是不是被某类图（重渐变底、柔光字形）顶到了边上。
> `adaptive=` 是这批埋点里最关键的一位：`false` 表示这是非自适应图标，
> 掩码走的是「满幅判定 → 缩进可见区」那条路。
> 汇总行**逐条打**（不打上限 —— 采样太少会让真正出问题的那一类在日志里一次都不出现），
> 只有二值剪影样本的 ASCII 缩略图限 8 张（图标加载是热路径，打多了会把 logcat 环形缓冲挤爆），
> 发版前可降级/移除。
> （记录是在 App 还没有 `INTERNET` 权限时建立的，`adb install -r` 不会刷新它）。
> **卸载后全新安装**即可重建，重启无效。

## 构建

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug
```

需要 Android SDK 37（在 `local.properties` 里配好 `sdk.dir`）与 JDK 17+。

- `assembleRelease` 的签名取仓库根的 `keystore.properties`（`.gitignore` 已覆盖它和
  `*.jks`；**keystore 本体刻意放在仓库外**，见 .gitignore 里那条注释的立场）。
  文件不存在时**回退到 debug key** 并打一条构建警告 —— clone 下来照样能出一个能装的包，
  但那个包**不能对外发布**：debug key 的指纹是公开的（`CN=Android Debug`），
  任何人都能签出「同包名 + 同签名」的 APK，而本模块持有 `signature` 级的
  `PROCESS_CONTROL` 权限（能反复杀桌面 / SystemUI）。
  ⚠ 签名换掉之后**不能覆盖安装**：已装旧签名的设备必须先卸载（本应用自己的配置会丢，
  框架侧的作用域选择不受影响）。
- release 开了 R8（`isMinifyEnabled` + `isShrinkResources`）。入口类
  `com.iamcanincan.opticon.entry.OpticonModule` 由 proguard `-keep` 保名 ——
  它同时出现在 `META-INF/xposed/java_init.list` 里，改包名时**四处必须一起改**
  （`namespace` / `applicationId`、proguard `-keep`、`java_init.list`、
   以及 `AndroidManifest` 里 provider 的 `android:authorities` —— 模块侧是按
   「包名 + `.config`」拼 URI 的，漏掉它就是配置通道整条断掉、全线退回默认值）。
- 依赖版本集中在 `gradle/libs.versions.toml`。⚠ `material3` 必须显式写版本，
  不能用 BOM 托管：MD3E 的公开 API（`MaterialExpressiveTheme`）在稳定版里还是 internal。

## 开发：离线判据台与真图标重放

取色这条链的判据全是数字，而数字**不能靠推演定**。仓库里 `_harness/`（被 `.gitignore` 的
`/_*/` 挡住，不入库）是一台纯 JVM 的判据台：手写一组 `android.graphics` 桩，把**仓里那份真
`MonochromeMask.kt`** 连驱动一起喂给 kotlinc，跑的是同一份代码而不是副本。

```sh
cd _harness && sh build.sh
java -cp out;stubs_out;<stdlib> harness.SelfTestKt        # 合成性质
java -cp out;stubs_out;<stdlib> harness.MaskBenchKt --shot --inset corpus
java -cp out;stubs_out;<stdlib> harness.ReplayKt ../_dump.txt captured   # 重放真机抓回来的图标
```

- **`SelfTest`** —— 自己造图，一张只有一个变量，断的是**性质**不是像素：底必须归 0、渐变底的
  **两端**都要归 0、透明底的轮廓与抗锯齿不许动、被围住的镂空要出来、贴着边的另一半 logo
  不许被当镂空擦掉、柔光不许切成骨架、整张同色必须明确不成立、以及**尺寸不变性**
  （同一张图在 192/256/384 上结论不许漂 —— 他报的「时对时错」就是这一类没守住）。
  还有一条对着**公开参考值**核整数 Lab 表（⚠ 参考值有 D65 与 D50/ICC 两套，同一个
  `#00FF00` 的 b\* 是 83.2 与 71.4，混过一次，报出来是个假故障）。
- **`MaskBench --shot`** —— 真实图标 + 一张「原图 | 浅色主题 | 深色主题」预览 PNG
  （桌面拿 alpha 当不透明度上色，所以预览就是 `plate×(1-a)+ink×a`）。
- **`Replay`** —— 把 `MonoDump` 从真机带回来的 PNG 拼回去重跑。**这条是必需的，不是便利**：
  他报的那批应用发的是 adaptive **vector** XML 或 **webp**，JVM 这边一张都解不出来，
  没有重放就等于对着代理图调参。抓回来的样本在 `captured/`。

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
│   ├── MonochromeSynth        强制取色：画布与坐标（取哪一层、尺寸、可见区、ALPHA_8、缓存）
│   ├── MonochromeMask         强制取色：形状（局部底色场 + ΔE 距离 + 两点标定的 alpha 斜坡）
│   └── CircleIconDrawable     圆形 drawable（自适应图标 + 圆形遮罩）
├── notify/                    ← 功能二：通知小图标
│   ├── SystemUiHooks          往 SystemUI 里装挂钩
│   ├── NotificationIconPatch  要不要替换、换成什么的判定
│   ├── IconBitmap             位图的取、裁、合成（通知那半边，桌面已不再共用）
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
│   ├── Common                 各页共用：Section / SectionLabel / IconTile / FloatingNavSpace
│   ├── icon/IconScreen        「图标」页（裁圆 + 单色极性 + 运维按钮）
│   ├── notify/NotifyScreen    「通知」页（通知小图标）
│   ├── module/ModuleScreen    「模块」页（作用域 / 步骤 / 验证）
│   ├── about/AboutScreen      「关于」页（检查更新 / 版本信息）
│   └── theme/Theme            M3E 主题（动态取色）
└── update/UpdateChecker       检查更新（唯一的联网点）
```

## 许可

MIT —— 见 [LICENSE](LICENSE)。

## 更新日志

### 未发布（v1.0.3 之后）
- 修复：作用域里**不持有系统签名权限的应用根本读不到配置**。配置通道原本在包名白名单之外还加了一道 `readPermission="android.permission.STATUS_BAR"`，本意是防普通应用，实际把自家人也防了 —— 真机实测 `com.google.android.apps.wellbeing` 与 `com.android.permissioncontroller` 每个 TTL 查一次、每次被 `SecurityException` 挡掉，最后**只能按默认值干活**（界面上关掉开关对它们不生效），另外每秒往 logcat 刷一行失败日志。撤掉这道锁，只留 `isTrustedCaller()` 那道按包名的判断（名单仍与作用域逐条对齐）。撤锁后 permissioncontroller 立刻打出 `provider ok: …`，同一次查询用 shell 发则返回空游标而非权限拒绝 —— 陌生人依旧进不来。
- 新增：**单色遮罩极性**独立开关（图标页「修正单色图标的极性」）。之前这个反色行为是无条件的，只能改代码重刷；默认保持开着，行为不变。
- 修复：批量打标记通道（`Parcel.readTypedList` / `createTypedArray` / `BaseParceledListSlice`）因为重入守卫套了两层而**完全空转**，一个图标 id 都没打上。受影响最实的是 `ResolveInfo` —— 它的 `activityInfo` / `icon` 要到 `readFromParcel` 才有值，构造时打标那条路对它走不通，批量这条正是唯一补救。表现为分享面板、默认应用选择页里的图标保持方角。
- 修复：图标页三个运维按钮的广播接收器导出但**没有任何权限要求**，任何第三方应用都能反复杀桌面 / SystemUI、反复清桌面图标缓存。现在要求同签名才有的 `PROCESS_CONTROL` 权限。
- 修复：`SystemUiHooks.isInstalled()` 恒为 false（那个标志位全仓没有一处赋值），装钩成功后的「别再接着试」闸门从不放行。
- 修复：「检查更新」的结果和协程都活在 Pager 的那一页里，切一页结论就没了、后台那次 HTTP 请求也不会因取消而中断。现在由外壳持有；Snackbar 同理。
- 修复：更新跳转链接原本直接采用响应里的 `html_url`，而直连失败时那份响应来自公共加速镜像（第三方）。现在 host 不是 `github.com` 就退回自己拼的地址。
- 性能：SystemUI 每条通知都带 `CONTEXT_INCLUDE_CODE` 去 `createPackageContext`，那会建目标应用的类加载器、还会把 `packageReady` 重入进来；取图标只需要资源，去掉这个 flag。
- 性能：挂钩里 25 处 `chain.proceed(args.toTypedArray())` 改成零参 `proceed()`（`getArgs()` 返回 `List`，每次都要白拷一份数组，而那条钩挂在进程内**每一张** drawable 上）；圆形图标的离屏 `Canvas` 与遮罩 `Path` 改为按尺寸复用。
- 健壮性：配置快照改为 volatile 发布 + 不可变对象，并给重读加互斥闸（system_server 里是多个 binder 线程同时进热路径）；provider 未就绪时的三条回退通道从「每秒在宿主主线程跑一遍」退避到 30 秒一次。
- 整理：所有开关默认值收进 `ModulePrefs` 的 `DEFAULT_*` 常量（原先 circle/notify 的 `true` 散在界面、provider、prefs 四处）。
- 修复：配置通道每次成功都打一行 `provider ok: …`，而它按 TTL 每秒一轮 —— 每个被注入的进程每秒一行、内容一模一样（撤 `STATUS_BAR` 之前还是每秒一行 `SecurityException`）。改成只在值变化时打。真机量过：同一进程 10 秒从约 10 行降到 1 行。
- 重构：四页重复的卡片与列表骨架收进 `Common.kt`（`ScreenList` 收成图标/模块/关于三页原本逐字节相同的骨架、`HeroCard` 收总开关卡与模块页状态卡、四处手写的 40dp 底板改用已有的 `IconTile`、`InfoCard` 合并通知页两张说明卡、`RepoUrl` 收成一份）。**纯结构，未改任何数值**。通知页那套 44dp 底板 / 私有小标题 / 14dp 间距是另一套视觉决定，刻意没有一起统一。
- 性能：SystemUI 通知路径上的反射成员（`Field` / `Method`）与两个内部资源 id 从「每次访问现查」提到装钩时解析一次，句柄存进拦截闭包。原先 `MemberLookup` 每个成员访问都做一遍 `getDeclaredField`，`declaredMethod` 还靠逐级抛 `NoSuchMethodException` 走父类链，而这些都在每条通知、每次图标着色的路径上。
- 清理：移除零引用的 `androidx.graphics:graphics-shapes` 依赖（Material Shapes 从来没被 import 过）；修正 `libs.versions.toml` 与 `lint.xml` 里那条「Compose 编译器版本必须与 Kotlin Gradle 插件严格一致」的说法 —— 仓库里没有 `kotlin.android` 插件，`kotlin = "…"` 只钉住 Compose 插件，KGP 由 AGP 带入，注释描述的约束实际不存在。
- 性能：`invertAlphaMask` 用的那块 `byte[]` 改为按大小就地复用的 ThreadLocal 缓存，每张 mono 遮罩免去一次 Eden 区分配。Android 16「强制动态取色」下桌面会现场为每张没适配的应用生成 ALPHA_8 掩码，真机量过单次清缓存重启后 60 秒内能跑 360+ 张翻转 —— 短命大对象的 GC 压力是真能看到的，省下来比挂钩里 25 处 `toTypedArray()` 加起来还多。
- 重构：**单色遮罩改成「合成」而不是「翻转」**。原先是让桌面先算出一张极性错误的掩码、模块再整张翻回来；现在改为钩 `AdaptiveIconDrawable.getMonochrome()`，在它返回 null（= 应用没提供单色图标）时**直接补一个极性正确的合成层** —— 桌面只在这个 getter 返回 null 时才 new `MonochromeIconFactory`，所以合成一接上，那条算错的路径根本不进。好处是不用等它算完再翻（省一次全像素往返）、不依赖 `mAlphaBitmap` 这个私有字段名、极性判据收在自己手里。剪影算法**与通知小图标的黑白模式共用同一个函数**（`IconBitmap.monochrome`：透明底走 alpha 阈值、实心底走 Otsu 自动阈值二值化 + 连通性判断哪一侧是图形），不另写一套。**与通知侧有三处差别**：(1) 不做 `fill()` —— 通知要填满状态栏那点小尺寸，而桌面单色层必须与自适应图标的坐标系对齐，一 fill 图形就撑出可见区；(2) `preserveAlpha = true` 保留边缘抗锯齿、不做 0/255 二值化 —— 96px 的状态栏看不出硬边，桌面图标大得多，硬边放大就是一圈锯齿；(3) **按桌面实际要的尺寸出图** —— 掩码在 `draw()` 里看实际 bounds，比手上这张大就按那个尺寸重压一次，固定尺寸再放大正是「糊」的根源。另外 `CircleIconDrawable` 重写了 `getMonochrome()`（虚方法分派绕过基类），合成在那里也接了一次，否则裁圆默认开着时整条「合成优先」等于没上。两条路**天然互斥、不需要开关**：合成一旦生效桌面就不进 Factory，翻转自然不触发；合成挂不上或抛异常时返回 null，翻转照常兜底。
- 修复：合成层的**坐标系**（这一处不改会画歪，而且不报错、只画歪）。桌面拿到非 null 的 `getMonochrome()` 后，是把它当成**前景层**塞进一个新的 `AdaptiveIconDrawable`（`MonoIconThemeController.createThemedBitmap` 的字节码：第 78 行 `new InsetDrawable(mono, -getExtraInsetFraction())`，再交给 `toAlphaBitmap` 画进 ALPHA 位图），而 `AdaptiveIconDrawable` 给每个子层设的是**图层 bounds = 1.5 × 图标尺寸**、并且只把中央 2/3 画进可见区。所以掩码必须按「整张 108dp 画布」出图（图形占 61%），**不能**按 inset 缩到可见区 —— 缩了图形会变成 137%、边缘被裁掉一圈。这一条对着 `android-37` 源码的 `updateLayerBoundsInternal` 与真机 Launcher3 的 `classes.dex` 逐条核过，结论写进了 `MonochromeSynth` 的类文档。
- 修复：合成时光栅化**不还原应用那份前景层的 bounds**。`IconBitmap.foregroundOf` 会 `setBounds` 到 `(0, 0, edge, edge)`，改的是应用图标对象自己持有的那个前景层；而 `AdaptiveIconDrawable.draw()` 重画图层时用的是子层**当前** bounds（不再重新 setBounds），`setBounds` 又会把缓存的 layers shader 置空 —— 于是「取过 monochrome 之后原图标又重画一次」就会画歪：前景锚到图层原点、尺寸缩一圈。现在 `MonochromeSynth` 自己光栅化并在 `finally` 里还原；通知侧是「画一次就扔」，那条已验过的链保持原样不动。
- 修复：合成的单色层**没实现 `getConstantState()`**（默认返回 null），会把外面那个 `AdaptiveIconDrawable` 的 ConstantState 一起拖成 null —— `AdaptiveIconDrawable.getConstantState()` 在任一子层的 ConstantState 为 null 时整个返回 null，而桌面 FloatingIconView（点图标时的打开动画）直接 `getConstantState().newDrawable()`，null 就是 NPE、桌面进程崩。同一个坑 `CircleIconDrawable` 里已经踩过一次。
- 调整：合成的掩码缓存从「缓存 Drawable 实例」改为「缓存掩码位图 + 每次交出新实例」。位图只读、可以共享，Drawable 实例不行 —— 同一个实例被两个父 `AdaptiveIconDrawable` 同时持有的话，图层 bounds 与 callback 会互相抢（bounds 是父级在 `onBoundsChange` 里设的）。
- 修复：合成的单色掩码**被一层淡色铺满**，桌面主题图标看起来是一整块色、字形几乎看不见。`IconBitmap.monochrome` 的 alpha 分支里，`preserveAlpha` 把「算不算图形」的判据从 `alpha >= 128` 松成了 `alpha > 0` —— 判据和真正写出去的像素两边口径不一致，而调用方正是拿这个判据数出来的 `shapeCount` 在判「形状合不合理」，等于没判。桌面图标的前景层常自带一整张极淡的满幅底：真机实测一张 192² 的输入里 26% 全透明、66% 落在 alpha 1~63、只有 8% 是实心字形，128~254 **一个像素都没有**（说明那层淡底是矢量自带的，不是抗锯齿）。松掉判据后掩码的等效覆盖率从 8% 涨到 34%，上色后整块图标被铺成一片色。现在判据一律是 `alpha >= 128`，`preserveAlpha` 只决定「写出去的 alpha 是多少」，不参与判定 —— `binarize` 那条路本来就是对的（判据是 `class != CLASS_TRANSPARENT`）。
- 修复：主题图标**整块平色、看不见字形**（上面那条只修了掩码内容，不是病因）。桌面给这层上色用的是 `mono.setTint(colors[1])` —— `MonoIconThemeController.createThemedAdaptiveIcon` 的字节码是 `getMonochrome()` → `setTint(colors[1])` → `new AdaptiveIconDrawable(ColorDrawable(colors[0]), mono)`。而 `Drawable.setTint()` 的基类实现**只是把 tint 存进自己的私有字段、算出一个 `mTintFilter`，之后就没有然后了** —— 应用那个 filter 是子类自己的事。系统 drawable 都自己做了（`VectorDrawable` / `BitmapDrawable` 都实现了 `TintAwareDrawable`），自定义 Drawable 不重写就是**静默丢失**：掩码按 `Paint` 的默认色（黑）画出来，而深色主题下底色 `colors[0]` 本身就是深色 → 黑色字形糊在深色底上 = 一整块平色。对照桌面自己的兜底路径就很清楚：它拿不到应用的单色层时，会把 `MonochromeIconFactory` 的掩码包进 `new BitmapDrawable(mono)` 再 `setColorFilter(BlendModeColorFilter(colors[1], SRC_IN))` —— 换成 `BitmapDrawable` 就自动生效，所以那个版本反而看得见字形。现在 `MonochromeLayer` 把 `setColorFilter` / `setTintList` / `setTintMode` 三个入口都落到自己的 `Paint` 上（显式 color filter 优先于 tint，取舍与 `BitmapDrawable` 一致），`Paint` 的默认色设成白色兜底（掩码是 ALPHA_8、画出来的颜色由画笔决定 —— 这正是 `Bitmap.extractAlpha()` 那套「alpha 当遮罩」的用法）。另留两条埋点：`Mono: tint #N via …`（桌面走哪个入口、给了什么颜色）与 `Mono: layer … filter=…`（这一帧画的时候有没有 filter），用来在真机上核实，而不是只靠字节码推断。
- 更正（**推翻上一条的判断**）：主题图标那条路径桌面**根本不 tint**。上一条从字节码里读到 `mono.setTint(colors[1])` 就认定「桌面用 `setTint` 上色」，但真机日志里**一条 tint 埋点都没有**、`filter=none`，而我们的层确实被画了（`Mono: layer bounds=384x384`）。补上「谁在问 / 谁在画」的堆栈埋点后看清了实际路径 —— `BaseIconFactory.createBadgedIconBitmap` → `MonoIconThemeController.createThemedBitmap`（Kotlin 第 76 行 `icon.getMonochrome()`）→ 第 78 行 `InsetDrawable(mono, -getExtraInsetFraction())` → 第 79 行 `toAlphaBitmap(inset, iconBitmapSize)`：**把这一层画进一张 ALPHA 位图**，再交给 `MonoThemedBitmap(alphaBitmap, colorProvider, …)` 由 `colorProvider` 上色。这条路上**只有 alpha 有意义，画出来是什么颜色完全无所谓**，所以之前那次「整块平色」的病因不在 tint，而在同一次提交里 `preserveAlpha` 判据那条（掩码被一层淡色铺满）。顺带用这段字节码解释了另外两个观察：负 inset 把我们的层撑到 1.5×（真机 `bounds=384` = 1.5 × 256）、掩码被画进 256 的位图所以只有中央 2/3 可见 —— 坐标系那一节的推导因此有了更直接的证据。tint 的实现**保留**（`Drawable` 的 tint 确实是「声明了但完全不管」，别的路径可能调它，补上契约是防御性的），但要指出上一条里的一处事实错误：基类 `setTintList` 是**空方法体**，它连「存进私有字段」都没做。
- ⚠ **已被下面那条「合并上两条的做法」取代，保留过程记录**：修复：**非自适应（老式）图标全部变成色块**。桌面给老式图标造主题图标时，会先把那张满幅位图**缩到 70%** 再包进一个 `InsetDrawable`（`BaseIconFactory.wrapToAdaptiveIcon`：`AdaptiveIconDrawable(ColorDrawable(bg), wrapIntoSquareDrawable(icon, LEGACY_ICON_SCALE))`，而 `wrapIntoSquareDrawable` 就是 `InsetDrawable(d, (1-scale)/2, …)`），于是我们拿到的前景层四周有一圈 15% 的透明边。那张**满幅不透明**的老式图标因此被光栅化成「透明 51% / 实心 49%」，正好落进 `IconBitmap.monochrome` 里 `useAlpha` 的判据（透明 > 5% 且实心 < 70%）→ 走 alpha 分支 → 而那个 alpha 描述的是**那块底的外轮廓**，压出来就是一整块实心方块，上色后就是一块色。真机实测特征：`a0=0.88 a1=0.00 a2=0.00 a255=0.11`（硬边、零过渡、单连通块、`out ≈ fill`）。现在这类输入**主动放弃合成**（`MonochromeSynth.wrapsSolidPlate`：把 `DrawableWrapper` 剥一层、满幅光栅化被包的那层、用新增的 `IconBitmap.isSolidPlate` 判「不透明占比 ≥ `SOLID_SHAPE_LIMIT`」），交回桌面自己的 `MonochromeIconFactory` —— 它把图标画进 ALPHA 位图、把**亮度**当 alpha，再归一化 + 做一条以 128 为中心的对比度曲线（`generateMono`），对一整块不透明的彩色方图比二值化剪影（只留外轮廓、细节全丢）好得多，也正是用户说的「之前的黑白图标」。放弃的每一次都留日志（`Mono: diag#N … 放弃合成：…`），不会和「压根没进这条函数」混在一起。
- ⚠ **已被下面那条「合并上两条的做法」取代，保留过程记录**：修复：非自适应图标在**裁圆那条路**上同样会压成方块，根因是同一个。这条路上图标是**没被包装**的裸 `BitmapDrawable`（真机 `adaptive=false`），而 `rasterizeForeground` 原先在**光栅化时**就把它缩进可见区（四周留 `edge/6` 透明边）—— 亲手制造出同一圈透明边、同一个误判。改成**满幅光栅化 → 让 `monochrome()` 判分支 → 剪影算完再缩进可见区**（新增 `intoVisibleArea`）。两处修改根因相同：**inset 的透明边会骗过 `useAlpha`**。真机实测改后 `a255=0.97 → useAlpha=false` → 走二值化 → `out=0.05`。
- 重构（**合并上两条的做法**）：合成收敛成**一条通用规则的两支**，也就是**通知小图标那套方案本身**，不再为任何一类图标开特判。通知侧（`NotificationIconPatch.launcherIconMonochrome`）做的是 `fill(前景层)` → `grayscale`；桌面只读 alpha，所以那一步的**两个成分各自成了一支**，实现都放进 `IconBitmap` 与通知**共用同一份代码**：(1) 透明像素真的构成背景 → 取 alpha（新增 `alphaSilhouette`，与 `monochrome` 的 `useAlpha` 支走同一个 `alphaShape`）；(2) 否则 → 把灰度写进 alpha（新增 `luminanceMask`，即 `grayscale` 那一步的移植）。⚠ **第 (2) 支后来被推翻**（灰度在桌面上染出来是渐变，见下面那条），现在换成 `binarizeSilhouette`。据此**删除**上一轮那套「按类型特判 + 剪影体检」：`wrapsSolidPlate` / `IconBitmap.isSolidPlate` / `silhouetteVerdict` / `Verdict` / `SOLID_BOX_AREA` / `SOLID_BOX_FILL` / `SHATTER_COMPONENTS` 全部移除 —— 那套每发现一个坏样本就要加一条规则，永远补不全。
- 修复（**上一条的根因，也是「淘宝变成大色块」的病根**）：判据必须**先裁到内容外接框再量**。自适应前景层四周约 53% 的面积是安全区留白，带着它去量，一张**满幅不透明的整块底**也会被量成「透明 53% / 实心 47%」，正好落进「透明底 + 图形」的特征 → 走 alpha → 压出那块底的外轮廓 = 一整块色。真机实例淘宝：`fg=BitmapDrawable@648x648 intrinsic=432x432`，`box=0.47 boxfill=1.00` → 二值化支 → 掩码是「淘」字本身（`out=0.10 cc=4 top=0.42`，4 个连通块 = 汉字离散笔画），不再是实心方块（原先 `out=0.47 cc=1 top=1.00`）。通知侧对同一件事早有原话：**「注意顺序：先 fill 再 monochrome。fill 裁掉的透明边正好是 monochrome 判断形状的凭据，反过来做会把整块图标压成一个实心圆。」** 桌面不能像通知那样把内容放大填满（坐标系要对齐 108dp 画布），但「先裁框、再判形状」这一步现在是一致的。分界线取 `FULL_BOX_FILL = 0.92` 而不是更松的值：实心圆形的填充率只有 0.785（π/4），只有真正铺满的方底才够得着。
- 验证：115 个第三方包逐个 `pm disable-user` + `pm enable` 强制重建图标（桌面图标缓存会缓存已渲染的图标，不逐个触发就只能看到首页那几个），全量普查 **707 次渲染 / 99 个唯一图标 / 0 次放弃**，分布 377 次 alpha 轮廓 + 330 次二值剪影；逐条核对每个唯一图标的 `box` / `boxfill` / `out` / `cc` / 亮度直方图，结论是 opaque 满幅底全部落在二值化支、透明底图形全部落在 alpha 支，没有一类被误分。诊断埋点也一并调整：汇总行**逐条打**（不打上限 —— 采样太少正是上一轮的教训：只看得到前几个图标，而前几个往往同属一类，真正出问题的那一类在日志里一次都没出现），只有二值剪影样本的 ASCII 缩略图限 8 张；新增 `box` / `boxfill` 两个字段（就是上面定位淘宝的那两个数）。
- 修复（**上一条的第二支选错了**）：不透明底的合成结果**是渐变、不是纯色**。桌面拿掩码的 alpha 当**不透明度**上色（`MonoIconThemeController.toAlphaBitmap` 用普通 `Canvas` 把这一层画进 ALPHA 位图，全程没有任何 color filter），所以连续灰阶写进 alpha 就等于「同一个颜色的浓淡渐变」—— 而主题图标要的是**一整块纯色**。第二支因此从「灰度掩码」改成**二值化**：新增 `binarizeSilhouette`（复用 `binarize`：Otsu 自动阈值 + `keepDarkSide` 连通性判据），与 `monochrome` 的 else 支**同一份实现**；删掉 `luminanceMask` 与 `MIN_LUMINANCE_SPAN`，`Split` / `analyzeSplit` 降级为纯诊断（不再给掩码定极性，极性由 `binarize` 内部自己定）。**为什么通知侧能用灰度而桌面不能**：通知侧 `grayscale` 交出去的是**完整位图**（RGB 与 alpha 各司其职），而桌面这条路上 RGB 根本没人看 —— 把通知侧的结论搬到桌面时漏掉的正是这个约束差异。
- 诊断：`diag#N` 汇总行加上 `adaptive=`（走哪条口径），采样上限当时拆成「汇总行 12 条 / ASCII 缩略图 2 条」—— ⚠ **这两个值后来被上面「验证：115 个第三方包…」那条放宽成「汇总行逐条打、不打上限 / ASCII 缩略图 8 张」，当前代码是后者**。原先每进程只放行 2 个图标，而**最先加载的往往是同一类**，于是「非自适应图标全是色块」这个现象在日志里一次都没出现过；采样太少是上一轮判断错方向的原因之一。另外加了「谁在问 / 谁在画」两条堆栈埋点 —— 上面那条 tint 矛盾就是靠它解开的。
- 修复：ALPHA_8 掩码**按 packed 下标写、按 rowBytes 交出去**，`edge` 不是 4 的倍数时整条合成**静默失效**。`MonochromeSynth.toAlphaMask` 里缓冲区开的是 `rowBytes × h`，填充却用 `bytes[o++]` 逐像素连写，只交得出 `w × h` 字节给 `copyPixelsFromBuffer` —— 而 ALPHA_8 的 rowBytes 会**补到 4 的倍数**，于是那个调用抛异常，被 `render` 外层的 `runCatching` 变成一句正常的 null 出口（= 放弃合成、桌面回落到自己的 Factory，不崩、屏幕上只是退回旧观感）。本机 `edge = 384` 正好是 4 的倍数所以永远踩不到；桌面实际给的是 `1.5 × iconSize`，density 3.0 的机器是 378、3.375 是 423，这些设备上「合成优先」等于没上。现在按 `y * rowBytes + x` 写，同一处口径的 `dumpAscii` 也跟着修（它此前用 `y * w + x` 读一张按步长填的缓冲，宽度不带 padding 时看不出来，真踩到时缩略图**逐行错位**、像掩码碎了）。
- 健壮性：`render` 那条 `runCatching` 现在把异常打进日志（`Mono: render threw at edge=… <类名>: <message>`）。上一条能潜伏那么久，就是因为 null 出口和异常出口在日志里长得一模一样 —— 本项目第三次踩「提前 return 静默失效」，规矩仍然是**每一条出口都要留痕**，异常这条尤其。
- 诊断降级：上面那条「汇总行逐条打、不打上限」是**普查期间**的决定，现在收回到每进程 12 条（`HEAVY_DIAG_LIMIT`）。理由是这条汇总行**很贵**：四段 alpha 直方图、`box`/`boxfill`、Otsu 与两侧连通性都要在 384² 上重跑一遍（`analyzeSplit` 复算真算法，不是读现成结果），再加掩码连通块，单次约 4 MB 短命数组 —— 而它的入口是桌面加载图标。逐条的 `noteBranch`（走哪支、有没有交出掩码，一行、零复算）照旧**逐条打**，降级掉的只是贵的那部分，「每条出口都留痕」没动。「谁在问 / 谁在画」两条堆栈埋点移除 —— 它们要回答的问题（桌面到底 tint 不 tint、走哪条路）已经有了结论并写进 `MonochromeLayer` 的类文档，`Throwable().stackTrace` 留在图标热路径上只是白付。
- 构建：release 改用正式 keystore 签名（`keystore.properties` + 仓库外的 `~/.opticon/*.jks`，两者都被 `.gitignore` 挡住）。⚠ **签名换了就装不上去**：设备上此前那份是 debug key（`CN=Android Debug`），直接 `install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须**先卸载**，而卸载会连带清掉 LSPosed 的启用状态与作用域勾选，装完要重新勾。keystore 本体必须另外备份 —— 丢了它，这台机器上以后所有用户都只能卸载重装，等于换包名。
- 新增：**合成侧也认「已适配取色」**。之前只在应用自带单色层时透传（`getMonochrome()` 非 null 直接原样返回），漏了第二种适配：**图标本身就是灰度剪影、等系统按主题上色** —— 那种没有单色层，会一路走进合成被我们重压一遍，白丢人家画好的层次。现在在光栅化之后、压剪影之前过一道 `ToneCheck.isGrayscale`，判成灰度就放弃合成（一个像素都不碰），并留一行 `已是灰度…→ 不动` 的日志。判据**复用通知侧那一个函数**（那边是 `preserveTinted` + `ToneCheck.isGrayscale`，「单色说明人家已经适配好了，不动」），两边说的同一件事不另写一套标准；开关也跟着走同一个 `preserveTinted`。⚠ 量的是**要被我们动的那一层**（自适应=前景层、非自适应=整张），不是合成结果 —— 拿合成结果判等于用「我压出来的形状」决定「要不要压」，自己证自己。
- 新增：**二值支加后处理 —— 闭运算 → 去碎渣 → 形状体检**。亮度 Otsu 一刀切下去会把抗锯齿那圈中间调判给另一侧，笔画被切出 1 像素的缝、字被拆成几截（真机实例：中国移动那张 `cc=5 top=0.24`）。现在这支先做一次形态学**闭运算**（膨胀 + 腐蚀，8 邻域）把这种缝连回来 —— 它补得掉 1 像素的缝、补不掉 2 像素以上的洞，字腔和眼睛这类真细节闭完照旧是洞；再按连通块擦掉太小的杂色碎渣（阈值按**面积比例**给，不写死像素数 —— 掩码出图尺寸跟着桌面要的大小变，写死 36 像素在 192 那一档等于抹掉一整笔）。**alpha 那一支不做这些**：它交出去的是图标自带的 alpha，多做一次形态学只会凭空造像素。
- 新增：两条**形状闸门**，都在闭运算之后判（连不回去才算真碎）：`框内 ≥0.55 且洞占比 ≤0.03` → 一整块什么细节都没有的底板，拒；`块数 ≥8 且最大块占比 ≤0.35` → 碎成一片，拒。⚠ **两个数都必须成对用** —— 单看块数会误杀汉字（「淘」天然 4 块、每块都是实心笔画，`top≈0.42`），单看洞占比会把「白底 + 彩色 logo」那种正确形状当板子。判据台实测：MOONDROP 那种 16 块的碎片被拦下，其余图标的像素数与处理前逐条一致 —— 没缝可补时闭运算就是恒等变换，不会凭空造东西。
- 新增：**把字形从底板里刻出来**（`IconBitmap.plateSilhouette` + `assignLayers`），合成按「最忠实的先试」排三条判据：alpha → **按颜色分层刻底** → 亮度 Otsu。大色块的病根不是阈值定错，是**特征选错了**：中国 App 的图标绝大多数是「一层彩色底 + 与之不同的字形」，每一像素都不透明，alpha 描述的是那块底；而亮度 Otsu 只在底与形恰好一明一暗时切得开，底板带渐变时直方图根本没有两个峰。改成按**颜色**分：逐通道中位色当第一层心（底板），再最远点采样取两个（主体、点缀），每个像素归到最近的层心、跑一步均值修正，底那一层整层摘掉，剩下的就是字形。判据台实测：Reddit 那种圆角红底 + 白色天线，原先 alpha 交出 `boxFill=0.79` 的一块纯色，现在是 `cc=1 top=1.00` 的字形（占框 0.21）；12306 那种渐变底从「alpha 整块收下 0.65」变成「刻出 0.22 的字形」。
- 新增：**合成日志带上包名**（`IconNames`），报一个应用名就能直接 `grep` 到它那一行。之前每一轮反馈都是「12123 / termux / 闲鱼 不对」，而 `Mono: diag` 行里只有 `AdaptiveIconDrawable fg=VectorDrawable@425x425` 这种 drawable 签名，两边对不上号只能靠猜（能定位淘宝纯属它特征恰好独占一类）；判据台也帮不上 —— Termux 与闲鱼都是**矢量**自适应图标，JVM 上光栅化不了。登记挂在两条入口上：`Resources.getDrawable*`（拿得到 `包名/类型/入口名`）与 `PackageManager.getApplicationIcon / loadItemIcon` 那类**没有 resId** 的入口（只能拿包名）。⚠ 第一版只挂了前者，真机日志全是 `?`；靠一次性探针（各打 3 行"登记/查不到"）才看清**查询比登记早 8 秒** —— 桌面根本不经过 `Resources.getDrawable*` 拿这批图标。认不出仍是常态（`newDrawable` / `mutate` 之后实例会变，所以连子层的 `ConstantState` 一起记），查不到就打 `?`，绝不为了名字去改应用那份图标对象。
- 更正（**同一轮里推翻自己两次**，都记下来）：① 底板色不能用**众数色桶** —— 第一版按 4 bit 量化取最大桶，渐变底散成 4768 种颜色、最大桶只占 0.11，"有没有一块主导底"当场判否，大色块一点没治到；换成逐通道中位色才立得住（取中位数走 256 桶直方图，不在热路径上排 14 万个数）。② 「一个图标最多三层颜色」这条先验**不能当否决条件用** —— 第一版写的是「离三个层心都远的像素占比 >0.30 就放弃刻底」，那等于把「我算不出三层」当成「这张图不是三层」：渐变底被整块判成装不下、直接不刻，彩色底原样留在掩码里，就是「去色没做好」。先验的正确用法是**预算**：没有"逃出去"的出口，每个像素都必须归层，归不进前两层的并进第三层；占比不到 2% 的层当噪声并回底板（渐变最暗端总会骗出一个这样的假层）。
- 修复：主题图标**发浅** —— 掩码内部的 alpha 不再原样透传。桌面把 alpha 当**不透明度**上色，而 `preserveAlpha` 保留的是整片原始 alpha、不只是抗锯齿那圈边：源图标里 alpha 150~200 的区域被画成六到八成浓度；细笔画更糟，三四像素宽的描边整个都是"边界"，逐像素保留过渡值等于整条笔画半透明。现在在 `toAlphaMask` 里收口：内部一律钳到 255 实心，轮廓那一圈允许过渡但保底 `EDGE_ALPHA_FLOOR`（200），边缘仍是渐变不硬，但最淡也有八成不透明度。
- 新增：**输出改三档浓度**（用户规定「图标就该有三种颜色」：深浅都用系统的、中间取中值）。桌面给主题图标的是两层系统色 —— `MonoIconThemeController` 里就是 `new AdaptiveIconDrawable(new ColorDrawable(colors[0]), mono)` 再按 `colors[1]` 上色，而 alpha 合成是线性的，所以三档这么定：**底板层给 alpha 0**（浅那一档由系统的底色透出来，我们不猜颜色）、**主体层 255**（系统前景色，深那一档也来自系统）、**点缀层 128**（50% 前景压在系统底色上，正好落在两档的中值）。哪层算主体按**面积**定。三档全是离散值，与「别把整片原始 alpha 透出去」那条老约束不冲突 —— 那条反对的是连续渐变，不是三个确定档。配套：`toAlphaMask` 对带档的掩码只做**吸附归档**（<64→0、<192→128、其余 255）不再钳实心，否则中间档被抹平、三层塌回两层；吸附也顺带修掉非自适应图标缩放时双线性混出的档间杂值。判据台验过：三档齐、`其它=0`。
- 修复：**层心改从量化色板里挑**（`assignLayers`）。原来"离底板最远的那个像素"当一个层心，一个孤立高饱和噪点就能独占一层；更糟的是同一遍循环里第一个层心还在长大，第二个层心已经拿它去算最近距离了 —— 点缀档落在哪一层取决于像素数组的遍历顺序（判据台上点缀占比从 4.6% 乱跳到 29%）。现在按 4 bit/通道统计色桶、只保留占比 ≥1% 的桶、按计数排序（同数按桶号，与像素顺序无关），再依次挑「离已选层心最远」的桶。
- 修复：**三档的中间档不再压在判定线上**。`TONE_MID = 128` 与 `MIN_SHAPE_ALPHA = 128` 相等，而"算不算图形"判的是 `alpha >= 128` —— 点缀档正好站在刀尖上，任何一次取整把它压成 127，那一层就整个从掩码里消失。现在体检按掩码种类分别判：带档的只认「非 0 即图形」（那些值是我们写死的确定档，没有需要滤掉的淡色），不带档的仍按 128 滤淡色（不滤就是柔光铺满整张的老坑）。吸附阈值也跟着 `TONE_MID` 推导，不再写死 64/192。
- 修复：闭运算新添的像素不再一律给实心白，而是**继承邻域里那一档的值** —— 给 255 等于把点缀区外圈闭出来的那一圈升成主体档，中灰细节被硬边吃掉。
- 修复：「一整块板」那条闸门改用**内部结构占比**（封闭空洞 + 点缀档像素）而不是空洞占比。三层模式下字腔常常正是第三种颜色，它是实心的、根本不算洞 —— 只量洞会让这条闸系统性失明（真机日志里 `hole=0.00` 一片有一半是这个原因），一块 0.98 的纯色板照样过关。判据台上改完 `结构=` 从一片 0.00 变成 0.04~0.35。
- 整理：0.55 这条线原来在两个文件里各有一份同值常量（`PLATE_HINT` / `PLATE_FILL`，一个是"要不要试刻底"、一个是"刻完还是整块板"），现在合并成 `IconBitmap.PLATE_FILL` 一处。
- ⚠ **已被下面两条推翻，保留过程记录**：- 修复：**三档改成三个控制点，不再分箱**（分段线性插值）。上一条按层写死 0 / 128 / 255，结果图标里的渐变段被整片吸进某一层 —— 用户反馈的「渐变部分也被做成色块」就是这个。现在像素的 alpha 按「离底板色有多远」在 `0 → 128 → 255` 之间插值，两个控制点取点缀层与主体层各自的平均距离：平色区域照旧平色（同色距离相同），真渐变恢复成过渡。只有点缀层时退化成 0→255 一条直线。配套两处：`TONE_FLOOR`（64）—— 有了插值就必然有"刚离底板色一点点"的淡像素，不滤就等于把柔光/渐变尾端整片收进掩码（本项目踩过的那类"淡色铺满"），所以带档的掩码也要有条线，取中档的一半；`toAlphaMask` 对带档掩码**原样透传**（钳实心会抹掉渐变、吸附会把过渡重新变成色块 —— 这两件正是它以前的行为）。判据台：过渡像素从 0 变成 669~4840 个，平色区仍是实心 255。
- 修复：**闭运算换成「条件桥接」** —— 只补 1 像素的缝，不再把两笔粘成一块。原来的闭运算（膨胀一次再腐蚀一次，8 邻域）连得上的不只是 1 像素：2 像素的空隙一起被填满，就是「缝隙小的部分粘连在一块」。现在只做一个动作：一个透明像素在**相反两个方向上都夹着图形**（上下 / 左右 / 两条对角）才算图形，于是 1 像素缝接得上、2 像素缝接不上；且不膨胀 → 形状不变粗，不腐蚀 → 不削尖角（顺带把"腐蚀必须另开缓冲"那个坑连代码一起去掉了）。判据台：kimichat 的连通块 4→7、过渡档归零。
- ⚠ **已被下面「是不是底归归层管、多浓归距离管」那条取代，保留过程记录**：修复：**渐变不做过渡，直接纯色**（推翻上面那条「三档改控制点」）。插值确实让渐变段不再是平色块，但用户看完的裁定是反过来：**过渡在桌面上就是「不纯色 / 发糊」那个老问题的另一种形态，宁可让渐变那段变成一整块**。所以三档回到离散值（底 0 / 主体 255 / 点缀 128），`toneAlpha` 与层平均距离那套一起删掉，`toAlphaMask` 对带档掩码仍然原样透传（钳实心会抹掉点缀档）。
- ⚠ **已被下面「是不是底归归层管、多浓归距离管」那条取代，保留过程记录**：新增：**「渐变做成纯色」= 过渡带并回相邻那一层**（判据：离轴距离）。用户补的这句把上一条说准了 —— 纯色不是"自己成一个中间色块"，而是**跟旁边一个颜色**。落成一个几何量：渐变段的平均色必然落在「底板色 → 主体色」这条连线上（它就是两者的混合，同色系的阴影也算），真正独立的第三种颜色会偏出去。所以量点缀层心到那条线的垂直距离：< 35（≈每通道偏 12）就整层并回主体，一起涂成实心；≥ 35 才保留中间档。判据台：kimichat 离轴 28 → 并掉，中色带清零；12306 离轴 111 → 那抹绿是真的第三种颜色，保留。每条三层判定都在日志里带着这个数（`三层·离轴NN`），它就是用来收 35 这条线的。⚠ 顺手排掉一颗雷：并回之后把层号置 -1，而**透明像素的标签也是 -1** —— 用 `when (layer) { accent -> … }` 会把整张画布涂成中档。
- 修复：判据改成量整张图标之后，**光栅化整张图标会把两个子层的 bounds 留在小尺寸上 → 图标变糊**。`AdaptiveIconDrawable.draw()` 里的 `updateLayerBoundsInternal()` 会按当前 bounds 重算背景层与前景层，而它之后重画图层用的是子层**当前** bounds、不再 `setBounds`。上一版只在 `finally` 里还原了父层，于是那张 64px 取样图把 1/6 尺寸的子层 bounds 留在了应用自己持有的图标对象上，桌面下次画原图标时按 64 的尺寸铺进 384 的画布 —— 观感就是「突然变糊」。这正是本模块为前景层写过的那条坑（`rasterize` 上面那段说明）在上一层的翻版，我自己又踩了一遍。现在两个子层一起快照、一起还原。⚠ 子层**不能靠父层那次 setBounds 顺带带回去**：取 monochrome 的时机常常在桌面设 bounds 之前（真机日志里就是 `bounds=0x0`），还原成空矩形时 `Drawable.setBounds` 根本不触发 `onBoundsChange`，图层也就不会重算。
- 修复：合成的**覆盖率判据按错了分母**，一整块底板永远拦不住 —— 真机上「白块 / 块不干净」的病根。两支的产出守卫（`MIN_SHAPE_RATIO`~`MAX_SHAPE_RATIO`）量的是「掩码占**整张 108dp 画布**多少」，而自适应前景层的内容只落在画布中央约 61% 一线、**内容外接框本身就只有画布的约 47%**。于是「整个外接框被填满」这种最坏的掩码（一整块底板，字形根本不在里面）按画布量永远只有 0.47，离 0.85 的上限远得很，**那条兜底闸形同不存在**。证据就在自己的日志里：淘宝那一版 `out=0.47 cc=1 top=1.00` —— `cc=1 top=1.00` 说的是「一整块」，`out=0.47` 却一路放行。改成两支都按**内容外接框**量（与「走哪一支」那条判据同一个口径、同一个数）。实际收紧的两处：alpha 支的分界线从另设的 0.92 收到共用的 0.85（实心圆的框内填充率是 π/4≈0.785，仍然不误伤，「圆形底板压成一个圆」是对的）；**二值支才是真正的漏洞** —— 此前它按画布量等于**没有上限**，`keepDarkSide` 一旦选错侧（打平退回「取较少一侧」时）保住的整块底会原样交出去，现在框内 >0.85 当场拒掉、两支都不成立就放弃合成让桌面走自己的路。**宁可退回桌面，也不交一块脏色板。**
- 整理：这条判据**只动桌面的两个入口**（`alphaSilhouette` / `binarizeSilhouette`），`monochrome`（通知侧）保持画布口径不动 —— 那条链在压之前先 `fill()`，内容已经放大填满画布，框 ≈ 画布，两种口径是同一个数；改它等于去动一条已验过的路。顺带删掉 `FULL_BOX_FILL`（0.92）那条独立分界线：它和产出守卫本来量的就是同一个数，分成两条线中间那段（框内 0.85~0.92）既没人拦也没人看。`alphaSilhouette` 里也去掉重复的第二次守卫 —— `alphaShape` 收的正是「框内 alpha ≥ 128」那一批像素，它的计数除框面积按构造就等于上面那个 `boxFill`，再量一遍只会多一次全画布遍历、还留下两条可能对不上的数。
- ⚠ **已被下面那条「档由层的身份定、过渡在档内、淡不留由连通性定」取代，保留过程记录**：裁定（**这一条把上面两条「渐变做成纯色 / 过渡带并回相邻层」都推翻，一天里第四次翻，往返记全**）：渐变恢复成渐变（`toneAlpha` 按「离底板色多远」插值），但**两件本该分开的事必须分开量 —— 「是不是底」归归层管，「多浓」归离底距离管**。底板那一层无论内部有没有渐变都整层摘掉（`assignLayers` 早把它判出来了），剩下两层无论离底多近都是形，浓度按**形自己那一段距离**撑开（新增 `glyphFloor`：形层距离的 5 分位下界，再钳在字形跨度的一半上）。中途试过把零点摆在「底板自己散到多远」（`plateSpread`，95 分位）：它确实把 steamlink 的框内填满从 0.78 压到 0.29，代价是**与底板颜色相近的那层形整片被宣布不是形** —— 就是用户报的「同一图标内颜色太相近会被画成同一个颜色，然后成了白块 / 黑块」，细节不是糊掉，是**根本没进掩码**，所以撤回。两个后果都在判据台上量到：元宝（单层平色字形）`档距149/0/147` —— 零点高过实心点，`结构=0.00 top=1.00` 一块实心；钳在半跨变成 `73/0/147` 之后结构 0.18、过渡像素 1417。**两个字形档改按离底远近排浓度**（近 → 128、远 → 255），不按面积：alpha 本就是距离的单调函数，面积大的那层离底更近时两个控制点前后颠倒、斜坡刚起步就饱和（12306 一度量出 `55/129/56`）。判据台现状六张样本五张带过渡，框内填满 0.17~0.54（此前 alpha 一支交出去的是 0.65~0.79 的整块底）。日志同步成 `三层·档距下/近/远`，`离轴NN` 降级为纯观测量（并层在斜坡上是多余的）。`toAlphaMask` / `intoVisibleArea` 里为离散档加的最近邻分支一并删掉 —— 连续斜坡混它只会更平滑。
- 修复：**「先试刻底」那条线只看覆盖率，认不出一块没有任何内部结构的实心板**。真机一轮日志：元宝的 alpha 掩码 `boxFill=0.27 cc=1 top=1.00 结构=0.00`（一整块卡片的轮廓），而一件正常字形量出 0.31 —— 覆盖率上两个数前后脚，**分不开**；分得开的是「里面有没有结构」，字形总有字腔。所以 `IconBitmap.Silhouette` 现在把 `structure` / `largest` 两个数一起带出去（以前只写在日志字符串里，调用方读不到），`MonochromeSynth` 用「一个连通块占 ≥0.90 且内部结构 ≤`PLATE_HOLE`」判 alpha 交出的是形还是底。同时给"换掉 alpha"加门槛：另一支（刻底 / 亮度 Otsu）必须**既刻掉得更多、又真的刻出了更多内部结构**才许压过去 —— 否则就是拿一次猜测换掉图标自带的形状。
- 裁定（**第六次翻这条线，把六次都记下来：①离散三档→②插值→③回离散+并层→④再插值→⑤归层管底/距离管浓度→⑥现在这版**）：**档由层的身份定，过渡在档内，淡不留不留由连通性定**。⑤ 那版把两件事拆开了（底板整层摘掉、形层的浓度按离底距离插值），"相近颜色并成一个"当场好了，但它留了**一条绝对的淡色线**（`TONE_FLOOR`，alpha<64 不算图形）—— 柔光型图标被这条线**切成骨架**。查这个案例花了整轮：皎月连（`com.example.appui`，白底 FDFDFD 占 0.54 + 蓝色柔光月亮 0092DB 占 0.20，其余全是过渡）在判据台上三份素材三种走法 —— 320 那张 `cc=3 top=0.86` 好好的，480/640 那两张无论按哪个尺寸出图都 `桥接后仍碎成 16~18 块 top=0.10~0.26`，两支双双放弃 → 桌面回去用自己的 `MonochromeIconFactory` 按**亮度**重画 → 半张白底被画成实心 = **一整块白**。这就是"白块"最后一条来路，而且它对尺寸敏感，观感就是"时对时错"。现在三处一起改：**(1)** 两个形层各占一档区间（近底 `1..TONE_MID`、远底 `TONE_MID+1..255`），**档内**按该层自己的距离跨度（5~95 分位，新增 `distanceSpan` / `toneBand`）线性铺开 —— 同层里的渐变仍是渐变，不同层永远不同色，平色区域仍是实心（跨度为零时直接给到该档上界）；**(2)** 那条绝对淡色线换成**空间判据**：从内容框边界出发洪泛，与外面连成一片的淡档当背景擦掉，被图形围住的同色淡像素当细节留下（日志新增 `淡擦NN`），"同一块颜色处在什么位置"才算数，只看颜色永远分不开这两处；**(3)** 「碎成一片」那道闸原本只按**块数**判，块多≠碎 —— 月亮加星点天生十几个各自成块的正形，所以再加一条「小片占的像素比例 ≥`SHATTER_SMALL_SHARE`」（新增 `Shape.smallShare`），块大而多的放过、噪点糊出来的拦下。真机一轮 1789 行：形状闸**一次都没再触发**（此前这一类图标全被它拦死），47 次放弃全是"应用自己已适配取色"那一条，0 异常；皎月连本尊 `alpha[0.79]（一块白）→ 刻底[0.31 三层·档近25-84/远103-131·淡擦0.01 cc=18 top=0.11 小片=0.00] → 已交出掩码`。判据台五张样本 + 六张普查样本全部出掩码，无一支退回 Factory。
- 裁定（**第七次**）：**档数上限 3 → 5（`MAX_TONES`，含底板），用户说"上限不管了，每处都单独上色"**。聚类、最远点采样、均值修正、并噪声四处原本都写死 3，一起改成按上限走；档间分界由形层数均分（两档形层时均分点正好落在 128，所以旧规则「底 0 / 中 128 / 实心 255」成了 N 档规则的一个特例而不是规则本身）。真机皎月连（`com.example.appui`）现在量到 `层4·底占0.56·档 1-63 / 64-127 / 128-191 / 192-255·cc=3 top=0.89` —— 那张白底 + 柔光月亮第一次有四个确定档，不再是一块白。
  ⚠ **上限不是随手能再加**：8 档在判据台上实测过，结果是「放宽上限」与「把底摘干净」直接冲突 —— 一道渐变底会被切成好几个形层，于是①底板层的色散塌到 0~16（洪泛那把"离底多远"的尺子失效）、②第一档上沿掉到 37 以下（淡档几乎不再被擦），12306 的形占从 0.26 坏回 0.41~0.53，大色块照旧回来。中间还试过把洪泛改成**局部相邻色差**的区域生长：容差 12 跨不过分带的渐变底（底擦不掉，12306 形占 0.53），容差 32 能顺着柔光字形一路漂出去（元宝 / reddit / 中国移动 / 皎月连 全部「几乎刻不出东西」，底占冲到 0.9+）—— 局部连续性根本分不开"渐变底"与"柔光字形"。两头的实测都写进注释，别再走第三遍。
- 修复：**「仍是一整块板」那道闸看错了量，中间档能把它骗过去**。闸原来比 `structureRatio`（封闭空洞 + 中间档），而带档的掩码里一块底板渐变自己就能凑出 0.2~0.4 的"结构" —— 判据台放过 `out=0.54 cc=3 top=0.94 结构=0.41`，屏幕上就是一块大色板。改成只看**封闭空洞**（新增 `Shape.holeRatio`，与 `structureRatio` 一起进日志 `空洞=`）：字形总有字腔，一整块板里一个洞都没有。另一处配套：`MonochromeSynth` 里 alpha 支被判成 `plateLike`（单块 ≥0.90 且空洞 ≤`PLATE_HOLE`）时**连垫底都不配** —— 另两支都不成立就返回 null、让桌面走它自己的 Factory，也不交一块脏色板（用户这一轮的裁定：「不要让我看到大黑块 / 大白块」）。「有没有一块主导底板」也从"归层分给底板层几个像素"改成**洪泛之后实际剩下多少图形**（`background`）：前者是三档聚类定标的，聚类一细底板层就缩水到 0.25、判据当场判否、整块底原样交回 alpha 支 —— 换成后者与簇数无关，`底占` 收下时也一并打出来好收线。
- 诊断：三条判据现在各自把**决策用的那个数**带出来（`IconBitmap.Silhouette(mask, boxFill, verdict)`），逐条的 `diag#N` 行从「alpha 轮廓 → 已交出掩码」变成 `alpha[0.31 4421 像素]` / `alpha[1.00 框内已填满=底板] → 二值[1.00 保住的那侧=整块底] → 两支都不成立，放弃合成`。上一轮的教训不是没打数，而是**数打在错的分母上、看着完全健康** —— 所以这一行给的就是判据自己吃的那个口径。这行仍然零复算（每个图标一行、不打上限），贵的那条全画布复算汇总照旧限 12 条。
- **重构：强制取色整条链换成一个「对比度模型」，四支竞价全部删掉。** 上面那七次裁定不是白做的，但它暴露了一件更根本的事：`alphaSilhouette` / `detailSilhouette` / `plateSilhouette` / `binarizeSilhouette` 四支**量的不是同一件事**（照抄轮廓 / 离主色多远 / 按颜色刻底 / 按亮度切），所以任何一条全局线都会在一支上是解、在另一支上是病 —— 七次往返全花在这上面。新模型 `circle/MonochromeMask.kt`（纯数组进出，判据台跑的就是这一份）只问一句「**这个像素与它*周围*那块底差多少**」：
  - **底 = 局部底色场**：8×8 格子各取该格底像素的中位 RGB，空格由邻格松弛补齐，取值双线性插值，「拟合场 → 重算距离 → 重新洪泛」跑 3 轮。渐变底在每个格子里都有自己那一份，于是**渐变不需要并层、不需要档内插值补偿，它自己就是平的** —— 第七次裁定那张「上限不能再加，加到 8 档就会把渐变底切成好几个形层」的两难，前提是"底是一个颜色"，换掉前提就消失了。
  - **差 = CIELAB ΔE76**（整数查表：sRGB→线性 LUT + `t^(1/3)` LUT，三个通道都存成真值 ×64）。不再用 RGB 曼哈顿距离，也不再只看亮度。合成用例台第 0 条对着**公开参考值**核这两张表（白/红/绿/蓝/黑/sRGB128 灰），最大偏差 0.6。⚠ 参考值有 D65 与 D50(ICC) 两套，同一个 `#00FF00` 的 b* 是 83.2 与 71.4 —— 混过一次的假故障记在 `SelfTest.kt` 那条注释上。
  - **浓度 = 两点标定**：底板自己的散度（ΔE 分位）归 0，这张图形层的 [INK_REF_PCT] 分位归 255，中间过一条 `t^0.75` 曲线。**「一整块色板」在这条路上不是被闸门拦掉的，是产生不出来** —— 底板与自己比必然差 0。
  - 只剩一支例外：**透明底**。底是「没有东西」时色场没有可比对象，差退成覆盖率 = 图标自带 alpha（一个像素不改、不做形态学）。但这一支以前交出去就是**一整块实心**（判据台上那个绿色机器人：机身、白立方体、两只眼睛一样浓），现在补一步 `carveInside`：参照物换成**图形自己的主色**，只把**被主体围住**、又与局部主色差得多的那部分按差多少镂空；贴着背景的那一半（红蓝双色 logo 的另一半）不算镂空。
  - 形状体检只剩一份：条件桥接（只补 1 像素的缝）、擦碎渣、三道闸，全部原样保留 —— 它们是被真机反馈定下来的，跟"怎么算浓度"是两件事。
  - 删掉的：`IconBitmap` 里那四个桌面入口 + `assignLayers` / `toneBand` / `distanceSpan` / `accentOffAxis` / `polishBinarized` / `analyzeSplit` 整套（该文件 1830 → 660 行，只剩通知那一半边），以及 `MonochromeSynth.toAlphaMask` 里「内部一律钳实心 + 边缘保底 200」—— 那是**模型之外第二次改 alpha**，判据说"离底 30%"、这里改成"实心"，日志里的数字和屏幕上的东西就不是同一张图。现在 ALPHA_8 只是原样搬一个通道。
  - 合成用例台（`_harness/SelfTest.kt`，15 条断言全过）当场抓出两个真故障，都不是靠推演发现的：① 「alpha 可用但像一块板 → 两支全灭就返回 null」这条，让**透明底上的实心圆盘**和**柔光加一个实心核**（皎月连那一类）整个放弃合成，桌面回去按亮度重画成一块实心 —— 回落从来不是中性的，"这张图本来就只有一层"不是错误而是事实，现在 alpha 可用就是地板；② 网格从 6 提到 12 时自适应容差塌到 0（局部场贴着自己那一片），steamlink 的 `淡` 从 0.16 涨到 0.34、底占从 0.57 掉到 0.39 —— 加了 `TOL_MIN=6` 兜住，这条是所有局部背景模型的通病。
  - 判据台 11 张真图标 + 满幅/内缩两种口径：全部出掩码、0 张退回桌面自己的 Factory；`框内` 从旧版透明底那一批的 0.65~0.79（整块底）落到 0.13~0.33。⚠ 这些是**代理输入**（多数报错的应用发的是 adaptive vector XML，台子解不动），真机分布仍然要看日志里那行 `支=… 底占=… 容ΔE=… 实ΔE=… 框内=…`。
- **重构（第二轮，由真机抓回来的图标驱动）：底从"一个颜色"改成"四个角上的几族颜色"。** 上面那条重构写完，判据台上 15 条合成性质全过、11 张代理图预览都好看 —— 但用户还是说「不够细致通用」，因为**代理图根本没有他要的那些图标**。于是补了一条通道（见下条），第一次把真机上光栅化之后的像素带回来重放，11 张真图标里当场查出 3 张同一个病：
  - **高德**（绿/白/蓝/灰四块拼底）、**饿了么**（上橙下蓝两段）、**云闪付**（上红下白两段）—— 底**不是一个颜色，是几块拼的**。按"哪个候选摘掉的面积多"只选一个家族，剩下那块底整块变成墨，屏幕上就是他报的「还剩一块大色块」。判据台上这三张的数字全都"正常"（底占 0.40~0.59），因为它们确实摘掉了一半 —— 只是剩下那一半也是底。
  - 改法：**底 = 内容框四个角上的颜色家族**（≤4，按 ΔE 互异线合并；角点落在圆角外面就沿对角往里走直到窗口落到底上 —— 上一版把窗口钉在角点，云闪付 / 12123 / 闲鱼 三张满幅圆角图标当场量出"四个角都没有底"，从"刻得很好"退化成"整个放弃合成"）。洪泛一次穿过**所有**家族，底是它们的并集。角这个位置只有底会去占：图形即使贴着边一般也占不到角。
  - **参照色改成传播，不再按格子取。** 每个像素的"背后的底"= 离它最近的那块底（从底像素出发的四邻域洪泛，与 `flood` 同一个道理）。按格子取有两处漏：一块字形跨了两个格子、两格底色不同，浓度就在格界上跳一级（高德纸飞机右下角那块灰色方片）；两块底之间那圈抗锯齿混色离两边都远，被算成墨（云闪付红白分界上那串虚线）。
  - **中缝按位置吸收，不按距离。** 判据是"沿某方向隔着缝对脸看到的两块底，底色彼此差得开"（ΔE ≥ 25），再从种子穿过非底像素吞并整条缝，只留一道"这块够不够细"（≤ 框的 2%）的闸。⚠ 原来还加了"碰到内容框边界就放弃"，那条**是错的而且致命**：横向两段拼底的分界线天生横贯整张图，一定碰到左右边界 —— 判据台上红蓝两段一格都没吸收掉就是被它挡的。真正的保护是"两侧底色差得开"：画在均匀底上的 1px 细线两侧是同一个底色，根本进不了种子集。合成用例台第 9 条同时钉这两个方向（缝上 = 0.0 且 细线 = 255），因为"擦干净"和"保笔画"必须同时成立，否则就是拿细节换干净。
  - 结果：三张真图标全部修好（高德纸飞机的折面明暗第一次出来、云闪付的卡标三条色带分开、饿了么两层字都留下），代理图无回归（Kimi 底占 0.71→0.86、框内 0.17→0.10）。真机一轮 174 个图标：3 个放弃（王者荣耀 / zyddz.wisdomgame 这类整张插画，`刻底=4 族底只摘掉 0.14 的框` —— 确实没有底可摘，这个数现在跟着日志一起出来），0 异常。
- **新增诊断通道：把真机图标"原样"带回来重放。** `circle/MonoDump.kt` 在模型跑完之后，把**它实际吃进去的那张位图**（矢量已经光栅化、webp 已经解码）压成无损 PNG、base64 分片发进 logcat；`_harness/ReplayKt <日志>` 拼回来、用仓里同一份 `MonochromeMask` 重跑，出判据数字 + 「原图 | 浅色主题 | 深色主题」对照预览。只带**可疑的**（判据不成立 / 框内>0.55 / 碎成 10 块以上 / 淡层>0.08），按 8×8 alpha 指纹去重（同一张形状被问多少次只带一次），每进程 16 张、1.2 MB 预算封顶。⚠ 三个坑都踩过：分片行不带 `what` → 一个图标两份混进同一个桶、14 张全部判成截断；384² 的 src 加 mask 双份发 → logd 直接丢片（掩码是模型算的，重放那边能自己算出来，不该发）；只带"数字难看"的 → 6 个名额全被透明底上的白剪影占满，那类图标的正确答案就是它自己的轮廓。**没有这条通道之前，每一轮"更细致"都是在对着 11 张代理图调参** —— 他报的应用发的是 adaptive vector XML 与 webp，判据台一张都解不出来。
- **裁定（第三轮）：非适配的图标**全部**走合成，并给出「明度」这最后一级。** 他说的是「非适配取色的图标全走纯合成的路线，所以算法要更细致更有层次」，两件事分开落：
  - **不许退回桌面。** 之前有 1.7% 的图标（王者荣耀 / zyddz.wisdomgame 这类整张插画，量出「四族底只摘掉 0.14 的框」）返回 null，而 null 不是"什么都不做"—— 是把这张图交给桌面那条**算错极性**的工厂，观感正是他报的「不是纯色 / 一块白」。新增 `luminanceRamp`：没有底可摘的图，把**它自己的明暗层次**搬到 alpha 上（越暗越实，端点按这张图的 p5/p95 量，不是绝对阈值）。真机一轮 633 个图标：`支=刻底` 345、`支=透明底` 271、**`支=明度` 15**（这 15 个以前全在退回），只剩 2 个放弃 —— 而且那 2 个的理由是 `明度=整张只散开 0L*`：**一张纯色图标确实没有层次可搬**，拒绝是正确答案不是漏洞。0 异常。
  - **层次不能靠把实心段降下来换。** 试过两点改三点（底散度→0、形层 p50→200、p90→255）想把中段拉开 —— 判据台上立刻砸：图标的主体往往就是那个中位色，钉在 200 上白块从 255 掉到 184，正是他报过的「主题色怎么这么浅」。撤回，实心点照旧 p66。
  - **真正的层次在"同一距离、不同明度侧"。** |ΔE| 把"比底暗 42"和"比底亮 46"两块撞成同一个值，可它们在原图里明明是两样东西 —— 这就是「每处都单独上色」还差的那一环。现在按明度分侧：暗侧最多 +16%（本来就该更实），亮侧最多 −8%。**不对称是有理由的**：白字在彩色底上是最常见的"亮侧实心"，压狠了就发浅；而 235/255 的差别肉眼看不出，信息却留住了。乘法保住了"底必为 0"那条性质。
  - ⚠ **还留着一条边界，写进测试不当漏洞**：比实心点更远的一切都被削到饱和，只剩这 ±16/−8% 可分 —— 所以"淡粉"和"纯白"这种都在饱和段、又都比底亮的两块，仍然撞在 235 上。要分开只能改成"越亮越少上墨"的强排序，那会把红底白字一起压下去 —— 那是产品决定，不是算法决定。`SelfTest` 第 12 条把这条边界钉在注释里。
  - 合成用例台从 17 条涨到 **24 条**，新增：同距离不同明度侧必须分开、三段距离必须排出三个浓度、**插画不许退回**（走明度那一级、结果非空非满）、纯色必须明确拒绝。全过。
  - `MonoDump` 顺带修了：整张插画的无损 PNG 有 200KB+、base64 上百行，logd 直接丢片（实测 14 张里 3 张这么废的）。现在超过 90KB 就降到 192 再压 —— 形状与层次照样看得出，尺寸不变性另有第 8 条钉着。

### v1.0.3（2026-09-22）
- 修复：点「清除缓存并重启桌面 / 重启 SystemUI / 重启其他进程」后弹出的提示（Snackbar）被底部悬浮导航栏挡住、截断的问题。提示现在显示在底栏上方，完整可见。
- 调整：桌面图标底板**全 Android 版本固定品牌淡粉** `#FCE4EC`，不再在 Android 12+ 跟随壁纸取色（移除了 `values-v31/colors.xml` 的 `system_accent1_100` 覆盖）。

### v1.0.2（2026-09-22）
- 整合发布：将「应用图标强制裁圆」与「通知小图标修复」两个既有模块整合为一个 Xposed 模块，四页悬浮药丸界面，并统一文案口径（Opticon = Optimize + Icon，两件事落在不同位置，但干的都是把图标优化回该有的样子）。
