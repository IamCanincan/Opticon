package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **设备自己交回来的那一张 vs 判据台算的那一张**（2026-10-12）。
 *
 * 装机那一轮 `MonoDump` 把「模型吃进去的 src」和「交出去的 out」都压成 PNG 发回日志，
 * `_undump.cjs` 重组到本地。这一台把两边并排画出来：源｜设备交付｜离线复算。
 * 用途只有一个 —— 他说"没任何区别"的时候，**别猜他看到的是哪一张**。
 *
 * 用法：`harness.DevOutKt <undump目录> [名字子串] [语料目录]`
 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val needle = args.getOrNull(1) ?: "translate"
  val corpus = args.getOrNull(2)?.let { File(it) }
  val edge = 384
  val srcs = dir.listFiles { x -> x.extension == "png" && x.name.contains(needle) && x.name.contains(".src.") }
    ?: emptyArray()
  if (srcs.isEmpty()) { println("${dir} 里没有含「$needle」的 .src.png"); return }
  for (s in srcs) {
    val name = s.name.removeSuffix(".src.png")
    val out = File(dir, "$name.out.png")
    val px = grab(s, edge)!!
    var offMask: IntArray? = null
    val panels = ArrayList<Pair<String, BufferedImage>>()
    panels.add("源（设备吃进去）" to ImageIO.read(s))
    if (out.isFile) panels.add("设备交出去的那一张" to srcOn(grabAlpha(out, edge), edge))
    if (corpus != null) {
      val c = corpus.listFiles { x -> x.extension == "png" && x.name.contains(needle) &&
          !x.name.endsWith("_modelbg.png") }?.firstOrNull()
      if (c != null) {
        val un = File(corpus, c.nameWithoutExtension + "_modelbg.png")
        val m = MonochromeMask.build(grab(c, edge)!!, edge, edge, false,
          if (un.isFile) grab(un, edge) else null).mask
        if (m != null) { offMask = m; panels.add("判据台复算（同一份代码）" to srcOn(m, edge)) }
      }
    }
    /** 墨占整幅几成 —— 「右半边全黑」那一类目标要买的就是这个数，而第八闸与 `盘刀` 防的也是它。 */
    fun inkPct(mm: IntArray?): Double? {
      if (mm == null) return null
      var n = 0; for (p in mm) if (((p ushr 24) and 0xff) >= 128) n++
      return n * 100.0 / mm.size
    }
    val devInk = if (out.isFile) inkPct(grabAlpha(out, edge)) else null
    val offInk = inkPct(offMask)
    val w = edge * panels.size + 8 * (panels.size - 1)
    val img = BufferedImage(w, edge + 22, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, edge + 22)
    panels.forEachIndexed { i, (t, b) ->
      g.drawImage(b, i * (edge + 8), 22, edge, edge, null)
      g.color = java.awt.Color.DARK_GRAY; g.drawString(t, i * (edge + 8) + 4, 16)
    }
    g.dispose()
    val o = File("D:/tmp/devout_${needle}.png"); ImageIO.write(img, "png", o)
    println("写出 ${o.absolutePath}（${panels.map { it.first }}）")
    println("   墨占整幅：设备 ${devInk?.let { "%.1f".format(it) } ?: "?"}%   离线 ${offInk?.let { "%.1f".format(it) } ?: "?"}%")
  }
}

private fun grab(f: File, edge: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val out = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics(); g.drawImage(img, 0, 0, edge, edge, null); g.dispose()
  return IntArray(edge * edge).also { out.getRGB(0, 0, edge, edge, it, 0, edge) }
}

/** 设备那张 out 只有 alpha 有意义（PNG 的 RGBA 直通 alpha，Android 转预乘再转回时 alpha 不变）。 */
private fun grabAlpha(f: File, edge: Int): IntArray = grab(f, edge)!!.let { p ->
  IntArray(p.size) { i -> ((p[i] and 0xff) shl 24) or 0x00FFFFFF }
}

private fun srcOn(mask: IntArray, edge: Int): BufferedImage {
  val plate = 0xFFEDEDED.toInt(); val ink = 0xFF1F1F1F.toInt()
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
  for (i in 0 until edge * edge) {
    val a = (mask[i] ushr 24) and 0xff
    val pr = (plate shr 16) and 0xff; val pg = (plate shr 8) and 0xff; val pb = plate and 0xff
    val ir = (ink shr 16) and 0xff; val ig = (ink shr 8) and 0xff; val ib = ink and 0xff
    img.setRGB(i % edge, i / edge,
      ((pr + (ir - pr) * a / 255) shl 16) or ((pg + (ig - pg) * a / 255) shl 8) or (pb + (ib - pb) * a / 255))
  }
  return img
}
