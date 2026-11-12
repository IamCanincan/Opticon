package com.iamcanincan.opticon.ui

import android.content.res.Configuration
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamcanincan.opticon.BuildConfig
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.about.AboutScreen
import com.iamcanincan.opticon.ui.about.UpdateState
import com.iamcanincan.opticon.ui.home.HomeScreen
import com.iamcanincan.opticon.ui.features.FeaturesScreen
import com.iamcanincan.opticon.ui.stats.StatsScreen
import com.iamcanincan.opticon.ui.about.toState
import com.iamcanincan.opticon.ui.crop.CropScreen
import com.iamcanincan.opticon.ui.module.ModuleScreen
import com.iamcanincan.opticon.ui.module.ScopeScreen
import com.iamcanincan.opticon.ui.module.SetupScreen
import com.iamcanincan.opticon.ui.module.ToolsScreen
import com.iamcanincan.opticon.ui.mono.MonoScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen
import com.iamcanincan.opticon.update.UpdateChecker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

private const val TAB_HOME = 0
private const val TAB_FEATURES = 1
private const val TAB_STATS = 2
private const val TAB_SETTINGS = 3

/** 底栏格数 = Pager 的页数 */
private const val PAGE_COUNT = 4

/**
 * 自动检查更新「每进程一次」。
 *
 * 放文件级而不是 `remember`：转屏 / 从后台回来会重建 Activity，`remember` 活不过去，
 * 那样每次重进都会再联网一次。这个标记只求活到进程结束。
 */
@Volatile private var autoUpdateChecked = false

// 底栏几何照搬 KeyLens 的 webui：药丸 6dp 内边距 + 每格 52dp + 44dp 圆形光晕 + 24dp 图标。
// 四格在 360dp 屏上仍留边（展开文字那格约 98dp，其余 52dp，合计约 254dp）。
private val NavPadding = 6.dp
private val NavGap = 4.dp
private val NavItemHeight = 52.dp
private val NavHaloSize = 44.dp
private val NavIconSize = 24.dp

/**
 * 药丸距系统导航栏的边距（收起动画的位移量也用它）。
 *
 * 和 KeyLens 的 `--inset-bottom + 24px` 对齐：24dp 比原来的 20dp 只高 4dp，
 * 既离开底部手势判据一点，也不至于悬得太高。
 */
private val NavBottomInset = 24.dp

/** 横向拖动拿起活动圆的手指位移（KeyLens 用 6px） */
private val NavDragSlop = 6.dp

/** 下滑超过这个量把药丸收起来；上滑同样的量再放出来 */
private val NavScrollSlop = 6.dp

/** 页面离顶不足这个距离时不收药丸（KeyLens 的 `y < 24`） */
private val NavNearTop = 24.dp

private class NavEntry(val index: Int, val icon: ImageVector, val labelRes: Int)

private val NavEntries = listOf(
    NavEntry(TAB_HOME, Icons.Default.Home, R.string.tab_home),
    NavEntry(TAB_FEATURES, Icons.Default.Build, R.string.tab_features),
    NavEntry(TAB_STATS, Icons.AutoMirrored.Filled.List, R.string.tab_stats),
    NavEntry(TAB_SETTINGS, Icons.Default.Settings, R.string.tab_settings),
)

/**
 * 应用外壳：顶栏 + **悬浮药丸式**底部导航 + 四个页面。
 *
 * 分页按**内容归属**来：
 * - 「主页」**看**：状态与次数，点一行进三个功能的二级页（说明与效果，只读）；
 * - 「功能」**改**：三个功能的开关与「全部开启 / 全部关闭」；
 * - 「统计」**查**：按功能分组的次数；
 * - 「设置」**配**：作用域、启用与验证、运维操作、关于（都是二级页）。
 *
 * 底栏刻意做成**悬浮药丸**而不是通栏的 `NavigationBar`：页面内容从药丸后面滚过，
 * 视觉上更轻、也更贴合 M3 Expressive 的形态语言。几何与交互对齐 KeyLens 的 webui：
 * 选中项是「44dp 圆形光晕 + 侧边文字」（未选中只留光晕里的图标），整颗药丸随当前页变宽；
 * 长页下滑时药丸下沉缩小消失、上滑或回顶再出来；按住活动圆横向拖到另一格可直接切页。
 *
 * ⚠ 底栏是**四格**：主页 / 功能 / 统计 / 设置。每格固定 52dp（44dp 光晕 + 4dp 内边距），
 *   360dp 屏上药丸仍留出两侧空隙。
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
    // 切页**只走底栏**（点击或拖拽），页面本身不响应左右滑动（见 HorizontalPager 的
    // `userScrollEnabled = false`）：滑动会和系统边缘返回手势、页面内控件抢手势。
    // rememberPagerState 内部是 rememberSaveable，所以转屏后仍停在原来那页。
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // 底栏的「读完长页就收起来」：和 KeyLens 一样按滚动方向判 —— 向下累计 6dp 收起，
    // 向上累计 6dp 放出；离顶不足 24dp 时不收（短页根本滚不动，也就不会触发）。
    //
    // ⚠ 这里单独挂一条 connection，只读 `consumed.y`（符号：向下滑为负），不动顶栏那条。
    //   `contentOffset` 只用来判断"离顶多远"（EnterAlways 在 onPostScroll 里直接累加
    //   consumed.y，等价于负的滚动距离）。
    // 横屏：大标题栏（LargeFlexibleTopAppBar）在 360dp 高的横屏上占掉近一半高度，
    // 所以横屏换 64dp 的紧凑栏 + 小一号标题。
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var navVisible by remember { mutableStateOf(true) }
    // 二级页。null = 停在一级页（底栏的某一页）。一级页只把「点进去是哪一页」递上来，
    // 渲染在这里按 [SubPage] 分派 —— 页面之间不互相引用，加一页只动这个枚举 + 下面那条 when。
    var subPage by rememberSaveable { mutableStateOf<SubPage?>(null) }
    // 可预测返回（Android 14+ 的预测式返回手势）：手势拖动时二级页跟着手指右移，
    // 松手前能看见底下的一级页 —— 见下面 PredictiveBackHandler。
    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = subPage != null) { progress ->
      try {
        backProgress.snapTo(0f)
        progress.collect { backProgress.snapTo(it.progress) }
        // 手势走完 = 用户确认返回
        subPage = null
      } finally {
        // ⚠ 必须 NonCancellable：手势被取消时这个协程已是取消态，直接调 suspend 会立刻抛
        //   CancellationException，清理被跳过 —— 画面就卡在拖到一半的位置（主人报过「卡死」）。
        withContext(NonCancellable) {
          // 中途撤销就把位移收回去；已完成时页面已经换了，这一步只是复位。
          backProgress.animateTo(0f)
        }
      }
    }
    // 一级页的返回顺序（2026-11-08 主人：「别的三个页面返回应先返回主页」）：
    // 二级页 → 它所属的一级页（上面那条）→ **主页** → 才退出应用。
    //
    // ⚠ 可预测手势（主人：「可预测返回手势呢」）：第一版把 Pager 连续往回滚（从设置拖到主页
    //   要划过统计、功能三页），主人当场判「会卡死，而且一次返回最多应只出现两个界面」。
    //   现在改成**两屏**：底下铺一张主页（只在拖动时组合），当前页按手势进度整块右移，
    //   露出来的就是主页 —— 松手落到主页，取消再滑回来。全程不动 Pager 的滚动，不会卡死。
    val rootBack = remember { Animatable(0f) }
    // 只在手势**开始/结束**翻一次：如果在组合里直接读 rootBack.value 来判"要不要铺主页"，
    // 每帧都会重组那一块 —— 而 HomeScreen 的参数是个新建的 lambda，跟着一起每帧重组，
    // 真机上就是"抽搐"（2026-11-08 主人报的）。
    var previewing by remember { mutableStateOf(false) }
    val nearHome by remember { derivedStateOf { rootBack.value > 0.45f } }
    val nearParent by remember { derivedStateOf { backProgress.value > 0.45f } }
    PredictiveBackHandler(enabled = subPage == null && pagerState.currentPage != TAB_HOME) { progress ->
      var finished = false
      try {
        previewing = true
        rootBack.snapTo(0f)
        progress.collect { rootBack.snapTo(it.progress) }
        finished = true
      } finally {
        // 同二级页那条：取消态下 suspend 会直接抛，清理必须放在 NonCancellable 里。
        withContext(NonCancellable) {
          if (finished) {
            // 此时当前页已被拖到屏幕外：先把 Pager 放到主页，再复位位移 —— 画面不跳。
            pagerState.scrollToPage(TAB_HOME)
            rootBack.snapTo(0f)
          } else {
            rootBack.animateTo(0f)
          }
          previewing = false
        }
      }
    }
    val density = LocalDensity.current
    val navScrollConnection = remember(scrollBehavior, density) {
        val slop = with(density) { NavScrollSlop.toPx() }
        val nearTop = with(density) { NavNearTop.toPx() }
        object : NestedScrollConnection {
            private var net = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = consumed.y
                if (dy != 0f) {
                    // 手指一换向就重新计数，避免上下抖动被累加成一次"下滑"
                    if (net != 0f && (dy > 0f) != (net > 0f)) net = 0f
                    net += dy
                    if (net >= slop) {
                        navVisible = true
                        net = 0f
                    } else if (net <= -slop) {
                        // 两道门都要过：离顶不足 24dp 不收（刚往下蹭一点就藏，更像坏了），
                        // **这一页还往下滚得动也不收**（KeyLens 那句「短页不藏」，见 [NavScrollGate]）。
                        if (-scrollBehavior.state.contentOffset >= nearTop && NavScrollGate.canScrollForward)
                            navVisible = false
                        net = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    // 切页 / 进出二级页时都**强制把底栏放出来**（KeyLens 的 resetScrollState 同样做这件事）：
    // 从滚动着的长页滑到新页、或从二级页返回，新画面通常停在顶部，底栏不该继续隐身。
    // ⚠ 2026-11-08 主人报过：「二级界面上滑然后返回上一页后，底栏不见了」——
    //   二级页里滚动照样会把它置成 false，而原来的 key 只有 currentPage（返回时没变），
    //   所以那条 false 一直留到下一次切页。这里把 subPage 也放进 key。
    LaunchedEffect(pagerState.currentPage, subPage) { navVisible = true }

    // 顶栏标题：一级页跟 Pager 走；二级页换成那一页的名字（左上角同时出现返回箭头）。
    val rootTitle = stringResource(
        when (pagerState.currentPage) {
            TAB_HOME -> R.string.tab_home
            TAB_FEATURES -> R.string.tab_features
            TAB_STATS -> R.string.tab_stats
            else -> R.string.tab_settings
        }
    )
    val pageTitle = subPage?.let { stringResource(it.titleRes) } ?: rootTitle

    // 首次打开就把默认配置落盘。放在外壳而不是各页面里：一开始打开的是「主页」，而模块侧有
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
    // 自动检查发现的更新：只弹一次提示。两个字符串进 rememberSaveable —— 转屏不丢。
    var updatePromptMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var updatePromptUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val checkUpdate: () -> Unit = {
        val version = BuildConfig.VERSION_NAME
        updateState = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
            updateState = result.toState(context, version)
        }
    }

    // 开启应用时自动查一次更新（每进程一次）：只关心"有新版"，失败 / 已是最新都不打扰。
    // 联网放 IO；判据与手动检查是同一个 `UpdateChecker`，结果同样写进 `updateState`，
    // 所以「关于」页那块常驻结果也一并更新。
    LaunchedEffect(Unit) {
        if (autoUpdateChecked) return@LaunchedEffect
        autoUpdateChecked = true
        val version = BuildConfig.VERSION_NAME
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
        if (result is UpdateChecker.Result.Newer) {
            val done = result.toState(context, version) as? UpdateState.Done ?: return@LaunchedEffect
            updateState = done
            updatePromptMessage = done.message
            updatePromptUrl = done.url
        }
    }

    // Snackbar 也从外壳的作用域发：showSnackbar 会挂满整个展示时长，
    // 用页面自己的 scope 一切页就被取消，提示当场消失（剪贴板那条同理）。
    val notify: (String) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }

    // 跳转失败的提示语。三条跳转（顶栏、「关于」页两块）共用这一句。
    val openLinkFailed = stringResource(R.string.open_link_failed)

    // 极淡的环境色：顶部 surfaceContainerLow → 页面 background。
    // 卡片本身是不透明色，这条只负责在卡与卡之间、顶栏后面形成一点纵深；
    // 不引入品牌色，避免和 primaryContainer 的功能卡抢。
    // ⚠ 滑动层（返回主页那段）也用同一把刷子：各页是透明的，滑走时若没有底色，
    //   会透出底下那张主页、两层叠在一起发花（主人：「返回时是透明的，有点抽搐」）。
    val pageBackground = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.background,
        )
    )
    Box(modifier = Modifier.fillMaxSize().background(pageBackground)) {
        Scaffold(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .nestedScroll(navScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                val barColors = TopAppBarDefaults.topAppBarColors(
                    // 与渐变顶端同色：顶栏不透明，滚动内容不会从标题后面透出来；
                    // 颜色与 Box 渐变的第一个色标一致，视觉上仍是一整块。
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
                val titleSlot: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 二级页不画品牌标记：那个位置让给返回箭头，标题更贴近内容一点。
                        if (subPage == null) {
                            BrandMark()
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            // 两套返回用同一套阈值：拖过半就换成"要回去的那一页"的标题。
                            val barTitle = when {
                                subPage != null && nearParent -> rootTitle
                                subPage == null && nearHome -> stringResource(R.string.tab_home)
                                else -> pageTitle
                            }
                            AnimatedContent(
                                targetState = barTitle,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "pageTitle",
                            ) { title ->
                                Text(
                                    text = title,
                                    style =
                                        if (landscape) MaterialTheme.typography.titleLarge
                                        else MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                val navIcon: @Composable () -> Unit = {
                    // 返回的**入口**也要统一（2026-11-08 主人：「还有返回不统一」）：
                    // 二级页有返回箭头，可是功能 / 统计 / 设置按返回也会回主页，却看不到箭头。
                    // 现在凡"退得回去"的页面都画箭头：二级页 → 它的一级页；其它一级页 → 主页。
                    val canGoBack = subPage != null || pagerState.currentPage != TAB_HOME
                    if (canGoBack) {
                        IconButton(
                            onClick = {
                                if (subPage != null) {
                                    subPage = null
                                } else {
                                    scope.launch { pagerState.animateScrollToPage(TAB_HOME) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                }
                val barActions: @Composable RowScope.() -> Unit = {
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
                }

                // ⚠ 横屏必须换成 64dp 的紧凑栏：`LargeFlexibleTopAppBar` 在 360dp 高的横屏上
                //   要吃掉近一半高度（2026-11-08 截到的现场），标题也换成 titleLarge。
                if (landscape) {
                    TopAppBar(
                        colors = barColors,
                        title = titleSlot,
                        navigationIcon = navIcon,
                        actions = barActions,
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        colors = barColors,
                        title = titleSlot,
                        navigationIcon = navIcon,
                        actions = barActions,
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { inner ->
            // ⚠ 这里只扣顶栏让出的空间，**不要**再减掉药丸的高度：
            // 内容要一直铺到屏幕底部、从药丸后面滚过去，那才是「悬浮」。
            // 让位交给各页列表自己的 contentPadding（见 FloatingNavSpace）。
            Box(modifier = Modifier.padding(inner)) {
                // 返回主页的两屏预览：拖动时底下铺一张主页，当前页整块右移把它露出来。
                // 只在手势进行中（rootBack > 0）才组合，平时一分钱不花。
                if (previewing) {
                    HomeScreen(onOpen = { subPage = it })
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 与二级页那条完全同一套：只位移、不淡出。
                            translationX = size.width * rootBack.value
                        }
                        .background(pageBackground)
                ) {
                    // 一级页**一直**在组合里：二级页滑出时，底下露出来的就是它，
                    // 可预测返回才有东西可看（不然只会露出背景色）。
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // 只能由底栏切页：页面横向滑动会和系统边缘返回手势抢事件
                        userScrollEnabled = false,
                    ) { page ->
                        when (page) {
                            // 四个一级页分工：主页「看」、功能「改」、统计「查」、设置「配」。
                            TAB_HOME -> HomeScreen(onOpen = { subPage = it })
                            TAB_FEATURES -> FeaturesScreen(onNotify = notify)
                            TAB_STATS -> StatsScreen(onNotify = notify)
                            else -> ModuleScreen(onOpen = { subPage = it })
                        }
                    }
                }

                val sub = subPage
                if (sub != null) {
                    // 二级页没有底栏 → 列表不该再为药丸留 96dp（见 Common.kt 的 LocalNavSpace）。
                    // ⚠ 只包这一层：一级页虽然还组合在下面，返回时它的留白与滚动位置都不跳。
                    CompositionLocalProvider(LocalNavSpace provides 0.dp) {
                        // 二级页盖在一级页上：拖动时按手势进度右移，底下一级页露出来。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // 只做位移、**不淡出**：半透明会透出底下那页、叠影发花
                                    //（主人一级页那条报过「返回时是透明的」，二级页这条原来
                                    //  也一直是半透明）。两条返回现在都是不透明的整块滑动。
                                    translationX = size.width * backProgress.value
                                }
                                // 与一级页那条用同一道渐变：两条返回动画除了"底下是谁"以外完全同构。
                                .background(pageBackground)
                        ) {
                            when (sub) {
                                SubPage.CROP -> CropScreen()
                                SubPage.MONO -> MonoScreen()
                                SubPage.NOTIFY -> NotifyScreen(onNotify = notify)
                                SubPage.SCOPE -> ScopeScreen(onNotify = notify)
                                SubPage.SETUP -> SetupScreen(onNotify = notify)
                                SubPage.TOOLS -> ToolsScreen(onNotify = notify)
                                SubPage.ABOUT -> AboutScreen(
                                    state = updateState,
                                    onCheck = checkUpdate,
                                    onNotify = notify,
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingNavBar(
            selectedIndex = pagerState.currentPage,
            entries = NavEntries,
            // 二级页藏起底栏：这一页不属于任何一格，留着反而让人以为自己在某一格里。
            // 回一级页靠顶栏的返回箭头或系统返回键。
            visible = navVisible && subPage == null,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            // ⚠ `navigationBarsPadding()` 不能省：本 Activity 是 edge-to-edge
            // （`enableEdgeToEdge()` + 系统栏透明），外层 Box 一直铺到屏幕底边，
            // 而药丸是这个 Box 的**兄弟节点**、不经过 Scaffold 的 innerPadding。
            // 少了它，三键导航（导航栏约 48dp）会把药丸下面那截连同每格的点击区一起盖住 ——
            // 系统导航栏吃掉那些触摸，底栏就是"看得见、点不着"；手势导航下那 20dp 也会
            // 落进手势条里。药丸要悬浮在**内容**之上，但不该钻进系统导航栏下面。
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = NavBottomInset),
        )

        // ⚠ Snackbar 必须放在悬浮底栏**之上**：这里既靠后绘制（z 序压过药丸），
        // 又把位置抬到药丸上方（药丸顶边 24 + 64 = 88dp、阴影到 ~98dp，抬到 104dp 留间隙）。
        // 否则默认贴底的 Snackbar 会被药丸挡住 / 截断（清缓存提示就中过招）。
        // ⚠ 导航栏那一段同理要先让开：这个数是**相对药丸**定的，药丸抬多高它就得抬多高，
        // 否则两者的相对位置会随导航方式（三键 / 手势）变来变去。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 104.dp),
        )

        // 自动检查到新版才弹：正文就是「关于」页那块结果 chip 的同一句话（发现新版本 X（当前 Y））。
        val promptMessage = updatePromptMessage
        val promptUrl = updatePromptUrl
        if (promptMessage != null && promptUrl != null) {
            AlertDialog(
                onDismissRequest = {
                    updatePromptMessage = null
                    updatePromptUrl = null
                },
                title = { Text(stringResource(R.string.update_prompt_title)) },
                text = { Text(promptMessage) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            updatePromptMessage = null
                            updatePromptUrl = null
                            if (!openExternalUrl(uriHandler, promptUrl)) notify(openLinkFailed)
                        }
                    ) {
                        Text(stringResource(R.string.update_go_download))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            updatePromptMessage = null
                            updatePromptUrl = null
                        }
                    ) {
                        Text(stringResource(R.string.update_later))
                    }
                },
            )
        }
    }
}

/**
 * 顶栏左侧的品牌标记：与桌面主题图标、以及「关于」页的标记同一个组件（[OpticonMark]）。
 *
 * 加它的理由：整页是卡片叠卡片，顶栏只有一行字，视觉上没有任何"这是 Opticon"的锚点。
 * 2026-11-08 主人「oi logo 稍微画大点」：34dp → **40dp**，字形占圆的比例不变（仍是 1.5×）。
 */
@Composable
private fun BrandMark() {
  OpticonMark(size = 40.dp)
}

/**
 * 悬浮药丸导航：四格（主页 / 功能 / 统计 / 设置），选中那格展开文字。
 *
 * 几何与交互照搬 KeyLens 的 webui 底栏：
 * - 每格 52dp，图标永远在 44dp 圆形光晕的圆心；选中那格向左端扩出光晕圆、文字从右侧淡入；
 * - 长页向下滑时整颗药丸下沉 + 缩到 0.94 + 淡出，向上滑或回顶再回来；
 * - 按住活动圆横移超过 6dp 就"拿起来"，拖到另一格松手切页（拿起时轻震动一次）。
 *
 * ## 底色为什么不是 `surface`
 * 这条原来写的是"用最亮的 `surface`（纯白）拉开明度差"，但两套配色下它都不成立：
 * 写死的浅色方案里 `surface` 与 `background` **是同一个值**（`0xFFFFF8F8`），
 * 动态取色下两者也几乎同色；深色下 `surface` 反而比背景更暗。也就是说药丸与背景的区分
 * 一直只靠 `shadowElevation` —— 而深色里阴影基本看不见（真机截图复核）。
 *
 * 现在用 `surfaceContainer`（KeyLens 的 navigation-container 同一档）+ 38% `outlineVariant`
 * 描边：前者在两套主题下都与背景有一档明确差值，后者把边界钉死，不依赖阴影。
 * **与卡片同一套做法**（见 [OpticonCard]）。
 *
 * ⚠ 改底色时记住这条：**药丸必须在浅色和深色下都和背景有明显差值**，
 * 而"靠阴影"在深色下不算数。
 */
// invisibleToUser() 在本仓库的 Compose 版本上仍是唯一可用的等价物（新版叫 hideFromAccessibility）。
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingNavBar(
    selectedIndex: Int,
    entries: List<NavEntry>,
    visible: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val hideFraction by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "navHide",
    )

    // 收起时要把药丸整个送出屏幕：自己的高度 + 底部边距 + 系统导航栏那一段
    val navInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val bottomGapPx = with(density) { NavBottomInset.toPx() }

    // 拖动切页：由活动格拿起，横向跨过其它格；松手才真正切页
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(selectedIndex) }
    var navWidthPx by remember { mutableIntStateOf(0) }
    // 每格实测宽度（onGloballyPositioned 写入），用于把手指 x 换算成最近的一格
    val itemWidths = remember { IntArray(entries.size) }
    val gapPx = with(density) { NavGap.toPx() }

    fun nearestIndex(x: Float): Int {
        var edge = 0f
        var best = 0
        var bestDist = Float.MAX_VALUE
        entries.forEachIndexed { i, _ ->
            val w = itemWidths[i].toFloat()
            val center = edge + w / 2f
            val d = abs(x - center)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
            edge += w + gapPx
        }
        return entries[best].index
    }

    // 位移前的药丸高度：`offset {}` 是**布局期**的偏移，拿不到 `size`，所以自己量一次。
    var navHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            // 🔴 **过渡里不许给这一层加 `alpha`**（2026-11-10 主人：「返回后底栏的阴影会一瞬间变方」）。
            //   把系统动画放慢 10 倍抓帧，现场是：过渡中底栏**下半截被一条直线切掉、阴影成一个方块**，
            //   稳态（alpha=1）完全正常。原因：`alpha < 1` 会把整层渲染进一张**和底栏等大**的离屏位图，
            //   药丸自己的阴影就画在那张位图之外 —— 被裁成直边。实测对照：只删掉 `alpha` 这一行，
            //   同一时刻的阴影立刻恢复成跟着药丸圆角走。
            //   ⇒ 收起/出现**只走位移 + 缩放，不做淡出**（药丸本来就整个移出屏幕，淡出看不出区别）。
            //   位移也留在布局期（`offset {}`），这样即使以后又有人给这层加 alpha，阴影也不会被推出去。
            .offset { IntOffset(0, (hideFraction.coerceIn(0f, 1f) * (navHeightPx + bottomGapPx + navInsetPx)).roundToInt()) }
            .onSizeChanged { navHeightPx = it.height }
            .graphicsLayer {
                val f = hideFraction.coerceIn(0f, 1f)
                val s = 1f - 0.06f * f
                scaleX = s
                scaleY = s
            }
            .onGloballyPositioned { navWidthPx = it.size.width }
            // ⚠ 收起 ≠ 只是画到屏幕外：KeyLens 在 `nav-hidden` 上同时写 `aria-hidden` 与
            //   `pointer-events:none`。少了这一条，TalkBack 仍能把焦点停在一个看不见的按钮上，
            //   而过渡途中（半透明、正在下滑）也还能被点着。
            .semantics { if (hideFraction > 0.5f) invisibleToUser() },
    ) {
        Surface(
            // 固定 32dp（药丸高度 52+6×2=64dp 的一半），不用 `percent = 50`：
            //   **纯防御**，不依赖当帧测到的尺寸（`animateContentSize` / 首帧尺寸未定时百分比半径会塌）。
            //   ⚠ 它**不是**「阴影一下变方」的病因 —— 那条的真凶是下面 `alpha` 那一层，别记错。
            shape = RoundedCornerShape((NavItemHeight + NavPadding * 2) / 2),
            color = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.38f)),
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(NavPadding)
                    // 坐标相对 Row 的内容区（padding 之内），和鼠标/手指的 x 同一坐标系
                    .pointerInput(selectedIndex, visible) {
                        if (!visible) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            // 🔴 **整颗药丸都是拖拽区**（2026-11-10 主人：「底栏拖拽应不必特意点到选中光圈
                            //   才能滑，应自动吸附到手指触摸处，在底栏中不能超出底栏」）。
                            //   旧写法要求"按在活动格上"才拿起（照抄 KeyLens 的 `.active`）—— 手指落在
                            //   空格上会**直接不响应**，得先瞄准光圈才能拖。
                            //   现在：按下即拿起，光圈立刻吸附到手指的 x（绘制时夹在药丸内，见下面 ghost）。
                            //   ⚠ 按下就 consume，子项的点击不再报第二次（选页由松手那一下统一做）。
                            down.consume()
                            dragging = true
                            dragX = down.position.x
                            dragTarget = nearestIndex(down.position.x)
                            val startX = down.position.x
                            val startY = down.position.y
                            // 轻震动仍按"真的横向拖起来"给：单纯点一下不该震。
                            var started = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (!started &&
                                    abs(dx) > NavDragSlop.toPx() &&
                                    abs(dx) > abs(dy)
                                ) {
                                    started = true
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                }
                                // ⚠ 必须**先**判定抬起再 consume：`changedToUp()` 把"已消费"算作
                                //   不是抬起（要忽略消费得用 IgnoreConsumed 版），先消费就永远
                                //   走不到松手切页 —— 2026-11-05 真机上手势能拿起、松手没反应的根因。
                                val isUp = change.changedToUp()
                                dragX = change.position.x
                                dragTarget = nearestIndex(change.position.x)
                                // 吃掉后续事件，让下面的可点击 Surface 不再触发点击
                                change.consume()
                                if (isUp) {
                                    val target = nearestIndex(change.position.x)
                                    if (target != selectedIndex) onSelect(target)
                                    break
                                }
                                if (!change.pressed) break
                            }
                            dragging = false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(NavGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEachIndexed { i, entry ->
                    NavItem(
                        index = entry.index,
                        icon = entry.icon,
                        labelRes = entry.labelRes,
                        selectedIndex = selectedIndex,
                        dragging = dragging,
                        dragTarget = dragTarget,
                        // 收起过半就不许点（KeyLens 的 `pointer-events:none`）
                        enabled = hideFraction <= 0.5f,
                        onSelect = onSelect,
                        onWidth = { itemWidths[i] = it },
                    )
                }
            }
        }

        // 被拿起的活动圆：画在药丸之上，横向跟随手指并在两端收住
        if (dragging) {
            val haloPx = with(density) { NavHaloSize.toPx() }
            val padPx = with(density) { NavPadding.toPx() }
            val ghostTop = with(density) {
                (NavPadding + (NavItemHeight - NavHaloSize) / 2f).toPx()
            }
            val ghostLeft = (padPx + dragX - haloPx / 2f)
                .coerceIn(padPx, (navWidthPx - padPx - haloPx).coerceAtLeast(padPx))
            Surface(
                modifier = Modifier
                    .offset { IntOffset(ghostLeft.roundToInt(), ghostTop.roundToInt()) }
                    .size(NavHaloSize),
                shape = CircleShape,
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shadowElevation = 10.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 光晕里画的是**将要选中的那一格**的图标（不是当前格）：拖到哪就预览哪一页。
                    Icon(
                        imageVector = entries.first { it.index == dragTarget }.icon,
                        contentDescription = null,
                        modifier = Modifier.size(NavIconSize),
                    )
                }
            }
        }
    }
}

/**
 * 药丸里的一格：未选中只有圆形光晕里的图标，选中时右侧展开文字。
 *
 * ## 动效
 * 四件事一起动，缺一个都会显得"卡"：
 * 1. 胶囊底色 / 光晕 / 内容色 —— `animateColorAsState`，用主题的 `MotionScheme`
 * 2. 整格的宽度 —— `animateContentSize()`（文字挤进来时把胶囊撑开）
 * 3. 文字本身 —— `AnimatedVisibility` 横向展开 + 淡入
 * 4. 按下 —— 缩到 0.96（KeyLens 的 `:active`）
 *
 * ⚠ 时长与曲线一律取自 `MaterialTheme.motionScheme`，**不要自己写 tween(300)**：
 * Expressive 的弹簧参数是主题给的，硬编码会在换主题/换设备时和系统动效对不上。
 * 颜色与透明度用 `defaultEffectsSpec`，位置与尺寸用 `defaultSpatialSpec`。
 *
 * ⚠ 拖动切页时，源格的文字只**淡出**、不从布局里拿掉：拿掉会改变整颗药丸的宽度，
 *   手指底下的格子会跟着移位（KeyLens 用 `opacity:0!important`，不移宽度）。
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
    dragging: Boolean,
    dragTarget: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onWidth: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val isSelected = selectedIndex == index
    val isSource = dragging && isSelected
    val isTarget = dragging && dragTarget == index

    val containerColor by animateColorAsState(
        targetValue = when {
            isSource -> scheme.secondaryContainer.copy(alpha = 0.42f)
            isTarget -> scheme.primary.copy(alpha = 0.12f)
            isSelected -> scheme.secondaryContainer
            else -> Color.Transparent
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "navItemContainer",
    )
    val haloColor by animateColorAsState(
        targetValue = when {
            isTarget -> scheme.primary.copy(alpha = 0.22f)
            isSelected -> scheme.surface.copy(alpha = 0.22f)
            else -> Color.Transparent
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "navHalo",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isTarget -> scheme.primary
            isSelected -> scheme.onSecondaryContainer
            else -> scheme.onSurfaceVariant
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "navItemContent",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (isSource) 0f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "navLabelAlpha",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (isSource) 0.25f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "navHaloAlpha",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "navPressScale",
    )

    Surface(
        // 「当前在哪一页」只靠高亮是给不了读屏用户的：不加 `selected`，四格在 TalkBack 里
        // 读起来一模一样（README 那句「当前在哪一页由底栏高亮表示」对视觉成立，对无障碍不成立）。
        modifier = Modifier
            .semantics { selected = isSelected }
            .onGloballyPositioned { onWidth(it.size.width) }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        onClick = { onSelect(index) },
        enabled = enabled,
        // 同上：固定 26dp（= 52dp 的一半），不跟测量尺寸走 —— 选中那格会用 `animateContentSize`
        // 横向撑开，百分比圆角在动画的头几帧同样是"先塌成方角"。
        shape = RoundedCornerShape(NavItemHeight / 2),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier
                .height(NavItemHeight)
                .animateContentSize(animationSpec = motion.defaultSpatialSpec())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(NavHaloSize)
                    // 被拿起的那一格：光晕连同里面的图标一起降到 25%（KeyLens
                    // `.nav-dragging .nav-item.active .nav-halo{opacity:.25}` 就是这个意思——
                    // 圆已经"离开"了这一格，浮到手指上）。
                    .alpha(haloAlpha)
                    .clip(CircleShape)
                    .background(haloColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    // 选中时文字就在旁边，图标再报一次名字是重复朗读
                    contentDescription = if (isSelected) null else stringResource(labelRes),
                    modifier = Modifier.size(NavIconSize),
                )
            }
            AnimatedVisibility(
                visible = isSelected,
                enter = expandHorizontally(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.defaultEffectsSpec()),
                exit = shrinkHorizontally(animationSpec = motion.defaultSpatialSpec()) +
                    fadeOut(animationSpec = motion.defaultEffectsSpec()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(labelAlpha),
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    // 活动格右端 16dp：4dp 内边距 + 这 12dp，随文字一起展开
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
        }
    }
}
