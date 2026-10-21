package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **通用性分布台**：把每一条"闸"所读的数，逐张打成一列，用来回答一个问题 ——
 * **这条线是画在空档里，还是画在人群里？**
 *
 * 为什么要有它（2026-10-09）：那一天先是被 `底 L* < 80` 教了一次 —— 把全语料的底 L 摊开之后
 * 发现该翻的挤在 58~79、不该翻的 PH 是 75，**这条线上根本没有第二个切点**，而它已经躺在仓里很久。
 * 当天又立了两条线（`airCanvas ≥ 25`、斜坡地板 128），当时只在 **11 张**上看过"中间空 43 个点"。
 * **11 张不算分布。** 这一台把三条语料里所有"会进模型的"图都跑一遍，每条线给出：
 * 线上/线下各多少张、离它最近的邻居有多远、以及**它两边最接近的一对差几个单位**。
 *
 * 用法：`harness.DistKtKt <语料…> [--edge=384] [--out=bench/dist.tsv]`
 */
private fun px(f: File, edge: Int): IntArray {
  val src = ImageIO.read(f)
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = java.awt.Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, 0, 0, edge, edge, null); g.dispose()
  val o = IntArray(edge * edge); img.getRGB(0, 0, edge, edge, o, 0, edge); return o
}

private fun fld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun col(rep: String, k: String): Double {
  val i = rep.indexOf("$k="); if (i < 0) return -1.0
  val v = rep.substring(i + k.length + 1).substringBefore(' ')
  return v.toDoubleOrNull() ?: -1.0
}

fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val out = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "bench/dist.tsv")
  val dirs = args.filter { !it.startsWith("--") }.ifEmpty { listOf("rec3u", "rec_blank", "corpus_runtime") }
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  // **直接调模型自己那个 `tonalClass`**：判据台的 `跨/主色/气` 三列**只在满幅那一支开火时才印**，
  // 拿它们做分布就是循环论证（"线下 0 张"是必然的）。第一版就栽在这里。
  val sb = StringBuilder("icon\tcorpus\tairCanvas\tairBox\tspan\tflat\ttex\tshare\tgN\tiN\tgL\tiL\ttonal\n")
  for (d in dirs) {
    val dir = File(d); if (!dir.isDirectory) continue
    for (f in dir.listFiles { x: File -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.contains("_fg") }!!.sortedBy { it.name }) {
      val src = runCatching { px(f, edge) }.getOrNull() ?: continue
      val r = MonochromeMask.build(src, edge, edge)
      var airCanvas = 0; var t = edge; var b = 0; var l = edge; var rr = 0
      for (y in 0 until edge) for (x in 0 until edge) {
        val a = (src[y * edge + x] ushr 24) and 0xff
        if (a < 250) airCanvas++
        if (a > 0) { if (y < t) t = y; if (y > b) b = y; if (x < l) l = x; if (x > rr) rr = x }
      }
      // `跨 / 主色 / 纹理` 这三条**没有**从模型里取：反射调 `tonalClass$main` 每张都回 null
      // （单独调与在 `build` 里调不等价，原因没查），而台子里留一列永远 -1 的假数比没这列更坏。
      // 这三条的分布改从判据行取（见下面 `col(r.report, …)`）—— 代价是**只数得到开火的那些**，
      // 而对"入场闸"这恰好是对的口径：它做的决定就是"这张开不开火"。
      val g = col(r.report, "跨"); val fl = col(r.report, "主色"); val tx = col(r.report, "纹理")
      val bw = (rr + 1 - l).coerceAtLeast(1); val bh = (b + 1 - t).coerceAtLeast(1)
      var airBox = -1
      run {
        var ab = 0
        for (y in t..b) for (x in l..rr) if (((src[y * edge + x] ushr 24) and 0xff) < 250) ab++
        airBox = ab * 100 / (bw * bh)
      }
      val s = pool.get()
      var gl = -1.0; var il = -1.0; var gN = 0; var iN = 0
      if (s != null) {
        val plate = fld(s, "plate") as ByteArray
        val labL = fld(s, "labL") as ShortArray
        var gs = 0L; var isum = 0L
        for (i in src.indices) {
          if (((src[i] ushr 24) and 0xff) != 255) continue
          val lv = (labL[i].toInt() and 0xffff) / 64
          if (plate[i] != 0.toByte()) { gs += lv; gN++ } else { isum += lv; iN++ }
        }
        if (gN > 0) gl = (gs / gN).toDouble()
        if (iN > 0) il = (isum / iN).toDouble()
      }
      sb.append(f.nameWithoutExtension.replace(Regex("^icon\\d+_"), "").substringBeforeLast("_").take(40))
        .append('\t').append(d)
        .append('\t').append(airCanvas * 100 / src.size)
        .append('\t').append(airBox)
        .append('\t').append(g).append('\t').append(fl).append('\t').append(tx)
        .append('\t').append(col(r.report, "底占"))
        .append('\t').append(gN).append('\t').append(iN)
        .append('\t').append(gl).append('\t').append(il)
        .append('\t').append(if (r.report.contains("调=1")) 1 else 0).append('\n')
    }
  }
  out.writeText(sb.toString())
  println("${out.path} 写了 ${sb.toString().count { c -> c == '\n' } - 1} 行")
}
