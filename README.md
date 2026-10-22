# Opticon

**Opti**mize + **Icon** —— 一个 Xposed 模块，专治「图标不对劲」。

三处毛病，一个根源：**系统替你决定了图标长什么样，而那个样子不对**。

| 功能 | 解决什么 | 默认 |
|---|---|---|
| **强制圆形图标** | 桌面 / 应用列表 / 最近任务里的应用图标形状由 ROM 的 mask 决定（圆角方、水滴、方），不一定是圆。这一路把它**一律裁成正圆** | 开 |
| **修复通知小图标** | Android 12 起系统会强行把通知小图标统一着色，没做单色适配的应用会显示成一坨认不出是谁的灰白色块。这一路把它**换回能认出是谁的样子** | 开（系统黑白） |
| **强制取色的单色合成** | Android 16 起桌面会给没做单色适配的应用**现场生成**主题图标遮罩，算法是亮度当 alpha —— 深色字形 + 浅色底的图标整个画反（「颜色反一下」）。这一路**自己合成一张极性正确的单色层**交上去，桌面那套工厂根本不进（v1.1.0 重写的对比度模型，见「单色合成引擎」） | 开 |

一个管**桌面上的应用图标**被套上了不想要的形状，一个管**通知栏里的小图标**被染成了
认不出的颜色，一个管 Android 16 主题图标把整张图的明暗**画反** —— 位置不同，
干的都是同一件事：**把图标优化回它该有的样子**。

三个功能各有独立开关（只想开一个也行），改完**不需要重启**，
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
   > 上面其余几条是给图标那两路用的（裁圆、强制取色的单色合成）。

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
| **图标** | 裁圆开关、未适配图标的单色层开关、效果示意、注意事项、运维按钮（清图标缓存 / 重启进程） |
| **通知** | 通知总开关、作用域、怎么确认生效 |
| **模块** | 三个功能**共用**的东西：作用域清单、启用步骤、验证命令 |
| **关于** | 检查更新与版本信息 |

- **左右滑动也能切页**，和点底栏是双向联动的（当前页由 Pager 统一持有）。
- 底栏是**悬浮药丸**：内容会从它后面滚过去，选中项展开文字、未选中只留图标。
  四格是刻意定的 —— 三格时药丸太短，撑不起悬浮药丸该有的形态。
  底色是 `surfaceContainerHigh` + 1dp `outlineVariant` 描边，**不是** `surface`
  （理由见 `OpticonApp` 的说明：写死的浅色配色里 `surface` 与 `background` 是同一个值，
  深色下 `surface` 还比背景暗 —— 靠阴影在两套主题下都不成立）。
- **各页不再自带品牌头**：顶栏已经有 App 名，每页再来一遍标记 + 版本是重复。
  页面直接从内容开始，当前在哪一页由底栏高亮表示。
  （唯一的例外是「关于」页那块居中的**图标本体**：它是身份说明，不是每页都来一遍的品牌头，
  而且不复述 App 名。）
- 作用域清单用**分段按钮**（必选 + 推荐 / 全部）左右切换，而不是一个文案会变的
  "展开 / 收起"按钮 —— 这两档是并列的，分段控件更直白。
- 动效一律取自 `MaterialTheme.motionScheme`（MD3 Expressive 的弹簧参数），
  没有硬编码时长的 `tween`：Tab 选中胶囊的展开、颜色过渡、作用域清单的展开
  （`animateContentSize`）都跟着主题走。

作用域清单放在「模块」页而不是某个功能页，是因为它本来就有两半：
launcher / settings / system_server 那几条是给图标功能用的（裁圆与单色合成），
而 `com.android.systemui` 一个进程横跨两条（它既画通知，也画状态栏和最近任务的图标）。

> 界面全部走 Material 3 Expressive：圆角一律用 `MaterialTheme.shapes.*` 的语义档位、
> 内容卡一律走共享的 `OpticonCard`（一档底色 + 1dp `outlineVariant` 描边）、
> 配色只用 `colorScheme` 的语义角色，所以浅色 / 深色两套主题都成立。
>
> ⚠ **唯一的写死颜色**是「关于」页那块图标底板的 `@color/opticon_icon_background`
> （品牌淡粉）：那是在复刻桌面图标本体，本来就不该跟随取色 —— 见 `colors.xml` 里的说明。
>
> ⚠ 卡片为什么不用 `ElevatedCard`、字号为什么用 `bodyMedium`：见 `Common.kt` 里
> `OpticonCard` 的 KDoc 与 `SectionLabel` 的说明（2026-09-30 按真机截图重做过一轮）。

设置写进自己的 SharedPreferences，模块侧通过本应用暴露的**只读 ContentProvider**
（`com.iamcanincan.opticon.config`，只读，按调用方包名认自家人）
跨进程读取，按 1 秒 TTL 重读 —— 所以改完开关立刻生效，不用重启进程。

## 验证

```bash
adb logcat -s Opticon
```

**要拿日志做统计（不是只看两眼）之前先把缓冲开大**：`adb logcat -G 4M`。
默认 main 缓冲 256 KiB，而抓取通道一次回传就是 6 行 base64（一张 9 KB 上下）——
开机后桌面重画那一轮能把几百条判据行整个挤出去，事后再 `logcat -d` 只剩个位数行，
点名的账就点不成了（2026-09-28 实测：重启后 12 分钟，`-s Opticon` 只读到 3 行）。
这个设置**每次重启会回到默认**，所以它属于"查之前"的一步，不是一次性配置。

所有功能共用这一个 tag，日志正文里带前缀区分是哪一路。

**图标裁圆**：正常时每个作用域进程会打印

```
module loaded in com.android.settings (systemServer=false)
attach in com.android.settings: framework=... api=102
options: circle=true monoInvert=true notify=true (source=provider)
install in com.android.settings, sdk 36
Hooked com.android.settings
```

桌面进程还会多一行 `PixelLauncher: 2 createBadgedIconBitmap hooked`（Pixel 上进程名是
`com.google.android.apps.nexuslauncher`）。

裁圆还会打两类诊断行（都只在**每个图标多出来的那一趟量测**里）：

- `量一张 X ms → 有留白 / 满幅（不放大）` —— 那一趟光栅化的成本（真机暖态中位 0.60ms）；
- 落到哪一档：`…裁了还剩 X% 外框内切圆 r=… 不透明圆盘 r=… → 放大 X×`（裁得好的）
  / `…裁了只剩 X%（<60%）→ 不裁、缩进圆里 body/far=… 放大 X×，圆内补白 入口=keep|spiky|keep+spiky`
  （裁不动的）。⚠ `（<60%）` **只在 keep 入口时印** —— plate 档有两条入口，走 `spiky`（纯几何
  "圆盘比外框小太多"）进来的那张，`裁了只剩` 可以远大于 60（kodi 就是 72%）。
  ⚠ 这一行是**彩色侧**量的；判"极性开没开"读判据行上的 `判剩=` —— 2026-10-21 起**这两格必须是同一个数**
  （极性的取数搬到了彩色这一本账上，`node _harness/_twobook.cjs <logcat>` 逐包核，不等就非零退出）。
  以前它俩是两本账（判据那一本量的是喂进模型那张，装机现量差 3 个点：wenku8 55→52、contacts 彩色 50 / 判据 49），
  **旧日志里的 `判剩=` 与这一版不可对表**。

**通知小图标**：出现 `patched <包名> 2->1` 就是替换成功了（图标类型从「资源」变成「位图」）。

每台通知小图标还有一条 `通知尺` 的账（**每个进程里每种形状只记一次**，最多 200 行）：

```
通知尺 已适配 源 96x96 尺=67% 首见 android
通知尺 已适配 源 63x63 尺=100% 首见 android
```
（被补那一族多一段 `→ 出 WxH 尺=…`：那是压缩**之后**交付的那张。今天**只采到上面这两行 已适配** ——
被补那一支由 18:44:07 的 `patched com.baidu.tieba 2->1` 证明会走到，但它那行已经转出设备环形缓冲，
所以 `出 … 尺=…` 这个形状尚未在真机上见过。）

`尺` = **墨外接框长边 ÷ 画布长边**。为什么按长边：状态栏槽位是固定的 `status_bar_icon_size`
（本机默认档 22dp，630dpi ⇒ ≈87px）配 `fitCenter`，等比缩放会把"交出去 48 还是 96"吃掉，
**只剩留白比例决定观感大小**。定义钉在 `SelfTest` 第 33 组三条断言上（含分岔口那一条：32×8 在 32×16 上按长边=1.0、按面积会量到 0.25）。

⚠ **通知图标不做尺寸归一**（2026-10-21 定）：被补那族交出去的留白比例 = 源自己的留白比例，与 AOSP 一致。
参照族自己跨 **0.40–1.00**（framework `stat_*` n=100，中位 0.949），要"精确同档"只能连已适配的一起重画，
那破的是"已适配不碰"这条硬规矩。唯一修过的是一处**变形**：非方形源以前被 `setBounds(0,0,96,96)` 拉成正方，
现在等比 fit + 居中（`iconBounds`，见 `docs/KNOWN-LIMITS.md`）。

**射程两头有没有坏**（2026-10-21 起默认全裁、只排磁贴，见上面「管哪些界面」）—— 装机后跑一条：

```bash
adb logcat -d -s Opticon > D:/tmp/lc.txt      # 之前先拉两次通知栏 + 一次快速设置（含编辑/预览页）
cd _harness && node _croprange.cjs D:/tmp/lc.txt   # 退码 0=两头都对 / 1=磁贴没挡住 或 宿主没覆盖 / 2=这轮没测到
```

它读两本账：`裁圆新开 host=… 通道=…`（谁开始被裁）与 `裁圆按磁贴挡下 …`（磁贴确实被排除了）。
退码 `1` 有两种可能，**守卫故意不二选一**：① 这一轮没真打开快速设置；② `TILE_CALLERS` 那条前缀
被 OTA 改了（那就是磁贴正在被裁）—— 判别方法是看 SystemUI 有没有裁过东西。
退码 `2` 是**没测到**（模块没重启 / 屏没亮 / 抓错段），不是"没问题"。
没有 `_harness/` 时的裸查法：`adb logcat -d -s Opticon | grep -c "裁圆按磁贴挡下"` 要 ≥ 1，
且 `grep -a "裁圆新开"` 里要同时有**桌面与 SystemUI 两户**（这一走必到）。**设置那一户 0 行是正常的**：
它被注入了，但上面那一走不经它的取图标路径 —— 自己驱动就能量到，不用请人动手：
`adb shell am start -a android.settings.MANAGE_APPLICATIONS_SETTINGS` 之后再 grep，
2026-10-21 实测打出 `裁圆新开 host=com.android.settings` + 12 行 `clip`（三户齐）。
2026-10-21 实测：8 个"通道×宿主"开火、磁贴挡下 2 次，且 `PM.loadItemIcon` 同一通道先被挡下又新开
⇒ 分得开的是调用方，不是通道名。
这台守卫自己也被变异测过（`node _croprangemut.cjs`：五条，含一条负对照，每条自证样本真被改过）。
旧的 `_notifyguard.cjs` 随"通知行按调用方放行"那条规则一起退休 —— 现在通知行是默认行为，不需要放行。

**桌面主题图标（强制取色）**：每张未适配的图标一行判据，行首带**包名/资源名**，报一个应用名
就能 grep 到它那一行；每 40 张一行汇总。

```
Mono: diag#81 edge=384 sig=4c8deb5010d9 键=b3df31226e76f745 org.mewx.wenku8/drawable/ic_launcher
  BitmapDrawable intrinsic=256x256 bounds=256x256 adaptive=false state=60936b4 票=0抹/0抬/0块
  带占=86 底占=0.01 H=3 族=0 … 拦=128 盘实=0/43537 … → 已交出掩码 几何=缩0.70× 档=plate
  接管=开 判剩52% 入口=keep
Mono: 680 layers synthesized  H0: 接手=9 画布拦=32 关系拦=0  同色退=17
```

⚠ **这两行是从真机 logcat 现抄的，不是手写样例。** 以前这一节给的是
`极性=不翻 墨L= 参照L= 死线=` 加一句「汇总行的 `无判据` 是关键」—— **那四个字段出自
`MonochromeMask.polarity()`，它业务侧零调用者，永远打不出来；`无判据` 更是全仓根本不存在**
（2026-10-16 核对：`app/src` 里 0 处、真机日志 0 行）。拿它当健康指标会读到一个永远"正常"的假数。

现役字段：`H=` 判据自己报的档位（0/1/2/3）、`底占` 被摘掉的底占内容框比例、`框内` 交出去的掩码
占内容框比例、`拦=` 被"整块底"拦下的格子数 —— **`拦=128` 就是极性被 plate 档接管了**
（真机 4531 行 diag 里**只有 wenku8 这一个图标**走到那一步，它占两行同 `sig` —— **行数≠张数**）。
`接管= / 判剩= / 入口=` 是极性那一档**自己吃的数**
与它从哪条入口进来（`keep` / `spiky` / `keep+spiky`，见 `IconCropWindow.PLATE_SUBJECT_MIN`）。

`调跨= / 调未算=` 是**满幅那一支开没开火**的账，两个都在判据行末尾：`调跨=N` = 算了连续调跨度、
没过 `TONAL_SPAN_MIN` 所以没开火；`调未算=3` = 压根没算，因为 `build()` 这一遍交的是**参照那一遍**
的产物（按设计）。以前这两种情况**在同一行里长得一模一样**（都是什么都不印），于是"换支账"
读不出漂移来自哪一类。真机同一台、两个进程正好是一组前后对照（`node _harness/_tonalacct.cjs`）：
**加这两列之前的那次构建 161 行里有 47 行三类都落不上**（开火 35 / `调跨=` 79 / 其余 47 什么都不知道），
加上之后 230 行**恰好盖满**（开火 65 / `调跨=` 95 / `调未算=3` 70），且两次重载逐数相同。
`调未算=` 只出现过 **3**（= 参照那一遍，按设计）；**1/2/4**（框太小 / 框内无实心 / 凑不出相邻对）
**一条都没有**。

🔴 **`判剩=` 与彩色侧那行的 `裁了只剩` 是两个数，别拿一个对另一个的表。** 同一张
`com.android.contacts/logo_quick_contacts_mail`：彩色侧 `裁了只剩 50%`、判据侧 `判剩49%` ——
**差 1 个点，正好跨过阈值 50**。判"极性开没开"只认判据行上的 `接管=`/`判剩=`/`拦=`。

**这台子到底有没有在跑**（替代那句已经不存在的 `无判据`）：看两条 ——
① `Mono: N layers synthesized` 里那个 N 随图标加载往上涨；② 报一个应用名能 grep 到它的
`Mono: diag#` 行。**两条都没有 = 钩子没装上**（不崩、不报错、桌面照常显示旧缓存）。

判据台与真机重放另外打模型自己那一路的数（`带占/底占/零/实/端/壳占/降/降力/明暗/框内/
cc/top/小片/空洞/淡/色驱`），逐字段解释见判据台 `_harness/SelfTest.kt` 的用例名
（`_harness/` **不入库**，见文末）。合成那条路现在不上屏，这些数只在离线跑
（判据本身仍然出自同一份 `MonochromeMask`）。
⚠ 这些数只在**图标被重新加载时**产生：桌面缓存里是预渲染好的位图，光重启桌面进程只会
重算新加载的那几个，要看全量分布必须先按界面里的「清除缓存并重启桌面」。

`options:` 那行里的 `source=` 表示配置是从哪条通道读到的：正常情况下是 `provider`。
若显示别的来源，说明 provider 没查到，模块在用备选通道或默认值。

**装钩的每一条通道都会自己报数**：`ParcelWrite: 2 hooked` / `MarkedIconIds: 9 hooked` /
`PackageInfoCommonUtils: 5 hooked` 这类行，挂上几条就报几条；**一条都没挂上时报的是
`<通道名>: 0 hooked —— 这条通道在本进程没生效`**（W 级），找不到目标类则是
`<通道名>: 找不到 <类名> —— 这条通道在本进程没生效`。
所以判断"这台机器上功能完整吗"要看的是**有没有 0 行**，而不是收尾那句 `Hooked <包名>` ——
后者只说明加载出口挂上了。

两条特殊：
- `ParcelWrite: 0 hooked` 不只是少一条日志 —— 它是"打过标记的 `0x6e` 资源 id 别泄漏到未注入进程"
  的唯一闸门，挂不上时本进程**一个标记都不打**（否则会崩掉别人的应用），代价是圆角图标的
  构造/批量通道整体不生效，此时会另有一条 E 级说明。
- 不是 AOSP / Pixel 桌面的进程会打一行
  `<包名> 不在已登记桌面的名单里 → 单色合成 / 最近任务通道不挂`。
  换第三方桌面时**这一行是功能不生效的第一原因**，需要把桌面包名加进登记名单。

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
   结构 = `背景层（宿主的盘色，只给取色合成器看）+ 前景 = 缩到可见区、套过圆形遮罩的图标`：
   - **裁剪基准是"图案实际占的那块"，不是整块画布**：先把原图标光栅化到 128 边长的一张小图，
     量出图案外框与"不透明圆盘"（半径方向上整个落在实心区里的最大圆盘），再按它 cover + 内切。
     图案本来就满幅的走满幅早退、逐位不变；真机上那 59/61 张属于这一类。
   - **两档**：裁得好的 → 内切填满、圆外透明、无白边；**裁不动的**（按圆裁下去只剩 <60% 图案 ——
     宽幅式、铃铛、十字、环一类）→ **不裁**：整幅缩进圆里，圆内先铺一层白底（画在前景位图里），
     缩量由"主体进安全区 / 尖角不贴边 / 墨量对齐"三条里最紧的一条定。
   - **这条窗口判据收在 `IconCropWindow`，是彩色与染色共用的唯一来源**：开着「强制裁圆」时，
     未适配图标的单色合成**按裁圆定的那个尺寸摆** —— 判据先在**没搬过**的那一张上算完（浓度、分档、
     盘刀、极性一个像素不动），只把**算好的交付掩码**按同一个窗口重排（`under` 不参与这一手）。
     **搬过的那一张一律映射到图层的可见区，不是整张 108dp 画布** —— 桌面画图层按 1.5 倍铺进去再裁边，
     铺满画布落到屏幕上就是又放大 1.5 倍（量过的账见 `CHANGELOG.md` 的 **v1.1.2** 那节）。倍数超过
     **1.82×** 的整张不搬（那道线的两个邻居是他对着屏点的：uu远程 1.75× 留、openlist 1.90× 拦）。
     **不搬那个圆**：圆是彩色那支
     自己 `DST_OUT` 的，染色的形状由桌面 mask 决定，烘进去模型会把圆边当成一块底。
     ⚠ **已适配的图标（自带 monochrome 层）原样透传，不参与这一手**（主人定的范围）。
     判据行末的 `几何=满幅 | 量到没搬 | 放1.47× | 缩0.78×` 加 `档=plate|inscribe` 就是这一趟的读数
     —— ⚠ 它只报**倍数与档位**，两版倍数相同
     而屏上大一圈是可能的（落点不进这个数），要对"交付那张"只能比 `noteDiagnosis` 的缩略图。
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

### 管哪些界面（射程）

**默认全裁，只排快速设置那一路**（2026-10-21 定：「裁圆算法应只排除快速设置的磁贴，别的应做到全覆盖」）。
旧版这里是"白名单宿主 + 名单外按调用方放行"，那两层都已经删掉 —— 判据只有一处：

1. **排除**：`CircleIconDrawable.TILE_CALLERS = com.android.systemui.qs.`，按**调用方**判
   （`calledByQuickSettings`，堆栈只在 SystemUI 里读，别的过程读了白读）。
   这一族在真机上盖住：磁贴本体（`qs.tiles` 217 个类）、**编辑/预览页**（`qs.tiles.dialog` 90）、
   新面板（`qs.panels.*` / `qs.pipeline.*`）、第三方磁贴宿主（`qs.external` 59）——
   数是从这台机器的 `SystemUI.apk` 三个 dex 里扫出来的（`node _qsfind.cjs D:/tmp/systemui.apk`）。
2. **第二道**（防御性、不承重）：system_server 打标记时本来就跳过 `BIND_QUICK_SETTINGS_TILE` 的
   `ServiceInfo`，所以磁贴自己的图标 id 也不会被标记。

⚠ **为什么必须按"调用方"而不是按"通道"**（2026-10-17 从这台机器的 framework dex 读出来的）：
通知行的应用图标走 `loadUnbadgedIcon → loadUnbadgedItemIcon → APM.getDrawable`，而磁贴走的
`loadItemIcon` **内部第一件事就是回 `loadUnbadgedItemIcon`**（`1f6df0`）—— 两条路在框架里
**汇到同一个出口**，通道名分不开它们。

🔴 **代价写在明处**：现在**每一个被注入的进程**都裁，包括第三方 app 里显示 app 图标的界面
（作用域是你在管理器里选的，见「模块」页；**没注入的进程永远不裁，代码补不了**）。
哪一户裁坏了，拿日志里那行 `裁圆新开 host=… 通道=…` 点名，往 `TILE_CALLERS` 旁边加排除，
**不要回去改默认值** —— 漏一户看得见、能点名、可排除；多一户只是形状变了。

**装机后必跑守卫**（它守的是"两头都别偷偷坏"）：

```sh
# 先走一遍：下拉两次通知栏 → 下拉一次快速设置（顺手打开编辑/预览页）→ 回桌面
adb logcat -d -s Opticon > D:/tmp/lc.txt
cd _harness && node _croprange.cjs D:/tmp/lc.txt
# 退码 0 = 两头都对；1 = 磁贴一次都没挡下（没打开面板 或 前缀漂了）／宿主没被覆盖；2 = 这一轮没测到
```

`⚪ 没测到` 与 `✅ 通过` 是两种读数，**守卫不许把前者报成后者**；这台守卫自己也有变异测
（`node _croprangemut.cjs`：五条，含一条负对照，每条都自证样本真被改过）。
旧的 `_notifyguard.cjs`（守"通知行还在不在裁"）随那条规则一起退休 —— 现在通知行是默认行为。

### 开关关掉时

裁圆的判定收在**一处** —— `clipToCircle()`：所有替换通道最终都汇到这里，
开关关掉就把原图标原样交回去（图标 id 本身是换回原始值之后才解析的，
拿到的就是应用原本的图标）。

⚠ **读不到配置的进程会用默认值（功能全开）**：作用域可以由用户在管理器里额外勾选，
那些进程不在 provider 的包名白名单里，拿不到配置。
对它们而言「关掉开关」不生效。真正决定全局的是 **system_server**：它在名单里，
读到关闭就不给图标 id 打标记，下游任何进程拿到的都是未经处理的原图标。

## 功能二：单色合成（强制取色）

Android 16 的「强制动态取色」会给**没做单色适配**的应用现场生成一张单色遮罩
（`com.android.launcher3.icons.MonochromeIconFactory`）。它的算法是**亮度当 alpha**：把图标
画进 ALPHA_8、读回亮度、按这张图自己的 min/max 归一，再叠一条以 128 为中心的对比度曲线。
一句话 —— **它假定「亮的就是墨」**。深色字形 + 浅色底的图标因此变成「字形是洞、底色是形」，
看着整个反掉，这就是用户报的那句「颜色反一下」。

现在只有一条路：**模块自己合成一张极性正确的单色层交上去**，桌面根本不进它自己那套算法。两道钩子（`circle/IconHooks.kt`）：

1. `hookSynthesizedMonochrome` 钩 `AdaptiveIconDrawable.getMonochrome()`。应用自带单色层就原样
   透传（一个像素都不碰）；没有的话，把这张图光栅化、交给 `MonochromeSynth.forIcon` →
   `MonochromeMask.build` 压一张**极性正确的剪影**直接返回。桌面只在 getter 返回 null 时才去 new
   它自己的 `MonochromeIconFactory` —— 合成一接上，那条把亮度当不透明度的路径**根本不进**。
   代码里对这条的原话是「这是主路径，也是唯一路径」。
2. `hookMaskFallThrough` 只数一件事：**桌面自己出了多少张遮罩** = 上游合成漏接了多少张。
   纯合成模式下这个数应该是 0；它非零是「屏幕上有一批图标是反的」唯一的可见信号，
   日志是 `Mono: N 张走了桌面自己的遮罩…`。

「已适配的不动」不用判条件：桌面只在 `getMonochrome()` 为 null 时才 new 那个 Factory，
自带单色层的应用根本走不到这条路径。

开关是**有的**（图标页 →「未适配的图标由本模块生成单色层」）：关掉 = 完全交回桌面自己处理。
它不是口味旋钮，而是"这台 ROM 上厂商已经修过极性、我们整体不该介入"时的逃生口。
一张一张定"这个应用翻不翻"的调试面板已经删掉了（2026-09-30）：它给的是"绕开判据"，
而这一路要的是把判据量对。

⚠ 这一路**不读桌面任何私有字段** —— 旧版翻转兜底要读 `mAlphaBitmap`，字段名一变整条静默失效，
那是它被合成换掉的直接原因之一。极性怎么判、为什么"亮的不一定是墨"，全在下一节的模型里
（`MonochromeMask` 的 `groundPass` 是全仓唯一一份"哪边是图形"的实现，出现第二份就会各自漂移）。

> 这里曾经的第一条路是**翻转兜底**：让桌面先算出一张极性错误的遮罩、模块再整张翻回来。
> 换成合成优先之后它只剩兜底，现在整条已删。`MonochromeMask` 里那套极性判据
> （`Polarity` / `polarity()`）**只剩判据台与性质在用**，业务侧已无调用者 —— 别把它当成
> "屏幕上那条路还在翻转"的证据。极性怎么判、模型长什么样，见下面的「单色合成引擎」一节。

**剪影按内容缓存两层**（2026-10-15）。一张剪影现压 **350 ms**，而桌面**每次 bind 都 `getConstantState().newDrawable()`
交一个新实例** —— 所以按实例建的缓存结构上命中不了：真机一次重启 **1594 次合成、里面只有 209 种内容**
（同一张最多重压 148 次），串行在一把锁上铺满 760 秒。现在键换成内容本身
`(像素, 层底像素, edge, adaptive, flatten, 强制裁圆)`（掩码就是这六样的纯函数，少一位就是"换了设置还给旧答案"），
两层：**内存 LRU 8 MB**（故意不追求装下整个抽屉的 30 MB，扫穿无害，漏下去的落到磁盘而不是落到模型；
负缓存条目**一个字节都不占**，所以字节那道闸对它们无效，另有一道**条数**上限 4096 兜住）+
**磁盘 48 MB**，落在**宿主的 `cacheDir`/opticon-mono/<装机身份+API>/**（我们就跑在那个进程里，只能写它自己的地盘；
换到模块 dataDir 是跨 UID）。目录名认 `lastUpdateTime` 不认版本号，再带 `_api<SDK_INT>` —— 换装新构建整层作废，
OTA 也整层作废（`ALPHA_8` 的 `rowBytes` 这类"像素没变、交付口径变了"只有系统侧会动，内容键盖不住）；
否则迭代时看到的是上一版画的那张；拿不到装机身份就整层磁盘关掉，宁可不快不给旧答案。
"判据不成立、放弃合成"那几张也记负缓存（只记内存），它们同样被重压过 148 次。
真机账：热重启 **2 次现算 / 1880 次命中**，前 300 张上屏 **63.5 s → 5.07 s**；界面上「说明」那条与
「清除桌面图标缓存」按钮的文案就照着这一段写（那个按钮清的是桌面的 `app_icons.db`，动不到这一层）。
两条**量到零**的否定结论记在这里，别再试：并行化合成（1894 次请求全在同一条加载线程上）、
以及按 `ConstantState` 建一层省掉光栅化（1043 次请求对 1043 个不同 state，桌面连 state 都是每次新建）。
⚠ 后面那一条**不能读包装路径**：`CircleIconDrawable.getConstantState()` 每次调用都新建一个匿名 ConstantState，
那条路上"state 永远独一无二"说的是我们的 getter，不是桌面的行为 —— 日志里这一位对自己标 `state=-wrap`，
换一台桌面重开这条之前先看类名。

## 功能三：修复通知小图标

### 只做黑白

把 App 自己给的小图标压成**单色剪影**，交给系统按主题着色 —— 只此一条，没有可选模式。

⚠ 曾经是两种（另一种「换成桌面彩色图标、原色保留」），2026-09-30 按主人的要求删掉：
那条要成立得钩进 SystemUI 的着色链里改图标颜色，是整条功能里最依赖系统内部实现的一部分，
而实际没人用。已经是单色 / 灰度的图标仍然**一律不动**。

### 它改的是哪个图标

模块改的是通知对象里的小图标字段（`Notification.smallIcon`）—— 就是被系统染色成一坨的那个。
它是通知内容区图标的来源，也是状态栏图标的来源，所以**不要在状态栏那行上找差异**：
真正直观的变化在通知内容区那个小图标，从灰白色块变回能认出是哪个 App 的样子。

⚠ 过去这里还有一句「配套还会阻止系统把替换后的图标重新染回单色」（钩 `IconManager#setIcon`、
`StatusBarIconView#updateIconColor`、`Notification.Builder#processSmallIconColor`）—— 那是
「保留原色」那一档的配套，2026-09-30 随那一档一起删了（`ca93cba`）。通知侧现在只有黑白一条路。

### 几个关键约束

- **只动没适配的**：用和系统同款的灰度判定检测图标是否已做单色适配，已适配的直接跳过 ——
  免得把人家本来正确的图标改坏。
- **状态栏只取 alpha 通道渲染小图标** ⇒ 交出去的位图必须有意义的透明背景；
  不透明彩色图标必然变纯色块。
- **自适应图标光栅化是「遮罩贴边」的** ⇒ 裁空白、放大填满这类操作对**整张**是空操作，只对前景层
  才有意义。曾经「彩色桌面图标」那一档会先裁掉空白再放大填满（光栅化后内容只占画布中间约 61%，
  不填满缩到通知里那点尺寸会又小又糊）—— 黑白这一档不做这件事，`fill()` / `scaleTo()` 随那一档
  一起删了（`ca93cba`）。
- 仅注入 `com.android.systemui`，不碰其他进程。
- **活路径已在真机核过（2026-09-30，XQ-DQ72 / Android 16）**：SystemUI 里
  `inflateViews hooked (1 overload(s))`，而 `not resolvable` / `inflateViews not found` /
  `inflateViews patch failed` **三条各 0 次** —— 这条通道装上了、没静默失败。
  ⚠ 同一段时间里 `patched` 也是 0 条，那**不是**"修了 0 次都成功"，是**这段时间没有哪条通知的
  小图标需要被修**。判"钩子活着"要看装钩那一行，不能看 `patched` 的条数 —— 零条 `patched`
  加零条错误，在这条通道上读不出任何结论（这个坑踩过一次，靠的正是"每一条早退都要留下名字"
  那次修复才分得开"没装上"与"没有东西要修"）。

### 改完为什么不生效

替换只发生在通知行**首次** `inflateViews` 时，**已经在通知栏里的那条不会变** ——
要等它重新加载（重新发出，或重启系统界面）。

## 运维按钮（纯 LSP，不 root 不 shell）

界面里的三个按钮走的是「App 发广播点名 → **目标进程里的模块**自己执行」：

- **清除缓存并重启桌面**：删 launcher 自己数据目录里的 `app_icons.db*`，然后自杀重启
  ⚠ 它**不删**模块自己那层剪影缓存（`<launcher 的 cacheDir>/opticon-mono/<装机身份+API>/`，上限 48 MB）——
  那一层不需要手动清：键是图标内容，目录名是"哪一次装机"，所以图标更新 / 改了「强制圆形图标」/ 升级模块
  都会自己重压。反过来说，升级模块后第一次重启会慢一些（那 209 张要现压一遍），之后就快了。
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

## 单色合成引擎

Android 16 的「强制动态取色」会给**没做单色适配**的应用现场生成遮罩，算法是**亮度当 alpha** —— 深色字形 + 浅色底因此整个反掉。Opticon 的答案是**自己合成一张极性正确的单色层直接交上去**，桌面那套工厂根本不进。合成器（`circle/MonochromeMask.kt`，纯数组进出）只问一句：

> **这个像素与它周围那块底差多少？**

### 模型

- **底 = 局部底色场**：内容框四角的颜色家族（≤4，圆角沿对角找窗口）铺成一张 8×8 场，再把每格底色**传播**到整张画布 —— 一个像素的参照 = 离它最近的那块底。渐变底在每个格子里都有自己那一份，所以渐变不需要并层；拼色底（高德四块、云闪付红白两段）是所有家族的并集，不会剩下半块变墨。
- **差 = CIELAB ΔE76**（整数查表，SelfTest 对公开参考值核过），按像素 alpha 收一道覆盖率；`dep = a×[(1−β)+β·ΔE/100]` 应用两遍（对底 / 对近旁主体）。
- **alpha = 两点标定 + `t^0.75` 曲线**：零点 = 底板自己的散度，实心点 = 形层距离分位。「一整块色板」不是被闸门拦掉的，是**产生不出来**。
- **支路**：H1 底参照 / H2 / H3 空气参照 / H0 区域关系四支选支，满幅插画（没有「底」可摘）单独一支按自身明暗铺层次；透明底退回图标自带 alpha + 被围住的内部镂空。

### 支路上的刀（真机语料一张一张喂出来的）

| 刀 | 治什么 |
|---|---|
| `propagateBands` + 桥接 | 粗块连接、1px 缝，细带归属沿连通传播 |
| `flatVote` | 同一块源色被给了两个答案的投票 |
| `carrierPocket` | 与「交成底的自己」同色、却被交成墨的承载面口袋（易班蓝气泡块一类）整袋抹掉 |
| `separation` / **SepTone** | 源色差得开、交付撞成同一浓度的那一对，把浅而小的挪开 |
| 盘刀 / 膜刀 | 背景层的盘、叠膜各自的误判修补 |
| `ownBrightPlate` | 前景层自带的整块白底不压地板 |

### 量具

算法的判据全是数字，数字不能靠推演定。`_harness/`（**2026-10-21 起裁判本体已入库**：判据 `.kt` + `*.cjs` + `*.sh` + 各档底线 + 不可复现的 34 张语料；语料图片与可重建的那批仍在本机）是一台纯 JVM 判据台：手写 `android.graphics` 桩，跑的就是**仓里那份真 `MonochromeMask.kt`**。`sh regress.sh` 一条命令跑 **20 道回归闸**（性质台 / 单调性审计 / 对表「人画的单色层」/ 兼容性台 / 肉眼账 / 换支账 / 源尺寸账 / 无对岸半灰账 / 掩码缓存账 / 极性入口账 / **跨 canvas 判据不变账** / **平块坡数账** / **两本账一本账**……），任何一项比基线差就非零退出；发版前另跑 `EDGES=96,192,384,768` 四档全扫。⚠ **分母覆盖哪一类要写出来**：2026-10-22 起语料里多了**长按快捷方式**那一类（`shortcut_model` 183 张，第 16/19 闸吃它）—— 在那之前"离线量到 0 变化"曾把真机翻的两张快捷方式漏掉。细节见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。⚠ **回归台绿 ≠ 能编译**：`build.sh` 只把清单里那几份喂给 kotlinc，要 Android 类的（`MonochromeSynth.kt` / `CircleIconDrawable.kt` / `IconHooks.kt` 等）它编不动 —— 这一半由 `sh _harness/precommit.sh` 管（提交前跑；退 1 = 碰了它们而 release 成品不比改动新，退 2 = 它自己解析不出清单，不许读成"没碰生产源码"）。

## 已知取舍

以下都是**量死后有意不修**的边界，不是没看见；每条的完整过程（试过什么、数字多少、为什么死、尸体埋在哪）见 [docs/KNOWN-LIMITS.md](docs/KNOWN-LIMITS.md)，判据的编年史见 [docs/devlog/](docs/devlog/)。

- **一层 alpha 只能表达一个量**：与底同色相又同亮度的东西本来就画不出来。仪表环续弧（globalspeed）断口就是这条颜色墙，四条路全部量死。
- **两块都被判成底时，中间那道边留不下**：给其中一块降档要先指认「哪块是图形」，那道证据至今量不出来。
- **「色块 vs 线稿」族认了**：描边 logo 被填成实心（credentialmanager 等 5 张），主人看图裁定「没什么问题」。
- **满幅插画支的边界**：支路判定线画在「纹理 23/24」之间没有能站人的位置；极性固定「暗=墨」，亮主体压暗底的插画会被反。
- **层次细腻与实心冲突时先不伤人**：坚果云橡子那类三档真实层次，前提放进来的会有本来正确的图，没收。
- **自带单色层的应用按硬规则一个像素不碰**（transparency 优先），对表台上那 62 张人画单色层是验收标准、不是缺陷清单。
- 自适应图标把图形整个放背景层时前景栅格化为空 → 交全 0 掩码（真机语料 1/91 张，且自带单色层不会上屏）。
- 上屏浓度由桌面归一承担；快捷设置磁贴小尺寸图形不处理；通知已适配图标一律不动。
- **合成掩码按内容缓存（内存 + 宿主 `cacheDir`），热重启几乎全部命中；命中之后仍付一次光栅化**（键要像素，
  拿像素就得先画一遍；哈希只 0.15 ms）。缓存的失效条件是**装机身份**（认 `lastUpdateTime`），换构建整层作废。
- **裁圆的「满幅」判据不与别的启动子对标**：Lawndesk（AOSP 血统）那条「10% 透明 ⇒ 满幅」同名不同义
  （它的用途是放大 1.44× cover 让遮罩裁边，我们的用途是**一个像素都不动**），照搬会让 `live61` 里
  32/61 张圆盘**长大 3.2%**；另一头「外框 95~99% 带 1~2 行留白」那条缝在 706 张语料里只有 1 张（Kimi），
  且它现在放大那条是对的。**判据不动。**
- **应用声明了两份图标时各归各的**：桌面为圆形遮罩优先取 `roundIcon`（那份通常是自适应图标、**平台画得就对**，
  我们不碰），应用信息页走 `icon`（我们接管）。这类应用两屏**天然不同源，不是缺陷** —— 曾试图"两边都归我们管"
  而把桌面那份改坏，已撤回（主人判「原桌面图标就是一个完美的圆了」）。
- **标记 id 不假设应用资源住在 `0x7f`**：真机有整个 APK 的资源都在 `0x95` 包号下的（`--package-id 0x95`），
  老写法会让这类应用**整批隐形**。改成进程内槽位表，`0x7f` 之外也能认。
- **「外框大、不透明圆盘小」的图案（字母 / 尖角类）走「缩进 + 圆内补白」**：`rCover` 当窗口只对圆盘状图案
  成立，这类走"内切填满"会被放大到溢出画布（Kodi 实测放大 1.49 倍、外框比画布宽 38%）。线摆在 0.80，
  语料 703 张一张都不动；**代价是这一档必然带一圈白**（白是我们画的）。

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

## 开发

判据台的完整玩法（各驱动的来历、踩过的量具坑、真机重放通道）在 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

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
├── circle/                    ← 功能一：应用图标（裁圆 + 单色合成）
│   ├── IconHooks              各条 hook 通道的安装与拦截逻辑
│   ├── MonochromeSynth        强制取色：画布与坐标 + **极性判据的入口**（光栅化、线程内交接、诊断）
│   ├── MonochromeMask         单色合成引擎：`dep(a,β,ΔE)` 应用两遍（底色场 / 近旁主体），四支选支 + 各刀，上屏与判据台同一份
│   ├── MonoDump               诊断通道：把它实际吃进去的那张位图经 logcat 带回来重放
│   ├── IconCropWindow         **图案窗口的唯一判据**（纯函数）：彩色那支照它裁，染色那支照它重排剪影
│   └── CircleIconDrawable     圆形 drawable（自适应图标 + 圆形遮罩）
├── notify/                    ← 功能二：通知小图标
│   ├── SystemUiHooks          往 SystemUI 里装挂钩
│   ├── NotificationIconPatch  要不要替换、换成什么的判定
│   ├── IconBitmap             位图的取与压单色（通知那半边，桌面已不再共用）
│   └── ToneCheck              单色（已适配）判定
├── debug/                     ← 诊断，不参与任何判断
│   └── CorpusCapture            adb 触发的语料抓取（真机图标 → 判据台），见「离线判据台」一节
├── runtime/                   三个功能共用
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
│   ├── icon/IconScreen        「图标」页（裁圆 + 单色层 + 运维按钮）
│   ├── notify/NotifyScreen    「通知」页（通知小图标）
│   ├── module/ModuleScreen    「模块」页（作用域 / 步骤 / 验证）
│   ├── about/AboutScreen      「关于」页（检查更新 / 版本信息）
│   └── theme/Theme            M3E 主题（动态取色）
└── update/UpdateChecker       检查更新（唯一的联网点）
```

## 许可

MIT —— 见 [LICENSE](LICENSE)。

## 变更日志

- **v1.1.2（2026-10-17）**：染色那支不再跟着裁圆的「纯几何」入口走 —— 「外框大、不透明圆盘小」那一类（Kodi）不再整块上墨；
  「只给前景层」那一路的单色几何与彩色完全对齐；裁圆的资源标记不再假设包号 `0x7f`（HookVip 一类第一次被认出来，
  桌面那张 47.5% → 78.8%，与应用信息页 78.5% 两边一致）；判据行补上 `接管= / 判剩= / 入口=` 与 `调跨= / 调未算=`，回归台 14 → **16 道闸**。
- **v1.1.1（2026-10-15）**：强制裁圆的内切基准从「整块画布」换成「图案实际占的那块」（外框 + 不透明圆盘，在钉死的 128 边长上量一次）；
  按圆裁下去留不下 60% 图案的那一类**不再硬裁** —— 整幅缩进圆里、圆内补白底，缩量由「主体进安全区 / 尖角不贴边 / 墨量对齐」三条里最严的一条定。
- **v1.1.0（2026-10-14）**：图标单色合成引擎整条重写为对比度模型；判据台 7 闸 → 14 闸。按版本收口的摘要见根目录 [CHANGELOG.md](CHANGELOG.md)；
  每一刀的完整过程（为什么收、为什么拒、被哪道闸拦下）见 [docs/devlog/](docs/devlog/)。
