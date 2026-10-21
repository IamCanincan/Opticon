package harness

import android.graphics.Rect
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * **格级诊断**：反射驱动 `groundPass`，在**一遍跑完、二遍还没跑**的时候把每格的
 * `fCov`（参照平均 α）、`fTop`（参照最高 α）、参照 α 的 air/mid/solid 分段与 sub-solid 段均值读出来。
 *
 * 为什么非要自己驱动：`fCov`/`fTop` 会被 `bodyPass` 的 `fitField`（solidRef=true）整个覆写成 255，
 * `build` 返回后再读只会读到一片 255。判据行与掩码都只能看出"多了一条痕"，
 * 看不出**痕所在那一格参照长什么样**；修法要选的统计量只有在格子上才分得出来。
 *
 * 用法: `CellDumpKt <png> … [--cell=cx,cy] [--min=64]`
 */
private val MM = MonochromeMask::class.java
private val SCRATCH: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Scratch")

private fun fieldOf(owner: Class<*>, name: String): Field =
  owner.getDeclaredField(name).also { it.isAccessible = true }

private fun methodOf(owner: Class<*>, name: String): Method =
  owner.declaredMethods.first { it.name == name && !it.isSynthetic }.also { it.isAccessible = true }

private fun constOf(name: String): Int = fieldOf(MM, name).getInt(null)

private class CellSnap(val s: Any) {
  fun i(name: String): IntArray = fieldOf(SCRATCH, name).get(s) as IntArray
  fun i0(name: String): Int = fieldOf(SCRATCH, name).getInt(s)
}

fun main(args: Array<String>) {
  val wantCell = args.firstOrNull { it.startsWith("--cell=") }?.substringAfter("=")
    ?.split(",")?.map { it.toInt() }
  val minDiff = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 64
  for (f in args.filter { !it.startsWith("--") }) {
    val img = ImageIO.read(File(f))
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val box = methodOf(MM, "contentBounds").invoke(MonochromeMask, px, w, h) as? Rect
    if (box == null) { println("${File(f).name}: 整张透明"); continue }
    val short = min(box.width(), box.height())
    val band = (short / constOf("RING_BAND_DIV")).coerceIn(2, 8)
    val g = if (short >= constOf("FIELD_MIN_SIDE")) constOf("FIELD_GRID") else constOf("FIELD_GRID_MIN")
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
    methodOf(MM, "groundPass").invoke(MonochromeMask, px, w, h, box, band, g, s)
    val sn = CellSnap(s)
    val cov = sn.i("fCov").copyOf(); val top = sn.i("fTop").copyOf()
    val tot = sn.i("cellTot").copyOf(); val cn = sn.i("cellN").copyOf()
    val plate = (fieldOf(SCRATCH, "plate").get(s) as ByteArray).copyOf()
    val ref = (fieldOf(SCRATCH, "ref").get(s) as IntArray).copyOf()
    // ⚠ `fieldHist` / `fCov` / `fTop` 是**最后一次** fitField 留下的，而 groundPass 会依次试
    //   H1/H2/H0/H3 各跑一次 fitGround —— 被否掉那几支的格子会留在里面。所以**只信 plate**：
    //   按 groundPass 最终留下的 `plate` 自己去数每格的参照 α 分布，这一份不会骗人。
    val pw = IntArray(g * g); val pAir = IntArray(g * g); val pMid = IntArray(g * g)
    val pSol = IntArray(g * g); val pTopA = IntArray(g * g); val pSubS = LongArray(g * g)
    val bins = Array(g * g) { IntArray(8) }   // [0,32) [32,64) [64,96) [96,128) [128,160) [160,192) [192,224) [224,256)
    for (y in box.top until box.bottom) {
      val row = y * w
      val cy = ((y - box.top) * g / box.height()).coerceAtMost(g - 1)
      for (x in box.left until box.right) {
        val i = row + x
        if (plate[i] == 0.toByte()) continue
        val cx = ((x - box.left) * g / box.width()).coerceAtMost(g - 1)
        val k = cy * g + cx
        val a = (px[i] ushr 24) and 0xff
        pw[k]++
        bins[k][(a / 32).coerceAtMost(7)]++
        if (a > pTopA[k]) pTopA[k] = a
        when {
          a >= 224 -> pSol[k]++
          a >= 32 -> { pMid[k]++; pSubS[k] += a.toLong() }
          else -> { pAir[k]++; pSubS[k] += a.toLong() }
        }
      }
    }
    println("=== ${File(f).name}  ${w}x$h  grid=$g ===")

    if (wantCell != null) {
      val k = wantCell[0] * g + wantCell[1]
      val subN = pAir[k] + pMid[k]
      println("  cell(${wantCell[0]},${wantCell[1]}) 参照=${pw[k]} 最高α=${pTopA[k]}" +
        " air=${pAir[k]} mid=${pMid[k]} solid=${pSol[k]}" +
        " subMean=${if (subN == 0) 0 else pSubS[k] / subN}")
      println("    分段 " + (0 until 8).joinToString(" ") { "${it * 32}:${bins[k][it]}" })
      continue
    }
    var shown = 0
    println("  格子 (cx,cy)  参照  最高α   air   mid solid subMean  |  0..32 32..64 64..96 96..128 128..160 160..192 192..224 224..")
    for (cy in 0 until g) for (cx in 0 until g) {
      val k = cy * g + cx
      if (pw[k] == 0) continue
      val subN = pAir[k] + pMid[k]
      println("   (%d,%d)  %6d %5d %5d %5d %5d %6d  |%s".format(
        cx, cy, pw[k], pTopA[k], pAir[k], pMid[k], pSol[k],
        if (subN == 0) 0 else pSubS[k] / subN,
        (0 until 8).joinToString("") { "%7d".format(bins[k][it]) }))
      shown++
    }
    println("  分家的格子 $shown / ${g * g}")
  }
}
