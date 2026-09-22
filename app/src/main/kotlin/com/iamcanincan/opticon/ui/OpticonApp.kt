package com.iamcanincan.opticon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Circle
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.iamcanincan.opticon.R
import com.iamcanincan.opticon.ui.icon.IconScreen
import com.iamcanincan.opticon.ui.notify.NotifyScreen

private const val REPO_URL = "https://github.com/IamCanincan/Opticon"

/**
 * 应用外壳：顶栏 + 底部导航 + 两个页面。
 *
 * 两个功能各自成页，共用这一个 Scaffold —— 顶栏的折叠行为、snackbar 宿主、
 * 边到边留白都只有一份，页面本身只管出内容。
 *
 * 顶栏随列表滚动收起、往回滑立刻回来：内容很长（图标页 9 个分区），
 * 滚到中段时有个常驻入口（标题 + 仓库）比「一路滑回顶」省事。
 *
 * ⚠ 切换页面时**不重建页面**：两个 Screen 各自 `remember` 自己的开关状态，
 * 切走再切回会重新从 SharedPreferences 读一遍 —— 那正是我们要的
 * （用户可能在别处改过设置），代价只是重新读一次文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpticonApp() {
    var tab by rememberSaveable { mutableIntStateOf(TAB_ICON) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

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
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        // 顶栏与底部导航让出的空间在这里一次性扣掉，页面内部只关心自己的边距。
        Box(modifier = Modifier.padding(inner)) {
            when (tab) {
                TAB_ICON -> IconScreen(snackbarHostState = snackbarHostState)
                else -> NotifyScreen()
            }
        }
    }
}

private const val TAB_ICON = 0
private const val TAB_NOTIFY = 1
