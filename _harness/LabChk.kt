package harness

import com.iamcanincan.opticon.circle.MonochromeMask

/** 一次性核对：Lab 通道的**符号**与 `and 0xffff` 掩码对 ΔE 的影响（2026-10-17）。 */
object LabChk {
  private fun d(l1: Int, a1: Int, b1: Int, l2: Int, a2: Int, b2: Int): Int {
    val dl = (l1 - l2).toLong(); val da = (a1 - a2).toLong(); val db = (b1 - b2).toLong()
    return Math.sqrt((dl * dl + da * da + db * db).toDouble()).toInt()
  }
  @JvmStatic
  fun main(args: Array<String>) {
    val cols = listOf("黑" to 0xFF000000.toInt(), "白" to 0xFFFFFFFF.toInt(), "中灰" to 0xFF808080.toInt(),
      "红" to 0xFFFF0000.toInt(), "蓝" to 0xFF0000FF.toInt())
    for ((nm, p) in cols) {
      val l = MonochromeMask.labChannel(p, 0); val a = MonochromeMask.labChannel(p, 1); val b = MonochromeMask.labChannel(p, 2)
      println("$nm  L=$l a=$a b=$b  (真值 ${l / 64.0}/${a / 64.0}/${b / 64.0})  掩码后 ${l and 0xffff}/${a and 0xffff}/${b and 0xffff}")
    }
    val blk = cols[0].second; val wht = cols[1].second
    fun ch(p: Int, k: Int) = MonochromeMask.labChannel(p, k)
    println("黑白 ΔE 原始 = ${d(ch(blk,0),ch(blk,1),ch(blk,2),ch(wht,0),ch(wht,1),ch(wht,2)) / 64.0}")
    println("黑白 ΔE 掩码 = ${d(ch(blk,0) and 0xffff,ch(blk,1) and 0xffff,ch(blk,2) and 0xffff,ch(wht,0) and 0xffff,ch(wht,1) and 0xffff,ch(wht,2) and 0xffff) / 64.0}")
  }
}
