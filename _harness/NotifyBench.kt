package harness

import android.graphics.Bitmap
import com.iamcanincan.opticon.notify.IconBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 判据台：通知小图标那一支（`IconBitmap.monochrome`）的**语料 + 判据**。
 *
 * ## 为什么要有它
 * 裁圆有几何两格表、单色有 `rec_model` + 15 道闸，通知这边以前只有真机日志里的
 * 「通知尺 源=WxH 出=WxH / 0.95」—— 那能说"走了哪条支、出了多大"，判不了"剪影对不对"，
 * 也挡不住行为回归。这个文件补的就是这两件：**一份真实语料**（`notif_model/`）和
 * **一组可判的约束 + 逐张基线**。
 *
 * ## 输入口径
 * 生产上 `TYPE_RESOURCE` 那条先把 drawable 画进 96×96（[IconBitmap.iconBounds] 等比取框、
 * 居中），再交给 [IconBitmap.monochrome]；`TYPE_BITMAP` 等载体交的是原尺寸。语料是资源 PNG，
 * 所以这里按**生产取框**铺到 96² 再跑 —— 与 `rasterize` 同一条几何。缩放用最近邻（桩类
 * `createScaledBitmap` 就是它），与真机的双线性差在亚像素抗锯齿上；本闸只与**自己上一轮**
 * 比（基线 md5）和**结构约束**比，不拿它冒充设备读数。
 *
 * ## 能判什么、判不了什么
 * 能判：输出必须是纯白+alpha（不能带色）、长边 ≤96、宽高比不跑偏、形状占比在
 *   `[MIN_SHAPE_RATIO, MAX_SHAPE_RATIO]`、墨框非空、以及**逐张基线不漂**。
 * 判不了：「这张剪影好不好看」——那需要人眼/参照，本闸只保证"它一直是主人验收过的那一张"。
 *
 * 跑法：
 *   sh run.sh harness.NotifyBenchKt notif_model [--update] [--shot]
 * `--update`：把本轮结果写成新基线（**只在没有结构违规时**才写）。
 * `--shot`：把 原图 | 浅色主题 | 深色主题 写到 `bench/notifpreview/`。
 */
private const val EDGE = 96
private const val BASELINE = "notif_baseline.tsv"

private data class NotifRow(
  val name: String,
  val status: String,
  val w: Int,
  val h: Int,
  val md5: String,
  val line: String,
  val bad: List<String>,
)

fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "notif_model")
  if (!dir.isDirectory) {
    println("!! 语料目录 $dir 不在 —— 这一闸没跑，别把空结果读成通过")
    kotlin.system.exitProcess(2)
  }
  val update = args.contains("--update")
  val shot = args.contains("--shot")
  val outDir = File("bench/notifpreview").apply { mkdirs() }
  val baseFile = File(BASELINE)
  val base = if (baseFile.isFile && !update)
    baseFile.readLines().drop(1).mapNotNull { l ->
      val c = l.split('\t')
      if (c.size >= 5) c[0] to "${c[1]}\t${c[2]}\t${c[3]}\t${c[4]}" else null
    }.toMap()
  else emptyMap()

  val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.sortedBy { it.name }
  if (files.isNullOrEmpty()) {
    println("!! $dir 里没有 PNG —— 语料是空的，这一闸没跑")
    kotlin.system.exitProcess(2)
  }

  val rows = ArrayList<NotifRow>()
  var nulls = 0
  var alphaN = 0
  var solidN = 0
  println("=== 通知小图标语料 $dir · n=${files.size} ===")
  for (f in files) {
    val img = ImageIO.read(f) ?: run { println("!! 读不出 ${f.name}"); kotlin.system.exitProcess(2) }
    val src = toRaster96(img)
    val t0 = System.nanoTime()
    val out = IconBitmap.monochrome(src)
    val ms = (System.nanoTime() - t0) / 1_000_000.0
    val sp = IntArray(EDGE * EDGE).also { src.getPixels(it, 0, EDGE, 0, 0, EDGE, EDGE) }
    val transparent = sp.count { ((it ushr 24) and 0xff) < 128 }
    val solidRatio = 1.0 - transparent.toDouble() / sp.size
    val branch = if (solidRatio >= 0.7) "二值化" else "alpha"
    if (branch == "alpha") alphaN++ else solidN++

    val bad = ArrayList<String>()
    if (out == null) {
      nulls++
      // 基线也要认这一档：从「放弃」变成「交出」同样是行为漂移，不能因为没掩码就跳过。
      // ⚠ `--update` 是**人认账**的那一轮：此时不拿旧基线比，否则"上一轮这张还交得出、
      //   这一轮放弃了"只许新建、永远钉不上。
      if (!update && base.isNotEmpty()) drift(f.name, "null\t0\t0\t", base[f.name])?.let { bad += it }
      val line = "--- ${f.name}  ${"%.1f".format(ms)}ms  src=${img.width}x${img.height}→96 实心=${"%.0f".format(solidRatio * 100)}% " +
        "支=$branch → 放弃（占比不在 2%..85%）${if (bad.isEmpty()) "" else "  ✗ " + bad.joinToString("；")}"
      rows += NotifRow(f.name, "null", 0, 0, "", line, bad)
      println("  $line")
      continue
    }
    val op = IntArray(EDGE * EDGE).also { out.getPixels(it, 0, EDGE, 0, 0, EDGE, EDGE) }
    var colored = 0
    var shape = 0
    var x0 = EDGE; var y0 = EDGE; var x1 = -1; var y1 = -1
    var md = 1469598103934665603L
    for (y in 0 until out.height) for (x in 0 until out.width) {
      val p = op[y * EDGE + x]
      val a = (p ushr 24) and 0xff
      md = (md xor (((a.toLong() shl 32) or (p.toLong() and 0xffffffL)))) * 0x100000001b3L
      if (a == 0) continue
      if ((p and 0xffffff) != 0xffffff) colored++
      if (a >= 128) {
        shape++
        if (x < x0) x0 = x; if (x > x1) x1 = x
        if (y < y0) y0 = y; if (y > y1) y1 = y
      }
    }
    val ratio = shape.toDouble() / (out.width * out.height)
    val white = java.lang.Long.toHexString(md).take(12)
    val bw = if (x1 >= 0) x1 - x0 + 1 else 0
    val bh = if (y1 >= 0) y1 - y0 + 1 else 0

    if (colored > 0) bad += "带色 $colored 格"
    if (out.width > EDGE || out.height > EDGE) bad += "超限 ${out.width}x${out.height}"
    if (ratio < 0.02 || ratio > 0.85) bad += "占比 ${"%.2f".format(ratio)}"
    if (shape == 0) bad += "空框"
    if (!update && base.isNotEmpty()) {
      drift(f.name, "mask\t${out.width}\t${out.height}\t$white", base[f.name])?.let { bad += it }
    }

    val line = "--- ${f.name}  ${"%.1f".format(ms)}ms  src=${img.width}x${img.height}→96 实心=${"%.0f".format(solidRatio * 100)}% " +
      "支=$branch 出=${out.width}x${out.height} 占比=${"%.2f".format(ratio)} " +
      "框=${bw}x${bh}@($x0,$y0) md5=$white${if (bad.isEmpty()) "" else "  ✗ " + bad.joinToString("；")}"
    rows += NotifRow(f.name, "mask", out.width, out.height, white, line, bad)
    println("  $line")

    if (shot) {
      val view = BufferedImage(EDGE * 3 + 16, EDGE, BufferedImage.TYPE_INT_ARGB)
      view.setRGB(0, 0, EDGE, EDGE, sp, 0, EDGE)
      view.setRGB(EDGE + 8, 0, EDGE, EDGE, render(op, 0xEDEDED, 0x1F1F1F), 0, EDGE)
      view.setRGB(EDGE * 2 + 16, 0, EDGE, EDGE, render(op, 0x2B2B2B, 0xE8E8E8), 0, EDGE)
      ImageIO.write(view, "png", File(outDir, f.name))
    }
  }

  val failed = rows.count { it.bad.isNotEmpty() }
  println(
    "== 合计 n=${rows.size}（alpha 支 $alphaN / 二值化支 $solidN）放弃 $nulls " +
      "违规 $failed 基线 ${if (base.isEmpty()) "未建" else "${base.size} 张"}"
  )
  if (failed > 0) {
    println("!! $failed 张违反约束/基线 —— 这一闸不通过")
    kotlin.system.exitProcess(1)
  }
  if (update) {
    baseFile.writeText(
      "name\tstatus\tw\th\tmd5\n" + rows.joinToString("\n") { "${it.name}\t${it.status}\t${it.w}\t${it.h}\t${it.md5}" } + "\n"
    )
    println("== 基线已写入 ${baseFile.name}（n=${rows.size}）")
  }
  if (base.isEmpty() && !update) {
    println("!! 还没有基线文件：本轮只验结构，没验「与上一轮逐张相同」。建基线跑 --update")
    kotlin.system.exitProcess(1)
  }
}

/**
 * PNG → 生产口径的 96×96 输入：按 [IconBitmap.iconBounds] 等比取框、居中铺满。
 * 缩放用最近邻（桩类 `createScaledBitmap` 就是最近邻），与真机的双线性差在亚像素抗锯齿上。
 */
/**
 * 基线比对：`null` = 基线里没有这张（新增文件 / 分母被动过），返回 null = 没漂。
 * ⚠ 「新增」也算不通过：本仓吃过「分母悄悄变了、旧基线照样绿」的亏，语料加一张就该
 *   `--update` 认一次账，不许静默扩分母。
 */
private fun drift(name: String, want: String, got: String?): String? = when {
  got == null -> "新增（基线里没有 $name，先 --update）"
  got == want -> null
  else -> "漂移（基线 $got）"
}

private fun toRaster96(img: BufferedImage): Bitmap {
  val w = img.width
  val h = img.height
  val b = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888)
  val src = IntArray(w * h)
  img.getRGB(0, 0, w, h, src, 0, w)
  val box = IconBitmap.iconBounds(w, h, EDGE)
  val dw = box[2] - box[0]
  val dh = box[3] - box[1]
  val dst = IntArray(EDGE * EDGE)
  for (y in 0 until dh) for (x in 0 until dw) {
    val sx = x * w / dw
    val sy = y * h / dh
    dst[(box[1] + y) * EDGE + box[0] + x] = src[sy * w + sx]
  }
  b.setPixels(dst, 0, EDGE, 0, 0, EDGE, EDGE)
  return b
}

/** 桌面按 alpha 上色：`plate×(1−a) + ink×a`（与 MaskBench 的预览同一个式子）。 */
private fun render(mask: IntArray, plate: Int, ink: Int): IntArray {
  val out = IntArray(mask.size)
  for (i in out.indices) {
    val a = (mask[i] ushr 24) and 0xff
    val r = (((plate shr 16 and 0xff) * (255 - a) + (ink shr 16 and 0xff) * a) / 255) shl 16
    val g = (((plate shr 8 and 0xff) * (255 - a) + (ink shr 8 and 0xff) * a) / 255) shl 8
    val bl = ((plate and 0xff) * (255 - a) + (ink and 0xff) * a) / 255
    out[i] = (0xff shl 24) or r or g or bl
  }
  return out
}
