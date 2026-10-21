package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **全量普查「该实不实」** —— 不再靠点名，把整份语料里两类同一形状的缺陷一次数出来：
 *
 *   · **白未实**：源那一格本来接近纯白（L ≥ 220）**且它坐在墨上/是字形**，交付却给了 α 128..254
 *     —— 屏幕上就是"白字发灰"。`tv.danmaku.bili` 那 5.9% 的 `#FEFEFE` 交成 α=175 是同一件事的一张样本。
 *   · **暗未实**：源接近纯黑（L ≤ 40）却只交出一段中灰 —— 屏幕上就是"黑字发灰"。
 *
 * 为什么要单独数这两类：`淡=`（α 1..127）与 `总灰区`（α 1..254）**都只看"交出去多少灰"**，
 * 不看"源本来是什么"。一张纯白的字标交成 α=175，在总灰区上只算"摊在中间调的面积"，
 * 而它的正确读数是**该实不实** —— 方向是抬，不是并档、不是删灰。
 * 这两类都是**源侧的事实**（不依赖盘、不依赖连通块、不依赖阈值猜参照），所以能一次数全。
 *
 * 用法：`harness.WashKtKt <目录> [--edge=384] [--top=14]`
 */
fun main(args: Array<String>) {
  val dir = File(args.first { !it.startsWith("--") })
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("="))?.toIntOrNull() ?: 384
  val top = (args.firstOrNull { it.startsWith("--top=") }?.substringAfter("="))?.toIntOrNull() ?: 14
  val plate = HashMap<String, Boolean>(); val underOf = HashMap<String, String>(); val adapted = HashSet<String>()
  File(dir, "index.tsv").let { ix -> if (ix.isFile) ix.readText().trim().split('\n').drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 9) {
      plate[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
      if (c[8] == "有") adapted.add(c[2])
      if (c.size >= 19 && c[18] != "-" && c[18] != "null") underOf[c[2]] = c[18]
    } } }
  val rows = ArrayList<Triple<String, Double, Double>>()
  val tonalRows = ArrayList<Triple<String, Double, Double>>()
  var whiteHit = 0; var darkHit = 0; var whiteTonal = 0; var whiteRef = 0
  val whiteRefRows = ArrayList<Triple<String, Double, Double>>()
  val whiteAlphas = ArrayList<Int>()
  for (f in dir.listFiles { x -> x.extension.equals("png", true) && !x.name.endsWith("_modelbg.png") &&
      !x.name.endsWith("_bg.png") && !x.name.endsWith("_fg.png") && !x.name.contains("preview") &&
      !adapted.contains(x.name) }!!.sortedBy { it.name }) {
    val px = read(f, edge) ?: continue
    val u = underOf[f.name]?.let { read(File(dir, it), edge) }
    val r = MonochromeMask.build(px, edge, edge, plate[f.name] == true, u)
    val out = r.mask ?: continue
    val byTonal = r.report.contains("调=1")          // 满幅那一支：亮部交成中灰是**设计**，不是病
    val b = edge * 3 / 4; val o = (edge - b) / 2
    val cells = b.toLong() * b
    var white = 0; var dark = 0
    for (y in o until o + b) for (x in o until o + b) {
      val i = y * edge + x
      val a = (out[i] ushr 24) and 0xff
      if (a < 128 || a == 255) continue                     // 只管"上了墨但没实心"的格
      val p = px[i]
      if (((p ushr 24) and 0xff) < 250) continue
      val l = 0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)
      if (l >= 220) { white++ } else if (l <= 40) { dark++ }
    }
    if (white * 100.0 / cells >= 1.0) whiteHit++
    if (dark * 100.0 / cells >= 1.0) darkHit++
    val nm = (f.nameWithoutExtension.split('_').getOrNull(if (f.name.startsWith("icon")) 1 else 0)
      ?: f.nameWithoutExtension).take(30)
    rows.add(Triple(nm, white * 100.0 / cells, dark * 100.0 / cells))
    if (byTonal) tonalRows.add(rows.last()) else {
      if (white * 100.0 / cells >= 1.0) whiteRef++
      // **这些格现在几档？** 差 20 点（"补最后一段"）与差 150 点（"整片洗白"）是两种完全不同的刀，
      //   所以先摊 α 分布，再决定门槛画在哪 —— 别拍一个"抬到实心"结果抬的是一堆本来就该半墨的格。
      for (y in o until o + b) for (x in o until o + b) {
        val i = y * edge + x
        val a = (out[i] ushr 24) and 0xff
        if (a < 128 || a == 255) continue
        val p = px[i]
        if (((p ushr 24) and 0xff) < 250) continue
        val l = 0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)
        if (l >= 220) whiteAlphas.add(a)
      }
      whiteRefRows.add(rows.last())
    }
    if (byTonal && white * 100.0 / cells >= 1.0) whiteTonal++
  }
  println("「该实不实」普查  ${dir.name}  ${rows.size} 张非适配 —— **按交付那一支分开，否则把设计当病**")
  println("   满幅那一支交出去的 ${tonalRows.size} 张：白未实 ≥1% 的 ${whiteTonal} 张" +
    "（亮部交成中灰**就是这一支的目的**，这一列不算缺陷）")
  println("   参照那一支交出去的 ${whiteRefRows.size} 张：**白未实 ≥1% 框的 $whiteRef 张 ← 这才是要看的**")
  println("   参照那一支里白未实最狠的前 $top：")
  for (r in whiteRefRows.sortedByDescending { it.second }.take(top))
    if (r.second >= 1.0) println("      白 %5.2f%%  暗 %5.2f%%   %s".format(r.second, r.third, r.first))
  if (whiteAlphas.isNotEmpty()) {
    whiteAlphas.sort()
    fun pc(q: Double) = whiteAlphas[minOf(whiteAlphas.size - 1, (whiteAlphas.size * q).toInt())]
    println("   **这些「白未实」的格现在交在几档**（只数参照那一支、白未实≥1% 的那 $whiteRef 张）：")
    println("      格数 ${whiteAlphas.size}   p10 ${pc(0.10)}  p50 ${pc(0.50)}  p90 ${pc(0.90)}  最大 ${whiteAlphas.last()}")
    println("      其中 α≥190（只差最后一段）占 ${whiteAlphas.count { it >= 190 } * 100 / whiteAlphas.size}%   " +
      "α<160（本来就该半墨，不许抬）占 ${whiteAlphas.count { it < 160 } * 100 / whiteAlphas.size}%")
  }
  println("   ── 不分支的总账（只作对照，别拿它当名单下刀）──")
  println("   白未实 ≥1% 框的张数 $whiteHit   暗未实 ≥1% 框的张数 $darkHit")
  println("   按两类之和降序（前 $top）：")
  for (r in rows.sortedByDescending { it.second + it.third }.take(top))
    if (r.second + r.third >= 0.5) println("      白 %5.2f%%  暗 %5.2f%%   %s".format(r.second, r.third, r.first))
}

private fun read(f: File, edge: Int): IntArray? {
  val src = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = src.width; val h = src.height; val e = minOf(w, h)
  val sq = if (w == h) src else src.getSubimage((w - e) / 2, (h - e) / 2, e, e)
  val big = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = big.createGraphics(); g.drawImage(sq, 0, 0, edge, edge, null); g.dispose()
  val px = IntArray(edge * edge); big.getRGB(0, 0, edge, edge, px, 0, edge); return px
}
