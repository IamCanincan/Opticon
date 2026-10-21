package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **「背景被当成很淡的墨」那把尺子**（2026-10-16）。
 *
 * ## 量的是什么
 * 在交付掩码里找**最大的那块「一层淡墨」**，再核两件事 —— 这两件正是主人说的那个症状的指纹：
 *  · **面积**占画布多少（背景是一整块，通常 ≥ 十几个点）；
 *  · 这块里**源像素亮度的极差**（是真的一块底 = 源里也近乎恒定 → 小；插画的渐变/花纹 → 大）。
 *
 * ⚠⚠ **必须在「同一个 alpha 桶」里找连通块，不能在宽区间里找。**
 *   第一版把「淡」定义成 `alpha ∈ [16,120]` 再找最大连通块 —— 那个块会把整条浓度梯度
 *   一起连进来，块内极差自然 ~104，"恒定"这条永远不成立，**62 张里一张都点不出来**
 *   （与仓里那条"负例必须是真坏的，否则它只是在替我的假设背书"同族）。
 *   要的是"**一层**很淡的墨"，所以先按 `alpha >> 4` 分桶（桶 1..7 = alpha 16..127），
 *   再在每个桶内找连通块 —— 桶内极差 ≤15 是构造出来的，不用再判。
 *
 * ⚠ **为什么不能只看"淡格占比"**：插画/照片类图标的中间调本来就该是淡墨（浓淡是靠层级换的），
 *   它们同样贡献大片淡格（本仓 `feimo` 66.6%、`zyddz` 51.4%）—— 只数淡格会把它们一起冤枉。
 *   **"同一层 + 源也恒定"** 才区分得出"底"。
 *
 * 门槛（`--min=`、`--srcflat=`）**只是分类用的**：改判据之前先用它把张数点出来，
 * 别拿一个数当"好/坏"（本仓吃过"骑在人群上的阈值"的亏）。
 *
 * 用法：`sh run.sh harness.FaintKtKt <目录...> [--edge=384] [--min=12] [--srcflat=32]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val minPct = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 12
  val srcFlatMax = args.firstOrNull { it.startsWith("--srcflat=") }?.substringAfter("=")?.toInt() ?: 32
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  val cls = HashMap<String, String>(); val flat = HashSet<String>()
  for (d in dirs) {
    val ix = File(d, "index.tsv"); if (!ix.isFile) continue
    for (l in ix.readLines().drop(1)) {
      val c = l.split("\t")
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
  }
  println("%-46s %-6s %-6s %-7s %-7s %-8s %s".format(
    "图标", "淡块%", "块α", "ΔL/跨%", "源极差", "淡格%", "判定"))
  var n = 0; var bad = 0; var ill = 0
  // 面积加权的「淡块 α」分布 —— **定死区那条线就靠它**。
  // ⚠ `α/255` 就是这个块离斜坡端点的距离占**该图自身跨度**的比例（死区没加之前
  //   `ta = ramp * 255 / 跨度`，所以 α 直接读作百分比），只是被 16 宽的桶量化了 ±3%。
  val hist = DoubleArray(16)
  for (d in dirs) {
    val files = d.listFiles()?.filter {
      it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") && !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
    for (file in files) {
      val img = runCatching { ImageIO.read(file) }.getOrNull() ?: continue
      val px = raster(img, edge)
      val adaptive = (cls[file.name] ?: "").contains("Adaptive")
      val isFlat = flat.contains(file.name)
      val under = File(file.parentFile, file.name.removeSuffix(".png") + "_modelbg.png")
        .takeIf { it.isFile }?.let { raster(ImageIO.read(it), edge) }
      // 与产品同路：窗口（≤1.82）决定 `plateSubject`，其余用 `plateLayer`（只给前景层那支）
      val win = IconCropWindow.of(px, edge, edge)?.takeIf { it.zoom <= 1.82f }
      val plateLayer = adaptive && !isFlat
      val plateSubject = win != null && win.plate && win.keepPct >= 50
      val mask = runCatching { MonochromeMask.build(px, edge, edge, plateLayer, under, plateSubject).mask }
        .getOrNull() ?: continue
      n++
      var faintN = 0
      for (i in mask.indices) { val a = (mask[i] ushr 24) and 0xff; if (a in 16..112) faintN++ }
      // 每个 alpha 桶（16 宽，桶 1..7 = alpha 16..127）里找最大 4 连通块（显式栈，384² 会爆递归）
      val seen = BooleanArray(edge * edge)
      val stack = IntArray(edge * edge)
      var bestArea = 0; var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0; var bestLvl = 0; var bestSrc = 0
      for (b in 1..7) {
        java.util.Arrays.fill(seen, false)
        for (s in 0 until edge * edge) {
          if (seen[s] || (((mask[s] ushr 24) and 0xff) shr 4) != b) continue
          var sp = 0; stack[sp++] = s; seen[s] = true
          var area = 0; var x0 = edge; var x1 = -1; var y0 = edge; var y1 = -1
          var lLo = 255; var lHi = 0
          while (sp > 0) {
            val cur = stack[--sp]; val cy = cur / edge; val cx = cur % edge
            area++
            if (cx < x0) x0 = cx; if (cx > x1) x1 = cx; if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
            val lu = luma(px[cur]); if (lu < lLo) lLo = lu; if (lu > lHi) lHi = lu
            if (cx > 0) { val i = cur - 1; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i } }
            if (cx < edge - 1) { val i = cur + 1; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i } }
            if (cy > 0) { val i = cur - edge; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i } }
            if (cy < edge - 1) { val i = cur + edge; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i } }
          }
          if (area > bestArea) {
            bestArea = area; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1
            bestLvl = b * 16; bestSrc = lHi - lLo
          }
        }
      }
      val areaPct = bestArea * 100.0 / (edge * edge)
      val verdict = when {
        areaPct < minPct -> "干净"
        bestSrc <= srcFlatMax -> "**底=淡墨**"
        else -> "插画（源有花纹）"
      }
      if (verdict.startsWith("**底")) bad++ else if (verdict != "干净") ill++
      if (bestArea > 0) hist[bestLvl / 16] += areaPct
      println("%-46s %-6s %-6s %-7s %-7s %-8s %s%s".format(
        file.name.take(46), "%.0f%%".format(areaPct), bestLvl,
        "%.0f%%".format(bestLvl * 100.0 / 255), bestSrc,
        "%.1f%%".format(faintN * 100.0 / (edge * edge)), verdict,
        if (bestArea > 0) " ←（%dx%d 那块）".format(bx1 - bx0 + 1, by1 - by0 + 1) else ""))
    }
  }
  println("共 $n 张；**「底=淡墨」$bad 张**；「插画」$ill 张；" +
    "门槛 面积≥$minPct% 源极差≤$srcFlatMax")
  println("—— 「淡块 α」面积加权分布（**定死区那条线就靠它**）——")
  var acc = 0.0
  for (i in 0 until 16) if (hist[i] > 0) {
    acc += hist[i]
    println("  α %3d..%3d : %6.1f 点（累计 %.1f）".format(i * 16, i * 16 + 15, hist[i], acc))
  }
}

/** 感知亮度（0..255，只用来看"源那一块是不是恒定"，不当判据）。 */
private fun luma(argb: Int): Int {
  val r = (argb shr 16) and 0xff; val g = (argb shr 8) and 0xff; val b = argb and 0xff
  return (r * 77 + g * 151 + b * 28) shr 8
}

private fun raster(img: java.awt.image.BufferedImage, edge: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
    px[y * edge + x] = s0[sy * img.width + sx]
  }
  return px
}
