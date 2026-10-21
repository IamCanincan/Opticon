package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **源尺寸漂移普查（只量，不改模型）** —— 2026-10-12。
 *
 * 起因：主人报「translate 修完"文"都空了」，而我离线那张是好的。逐位对日志对出来的真相是
 * **桌面把同一张图标按两种前景层尺寸各画了一遍**：`@432x432 intrinsic=288` 与 `@425x425 intrinsic=283`，
 * 两者 `edge` 都是 384，但掩码不同（设备 `分档 0,134 / 盘刀 59430` vs `0,155 / 59351`）。
 * 判据台那份语料恰好是 **425 那一种**（离线跑出来 0,155 / 59351，逐位等于设备 425 那一行）——
 * 也就是说：**我所有绝对值量的是屏幕上少见的那一种画法。**
 *
 * ⚠ 这条轴和 `regress.sh` 里那个 `EDGES=96,192,288,384` **不是同一件事**：那一条扫的是模型自己的
 *   画布尺寸，而设备永远是 384，变的是**喂进来的那张位图多大**。老闸完全没盖住这一轴。
 *
 * 这一台的做法：固定 `edge=384`，把语料那张先绕到 `--via` 那个尺寸再落回 384（就是设备那一步），
 * 报每张的**判据行里哪些字段跟着动**。重点看三个：走哪一支（`调=`）、`分档`、`盘刀`。
 *
 * 用法：`harness.SizeVarKt <目录> [目录…] [--via=N,N,…] [--show=子串]`
 */
private val VIAS = intArrayOf(320, 384, 425, 432, 480, 576)

fun main(args: Array<String>) {
  val dirs = args.filter { !it.startsWith("--") }.map { File(it) }.filter { it.isDirectory }
  var vias = VIAS
  args.filter { it.startsWith("--via=") }.forEach {
    vias = it.substringAfter("=").split(",").map { s -> s.trim().toInt() }.toIntArray()
  }
  val show = args.filter { it.startsWith("--show=") }.map { it.substringAfter("=") }
  val noPlate = args.contains("--noPlate")
  val edge = 384

  val rows = ArrayList<Triple<String, Int, String>>()   // 名字, 变了几个尺寸, 明细
  val perVia = LinkedHashMap<Int, IntArray>()            // via -> [判据行变了, 换了支, 盘刀变了]
  val sv = StringBuilder()                               // 第十一闸的机器行（与上面同源，不另算一遍）
  var n = 0
  for (d in dirs) {
    // 这一份 index 给出**逐张**的"盘在不在另一层"（与 `MaskBench`/`LedgerKt` 同一个读法，见 `Inputs.kt`）。
    val plateByLayer = indexFlags(d).second
    for (f in d.listFiles { x -> x.extension == "png" }!!.sortedBy { it.name }) {
      if (f.name.endsWith("_modelbg.png")) continue
      if (show.isNotEmpty() && show.none { f.name.contains(it) }) continue
      val un = File(d, f.nameWithoutExtension + "_modelbg.png")
      val hasBg = un.isFile
      val plateLayer = plateByLayer[f.name] == true && !noPlate
      // ⚠ **`--noPlate` 只用来对照口径**（把 `plateLayer` 按旧写法全当 false）：
      //   这根 pin 从"写死 false"换成"按 index 逐张"之后 `SV 256 fields 51→50`，
      //   而本仓的规矩是**变了几个数就要说出是哪一张**（无脸名的 Δ 不许引用，见下面 `--all` 那段）。
      //   默认关着 ⇒ 正常跑与改动前逐字同口径的只有那 10 张 true，其余一张不多不少。
      val base = report(load(f, edge), if (hasBg) load(un, edge) else null, plateLayer) ?: continue
      n++
      var diff = 0
      val detail = StringBuilder()
      for (v in vias) {
        if (v == edge) continue
        val s = load(f, v) ?: continue
        val r = report(s, if (hasBg) load(un, v) else null, plateLayer) ?: continue
        val d2 = fieldsDiffer(base, r)
        val acc = perVia.getOrPut(v) { IntArray(3) }
        if (d2.isNotEmpty()) { diff++; detail.append(" $v:").append(d2); acc[0]++ }
        if (base["支"] != r["支"]) acc[1]++
        if (base["盘刀"] != r["盘刀"]) acc[2]++
      }
      if (diff > 0) rows.add(Triple(f.name.substringBefore("_adaptive").substringBeforeLast("_"), diff, detail.toString()))
    }
  }
  println("== 源尺寸漂移普查：edge 固定 384，只换喂进来的那张多大 ==")
  println("⚠ **只有比 384 小的那一档量得到东西**：语料存的就是 384，先放大到 425/432/576 再缩回 384")
  println("   是**一条来回、等于没动**（设备那两种尺寸的真差别要在抓取端留原生尺寸才量得到，见 README）。")
  for (v in vias) { val a = perVia[v] ?: continue
    println("  喂成 $v：判据行变 ${a[0]}/$n，其中**换了支** ${a[1]}、盘刀格数变 ${a[2]}")
    // 给回归台第十一闸读的机器行（人看的那行在上面，两条同源，不会各说一套）
    println("SV $v n=$n fields=${a[0]} branch=${a[1]} platecut=${a[2]}")
    sv.append("SV $v n=$n fields=${a[0]} branch=${a[1]} platecut=${a[2]};")
  }
  // **`--pin=<文件>`：判定搬进这把尺的退出码**（2026-10-21，与 `_press.cjs --pin=` 同一条规矩）。
  // 为什么必须搬：这根 pin 比的是**整行字符串**，几乎任何数值改动都会先撞它 —— 于是语义变异台
  // 整台重跑时永远停在第 11 段，**第十一闸自己有没有牙从来没被单独量过**。现在它能单独跑了。
  val pinArg = args.firstOrNull { it.startsWith("--pin=") }
  if (pinArg != null) {
    val pf = File(pinArg.substringAfter("="))
    if (!pf.isFile) { println("!! 没有 ${pf.path} —— 没基线不算过（先手工钉一次）"); kotlin.system.exitProcess(2) }
    val old = pf.readText().trim()
    println("SVLINE $sv")
    if (old != sv.toString()) {
      println("!! 源尺寸账动了：")
      println("   基线 $old")
      println("   这轮 $sv")
      println("   （变好也要认账：看清是哪几张换了支，再重钉）")
      kotlin.system.exitProcess(1)
    }
    println("SV-PIN OK —— 与基线逐字相同")
  }
  println("合计：$n 张里 **${rows.size} 张**（${"%.0f".format(rows.size * 100.0 / n.coerceAtLeast(1))}%）的判据行随源尺寸变")
  // ⚠ 默认只印"换了支 / 底占变 / H0 变"的那些 —— 于是**纯浓度档差**的漂移在名单里根本不存在，
  //   而第十一闸的 `fields` 把它们计入。2026-10-13 就撞上：候选让 `SV 256 fields 47→48`，
  //   可名单里一张都没多 —— 那 +1 无脸无名，等于闸报了一个不许引用的数。`--all` 把全部印出来。
  val showAll = args.contains("--all")
  for ((name, k, det) in rows.sortedByDescending { it.second })
    if (showAll || det.contains("支=") || det.contains("底占") || det.contains("H0")) println("  $k  $name$det")
}

/** 只比**会被刀吃到的那几个字段** —— 连续量（`顶占比` 差 1）不算翻，那叫噪声。 */
private fun fieldsDiffer(a: Map<String, String>, b: Map<String, String>): String {
  val sb = StringBuilder()
  for (k in listOf("支", "分档", "盘刀", "底占", "H0", "反相")) {
    val x = a[k] ?: "-"; val y = b[k] ?: "-"
    if (x != y) sb.append("$k=$x→$y,")
  }
  return sb.toString().trimEnd(',')
}

private fun report(px: IntArray?, under: IntArray?, plateLayer: Boolean): Map<String, String>? {
  if (px == null) return null
  // 🔴 **`plateLayer` 以前在这里写死 `false`，而 `under` 是照传的** —— 装机上这两句是绑在一起的：
  //   只有"背景是 `ColorDrawable`/`GradientDrawable` 的自适应图标"才有另一层可擦，也才有 `under`。
  //   所以第十一闸过去量的是一扇**不可能出现在真机上的门**：`rec_model` 里 10/62 张那一位是 true，
  //   它们这一路从来没被这一闸量过（与第九闸同族，见 `AirInkKt.kt` 文件头）。
  //   现在这一位按语料 `index.tsv` **逐张**给（`indexFlags` 与 [MaskBench] 共用同一份读法）。
  // ⚠ 这一台**不吃 `cases()`**：它要的是"同一张图标先绕到 256/320 再落回 384"，
  //   `src` 与 `under` 都得按 `via` 各自缩放（`cases` 只按一个 edge 构造）。这是有意的一处例外，
  //   但 index 那两位不例外 —— 那必须同源。
  val out = runCatching { MonochromeMask.build(px, 384, 384, plateLayer, under) }.getOrNull() ?: return null
  val r = out.report
  fun fld(k: String) = Regex("(?:^| )$k=([^ ]*)").find(r)?.groupValues?.get(1) ?: "-"
  return mapOf(
    "支" to if (r.contains("调=1")) "满幅" else "参照",
    "分档" to fld("分档"), "盘刀" to fld("盘刀"), "底占" to fld("底占"), "H0" to fld("H0"),
    // 「平盘反相」那一刀自己**开没开火** —— 它必须不随桌面递进来的那张多大而变，否则就是
    // "同一个图标在桌面两个位置，一个黑一半一个不黑"，正是上一刀死的那个形状。
    // ⚠ 取**有没有开火**，不取格数：格数本来就是尺寸量（384 与 320 差几百格是噪声，不是翻）。
    "反相" to run { val v = fld("反相"); if (v == "-" || v.startsWith("0/")) "0" else "1" },
  )
}

private fun load(f: File, via: Int): IntArray? {
  val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
  val mid = if (via == img.width) img else BufferedImage(via, via, BufferedImage.TYPE_INT_ARGB).also {
    val g = it.createGraphics(); g.drawImage(img, 0, 0, via, via, null); g.dispose()
  }
  val out = BufferedImage(384, 384, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics(); g.drawImage(mid, 0, 0, 384, 384, null); g.dispose()
  return IntArray(384 * 384).also { out.getRGB(0, 0, 384, 384, it, 0, 384) }
}
