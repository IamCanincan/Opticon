package harness

import android.graphics.Rect
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **底场格子的两件事：① 哪几格是真双峰；② 若"每像素按自己的峰取参照"，`零` 会掉到多少**（只量不改）。
 *
 * 为什么是这一件（2026-10-13）：知到那条「中调被压」的根写在 `MonochromeMask` 的注释里 ——
 * 参照像素是两堆颜色，**中位落在两堆之间**，两边都离这个"图上不存在的颜色"很远 →
 * `departure(withCoverage=false)` 的 p97（= `零`）被抬到 115（`DEP_TOL_MAX` 顶格）→ `dep=129` 只映射到 `t=25`。
 *
 * 已经量死的两版：宽收（2026-10-01，按 ΔE≤`SEAM_DIVERGE` 重取中位）面太宽；
 * `cand/Bimodal`（只碰真双峰、把中位挪到主峰）**`零` 一格没降** —— 把中位挪到主峰后**另一族立刻成新尾巴**。
 * 这一版问的是那句结论：**如果每格能同时代表两族**（每个像素用它自己那一峰的参照），`零` 会不会真掉下来。
 * 掉 → 那句"参照分辨率不够"有了数、可以去动 `fitField`；不掉 → 这条路也堵死，别再动结构。
 *
 * ⚠ 口径说明：`零` 在模型里是**每个像素的实际参照格**（`propagateField` 之后 `ref[i]`）上的散度 p97，
 * 这里用**几何格**近似 —— 两个变体（现参照 / 峰参照）用的是同一个几何格，比较仍然成立；
 * 绝对值会与判据行差一点（输出里把两者都印出来，便于对账）。
 *
 * 用法：`FieldMixKt <目录|图 …> [--dive]`
 */
private val MM = MonochromeMask::class.java
private val SCRATCH: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Scratch")

private fun fieldOf(owner: Class<*>, name: String) =
  owner.getDeclaredField(name).also { it.isAccessible = true }

private fun methodOf(owner: Class<*>, name: String): Method =
  owner.declaredMethods.first { it.name == name && !it.isSynthetic }.also { it.isAccessible = true }

private fun constOf(name: String): Int = fieldOf(MM, name).getInt(null)

fun main(args: Array<String>) {
  val dive = args.any { it == "--dive" }
  val aSolid = constOf("A_SOLID")
  val emax = constOf("EMAX")
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("_modelbg") &&
      !it.name.contains("_mono") && !it.name.contains("_bg") }.sortedBy { it.name } else listOf(f)
  }
  var nImg = 0; var nCell = 0; var nBi = 0
  println("=== 底场格子：两峰/谷深 + 「每像素按自己的峰取参照」时 `零` 会掉到多少 · ${files.size} 张 ===")
  println("%-42s %5s %5s %6s %7s %7s %7s".format("图标", "格数", "双峰", "最浅谷", "零(现)", "零(峰)", "Δ"))
  for (f in files) {
    if (!f.exists()) continue
    val img = ImageIO.read(f) ?: continue
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val box = methodOf(MM, "contentBounds").invoke(MonochromeMask, px, w, h) as? Rect ?: continue
    val short = min(box.width(), box.height())
    val g = if (short >= constOf("FIELD_MIN_SIDE")) constOf("FIELD_GRID") else constOf("FIELD_GRID_MIN")
    val band = (short / constOf("RING_BAND_DIV")).coerceIn(2, 8)
    val s = SCRATCH.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
    fieldOf(SCRATCH, "grid").setInt(s, g)
    methodOf(SCRATCH, "ensure").invoke(s, w * h, g * g)
    val labL = fieldOf(SCRATCH, "labL").get(s) as ShortArray
    val labA = fieldOf(SCRATCH, "labA").get(s) as ShortArray
    val labB = fieldOf(SCRATCH, "labB").get(s) as ShortArray
    for (i in px.indices) {
      labL[i] = MonochromeMask.labChannel(px[i], 0).toShort()
      labA[i] = MonochromeMask.labChannel(px[i], 1).toShort()
      labB[i] = MonochromeMask.labChannel(px[i], 2).toShort()
    }
    methodOf(MM, "groundPass").invoke(MonochromeMask, px, w, h, box, band, g, s, false)
    val plate = (fieldOf(SCRATCH, "plate").get(s) as ByteArray)
    val cells = g * g
    // ① 每格：α≥A_SOLID 的颜色直方图 + 明度直方图 + 全部参照像素的 α 和与格数（β 与模型同口径）
    val rgbH = Array(cells) { IntArray(768) }
    val lh = Array(cells) { IntArray(101) }
    val cntCol = IntArray(cells)
    val tot = IntArray(cells); val sumA = LongArray(cells)
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (plate[i] == 0.toByte()) continue
        val a = (px[i] ushr 24) and 0xff
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val k = cy * g + cx
        tot[k]++; sumA[k] += a
        if (a < aSolid) continue
        cntCol[k]++; lh[k][(labL[i].toInt() shr 6).coerceIn(0, 100)]++
        rgbH[k][(px[i] shr 16) and 0xff]++; rgbH[k][256 + ((px[i] shr 8) and 0xff)]++; rgbH[k][512 + (px[i] and 0xff)]++
      }
    }
    // ② 每格中位 → Lab；两峰 + 谷深 → 双峰标志
    val mL = IntArray(cells); val mA = IntArray(cells); val mB = IntArray(cells)
    val pA = IntArray(cells); val pB = IntArray(cells)     // 两峰的 L；-1 = 非双峰
    for (k in 0 until cells) {
      if (cntCol[k] == 0) continue
      val rgb = (0xff shl 24) or ((mid(rgbH[k], 0, cntCol[k])) shl 16) or
        ((mid(rgbH[k], 256, cntCol[k])) shl 8) or mid(rgbH[k], 512, cntCol[k])
      mL[k] = MonochromeMask.labChannel(rgb, 0); mA[k] = MonochromeMask.labChannel(rgb, 1); mB[k] = MonochromeMask.labChannel(rgb, 2)
      val n = cntCol[k]; val minPeak = maxOf(4, n / 10)
      var p1 = 0
      for (v in 0..100) if (lh[k][v] > lh[k][p1]) p1 = v
      pA[k] = -1; pB[k] = -1
      if (lh[k][p1] >= minPeak) {
        var valley = 1.0; var b2 = -1
        for (p2 in 0..100) {
          if (abs(p2 - p1) < 12 || lh[k][p2] < minPeak) continue
          var dip = Int.MAX_VALUE
          for (t in minOf(p1, p2)..maxOf(p1, p2)) if (lh[k][t] < dip) dip = lh[k][t]
          val r = dip.toDouble() / minOf(lh[k][p1], lh[k][p2])
          if (r < valley) { valley = r; b2 = p2 }
        }
        if (valley <= 0.6) { pA[k] = p1; pB[k] = b2 }
      }
    }
    // ③ 每个参照像素：现参照（格中位）与峰参照（自己那一峰的均值色）两条 ΔE
    val pkR = HashMap<Int, LongArray>()   // key = k*2 + slot → [rSum,gSum,bSum,n]
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (plate[i] == 0.toByte()) continue
        if (((px[i] ushr 24) and 0xff) < aSolid) continue
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val k = cy * g + cx
        if (pA[k] < 0) continue
        val l = labL[i].toInt() shr 6
        val slot = if (abs(l - pA[k]) <= abs(l - pB[k])) 0 else 1
        val acc = pkR.getOrPut(k * 2 + slot) { LongArray(4) }
        acc[0] += (px[i] shr 16) and 0xff; acc[1] += (px[i] shr 8) and 0xff; acc[2] += px[i] and 0xff; acc[3]++
      }
    }
    val pkLab = HashMap<Int, IntArray>()
    for ((key, acc) in pkR) if (acc[3] > 0) {
      val rgb = (0xff shl 24) or ((acc[0] / acc[3]).toInt() shl 16) or ((acc[1] / acc[3]).toInt() shl 8) or (acc[2] / acc[3]).toInt()
      pkLab[key] = intArrayOf(MonochromeMask.labChannel(rgb, 0), MonochromeMask.labChannel(rgb, 1), MonochromeMask.labChannel(rgb, 2))
    }
    val dCur = IntArray(256); val dPk = IntArray(256)
    var nG = 0
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (plate[i] == 0.toByte()) continue
        val a = (px[i] ushr 24) and 0xff
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val k = cy * g + cx
        val beta = if (tot[k] == 0) 255 else (sumA[k] / tot[k]).toInt()
        val dc = delta(labL[i].toInt(), labA[i].toInt(), labB[i].toInt(), mL[k], mA[k], mB[k])
        var dp = dc
        if (pA[k] >= 0 && a >= aSolid) {
          val l = labL[i].toInt() shr 6
          val slot = if (abs(l - pA[k]) <= abs(l - pB[k])) 0 else 1
          val pl = pkLab[k * 2 + slot]
          if (pl != null) dp = delta(labL[i].toInt(), labA[i].toInt(), labB[i].toInt(), pl[0], pl[1], pl[2])
        }
        dCur[(a * beta * cf(dc, emax) / 65025).coerceIn(0, 255)]++
        dPk[(a * beta * cf(dp, emax) / 65025).coerceIn(0, 255)]++
        nG++
      }
    }
    if (nG == 0) continue
    var nc = 0; var nb = 0; var worstValley = 1.0
    for (k in 0 until cells) {
      if (cntCol[k] < 64) continue
      nc++
      if (pA[k] >= 0) { nb++ }
    }
    for (k in 0 until cells) {
      if (cntCol[k] < 64 || pA[k] < 0) continue
      val n = cntCol[k]; val minPeak = maxOf(4, n / 10)
      for (p2 in 0..100) {
        if (abs(p2 - pA[k]) < 12 || lh[k][p2] < minPeak) continue
        var dip = Int.MAX_VALUE
        for (t in minOf(pA[k], p2)..maxOf(pA[k], p2)) if (lh[k][t] < dip) dip = lh[k][t]
        val r = dip.toDouble() / minOf(lh[k][pA[k]], lh[k][p2])
        if (r < worstValley) worstValley = r
      }
    }
    if (nc == 0) continue
    nImg++; nCell += nc; nBi += nb
    val zc = p97(dCur, nG); val zp = p97(dPk, nG)
    println("%-42s %5d %5d %6.2f %7d %7d %7d".format(
      f.nameWithoutExtension.take(42), nc, nb, worstValley, zc, zp, zp - zc))
    if (dive && nb > 0) {
      println("     双峰的格：$nb / $nc  —— 「峰参照」把 `零` 从 $zc 拉到 $zp")
    }
  }
  println()
  println("=== $nImg 张 / $nCell 格；双峰的格 = $nBi")
  println("零(现) = 用「格中位」当参照时，参照像素 dep 的 p97；零(峰) = 每个像素用它**自己那一峰**的均值色当参照。")
  println("（两者用同一个几何格近似 ref[i]；掉得多 = 那句「参照分辨率不够」成立，值得动 fitField。）")
}

private fun mid(h: IntArray, at: Int, n: Int): Int {
  val half = (n + 1) / 2
  var run = 0
  for (i in 0 until 256) { run += h[at + i]; if (run >= half) return i }
  return 255
}

private fun p97(h: IntArray, n: Int): Int {
  val want = maxOf(1, (n.toLong() * 97 / 100).toInt())
  var acc = 0
  for (i in h.indices) { acc += h[i]; if (acc >= want) return i }
  return h.lastIndex
}

private fun cf(de: Int, emax: Int): Int = if (de >= emax) 255 else de * 255 / emax

private fun delta(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
  val dl = (l1 - l2) / 64.0; val da = (a1 - a2) / 64.0; val db = (b1 - b2) / 64.0
  return sqrt(dl * dl + da * da + db * db).toInt()
}
