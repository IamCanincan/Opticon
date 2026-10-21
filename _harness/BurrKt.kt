package harness

import com.iamcanincan.opticon.circle.IconRough
import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * 【主形】那把毛刺尺的**换尺台**（只量，不改生产）。
 *
 * 要回答一个问题：把 `周长/√面积` 换成等周比 `周长²/面积` 之后，
 * **金标准两侧的名单各落在哪儿** —— 旧尺的失败不是"阈值不准"，是**方向错**：
 * 它把"整块平滑消失"（uu远程的箭头擦剩半个、azure 的小人只剩一个头）读成小抬升放过去，
 * 又把"清掉一小块孤立碎件"（malody 那四块脚）读成大抬升顶回来。
 *
 * 用法：`sh run.sh harness.BurrKtKt rec_model [--edge=384] [--sweep]`
 *   每行 = 一张：`旧Δ` `新Δ` `墨格 前→后`，末尾按候选阈值摊一张"哪一侧进来了"的表。
 */
private fun loadSq(f: File, side: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val w = img.width; val h = img.height
  val src = IntArray(w * h); img.getRGB(0, 0, w, h, src, 0, w)
  if (w == side && h == side) return src
  val out = IntArray(side * side)
  for (y in 0 until side) for (x in 0 until side) {
    val sy = (y.toLong() * h / side).toInt().coerceAtMost(h - 1)
    val sx = (x.toLong() * w / side).toInt().coerceAtMost(w - 1)
    out[y * side + x] = src[sy * w + sx]
  }
  return out
}
private fun alphaOf(m: IntArray?) = IntArray(m?.size ?: 0) { (m!![it] ushr 24) and 0xff }
private fun inkCells(al: IntArray) = al.count { it > 0 }

/** 金标准（来源：`MonochromeMask.trim` 的文档 + 2026-10-19 看图账）。 */
private val KEEP = listOf("me.mugzone.emiria", "com.jingdong.app.mall", "com.eg.android.AlipayGphone",
  "com.baidu.tieba", "com.autonavi.minimap", "com.sina.weibo")
private val MUST_CATCH = listOf("com.netease.uuremote", "com.azure.authenticator")
private val OLD_REJECT = listOf("tv.danmaku.bili", "com.yiban.app", "cn.emacampers", "com.sgameglobal",
  "com.neowizgames.game.browndust2", "com.xiaojiezzl.yhdj", "com.jiazhao.jk")

/**
 * ## `--feet`：这把尺**存在的理由**那一测
 * 台账那条补刀（"留下的实心碎件够不到最大块两成就一并退掉"）在旧尺下**全语料 0 张开火**，
 * 因为旧尺 `周长/√面积` 在"整块删掉一块面积"时自己抬起来。所以真正的金标准不是那两张坏图，
 * 而是一条**按定义必须成立**的性质：**删掉一小块孤立的实心件，这把尺不许抬。**
 * 这一档对每张图：把【主形】割完之后**够不到最大块两成的孤立件**删掉，两把尺各量一次前/后。
 */
private fun feet(m: IntArray, side: Int, frac: Int): Pair<IntArray, Int> {
  val n = side * side
  val ink = BooleanArray(n) { m[it] > 0 }
  val lab = IntArray(n) { -1 }; val q = IntArray(n)
  val areas = ArrayList<Int>()
  for (s in 0 until n) {
    if (!ink[s] || lab[s] >= 0) continue
    val id = areas.size; var hd = 0; var tl = 0; var a = 0
    lab[s] = id; q[tl++] = s
    while (hd < tl) {
      val i = q[hd++]; a++
      val y = i / side; val x = i - y * side
      if (x > 0 && ink[i - 1] && lab[i - 1] < 0) { lab[i - 1] = id; q[tl++] = i - 1 }
      if (x < side - 1 && ink[i + 1] && lab[i + 1] < 0) { lab[i + 1] = id; q[tl++] = i + 1 }
      if (y > 0 && ink[i - side] && lab[i - side] < 0) { lab[i - side] = id; q[tl++] = i - side }
      if (y < side - 1 && ink[i + side] && lab[i + side] < 0) { lab[i + side] = id; q[tl++] = i + side }
    }
    areas += a
  }
  if (areas.size < 2) return m to 0
  val big = areas.max()
  val out = m.copyOf(); var cut = 0
  for (i in 0 until n) {
    if (lab[i] >= 0 && areas[lab[i]] * frac < big) { out[i] = 0; cut++ }   // 够不到最大块的 1/frac
  }
  return out to cut
}

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val side = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val index = File(dir, "index.tsv")
  if (!index.isFile) { println("!! 缺 ${index.path}"); exitProcess(2) }
  val rows = index.readText().replace("\r\n", "\n").split('\n').drop(1).filter { it.isNotBlank() }
    .map { it.split('\t') }
  val t = ArrayList<Triple<String, DoubleArray, IntArray>>()   // pkg, [oldBefore,oldAfter,newBefore,newAfter], [inkBefore,inkAfter]
  val kept = HashMap<String, IntArray>()                        // 割完之后那张墨，`--feet` 要用
  for (c in rows) {
    if (c.size < 18) continue
    val modelName = if (c[17] != "-" && c[17] != "err" && c[17].isNotEmpty()) c[17] else c[2]
    val mf = File(dir, modelName); if (!mf.isFile) continue
    val mp = loadSq(mf, side) ?: continue
    val plateLayer = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    val bgSib = File(dir, mf.nameWithoutExtension + "_modelbg.png")
    val under = if (bgSib.isFile) loadSq(bgSib, side) else null
    val before = runCatching { MonochromeMask.build(mp, side, side, plateLayer, under, trimSubject = false).mask }.getOrNull() ?: continue
    val after = runCatching { MonochromeMask.build(mp, side, side, plateLayer, under, trimSubject = true).mask }.getOrNull() ?: continue
    val ab = alphaOf(before)
    val aa = alphaOf(after)
    if (inkCells(ab) == inkCells(aa)) continue      // 这张没开火，量它没意义
    t += Triple(c[0], doubleArrayOf(IconRough.jag(ab, side, side), IconRough.jag(aa, side, side),
      IconRough.iso(ab, side, side), IconRough.iso(aa, side, side)), intArrayOf(inkCells(ab), inkCells(aa)))
    kept[c[0]] = aa
  }
  if (t.isEmpty()) { println("!! 一张都没开火 —— 台子没牙，检查 trimSubject 有没有传进去"); exitProcess(1) }
  println("开火 ${t.size} 张（`rec_model` 里【主形】真动了的那批）")
  println("%-34s %9s %9s %9s %9s   %s" .format("包名", "旧前", "旧后", "旧Δ", "新Δ", "墨格 前→后(%)  金标准"))
  for ((pkg, v, ink) in t.sortedBy { it.second[3] - it.second[2] }) {
    val tag = when {
      MUST_CATCH.any { pkg.startsWith(it) } -> "★该拦(旧尺漏了)"
      KEEP.any { pkg.startsWith(it) } -> "该放"
      OLD_REJECT.any { pkg.startsWith(it) } -> "该拦(旧尺已拦)"
      else -> ""
    }
    println("%-34s %9.2f %9.2f %+9.2f %+9.2f   %6d→%-6d %4.1f%%  %s".format(
      pkg, v[0], v[1], v[1] - v[0], v[3] - v[2], ink[0], ink[1], (ink[1] - ink[0]) * 100.0 / ink[0], tag))
  }
  if (args.contains("--sweep")) {
    println("\n== 候选阈值摊开（新Δ 超过即整张退回）==")
    println("%8s  %6s %6s %6s   %s" .format("阈值", "该放被拦", "该拦被放", "无名被拦", "旧尺同列的对照"))
    for (lim in listOf(-400.0, -200.0, -100.0, -50.0, 0.0, 50.0, 100.0, 200.0, 400.0, 1e9)) {
      var keepFail = 0; var catchPass = 0; var anon = 0
      var oldKeepFail = 0; var oldCatchPass = 0
      for ((pkg, v, _) in t) {
        val dNew = v[3] - v[2]
        val dOld = v[1] - v[0]
        val rejNew = dNew > lim
        when {
          KEEP.any { pkg.startsWith(it) } -> { if (rejNew) keepFail++; if (dOld > 1.0) oldKeepFail++ }
          MUST_CATCH.any { pkg.startsWith(it) } -> { if (!rejNew) catchPass++; if (dOld <= 1.0) oldCatchPass++ }
          else -> if (rejNew) anon++
        }
      }
      println("%8s  %6d %6d %6d   旧尺：该放被拦 %d / 该拦被放 %d".format(
        if (lim > 1e8) "∞(全放)" else lim.toString(), keepFail, catchPass, anon, oldKeepFail, oldCatchPass))
    }
  }
  if (args.contains("--feet")) {
    println("\n== `--feet` 金标准一测：删掉一小块**孤立**实心件（够不到最大块两成），这把尺许不许抬 ==")
    println("%-34s %8s %8s %8s   %s".format("包名", "旧Δ", "新Δ", "删掉格数", "判定"))
    var oldOk = 0; var newOk = 0; var fired = 0
    for ((pkg, v, _) in t.sortedBy { it.second[3] - it.second[2] }) {
      val m = kept[pkg] ?: continue
      val (less, cut) = feet(m, side, 5)
      if (cut == 0) continue
      fired++
      val jOld = IconRough.jag(less, side, side) - v[1]
      val iOld = IconRough.iso(less, side, side) - v[3]
      if (jOld <= 1.0) oldOk++
      if (iOld <= 1.0) newOk++
      println("%-34s %+8.2f %+8.2f %8d   %s".format(pkg, jOld, iOld, cut,
        (if (jOld <= 1.0) "旧尺放行 " else "旧尺顶回 ") + (if (iOld <= 1.0) "新尺放行" else "新尺顶回")))
    }
    println("有孤立件可删的 $fired 张：旧尺放行 $oldOk 张、新尺放行 $newOk 张" +
      "  ← 新尺要赢，就得在这里赢得多、且不在上面那张表里赢得假")
  }
  println("\n参考：旧尺现在钉的是 `旧Δ > 1.0 退回`（TRIM_JAG_MAX）。")
}
