package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **"这块盘是被画出来的物体，还是承载面"—— 用画布四条边上的不透明比例量一次。**
 *
 * 为什么要它：只用 dump 通道（`rec3/`，MonoDump 抓的、和真机 logcat 逐字对得上）重判点名
 *   那 12 张之后，坏的那几张是同一个形状 —— **图标自己的身体被当成底交成了 0**：
 *     · 拼多多：白六边形拼成的那颗心整块没了，只剩心上的小红图标（`墨` 只占实心 26%）
 *     · azure：整只盾没了，只剩盾里那个人（`没盾`）
 *     · 混元：太极只剩一半（`只有半个太极`）
 *     · 京东：狗的身体没了（`头顶没了`）
 *   而好的那几张（饿了么 / 驾考宝典 / 浙江在线 / 易班 / 智慧树）全是**满幅**的：盘子铺到画布边。
 *   模型里 [GROUND_ISLAND_PCT] 那条已经说过"小岛本身就是图形"，但它只挂在 H0 的豁免上。
 *   这一张台子量的是**这个证据本身在 113 张上分不分得开**，不是又一版判据。
 *
 * 用法：`harness.EdgeKtKt <目录> … [--edge=384]`
 * 列：`边触%`=画布四条边那一圈像素里不透明的比例；`实心%`=整张里 α=255 的比例；
 *     `盘吞%`=源实心格被判成底的比例（`盘实/(盘实+墨实)`）；`出墨%`=交付 α>0 占实心的比例。
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val pool = MonochromeMask::class.java.getDeclaredField("pool").apply { isAccessible = true }
    .get(MonochromeMask) as ThreadLocal<*>
  val files = args.filter { !it.startsWith("--") }.flatMap { a ->
    val f = File(a)
    if (f.isDirectory) f.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }.sortedBy { it.name }
    else listOf(f)
  }
  // `--hist`：只打**源图自己的 α 直方图**。为什么要有这一档：`盘纯=100` 说这块底是一种颜色，
  //   而 `实心=54.8` 说这张图只有一半的格是 α=255 —— 两句合起来的意思是**另一半压根不是不透明的**，
  //   于是"底吞掉了整张"这个说法从一开始就问错了问题。翻译那张到底是不是半透明，得直接数。
  if (args.contains("--hist")) {
    for (f in files) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val p = IntArray(im.width * im.height); im.getRGB(0, 0, im.width, im.height, p, 0, im.width)
      val bins = IntArray(9)   // 0 / 1-31 / 32-63 / … / 224-254 / 255
      for (v in p) bins[(v ushr 24).let { if (it == 0) 0 else if (it == 255) 8 else (it - 1) / 32 + 1 }]++
      val n = p.size
      // 顺手把**每一档 α 上最多的那一种 RGB** 打出来：只数 α 不够 —— 翻译那一档 34.6% 到底是
      // "半透明的蓝带"还是"抗锯齿的边缘"，看它自己的颜色就知道。
      val byBin = HashMap<Int, HashMap<Int, IntArray>>()
      for (v in p) {
        val a = v ushr 24
        val b = if (a == 0) 0 else if (a == 255) 8 else (a - 1) / 32 + 1
        val m = byBin.getOrPut(b) { HashMap() }
        val k = v and 0x00FFFFFF
        val c = m.getOrPut(k) { IntArray(1) }
        c[0]++
      }
      println(f.name.removeSuffix(".png").take(44) + "  " +
        bins.mapIndexed { i, c -> "%s=%.1f%%".format(
          if (i == 0) "0" else if (i == 8) "255" else "${i * 32}-${i * 32 + 31}", c * 100.0 / n) }
          .joinToString(" "))
      for (b in 1..7) {
        val m = byBin[b] ?: continue
        if (m.values.sumOf { it[0] } * 1000 < p.size) continue
        var bk = 0; var bn = 0
        for ((k, c) in m) if (c[0] > bn) { bn = c[0]; bk = k }
        println("      α档 ${b * 32}-${b * 32 + 31} 主色=#%06X 占全图 %.1f%%（这一档内 %.0f%%）".format(
          bk, bn * 100.0 / p.size, bn * 100.0 / m.values.sumOf { it[0] }))
      }
    }
    return
  }
  // `--cols`：**这块底到底是哪一个颜色、墨是哪一个颜色、各自交出去多少**。
  //   `盘纯=100` 只说"底是一种颜色"，不说**是哪种**。翻译那张按 α 分完还剩一个谜：
  //   蓝带既不在墨里（`出墨` 只有实心的 4.8%），按 `盘纯` 也不该在底里。这一档就是去数清楚。
  if (args.contains("--cols")) {
    for (f in files) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val ew = im.width; val eh = im.height
      val sc = BufferedImage(ew, eh, BufferedImage.TYPE_INT_ARGB)
      val gg = sc.createGraphics(); gg.drawImage(im, 0, 0, null); gg.dispose()
      val p = IntArray(ew * eh); sc.getRGB(0, 0, ew, eh, p, 0, ew)
      val r = runCatching { MonochromeMask.build(p, ew, eh) }.getOrNull() ?: continue
      val mk = r.mask ?: continue
      val st = pool.get() ?: continue
      val pl = pfld(st, "plate") as ByteArray
      val plates = HashMap<Int, IntArray>(); val inks = HashMap<Int, IntArray>()
      for (i in 0 until ew * eh) {
        if ((p[i] ushr 24) != 255) continue
        val m = if (pl[i] != 0.toByte()) plates else inks
        val v = m.getOrPut(p[i] and 0x00FFFFFF) { IntArray(2) }
        v[0]++; v[1] += mk[i] ushr 24
      }
      fun top(m: HashMap<Int, IntArray>, tag: String) {
        val tot = m.values.sumOf { it[0] }
        println("  $tag 共 $tot 格")
        m.entries.sortedByDescending { it.value[0] }.take(6).forEach {
          println("     #%06X  %6d 格 %5.1f%%  交付α均 %5.1f".format(
            it.key, it.value[0], it.value[0] * 100.0 / maxOf(tot, 1), it.value[1].toDouble() / it.value[0]))
        }
      }
      println(f.name)
      top(plates, "底"); top(inks, "墨")
    }
    return
  }
  // `--rimink`：**这块底的外轮廓，是不是和墨共用的**。
  //   前面四把尺子（吞实心比例 / 单色 / 纯度 / 占画布）在"该擦"与"该留"两侧都是反着排的，
  //   但它们量的全是"这块底自己长什么样"。没量过的是**另一侧**：
  //   一块真正的承载面，它的边界就是画布的空气边界，**墨不该摸到那条边**；
  //   而太极那种"一块东西的两半"，两半**都**摸着自己的轮廓。
  //   所以数：把 不透明↔透明 那一圈（外扩几步）里的实心格按 底/墨 分开，看墨占多少。
  //   满幅的图（没有空气边界）这一列没有定义，报 `-`。
  if (args.contains("--rimink")) {
    val RIM = 3
    for (f in files) {
      val im = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val ew = im.width; val eh = im.height
      val p = IntArray(ew * eh); im.getRGB(0, 0, ew, eh, p, 0, ew)
      val r = runCatching { MonochromeMask.build(p, ew, eh) }.getOrNull() ?: continue
      val st = pool.get() ?: continue
      val pl = pfld(st, "plate") as ByteArray
      val op = BooleanArray(ew * eh) { (p[it] ushr 24) > 128 }
      if ((0 until ew * eh).count { op[it] } * 20 >= ew * eh * 19) {
        println("%-44s  满幅（空气不到 5%%），这一列没有定义".format(f.name.removeSuffix(".png").take(44))); continue
      }
      var rim = BooleanArray(ew * eh)
      for (y in 1 until eh - 1) for (x in 1 until ew - 1) {
        val i = y * ew + x
        if (!op[i]) continue
        if (!op[i - 1] || !op[i + 1] || !op[i - ew] || !op[i + ew]) rim[i] = true
      }
      repeat(RIM) {
        val nx = BooleanArray(ew * eh)
        for (y in 0 until eh) for (x in 0 until ew) {
          val i = y * ew + x
          if (rim[i]) { nx[i] = true; continue }
          if (x > 0 && rim[i - 1] || x < ew - 1 && rim[i + 1] || y > 0 && rim[i - ew] ||
            y < eh - 1 && rim[i + ew]) nx[i] = true
        }
        rim = nx
      }
      var rp = 0; var ri = 0
      for (i in 0 until ew * eh) {
        if (!rim[i] || (p[i] ushr 24) != 255) continue
        if (pl[i] != 0.toByte()) rp++ else ri++
      }
      val tot = rp + ri
      println("%-44s  轮廓实心 %6d 格  底 %5.1f%%  墨 %5.1f%%".format(
        f.name.removeSuffix(".png").take(44), tot,
        if (tot == 0) 0.0 else rp * 100.0 / tot, if (tot == 0) 0.0 else ri * 100.0 / tot))
    }
    return
  }
  println("%-46s %5s %5s %5s %5s %5s %5s  %s".format("icon", "边触", "实心", "盘吞", "出墨", "单色", "盘纯", "H/底占/族/盒布/盘实/零"))
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val src = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g0 = src.createGraphics()
    g0.color = java.awt.Color(0, 0, 0, 0); g0.fillRect(0, 0, edge, edge)
    g0.drawImage(img, 0, 0, edge, edge, null)
    g0.dispose()
    val px = IntArray(edge * edge); src.getRGB(0, 0, edge, edge, px, 0, edge)
    val r = runCatching { MonochromeMask.build(px, edge, edge) }.getOrNull() ?: continue
    val m = r.mask ?: continue
    val s = pool.get() ?: continue
    var solid = 0; var edgePx = 0; var edgeSolid = 0
    for (y in 0 until edge) for (x in 0 until edge) {
      val i = y * edge + x
      val op = (px[i] ushr 24) == 255
      if (op) solid++
      if (x == 0 || y == 0 || x == edge - 1 || y == edge - 1) { edgePx++; if (op) edgeSolid++ }
    }
    val ink = (0 until edge * edge).count { m[it] ushr 24 > 0 }
    // **这张图到底有几种颜色**：把源实心格按粗 RGB 桶归，取最大那一桶的均色当"主色"，
    // 再数离它 ΔE ≤ 30 的占实心多少。豁免那道的前提是「整张纯色」，那就得真的纯色才算。
    val cnt = HashMap<Int, IntArray>()   // key = 粗桶, val = [n, sumR, sumG, sumB]
    for (p in px) if ((p ushr 24) == 255) {
      val k = (((p shr 16) and 0xFF) ushr 4 shl 8) or (((p shr 8) and 0xFF) ushr 4 shl 4) or ((p and 0xFF) ushr 4)
      val v = cnt.getOrPut(k) { IntArray(4) }
      v[0]++; v[1] += (p shr 16) and 0xFF; v[2] += (p shr 8) and 0xFF; v[3] += p and 0xFF
    }
    var topR = 0; var topG = 0; var topB = 0; var topN = 0
    for (v in cnt.values) if (v[0] > topN) {
      topN = v[0]; topR = v[1] / v[0]; topG = v[2] / v[0]; topB = v[3] / v[0]
    }
    var near = 0
    if (topN > 0) for (p in px) if ((p ushr 24) == 255) {
      if (de(p shr 16 and 0xFF, (p shr 8) and 0xFF, p and 0xFF, topR, topG, topB) <= 30) near++
    }
    val mono = if (solid == 0) 100.0 else near * 100.0 / solid
    // **这块盘自己是一种颜色吗**：豁免那道的前提是「整张纯色 → 什么都是底」，
    //   所以真正该量的是**被判成底的那些格**纯不纯 —— 不是整张图。
    //   华为应用市场：底 = 那一块红，纯 → 豁免是对的。Google 翻译：底 = 蓝带 **加** 白页，
    //   两种毫不相干的色 → "什么都是底"这句话自己就站不住。
    val plate = runCatching { pfld(s, "plate") as ByteArray }.getOrNull() ?: ByteArray(edge * edge)
    val pcnt = HashMap<Int, IntArray>()
    var pSolid = 0
    for (i in 0 until edge * edge) {
      if (plate[i] == 0.toByte() || (px[i] ushr 24) != 255) continue
      pSolid++
      val p = px[i]
      val k = ((p shr 16 and 0xFF) ushr 4 shl 8) or (((p shr 8) and 0xFF) ushr 4 shl 4) or ((p and 0xFF) ushr 4)
      val v = pcnt.getOrPut(k) { IntArray(4) }
      v[0]++; v[1] += p shr 16 and 0xFF; v[2] += (p shr 8) and 0xFF; v[3] += p and 0xFF
    }
    var pR = 0; var pG = 0; var pB = 0; var pn = 0
    for (v in pcnt.values) if (v[0] > pn) { pn = v[0]; pR = v[1] / v[0]; pG = v[2] / v[0]; pB = v[3] / v[0] }
    var pNear = 0
    if (pn > 0) for (i in 0 until edge * edge) {
      if (plate[i] == 0.toByte() || (px[i] ushr 24) != 255) continue
      val p = px[i]
      if (de(p shr 16 and 0xFF, (p shr 8) and 0xFF, p and 0xFF, pR, pG, pB) <= 30) pNear++
    }
    val pPure = if (pSolid == 0) 100.0 else pNear * 100.0 / pSolid
    val rep = r.report.toString()
    fun num(key: String): String = Regex(Regex.escape(key) + "=?([^ ]+)").find(rep)?.groupValues?.get(1) ?: "-"
    val gn = num("盘实").split("/").first().toIntOrNull() ?: 0
    val inn = num("盘实").split("/").getOrNull(1)?.toIntOrNull() ?: 0
    println("%-46s %5.2f %5.1f %5.0f %5.1f %5.0f %5.0f  H=%s 底占=%s 族=%s 盒布=%s 盘实=%s/%s 零=%s".format(
      f.name.removeSuffix(".png").take(46),
      edgeSolid * 100.0 / maxOf(edgePx, 1),
      solid * 100.0 / (edge * edge),
      if (gn + inn == 0) 0.0 else gn * 100.0 / (gn + inn),
      if (solid == 0) 0.0 else ink * 100.0 / solid,
      mono, pPure,
      num("H"), num("底占"), num("族"), num("盒布"), gn, inn, num("零")))
  }
}

/** 加权的 RGB 距离（当 ΔE 用够了：这一列只分"一种颜色"和"两种明显不同的颜色"，不差那几档）。 */
private fun pfld(o: Any, name: String): Any {
  for (f in o::class.java.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun de(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Int {
  val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
  val rm = (r1 + r2) / 2
  return kotlin.math.sqrt(
    ((2 + rm / 256.0) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256.0) * db * db).toDouble()
  ).toInt()
}
