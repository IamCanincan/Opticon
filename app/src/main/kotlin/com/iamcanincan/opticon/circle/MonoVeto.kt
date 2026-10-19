package com.iamcanincan.opticon.circle

/**
 * 「这块自画单色层交出去是不是一块饼」里**能被判据台量到的那一半**。
 *
 * 剥成纯函数的理由与 `MonoKey` / `IconCropWindow` 是同一条：不碰 `Bitmap` / `Drawable` 的算式
 * 才编得进 `_harness/`，判据才有尺 —— 否则这条闸只有装机那一条路可验，而设备随时可能不在。
 * 它的尺是 `_harness/VetoKt.kt`：拿真机抓回来的 63 张应用自画单色层直接跑这一条，
 * 看开火的集合是不是**只有**该开火的那两张。
 */
internal object MonoVeto {

  /** 探针边长。⚠ 重采样会把小于约 4px 的洞抹平 ⇒ 只会**多**开火不会漏；而"只有 4px 透明"的一层
   *  交出去本来就是饼，所以这个方向的误差是良性的。 */
  const val EDGE = 128

  /** 可见圆半径 / 画布边长：桌面只画中央 66dp，而自适应画布是 108dp（66/108 = 0.3056·2 的半径分数）。 */
  const val DISC = 0.3056f

  /**
   * 可见圆里**一格 α=0 都没有** ⇒ true。
   *
   * 为什么这一条是算术不是判据拟合：主题图标 = 底色盘 + 这一层的 alpha。圆内没有一个透明格，
   * 屏幕上就不可能有任何"背景"，交出去必是一块实心饼。
   */
  fun wholePlate(px: IntArray, edge: Int = EDGE): Boolean {
    val c = (edge - 1) / 2f
    val r = edge * DISC
    val r2 = r * r
    var n = 0
    for (y in 0 until edge) {
      val dy = y - c
      if (dy * dy > r2) continue
      val row = y * edge
      for (x in 0 until edge) {
        val dx = x - c
        if (dx * dx + dy * dy > r2) continue
        n++
        if (((px[row + x] ushr 24) and 0xff) == 0) return false
      }
    }
    return n > 0
  }
}
