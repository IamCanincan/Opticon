package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.ArrayDeque

/**
 * **围合探针（只量，不改模型）** —— 2026-10-09。
 *
 * 逼它出来的是一对反例：`org.chromium.webapk.aec2b158…`（橙底 + 黑色播放三角）要"暗=墨"，
 * `com.microsoft.office.outlook`（六边形信封）要"亮=墨"。能分开它们的只有"底占实心 90% vs 61%"
 * —— 又一个阈值，而这条线今天已经否掉九把轴，共同死法都是"再找一个数"。
 *
 * 所以这里问一个**不比色**的问题：**那块被当成底的区域，有没有把图形围起来。**
 * 一张真正的承载面会把标记**包在肚子里**（橙底包着三角）；而"底其实是图形的一部分"时，
 * 其余图形能一路走到画布边缘、不被包住（outlook 的亮带一直通到六边形的边上）。
 *
 * 量的是模型**自己**判出来的 `plate`（反射读 `Scratch.plate`，与出图同一份），
 * 从画布四边出发、只走**非底**的格做洪泛；到不了的实心格 = 被底围住。
 *
 * ⚠ **这台报的数与模型判据行里的 `围=` 不是同一个东西，别拿来对齐**（2026-10-10 撞上）。三处不同：
 *   ① 这里读的是 `build` **全部跑完**之后的 `plate`，模型那一列取的是 `groundPass` 之后、
 *     后面几遍覆写之前的同一份数组；② 这里的洪泛**允许穿过空气**（只挡 `plate`），模型那一列
 *     空气不算通路；③ 分母这里用 α≥200，模型用"α 恰好 255"（与 `cDep`/`字均` 同一条）。
 *   合起来差多少：`com.jingdong.app.mall` 在这台上报 **围合=100%**，在模型里报 **26%**。
 *   真机 62 张里"包住"的只有 6 张 ≥97%（xbud / mc10086 / appui / contacts×2 / 一张无名），
 *   **京东不在里面** —— 所以"底把标记围住 → 该翻极性"这条判据**够不到主人点的那张**，死在这里。
 *   以后要用这个概念，以判据行的 `围=` 为准（装机那一条自己算的），别再读这台。
 *
 * 跑法：`java -cp … harness.PolarKtKt <语料目录> [--only=子串]`
 */
private fun loadArgb(path: String, edge: Int): IntArray {
  val src = javax.imageio.ImageIO.read(File(path))
  val img = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_runtime")
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  println("%-40s %6s %7s %7s %7s".format("图标", "底顶边", "围合%", "实心非底", "底占"))
  val rows = ArrayList<Triple<String, Boolean, Int>>()
  for (f in dir.listFiles { x: File -> x.extension == "png" && !x.name.contains("preview") &&
    !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
    if (only != null && !f.name.contains(only)) continue
    val src = runCatching { loadArgb(f.absolutePath, edge) }.getOrNull() ?: continue
    val r = MonochromeMask.build(src, edge, edge)
    val s = pool.get() ?: continue
    val plate = fld(s, "plate") as ByteArray
    val n = edge * edge
    // 四边出发，只走非底的格
    val seen = ByteArray(n)
    val q = ArrayDeque<Int>()
    var borderPlate = false
    fun push(i: Int) { if (plate[i] == 0.toByte() && seen[i] == 0.toByte()) { seen[i] = 1; q.add(i) } }
    for (x in 0 until edge) {
      if (plate[x] != 0.toByte()) borderPlate = true
      if (plate[(edge - 1) * edge + x] != 0.toByte()) borderPlate = true
      push(x); push((edge - 1) * edge + x)
    }
    for (y in 0 until edge) {
      if (plate[y * edge] != 0.toByte()) borderPlate = true
      if (plate[y * edge + edge - 1] != 0.toByte()) borderPlate = true
      push(y * edge); push(y * edge + edge - 1)
    }
    while (q.isNotEmpty()) {
      val i = q.poll(); val x = i % edge; val y = i / edge
      if (x > 0) push(i - 1); if (x < edge - 1) push(i + 1)
      if (y > 0) push(i - edge); if (y < edge - 1) push(i + edge)
    }
    var opq = 0; var enc = 0; var gp = 0
    for (i in 0 until n) {
      if (plate[i] != 0.toByte()) { gp++; continue }
      if (((src[i] ushr 24) and 0xff) < 200) continue
      opq++
      if (seen[i] == 0.toByte()) enc++
    }
    val pct = if (opq == 0) 0 else enc * 100 / opq
    rows.add(Triple(f.nameWithoutExtension, borderPlate, pct))
    println("%-40s %6s %6d%% %7d %6.2f".format(
      f.nameWithoutExtension.take(40), if (borderPlate) "是" else "否", pct, opq,
      gp.toDouble() / n))
  }
  val hi = rows.filter { it.second && it.third >= 80 }
  val lo = rows.filter { !(it.second && it.third >= 80) }
  println("\n「底顶到边 且 把 ≥80% 的图形围在肚子里」= 判它是一张真盘：${hi.size} 张 / ${rows.size}")
  println("  " + hi.take(24).joinToString(" ") { it.first.split("_")[0] })
  println("  ↑ 这一族若成立，极性就不该翻（PH 该在这里）；outlook 不该在这里。")
  println("其余 ${lo.size} 张：底没有把图形包起来（图形通得出去）")
}
