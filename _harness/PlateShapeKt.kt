package harness

import android.graphics.Rect
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * **探针（只量不改）**：角上选出来的每一族"底"，**自己是什么形状** —— 整幅的盘，还是一张贴在边上的窄条。
 *
 * 为什么换这一根轴（接 KNOWN-LIMITS 末尾）：
 *  「哪块是底」那堵墙撞了十次（八次颜色/彩度/围合/面积/边数，两次几何）。这一台子不再问"谁是谁的底"，
 *  改问一个**不问对手**的问题：**这一族自己铺不铺满整张**。理由来自淘宝新那张的图本身 ——
 *  橙方块的下面贴了一条通宽的白横幅（横幅压着缎带尖），于是白族**确实**够得着内容框三条边，
 *  洪泛把它当底在拓扑上没有错；错的是"够得着边"这一条被当成了"铺满整张"的证据。
 *  盘的定义是**承载面**：它的高宽比接近 1、它盖住框的大部分高度；横幅是 6:1、只盖 15% 高。
 *  这一列之前没有任何一台子量过。
 *
 * 口径（⚠ 族与 plate 都读**生产自己的**，不另算一套）：
 *  - 反射驱动 [MonochromeMask] 的 `contentBounds` + `groundPass`（和 [RimDbg] 同一套搭前置：
 *    `grid` / `ensure` / Lab 预算，少一样 `ref` 就填不上、洪泛自己挤爆队列）。
 *    读回来的就是 `cornerFamilies` 选出的那 ≤4 族（`s.famColor`）与它认的底（`s.plate`）。
 *  - 一族的格子 = 内容框内 α=255 且 `ΔE76(格, 族色) ≤ CANDIDATE_DISTINCT` —— 用生产自己那条"互异线"，
 *    不引入新的量化（⚠ 这台子刻意**不用** 4 位色族：族色是逐通道中位色，4 位键会把 AA 混色分出去）。
 *  - 同族四邻连通成组件；**碰边界带**（生产那条 `inBand`，带宽 = 短边/24 夹在 2..8）的那一块才是
 *    "它凭什么当底"的那一块 —— 被围住的块（字形）不碰带，与"当底"这件事无关。
 *  - 主列 = 碰带那一块的 **宽高比** 与 **高占框%**、面积占框%、贴了几条边。
 *
 * `--calib` 先造一张必定该被看见的合成图（整幅方盘 + 通宽底栏 + 盘上字形）当正对照：
 *  量具若不在那一块上读出 6:1 / 15%，这台子就是没电，语料的数不算。
 *
 * 跑法：`java -cp out;stubs_out;<stdlib> harness.PlateShapeKtKt <图标目录> [--edge=384] [--calib] [--all]`
 */
private val MM = MonochromeMask::class.java
private val SCRATCH: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Scratch")

private fun fld(owner: Class<*>, name: String): Field =
  owner.getDeclaredField(name).also { it.isAccessible = true }

/** 按名字**和参数个数**取方法：反射调用编译期查不出来，`contentBounds` 加第 4 个参数那天，
 *  仓里七台靠反射的子台当场全废（RimDbg 2026-11-06 撞上，报 "wrong number of arguments"）。 */
private fun met(owner: Class<*>, name: String, argc: Int): Method {
  val cands = owner.declaredMethods.filter { it.name == name && !it.isSynthetic }
  val m = cands.firstOrNull { it.parameterCount == argc }
  if (m == null) {
    println("!! `$name` 的参数个数变了（台子按 $argc 个找，实际是 ${cands.joinToString { it.parameterCount.toString() }}）—— 这一台子没电")
    exitProcess(3)
  }
  m.isAccessible = true
  return m
}

private fun const(name: String): Int = fld(MM, name).getInt(null)

fun main(args: Array<String>) {
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=") ?: "384").toInt()
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val all = args.contains("--all")
  // 第四道门摆在哪，要先看分布才准拍：`--dmin=0` = 只过前三道门，把 ΔE 那一列整串摊出来。
  // 默认 70 = 与生产 `STRIP_DISTINCT_DE` 同一条线（41/47 一侧是同一块盘与主体，105/107 才是贴上去的）。
  val dMin = (args.firstOrNull { it.startsWith("--dmin=") }?.substringAfter("=") ?: "70").toDouble()

  val jobs = ArrayList<Pair<String, IntArray>>()
  if (args.contains("--calib")) jobs += "★标定：整幅方盘+通宽底栏+盘上字形" to calib(edge)
  if (dir.isDirectory) {
    for (c in cases(dir, edge, inset = false, fgAll = false)) {
      if (c.src.size != edge * edge) continue
      jobs += c.file.name.removeSuffix(".png").take(34) to c.src
    }
  } else if (!args.contains("--calib")) {
    println("!! 目录不存在：${dir.path}"); exitProcess(2)
  }

  val distinct = const("CANDIDATE_DISTINCT")
  val rows = ArrayList<String>()
  var stripHits = 0
  var stripH1 = 0
  for ((title, src) in jobs) {
    val box = met(MM, "contentBounds", 4).invoke(MonochromeMask, src, edge, edge, null) as? Rect
      ?: continue
    val short = min(box.width(), box.height())
    val band = (short / const("RING_BAND_DIV")).coerceIn(2, 8)
    val g = if (short >= const("FIELD_MIN_SIDE")) const("FIELD_GRID") else const("FIELD_GRID_MIN")
    val s = SCRATCH.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
    fld(SCRATCH, "grid").setInt(s, g)
    met(SCRATCH, "ensure", 2).invoke(s, edge * edge, g * g)
    val labL = fld(SCRATCH, "labL").get(s) as ShortArray
    val labA = fld(SCRATCH, "labA").get(s) as ShortArray
    val labB = fld(SCRATCH, "labB").get(s) as ShortArray
    for (i in src.indices) {
      labL[i] = MonochromeMask.labChannel(src[i], 0).toShort()
      labA[i] = MonochromeMask.labChannel(src[i], 1).toShort()
      labB[i] = MonochromeMask.labChannel(src[i], 2).toShort()
    }
    val gp = met(MM, "groundPass", 9).invoke(MonochromeMask, src, edge, edge, box, band, g, s, false, false)
    val plate = fld(SCRATCH, "plate").get(s) as ByteArray
    // ⚠ 家族数**不许读 `Scratch.fams`**（仓里 5969 行那条老教训：那是上一个假设留下的 ——
    //   H0「区域关系」与 H2「轮廓族」都会把 `s.fams` 改写成 1，读它会把两支的族色混着一支的族数打出来）。
    //   接手那一支自己报的数在 `Pass.families` 上。
    val fams = fld(Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Pass"), "families").getInt(gp)
    val hyp = fld(Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Pass"), "hyp").getInt(gp)
    val famColor = fld(SCRATCH, "famColor").get(s) as IntArray
    val bandSolid = fld(SCRATCH, "bandSolid").getInt(s)
    if (fams == 0) { rows += "$title  H$hyp 族=0（带占${bandSolid * 100 / 255}，没有角族）"; continue }

    val bw = box.width(); val bh = box.height(); val boxN = bw.toLong() * bh
    var strips = 0
    val parts = ArrayList<String>()
    for (j in 0 until fams) {
      val fc = labOf(famColor[j] and 0xFFFFFF)
      // 这一族的格子
      val inFam = BooleanArray(edge * edge)
      for (y in box.top until box.bottom) {
        val row = y * edge
        for (x in box.left until box.right) {
          val i = row + x
          if (((src[i] ushr 24) and 0xff) != 255) continue
          val l = labOf(src[i] and 0xFFFFFF)
          if (de2(fc, l) <= distinct.toLong() * distinct) inFam[i] = true
        }
      }
      // 四邻连通，找**碰边界带**的那一块
      val seen = BooleanArray(edge * edge)
      val q = IntArray(edge * edge)
      var bestA = -1.0; var bestH = 0; var bestW = 0; var bestN = 0; var bestSides = 0
      var strip = false; var sW = 0; var sH = 0; var sN = 0; var bandN = 0
      for (y in box.top until box.bottom) {
        val row = y * edge
        for (x in box.left until box.right) {
          val i0 = row + x
          if (!inFam[i0] || seen[i0]) continue
          var head = 0; var tail = 0
          q[tail++] = i0; seen[i0] = true
          var n = 0; var l0 = edge; var r0 = -1; var t0 = edge; var b0 = -1
          var touch = false; var sb = 0
          while (head < tail) {
            val k2 = q[head++]
            val ky = k2 / edge; val kx = k2 - ky * edge
            n++
            if (kx < l0) l0 = kx; if (kx > r0) r0 = kx
            if (ky < t0) t0 = ky; if (ky > b0) b0 = ky
            if (kx - box.left < band || box.right - kx <= band || ky - box.top < band || box.bottom - ky <= band) {
              touch = true; bandN++
            }
            if (kx == box.left) sb = sb or 1
            if (kx == box.right - 1) sb = sb or 2
            if (ky == box.top) sb = sb or 4
            if (ky == box.bottom - 1) sb = sb or 8
            if (kx > box.left && inFam[k2 - 1] && !seen[k2 - 1]) { seen[k2 - 1] = true; q[tail++] = k2 - 1 }
            if (kx < box.right - 1 && inFam[k2 + 1] && !seen[k2 + 1]) { seen[k2 + 1] = true; q[tail++] = k2 + 1 }
            if (ky > box.top && inFam[k2 - edge] && !seen[k2 - edge]) { seen[k2 - edge] = true; q[tail++] = k2 - edge }
            if (ky < box.bottom - 1 && inFam[k2 + edge] && !seen[k2 + edge]) { seen[k2 + edge] = true; q[tail++] = k2 + edge }
          }
          if (!touch) continue
          val cw = r0 - l0 + 1; val ch = b0 - t0 + 1
          val a = max(cw, ch).toDouble() / min(cw, ch)
          // **落码那一条判据**（与 MonochromeMask 里 `STRIP_*` 逐字同口径）：碰带这一块
          //   通宽 ≥95% 框、高 ≤30% 框、面积 ≥1% 框。宽高比不用单独问 —— 95/30 本身就 ⇒ 比 ≥3.17。
          if (cw * 100 >= bw * 95 && ch * 100 <= bh * 30 && n * 100L >= boxN) {
            strip = true
            // 报数报**过门那一块**，不是最大宽高比那一块：一个族可以既有满幅的浅底、
            // 又有一条通宽的深色带（浙江在线就是这两块都有），报错了块后面没法核。
            if (n > sN) { sW = cw; sH = ch; sN = n }
          }
          if (a > bestA) { bestA = a; bestH = ch; bestW = cw; bestN = n; bestSides = sb }
        }
      }
      // 这一族在模型眼里到底是不是底（`plate=1` 占全族格子的比例）—— 不是底就没有"退出底"可谈
      var gp = 0; var gPlate = 0
      for (y in box.top until box.bottom) {
        val row = y * edge
        for (x in box.left until box.right) {
          val i = row + x
          if (!inFam[i]) continue
          gp++
          if (plate[i] != 0.toByte()) gPlate++
        }
      }
      if (bestN == 0) { parts += "族$j#${hex(famColor[j])} 无碰带块(全族$gp)"; continue }
      // 第四道门（zjol 教的）：这一族与**剩下的那一族**差多少。浙江在线的两段蓝是同一块盘的深浅
      // （ΔE 35），淘宝的白带与橙盘是两个东西（ΔE 100）—— 只有后者才是"贴上去的"。
      var dOther = 999.0
      for (o in 0 until fams) if (o != j) {
        val d = sqrt(de2(labOf(famColor[j] and 0xFFFFFF), labOf(famColor[o] and 0xFFFFFF)).toDouble())
        if (d < dOther) dOther = d
      }
      if (strip && dOther <= dMin) strip = false
      if (strip) strips++
      parts += "族$j#${hex(famColor[j])} 碰带块 宽%d%% 高%d%% 面积%d%% 贴%d边 在底%d%% ΔE%.0f%s".format(
        (if (sN > 0) sW else bestW) * 100 / bw, (if (sN > 0) sH else bestH) * 100 / bh,
        (if (sN > 0) sN else bestN) * 100 / boxN, Integer.bitCount(bestSides),
        gPlate * 100 / gp.coerceAtLeast(1), dOther, if (strip) " ⇒横幅" else "")
      if (all) parts += "(碰带块$bestN/全族$gp)"
    }
    rows += "%-36s H%d 族%d 框%dx%d 带占%3d%% │ %s".format(
      title, hyp, fams, bw, bh, bandSolid * 100 / 255, parts.joinToString(" │ "))
    if (strips > 0 && fams > 1) { stripHits++; if (hyp == 1) stripH1++ }
  }
  for (r in rows) println(r)
  println()
  println("有 ≥2 族、且至少一族是「横幅」（碰带那块 通宽≥95% 且 高≤30% 且 面积≥1% 框，" +
    "且与另一族 ΔE>40）的：$stripHits / ${rows.size} 张，其中走角族那一支（H1）的 $stripH1 张")
}

/** 正对照：整幅方盘（蓝）+ 通宽底栏（白，贴在框下沿）+ 盘上白字形。
 *  按定义那一族白**既碰三条边、又和字形同色**，正是淘宝的形状；量具必须在这里读出「窄条」。 */
private fun calib(edge: Int): IntArray {
  val p = IntArray(edge * edge)
  val m = edge / 12
  for (y in m until edge - m - edge / 5) for (x in m until edge - m)
    p[y * edge + x] = (255 shl 24) or 0x2E63C8
  for (y in edge - m - edge / 5 until edge - m) for (x in m until edge - m)
    p[y * edge + x] = (255 shl 24) or 0xFFFFFF
  // 盘上的字形：一个白方块，被蓝围着
  for (y in edge / 3 until edge / 2) for (x in edge / 3 until edge / 2) p[y * edge + x] = -0x1
  return p
}

private fun hex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

private fun de2(a: DoubleArray, b: DoubleArray): Long {
  val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
  return (dl * dl + da * da + db * db).toLong()
}

private fun labOf(p: Int): DoubleArray {
  fun s(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
  val r = s((p shr 16) and 0xff); val g = s((p shr 8) and 0xff); val b = s(p and 0xff)
  var x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
  var y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
  var z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
  fun f(v: Double) = if (v > 0.008856) Math.cbrt(v) else 7.787 * v + 16.0 / 116.0
  x = f(x); y = f(y); z = f(z)
  return doubleArrayOf(116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z))
}
