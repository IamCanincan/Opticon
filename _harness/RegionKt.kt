package harness

import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * **把"主体内部那块该不该交成底"变成一张可打分的表**（2026-10-20，KNOWN-LIMITS 两条重开入口指向的那件事）。
 *
 * 前六次撞这堵墙的写法都是：**先想一条判据 → 拿它去改像素 → 看图发现错**。
 * 这一台反过来：把每张图切成**颜色区域**，每个区域记两件事 ——
 *   ① 它自己长什么样（面积 / 碰不碰画布边 / 离边多远 / 明度 / 饱和 / 被不被包住），
 *   ② **设计师把它涂成墨还是留成底**（对岸给的答案，不是我猜的）。
 * 然后才问：这六个数里有没有哪一个**分得开**那两类。分得开 → 判据有了来源；
 * 分不开 → 这堵墙第七次结案，而且这次是带数的，不是"再试一根尺子"。
 *
 * ⚠ **必须挂在 `YardKt` 的循环里调用，不许另起一台**：`ours` 这一边要过 `toVisible` 的几何换算，
 *   另起炉灶就会拿满幅几何去和可见区的设计师层并排 —— 那是仪器错位，不是模型错（`GeoKt` 量死过）。
 */
/**
 * **同一套切块，问的是另一件事**：在**没有对岸**的语料上（真上屏那 64 张），
 * 有多少块是「源本来是一块平色、我们却铺了一道渐变上去」= 第二十一段那个 `fade`（掉色）在装机侧的形状。
 * 设计师那一层在这里没有（已适配的图根本不进模型），所以只能问"源是平的、我们交出去平不平"。
 *
 * 报三个数：`平块` = 面积 ≥0.5% 画布且我们平均上了墨(α≥128) 的平色块数与格数；
 * `坡` = 其中我们交的 α 的 p90−p10 > 40 的（一块纯色被画成了一道坡）；
 * `可抬` = 这些块如果全抬到 255，会多出几格实心（**这是那把刀的射程，不是它的收益**）。
 */
internal fun flatInkLine(src: IntArray, ours: IntArray?, edge: Int): String {
  if (ours == null) return "〔平块〕交了空掩码"
  if (!flatSelfDone) {
    flatSelfDone = true
    // **两台自检**：同一块纯色，交成实心必须报"坡=0"，交成一道坡必须报"坡=1 块"。
    //   少了这两条，"平块=0 / 坡=0" 这种**恒零**的读数看着和"这一族真的一张都没有"一模一样。
    // ⚠ 这个自检**第一版自己是错的**（它报出来的就是那两个 0）：源整张涂成一个颜色，
    //   于是"块内"和"块外"在源里是**同一块**，那块的平均 α 被块外一圈 0 墨拉到 116 <128 → 整张被跳过。
    //   自检要测"一块平色"，源就必须**真有两块不同颜色**：外面不透明黑、里面灰。
    val outside = (0xFF shl 24)
    val inside = (0xFF shl 24) or (60 shl 16) or (60 shl 8) or 60
    val s = IntArray(edge * edge) { outside }
    for (y in 40 until 300) for (x in 40 until 300) s[y * edge + x] = inside
    val solid = IntArray(edge * edge); val ramp = IntArray(edge * edge)
    for (y in 40 until 300) for (x in 40 until 300) {
      solid[y * edge + x] = (0xFF shl 24) or (60 shl 16) or (60 shl 8) or 60
      ramp[y * edge + x] = ((130 + (x - 40) * 109 / 259) shl 24) or (60 shl 16) or (60 shl 8) or 60
    }
    val g = flatInk(s, solid, edge); val p = flatInk(s, ramp, edge)
    if (!g.contains("平块=1/67600格") || !g.contains("坡=0块")) println("!! 平块台自检失败（金标准该 0 坡）：$g")
    if (!p.contains("坡=1块/67600格")) println("!! 平块台正对照失败（一道坡必须被数到）：$p")
  }
  return flatInk(src, ours, edge)
}

private var flatSelfDone = false

/**
 * **候选那把刀的模拟（只在台子上，生产里一行都没有）**：把"源是平色、我们却铺了一道坡"的块抬实。
 * 抬法 = 该块每一格取它自己的 p90（不是硬写 255 —— 一块本来就只画到 200 的浅灰，抬到 255 是凭空加墨）。
 * 返回改了多少格。用途只有一个：**出图，用眼睛判这 5 张到底该不该抬**。
 */
internal fun raiseFlat(src: IntArray, mask: IntArray, edge: Int): Int {
  val n = edge * edge
  val (comp, areas) = components(colorBins(src, edge), edge)
  var changed = 0
  for (id in areas.indices) {
    val area = areas[id]
    if (area * 200 < n) continue
    val a = IntArray(area); var k = 0
    for (p in 0 until n) if (comp[p] == id) a[k++] = (mask[p] ushr 24) and 0xff
    if (k == 0) continue
    a.sort()
    val mean = a.sum().toDouble() / k
    if (mean < 128) continue
    if (a[(k * 90) / 100] - a[(k * 10) / 100] <= 40) continue
    val top = a[(k * 90) / 100]
    for (p in 0 until n) if (comp[p] == id && ((mask[p] ushr 24) and 0xff) < top) {
      mask[p] = (top shl 24) or (mask[p] and 0x00ff_ffff)
      changed++
    }
  }
  return changed
}

private fun flatInk(src: IntArray, ours: IntArray, edge: Int): String {
  val n = edge * edge
  val (comp, areas) = components(colorBins(src, edge), edge)
  var blocks = 0; var cells = 0L; var slope = 0; var slopeCells = 0L; var raise = 0L
  for (id in areas.indices) {
    val area = areas[id]
    if (area * 200 < n) continue                       // <0.5% 画布的不算一块
    val a = IntArray(area); var k = 0
    for (p in 0 until n) if (comp[p] == id) a[k++] = (ours[p] ushr 24) and 0xff
    if (k == 0) continue
    a.sort()
    val mean = a.sum().toDouble() / k
    if (mean < 128) continue                            // 这块我们本来就交成底，不在掉色这本账里
    blocks++; cells += k
    val spread = a[(k * 90) / 100] - a[(k * 10) / 100]
    if (spread > 40) {
      slope++; slopeCells += k
      for (v in a) if (v < 255) raise += (255 - v)
    }
  }
  return "〔平块〕平块=$blocks/${cells}格 坡=${slope}块/${slopeCells}格 抬到255多=${raise} 墨当量"
}

/** 4 位/通道的颜色 bin；α<64 一律记 -1（空气）。两处共用一份，别各抄一遍。 */
internal fun colorBins(src: IntArray, edge: Int): IntArray {
  val out = IntArray(src.size)
  for (i in src.indices) {
    val p = src[i]
    val a = (p ushr 24) and 0xff
    out[i] = if (a < 64) -1
    else (((p shr 16) and 0xff) / 16 * 256) or (((p shr 8) and 0xff) / 16 * 16) or ((p and 0xff) / 16)
  }
  return out
}

/** 同 bin 的 4 邻接连通块。返回 (每格的块号, 各块面积)。 */
internal fun components(bin: IntArray, edge: Int): Pair<IntArray, ArrayList<Int>> {
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
/** 真 ΔE76（[MonochromeMask.labChannel] 三个通道都是**真值 ×64**，与 `carrierDep` 同口径）。 */
internal fun labDe(p: Int, q: Int): Double {
  val dl = (MonochromeMask.labChannel(p, 0) - MonochromeMask.labChannel(q, 0)) / 64.0
  val da = (MonochromeMask.labChannel(p, 1) - MonochromeMask.labChannel(q, 1)) / 64.0
  val db = (MonochromeMask.labChannel(p, 2) - MonochromeMask.labChannel(q, 2)) / 64.0
  return Math.sqrt(dl * dl + da * da + db * db)
}

/**
 * 逐颜色区域落表。**第一段六个数是 2026-10-20 那一轮的原配**（`面积/触边/离边/离空/明度/饱和/围合`），
 * 后面八个是 2026-10-23 新加的**运行时可得**候选（不碰设计师那层）：
 *
 *   盘同 = 这块有多少格与**盘色**同族（甲类 `ColorDrawable` 用它的颜色；乙类用背景层同位置的颜色）
 *   盘Δ  = 与盘色的平均 真 ΔE76
 *   源α  = 源图的平均不透明度
 *   质心 = 区域质心到**内容质心**的距离（按画布半径归一，0=正中）
 *   细长 = 周长/(面积×8)（细线/文字高，块状低）
 *   环Δ  = 区域均色与**外圈邻格均色**的 真 ΔE76
 *   族占 = **同色族**（同一 4 位/通道 bin）占整张画布的比例 —— 盘通常很大、细节很小
 *   长宽 = 外接框长边/短边
 *
 * [bg] 必须与 [src] **同几何**（可见区）；甲类的纯色盘走 [plate]，乙类传 [bg]。
 */
internal fun regionRows(
  pkg: String, src: IntArray, ours: IntArray, theirs: IntArray, edge: Int,
  bg: IntArray? = null, plate: Int = -1,
): ArrayList<String> {
  val n = edge * edge
  // 切块与 bin 都用共用那一份（`colorBins` / `components`）—— 两处各抄一套，
  // 就会出现"两张表的分块不是同一套块"，而那两个数看着完全可以互相解释。
  val bin = colorBins(src, edge)
  val (comp, areas) = components(bin, edge)
  val minArea = n * 2 / 1000                   // 小于 0.2% 画布的不算"区域"，算噪点
  // **离空**：这块到"最近一口空气（或画布外）"要走几格。
  //   为什么要有它：原来那把 `围合`（外圈邻格里非空气的比例）**当场退化** —— 645 块里 594 块 = 1.00、
  //   只有 49 块 <0.2，AUC 0.504 量的是"这把尺没有量程"，不是"这块包不包住没有信息"。
  //   连续的距离才有分辨率：谁能把"主体内部的一块"和"贴在边缘的一块"分开，看这个数。
  val distAir = distToAir(bin, edge)
  // 同色族总覆盖 + 内容质心：这两样运行时都能从源图直接算，不需要设计师层。
  val binCount = IntArray(4096)
  var gx = 0.0; var gy = 0.0; var nAll = 0
  for (p in 0 until n) {
    val b = bin[p]; if (b < 0) continue
    binCount[b]++
    gx += p % edge; gy += p / edge; nAll++
  }
  if (nAll > 0) { gx /= nAll; gy /= nAll } else { gx = edge / 2.0; gy = edge / 2.0 }
  val out = ArrayList<String>()
  var dropped = 0
  for (id in areas.indices) {
    val area = areas[id]
    if (area < minArea) { dropped++; continue }
    var x0 = edge; var y0 = edge; var x1 = 0; var y1 = 0
    var touch = 0; var luma = 0L; var sat = 0L; var their = 0L; var our = 0L
    var perim = 0; var enclosed = 0; var deep = 1 shl 20
    var alphaSum = 0L; var cxSum = 0L; var cySum = 0L
    var perim4 = 0
    var plateSame = 0; var plateDen = 0; var plateDe = 0.0
    var labL = 0L; var labA = 0L; var labB = 0L
    var ringL = 0L; var ringA = 0L; var ringB = 0L; var ringN = 0
    var binId = -1
    for (p in 0 until n) {
      if (comp[p] != id) continue
      if (binId < 0) binId = bin[p]
      val x = p % edge; val y = p / edge
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
      if (x == 0 || y == 0 || x == edge - 1 || y == edge - 1) touch = 1
      val px = src[p]
      val rr = (px shr 16) and 0xff; val gg = (px shr 8) and 0xff; val bb = px and 0xff
      luma += (0.2126 * rr + 0.7152 * gg + 0.0722 * bb).toLong()
      sat += maxOf(rr, gg, bb) - minOf(rr, gg, bb)
      their += ((theirs[p] ushr 24) and 0xff).toLong()
      our += ((ours[p] ushr 24) and 0xff).toLong()
      alphaSum += ((px ushr 24) and 0xff).toLong()
      cxSum += x; cySum += y
      labL += MonochromeMask.labChannel(px, 0)
      labA += MonochromeMask.labChannel(px, 1)
      labB += MonochromeMask.labChannel(px, 2)
      // 盘色：甲类 = 纯色盘那一个颜色；乙类 = 背景层同位置的颜色（α<128 的格不算分母）。
      if (plate >= 0) {
        plateDen++
        if (MonochromeMask.plateKey(px) == MonochromeMask.plateKey(plate)) plateSame++
        plateDe += labDe(px, plate)
      } else if (bg != null) {
        val q = bg[p]
        if (((q ushr 24) and 0xff) >= 128) {
          plateDen++
          if (MonochromeMask.plateKey(px) == MonochromeMask.plateKey(q)) plateSame++
          plateDe += labDe(px, q)
        }
      }
      // **围合度**：这块的外圈邻格里，有多少是"图上还有别的东西"（非空气、在画布内）。
      //   1.00 = 完全被别的东西包着（主体内部的一块）；0.00 = 四周都是空气/画布外。
      //   环Δ 用的是**别的块**（同一块的邻格不算），与围合的"非空气"分母分开。
      var nb = 0; var enc = 0
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val qx = x + dx; val qy = y + dy
        if (qx < 0 || qy < 0 || qx >= edge || qy >= edge) continue
        nb++
        val q = qy * edge + qx
        if (bin[q] >= 0) {
          enc++
          if (comp[q] != id) {
            val rp = src[q]
            ringL += MonochromeMask.labChannel(rp, 0)
            ringA += MonochromeMask.labChannel(rp, 1)
            ringB += MonochromeMask.labChannel(rp, 2)
            ringN++
          }
        }
      }
      perim += nb; enclosed += enc
      // 真正的**边界**：4 邻域里出到块外的边数。原来的 `perim` 是"邻格总数"（≈8×面积），
      // 除下来恒等于 1，量不了细不细 —— 2026-10-23 第一版当场退化成常数 1.00。
      if (x == 0 || comp[p - 1] != id) perim4++
      if (x == edge - 1 || comp[p + 1] != id) perim4++
      if (y == 0 || comp[p - edge] != id) perim4++
      if (y == edge - 1 || comp[p + edge] != id) perim4++
      if (distAir[p] < deep) deep = distAir[p]
    }
    val ringDe = if (ringN == 0) -1.0 else {
      val l = labL.toDouble() / area - ringL.toDouble() / ringN
      val a = labA.toDouble() / area - ringA.toDouble() / ringN
      val b = labB.toDouble() / area - ringB.toDouble() / ringN
      Math.sqrt(l * l + a * a + b * b) / 64.0
    }
    val platePct = if (plateDen == 0) -1.0 else plateSame * 100.0 / plateDen
    val plateDeMean = if (plateDen == 0) -1.0 else plateDe / plateDen
    val cdist = Math.hypot(cxSum.toDouble() / area - gx, cySum.toDouble() / area - gy) / (edge / 2.0) * 100
    val thin = perim4.toDouble() / area
    val famPct = if (binId < 0) -1.0 else binCount[binId] * 100.0 / n
    val aspect = maxOf(x1 - x0 + 1, y1 - y0 + 1).toDouble() / maxOf(1, minOf(x1 - x0 + 1, y1 - y0 + 1))
    out.add(listOf(pkg, id, "%.3f".format(area * 100.0 / n), touch,
      minOf(x0, y0, edge - 1 - x1, edge - 1 - y1), deep,
      "%.0f".format(luma.toDouble() / area), "%.0f".format(sat.toDouble() / area),
      "%.2f".format(if (perim == 0) 0.0 else enclosed.toDouble() / perim),
      "%.0f".format(their / area.toDouble()), "%.0f".format(our / area.toDouble()),
      "%.1f".format(alphaSum.toDouble() / area), "%.1f".format(cdist), "%.3f".format(thin),
      "%.2f".format(ringDe), "%.1f".format(platePct), "%.2f".format(plateDeMean),
      "%.2f".format(famPct), "%.2f".format(aspect)).joinToString("\t"))
  }
  if (dropped > 0) out.add("$pkg\t噪点\t$dropped\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-")
  return out
}

/** 每格到最近"空气（bin<0）或画布外"的 8 邻域步数。区域内部判据（`离空 ≥12`）与生产模拟共用这一份。 */
internal fun distToAir(bin: IntArray, edge: Int): IntArray {
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

/**
 * **模拟/候选**：纯色盘上，把"内部（离空≥12）、离盘色 ΔE 小于阈值、当前还是雾（逐格 α<200）"的格归零。
 * 2026-10-23 第九轮：62 对参照上甲类 +5.3k、0 块受伤；乙类净负，**生产里还没落地**。
 * 返回 [归零的区域数, 归零的可疑格数]；只改 [mask] 的 α，不动 RGB。
 */
internal fun cutFogNearPlate(mask: IntArray, src: IntArray, edge: Int, plate: Int, maxDe: Int): IntArray {
  val n = edge * edge
  val bin = colorBins(src, edge)
  val (comp, areas) = components(bin, edge)
  val dist = distToAir(bin, edge)
  val minArea = n * 2 / 1000
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
    if (deep < 12 || de / area >= maxDe || alpha / area >= 200) continue
    var cut = 0
    for (p in 0 until n) if (comp[p] == id) {
      val a = (mask[p] ushr 24) and 0xff
      if (a in 1..199) { mask[p] = mask[p] and 0x00ffffff; cut++ }
    }
    if (cut > 0) { regions++; cells += cut }
  }
  return intArrayOf(regions, cells)
}
