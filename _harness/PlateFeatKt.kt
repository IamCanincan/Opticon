package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * **「该留墨的大平块」vs「该擦的漏出背景」—— 判别线的定量台**（2026-10-16）。
 *
 * 路 B（`eraseFaintPlate`）把 `FaintKt` 的「底=淡墨」从 3 张清到 0 张，但它把一条**必须不破坏的
 * 负例**也擦了：`SelfTest` 12b 的「四块四档」（四象限四色方块，各约 13.7% 画布、源平源实）。
 * 那条性质说的是"色阶图四块都该有墨"，**它是对的**。
 *
 * 所以缺的是**第四道门**：能把这两类分开。本台把候选特征在**两个 population** 上一次摊开 ——
 * 这正是路 A 栽过的地方（**population 选错、没摊分布就定线**），这次不拍。
 *
 * ## 两个 population
 *  · **负例（不许擦）**：`--synth` 现场合成 `SelfTest` 12b 那张（深蓝底铺满画布 + 四个**内缩**的
 *    彩色方块，a0=5%/a1=42%/b0=55%/b1=92%）。
 *  · **正例（该擦）**：真机语料（`rec_model`），用**与 `eraseFaintPlate` 完全相同**的那三条判据
 *    找出"大平块"，再看它们各自长什么样。
 *
 * ## 摊出来的候选特征（每个合格块一行）
 *  · `贴边` —— 这块的外接框是否贴到画布四边之一（背景铺满 → 贴；内缩方块 → 不贴）；
 *  · `框占%` —— 外接框面积占画布多少（背景那一块的外接框 ≈ 整张；一块方块 ≈ 它自己那点）；
 *  · `块主色`/`块主色%` —— 这块里最常见的那个源色、及它在块内的占比（是不是"一整块同一个色"）；
 *  · `全图主色`/`同色` —— 画布上（源）最常见的那个色、以及块主色是不是就是它（是不是"这一张的底"）；
 *  · `同图块数` —— 这张图上一共有几块合格的大平块（色阶图是**多块不同档并存**）；
 *  · `余实心%` —— 擦掉这块之后，画布上还剩多少高档墨（α ≥ 128）。
 *
 * ⚠ **量的是"最终掩码"**（`MonochromeMask.build` 的返回值）。所以要把本台跑在**关掉路 B** 的
 *   那版源码上，否则路 B 已经把块擦了、正例 population 一张都点不出来（本台第一版就是这个空表）。
 *   关法：把 `buildOnce` 里那句 `eraseFaintPlate(out, …)` 临时 `if (false)` 掉再 `sh build.sh`。
 *
 * 用法：`sh run.sh harness.PlateFeatKtKt --synth [--s=384] [--min=10] <图标目录...>`
 */
fun main(args: Array<String>) {
  val S = args.firstOrNull { it.startsWith("--s=") }?.substringAfter("=")?.toInt() ?: 384
  val minPct = args.firstOrNull { it.startsWith("--min=") }?.substringAfter("=")?.toInt() ?: 10
  val wantSynth = args.any { it == "--synth" } || args.none { !it.startsWith("--") }

  println("%-42s %-4s %-7s %-5s %-7s %-9s %-7s %-9s %-5s %-7s %s".format(
    "图标", "桶档", "面积%", "贴边", "框占%", "块主色", "块主色%", "全图主色", "同色", "余实心%", "同图块数"))

  if (wantSynth) {
    // SelfTest 12b 的合成输入：深蓝底 + 四个内缩的彩色方块
    val a0 = S * 5 / 100; val a1 = S * 42 / 100; val b0 = S * 55 / 100; val b1 = S * 92 / 100
    val p = IntArray(S * S)
    fill(p, S, 0, 0, S, S, 0xFF1E2878.toInt())              // 深蓝底 30,40,120
    fill(p, S, a0, a0, a1, a1, 0xFFF5F5F5.toInt())          // 白 245
    fill(p, S, b0, a0, b1, a1, 0xFFFAC828.toInt())          // 黄 250,200,40
    fill(p, S, a0, b0, a1, b1, 0xFF28BE5A.toInt())          // 绿 40,190,90
    fill(p, S, b0, b0, b1, b1, 0xFF823CD2.toInt())          // 紫 130,60,210
    probe("合成·四块四档(SelfTest 12b)", p, S, minPct)
  }

  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }
  val cls = HashMap<String, String>(); val flat = HashSet<String>()
  for (d in dirs) {
    val ix = File(d, "index.tsv"); if (!ix.isFile) continue
    for (l in ix.readLines().drop(1)) {
      val c = l.split("\t")
      if (c.size > 5) cls[c[2]] = c[5]
      if (c.size > 18 && c[18] != "-") flat.add(c[2])
    }
  }
  for (d in dirs) {
    val files = d.listFiles()?.filter {
      it.extension == "png" && !it.name.contains("preview") &&
        !it.name.endsWith("_modelbg.png") && !it.name.endsWith("_winab.png") && !it.name.endsWith("_ab.png")
    }?.sortedBy { it.name } ?: continue
    for (f in files) {
      val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
      val px = raster(img, S)
      val adaptive = (cls[f.name] ?: "").contains("Adaptive")
      val under = File(f.parentFile, f.name.removeSuffix(".png") + "_modelbg.png")
        .takeIf { it.isFile }?.let { raster(ImageIO.read(it), S) }
      val win = IconCropWindow.of(px, S, S)?.takeIf { it.zoom <= 1.82f }
      val mask = runCatching {
        MonochromeMask.build(px, S, S, adaptive && !flat.contains(f.name), under,
          win != null && win.plate && win.keepPct >= 50).mask
      }.getOrNull() ?: continue
      val imgMode = modal(px, null, 0, S)
      val blocks = blocks(mask, px, S, minPct)
      if (blocks.isEmpty()) continue
      var solid = 0
      for (i in mask.indices) if (((mask[i] ushr 24) and 0xff) >= 128) solid++
      for (b in blocks) {
        var rest = solid
        for (m in b.members) if (((mask[m] ushr 24) and 0xff) >= 128) rest--
        println("%-42s %-4d %-7s %-5s %-7s %-9s %-7s %-9s %-5s %-7s %d".format(
          f.name.take(42), b.level, "%.1f".format(b.area * 100.0 / (S * S)),
          b.edgeN,
          "%.1f".format(b.bx() * b.by() * 100.0 / (S * S)),
          "#%06X".format(b.mode), "%.1f".format(b.modeN * 100.0 / b.area),
          "#%06X".format(imgMode.first), if (b.mode == imgMode.first) "是" else "否",
          "%.1f".format(rest * 100.0 / (S * S)), blocks.size))
      }
    }
  }
}

/** 与 `eraseFaintPlate` **完全相同**的三条判据下，一张图里合格的大平块。 */
private class Blk(
  val level: Int, val area: Int, val edgeN: Int,
  val bx0: Int, val by0: Int, val bx1: Int, val by1: Int,
  val mode: Int, val modeN: Int, val members: IntArray,
) {
  fun bx() = bx1 - bx0 + 1
  fun by() = by1 - by0 + 1
}

private fun blocks(mask: IntArray, px: IntArray, S: Int, minPct: Int): List<Blk> {
  val total = S * S
  val areaMin = total * minPct / 100
  val seen = BooleanArray(total)
  val stack = IntArray(total)
  val members = IntArray(total)
  val res = ArrayList<Blk>()
  for (b in 1..7) {
    java.util.Arrays.fill(seen, false)
    for (s in 0 until total) {
      if (seen[s] || (((mask[s] ushr 24) and 0xff) shr 4) != b) continue
      var sp = 0; stack[sp++] = s; seen[s] = true
      var mN = 0; members[mN++] = s
      var lLo = 255; var lHi = 0; var opaque = 0
      var eT = false; var eB = false; var eL = false; var eR = false
      var x0 = S; var x1 = -1; var y0 = S; var y1 = -1
      while (sp > 0) {
        val cur = stack[--sp]; val cy = cur / S; val cx = cur % S
        if (cy == 0) eT = true; if (cy == S - 1) eB = true
        if (cx == 0) eL = true; if (cx == S - 1) eR = true
        if (cx < x0) x0 = cx; if (cx > x1) x1 = cx; if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
        val p = px[cur]; val sa = (p ushr 24) and 0xff
        if (sa >= 128) { opaque++; val lu = luma(p); if (lu < lLo) lLo = lu; if (lu > lHi) lHi = lu }
        if (cx > 0) { val i = cur - 1; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i; members[mN++] = i } }
        if (cx < S - 1) { val i = cur + 1; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i; members[mN++] = i } }
        if (cy > 0) { val i = cur - S; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i; members[mN++] = i } }
        if (cy < S - 1) { val i = cur + S; if (!seen[i] && (((mask[i] ushr 24) and 0xff) shr 4) == b) { seen[i] = true; stack[sp++] = i; members[mN++] = i } }
      }
      if (mN < areaMin) continue
      if (opaque * 100 < mN * 80) continue
      if (opaque == 0 || lHi - lLo > 32) continue
      val (mode, modeN) = modal(px, members, mN, S)
      val edgeN = (if (eT) 1 else 0) + (if (eB) 1 else 0) + (if (eL) 1 else 0) + (if (eR) 1 else 0)
      res.add(Blk(b * 16, mN, edgeN, x0, y0, x1, y1, mode, modeN, members.copyOf(mN)))
    }
  }
  return res
}

/** 画布（或某个成员集合）里最常见的那个**源色**（精确 RGB，只数不透明的格）。 */
private fun modal(px: IntArray, members: IntArray?, n: Int, S: Int): Pair<Int, Int> {
  val cnt = HashMap<Int, Int>()
  if (members == null) {
    for (i in px.indices) { val p = px[i]; if ((p ushr 24) and 0xff >= 128) cnt[p and 0xFFFFFF] = (cnt[p and 0xFFFFFF] ?: 0) + 1 }
  } else {
    for (k in 0 until n) { val p = px[members[k]]; if ((p ushr 24) and 0xff >= 128) cnt[p and 0xFFFFFF] = (cnt[p and 0xFFFFFF] ?: 0) + 1 }
  }
  var best = -1; var bn = 0
  for ((k, c) in cnt) if (c > bn) { bn = c; best = k }
  return best to bn
}

private fun probe(tag: String, px: IntArray, S: Int, minPct: Int) {
  val r = runCatching { MonochromeMask.build(px, S, S) }.getOrNull()
  if (r?.mask == null) { println("$tag → 无掩码"); return }
  val bl = blocks(r.mask, px, S, minPct)
  val imgMode = modal(px, null, 0, S)
  var solid = 0
  for (i in r.mask.indices) if (((r.mask[i] ushr 24) and 0xff) >= 128) solid++
  println("$tag → 合格大平块 ${bl.size} 块；实心墨 ${"%.1f".format(solid * 100.0 / (S * S))} 点；" +
    "全图主色 #${"%06X".format(imgMode.first)}/${"%.1f".format(imgMode.second * 100.0 / (S * S))}%")
  for (b in bl) {
    var rest = solid
    for (m in b.members) if (((r.mask[m] ushr 24) and 0xff) >= 128) rest--
    println("%-42s %-4d %-7s %-5s %-7s %-9s %-7s %-9s %-5s %-7s %d".format(
      "  └ 块", b.level, "%.1f".format(b.area * 100.0 / (S * S)),
      b.edgeN,
      "%.1f".format(b.bx() * b.by() * 100.0 / (S * S)),
      "#%06X".format(b.mode), "%.1f".format(b.modeN * 100.0 / b.area),
      "#%06X".format(imgMode.first), if (b.mode == imgMode.first) "是" else "否",
      "%.1f".format(rest * 100.0 / (S * S)), bl.size))
  }
}

private fun fill(px: IntArray, S: Int, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
  for (y in y0 until y1) for (x in x0 until x1) px[y * S + x] = argb
}

private fun luma(argb: Int): Int {
  val r = (argb shr 16) and 0xff; val g = (argb shr 8) and 0xff; val b = argb and 0xff
  return (r * 77 + g * 151 + b * 28) shr 8
}

private fun raster(img: java.awt.image.BufferedImage, edge: Int): IntArray {
  val s0 = IntArray(img.width * img.height)
  img.getRGB(0, 0, img.width, img.height, s0, 0, img.width)
  val px = IntArray(edge * edge)
  for (y in 0 until edge) for (x in 0 until edge) {
    val sy = (y.toLong() * img.height / edge).toInt().coerceAtMost(img.height - 1)
    val sx = (x.toLong() * img.width / edge).toInt().coerceAtMost(img.width - 1)
    px[y * edge + x] = s0[sy * img.width + sx]
  }
  return px
}
