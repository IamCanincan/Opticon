package com.iamcanincan.opticon.circle

import android.graphics.Color

/**
 * 纯色盘（`ColorDrawable`）的两件纯计算：**前景有没有用到盘色**，以及**把盘铺到前景底下**。
 *
 * 为什么要有这一份（2026-10-23 Pinduoduo「白心没了」）：那枚图标的前景层是
 * 「白心 + 小物品」，而小物品的颜色与背景层那块 `#F40009` 同族。把盘色当作独立的 `under`
 * 交给模型时，模型看不见"盘色也画在前景里"这件事 —— 它把同色的小物品当成墨、把白心当成
 * 空气，屏幕上就是反的（设备 dump：掩码只覆盖 2.31%，全是小物品）。
 * 判据台拿**设备原样输入**量过：这张前景先铺到盘色上再交模型，得到的就是白心上墨、
 * 小物品镂空（`_harness/MaskBenchKt --over`）。
 *
 * ⚠ 只在纯色盘上用：真背景层（`under` 是画出来的位图）那条路本来就能把层分开，合成会
 *   把饿了么 / 淘宝那类整张涂满（2026-10-23 量过：掩码 11811→68657 / 15206→68644）。
 */

/** 前景里同族格占不透明格的下限。 */
private const val MIN_PCT = 5

/** 同族格的绝对下限，与 `DustKt` 的「一块至少 300 格才配叫一个部分」同量级。 */
private const val MIN_N = 300

/**
 * 前景里有多少**不透明格**用的是盘色；不够门槛就返回 0（= 不是这一族）。
 *
 * 门槛在 `rec_model` 的 10 张纯色底上摊开过：这一族是 12.8%（拼多多）/ 30.4%（混元）/
 * 63.0%（贴吧）/ 50.2%（kazumi）；其余 6 张（azure / xbox / uu远程 / openlist / termux /
 * spotube）**全是 0.0%** —— 空档很宽，不是贴着噪声画的线。
 */
internal fun bgMarks(px: IntArray, bg: Int): Int {
  val plate = MonochromeMask.plateKey(bg)
  var solid = 0
  var same = 0
  for (p in px) {
    if (Color.alpha(p) < 128) continue
    solid++
    if (MonochromeMask.plateKey(p) == plate) same++
  }
  if (same < MIN_N) return 0
  return if (same * 100 >= solid * MIN_PCT) same else 0
}

/** 把前景铺到纯色盘上 —— 屏幕上看到的那一张（不透明，颜色按 α 混合）。 */
internal fun overBg(px: IntArray, bg: Int): IntArray {
  val br = (bg shr 16) and 0xff
  val bgG = (bg shr 8) and 0xff
  val bb = bg and 0xff
  val opaqueBg = (0xff shl 24) or (bg and 0xffffff)
  val out = IntArray(px.size)
  for (i in px.indices) {
    val p = px[i]
    val a = Color.alpha(p)
    if (a == 255) {
      out[i] = p
    } else if (a == 0) {
      out[i] = opaqueBg
    } else {
      val r = (Color.red(p) * a + br * (255 - a)) / 255
      val g = (Color.green(p) * a + bgG * (255 - a)) / 255
      val b = (Color.blue(p) * a + bb * (255 - a)) / 255
      out[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
  return out
}
