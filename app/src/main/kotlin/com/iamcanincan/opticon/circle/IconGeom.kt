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
  fun planOf(px: IntArray, w: Int, h: Int, forceExtend: Boolean = false): Plan? {
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
    if (fill >= INSCRIBED_SHARE &&
      !(fill < PALE_VETO_TOP && uniformPaleInk(px, w, h))
    ) return Plan(fillScale, cx, cy, fill, 0f, false, false)
    // 占比线以下的正圆直接救进延伸：占比这把尺在小格上被抗锯齿/源量化吃掉（真机快捷方式
    //   87 档学习通 fill=0.768 < 0.778，四档 96/192/384/768 量不到这一档），而正圆"本来就是
    //   内切圆"，该不该延伸不该由占比答 ⇒ 认圆就延伸（主人 2026-11-20：「要一整块白底」）。
    //   连笔画都白的正圆仍不救（白上白看不见）。占比线只管非圆。
    val circle = roundCore(px, w, h)
    if (fill < INSCRIBED_SHARE && circle && !uniformPaleInk(px, w, h)) {
      return Plan(fillScale, cx, cy, fill, 0f, false, false)
    }
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
    val shrunk = radius > need && !circle
    val scale = if (shrunk) need / maxR else fillScale
    return Plan(scale, cx, cy, fill, radius, true, shrunk)
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
}
