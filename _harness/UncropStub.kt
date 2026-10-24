package com.iamcanincan.opticon.circle

import android.graphics.drawable.Drawable

/**
 * 判据台的桩：`IconBitmap.kt`（通知那两路）要剥掉裁圆壳，而 `CircleIconDrawable.kt` 吃
 * `AdaptiveIconDrawable` / `Canvas`，台子编不动。台子喂进来的都是已解码的 drawable，壳本来就
 * 不存在 —— 原样返回与生产行为等价。
 */
internal fun uncroppedIcon(d: Drawable): Drawable = d
