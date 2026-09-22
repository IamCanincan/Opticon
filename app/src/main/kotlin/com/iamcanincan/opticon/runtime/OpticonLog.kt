package com.iamcanincan.opticon.runtime

import android.util.Log

/**
 * 全模块统一的日志 tag。
 *
 * 两个功能（裁圆图标 / 通知图标）刻意共用同一个 tag：排查时
 * `adb logcat -s Opticon` 一次就能看到所有进程里本模块干了什么，
 * 不用先猜「这次该看哪个 tag」。具体是哪一路，日志正文里都有前缀
 * （`install in …` / `PixelLauncher: …` / `patched …` 等）。
 *
 * ⚠ release 构建也保留这些日志（见 `app/lint.xml` 对 `LogConditional` 的说明）：
 * 被注入的进程里没有界面，logcat 是确认「模块到底有没有挂上」的唯一手段。
 */
const val TAG = "Opticon"

fun logD(message: String): Int = Log.d(TAG, message)

fun logI(message: String): Int = Log.i(TAG, message)

fun logW(message: String): Int = Log.w(TAG, message)

fun logE(message: String, throwable: Throwable? = null): Int =
    if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
