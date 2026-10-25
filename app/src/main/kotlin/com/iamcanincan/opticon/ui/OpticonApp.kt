package com.iamcanincan.opticon.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.about.AboutScreen
import com.iamcanincan.opticon.ui.about.UpdateState
import com.iamcanincan.opticon.ui.about.toState
import com.iamcanincan.opticon.ui.crop.CropScreen
import com.iamcanincan.opticon.ui.module.ModuleScreen
import com.iamcanincan.opticon.ui.mono.MonoScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen
import com.iamcanincan.opticon.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

private const val TAB_CROP = 0
private const val TAB_MONO = 1
private const val TAB_NOTIFY = 2
private const val TAB_MODULE = 3
private const val TAB_ABOUT = 4

/** 底栏格数 = Pager 的页数 */
private const val PAGE_COUNT = 5

/**
 * 应用外壳：顶栏 + **悬浮药丸式**底部导航 + 五个页面。
 *
 * 分页按**内容归属**来，三个功能各占一页：
 * - 「裁圆」「单色」「通知」是**功能页** —— 各自的开关和只跟它自己有关的说明；
 * - 「模块」是**共用页** —— 作用域清单（三路功能都要勾）、启用步骤、验证方式、运维按钮；
 * - 「关于」是**信息页** —— 检查更新与版本信息（"看看就好"的那类内容）。
 *
 * 底栏刻意做成**悬浮药丸**而不是通栏的 `NavigationBar`：页面内容从药丸后面滚过，
 * 视觉上更轻、也更贴合 M3 Expressive 的形态语言。选中项会展开文字
 * （未选中的只留图标），所以药丸宽度随当前页变化 —— 这正是它像"药丸"的原因。
 *
 * ⚠ 底栏是**五格**：三个功能 + 模块 + 关于。五格时把每格左右内边距收到 12dp，
 *   360dp 屏上药丸仍留出两侧空隙（16dp 那档在五格下会贴边）。
 *
 * ⚠ 切页**会**销毁离屏那页的组合：Pager 只保留视口内的页。所以「跨页还要留着的东西」
 * 一律放在这里（外壳）而不是页面里 —— 目前有两个：检查更新的结果，和发 Snackbar 的作用域。
 * 各页自己 `remember` 的**开关状态**被销毁反而正合适：切回来会重新读一遍配置，
 * 用户可能刚在别处改过，代价只是重读一次文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpticonApp() {
    // 页面状态交给 Pager —— 它就是「当前在第几页」的唯一真相：
    // 左右滑动由 Pager 自己更新，点底栏则主动滚过去，两边不会各存一份而打架。
    // rememberPagerState 内部是 rememberSaveable，所以转屏后仍停在原来那页。
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // 首次打开就把默认配置落盘。放在外壳而不是各页面里：默认打开的是「图标」页，而模块侧有
    // 几条回退通道要先认「这份文件是不是 Opticon 的配置」—— 判据是
    // `ModulePrefs.CONFIG_KEYS` 里**任意一个键存在**（见 looksLikeConfig）。从没打开过界面的
    // 用户文件里一个键都没有，那几条通道就不该拿它当配置，否则空文件会被当成"用户全用默认值"
    // 把真设置顶掉。所以第一次开界面必须把整组默认值写下去。
    //
    // ⚠ 这是带磁盘读写的副作用，不能放在 remember {} 里：remember 的求值发生在**组合期**，
    //    重组可能重跑它，而且 Compose 不保证它不在 UI 线程的关键路径上。
    val context = LocalContext.current
    LaunchedEffect(Unit) { ModulePrefs.seedIfAbsent(ModulePrefs.of(context)) }

    // 检查更新的**结果**和发起它的那次 launch 都在外壳持有。
    // 「关于」页是 Pager 的一页，切走就离开组合 —— 放那边的话 job 被取消（结论再也写不回来，
    // 而 IO 线程上那条 HttpURLConnection 并不因取消而中断，最长还白等约 40 秒），
    // 那块承诺「常驻」的结果 chip 也会一片空白。
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val checkUpdate: () -> Unit = {
        val version = BuildConfig.VERSION_NAME
        updateState = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
            updateState = result.toState(context, version)
        }
    }

    // Snackbar 也从外壳的作用域发：showSnackbar 会挂满整个展示时长，
    // 用页面自己的 scope 一切页就被取消，提示当场消失（剪贴板那条同理）。
    val notify: (String) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }

    // 跳转失败的提示语。三条跳转（顶栏、「关于」页两块）共用这一句。
    val openLinkFailed = stringResource(R.string.open_link_failed)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark()
                            Spacer(Modifier.width(10.dp))
                            Text(text = stringResource(R.string.app_name))
                        }
                    },
                    actions = {
                        // ⚠ 走 openExternalUrl 而不是直接 `uriHandler.openUri`：没有浏览器的
                        //   设备上后者会抛 IllegalArgumentException 把应用带崩（见该函数说明）。
                        //   失败必须说出来 —— 按下去什么都不发生比崩溃更难查。
                        IconButton(
                            onClick = {
                                if (!openExternalUrl(uriHandler, REPO_URL)) notify(openLinkFailed)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = stringResource(R.string.action_open_repo),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { inner ->
            // ⚠ 这里只扣顶栏让出的空间，**不要**再减掉药丸的高度：
            // 内容要一直铺到屏幕底部、从药丸后面滚过去，那才是「悬浮」。
            // 让位交给各页列表自己的 contentPadding（见 FloatingNavSpace）。
            Box(modifier = Modifier.padding(inner)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        TAB_CROP -> CropScreen(onNotify = notify)
                        TAB_MONO -> MonoScreen(onNotify = notify)
                        TAB_NOTIFY -> NotifyScreen(onNotify = notify)
                        TAB_MODULE -> ModuleScreen(onNotify = notify)
                        else -> AboutScreen(state = updateState, onCheck = checkUpdate, onNotify = notify)
                    }
                }
            }
        }

        FloatingNavBar(
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            // ⚠ `navigationBarsPadding()` 不能省：本 Activity 是 edge-to-edge
            // （`enableEdgeToEdge()` + 系统栏透明），外层 Box 一直铺到屏幕底边，
            // 而药丸是这个 Box 的**兄弟节点**、不经过 Scaffold 的 innerPadding。
            // 少了它，三键导航（导航栏约 48dp）会把药丸下面那截连同每格的点击区一起盖住 ——
            // 系统导航栏吃掉那些触摸，底栏就是"看得见、点不着"；手势导航下那 20dp 也会
            // 落进手势条里。药丸要悬浮在**内容**之上，但不该钻进系统导航栏下面。
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        )

        // ⚠ Snackbar 必须放在悬浮底栏**之上**：这里既靠后绘制（z 序压过药丸），
        // 又把位置抬到药丸上方（药丸顶边约 74dp、阴影到 ~84dp，抬到 92dp 留间隙）。
        // 否则默认贴底的 Snackbar 会被药丸挡住 / 截断（清缓存提示就中过招）。
        // ⚠ 导航栏那一段同理要先让开：92dp 是**相对药丸**定的，药丸抬多高它就得抬多高，
        // 否则两者的相对位置会随导航方式（三键 / 手势）变来变去。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 92.dp),
        )
    }
}

/**
 * 顶栏左侧的品牌标记：与桌面图标同源（背景 + 前景两层）。
 *
 * 加它的理由：整页是卡片叠卡片，顶栏只有一行字，视觉上没有任何"这是 Opticon"的锚点；
 * 而关于页已经在用同一张图标当品牌，这里只是把身份带到第一屏。尺寸 28dp ——
 * 和顶栏一行的高度相称，不抢标题。
 */
@Composable
private fun BrandMark() {
  Box(
    modifier =
      Modifier.size(28.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(colorResource(R.color.opticon_icon_background))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
  ) {
    Image(
      painter = painterResource(R.drawable.ic_launcher_foreground),
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/**
 * 悬浮药丸导航：五格（裁圆 / 单色 / 通知 / 模块 / 关于），选中那格展开文字。
 *
 * ## 底色为什么不是 `surface`
 * 这条原来写的是"用最亮的 `surface`（纯白）拉开明度差"，但两套配色下它都不成立：
 * 写死的浅色方案里 `surface` 与 `background` **是同一个值**（`0xFFFFF8F8`），
 * 动态取色下两者也几乎同色；深色下 `surface` 反而比背景更暗。也就是说药丸与背景的区分
 * 一直只靠 `shadowElevation` —— 而深色里阴影基本看不见（真机截图复核）。
 *
 * 现在用 `surfaceContainerHigh` + 1dp `outlineVariant` 描边：前者在两套主题下都与背景有
 * 一档明确差值，后者把边界钉死，不依赖阴影。**与卡片同一套做法**（见 [OpticonCard]）。
 *
 * ⚠ 改底色时记住这条：**药丸必须在浅色和深色下都和背景有明显差值**，
 * 而"靠阴影"在深色下不算数。
 */
@Composable
private fun FloatingNavBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        // 底色与描边的理由见 [FloatingNavBar] 的说明（不能再退回 `surface`）
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(TAB_CROP, Icons.Default.Circle, R.string.tab_crop, selectedIndex, onSelect)
            NavItem(TAB_MONO, Icons.Default.InvertColors, R.string.tab_mono, selectedIndex, onSelect)
            NavItem(TAB_NOTIFY, Icons.Default.Notifications, R.string.tab_notify, selectedIndex, onSelect)
            NavItem(TAB_MODULE, Icons.Default.Extension, R.string.tab_module, selectedIndex, onSelect)
            NavItem(TAB_ABOUT, Icons.Default.Info, R.string.tab_about, selectedIndex, onSelect)
        }
    }
}

/**
 * 药丸里的一格：选中时是「图标 + 文字」的胶囊，未选中只有图标。
 *
 * ## 动效
 * 三件事一起动，缺一个都会显得"卡"：
 * 1. 胶囊底色 / 内容色 —— `animateColorAsState`，用主题的 `MotionScheme`
 * 2. 整格的宽度 —— `animateContentSize()`（文字挤进来时把胶囊撑开）
 * 3. 文字本身 —— `AnimatedVisibility` 横向展开 + 淡入
 *
 * ⚠ 时长与曲线一律取自 `MaterialTheme.motionScheme`，**不要自己写 tween(300)**：
 * Expressive 的弹簧参数是主题给的，硬编码会在换主题/换设备时和系统动效对不上。
 * 颜色与透明度用 `defaultEffectsSpec`，位置与尺寸用 `defaultSpatialSpec`。
 *
 * ⚠ 「当前是第几页」这个参数叫 `selectedIndex` 而不是 `selected`：里面要写
 *   `Modifier.semantics { selected = … }`，参数同名会把那个语义属性整个遮蔽掉（编不过）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavItem(
    index: Int,
    icon: ImageVector,
    labelRes: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val isSelected = selectedIndex == index

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) scheme.secondaryContainer else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "navItemContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "navItemContent",
    )

    Surface(
        // 「当前在哪一页」只靠高亮是给不了读屏用户的：不加 `selected`，四格在 TalkBack 里
        // 读起来一模一样（README 那句「当前在哪一页由底栏高亮表示」对视觉成立，对无障碍不成立）。
        modifier = Modifier.semantics { selected = isSelected },
        onClick = { onSelect(index) },
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .animateContentSize(animationSpec = motion.defaultSpatialSpec())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                // 选中时文字就在旁边，图标再报一次名字是重复朗读
                contentDescription = if (isSelected) null else stringResource(labelRes),
                modifier = Modifier.size(22.dp),
            )
            AnimatedVisibility(
                visible = isSelected,
                enter = expandHorizontally(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.defaultEffectsSpec()),
                exit = shrinkHorizontally(animationSpec = motion.defaultSpatialSpec()) +
                    fadeOut(animationSpec = motion.defaultEffectsSpec()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
