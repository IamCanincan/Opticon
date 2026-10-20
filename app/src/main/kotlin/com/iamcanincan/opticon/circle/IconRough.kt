package com.iamcanincan.opticon.circle

import kotlin.math.sqrt

/**
 * 「边有没有被啃毛」的两把尺 —— 剥成纯函数的理由与本仓 `MonoVeto` / `IconLabel` 同一条：
 * 不碰 `Bitmap` / `Drawable` 的算式才编得进 `_harness/`，判据才有尺、能换着量。
 *
 * ## 为什么要有第二把（[iso]）
 * [jag] = `周长 / √面积` **只在"形状不变、只换尺度"时不变**。整块删掉一块面积时，
 * 面积掉了、剩下的轮廓没同比掉，这个数**自己抬起来** —— 于是它把"少了一大块"和"边变毛了"
 * 读成同一个数：给【主形】补一条"留下的实心碎件一并退掉"，全语料 0 张开火，就是被它顶回去的。
 * 更糟的是它对**该拦的那两类反而漏了**：uu远程的箭头擦剩半个、azure 的小人只剩一个头，
 * 都是"整块平滑消失"，抬升很小就混过去了。
 *
 * [iso] = `周长² / 面积` 是**等周比**（无量纲，圆盘恒为 `4π≈12.57`，与尺度无关）：
 *  · 删掉一块**大的凸的**部分 → 留下一条长直切口，周长不同比缩 → 这个数**抬**（正是该拦的"少了一大块"）；
 *  · 删掉一块**小的孤立**实心件 → 它本来就贡献了不成比例的周长 → 这个数**降**（正是该放的"清掉碎渣"）。
 * 也就是说它把 [jag] 混在一起的两件事**分开了**，方向还正好相反。
 *
 * ⚠ 两把尺量的都是"**交付那张墨的边界形状**"，不是"这块是不是底"（那堵墙已量死六次）。
 * 尺的读数与阈值都在 `_harness/BurrKt.kt` 上对全语料摊过才许钉，见调用点注释。
 */
internal object IconRough {

  /** 墨格数与"墨格朝不是墨方向暴露的边数"（4 邻接，画布外沿算暴露）。 */
  private fun measure(al: IntArray, w: Int, h: Int): Pair<Long, Long> {
    var ink = 0L; var peri = 0L
    for (i in al.indices) {
      if (al[i] <= 0) continue
      ink++
      val cy = i / w; val cx = i - cy * w
      if (cy == 0 || al[i - w] <= 0) peri++
      if (cy == h - 1 || al[i + w] <= 0) peri++
      if (cx == 0 || al[i - 1] <= 0) peri++
      if (cx == w - 1 || al[i + 1] <= 0) peri++
    }
    return ink to peri
  }

  /** 旧尺：`周长 / √面积`。**已知是坏的**（见本文件头），留着只为让台子能同时报两把的读数。 */
  fun jag(al: IntArray, w: Int, h: Int): Double {
    val (ink, peri) = measure(al, w, h)
    return if (ink == 0L) 0.0 else peri / sqrt(ink.toDouble())
  }

  /** 新尺候选：等周比 `周长² / 面积`。无量纲、与尺度无关。 */
  fun iso(al: IntArray, w: Int, h: Int): Double {
    val (ink, peri) = measure(al, w, h)
    return if (ink == 0L) 0.0 else peri.toDouble() * peri / ink
  }
}
