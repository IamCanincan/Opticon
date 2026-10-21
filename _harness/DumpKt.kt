package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 把每张图标**交付的 alpha** 原样存成 `.alpha`（裸字节，edge×edge），给 `DiffKt` 当输入。
 * 为什么要落盘而不是在一份进程里比两版：`MonochromeMask` 一个 classpath 只有一份字节码，
 * 同进程比 = 假 A/B（本仓栽过两次）。所以两份字节码各跑一遍、各存一份，再离线对。
 *
 * 用法：`harness.DumpKtKt <输入目录> <输出目录> [--edge=384]`
 */
fun main(args: Array<String>) {
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val dir = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
  val files = dir.listFiles()!!.filter { it.extension == "png" && !it.name.contains("preview") }
    .sortedBy { it.name }
  var done = 0
  for (f in files) {
    val img = runCatching { ImageIO.read(f) }.getOrNull() ?: continue
    val sw = img.width; val sh = img.height
    val s0 = IntArray(sw * sh); img.getRGB(0, 0, sw, sh, s0, 0, sw)
    val px = IntArray(edge * edge)
    for (y in 0 until edge) for (x in 0 until edge) {
      val sy = (y.toLong() * sh / edge).toInt().coerceAtMost(sh - 1)
      val sx = (x.toLong() * sw / edge).toInt().coerceAtMost(sw - 1)
      px[y * edge + x] = s0[sy * sw + sx]
    }
    val m = runCatching { MonochromeMask.build(px, edge, edge).mask }.getOrNull() ?: continue
    val a = ByteArray(edge * edge)
    for (i in 0 until edge * edge) a[i] = (m[i] ushr 24).toByte()
    File(outDir, f.nameWithoutExtension + ".alpha").writeBytes(a)
    done++
  }
  println("存了 $done 张 → ${outDir.path}（edge=$edge，每张 ${edge * edge} 字节）")
}
