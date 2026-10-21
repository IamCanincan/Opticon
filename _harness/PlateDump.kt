package harness

import java.io.File
import java.lang.reflect.Modifier
import javax.imageio.ImageIO
import com.iamcanincan.opticon.circle.MonochromeMask

/**
 * 把模型**内部**的那几张图打出来：P = 第一遍判成"底"的像素、D = dep（离底多远）、
 * R = 这一像素背后那个格子选出的参照色。
 *
 * 为什么要反射进 Scratch 而不改仓里的源码：「白字红底只画出淡痕迹」有两种病因 ——
 * 白字被当成底（P 上有字）或者白字量出来离底很近（D 上字是黑的）。修法完全不同，
 * 而外层的 mask 把两件事洗成同一个观感。反射只读，不动模型一行。
 *
 * 用法：`PlateDumpKt 图1.png …`
 */
fun main(args: Array<String>) {
  val fld = MonochromeMask::class.java.getDeclaredField("pool")
  fld.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val pool = fld.get(MonochromeMask) as ThreadLocal<Any>
  for (f in args.filter { !it.startsWith("--") }) {
    val file = File(f)
    if (!file.exists()) { println("跳过（不存在）：$f"); continue }
    val img = ImageIO.read(file)
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val r = MonochromeMask.build(px, w, h)
    val s = pool.get()
    println("=== ${file.name} (${w}x$h) ===")
    println("  ${r.report}")
    probe(s, r.mask, px, w, h)
    if (args.contains("--map")) map("D dep（离底多远，@=255）", s, w, h, "dist") { v, _ -> v / 255.0 }
  }
}

/**
 * 按**源亮度**分桶，把每一桶的四件内部量摊开：判成底的比例、dep、参照 β、最后交出的墨。
 * 「白字只画出淡痕迹」的四种病因在这里是分开的：底=100% 是吞了；dep 小是量不到距离；
 * β 小是参照自己就不实；dep 大而墨小是标定/降层把它压掉了。
 */
private fun probe(s: Any, out: IntArray?, px: IntArray, w: Int, h: Int) {
  val labL = field(s, "labL") as ShortArray
  val dist = field(s, "dist") as ShortArray
  val plate = field(s, "plate") as ByteArray
  val beta = field(s, "beta") as ShortArray
  val body = field(s, "body") as ByteArray
  val off = field(s, "off") as ShortArray
  val nb = 13
  val n = LongArray(nb); val dep = LongArray(nb); val bt = LongArray(nb)
  val ink = LongArray(nb); val grd = LongArray(nb)
  val bod = LongArray(nb); val of = LongArray(nb)
  val nInk = LongArray(nb); val inkDep = LongArray(nb)
  for (i in px.indices) {
    if (((px[i] ushr 24) and 0xff) == 0) continue
    val b = ((labL[i].toInt() and 0xffff) / 64 / 8).coerceIn(0, nb - 1)
    n[b]++
    val d = dist[i].toInt() and 0xffff
    dep[b] += d
    bt[b] += beta[i].toInt() and 0xffff
    if (plate[i].toInt() == 0) { nInk[b]++; inkDep[b] += d }
    grd[b] += if (plate[i].toInt() != 0) 1 else 0
    bod[b] += if (body[i].toInt() != 0) 1 else 0
    of[b] += off[i].toInt() and 0xffff
    ink[b] += if (out == null) 0 else (out[i] ushr 24)
  }
  println("  按源亮度分桶（L* 每 8 一桶）。dep=**只算非底那部分**的平均 dep₁（底恒为 0，" +
    "整桶平均会被底拖成假数）")
  for (b in 0 until nb) {
    if (n[b] < 200) continue
    println("   L=%3d  n=%7d  底=%5.1f%%  墨像素=%6d dep=%3d β=%3d 壳=%5.1f%% 离=%3d 出墨=%3d"
      .format(b * 8 + 4, n[b], grd[b] * 100.0 / n[b], nInk[b],
        if (nInk[b] == 0L) 0 else inkDep[b] / nInk[b], bt[b] / n[b],
        bod[b] * 100.0 / n[b], of[b] / n[b], ink[b] / n[b]))
  }
  // 再按**源不透明度**分一遍桶：底板四角那圈抗锯齿是唯一"α 不满"的地方，
  // 屏幕上那几块本不该存在的角斑要是它们，病灶一定出现在这一列里。
  val na = 8
  val an = LongArray(na); val adep = LongArray(na); val aink = LongArray(na)
  val agrd = LongArray(na); val abt = LongArray(na)
  for (i in px.indices) {
    val a = (px[i] ushr 24) and 0xff
    if (a == 0) continue
    val b = (a - 1) / 32
    an[b]++
    adep[b] += dist[i].toInt() and 0xffff
    abt[b] += beta[i].toInt() and 0xffff
    agrd[b] += if (plate[i].toInt() != 0) 1 else 0
    aink[b] += if (out == null) 0 else (out[i] ushr 24)
  }
  println("  按源不透明度分桶")
  for (b in 0 until na) {
    if (an[b] < 200) continue
    println("   α=%3d..%3d  n=%7d  底=%5.1f%%  dep=%3d β=%3d 墨=%3d"
      .format(b * 32 + 1, b * 32 + 32, an[b], agrd[b] * 100.0 / an[b],
        adep[b] / an[b], abt[b] / an[b], aink[b] / an[b]))
  }
}

private fun field(o: Any, name: String): Any {
  val c = o::class.java
  for (f in c.declaredFields) if (f.name == name) { f.isAccessible = true; return f.get(o) }
  throw NoSuchFieldException(name)
}

private fun map(title: String, s: Any, w: Int, h: Int, name: String, of: (Int, Int) -> Double) {
  val raw = field(s, name)
  val ramp = " .:-=+*#%@"
  val n = 32
  println("  $title")
  for (j in 0 until n) {
    val sb = StringBuilder("   ")
    for (i in 0 until n) {
      var acc = 0.0; var c = 0; var sample = 0
      for (y in (j * h / n) until ((j + 1) * h / n).coerceAtMost(h))
        for (x in (i * w / n) until ((i + 1) * w / n).coerceAtMost(w) step 2) {
          val idx = y * w + x
          val v = when (raw) {
            is ShortArray -> raw[idx].toInt() and 0xffff
            is ByteArray -> raw[idx].toInt() and 0xff
            is IntArray -> raw[idx]
            else -> error("field $name: ${raw::class.java}")
          }
          if (c == 0) sample = v
          acc += of(v, idx); c++
        }
      val a = if (c == 0) 0.0 else acc / c
      sb.append(ramp[(a.coerceIn(0.0, 1.0) * 9.999).toInt()])
    }
    println(sb)
  }
}

/** 参照场是 8×8 的格子：把每格的参照色（Lab×64）与覆盖率打出来，看白字那几格拿到的是红还是白。 */
private fun refmap(title: String, s: Any, w: Int, h: Int) {
  val g = field(s, "grid") as Int
  val fL = field(s, "fL") as IntArray
  val fA = field(s, "fA") as IntArray
  val fB = field(s, "fB") as IntArray
  val fCov = field(s, "fCov") as IntArray
  println("  $title  grid=$g   每格: L a b (×64) | β")
  for (j in 0 until g) {
    val sb = StringBuilder("   ")
    for (i in 0 until g) {
      val c = j * g + i
      sb.append("%5d,%5d,%5d|%3d ".format(fL[c], fA[c], fB[c], fCov[c]))
    }
    println(sb)
  }
}
