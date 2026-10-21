package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 重放真机带回来的那几张：从 logcat 文本里把 `Mono: dump#N` 的 base64 分片拼回 PNG，
 * 然后**用仓里那份 MonochromeMask** 重跑一遍，出判据数字 + 上屏预览。
 *
 * 跑法：`java -cp out;stubs_out;<stdlib> harness.ReplayKt <logcat文件> [输出目录]`
 *
 * 这条通道存在的理由：他报的那批应用发的是 adaptive vector XML / webp，判据台解不出来，
 * 以前每一轮"更细致"都是在对着代理图调参。现在拿到的是**模型在真机上吃进去的同一张位图**
 * —— 矢量已经光栅化、webp 已经解码，尺寸就是图层 bounds。
 */

/**
 * 键必须是 **(进程, dump 序号, 哪一份)**。
 *
 * `dump#N` 是**每进程各自从 1 数起**的，而一次抓取的日志里从来不只一个进程（桌面重启前后各一段、
 * SystemUI / 设置也都在模块作用域里）。旧版只按 `(序号, 哪一份)` 分组，于是同号的不同图标
 * 撞进同一个桶，每个片号取"最后到的那片"—— 真机四份日志实测：`live2` 3 个进程 102 组，旧键
 * 只认得出 36 组，其中 **7 组跨进程混拼**；`live` 4 个进程 122 组，旧键 44 组、混拼 10。
 * 上一轮"约 15% 的 PNG 被 logd 吞掉"就是这么来的 —— **通道一片没丢，丢的是我自己的键**。
 *
 * 新包每行带 `p=<十六进制 pid>`；旧日志没这个字段，退回 logcat 前缀里的 `( pid)`。
 */
private val safeName = Regex("""[^A-Za-z0-9._-]""")

fun main(args: Array<String>) {
  val log = File(args.firstOrNull() ?: error("用法: ReplayKt <log文件>"))
  val outDir = File(args.getOrNull(1) ?: "captured").apply { mkdirs() }
  val (caps, summary) = capture(log.readLines())
  val byKey = caps.groupBy { it.key }

  var written = 0
  for (c in caps) {
    val safe = c.note.substringBefore('|').trim().ifEmpty { "unknown" }.replace(safeName, "_")
    // 文件名里带进程：桌面重启前后各发一轮时，同一个图标会是两张（内容应当相同 —— 不同就是尺寸漂移）。
    val f = File(outDir, "icon${c.iconId}_${safe}-p${c.key.substringBefore(':')}-${c.what}.png")
    f.writeBytes(c.bytes)
    println("写出 ${f.name}  ${c.bytes.size} 字节   ${c.note.take(150)}")
    written++
  }
  println("分组：$summary / 写出 $written 张")
  if (written == 0) { println("日志里没有完整的 dump —— 让桌面重新加载一批图标再抓（或调高 MonoDump.MAX_ICONS）"); return }

  // 2) 重放 src：跑模型 + 出预览。**输入必须凑齐设备上那三个**（合成图 / 层底 / under），
  //    少一个，`盘刀/反相` 读的那一层就不存在，复放出来的就不是设备那一张。
  for (c in caps.filter { it.what == "src" }.sortedBy { it.key }) {
    val f = File(outDir, "icon${c.iconId}_${c.note.substringBefore('|').trim().ifEmpty { "unknown" }
      .replace(safeName, "_")}-p${c.key.substringBefore(':')}-src.png")
    val img = ImageIO.read(ByteArrayInputStream(c.bytes))
    val w = img.width
    val h = img.height
    val argb = IntArray(w * h)
    img.getRGB(0, 0, w, h, argb, 0, w)
    val note = c.note
    val layer = note.contains("层底=1")
    // 同一组（同进程、同序号、同图标 id）的 under —— 按 key 配对，不再靠文件名后缀猜。
    val underCap = byKey[c.key]!!.firstOrNull { it.what == "under" }
    var under: IntArray? = null
    if (underCap != null) {
      val u = ImageIO.read(ByteArrayInputStream(underCap.bytes))
      under = if (u.width == w && u.height == h) IntArray(w * h).also { u.getRGB(0, 0, w, h, it, 0, w) }
      else {
        println("   ⚠ ${c.key} 的 under 是 ${u.width}x${u.height} 而 src 是 ${w}x$h —— 尺寸不同，这一张按没有 under 复放")
        null
      }
    } else if (note.contains(" 层=1"))
      println("   ⚠ 设备报这一张**有**背景层，可 under 没带回来（被预算拦下 / 那一份没拼齐）—— 复放出来的不是设备上那一张")
    val t0 = System.nanoTime()
    val r = MonochromeMask.build(argb, w, h, layer, under)
    val pol = MonochromeMask.polarity(argb, w, h)
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("--- ${f.nameWithoutExtension.removeSuffix("-src")}  ${w}x$h  ${ms}ms  输入[层底=${if (layer) 1 else 0} under=${if (under == null) 0 else 1}]")
    println("    ${r.report}")
    println("    ${pol.report}")
    println("    ${levelsOf(r.mask)}")
    asciiOf(r.mask, w, h)
    val name = f.nameWithoutExtension.removeSuffix("-src")
    ImageIO.write(triptych(argb, r.mask, w, h), "png", File(outDir, "${name}_preview.png"))
    println("    预览 → ${outDir.path}/${name}_preview.png")
  }
}

private fun levelsOf(mask: IntArray?): String {
  if (mask == null) return "-"
  var zero = 0; var faint = 0; var mid = 0; var solid = 0
  for (p in mask) {
    val a = (p ushr 24) and 0xff
    when {
      a == 0 -> zero++
      a < 128 -> faint++
      a == 255 -> solid++
      else -> mid++
    }
  }
  return "浓度[0=$zero 淡1-127=$faint 中128-254=$mid 实255=$solid]"
}

private fun asciiOf(mask: IntArray?, w: Int, h: Int) {
  if (mask == null) return
  val cols = 48
  val rows = 24
  for (ry in 0 until rows) {
    val sb = StringBuilder()
    val y0 = ry * h / rows
    for (rx in 0 until cols) {
      val x0 = rx * w / cols
      var hi = 0
      val y1 = maxOf(y0 + 1, (ry + 1) * h / rows)
      val x1 = maxOf(x0 + 1, (rx + 1) * w / cols)
      for (y in y0 until y1) for (x in x0 until x1) {
        val a = (mask[y * w + x] ushr 24) and 0xff
        if (a > hi) hi = a
      }
      sb.append(when {
        hi >= 250 -> '#'; hi >= 128 -> '+'; hi > 0 -> '·'; else -> ' '
      })
    }
    println("      |$sb|")
  }
}

/** 源图（合成到中灰底上）| 浅色主题 | 深色主题 —— 与桌面同一套 `plate×(1-a)+ink×a`。 */
private fun triptych(src: IntArray, mask: IntArray?, w: Int, h: Int): java.awt.image.BufferedImage {
  val img = java.awt.image.BufferedImage(w * 3 + 16, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
  val srcPrev = IntArray(w * h) {
    val p = src[it]
    val a = (p ushr 24) and 0xff
    if (a == 255) p and 0xffffff else {
      val r = ((((p shr 16) and 0xff) * a + 0x80 * (255 - a)) / 255) shl 16
      val g = ((((p shr 8) and 0xff) * a + 0x80 * (255 - a)) / 255) shl 8
      val b = ((p and 0xff) * a + 0x80 * (255 - a)) / 255
      (0xff shl 24) or r or g or b
    }
  }
  img.setRGB(0, 0, w, h, srcPrev, 0, w)
  img.setRGB(w + 8, 0, w, h, over(mask, w * h, 0xEDEDED, 0x1F1F1F), 0, w)
  img.setRGB(w * 2 + 8, 0, w, h, over(mask, w * h, 0x2B2B2B, 0xE8E8E8), 0, w)
  return img
}

private fun over(mask: IntArray?, n: Int, plate: Int, ink: Int): IntArray {
  val out = IntArray(n)
  if (mask == null) { out.fill(0xff000000.toInt() or plate); return out }
  for (i in out.indices) {
    val a = (mask[i] ushr 24) and 0xff
    val r = ((((plate shr 16) and 0xff) * (255 - a) + ((ink shr 16) and 0xff) * a) / 255) shl 16
    val g = ((((plate shr 8) and 0xff) * (255 - a) + ((ink shr 8) and 0xff) * a) / 255) shl 8
    val b = (((plate and 0xff) * (255 - a) + (ink and 0xff) * a) / 255)
    out[i] = (0xff shl 24) or r or g or b
  }
  return out
}
