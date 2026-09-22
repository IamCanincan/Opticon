package com.iamcanincan.opticon.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.about.AboutScreen
import com.iamcanincan.opticon.ui.icon.IconScreen
import com.iamcanincan.opticon.ui.module.ModuleScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen
import kotlinx.coroutines.launch

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

private const val TAB_ICON = 0
private const val TAB_NOTIFY = 1
private const val TAB_MODULE = 2
private const val TAB_ABOUT = 3

/** 底栏格数 = Pager 的页数 */
private const val PAGE_COUNT = 4

/**
 * 应用外壳：顶栏 + **悬浮药丸式**底部导航 + 三个页面。
 *
 * 分页按**内容归属**来，不按「东西多不多」：
 * - 「图标」「通知」是**功能页** —— 各自的开关和只跟它自己有关的说明；
 * - 「模块」是**共用页** —— 作用域清单（两个功能都要勾）、启用步骤、验证方式、运维按钮；
 * - 「关于」是**信息页** —— 检查更新与版本信息（"看看就好"的那类内容）。
 *
 * 底栏刻意做成**悬浮药丸**而不是通栏的 `NavigationBar`：页面内容从药丸后面滚过，
 * 视觉上更轻、也更贴合 M3 Expressive 的形态语言。选中项会展开文字
 * （未选中的只留图标），所以药丸宽度随当前页变化 —— 这正是它像"药丸"的原因。
 *
 * ⚠ 底栏是**四格**：三格时药丸太短，撑不起"悬浮药丸"该有的形态（试过，明显偏窄）。
 *
 * ⚠ 切换页面时**不重建页面**：各页自己 `remember` 状态，切走再切回会重新读一遍
 * 配置 —— 那正是我们要的（用户可能刚在别处改过），代价只是重新读一次文件。
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

    // 首次打开就把默认配置落盘（含 mode 键）。放在外壳而不是各页面里：
    // 默认打开的是「图标」页，而模块侧有几条回退通道是**以「配置里有 mode 键」
    // 作为「这份文件是不是有效配置」的判据**的 —— 只打开过图标页就改开关的话，
    // 文件里没有 mode，那几条通道会整份配置都不认，用户改的开关读不回来。
    val context = LocalContext.current
    remember { ModulePrefs.of(context).also { ModulePrefs.seedIfAbsent(it) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { uriHandler.openUri(REPO_URL) }) {
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
                        TAB_ICON -> IconScreen(snackbarHostState = snackbarHostState)
                        TAB_NOTIFY -> NotifyScreen()
                        TAB_MODULE -> ModuleScreen(snackbarHostState = snackbarHostState)
                        else -> AboutScreen()
                    }
                }
            }
        }

        FloatingNavBar(
            selected = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
        )

        // ⚠ Snackbar 必须放在悬浮底栏**之上**：这里既靠后绘制（z 序压过药丸），
        // 又把位置抬到药丸上方（药丸顶边约 74dp、阴影到 ~84dp，抬到 92dp 留间隙）。
        // 否则默认贴底的 Snackbar 会被药丸挡住 / 截断（清缓存提示就中过招）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp),
        )
    }
}

/**
 * 悬浮药丸导航：三格，选中那格展开文字。
 *
 * ## 底色为什么用 `surface` 而不是某个 container 色
 * 页面背景本身就是 `background`（很浅的粉白），而 `surfaceContainerHigh` 在同色系里
 * 只比它深一点点 —— 药丸和背景糊在一起，看着就像"底部垫了一块背景"而不是一个控件。
 * 用最亮的 `surface`（纯白）拉开明度差，再靠阴影把它托起来，才是「悬浮」的观感。
 *
 * ⚠ 改底色时记住这条：**药丸必须在浅色和深色下都和背景有明显明度差**。
 * 深色主题下 `surface` 同样比 `background` 亮，两边都成立。
 */
@Composable
private fun FloatingNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(TAB_ICON, Icons.Default.Circle, R.string.tab_icon, selected, onSelect)
            NavItem(TAB_NOTIFY, Icons.Default.Notifications, R.string.tab_notify, selected, onSelect)
            NavItem(TAB_MODULE, Icons.Default.Extension, R.string.tab_module, selected, onSelect)
            NavItem(TAB_ABOUT, Icons.Default.Info, R.string.tab_about, selected, onSelect)
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
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavItem(
    index: Int,
    icon: ImageVector,
    labelRes: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val isSelected = selected == index

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
        onClick = { onSelect(index) },
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .animateContentSize(animationSpec = motion.defaultSpatialSpec())
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
