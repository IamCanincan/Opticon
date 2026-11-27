package com.iamcanincan.opticon.circle

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 老式图标的几何：**一份**算式，彩色与单色共用（主人 2026-11-15：「彩色不能给单色提供图标，再让单色改吗」）。
 *
 * 三步（主人 2026-11-14 原话定下的口径）：
 *  ① 把内容缩放到**恰好不被正方形裁掉任何一个有色像素**（[INSCRIBED_SHARE] 之前先做这一步）；
 *  ② 按填满之后**可见格占整张光栅的比例**分档：≥ [INSCRIBED_SHARE]（π/4 调小到 0.778）走边角延伸，
 *    小于它要铺底；但**认出的正圆直接延伸**（占比在小格上被抗锯齿吃掉，不该由它答圆该不该延伸）；
 *    新翻进延伸那一档里连笔画都白的（白盘白字）仍否决回铺底（见 [PALE_VETO_TOP]）；
 *  ③ 铺底那一支再缩到**内切圆不会裁掉任何一个有色像素**（留 [CIRCLE_FIT] 余量；
 *    实心核是正圆的除外 —— 它恰好就是内切圆，再缩就是给完美的圆套一圈底）。
 *
 * 为什么要抽成一份：单色层必须落在与彩色同一块几何上，否则主题图标与彩色图标一大一小
 * （主人 2026-11-14：「单色图标和彩色图标不符」）。原先单色那边把这三段**重抄了一遍**，
 * 两份各自数格 —— 抄本用的分档口径还与彩色不同（按内容外接框归一 vs 按整张光栅归一），
 * 非方形内容的图标两边会各给一个答案。这就是本仓点过多次名的"一个模块两套主张"。
 *
 * 现在两边都调 [planOf]：彩色拿倍率**重画** drawable，单色拿同一个倍率**缩放**自己那张掩码。
 * 纯 `IntArray`、不吃 Android —— 所以判据台编得动这个文件（与 [IconCropWindow] 同一待遇）。
 */
internal object IconGeom {

  /**
   * 一次几何决定的结果，坐标与倍率都以**送进来的那张光栅（w×h）**为基准。
   *
   * @param scale 相对这张光栅的总倍率（1 = 不用动）
   * @param cx/cy 内容外接框中心（缩放原点，之后要对到光栅中心）
   * @param fill  填满正方形之后可见格占整张光栅的比例（②那把尺的读数）
   * @param radius ①之后有色格到内容中心的最大半径（②不成立时才有意义，否则 0）
   * @param plate true = 小占比那一支（彩色要铺底；单色只跟尺寸，底由宿主的主题色承担）
   * @param shrunk true = ③真的动了手（内切圆会裁到内容，所以按 [CIRCLE_FIT] 缩了）
   */
  class Plan(
    val scale: Float,
    val cx: Float,
    val cy: Float,
    val fill: Float,
    val radius: Float,
    val plate: Boolean,
    val shrunk: Boolean,
  ) {

    /** 缩放后图层要给的 bounds：`[left, top, right, bottom]`（内容中心对到光栅中心）。 */
    fun bounds(w: Int, h: Int): FloatArray {
      val left = w / 2f - cx * scale
      val top = h / 2f - cy * scale
      return floatArrayOf(left, top, left + w * scale, top + h * scale)
    }

    /** 倍率离 1 不到 1% 就别重画/重采样：两边共用同一句，别各写各的容差。 */
    val needsRedraw: Boolean get() = abs(scale - 1f) >= 0.01f
  }

  /** 全透明（一个有色格都没有）→ null，调用方按"不动几何"处理。
   *
   * @param forceExtend 调用方按**资源身份**认定这张是满幅圆盘（应用自己声明的 roundIcon
   *   老式位图）：它的可见占比天生比 π/4 低一圈抗锯齿，走铺底支会再缩一圈 + 铺白底 =
   *   给本来完美的圆套白圈（2026-11-18 真机 parsec 应用信息页）。强制走延伸支：
   *   角按它自己的边缘色补满，不缩、不铺底。单色侧经 [MonochromeSynth.forIcon] 跟同一个旗标，
   *   两边落在同一块几何上。
   */
  fun planOf(
    source: IntArray, w: Int, h: Int, forceExtend: Boolean = false,
    faintPlate: Boolean = !forceExtend && hasFaintPlate(source, w, h),
  ): Plan? {
    val px = if (faintPlate) source.copyOf().also(::clearFaintPlate) else source
    var l = w
    var t = h
    var r = -1
    var b = -1
    var vis = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if ((px[row + x] ushr 24) <= ALPHA_MIN) continue
        vis++
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
      }
    }
    if (r < 0) return null
    val bw = (r - l + 1).toFloat()
    val bh = (b - t + 1).toFloat()
    val cx = (l + r + 1) / 2f
    val cy = (t + b + 1) / 2f

    // ① 填满正方形（已经贴边就当 1，不重画 —— 与旧写法同一个容差）
    val s1 = minOf(w / bw, h / bh)
    val fillScale = if (abs(s1 - 1f) < 0.01f) 1f else s1

    // ② 分档读的是**填满之后**占整张光栅的比例。彩色先前是真的重画一遍再数，
    //   这里是同一个数的解析式：等比缩放 s 倍，有色格按 s² 涨。
    val fill = vis * fillScale * fillScale / (w * h)

    // ③ 铺底那一支才要缩进内切圆：半径在未缩放的格子上量，所以 ①② 合成一步时
    //   总倍率里的 fillScale 正好约掉（s1 × need/(maxR·s1) = need/maxR）。
    if (forceExtend) return Plan(fillScale, cx, cy, fill, 0f, false, false)
    // ② 延伸支。挪线新翻进来的那一档 [INSCRIBED_SHARE, PALE_VETO_TOP) 里，连笔画都白的
    //   （白盘白字，p5≥206）补白角会把字藏进白里 ⇒ 否决延伸、落铺底（黑底）。白盘灰字的
    //   （学习通 p5=109）不拦。旧线以上早已延伸的行不受这条否决（它们的角补的是自己的边色，
    //   未必是白，全量否决会把 chrome/glimpse/chatgpt 这些好好的延伸行误推回铺底）。
    val extendByArea = fill >= INSCRIBED_SHARE && !(fill < PALE_VETO_TOP && uniformPaleInk(px, w, h))
    // 占比线以下的正圆直接救进延伸：占比这把尺在小格上被抗锯齿/源量化吃掉（真机快捷方式
    //   87 档学习通 fill=0.768 < 0.778，四档 96/192/384/768 量不到这一档），而正圆"本来就是
    //   内切圆"，该不该延伸不该由占比答 ⇒ 认圆就延伸（主人 2026-11-20：「要一整块白底」）。
    //   连笔画都白的正圆仍不救（白上白看不见）。占比线只管非圆。
    val circle = !extendByArea && roundCore(px, w, h)
    val extend = extendByArea || (fill < INSCRIBED_SHARE && circle && !uniformPaleInk(px, w, h))
    val openGlyph = faintPlate || (extend && isOpenGlyph(px, w, h, l, t, r, b))
    if (extend && !openGlyph) return Plan(fillScale, cx, cy, fill, 0f, false, false)
    var maxR2 = 0f
    for (y in t..b) {
      val dy = y + 0.5f - cy
      val row = y * w
      for (x in l..r) {
        if ((px[row + x] ushr 24) <= ALPHA_MIN) continue
        val dx = x + 0.5f - cx
        val d2 = dx * dx + dy * dy
        if (d2 > maxR2) maxR2 = d2
      }
    }
    val maxR = sqrt(maxR2)
    val radius = maxR * fillScale
    val need = minOf(w, h) / 2f * CIRCLE_FIT
    // 正圆核本来就恰好是内切圆，再按 CIRCLE_FIT 缩就是给完美的圆套一圈底（主人 2026-11-19
    // 点名学习通正圆快捷方式「缩小的圆垫在底上」）。认圆只数实心格（主人：「半透明不能算
    // 有色像素」—— 用在认圆这一步；全局名单不动：半透明白盘那一族的盘本身就是半透明画的）。
    val scale = if (openGlyph) minOf(1f, need / maxR)
      else if (radius > need && !circle) need / maxR else fillScale
    val shrunk = scale < fillScale
    return Plan(scale, cx, cy, fill, radius, true, shrunk)
  }

  private const val FAINT_PLATE_ALPHA = 24
  private const val FAINT_PLATE_OPAQUE_PERMILLE = 20

  /** Detect a flat, mostly transparent backing around an opaque glyph. */
  internal fun hasFaintPlate(px: IntArray, w: Int, h: Int): Boolean {
    if (w < 16 || h < 16) return false
    val plate = px[h / 2 * w + w / 8]
    if (plate ushr 24 !in (ALPHA_MIN + 1)..FAINT_PLATE_ALPHA ||
      px[h / 2 * w + w - 1 - w / 8] != plate ||
      px[h / 8 * w + w / 2] != plate ||
      px[(h - 1 - h / 8) * w + w / 2] != plate
    ) return false
    var faint = 0
    var opaque = 0
    var other = 0
    for (p in px) {
      val alpha = p ushr 24
      if (alpha >= 245) opaque++
      if (alpha !in (ALPHA_MIN + 1)..FAINT_PLATE_ALPHA) continue
      if (p == plate) faint++ else other++
    }
    return faint * 5 >= px.size &&
      opaque * 1000 >= px.size * FAINT_PLATE_OPAQUE_PERMILLE &&
      other * 8 <= faint
  }

  internal fun clearFaintPlate(px: IntArray) {
    for (i in px.indices) if (px[i] ushr 24 <= FAINT_PLATE_ALPHA) px[i] = 0
  }

  /** A padded, single-color glyph with clear interior gaps needs a contrasting plate. */
  private fun isOpenGlyph(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
    if (minOf(l, w - 1 - r) < w / 20 || minOf(t, h - 1 - b) < h / 20) return false
    val xInset = (r - l + 1) / 5
    val yInset = (b - t + 1) / 5
    var clear = 0
    var area = 0
    var color = 0
    var solid = 0
    for (y in t..b) for (x in l..r) {
      val p = px[y * w + x]
      if (x in (l + xInset)..(r - xInset) && y in (t + yInset)..(b - yInset)) {
        area++
        if (p ushr 24 <= ALPHA_MIN) clear++
      }
      if (p ushr 24 != 255) continue
      if (solid == 0) color = p
      if (maxOf(
          abs((p shr 16 and 255) - (color shr 16 and 255)),
          abs((p shr 8 and 255) - (color shr 8 and 255)),
          abs((p and 255) - (color and 255)),
        ) > 12
      ) return false
      solid++
    }
    return solid > 0 && clear * 8 >= area
  }

  /** 实心核（α=255）是不是正圆：面积比 ≈ π/4 + 半径签名摆幅 ≈ 1 + 外框方。
   *  三轴各自量过分离度：星形面积比 ~0.4、圆角方 ≥0.85、满方 1；星形/圆角方/满方的
   *  半径摆幅 ≥1.3；正圆的摆幅只有像素量化（±1.5px）与源图量化放大（实测 ≤2%）。 */
  private fun roundCore(px: IntArray, w: Int, h: Int): Boolean {
    var l = w
    var t = h
    var r = -1
    var b = -1
    var vis = 0
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if ((px[row + x] ushr 24) < 255) continue
        vis++
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
      }
    }
    if (r < 0) return false
    val bw = r - l + 1
    val bh = b - t + 1
    if (abs(bw - bh) > maxOf(1, (bw * 0.02f).toInt())) return false
    val share = vis.toFloat() / (bw * bh)
    if (share < CIRCLE_AREA_LO || share > CIRCLE_AREA_HI) return false
    val cx = (l + r + 1) / 2f
    val cy = (t + b + 1) / 2f
    val half = bw / 2f
    var rMin = Float.MAX_VALUE
    var rMax = 0f
    for (k in 0 until CIRCLE_RAYS) {
      val th = k * Math.PI.toFloat() * 2f / CIRCLE_RAYS
      val dx = kotlin.math.cos(th)
      val dy = kotlin.math.sin(th)
      var last = 0f
      var d = 1f
      while (d <= half * 1.6f + 2f) {
        val x = Math.round(cx + dx * d)
        val y = Math.round(cy + dy * d)
        if (x < 0 || y < 0 || x >= w || y >= h) break
        if ((px[y * w + x] ushr 24) >= 255) last = d
        d += 0.5f
      }
      if (last < half * 0.8f) return false
      if (last < rMin) rMin = last
      if (last > rMax) rMax = last
    }
    return rMax / rMin <= 1f + CIRCLE_SWING_PX / half + CIRCLE_SWING_SRC
  }

  /** 连最暗的那一小撮墨都淡（p5 ≥ [PLATE_LIGHT_P10]）= 白盘**白字**那种：延伸补白角会把字
   *  藏进白里 ⇒ 否决延伸。看 p5 不看 p10/中位：p10/中位量的是"盘白不白"，会把"白盘**灰字**"
   *  （学习通 p5=109，灰字白底看得见）也误拦；p5 量"有没有任何看得见的深色笔画"，全白才拦
   *  （文件前景 p5=239、菜鸟 p5≈238）。空档 109↔238，206 摆中间。 */
  private fun uniformPaleInk(px: IntArray, w: Int, h: Int): Boolean {
    val (p5, _) = inkLumPercentiles(px, w, h) ?: return false
    return p5 >= PLATE_LIGHT_P10
  }

  /** 实心格（α≥[SOLID_ALPHA]）亮度的 (p5, p10)；一个实心格都没有 → null。
   *  亮度 = 0.2126r+0.7152g+0.0722b，直方图一遍不排序。彩色铺底换黑底（吃 p10）与几何延伸
   *  淡墨否决（吃 p5）共用这一份（主人 2026-11-15：一个模块不许两套主张）。 */
  internal fun inkLumPercentiles(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
    val hist = IntArray(256)
    var n = 0
    for (i in 0 until w * h) {
      val p = px[i]
      if ((p ushr 24) < SOLID_ALPHA) continue
      val r = (p ushr 16) and 255
      val g = (p ushr 8) and 255
      val b = p and 255
      hist[(0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)]++
      n++
    }
    if (n == 0) return null
    var p5 = -1
    var p10 = -1
    var acc = 0
    for (v in 0..255) {
      acc += hist[v]
      if (p5 < 0 && acc >= n / 20) p5 = v
      if (p10 < 0 && acc >= n / 10) { p10 = v; break }
    }
    return Pair(p5, p10)
  }

  /** 正圆面积比带：实测正圆族 0.754~0.803（四档），π/4=0.7854 居中；上下各留一格量化。 */
  private const val CIRCLE_AREA_LO = 0.74f
  private const val CIRCLE_AREA_HI = 0.81f

  /** 半径 march 的取整噪声 ±1.5px（步长 0.5 + round 各半格）。 */
  private const val CIRCLE_SWING_PX = 3f

  /** 源图量化被放大后的摆幅项：学习通实测 2%（384/768 档），留到 3%。 */
  private const val CIRCLE_SWING_SRC = 0.03f

  private const val CIRCLE_RAYS = 36

  /** Trace the same contour for RGB and monochrome. Monochrome stores ink in the low byte.
   * Both outputs mark coverage in the high byte, including where the propagated ink is zero. */
  internal fun traceExtension(
    px: IntArray, w: Int, h: Int, outside: ByteArray, fill: IntArray, ink: IntArray?,
    distance: IntArray? = null,
  ): ByteArray {
    val n = w * h
    val reached = ByteArray(n)
    if (outside.none { it == 1.toByte() }) return reached
    // 1b) 软边通行证：从 outside 出发洪泛整条 α<255 的渐隐坡（只作通路，不作种子）。
    //     parsec 那类轮廓是 6 格宽的 α 坡（21→204→255），贴 outside 的那格 α=33 不是实色，
    //     旧种子判据（八邻有 outside 的实色格）在整条软边弧上**一个种子都选不出**，
    //     底角只能借顶弧硬边的浅色种子 ⇒ 补色 34 贴局部边色 11 = 主人看到的色差。
    //     soft 供选种子与波前通行（种子在坡内侧），但只往 outside 格写值 ⇒ 补色区域逐格不变；
    //     硬边图 soft==outside ⇒ 零差异。
    val soft = ByteArray(n)
    val work = IntArray(n)
    var sp2 = 0
    for (i in 0 until n) if (outside[i] == 1.toByte()) { soft[i] = 1; work[sp2++] = i }
    while (sp2 > 0) {
      val i = work[--sp2]
      val x = i % w
      val y = i / w
      if (x > 0 && soft[i - 1] == 0.toByte() && (px[i - 1] ushr 24) < 255) { soft[i - 1] = 1; work[sp2++] = i - 1 }
      if (x + 1 < w && soft[i + 1] == 0.toByte() && (px[i + 1] ushr 24) < 255) { soft[i + 1] = 1; work[sp2++] = i + 1 }
      if (y > 0 && soft[i - w] == 0.toByte() && (px[i - w] ushr 24) < 255) { soft[i - w] = 1; work[sp2++] = i - w }
      if (y + 1 < h && soft[i + w] == 0.toByte() && (px[i + w] ushr 24) < 255) { soft[i + w] = 1; work[sp2++] = i + w }
    }

    // 2) 种子：八邻里有 outside/soft 格的**全实色**格 —— 真实轮廓边界，不是行/列极值。
    //    ⚠ 2026-11-18：阈值曾为 SOLID_ALPHA(160)，把 α 坡上的半透明格也当种子；那些格的
    //    straight-alpha 色 = 预乘÷α，低 α 处 rounding 噪声被放大成 25..62 锯齿（真机环向
    //    读数 r=126/128），被径向链拉成放射亮条。只认 α=255 的实心边（环向光滑），
    //    一个都选不出时退回 160 兜底（防整张空补色）。
    val dist = IntArray(n) { -1 }
    val counts = IntArray(n)
    val sums = Array(if (ink == null) 3 else 1) { IntArray(n) }
    var head = 0
    var tail = 0
    for (threshold in intArrayOf(255, SOLID_ALPHA)) {
      for (i in 0 until n) {
        if ((px[i] ushr 24) < threshold) continue
        val x = i % w
        val y = i / w
        var seed = false
        for (dy in -1..1) for (dx in -1..1) {
          if (dx == 0 && dy == 0) continue
          val nx = x + dx
          val ny = y + dy
          if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
          if (soft[ny * w + nx] == 1.toByte()) seed = true
        }
        if (!seed) continue
        dist[i] = 0
        distance?.set(i, -1)
        counts[i] = 1
        if (ink == null) {
          val pixel = px[i]
          sums[0][i] = pixel and 255
          sums[1][i] = pixel ushr 8 and 255
          sums[2][i] = pixel ushr 16 and 255
        } else {
          sums[0][i] = ink[i] ushr 24
        }
        work[tail++] = i
      }
      if (tail > 0) break
    }

    // 3) BFS 往外拓（八邻）。等距波前相遇按种子数累加、最后平均 ——
    //    "谁先到谁赢"会在两色边界的角上留一条硬缝。
    while (head < tail) {
      val i = work[head++]
      val x = i % w
      val y = i / w
      val d = dist[i] + 1
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val nx = x + dx
        val ny = y + dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        val j = ny * w + nx
        // 波前可以穿过 soft 渐隐坡（软边种子在坡内侧，不放行就一步也迈不进补色区），
        // 但第 4 步只往 outside 格写值 ⇒ 补色区域逐格不变。
        if (soft[j] != 1.toByte()) continue
        if (dist[j] < 0) {
          dist[j] = d
          counts[j] = counts[i]
          for (channel in sums) channel[j] = channel[i]
          work[tail++] = j
        } else if (dist[j] == d) {
          // ⚠ 2026-11-18 修溢出：旧写法 cnt[j]+=cnt[i]、sum+=sum[i] 把父格已累加的计数整份
          //   再加进来，cnt 数的是"最短路径条数"而非种子数，随距离指数爆炸（parsec 底角
          //   d=196 时 cnt=4.78 亿、sum 负穿 Int → 补色变黄/黑垃圾 = 桌面马赛克）。
          //   改为只加父格的**平均色**、cnt 只 +1：cnt 恒 ≤4、sum 恒 ≤1020，永不溢出；
          //   单种子区域（cnt=1）逐字不变，等距相遇仍是平滑平均（无硬缝）。
          counts[j] += 1
          for (channel in sums) channel[j] += (channel[i] + counts[i] / 2) / counts[i]
        }
      }
    }

    // 4) 只交"外面"的格
    for (i in 0 until n) {
      if (outside[i] == 1.toByte() && dist[i] >= 0) {
        reached[i] = 1
        distance?.set(i, dist[i])
        val count = counts[i]
        var pixel = 255 shl 24
        for (channel in sums.indices) {
          pixel = pixel or (((sums[channel][i] + count / 2) / count) shl (8 * channel))
        }
        fill[i] = pixel
      }
    }
    return reached
  }

  /** Suppress streaks where neighboring contour colors spread apart. Keep the seam
   * anchored by increasing the filter radius gradually with distance from the contour. */
  internal fun smoothExtensionColors(
    fill: IntArray, distance: IntArray, snapshot: IntArray, w: Int, h: Int,
  ) {
    val maxDistance = distance.maxOrNull() ?: return
    var step = 2
    while (step <= minOf(w, h) / 24 && maxDistance > step * 3) {
      fill.copyInto(snapshot)
      for (i in fill.indices) {
        val mix = (distance[i] - step * 3).coerceIn(0, step * 4)
        if (mix == 0) continue
        val x = i % w
        val y = i / w
        var red = 0
        var green = 0
        var blue = 0
        var weightSum = 0
        for (dy in -1..1) for (dx in -1..1) {
          val nx = x + dx * step
          val ny = y + dy * step
          if (nx !in 0 until w || ny !in 0 until h) continue
          val sample = ny * w + nx
          val midpoint = (y + dy * (step / 2)) * w + x + dx * (step / 2)
          if (distance[sample] <= 0 || distance[midpoint] <= 0) continue
          val color = snapshot[sample]
          val weight = (2 - abs(dx)) * (2 - abs(dy))
          red += (color ushr 16 and 255) * weight
          green += (color ushr 8 and 255) * weight
          blue += (color and 255) * weight
          weightSum += weight
        }
        val original = snapshot[i]
        val blendScale = step * 4
        val total = weightSum * blendScale
        val originalWeight = weightSum * (blendScale - mix)
        val r = ((original ushr 16 and 255) * originalWeight + red * mix + total / 2) / total
        val g = ((original ushr 8 and 255) * originalWeight + green * mix + total / 2) / total
        val b = ((original and 255) * originalWeight + blue * mix + total / 2) / total
        fill[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
      }
      step *= 2
    }
  }

  private fun isPaleBacking(color: Int): Boolean {
    val r = color ushr 16 and 255
    val g = color ushr 8 and 255
    val b = color and 255
    val low = minOf(r, g, b)
    return low >= 216 && maxOf(r, g, b) - low <= 16
  }

  /** Clear the pale rim adjoining the exterior when the mask already treats it as backing. */
  internal fun clearUninkedPaleOutline(
    pixels: IntArray, ink: IntArray, distance: IntArray, width: Int, height: Int,
  ): Boolean {
    var outline = 0
    var paleOutline = 0
    var clearPale = 0
    var paleArea = 0
    var coloredInk = 0
    for (i in pixels.indices) {
      val color = pixels[i]
      if (color ushr 24 < 245) continue
      val r = color ushr 16 and 255
      val g = color ushr 8 and 255
      val b = color and 255
      val high = maxOf(r, g, b)
      val low = minOf(r, g, b)
      val pale = isPaleBacking(color)
      val alpha = ink[i] ushr 24
      if (distance[i] == -1) {
        outline++
        if (pale) paleOutline++
      }
      if (pale) {
        paleArea++
        if (alpha <= 48) clearPale++
      } else if (high - low >= 64 && alpha >= 160) {
        coloredInk++
      }
    }
    if (outline == 0 || paleOutline * 100 < outline * 95 ||
      paleArea * 5 < pixels.size || clearPale * 10 < paleArea * 9 ||
      coloredInk * 16 < pixels.size
    ) return false

    val visited = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun enqueue(i: Int) {
      if (visited[i] || pixels[i] ushr 24 <= ALPHA_MIN || !isPaleBacking(pixels[i])) return
      visited[i] = true
      queue[tail++] = i
    }
    for (i in distance.indices) if (distance[i] == -1) enqueue(i)
    repeat((minOf(width, height) / 32).coerceAtLeast(1)) {
      val end = tail
      while (head < end) {
        val i = queue[head++]
        ink[i] = 0
        val x = i % width
        if (x > 0) enqueue(i - 1)
        if (x + 1 < width) enqueue(i + 1)
        if (i >= width) enqueue(i - width)
        if (i + width < pixels.size) enqueue(i + width)
      }
    }
    return true
  }

  /** 外侧洪泛 + 设计留缝退标（缝格=2）。自 CircleIconDrawable.computeFill 第 1/1a 步搬入，逐字未改。 */
  internal fun outsideOf(px: IntArray, w: Int, h: Int): ByteArray {
    val n = w * h
    val outside = ByteArray(n)
    val work = IntArray(n)
    var sp = 0
    fun mark(i: Int) {
      if (outside[i] == 0.toByte() && (px[i] ushr 24) <= ALPHA_MIN) {
        outside[i] = 1
        work[sp++] = i
      }
    }
    for (x in 0 until w) {
      mark(x)
      mark((h - 1) * w + x)
    }
    for (y in 0 until h) {
      mark(y * w)
      mark(y * w + w - 1)
    }
    while (sp > 0) {
      val i = work[--sp]
      val x = i % w
      val y = i / w
      if (x > 0) mark(i - 1)
      if (x + 1 < w) mark(i + 1)
      if (y > 0) mark(i - w)
      if (y + 1 < h) mark(i + w)
    }
    unmarkDesignSlots(px, w, h, outside)
    return outside
  }

  /**
   * 设计留缝：上下（或左右）被实色夹住、厚度 ≤ 边长/8 且连续长度 ≥ 半边长的透明通道
   * = 图标自己的缝（主人 2026-11-19：「查看日历那个镂空的横档呢」——outlook 日历顶块与
   * 身体之间那根横档），不是背景 ⇒ 从 outside 退标成 2，不补色、留 α0 交镂空掩码抠回透明。
   * 缝两端通外面，第 1 步洪泛会把它当外面填实 = 横档消失；封闭圆点那族不走这里（本就不是 outside）。
   *
   * ⚠ 厚度 = 到上/下实色距离之**和**（α≥[SOLID_ALPHA]，与种子兜底同数），不是半厚：
   *   半厚口径 D 会漏缝的首尾几行（384 档 D=24 时 27px 厚的缝只退中间 17行，缝上下留蓝条）。
   * ⚠ 缝缘一圈 α 渐隐格也归 α0 并标 2：它们不在 outside 里 ⇒ 补色场在它们是 0，composeOver
   *   把半透明压黑底 = 缝上下两条黑细线（384 正对照看图抓到）。缝是直边，抹渐隐无锯齿代价；
   *   **不碰封闭圆点那族的渐隐**（那族压在蓝底上是已验收的样子）。
   * 射程现量：四份语料 293 张延伸支只开火日历 1 张（离线台 `_extfill.cjs --mode=prod --slots`）。
   */
  private fun unmarkDesignSlots(px: IntArray, w: Int, h: Int, outside: ByteArray): Int {
    val n = w * h
    val d = maxOf(4, Math.round(w / 8f))
    val big = 1 shl 28
    val a = IntArray(n)
    val b = IntArray(n)
    fun ink(i: Int) = (px[i] ushr 24) >= SOLID_ALPHA
    for (x in 0 until w) {
      var t = big
      for (y in 0 until h) { val i = y * w + x; a[i] = t; t = if (ink(i)) 0 else t + 1 }
      t = big
      for (y in h - 1 downTo 0) { val i = y * w + x; b[i] = t; t = if (ink(i)) 0 else t + 1 }
    }
    val slot = ByteArray(n)
    var un = 0
    fun sandV(i: Int) = outside[i] == 1.toByte() && a[i] + b[i] <= d + 1
    for (y in 0 until h) {
      var x = 0
      while (x < w) {
        if (!sandV(y * w + x)) { x++; continue }
        var x2 = x
        while (x2 < w && sandV(y * w + x2)) x2++
        if (x2 - x >= w / 2) for (xx in x until x2) { val i = y * w + xx; outside[i] = 2; slot[i] = 1; un++ }
        x = x2
      }
    }
    for (y in 0 until h) {
      var t = big
      for (x in 0 until w) { val i = y * w + x; a[i] = t; t = if (ink(i)) 0 else t + 1 }
      t = big
      for (x in w - 1 downTo 0) { val i = y * w + x; b[i] = t; t = if (ink(i)) 0 else t + 1 }
    }
    fun sandH(i: Int) = outside[i] == 1.toByte() && a[i] + b[i] <= d + 1
    for (x in 0 until w) {
      var y = 0
      while (y < h) {
        if (!sandH(y * w + x)) { y++; continue }
        var y2 = y
        while (y2 < h && sandH(y2 * w + x)) y2++
        if (y2 - y >= h / 2) for (yy in y until y2) { val i = yy * w + x; outside[i] = 2; slot[i] = 1; un++ }
        y = y2;
      }
    }
    for (i in 0 until n) {
      if (slot[i] != 1.toByte()) continue
      val x = i % w
      val y = i / w
      if (x > 0) slotFringe(px, outside, i - 1)
      if (x + 1 < w) slotFringe(px, outside, i + 1)
      if (y > 0) slotFringe(px, outside, i - w)
      if (y + 1 < h) slotFringe(px, outside, i + w)
    }
    return un
  }

  private fun slotFringe(px: IntArray, outside: ByteArray, j: Int) {
    val al = px[j] ushr 24
    if (al in 9..254) {
      px[j] = px[j] and 0x00FFFFFF
      outside[j] = 2
    }
  }

  /**
   * 轮廓去边：把每行/列最外 2 个**毛边**格清成透明，交给 [outsideOf] 从新的边取色补进来。
   *
   * 源 PNG 的透明区常是黑色（RGB=0、alpha 有值），位图放大时抗锯齿会混出**不透明的深色边**
   * （实测 (2,21,29,a255)）；半透明的那些 bleedEdgeColor 能修，被放大成不透明的只能在这里清掉，
   * 否则叠在补边上就是一条线。
   *
   * ⚠ 2026-11-14 收窄成"只抹毛边"：原来是无条件抹最外 2 格，会把本身就有边缘细节的图标
   * （潮新闻 / 智游斗地主 / 吉星派对）糊掉。现在只清 alpha < 250 的 AA，或亮度 < [RIM_LUM]
   * 且比内侧暗 [RIM_DELTA] 以上的不透明深色边；实色真细节一格不动。
   * 实测这三张的左边缘与源图的平均亮度差：28.2→19.4、22.8→3.8、54.4→30.1。
   * 2026-11-20 搬入 IconGeom：它会改 alpha ⇒ 延伸区域判据的一部分，彩色与单色共读。
   */
  internal fun deFringe(px: IntArray, w: Int, h: Int) {
    val src = px.copyOf()
    fun visible(i: Int) = (src[i] ushr 24) > 8
    // 只抹**毛边**：半透明的 AA（alpha < 250），或明显比里面暗的不透明黑边。
    // 实色的真细节保留（2026-11-14：原来的"无条件抹最外 2 格"会把这 3 张的边缘细节糊掉）。
    fun rim(i: Int, inner: Int) {
      if (!visible(i)) return
      val a = src[i] ushr 24
      if (a < 250) {
        px[i] = 0
        return
      }
      if (lum31(src[i]) < RIM_LUM && lum31(src[inner]) - lum31(src[i]) > RIM_DELTA) px[i] = 0
    }
    for (y in 0 until h) {
      val row = y * w
      var x0 = 0
      while (x0 < w && !visible(row + x0)) x0++
      if (x0 < w) for (k in 0 until 2) {
        val x = x0 + k
        if (x < w && visible(row + x)) rim(row + x, row + minOf(x0 + 2, w - 1))
      }
      var x1 = w - 1
      while (x1 >= 0 && !visible(row + x1)) x1--
      if (x1 >= 0) for (k in 0 until 2) {
        val x = x1 - k
        if (x >= 0 && visible(row + x)) rim(row + x, row + maxOf(x1 - 2, 0))
      }
    }
    for (x in 0 until w) {
      var y0 = 0
      while (y0 < h && !visible(y0 * w + x)) y0++
      if (y0 < h) for (k in 0 until 2) {
        val y = y0 + k
        if (y < h && visible(y * w + x)) rim(y * w + x, minOf(y0 + 2, h - 1) * w + x)
      }
      var y1 = h - 1
      while (y1 >= 0 && !visible(y1 * w + x)) y1--
      if (y1 >= 0) for (k in 0 until 2) {
        val y = y1 - k
        if (y >= 0 && visible(y * w + x)) rim(y * w + x, maxOf(y1 - 2, 0) * w + x)
      }
    }
  }

  /** 亮度（Rec.601，0..255）：只给"深色毛边"判据用。 */
  private fun lum31(c: Int): Int {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
  }

  /** 源图把圆烤在不透明白方块上时（微信接龙管家快捷方式），白角是 α255 实像素：
   * planOf 的可见占比把白角算进去 ⇒ 走延伸支，但 computeFill 只填**透明**外面 ⇒ 白角原样留下。
   * 这里把「内切圆的白色补集」认成背景清零 α，交给后面的 bleedEdgeColor/computeFill 按边缘色补满。
   *
   * 判据 = 中性白（min≥235 且 max−min≤12、α≥128）的 4-连通块里，**四个角各有一块、
   * 每块恰好碰两条相邻边、四块面积同余（max/min≤1.15）且每块≥0.5% 光栅**。
   * 这是「圆/圆角方贴在白底上」的几何签名；设计白底不长这样：
   * 易班熊的右上白底 1 块、translate 1 块、kazumi/高德 2 块、白盘环碰四边 —— 全不满足。
   * ⚠ 第一版「碰两相邻边就清」在离线语料开火 6 张设计白底（易班 60331 格=熊的背景），已否。
   * 白→盘色的不透明渐隐环同清（只从已清零格往外长，min≥150 且 spread≤110，最多 4 环）：
   * 留着会当 computeFill 种子把四角补色拉向白（正对照看图=米色角）。清完调用方再跑一遍
   * cleanRim：r3c 那遍看到的是白角环，盘缘噪点没清 ⇒ 放射条纹（正对照第二版看图抓到）。
   * 射程现量：349 张源（runtime 语料+201 快捷方式+微信/bili 设备源）只开火接龙管家两代源图；
   * 141 张延伸合成负对照带/不带 --wc 逐字节相同。
   * 2026-11-20 搬入 IconGeom：它会改 alpha ⇒ 延伸区域判据的一部分，彩色与单色共读。
   */
  internal fun stripBakedWhiteCorners(px: IntArray, w: Int, h: Int): Int {
    val n = w * h
    val white = ByteArray(n)
    for (i in 0 until n) {
      val p = px[i]
      if ((p ushr 24) < 128) continue
      val r = (p ushr 16) and 255
      val g = (p ushr 8) and 255
      val b = p and 255
      val mn = minOf(r, g, b)
      if (mn >= 235 && maxOf(r, g, b) - mn <= 12) white[i] = 1
    }
    val lab = IntArray(n) { -1 }
    val stack = IntArray(n)
    val sides = ArrayList<Int>()
    val sizes = ArrayList<Int>()
    for (s in 0 until n) {
      if (white[s] != 1.toByte() || lab[s] >= 0) continue
      val id = sides.size
      var sp = 0
      var size = 0
      var mask = 0
      stack[sp++] = s
      lab[s] = id
      while (sp > 0) {
        val i = stack[--sp]
        size++
        val x = i % w
        val y = i / w
        if (y == 0) mask = mask or 1
        if (y == h - 1) mask = mask or 2
        if (x == 0) mask = mask or 4
        if (x == w - 1) mask = mask or 8
        if (x > 0 && white[i - 1] == 1.toByte() && lab[i - 1] < 0) { lab[i - 1] = id; stack[sp++] = i - 1 }
        if (x + 1 < w && white[i + 1] == 1.toByte() && lab[i + 1] < 0) { lab[i + 1] = id; stack[sp++] = i + 1 }
        if (y > 0 && white[i - w] == 1.toByte() && lab[i - w] < 0) { lab[i - w] = id; stack[sp++] = i - w }
        if (y + 1 < h && white[i + w] == 1.toByte() && lab[i + w] < 0) { lab[i + w] = id; stack[sp++] = i + w }
      }
      sides.add(mask)
      sizes.add(size)
    }
    // 碰边掩码：T=1 B=2 L=4 R=8 ⇒ 四角块 = TL5 / TR9 / BL6 / BR10
    val want = intArrayOf(5, 9, 6, 10)
    val pick = IntArray(4) { -1 }
    for (id in sides.indices) for (k in 0..3) if (sides[id] == want[k]) pick[k] = id
    if (pick.any { it < 0 }) return 0
    val sz = pick.map { sizes[it] }
    if (sz.max() / sz.min().toDouble() > 1.15 || sz.min() < n * 0.005) return 0
    val kill = BooleanArray(sides.size)
    for (id in pick) kill[id] = true
    val zeroed = ByteArray(n)
    var un = 0
    for (i in 0 until n) {
      if (lab[i] >= 0 && kill[lab[i]]) { px[i] = px[i] and 0x00FFFFFF; zeroed[i] = 1; un++ }
    }
    // 白→盘色的不透明渐隐环留着会当 computeFill 的种子，把四角补色拉向白（正对照看图=米色角）。
    // 只从已清零格往外长：min≥150 且 spread≤110 才认（环实测 min225..241/spread13..30，
    // 盘黄 min76/spread179 在第一层就停住），最多 4 环。
    for (pass in 0 until 4) {
      var grew = false
      for (i in 0 until n) {
        if (zeroed[i] == 1.toByte()) continue
        val p = px[i]
        if ((p ushr 24) < 128) continue
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        val mn = minOf(r, g, b)
        if (mn < 150 || maxOf(r, g, b) - mn > 110) continue
        val x = i % w
        val y = i / w
        val near = (x > 0 && zeroed[i - 1] == 1.toByte()) || (x + 1 < w && zeroed[i + 1] == 1.toByte()) ||
          (y > 0 && zeroed[i - w] == 1.toByte()) || (y + 1 < h && zeroed[i + w] == 1.toByte())
        if (near) { px[i] = p and 0x00FFFFFF; zeroed[i] = 1; un++; grew = true }
      }
      if (!grew) break
    }
    return un
  }

  /**
   * Recognize a thin white backing around a concentric, neutral dark disk. The inner
   * part of the disk is excluded from the cut, including light marks inside it.
   */
  internal fun stripPaleDiskRim(px: IntArray, w: Int, h: Int): Boolean {
    val edge = minOf(w, h)
    if (edge < 64 || abs(w - h) > edge / 32) return false
    val cx = w / 2f
    val cy = h / 2f
    val half = edge / 2f
    var innerMin = half
    var innerMax = 0f
    var outerMin = half
    var outerMax = 0f
    var bodyCeiling = 0
    for (ray in 0 until CIRCLE_RAYS) {
      val angle = ray * Math.PI * 2 / CIRCLE_RAYS
      val dx = kotlin.math.cos(angle)
      val dy = kotlin.math.sin(angle)
      var pale = 0
      var outer = 0f
      var inner = 0f
      var radius = half - 0.5f
      while (radius >= half * 0.77f) {
        val x = (cx + radius * dx).toInt()
        val y = (cy + radius * dy).toInt()
        val color = px[y * w + x]
        if (color ushr 24 >= 240) {
          val r = color ushr 16 and 255
          val g = color ushr 8 and 255
          val b = color and 255
          val high = maxOf(r, g, b)
          val low = minOf(r, g, b)
          if (low >= 235 && high - low <= 12) {
            if (pale == 0) outer = radius
            pale++
          } else if (pale >= 2 && high <= 64) {
            inner = radius
            val inset = radius - maxOf(2f, half * 0.025f)
            val body = px[(cy + inset * dy).toInt() * w + (cx + inset * dx).toInt()]
            val bodyHigh = maxOf(body ushr 16 and 255, body ushr 8 and 255, body and 255)
            if (body ushr 24 < 240 || bodyHigh > 64) return false
            bodyCeiling = maxOf(bodyCeiling, bodyHigh)
            break
          }
        }
        radius -= 0.5f
      }
      if (inner == 0f || outer - inner > half * 0.09f) return false
      innerMin = minOf(innerMin, inner)
      innerMax = maxOf(innerMax, inner)
      outerMin = minOf(outerMin, outer)
      outerMax = maxOf(outerMax, outer)
    }
    val tolerance = maxOf(2.5f, half * 0.021f)
    if (innerMax - innerMin > tolerance || outerMax - outerMin > tolerance ||
      outerMin - innerMax < 0.5f
    ) return false
    val cut = innerMin - 1f
    val limit = innerMax + 2f
    val darkLimit = minOf(64, bodyCeiling + 8)
    for (y in 0 until h) for (x in 0 until w) {
      val dx = x + 0.5f - cx
      val dy = y + 0.5f - cy
      val radiusSquared = dx * dx + dy * dy
      if (radiusSquared < cut * cut) continue
      val i = y * w + x
      val color = px[i]
      // The backing is opaque. Remove its mixed edge as well, retaining dark seeds.
      if (maxOf(color ushr 16 and 255, color ushr 8 and 255, color and 255) > darkLimit ||
        radiusSquared > limit * limit
      ) px[i] = 0
    }
    return true
  }

  private const val RIM_LUM = 60
  private const val RIM_DELTA = 40
}
