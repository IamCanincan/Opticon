package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File

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
  val plateSide = edge - 2 * inset
  val cls = HashMap<String, String>()
  File(dir, "index.tsv").let { ix ->
    if (ix.isFile) ix.readLines().drop(1).forEach { l ->
      val c = l.split("\t"); if (c.size > 5) cls[c[2]] = c[5]
    }
  }
  var n = 0; var nShrink = 0
  val rows = ArrayList<Pair<String, Float>>()
  for (f in dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") &&
    !it.name.endsWith("_modelbg.png") }.sortedBy { it.name }) {
    val a = File(adir, f.nameWithoutExtension + ".alpha")
    if (!a.isFile) continue
    val bytes = ByteArray(a.length().toInt()).also { b -> a.inputStream().use { it.read(b) } }
    val alpha = IntArray(edge * edge) { bytes[it].toInt() and 0xFF }
    n++
    val s = IconCropWindow.silhouetteScale(alpha, edge, edge, plateSide)
    if (s < 0.999f) {
      nShrink++
      rows += (f.nameWithoutExtension.replace(Regex("_[0-9a-f]{12}$"), "").take(44) to s)
    }
  }
  rows.sortBy { it.second }
  println("该缩的（缩到 x，盘边 ${plateSide}px）：")
  rows.forEach { println("  %-46s %.3f".format(it.first, it.second)) }
  println("\n$n 张交付掩码里 $nShrink 张出安全区需要缩，其余逐像素不动")
  println("⚠ 口径：这里的 alpha 来自 DumpKt（简化输入，不含 under），只用于验规则会不会开火，")
  println("  不等于设备真交付的那批 —— 那张账要等清缓存后的 `剪影归一` 日志。")
}
