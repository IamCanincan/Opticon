package harness

import com.iamcanincan.opticon.circle.IconCropWindow
import java.io.File
import javax.imageio.ImageIO

/**
 * 把 `rec_model` 再走一遍**装机那条链**，产出 `rec_model_r`：每张 = 模型真吃的那一张（reframe 之后）。
 *
 * 为什么现在能做、以前不该做：`measure()`（量窗口 + 卡 `GEO_ZOOM_CAP`）与 `reframe()` 都在
 * `IconCropWindow` 里，而那个文件**判据台编得动** —— 2026-10-17 把上限和链搬进来之前，
 * 这里只能再抄一份 1.82，那才是今天骂了一整天的"镜像"。
 *
 * ⚠ 只对**非自适应**成立（`rec_model` 62 张全是）：那一路装机把整张画布当可见区，
 *   所以 `off=0、side=edge`。自适应那张要换算可见区↔图层，别拿这个台子建。
 *
 * 跑法：`harness.ReframeCorpusKtKt <源目录> <目标目录> [--edge=384]`
 * 退出码非 0 = 一张都没搬出来（空目录不算成功）。
 */
fun main(args: Array<String>) {
  val srcDir = File(args.firstOrNull { !it.startsWith("--") } ?: "")
  val dstArg = args.getOrNull(args.indexOfFirst { !it.startsWith("--") } + 1)
  val dstDir = File(dstArg ?: "")
  require(srcDir.isDirectory) { "源目录不存在：$srcDir" }
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  dstDir.mkdirs()

  val files = srcDir.listFiles()!!.filter {
    it.extension == "png" && !it.name.contains("preview") && !it.name.endsWith("_modelbg.png")
  }.sortedBy { it.name }
  require(files.isNotEmpty()) { "源目录里没有 png" }

  var copied = 0; var reframed = 0; var err = 0; var underMoved = 0
  val manifest = StringBuilder("file\t搬了?\tzoom\t档\n")
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
    val win = runCatching { IconCropWindow.measure(px, edge) }.getOrNull()
    val out = IntArray(edge * edge)
    val moved: String
    if (win == null) {
      System.arraycopy(px, 0, out, 0, px.size); moved = "no"
    } else {
      val r = runCatching { IconCropWindow.reframe(px, edge, edge, win, 0, 0, edge, edge) }.getOrNull()
      if (r == null) { err++; System.arraycopy(px, 0, out, 0, px.size); moved = "err" }
      else { System.arraycopy(r, 0, out, 0, r.size); moved = "yes"; reframed++ }
    }
    val bmp = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    bmp.setRGB(0, 0, edge, edge, out, 0, edge)
    ImageIO.write(bmp, "png", File(dstDir, f.name))
    // ⚠ `under`（那块盘的满幅那一张）**必须跟着搬**：装机 `MonochromeSynth` 对 `pxIn` 与 `underIn`
    //   套的是同一个窗口。只搬主输入的话，这 20 张就是"前景搬了、盘没搬"的错位构图 ——
    //   拿它建的新语料去比旧语料，量出来的是我自己造的假差。
    val underSrc = File(srcDir, f.nameWithoutExtension + "_modelbg.png")
    if (underSrc.isFile) {
      val uimg = runCatching { ImageIO.read(underSrc) }.getOrNull()
      if (uimg != null) {
        val uw = uimg.width; val uh = uimg.height
        val us0 = IntArray(uw * uh); uimg.getRGB(0, 0, uw, uh, us0, 0, uw)
        val upx = IntArray(edge * edge)
        for (y in 0 until edge) for (x in 0 until edge) {
          val sy = (y.toLong() * uh / edge).toInt().coerceAtMost(uh - 1)
          val sx = (x.toLong() * uw / edge).toInt().coerceAtMost(uw - 1)
          upx[y * edge + x] = us0[sy * uw + sx]
        }
        val uout = if (win == null) upx
          else runCatching { IconCropWindow.reframe(upx, edge, edge, win, 0, 0, edge, edge) }.getOrNull() ?: upx
        val ubmp = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        ubmp.setRGB(0, 0, edge, edge, uout, 0, edge)
        ImageIO.write(ubmp, "png", File(dstDir, underSrc.name)); underMoved++
      }
    }
    copied++
    manifest.append(f.name).append('\t').append(moved).append('\t')
      .append(if (win == null) "-" else "%.3f".format(win.zoom)).append('\t')
      .append(if (win == null) "-" else if (win.plate) "plate" else "inscribe").append('\n')
  }
  File(dstDir, "_manifest.tsv").writeText(manifest.toString())
  println("REFRAMECORPUS 读了 ${files.size} 张、写出 $copied 张到 ${dstDir.name}/，主输入搬过 $reframed 张、under 跟着搬 $underMoved 张、出错 $err")
  if (copied == 0) { println("!! 一张都没写出来 —— 这台子什么都没干"); kotlin.system.exitProcess(1) }
}
