package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import kotlin.math.roundToInt

/**
 * 用**产品里那份实现**验「剪影归一」会不会开火：读 `DumpKt` 落盘的交付 alpha，
 * 算 [IconCropWindow.silhouetteScale]，打印该缩的名单与分布。
 *
 * 为什么单独一个驱动：装机上这条规则开不开火，取决于桌面重不重画（缓存一挡就是 0 张），
 * 不能拿"日志里没有"当"规则没生效"。台子上先量一遍，省一次白点的清缓存。
 *
 * 用法：`harness.SilKtKt <语料目录> <.alpha 目录> [--edge=384]`
 */
fun main(args: Array<String>) {
  val dir = File(args[0]); val adir = File(args[1])
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val inset = (edge * 0.25f / 1.5f).toInt()
  val plateSide = (args.firstOrNull { it.startsWith("--plateside=") }?.substringAfter("=")?.toInt()) ?: (edge - 2 * inset)
  val cls = HashMap<String, String>()
  File(dir, "index.tsv").let { ix ->
    if (ix.isFile) ix.readLines().drop(1).forEach { l ->
      val c = l.split("\t"); if (c.size > 5) cls[c[2]] = c[5]
    }
  }
  var n = 0; var nShrink = 0
  val rows = ArrayList<Pair<String, Float>>()
  // 「缩完落在哪」的预测：外接框按中心缩放就是线性乘 s，所以 before/after 两个分布能一起摊出来，
  // 和 `_silspace.cjs` 量的**透传那组**（平台自己的取色图标）放在同一把尺下比 ——
  // 开不开这条规则，该由这个分布说话，不是由"39 张要缩"这个数字说话。
  val extBefore = ArrayList<Float>(); val extAfter = ArrayList<Float>()
  val nShrinkList = ArrayList<Pair<Float, Float>>(); val allList = ArrayList<Pair<Float, Float>>()
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.endsWith("_modelbg.png") }.sortedBy { it.name }) {
    val a = File(adir, f.nameWithoutExtension + ".alpha")
    if (!a.isFile) continue
    val bytes = ByteArray(a.length().toInt()).also { b -> a.inputStream().use { it.read(b) } }
    val alpha = IntArray(edge * edge) { bytes[it].toInt() and 0xFF }
    n++
    var l = edge; var t = edge; var r = -1; var btm = -1
    for (y in 0 until edge) for (x in 0 until edge) {
      if (alpha[y * edge + x] <= 8) continue
      if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y
    }
    val s = IconCropWindow.silhouetteScale(alpha, edge, edge, plateSide)
    if (r >= 0) {
      val e0 = maxOf(r - l + 1, btm - t + 1).toFloat() / edge
      extBefore += e0; extAfter += e0 * s
      if (s < 0.999f) nShrinkList += (e0 to s)
      allList += (e0 to s)
    }
    if (s < 0.999f) {
      nShrink++
      rows += (f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "").take(44) to s)
    }
  }
  val q = { v: List<Float>, p: Float -> val s = v.sorted(); if (s.isEmpty()) -1f else s[minOf(s.size - 1, (p * (s.size - 1)).roundToInt())] }
  println("同一把尺（剪影占可见区几成）：")
  println("  现在    p10 %.3f  中位 %.3f  p90 %.3f"
    .format(q(extBefore, 0.1f), q(extBefore, 0.5f), q(extBefore, 0.9f)))
  println("  归一后  p10 %.3f  中位 %.3f  p90 %.3f   （对岸=透传那组：p10 0.516 中位 0.633 p90 0.770）"
    .format(q(extAfter, 0.1f), q(extAfter, 0.5f), q(extAfter, 0.9f)))
  // **往大了扫**（现在的线是 66/72=0.9167）：要"缩得比现在少"只能把安全区往外挪；
  //   而且 zone ≥ 0.9167 时，今天**不缩**的那些仍然不缩（body ≤ 0.9167·plateSide/2 < zone·plateSide/2），
  //   所以每个 zone 的效果都能从 `s` 反解出 body 来精确算 —— 不用改生产就能定线。
  println("  把线往大了扫（zone·盘边/2 当允许的主体半径）：")
  for (z in listOf(0.9167f, 0.94f, 0.96f, 0.98f, 1.0f)) {
    val after = ArrayList<Float>()
    for (i in 0 until allList.size) {
      val (e0, s0) = allList[i]
      val body = 0.9167f * (plateSide / 2f) / s0
      after += e0 * minOf(1f, z * (plateSide / 2f) / body)
    }
    println("    zone %.4f  全部 %d 张：中位 %.3f  p90 %.3f"
      .format(z, after.size, q(after, 0.5f), q(after, 0.9f)))
  }
  rows.sortBy { it.second }
  println("该缩的（缩到 x，盘边 ${plateSide}px）：")
  rows.forEach { println("  %-46s %.3f".format(it.first, it.second)) }
  println("\n$n 张交付掩码里 $nShrink 张出安全区需要缩，其余逐像素不动")
  println("⚠ 口径：这里的 alpha 来自 DumpKt（简化输入，不含 under），只用于验规则会不会开火，")
  println("  不等于设备真交付的那批 —— 那张账要等清缓存后的 `剪影归一` 日志。")
}
