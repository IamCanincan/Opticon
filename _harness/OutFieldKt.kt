package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 【场洗·判据候选】淡墨格归不归零，问的是**角色**：它在主体外面，还是被主体围着。
 *
 * 洪泛的可行集 = 「模型判成底的格」∪「淡墨格(1..95)」，从画布边界开始：
 *   够得到的淡墨 = 与底连成一片的开阔场  => 归零；
 *   够不到的     = 被主体挡在外面的中间调 => 留。
 * 关键是**高光带当墙**：它既不是底、交付又是 0 墨，所以不在可行集里 —— 上一版(离浓墨>=2 格)
 * 就是把 M 的中调面顺着这条高光带误判成开阔场的（主人：「有点切过头了」）。
 *
 * ⚠ 只读生产的 `Scratch.plate`（与 [PlateDump]/[ResistKt] 同一先例：反射读，不复刻判据）。
 * 用法：`harness.OutFieldKtKt <目录> [--write]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
  val write = args.contains("--write")
  val fld = MonochromeMask::class.java.getDeclaredField("pool"); fld.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val pool = fld.get(MonochromeMask) as ThreadLocal<Any>
  var tFaint = 0; var tOut = 0; var tIn = 0
  dir.listFiles { f: File -> f.name.endsWith(".png") }?.sortedBy { it.name }?.forEach { f ->
    val img = ImageIO.read(f); val w = img.width; val h = img.height; val n = w * h
    val px = IntArray(n); img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h); val m = r.mask ?: return@forEach
    val s = pool.get()
    @Suppress("UNCHECKED_CAST")
    val plate = (s.javaClass.getDeclaredField("plate").apply { isAccessible = true }
      .get(s) as ByteArray).clone()
    val ink = IntArray(n) { (m[it] ushr 24) }
    fun ok(i: Int) = plate[i] != 0.toByte() || (ink[i] in 1..95)
    val seen = BooleanArray(n)
    val q = ArrayDeque<Int>()
    fun push(i: Int) { if (!seen[i] && ok(i)) { seen[i] = true; q.add(i) } }
    for (x in 0 until w) { push(x); push((h - 1) * w + x) }
    for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
    while (q.isNotEmpty()) {
      val c = q.removeFirst(); val cy = c / w; val cx = c - cy * w
      for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val nx = cx + dx; val ny = cy + dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        push(ny * w + nx)
      }
    }
    var faint = 0; var fout = 0
    val out = if (write) BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB) else null
    for (i in 0 until n) {
      val k = ink[i]
      var v = 255 - k
      if (k in 1..95) { faint++; if (seen[i]) { fout++; v = 255 } }     // 白 = 会归零的
      out?.setRGB(i % w, i / w, (0xff shl 24) or (v shl 16) or (v shl 8) or v)
    }
    if (out != null) { val o = File("D:/tmp/outf2"); o.mkdirs(); ImageIO.write(out, "png", File(o, f.name)) }
    tFaint += faint; tOut += fout; tIn += faint - fout
    if (faint > 0) println("%-58s 淡墨=%-7d 外面=%-7d 围住=%d".format(f.name, faint, fout, faint - fout))
  }
  println("\n合计 淡墨=$tFaint 外面(归零)=$tOut (%.1f%%) 围住保留=$tIn (%.1f%%)".format(
    if (tFaint == 0) 0.0 else tOut * 100.0 / tFaint, if (tFaint == 0) 0.0 else tIn * 100.0 / tFaint))
}
