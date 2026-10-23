package com.iamcanincan.opticon.circle

/**
 * **纯色盘上的「盘色雾区」清理**（2026-10-23 第九轮，判据台量过、生产里只对纯色 under 开）。
 *
 * 来由：设计师参照 62 对、内部区域 322 块上，区域到盘色的平均 真 ΔE76 是**唯一分得开**
 * "内部这块该留空还是上墨"的运行时特征（AUC 0.870；旧七个数最好 0.631）。但代价模拟显示
 * **只有纯色盘干净**：甲类 +5.3k、0 块受伤；真背景层（乙类）净负（glimpse/documentsui 等）。
 *
 * 所以落地口径收得很窄：
 *   ① [uniformPlate] 认得出**整张 under 是同一个不透明颜色**（甲类 / 纯色盘）才开；
 *   ② 只动**内部**区域（到空气 ≥12 格）—— 贴边的那一圈永远是盘/空气，不在此列；
 *   ③ 区域均色到盘色 < [MAX_DE]；④ 只压**雾**（逐格 1..199），实心墨一格不碰。
 *
 * ⚠ 区域切块/连通/BFS 三件都在这里，判据台（`_harness/RegionKt.kt`）改为调用这一份 ——
 *   台子与生产必须是同一套分块，否则"台上量到的区域"和"生产压的区域"不是同一批。
 * ⚠ 这是**冷路径**：只在缓存未命中、模型算完之后跑一遍 384² 的切块；命中路径不付钱。
 */
internal object PlateFog {

  /** 区域均色到盘色的上限（真 ΔE76）。甲类参照的空档：留空 1..37、涂墨 38..76；取 20 留 18 点余量。 */
  const val MAX_DE = 20

  /** "内部"：区域里每一格到最近空气/画布边至少 12 格（与判据台账同一把尺）。 */
  private const val INTERNAL = 12

  /** 区域至少 0.2% 画布，与 `RegionKt` 的"一块"同门槛。 */
  private const val MIN_AREA_PERMILLE = 2

  /** 只压雾：α 在这个区间才归零；≥200 的实心墨不碰。 */
  private const val FOG_MAX = 200

  /** 整张 `under` 是同一个不透明颜色时返回它；否则返回 0（乙类/真背景层不走这条）。 */
  fun uniformPlate(px: IntArray): Int {
    if (px.isEmpty()) return 0
    val first = px[0]
    if (((first ushr 24) and 0xff) < 128) return 0
    for (p in px) if (p != first) return 0
    return first
  }

  /** 4 位/通道的颜色 bin；α<64 一律记 -1（空气）。 */
  fun colorBins(src: IntArray, edge: Int): IntArray {
    val out = IntArray(src.size)
    for (i in src.indices) {
      val p = src[i]
      val a = (p ushr 24) and 0xff
      out[i] = if (a < 64) -1
      else (((p shr 16) and 0xff) / 16 * 256) or (((p shr 8) and 0xff) / 16 * 16) or ((p and 0xff) / 16)
    }
    return out
  }

  /** 同 bin 的 4 邻接连通块（空气 bin=-1 也会成一块，但它离空=0，过不了内部那道）。 */
  fun components(bin: IntArray, edge: Int): Pair<IntArray, ArrayList<Int>> {
    val n = edge * edge
    val comp = IntArray(n) { -2 }
    val areas = ArrayList<Int>()
    val stack = IntArray(n)
    for (i in 0 until n) {
      if (comp[i] != -2) continue
      val id = areas.size; var sp = 0; var area = 0
      stack[sp++] = i; comp[i] = id
      val b = bin[i]
      while (sp > 0) {
        val p = stack[--sp]; area++
        val x = p % edge; val y = p / edge
        if (x > 0 && comp[p - 1] == -2 && bin[p - 1] == b) { comp[p - 1] = id; stack[sp++] = p - 1 }
        if (x < edge - 1 && comp[p + 1] == -2 && bin[p + 1] == b) { comp[p + 1] = id; stack[sp++] = p + 1 }
        if (y > 0 && comp[p - edge] == -2 && bin[p - edge] == b) { comp[p - edge] = id; stack[sp++] = p - edge }
        if (y < edge - 1 && comp[p + edge] == -2 && bin[p + edge] == b) { comp[p + edge] = id; stack[sp++] = p + edge }
      }
      areas += area
    }
    return comp to areas
  }

  /** 每格到最近"空气（bin<0）或画布外"的 8 邻域步数。 */
  fun distToAir(bin: IntArray, edge: Int): IntArray {
    val n = edge * edge
    val dist = IntArray(n) { 1 shl 20 }
    val q = IntArray(n); var head = 0; var tail = 0
    for (p in 0 until n) {
      val x = p % edge; val y = p / edge
      val outside = x == 0 || y == 0 || x == edge - 1 || y == edge - 1
      if (bin[p] < 0 || outside) { dist[p] = 0; q[tail++] = p }
    }
    while (head < tail) {
      val p = q[head++]; val x = p % edge; val y = p / edge
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val qx = x + dx; val qy = y + dy
        if (qx < 0 || qy < 0 || qx >= edge || qy >= edge) continue
        val q2 = qy * edge + qx
        if (dist[q2] > dist[p] + 1) { dist[q2] = dist[p] + 1; q[tail++] = q2 }
      }
    }
    return dist
  }

  /** 真 ΔE76（[MonochromeMask.labChannel] 三个通道都是**真值 ×64**，与 `carrierDep` 同口径）。 */
  fun labDe(p: Int, q: Int): Double {
    val dl = (MonochromeMask.labChannel(p, 0) - MonochromeMask.labChannel(q, 0)) / 64.0
    val da = (MonochromeMask.labChannel(p, 1) - MonochromeMask.labChannel(q, 1)) / 64.0
    val db = (MonochromeMask.labChannel(p, 2) - MonochromeMask.labChannel(q, 2)) / 64.0
    return Math.sqrt(dl * dl + da * da + db * db)
  }

  /**
   * 就地清理 [mask] 的 α。返回 `[归零的区域数, 归零的格数]`。
   * 只有区域均色到 [plate] < [maxDe] 且均 α < [FOG_MAX] 才进入；进入后也只压 α 1..199 的格。
   */
  fun cut(mask: IntArray, src: IntArray, edge: Int, plate: Int, maxDe: Int = MAX_DE): IntArray {
    val n = edge * edge
    val bin = colorBins(src, edge)
    val (comp, areas) = components(bin, edge)
    val dist = distToAir(bin, edge)
    val minArea = n * MIN_AREA_PERMILLE / 1000
    var regions = 0; var cells = 0
    for (id in areas.indices) {
      val area = areas[id]
      if (area < minArea) continue
      var deep = 1 shl 20; var de = 0.0; var alpha = 0L
      for (p in 0 until n) {
        if (comp[p] != id) continue
        if (dist[p] < deep) deep = dist[p]
        de += labDe(src[p], plate)
        alpha += (mask[p] ushr 24) and 0xff
      }
      if (deep < INTERNAL || de / area >= maxDe || alpha / area >= FOG_MAX) continue
      var cut = 0
      for (p in 0 until n) if (comp[p] == id) {
        val a = (mask[p] ushr 24) and 0xff
        if (a in 1 until FOG_MAX) { mask[p] = mask[p] and 0x00ffffff; cut++ }
      }
      if (cut > 0) { regions++; cells += cut }
    }
    return intArrayOf(regions, cells)
  }
}
