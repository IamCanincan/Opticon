package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 「甲类这张的盘在另一层，所以实心格里不许有底」的对照台（**探针，不进仓**）。
 *
 * 左 = 现在这张；右 = 同一张、把 H1/H2（实心区当底）关掉、只许空气当底。
 * 判它值不值不靠数，靠**看**：azure 是主人验过好的那一张，它要是被这一刀改坏，规则就作废。
 */
private fun px(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

private fun ink(mask: IntArray?, edge: Int): IntArray {
  if (mask == null) return IntArray(edge * edge) { 0xff202020.toInt() }
  return IntArray(mask.size) {
    val a = (mask[it] ushr 24) and 0xff
    0xff000000.toInt() or (((0x2B * (255 - a) + 0xE8 * a) / 255) shl 16) or
      (((0x2B * (255 - a) + 0xE8 * a) / 255) shl 8) or ((0x2B * (255 - a) + 0xE8 * a) / 255)
  }
}

fun main(args: Array<String>) {
  val edge = 384
  val dirs = (args.firstOrNull() ?: "rec3u").split(',')
  val only = (args.getOrNull(1) ?: "kazumi,tieba,uuremote,smartglass,hunyuan,azure,xunmeng,openlist").split(',')
  val outDir = File("bench/nosolid").apply { mkdirs() }
  outDir.listFiles()?.forEach { it.delete() }
  println("%-34s %-8s %6s %6s %6s".format("图标", "H", "底占", "盘实", "框内"))
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    for (f in dir.listFiles { x: File -> x.extension == "png" }!!.sortedBy { it.name }) {
      if (only.none { f.name.contains(it) }) continue
      val src = runCatching { px(f, edge) }.getOrNull() ?: continue
      MonochromeMask.probeNoSolid = false
      val a = MonochromeMask.build(src, edge, edge)
      // ⚠ **当场数，不要隔一次 build 再回头数**：第一版是先建 b、再回来数 a，报出
      //   "现版本 xbox 在空气上上墨 15308 格"，而单独一台 `AirInkKtKt` 每张只 build 一次、
      //   逐张数是 **0 / 96**。两次 build 之间会变的只有 `src` 与模型那份池化 `Scratch` ——
      //   不管动的是哪一个，"隔一次 build 再读"量到的都不是那一份输入。这条还没定案，先按能复现的写法来。
      val mA = a.mask?.copyOf()
      val ba = band(mA, src, edge)
      // **对照第三次 build**：开关全关回去再建一次。若第三次也有 α0上墨，那"漏墨"就不是这条规则
      // 造成的，而是**同进程里第二次/第三次 build** —— 那是一件大得多的事。
      MonochromeMask.probeNoSolid = false
      MonochromeMask.probeNoBridge = false
      MonochromeMask.probeNoAirFill = false
      val c = MonochromeMask.build(src, edge, edge)
      val bc = band(c.mask?.copyOf(), src, edge)
      MonochromeMask.probeNoSolid = true
      MonochromeMask.probeNoBridge = true
      MonochromeMask.probeNoAirFill = true
      MonochromeMask.probeWhere = true
      val b = MonochromeMask.build(src, edge, edge)
      MonochromeMask.probeNoSolid = false
      MonochromeMask.probeNoBridge = false
      MonochromeMask.probeNoAirFill = false
      MonochromeMask.probeWhere = false
      val mB = b.mask?.copyOf()
      val bb = band(mB, src, edge)
      val same = mA != null && mB != null && mA.contentEquals(mB)
      val nm = f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").substringBeforeLast("_")
      println("%-34s H%s→H%s %6s %6s %6s %6s".format(nm.take(32),
        col(a.report, "H"), col(b.report, "H"), col(a.report, "底占"), col(b.report, "底占"),
        col(a.report, "框内"), col(b.report, "框内")))
      println("     支 ${if (a.report.contains("调=1")) "满" else "参"}→${if (b.report.contains("调=1")) "满" else "参"}" +
        "  色阶 ${col(a.report, "色阶")}→${col(b.report, "色阶")}   " +
        "现在：α0上墨=${ba[0]} 边=${ba[1]} 淡=${ba[2]} 中=${ba[3]} 实=${ba[4]}   " +
        "改后：α0上墨=${bb[0]} 边=${bb[1]} 淡=${bb[2]} 中=${bb[3]} 实=${bb[4]}" + (if (same) "   ←逐位相同" else "") +
        "   第三次(开关全关)：α0上墨=${bc[0]} 淡=${bc[2]} 中=${bc[3]} 实=${bc[4]}")
      val img = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, edge, edge, over(src, edge), 0, edge)
      img.setRGB(edge + 8, 0, edge, edge, ink(mA, edge), 0, edge)
      img.setRGB(edge * 2 + 16, 0, edge, edge, ink(mB, edge), 0, edge)
      ImageIO.write(img, "png", File(outDir, "$nm.png"))
    }
  }
  println("对照图 → bench/nosolid（每张三联：源 | 现在 | 只许空气当底）")
}

/** [全透明上墨, 半透明边上墨, 淡1-127, 中128-254, 实255]：`α=0` 与抗锯齿边**分开数**。 */
private fun band(mask: IntArray?, src: IntArray, edge: Int): IntArray {
  val o = IntArray(5)
  if (mask == null) return o
  for (i in src.indices) {
    val sa = (src[i] ushr 24) and 0xff
    val da = (mask[i] ushr 24) and 0xff
    if (da < 8) continue
    if (sa == 0) { o[0]++; continue }
    if (sa in 1..7) { o[1]++; continue }
    when {
      da < 128 -> o[2]++
      da < 255 -> o[3]++
      else -> o[4]++
    }
  }
  return o
}

private fun col(rep: String, k: String): String {  val i = rep.indexOf("$k="); if (i < 0) return "-"
  return rep.substring(i + k.length + 1).substringBefore(' ')
}

private fun over(px: IntArray, edge: Int): IntArray = IntArray(px.size) {
  val p = px[it]; val a = (p ushr 24) and 0xff
  val r = (((p shr 16) and 0xff) * a + 0x40 * (255 - a)) / 255
  val g = (((p shr 8) and 0xff) * a + 0x40 * (255 - a)) / 255
  val b = ((p and 0xff) * a + 0x40 * (255 - a)) / 255
  (0xff shl 24) or (r shl 16) or (g shl 8) or b
}
