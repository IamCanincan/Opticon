package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import com.iamcanincan.opticon.circle.PlateFog
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **对表台** —— 拿真机上设计师**自己画的那一层单色**当参考答案，量我们交出去的墨与它差多少。
 *
 * ## 为什么这是台子里唯一一把"不是我自己定的"尺子
 * 其余每一项指标（淡、残盘、档数、ρ色、漂移、PASS/GAP）量的都是**模型自己的口径**：
 * 数字变好不等于屏幕上变好看 —— 今天为这件事付过三次代价（`淡` 与 `残盘` 各被自己的修法污染过一次，
 * 最后一刀是靠逐张看图才否掉的）。而真机上有 **61/122 个应用自带人工画好的单色层**：
 * 同一张彩色图标、同一个 384 口径、**设计师认为正确的那张遮罩**。这是免费的对岸。
 *
 * ## 口径（两边必须一模一样，不然比的是定义）
 * 都取「alpha ≥ 128 的格数 / 整幅 384²」。不用 `框内`：那个的分母是**内容框**，
 * 而人画的那一层是铺满画布的，两个分母差一个圆一个方。
 *
 * ## ⚠ 这把尺子的边界，写在这里免得被当成"61 张做错了"
 * 自带单色层的应用按硬规则**根本不进我们的模型**（已适配的一个像素都不碰）。所以这里量的是
 * **「如果由我们画，会离人画的差几点」** —— 它是**验收标准**，不是当前的用户可见缺陷清单。
 * 它真正的用处有两处：① 每一刀都必须报这一列，"我看着变好了"不再算证据；
 * ② 尾部那几张（人画 8%、我们 78%）指出的是**类别级**的方向错，不是某一张的偶发。
 *
 * 跑法：`java -cp … harness.YardKtKt <语料目录>`（目录里要有 `index.tsv` 与 `*_mono.png`，
 * 由 `CorpusCapture` 那一轮抓回来）。**配对数一掉就报红** —— 语料被动过、或被谁删了，
 * 不能悄悄变成"没有这一项"。
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_dev2")
  val show = (args.firstOrNull { it.startsWith("--worst=") }?.substringAfter("=")?.toInt()) ?: 8
  val dumpTo = args.firstOrNull { it.startsWith("--dump=") }?.substringAfter("=")
  // `--regions=<tsv>`：逐**区域**落盘（对岸给标签，见 `RegionKt.kt`）。
  val regTo = args.firstOrNull { it.startsWith("--regions=") }?.substringAfter("=")
  val regRows = ArrayList<String>()
  // `--input=composite`：**拿整张自适应图标喂模型**（背景层里带东西的那些，图形跨两层，
  //  只取前景层就把它们切掉了 —— translate 的"文"就是这么没的）。默认还是前景层。
  val useFg = !args.contains("--input=composite")
  /** `--input=model`：吃抓取通道存的「装机那一张」（`_model.png` + 乙类的 `_modelbg.png`）。 */
  val useModel = args.contains("--input=model")
  /**
   * `--shot=<目录> --only=pkg1,pkg2`：**把对表画出来**。这台子以前只有数字 —— 而"整张被涂成墨"
   * 和"位置差一点"在 `IoU 0.02` 这一个数上长得完全一样，只看数没法判该不该修、修哪。
   * ⚠ 必须在这台驱动里出图，不能另写一个：`ours` 这一边要过 [toVisible] 的几何换算，
   *   另起炉灶就会拿满幅几何去和可见区的设计师层并排，看上去像模型画歪了（那是仪器错位）。
   */
  val shotDir = args.firstOrNull { it.startsWith("--shot=") }?.substringAfter("=")?.let { File(it).apply { mkdirs() } }
  val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter("=")?.split(",")?.toSet() ?: emptySet()
  /**
   * `--why`：把模型自报的那一行判据数字并排打出来。对表以前只给「差几点」，不给**为什么差** ——
   * 而"整张被涂成墨"至少有两条完全不同的通路（极性翻错 / 承载面被当成主体 / 盘根本不在输入里），
   * 三条路要的是三种修法。这一行是唯一能分清它们的现场读数，且必须在这一台驱动里打：
   * 只有这里喂对了 `_model.png` + `_modelbg.png` + `plateIsLayer`，换台驱动就又不是装机那一张了。
   */
  val why = args.contains("--why")
  val index = File(dir, "index.tsv")
  if (!index.isFile) {
    println("YARD n=0 MISSING —— 缺 ${index.path}（先跑 CorpusCapture 抓一轮）")
    return
  }
  val rows = index.readText().trim().split('\n').drop(1).map { it.split('\t') }
    // 列：pkg label file edge sig cls fg bg mono kb fgAlpha bgAlpha monoFile monoAlpha
    .filter { it.size >= 14 && it[12] != "-" && it[12] != "写失败" }
  var n = 0
  var nFg = 0; var nComp = 0; var nLayer = 0; var nModel = 0
  var nJia = 0; var nYi = 0
  // `--noflag`：把今天新加的这一位按 false 传，**单独隔离出它的影响** —— 不然"新判据"与
  // "新输入口径"混在同一个差里，读出来的数不知道是谁干的。
  val noFlag = args.contains("--noflag")
  // `--yiPlate`：乙类把**背景层单独栅格化**得到的那张盘图喂进去（平台给的事实，不是投票）。
  val yiPlate = args.contains("--yiPlate")                                  // 喂进去的是前景层还是合成图
  val blanks = ArrayList<String>()                            // 输入整张透明的那些（不是模型的账）
  val devs = ArrayList<Pair<String, Double>>()      // 包名 → |我们 − 人画|（百分点）
  val ious = ArrayList<Pair<String, Double>>()      // 空间一致度：两边墨格的 IoU（1=完全重合）
  val das = ArrayList<Pair<String, Double>>()       // 逐格 |Δα| 均值（0..255，看的是"浓淡对不对"）
  val shares = HashMap<String, Pair<Double, Double>>()   // 包名 → (我们的墨%, 人画的墨%)
  // 逐张的原始行。汇总数（med/iouMed）会被中位数糊掉：**一张变好一张变坏时中位数可以不动**，
  // 而"这一刀到底改了哪些图"恰恰是要回答的问题，所以必须能落盘、能两次做差。
  val dump = ArrayList<String>()
  for (r in rows) {
    // ⚠ **喂模型的必须是运行时那一张。** 索引里有 `fgFile`（自适应图标的**前景层**，也就是
    //   `MonochromeSynth` 真正交给模型的东西）时一律用它，否则就又在合成图上打分 ——
    //   那个口径下 15 张缺陷账有 7 张 `框内` 差 1.7~6 倍（见 README「审计线吃的是合成图」）。
    // ⚠ **喂模型的必须是运行时那一张 —— 而"运行时那一张"分两种。**
    //   甲类（自适应 + 背景是 `ColorDrawable`）：`MonochromeSynth` 只取**前景层** → 用 `*_fg.png`。
    //   乙类（自适应 + 背景是图片/矢量）：走 `rasterizeLayers`，**两层合并**才是模型吃的那一张 →
    //   必须用合成图。以前这里只要 `fgFile` 存在就用它，于是**乙类 19 张全部被喂了半张图**：
    //   youtube 就是证据 —— 它的红色圆角矩形在**背景层**里，只喂前景层就只剩那个白三角，
    //   我们 1.9% / 人画 31.9%、IoU 0.000，看着像模型大错，其实是**打分喂错了图**。
    //   （2026-10-09 对表最差 12 张里 10 张是乙类，就是这么来的。）
    val isJia = r[5].contains("Adaptive") && r[7] == "ColorDrawable"
    // `--input=model`：吃抓取通道按 `MonochromeSynth` 那条分支存的**装机那一张**
    //   （甲类 = 前景层满幅、乙类 = 两层满幅叠加），并把乙类那张 `_modelbg.png` 当 `under` 一起交。
    //   上面那套 `*_fg.png` / 合成图的二选一是**近似**：`_fg.png` 是可见区几何（差 1.5 倍），
    //   而乙类喂合成图时 `盘刀` 没有 `under` 可用 —— 两个错叠在一起。
    val modelUnder = if (useModel && r.size > 18 && r[18] != "-" && r[18] != "null" && r[18] != "err")
      File(dir, r[18]).let { if (it.isFile) load(it, r[3].toInt()) else null } else null
    val srcName = if (useModel && r.size > 17 && (r[17] == "same" || File(dir, r[17]).isFile)) {
      nModel++; if (r[17] == "same") r[2] else r[17]
    } else if (!useFg && r[2].isNotEmpty() && File(dir, r[2]).isFile) r[2]
    else if (isJia && r.size > 15 && r[15] != "-" && r[15].isNotEmpty() && File(dir, r[15]).isFile) {
      nFg++; r[15]
    } else if (!isJia && File(dir, r[2]).isFile) { nComp++; r[2] }
    else if (r.size > 15 && r[15] != "-" && File(dir, r[15]).isFile) { nFg++; r[15] } else r[2]
    if (isJia) nJia++ else nYi++
    val src = File(dir, srcName); val mono = File(dir, r[12])
    if (!src.isFile || !mono.isFile) continue
    val edge = r[3].toInt()
    val inPx = load(src, edge)
    // ⚠ **输入本身是空的，就不是模型的账。** `com.android.chrome` 那一张 `*_fg.png` 只有 681 字节 ——
    //   解码出来整张透明（它的图形全在**背景层**里，而 `MonochromeSynth` 只把 `source.foreground`
    //   交给模型）。对表台原先把这一行记成"我们交了空掩码、IoU 0.000"，那是**拿抓取装置的洞
    //   给算法记分**。数一下就知道有几个：这一轮 91 张前景层里 1 张整张透明（`FgScan.java`）。
    val blankIn = (0 until edge * edge).none { ((inPx[it] ushr 24) and 0xff) > 0 }
    if (blankIn) { blanks += r[0]; continue }
    // **这一位必须跟着喂。** `MonochromeSynth` 在真机上就是这么传的：自适应 + 背景层是
    // `ColorDrawable` → 交给模型的只有前景层 → 这一张里没有承载面。63 对参照里这样的有 **25 张**，
    // 而对表台以前一律传 false —— 那等于**拿一个今天已经修过的错误口径给设计师打分**。
    val plateLayer = !noFlag && r[5].contains("Adaptive") && r[7] == "ColorDrawable" &&
      (useModel || (r.size > 15 && srcName == r[15]))
    if (plateLayer) nLayer++
    // 仓里这份 `build` 现在收 5 参（`plateIsLayer`、`under`）—— 乙类在真机上确实带一张 `under`，
    // 对表要按装机那条路打分就得一起交。⚠ 但**别只对着某一版候选编**：让一台驱动只在新接口上
    // 编得过，等于让那一版候选废掉整台回归（`PlateBench` 栽过一次，这是第二次记这条）。
    val res = MonochromeMask.build(inPx, edge, edge, plateLayer, modelUnder)
    val oursRaw = res.mask ?: continue
    // ⚠ **两边几何必须一致，而该换算的是我们这一边。** `GeoKt` 把底案量死了：
    //   `mono ↔ fg = 0.939`（**同一框架**，都是可见区）、`mono ↔ 合成图/model = 0.170/0.175`（满幅，另一框架）。
    //   所以 `_model.png` 那条路算出来的掩码在**满幅**坐标系里，而设计师那层在**可见区**里 ——
    //   直接对表 = 拿两张差 1.5 倍构图的图打分（第一版量出 `iouMed 0.909→0.259`，看着像模型大倒退，
    //   其实是仪器错位）。这里把**我们的**换算回可见区再比。
    //   （第一版我反过来把设计师那层放大 1.5 倍凑模型，`iouMed` 掉到 0.114 —— 方向错了，越对越差。）
    val ours = if (useModel) toVisible(oursRaw, edge) ?: continue else oursRaw
    val theirs = load(mono, edge)
    // 源必须在同一几何（可见区）里；`--regions` 与 `--cutPlate` 共用这一份换算。
    val srcVis = if (useModel) toVisible(inPx, edge) ?: continue else inPx
    // `--cutPlate=t`：在设计师参照上量**生产那条规则**（纯色盘 + 内部 + 近盘色 + 只压雾）。
    //   直接调 `PlateFog.cut`（生产同一份），不是台子里另写一条。
    val plateCutOn = args.firstOrNull { it.startsWith("--cutPlate=") }?.substringAfter("=")?.toIntOrNull()
    var oursUse = ours
    if (plateCutOn != null && r.size > 14 && r[14].startsWith("flat")) {
      val pc = r[14].removePrefix("flat").toIntOrNull(16) ?: 0
      if (pc != 0) {
        oursUse = ours.copyOf()
        val st = PlateFog.cut(oursUse, srcVis, edge, pc, plateCutOn)
        if (st[0] > 0) println("    〔盘雾〕${r[0]} t=$plateCutOn 区域=${st[0]} 格=${st[1]}")
      }
    }
    // `--regions=<tsv>`：把这张图切成颜色区域，逐块记「它长什么样 + 设计师把它涂成墨还是留成底」。
    //   挂在这里而不是另起一台：`ours` 已经过 `toVisible`，源也必须走同一个换算才在同一框架里。
    if (regTo != null) {
      // 盘色/背景层：甲类 = index 的 `bgColor`；乙类 = `_modelbg`（模型吃的那张 under）换算到同一几何。
      // 两者都是运行时拿得到的，不是设计师层。
      val plate = if (r.size > 14 && r[14].startsWith("flat"))
        r[14].removePrefix("flat").toIntOrNull(16) ?: -1 else -1
      val bgVis = if (modelUnder != null && useModel) toVisible(modelUnder, edge) else null
      regRows += regionRows(r[0], srcVis, oursUse, theirs, edge, bgVis, if (isJia) plate else -1)
    }
    if (shotDir != null && r[0] in only) shot(shotDir, r[0], inPx, oursUse, theirs, edge)
    devs += r[0] to Math.abs(inkShare(oursUse, edge) - inkShare(theirs, edge)) * 100.0
    // ⚠ 原来只有上面这一行 —— 它量的是**两边各涂了多少墨**，两张毫不相干的遮罩只要墨量相同
    //   就是满分。要拿"像不像人画的"当准，必须先有一把看**位置**的尺子，否则优化出来的是假像。
    var both = 0; var either = 0; var sum = 0L
    // 「掉色」这把尺（主人 2026-10-13 看图报的那句）：**设计师贴到顶的那些格里，我们平均交了多少**。
    //   为什么不能用模型自己的 `顶占比`：那是"我们自己有没有用满量程"，而**层次本来就是靠不满量程换的**
    //   （`顶平%` 那一项甚至把"贴顶太多"当坏事）。只有拿人画那层当对岸，才分得开
    //   "这块本来就该是浅灰（合法的中间调）"与"这块人画是实心黑、我们交成了灰（掉色）"。
    var tTop = 0L; var tTopOur = 0L; var tTopOurTop = 0L
    var refInk = 0L; var refInkSum = 0L
    var greyN = 0L; var greyBad = 0L; var theirMid = 0L
    var theirTopOurGrey = 0L; var theirEmptyOurGrey = 0L
    for (i in 0 until edge * edge) {
      val a = (oursUse[i] ushr 24) and 0xff
      val b = (theirs[i] ushr 24) and 0xff
      if (a >= 128 || b >= 128) either++
      if (a >= 128 && b >= 128) both++
      sum += Math.abs(a - b).toLong()
      if (b >= 240) { tTop++; tTopOur += a; if (a >= 240) tTopOurTop++ }
      // ⚠ 上面那一列**把两件事混在一起**：人画实心而我们交 0（位置错 / 反色）也会把平均值拉到 3。
      //   这一列只数"两边都确实上了墨"的那些格 → 它才是纯粹的**掉色**：位置对了，浓度够不够。
      if (b >= 240 && a >= 128) { refInk++; refInkSum += a }
      // 第三把：**主人说的「掉色」其实不是"浓度不够"**（上一列已经量死：两边都上墨的格 61 张里只有 1 张平均 <200），
      //   而是**该黑或该空的地方多了一层半灰**。所以按人画那一格是不是二值的（≤15 空 / ≥240 实）来分：
      //   `greyN` = 我们交了半灰(128..239)的格数；`greyBad` = 其中**人画那一格本来是黑或本来是空**的那些。
      //   ⚠ 人画自己也是中间调的那些**不算**——那是合法的层次，主人长期指令就是「颜色可以多点」，
      //   这一列不许把它一起砍掉（历史教训：抬顶那一刀把闲鱼/华为压平过）。
      if (a in 128..239) {
        greyN++
        if (b <= 15 || b >= 240) greyBad++
        // **把"半灰"拆成两个相反的方向**（2026-10-20）：`greyBad` 一个数把两件事混在一起 ——
        //   人画实心我们交灰 = **掉色**（该抬），人画留空我们交灰 = **铺雾**（该压）。
        //   两把修法方向相反，混在一个数里就会"改完一条、另一条悄悄变好"骗过验收。
        if (b >= 240) theirTopOurGrey++ else if (b <= 15) theirEmptyOurGrey++
      }
      // **这把尺自己的对照**：人画那一层有没有中间调？设计师要是 100% 二值，那 `greyBad` 就等于
      // 宣布"永远不许有灰"—— 那会直接撞回主人长期那条「颜色可以多点」。这一列就是它的量程。
      if (b in 16..239) theirMid++
    }
    val iou = if (either == 0) 1.0 else both.toDouble() / either
    val (us, them) = inkShare(oursUse, edge) * 100.0 to inkShare(theirs, edge) * 100.0
    val da = sum.toDouble() / (edge.toLong() * edge)
    ious += r[0] to iou
    shares[r[0]] = us to them
    das += r[0] to da
    if (why) println("WHY ${r[0]}\t%.3f\t%.1f\t%.1f\t%s".format(iou, us, them, res.report))
    // 末尾追加三列（**前面列位一个都不动**，`_shape.cjs` 按列号读表头）：
    //   refTopN = 设计师贴到顶(α≥240)的格数（分母，太小就别拿平均值下结论）
    //   refTopOur = 那些格里我们的平均 α　　refTopTop = 其中我们也贴到顶的百分比
    dump.add("${r[0]}\t%.6f\t%.4f\t%.4f\t%.4f\t%d\t%.1f\t%.1f\t%d\t%.1f\t%d\t%.2f\t%.2f\t%.2f\t%.2f".format(
      iou, da, us, them, tTop, if (tTop == 0L) -1.0 else tTopOur.toDouble() / tTop,
      if (tTop == 0L) -1.0 else tTopOurTop * 100.0 / tTop,
      refInk, if (refInk == 0L) -1.0 else refInkSum.toDouble() / refInk,
      greyN, greyBad * 100.0 / (edge.toLong() * edge),
      theirMid * 100.0 / (edge.toLong() * edge),
      // 又是**末尾追加两列**（列位一个都不动）：掉色 / 铺雾 各占整幅几成。
      theirTopOurGrey * 100.0 / (edge.toLong() * edge),
      theirEmptyOurGrey * 100.0 / (edge.toLong() * edge)))
    n++
  }
  if (n == 0) { println("YARD n=0 配对为 0 —— 目录里只有彩色图，没有 `*_mono.png`"); return }
  val sorted = devs.sortedByDescending { it.second }
  val mean = devs.map { it.second }.average()
  val med = devs.map { it.second }.sorted()[n / 2]
  println("对表（人画的墨 vs 我们交的墨）")
  println("  最差 $show 张（按墨量差）：")
  for ((pkg, d) in sorted.take(show)) println("    %-46s 差 %.1f".format(pkg, d))
  val byIou = ious.sortedBy { it.second }
  println("  位置最不像的 $show 张（IoU，1=完全重合；括号里=两边各自的墨占比%）：")
  for ((pkg, v) in byIou.take(show)) {
    // ⚠ 先把"参考答案是空图"这种假 0 分排掉：IoU=0 既可能是**完全互补（真反了）**，
    //   也可能只是那层 `*_mono.png` 压根没墨、或两边各画各的。不分开就会拿装置错当算法错。
    val (us, them) = shares[pkg] ?: (0.0 to 0.0)
    val note = when {
      them < 0.5 -> "  ← 参考答案是空图，这一行不算数"
      us < 0.5 -> "  ← 我们交了空掩码"
      us + them < 3.0 -> "  ← 两边都几乎没墨"
      else -> ""
    }
    println("    %-46s IoU %.3f  (%.1f%% vs 人画 %.1f%%)%s".format(pkg, v, us, them, note))
  }
  val iouMed = ious.map { it.second }.sorted()[n / 2]
  val daMed = das.map { it.second }.sorted()[n / 2]
  println("YARD n=$n blank=${blanks.size} med=%.1f mean=%.1f iouMed=%.3f dAlphaMed=%.1f  输入=model ${nModel} 前景层 ${nFg} 合成图 ${nComp}  按通道 甲=${nJia} 乙/丙=${nYi}".format(med, mean, iouMed, daMed))
  if (blanks.isNotEmpty())
    println("  ⚠ 剔掉 ${blanks.size} 对**输入整张透明**的（图形在背景层里，模型收到的是空气）—— " +
      "这一类不算模型的账，但它是屏幕上「整个看不见」的一条真通路，见 README「前景层是空的」：")
  for (b in blanks) println("     $b")
  if (dumpTo != null) {
    File(dumpTo).writeText(
      "pkg\tiou\tdAlpha\tourPct\ttheirPct\trefTopN\trefTopOur\trefTopTop\trefInkN\trefInkAvg\tgreyN\tgreyBadPct\ttheirMidPct\ttheirTopOurGreyPct\ttheirEmptyOurGreyPct\n" +
        dump.sorted().joinToString("\n") + "\n")
    println("逐张已落盘：$dumpTo（${dump.size} 行）")
  }
  if (regTo != null) {
    File(regTo).writeText(
      "pkg\treg\t面积%\t触边\t离边\t离空\t明度\t饱和\t围合\t设计师α\t我们α" +
        "\t源α\t质心\t细长\t环Δ\t盘同\t盘Δ\t族占\t长宽\n" +
        regRows.joinToString("\n") + "\n")
    println("逐区域已落盘：$regTo（${regRows.size} 行 = 区域 + 每包的噪点行）")
  }
}

/** 与 `MaskBench.load` 同一口径：平铺到 edge×edge 的 ARGB 像素。 */
private fun load(f: File, edge: Int): IntArray {
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = Color(0, 0, 0, 0)
  g.fillRect(0, 0, edge, edge)
  g.drawImage(ImageIO.read(f), 0, 0, edge, edge, null)
  g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

/**
 * 三格并排：**源图（铺白） | 设计师手画的单色层 | 我们交的**。后两格把 alpha 当灰度画
 * （0 = 白 = 不上墨，255 = 黑 = 实心墨），与 `IoU` / `Δα` 同一份数据。
 * 传进来的 `ours` 已经过 [toVisible]，所以图与数字是同一个坐标系。
 */
private fun shot(dir: File, pkg: String, src: IntArray, ours: IntArray, theirs: IntArray, edge: Int) {
  val gap = 10
  val img = BufferedImage(edge * 3 + gap * 4, edge + gap * 2, BufferedImage.TYPE_INT_RGB)
  for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, -1)
  for (y in 0 until edge) for (x in 0 until edge) {
    val i = y * edge + x
    val p = src[i]; val a = (p ushr 24) and 0xff
    val rr = ((p shr 16) and 0xff) * a / 255 + 255 * (255 - a) / 255
    val gg = ((p shr 8) and 0xff) * a / 255 + 255 * (255 - a) / 255
    val bb = (p and 0xff) * a / 255 + 255 * (255 - a) / 255
    img.setRGB(gap + x, gap + y, (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb)
    val t = 255 - ((theirs[i] ushr 24) and 0xff)
    img.setRGB(gap * 2 + edge + x, gap + y, (0xff shl 24) or (t shl 16) or (t shl 8) or t)
    val u = 255 - ((ours[i] ushr 24) and 0xff)
    img.setRGB(gap * 3 + edge * 2 + x, gap + y, (0xff shl 24) or (u shl 16) or (u shl 8) or u)
  }
  ImageIO.write(img, "png", File(dir, pkg + "_yard.png"))
}

/**
 * 把**可见区**那一张（子层按 1.5× 铺进去后取中间 2/3）换算回**满幅**几何 ——
 * 也就是模型真正吃的那个坐标系：把图放大 1.5 倍、居中，四周让出 `edge/4`。
 * 校验：可见区边长 = 子层的 2/3，所以放大 1.5 倍后正好铺满 `edge + 2·(edge/4)`。
 * ⚠ `internal` 不是手滑：`EdgeRegionKt` 要用**同一个**换算，另抄一份就是本仓骂过两次的镜像
 *   （换算写错一次，两把尺就各说各话）。
 */
internal fun toVisible(px: IntArray, edge: Int): IntArray? {
  val src = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  src.setRGB(0, 0, edge, edge, px, 0, edge)
  val q = edge / 4
  val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  g.color = Color(0, 0, 0, 0); g.fillRect(0, 0, edge, edge)
  g.drawImage(src, -q, -q, edge + 2 * q, edge + 2 * q, null)
  g.dispose()
  val out = IntArray(edge * edge)
  img.getRGB(0, 0, edge, edge, out, 0, edge)
  return out
}

/** 墨的占比 = alpha ≥128 的格数 / 整幅。两边共用这一个式子。 */
private fun inkShare(px: IntArray, edge: Int): Double {
  var ink = 0
  for (p in px) if ((p ushr 24) and 0xff >= 128) ink++
  return ink.toDouble() / (edge.toLong() * edge)
}
