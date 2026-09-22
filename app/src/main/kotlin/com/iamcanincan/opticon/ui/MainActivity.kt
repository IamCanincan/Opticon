package com.iamcanincan.opticon.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iamcanincan.opticon.ui.theme.OpticonTheme

/**
 * 模块的设置界面。这个 Activity 同时承担两件事：
 * 1. 给用户看两个功能的开关、作用域、启用步骤与验证方式；
 * 2. 让模块有 MAIN + LAUNCHER 入口 —— 否则它是纯后台模块，桌面上根本不会出现图标。
 *
 * 它只负责读写自己的 SharedPreferences；真正的挂钩工作发生在被注入的进程里，
 * 由 [com.iamcanincan.opticon.entry.OpticonModule] 通过本应用暴露的只读
 * ContentProvider 读到这里的设置。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpticonTheme { OpticonApp() } }
    }
}
