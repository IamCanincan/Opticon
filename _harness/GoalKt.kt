package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **把主人说的那张"目标"画出来给他确认**（不碰模型）：translate —— 右半边全黑，「文」是镂空的。
 *
 * 这张图标是两张卡：左边蓝气泡（白 G 镂空），右边灰盘（深灰「文」）+ 气泡投在盘上的那道影。
 * 主人要的是：**以气泡那条斜边为界，右边整片铺墨，「文」从墨里镂空回空气**；左边原样。
 *
 * 影和「文」都在盘上偏暗 —— 所以先量一次亮度分布，看这两堆分不分得开（`--hist`）。
 *
 * 用法：`harness.GoalKt <目录> [名字子串] [--hist]`
 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val needle = args.getOrNull(1) ?: "translate"
  val hist = args.any { it == "--hist" }
  val via = args.filter { it.startsWith("--via=") }.firstOrNull()?.substringAfter("=")?.toIntOrNull()
  val edge = 384
  val f = dir.listFiles { x -> x.extension == "png" && x.name.contains(needle) &&
      !x.name.endsWith("_modelbg.png") }!!.first()
  // `--via=425` = 先把源图绕一种尺寸再落回 384 —— 模拟桌面用**另一种前景层尺寸**再画一遍。
  // 真机实测同一张 translate 有 `@432x432` 与 `@425x425` 两种，而掩码不同（见 MonoDump 里 dedupeKey）。
  fun load(g: File): IntArray? {
    if (via == null) return grab(g, edge)
    val img = runCatching { ImageIO.read(g) }.getOrNull() ?: return null
    val mid = BufferedImage(via, via, BufferedImage.TYPE_INT_ARGB)
    val h = mid.createGraphics(); h.drawImage(img, 0, 0, via, via, null); h.dispose()
    return grabImg(mid, edge)
  }
  val src = load(f)!!   // rec_model 里主文件本身就是装机那一张
  val un = File(dir, f.nameWithoutExtension + "_modelbg.png")
  val under = if (un.isFile) load(un) else null
  if (under == null) { println("没有 _modelbg，判不了"); return }

  // 蓝气泡 = 偏蓝的不透明格。每行最右边那一格气泡 → 该行斜边位置
  val bnd = IntArray(edge) { edge }
  for (y in 0 until edge) {
    var last = -1
    for (x in 0 until edge) if (isBlue(src[y * edge + x])) last = x
    bnd[y] = if (last >= 0) last + 1 else 0
  }
  val L = IntArray(edge * edge) { luma(under[it]) }
  // 盘主色亮度 = 斜边右边不透明格里 L 的众数
  val h = IntArray(256)
  for (y in 0 until edge) for (x in bnd[y] until edge) {
    val i = y * edge + x
    if (((under[i] ushr 24) and 0xff) >= 128) h[L[i]]++
  }
  val plateL = (0..255).maxByOrNull { h[it] } ?: 0
  if (hist) {
    println("斜边每行 x：min=${bnd.min()} 中位=${bnd.sorted()[edge / 2]} max=${bnd.max()}")
    println("盘主色亮度 L=$plateL")
    var n = 0; for (y in 0 until edge) for (x in bnd[y] until edge) if (((under[y * edge + x] ushr 24) and 0xff) >= 128) n++
    println("斜边右边的盘格 = $n（占画布 ${"%.1f".format(n * 100.0 / (edge * edge))}%）")
    println("相对盘主色的亮度差直方图（负=比盘暗）：")
    val d = IntArray(257)
    for (y in 0 until edge) for (x in bnd[y] until edge) {
      val i = y * edge + x
      if (((under[i] ushr 24) and 0xff) < 128) continue
      val k = (L[i] - plateL) + 128
      if (k in 0..256) d[k]++
    }
    for (k in 0 until 128) if (d[k] > 0) println("  ΔL=${k - 128}  ${d[k]}")
    var far = 0; for (k in 0 until 128 - 40) far += d[k]
    var near = 0; for (k in 128 - 40 until 128) near += d[k]
    println("比盘暗 >40 的（那是「文」）= $far；暗 0..40（那是影/抗锯齿）= $near")
    // 合成图里三块各自多亮 —— 决定"右半铺墨"能不能从亮度斜坡里长出来
    fun medL(sel: (Int) -> Boolean): Int {
      val v = ArrayList<Int>(); for (p in src) if (sel(p)) v.add(luma(p))
      v.sort(); return if (v.isEmpty()) -1 else v[v.size / 2]
    }
    println("合成图：气泡 L 中位=${medL { isBlue(it) }}  盘 L 中位=${medL { (((it ushr 24) and 0xff) >= 128) && !isBlue(it) && luma(it) > 200 }}  「文」 L 中位=${medL { (((it ushr 24) and 0xff) >= 128) && !isBlue(it) && luma(it) < 160 }}")
    // 主人报「"文"左边还有一小块也是空着的」—— 把**会被镂空的那堆格按 Δ 分带定位**，
    // 看那块到底是字的一部分、还是别的东西（Δ 40..70 那一小条就是嫌疑：它贴着斜边）。
    for (lo in intArrayOf(40, 70, 100)) {
      val hi = if (lo == 40) 70 else if (lo == 70) 100 else 256
      var n = 0; var sx = 0; var sy = 0; var x0 = 9999; var x1 = -1; var y0 = 9999; var y1 = -1
      for (y in 0 until edge) for (x in 0 until edge) {
        val i = y * edge + x
        if (((under[i] ushr 24) and 0xff) < 128) continue
        val dd = plateL - L[i]
        if (dd < lo || dd >= hi) continue
        n++; sx += x; sy += y
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
      }
      if (n == 0) { println("  Δ $lo..$hi：0 格"); continue }
      println("  Δ $lo..$hi：$n 格  中心(=${sx / n},${sy / n}) 框 x$x0..$x1 y$y0..$y1  占画布 ${"%.2f".format(n * 100.0 / (edge * edge))}%")
    }
    println("  （画布 $edge×$edge，斜边中位 x=${bnd.sorted()[edge / 2]}；「文」的框在 x>斜边 那一侧）")
    // 主人说的那块"空着的"要是在**斜边附近**，那它就不是镂空掉的标记，而是**被当成"前景盖住了"而跳过**的盘：
    // 判据是「合成图那一格离背景层 ≥24」，而气泡与盘之间那道**抗锯齿边**正好落在这个数上 ——
    // 它是灰蓝混色，不是被盖住，但我们不去铺墨 → 留在模型原来给的"空气"上。
    var sk = 0; var skNear = 0; var skFar = 0
    var minD = 9999; var maxD = -9999; var sumD = 0
    for (y in 0 until edge) for (x in bnd[y] until edge) {
      val i = y * edge + x
      if (((under[i] ushr 24) and 0xff) < 128) continue
      if (plateL - L[i] >= 40) continue                       // 那是标记，本来就该镂空
      if (Math.abs(luma(src[i]) - L[i]) < 24) continue        // 露在外面的盘 → 会被铺墨
      sk++
      val d = x - bnd[y]
      sumD += d; if (d < minD) minD = d; if (d > maxD) maxD = d
      if (d <= 6) skNear++ else skFar++
    }
    println("  盘上却被判成「被前景盖住」而跳过的格 = $sk（占画布 ${"%.2f".format(sk * 100.0 / (edge * edge))}%）" +
      "  离斜边 ≤6 格的 = $skNear、更远的 = $skFar  平均离边 ${(if (sk == 0) 0 else sumD / sk)} 格  范围 $minD..$maxD")
    // 判据行里那三格 —— 换一种前景层尺寸再走一遍，看设备/判据台那个"对不上"是不是尺寸给的
    val rep = MonochromeMask.build(src, edge, edge, false, under).report
    fun fld(k: String) = Regex("$k[= ][^ ]*").find(rep)?.value ?: "$k=?"
    println("判据行" + (if (via == null) "" else "（源图先绕 $via 再落回 384）") + "：" +
      "${fld("分档")} ${fld("盘刀")} ${fld("盘候")} ${fld("框内")} ${fld("淡")} ${fld("顶占比")}")
    return
  }

  val cur = MonochromeMask.build(src, edge, edge, false, under).mask!!
  val goal = cur.copyOf()
  var filled = 0; var hollow = 0
  for (y in 0 until edge) for (x in bnd[y] until edge) {
    val i = y * edge + x
    if (((under[i] ushr 24) and 0xff) < 128) continue
    goal[i] = if (L[i] < plateL - 40) { hollow++; 0 } else { filled++; (255 shl 24) or (goal[i] and 0x00FFFFFF) }
  }
  val out = BufferedImage(edge * 3 + 16, edge, BufferedImage.TYPE_INT_RGB)
  val g = out.createGraphics()
  g.drawImage(ImageIO.read(f), 0, 0, edge, edge, null)
  g.drawImage(srcOn(cur, edge, true), edge + 8, 0, null)
  g.drawImage(srcOn(goal, edge, true), edge * 2 + 16, 0, null)
  g.dispose()
  val o = File("D:/tmp/goal_translate.png")
  ImageIO.write(out, "png", o)
  println("写出 ${o.absolutePath}（源｜装机｜目标）：右半铺墨 $filled 格、镂空「文」$hollow 格")
}

private fun luma(p: Int): Int =
  (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt()

private fun isBlue(p: Int): Boolean {
  if (((p ushr 24) and 0xff) < 128) return false
  val r = (p shr 16) and 0xff; val b = p and 0xff
  return b - r > 40
}

private fun grab(f: File, edge: Int): IntArray? = grabImg(runCatching { ImageIO.read(f) }.getOrNull(), edge)

private fun grabImg(img: BufferedImage?, edge: Int): IntArray? {
  if (img == null) return null
  val out = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics(); g.drawImage(img, 0, 0, edge, edge, null); g.dispose()
  return IntArray(edge * edge).also { out.getRGB(0, 0, edge, edge, it, 0, edge) }
}

/** 把掩码当不透明度压在浅盘上（白天那一联），墨色取桌面用的深墨。 */
private fun srcOn(mask: IntArray, edge: Int, day: Boolean): BufferedImage {
  val plate = if (day) 0xFFEDEDED.toInt() else 0xFF2B2B2B.toInt()
  val ink = if (day) 0xFF1F1F1F.toInt() else 0xFFE8E8E8.toInt()
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
