package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 乙类「平色饼」A 型的**人眼对照联表**（2026-10-26）。
 *
 * 背景：`ObjKt` 量出 9 张 A 型（源有 ≥4 档、交付 Neff≤2.5、交付墨里 ≥3 个互不相连的大物体）。
 * 但 `ObjKt` 只报数，不回答"这些平色饼到底是不是错的" —— 乙类缺的正是人眼参照。
 * 这里按**生产路径**（带 index.tsv 的 `plate`/`under`）重跑模型，把源图与交付掩码并排拼表。
 *
 * 用法：`harness.FlatSheetKtKt rec_model [--out=bench/flat]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val outDir = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/flat")
    .apply { mkdirs() }
  val names = listOf(
    "autonavi.minimap", "MobileTicket", "steamlink", "xboxone.smartglass", "wuba.zhuanzhuan",
    "chaoxing.mobile", "example.appui", "mc10086", "taobao.taobao", "apps.translate",
    "sankuai.meituan", "taptap", "hunyuan",
  )
  val index = File(dir, "index.tsv")
  val plateOf = HashMap<String, Boolean>(); val underOf = HashMap<String, String>()
  if (index.isFile) index.readLines().drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 8) plateOf[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    if (c.size >= 19 && c[18] != "-" && c[18] != "null" && c[18] != "err") underOf[c[2]] = c[18]
  }
  val cells = ArrayList<Pair<String, BufferedImage>>()
  for (f in dir.listFiles { x: File -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.endsWith("_modelbg.png") }!!.sortedBy { it.name }) {
    if (names.none { f.name.contains(it) }) continue
    val img = ImageIO.read(f) ?: continue
    val w = img.width; val h = img.height
    val src = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
    val und = underOf[f.name]?.let { u ->
      runCatching {
        val uf = File(dir, u)
        val bi = if (uf.isFile) ImageIO.read(uf) else null
        if (bi == null || bi.width != w || bi.height != h) null
        else IntArray(w * h).also { bi.getRGB(0, 0, w, h, it, 0, w) }
      }.getOrNull()
    }
    val r = MonochromeMask.build(src, w, h, plateOf[f.name] == true, und)
    val m = r.mask ?: continue
    val cell = 192
    val c = BufferedImage(cell * 2 + 8, cell + 20, BufferedImage.TYPE_INT_RGB)
    val g = c.createGraphics() as Graphics2D
    g.color = Color(0xEE, 0xEE, 0xEE); g.fillRect(0, 0, c.width, c.height)
    for (y in 0 until cell) for (x in 0 until cell) {
      val sx = x * w / cell; val sy = y * h / cell
      val p = src[sy * w + sx]
      val a = (p ushr 24) and 0xff
      val or = (p shr 16) and 0xff; val og = (p shr 8) and 0xff; val ob = p and 0xff
      val rr = (or * a + 0x22 * (255 - a)) / 255
      val gg = (og * a + 0x22 * (255 - a)) / 255
      val bb = (ob * a + 0x22 * (255 - a)) / 255
      c.setRGB(x, y, (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb)
      val ma = (m[sy * w + sx] ushr 24) and 0xff
      val v = 255 - ma
      c.setRGB(cell + 8 + x, y, (0xff shl 24) or (v shl 16) or (v shl 8) or v)
    }
    g.color = Color(0x22, 0x22, 0x22)
    g.font = Font("SansSerif", Font.PLAIN, 11)
    g.drawString(f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}"), "").take(46), 2, cell + 16)
    g.dispose()
    cells.add(f.name to c)
    println("cell ${f.name} plate=${plateOf[f.name] == true} under=${und != null} objects=${r.components}")
  }
  if (cells.isEmpty()) { println("没有命中"); return }
  val cols = 3
  val rows = (cells.size + cols - 1) / cols
  val cw = 192 * 2 + 8; val ch = 192 + 20
  val sheet = BufferedImage(cols * cw + 8, rows * ch + 8, BufferedImage.TYPE_INT_RGB)
  val g = sheet.createGraphics() as Graphics2D
  g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
  cells.forEachIndexed { i, p -> g.drawImage(p.second, (i % cols) * cw + 4, (i / cols) * ch + 4, null) }
  g.dispose()
  val out = File(outDir, "flat_sheet.png")
  ImageIO.write(sheet, "png", out)
  println("写出 ${out.path}（${cells.size} 张；每格左=源/右=交付，底为白）")
}
