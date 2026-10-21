package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import com.iamcanincan.opticon.circle.MonoVeto
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * **自画层这一类的"交出去长什么样"账**（2026-10-22）。
 *
 * 第 8 闸只管**该不该接管**（可见圆里有没有一格透明）。这一台管**接管之后交出去的是什么**：
 * `adapted_model` 那 67 张（应用自带单色层的那一整类）过去被 `_mkrec_model.cjs` 当"直通"整类剔掉，
 * 于是"我们接管的那两张画得好不好"**没有任何一把尺在看** —— 飞鸟（`com.fbclient.app`）就是这一类，
 * 主人问"能不能全覆盖"时，唯一诚实的回答要先有数。
 *
 * 每一张跑的是**装机那条链**：`MonochromeMask.build(喂进模型那一张, plateLayer, under)`，
 * 与 [PlateSubKt] 同一套输入构造（`cases()`），差别只在这里**多读一份 `*_mono.png`** 判 veto。
 *
 * 报的四根尺都是**这一类自己看得见的**：`字均`（字标笔画平均净宽，越小越容易被啃）、
 * `零`（被整片交成底的块数）、`淡`（只落在 α<128 那一层的像素）、`框内`（交付墨占内容外接框比例）。
 * ⚠ 这些数**不当红绿灯**：这一类没有对岸（设计师那层就是那块饼），所以它是"看有没有变坏"的账，
 *   不是"越小越好"的账 —— 拿它当收益表用就是本仓骂过的那族。
 *
 * 用法：`sh run.sh harness.AdaptKtKt <语料目录…> [--edge=384]`
 */
fun main(args: Array<String>) {
  val e = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  require(dirs.isNotEmpty() && dirs.all { it.isDirectory }) { "语料目录不存在：${dirs.joinToString()}" }

  fun fld(s: String, k: String) = Regex("$k=([0-9./+-]+)").find(s)?.groupValues?.get(1) ?: "-"

  var n = 0; var veto = 0
  val ink = ArrayList<Int>(); val zero = ArrayList<Int>(); val faint = ArrayList<Int>()
  val vetoRows = ArrayList<String>()
  for (d in dirs) {
    for (c in cases(d, e, inset = false, fgAll = false)) {
      val mono = File(d, c.file.nameWithoutExtension.removeSuffix("_model") + "_mono.png")
      // veto 那把尺的口径**必须与第 8 闸同一格**（`MonoVeto.EDGE`），不然两边判的不是同一件事。
      val mp = if (mono.isFile) runCatching { grid(mono, MonoVeto.EDGE) }.getOrNull() else null
      val fires = mp != null && MonoVeto.wholePlate(mp, MonoVeto.EDGE)
      val out = runCatching { MonochromeMask.build(c.src, e, e, c.plateLayer, c.under, plateIsSubject = false) }
        .getOrNull() ?: continue
      n++
      if (fires) veto++
      val rep = out.report
      fld(rep, "字均").toIntOrNull()?.let { ink += it }
      fld(rep, "零").toIntOrNull()?.let { zero += it }
      // `淡=` 是**百分数小数**（0.86 这种），按 Int 解析会恒 null —— 第一版就报出 `淡中位=-1`
      // 还看着像"这一类没有淡"。凡加"只报数"的列，先拿一条真读数验它数得出非零（本仓那条）。
      fld(rep, "淡").toDoubleOrNull()?.let { faint += (it * 100).roundToInt() }
      if (fires) {
        vetoRows += stemOf(c.file) + " H=" + fld(rep, "H") + " 底占=" + fld(rep, "底占") +
          " 框内=" + fld(rep, "框内") + " 字均=" + fld(rep, "字均") + " 零=" + fld(rep, "零") +
          " 淡=" + fld(rep, "淡") + " 交付=" + (if (out.mask == null) "null" else "有")
      }
    }
  }
  fun med(v: List<Int>): Int {
    if (v.isEmpty()) return -1
    val s = v.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else ((s[m - 1] + s[m]) / 2.0).roundToInt()
  }
  println("扫过 $n 张（自带单色层那一类）｜veto 开火 $veto 张")
  println("中位：字均净 ${med(ink)}｜零 ${med(zero)}｜淡 ${med(faint)}   （这些是**看有没有变坏**的账，不是收益表）")
  vetoRows.forEach { println("  接管 $it") }
  println("ADAPT n=$n veto=$veto 字均中位=${med(ink)} 零中位=${med(zero)} 淡中位=${med(faint)} " +
    "接管名单=[${vetoRows.joinToString(";")}]")
  if (n == 0) { println("!! 一张都没跑到 —— 这台子什么都没比"); kotlin.system.exitProcess(1) }
  if (veto == 0) { println("!! veto 一张都没开火：这一类的输出与本机现状脱钩了（第 8 闸那份分母是不是空的？）"); kotlin.system.exitProcess(1) }
}

/** 文件名去掉尾上那 12 位内容签名（钉名单要稳定身份）。 */
private fun stemOf(f: File) = f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "")

private fun grid(f: File, e: Int): IntArray {
  val src = ImageIO.read(f) ?: error("读不出 " + f.name)
  val bmp = BufferedImage(e, e, BufferedImage.TYPE_INT_ARGB)
  val g = bmp.createGraphics(); g.drawImage(src, 0, 0, e, e, null); g.dispose()
  return IntArray(e * e).also { bmp.getRGB(0, 0, e, e, it, 0, e) }
}
