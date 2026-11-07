package com.iamcanincan.opticon.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.iamcanincan.opticon.circle.ACTION_CACHE_DONE

/**
 * 桌面侧把图标缓存**删完、准备重启自己**时发来的一响。
 *
 * 为什么要它：「清除缓存并重启桌面」以前只管发广播，没人证明它到了 —— 2026-11-07 真机抓到过
 * 广播被系统冻结的桌面整个吞掉（另外三个进程都收到并打了 \`clear request ignored\`），
 * 用户点了等于没点，而界面上没有任何一句话说明。之后有人报"我清了缓存还是没用"，
 * 我们连"他按的那一下到底有没有到桌面"都答不上来。
 *
 * 现在由桌面在**真正删完之后**回报一声，应用侧弹一句"桌面已重建图标"：
 * 用户看得到，日志里也留一行 \`CacheAck:\`，以后再遇到同类报告，先看这一行有没有。
 *
 * ⚠ **免鉴权是刻意的**：发送方（桌面进程）不持有本应用的签名权限，只收得进导出广播。
 *   代价是任何应用都能伪造这一响 —— 它只会让界面弹一句话，不做任何动作、不改任何配置。
 */
class CacheAckReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ACTION_CACHE_DONE) return
    Log.i("Opticon", "CacheAck: 桌面回报已重建图标")
    runCatching {
      Toast.makeText(context.applicationContext, "桌面已重建图标", Toast.LENGTH_SHORT).show()
    }
  }
}
