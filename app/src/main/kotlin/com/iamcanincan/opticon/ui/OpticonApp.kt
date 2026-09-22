package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.runtime.ModulePrefs
import com.iamcanincan.opticon.ui.icon.IconScreen
import com.iamcanincan.opticon.ui.module.ModuleScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

private const val TAB_ICON = 0
private const val TAB_NOTIFY = 1
private const val TAB_MODULE = 2

/**
 * 应用外壳：顶栏 + 底部导航 + 三个页面。
 *
 * 分页按**内容归属**来，不按「东西多不多」：
 * - 「图标」「通知」是**功能页** —— 各自的开关和只跟它自己有关的说明；
 * - 「模块」是**共用页** —— 作用域清单（两个功能都要勾）、启用步骤、验证方式、
 *   运维按钮、检查更新、关于。
 *
 * 这样分的好处是每个页面的标题都名副其实：以前「图标」页里躺着作用域、启用步骤、
 * 检查更新和关于，既跟"图标"无关，又让那一页长到要滚半天。
 *
 * 顶栏随列表滚动收起、往回滑立刻回来：内容较长，滚到中段时有个常驻入口
 * （标题 + 仓库）比「一路滑回顶」省事。
 *
 * ⚠ 切换页面时**不重建页面**：各页自己 `remember` 状态，切走再切回会重新读一遍
 * 配置 —— 那正是我们要的（用户可能刚在别处改过），代价只是重新读一次文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpticonApp() {
    var tab by rememberSaveable { mutableIntStateOf(TAB_ICON) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // 首次打开就把默认配置落盘（含 mode 键）。放在外壳而不是各页面里：
    // 默认打开的是「图标」页，而模块侧有几条回退通道是**以「配置里有 mode 键」
    // 作为「这份文件是不是有效配置」的判据**的 —— 只打开过图标页就改开关的话，
    // 文件里没有 mode，那几条通道会整份配置都不认，用户改的开关读不回来。
    val context = LocalContext.current
    remember { ModulePrefs.of(context).also { ModulePrefs.seedIfAbsent(it) } }

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
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_ICON,
                    onClick = { tab = TAB_ICON },
                    icon = { Icon(Icons.Default.Circle, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_icon)) },
                )
                NavigationBarItem(
                    selected = tab == TAB_NOTIFY,
                    onClick = { tab = TAB_NOTIFY },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_notify)) },
                )
                NavigationBarItem(
                    selected = tab == TAB_MODULE,
                    onClick = { tab = TAB_MODULE },
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_module)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        // 顶栏与底部导航让出的空间在这里一次性扣掉，页面内部只关心自己的边距。
        Box(modifier = Modifier.padding(inner)) {
            when (tab) {
                TAB_ICON -> IconScreen()
                TAB_NOTIFY -> NotifyScreen()
                else -> ModuleScreen(snackbarHostState = snackbarHostState)
            }
        }
    }
}
