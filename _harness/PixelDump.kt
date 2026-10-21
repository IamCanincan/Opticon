package harness

import android.graphics.Rect
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * **像素级诊断**：反射驱动 `groundPass`，把某块矩形里每个像素的内部状态逐行打出来 ——
 * `plate` / `pass` / `ref`（背后那块底属于哪个格子）/ 该格的 `fCov`、`fTop`、家族色 Lab /
 * `dist` / `beta` / `core`（`absorbSeams` 的种子）。
 *
 * 为什么非要到像素级：判据行与 ASCII 只能看出"多了一条痕"，格子级只能看出"这一格参照杂"。
 * 而"这条痕为什么没被吸收"这个问题只有在**单像素**上才回答得了 ——
 * 比如接缝吸收要求"相反两向都是底、且两边的格子色差得开"，而 8×8 网格里
 * 痕的两侧**可能落在同一个格子**，ΔE 恒为 0，于是永远不会被吸收。
 *
 * 用法: `PixelDumpKt <png> --y0=56 --y1=60 --x0=160 --x1=172`
 */
private val MM2 = MonochromeMask::class.java
private val SCR2: Class<*> = Class.forName("com.iamcanincan.opticon.circle.MonochromeMask\$Scratch")

private fun f2(owner: Class<*>, name: String): Field =
  owner.getDeclaredField(name).also { it.isAccessible = true }

private fun m2(owner: Class<*>, name: String): Method =
  owner.declaredMethods.first { it.name == name && !it.isSynthetic }.also { it.isAccessible = true }

fun main(args: Array<String>) {
  fun arg(name: String, def: Int) =
    args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")?.toInt() ?: def
  val f = File(args.first { !it.startsWith("--") })
  val img = ImageIO.read(f)
  val w = img.width; val h = img.height
  val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
  val box = m2(MM2, "contentBounds").invoke(MonochromeMask, px, w, h) as? Rect
    ?: run { println("整张透明"); return }
  val short = min(box.width(), box.height())
  val band = (short / f2(MM2, "RING_BAND_DIV").getInt(null)).coerceIn(2, 8)
  val g = if (short >= f2(MM2, "FIELD_MIN_SIDE").getInt(null))
    f2(MM2, "FIELD_GRID").getInt(null) else f2(MM2, "FIELD_GRID_MIN").getInt(null)
  val s = SCR2.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
  f2(SCR2, "grid").setInt(s, g)
  m2(SCR2, "ensure").invoke(s, w * h, g * g)
  val labL = f2(SCR2, "labL").get(s) as ShortArray
  val labA = f2(SCR2, "labA").get(s) as ShortArray
  val labB = f2(SCR2, "labB").get(s) as ShortArray
  for (i in px.indices) {
    labL[i] = MonochromeMask.labChannel(px[i], 0).toShort()
    labA[i] = MonochromeMask.labChannel(px[i], 1).toShort()
    labB[i] = MonochromeMask.labChannel(px[i], 2).toShort()
  }
  // ⚠ 第 8 个参数 `plateIsLayer` 是 2026-10-09 加进 `groundPass` 的 —— 反射调用**编译期查不出来**，
  //   漏了它这台子直接抛 "wrong number of arguments"（2026-10-10 抓 转转"不"字没了时才撞上）。
  m2(MM2, "groundPass").invoke(MonochromeMask, px, w, h, box, band, g, s, false)

  val plate = f2(SCR2, "plate").get(s) as ByteArray
  val pass = f2(SCR2, "pass").get(s) as ByteArray
  val ref = f2(SCR2, "ref").get(s) as IntArray
  val core = f2(SCR2, "grown").get(s) as ByteArray
  val dist = f2(SCR2, "dist").get(s) as ShortArray
  val beta = f2(SCR2, "beta").get(s) as ShortArray
  val fCov = f2(SCR2, "fCov").get(s) as IntArray
  val fTop = runCatching { f2(SCR2, "fTop").get(s) as IntArray }.getOrElse { IntArray(0) }
  val fL = f2(SCR2, "fL").get(s) as IntArray
  val fA = f2(SCR2, "fA").get(s) as IntArray
  val fB = f2(SCR2, "fB").get(s) as IntArray

  val y0 = arg("y0", box.top); val y1 = arg("y1", box.bottom)
  val x0 = arg("x0", box.left); val x1 = arg("x1", box.right)
  // 家族色（H1/H2/H0 选出来的那块"底"的本色）—— 上面那些格子的色是**局部**参照，
  // 家族色才是"全局认定的底"。两者不一致时才知道该怪谁。
  val famL = f2(SCR2, "famL").get(s) as IntArray
  val famA = f2(SCR2, "famA").get(s) as IntArray
  val famB = f2(SCR2, "famB").get(s) as IntArray
  val fams = f2(SCR2, "fams").getInt(s)
  val famHex = f2(SCR2, "famColor").get(s) as IntArray
  println("=== ${f.name}  box=$box  grid=$g band=$band ===")
  for (k in 0 until fams) {
    val c = famHex[k]
    println("  家族#$k  #%06X  Lab=%d/%d/%d".format(c and 0xFFFFFF, famL[k], famA[k], famB[k]))
  }
  if (fams == 0) println("  家族：无（H3 参照=空气）")
  println("  y  x   srcα  srcRGB   plate pass core  ref  fCov fTop   famL/famA/famB    dist beta")
  for (y in y0 until y1) {
    val row = y * w
    for (x in x0 until x1) {
      val i = row + x
      val p = px[i]
      val a = (p ushr 24) and 0xff
      if (a == 0) continue
      val c = ref[i]
      val cc = if (c >= 0) c else 0
      println(
        "  %3d %4d  %4d  #%06X   %d    %d    %d  %4d  %4d %4d  %6d/%6d/%6d  %5d %4d".format(
          y, x, a, p and 0xFFFFFF,
          if (plate[i] != 0.toByte()) 1 else 0,
          if (pass[i] != 0.toByte()) 1 else 0,
          if (core[i] != 0.toByte()) 1 else 0,
          c, fCov[cc], if (fTop.isEmpty()) -1 else fTop[cc], fL[cc], fA[cc], fB[cc],
          dist[i].toInt() and 0xffff, beta[i].toInt() and 0xffff
        )
      )
    }
  }
}
