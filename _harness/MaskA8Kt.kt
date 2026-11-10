package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import kotlin.system.exitProcess

/**
 * **探针**：把每张图**交付出去的那份掩码**（alpha 通道）按 `<名字>.alpha` 写成裸字节，
 * 用来与桌面上 `cacheDir/opticon-mono/<身份>/<键>_<边长>.a8` 的尾部 `edge²` 字节**逐字节对表**。
 *
 * 为什么要它：屏上画的就是那一份 `.a8`，而读它**不需要解锁**。抓屏那条路在锁屏上只会得到
 * `mCurrentFocus=AlternateBouncerView` 的一张锁屏图（2026-11-06 撞过），那时唯一的实据就是这份文件。
 *
 * 口径：输入构造走 [cases]（与装机同一份），旗标 `--keep-strip` = 把【横幅退族】那一刀关掉，
 * 用来证明"设备上那份是新臂、不是旧臂"。
 *
 * 跑法：`java -cp out;stubs_out;<stdlib> harness.MaskA8KtKt <图标目录> <输出目录> [--edge=384] [--keep-strip]`
 */
fun main(args: Array<String>) {
  val plain = args.filter { !it.startsWith("--") }
  val dir = File(plain.getOrElse(0) { "rec_model" })
  val outDir = File(plain.getOrElse(1) { "_alpha" })
  val edge = (args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=") ?: "384").toInt()
  val drop = !args.contains("--keep-strip")
  // 2026-11-10 【亮侧内容】的 A/B 臂：不开 = 生产行为（默认），开了 = [MonochromeMask.build] 的 lightInk。
  val light = args.contains("--light-ink")
  if (!dir.isDirectory) { println("!! 目录不存在：${dir.path}"); exitProcess(2) }
  outDir.mkdirs()
  var n = 0
  for (c in cases(dir, edge, inset = false, fgAll = false)) {
    if (c.src.size != edge * edge) continue
    val r = MonochromeMask.build(c.src, edge, edge, c.plateLayer, c.under, dropStrip = drop,
      lightInk = light)
    val mask = r.mask ?: continue
    val b = ByteArray(edge * edge)
    for (i in b.indices) b[i] = ((mask[i] ushr 24) and 0xff).toByte()
    File(outDir, c.file.name.removeSuffix(".png") + ".alpha").writeBytes(b)
    n++
  }
  println("写了 $n 份 alpha（edge=$edge，退族=${if (drop) "开" else "关"}）→ ${outDir.path}")
}
