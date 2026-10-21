package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import javax.imageio.ImageIO

/**
 * 「好几个物体、只交一档」到底有多少张 —— 决定"按物体分档"这一刀能服务谁。
 *
 * 动机：主人要「颜色可以多点」。第一反应是"块就按连通块算，别按颜色桶算"。
 * 但那一条**方向可能是反的**：满幅插画（王者荣耀 / wisdomgame）的墨本来就是一整块连通的，
 * 按连通块分它就只有**一块** —— 层次反而更少。而颜色桶恰好是现在唯一还在给这类图分层的东西。
 *
 * 所以先分清两种"塌成一档"：
 *  · **A 型**：交付的墨里有好几个**互不相连**的物体（各自都够大），却全发成同一档
 *    —— 这一类按物体分档**能救**，因为物体本来就分得开，不需要靠颜色猜。
 *  · **B 型**：整张就是一块连通的墨（盘 / 插画），内部只有颜色差别
 *    —— 这一类按连通块分**救不了**，只会把层次降到一块。
 *
 * 量法：跑模型拿到交出去的那张，把"有墨的格"（alpha ≥ 128）按 4 邻接数连通块，
 * 只数**占框 ≥ 0.3%** 的（碎屑不算物体）；再看这张交出去实际有几种浓度。
 * 全程只用公开接口，不改模型。
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "corpus_dev2")
  val minShare = args.firstOrNull { it.startsWith("--min=") }?.substringAfter('=')?.toDoubleOrNull() ?: 0.003
  val srcMin = args.firstOrNull { it.startsWith("--srcmin=") }?.substringAfter('=')?.toIntOrNull() ?: 4
  val neffMax = args.firstOrNull { it.startsWith("--neff=") }?.substringAfter('=')?.toDoubleOrNull() ?: 2.5
  val toneGap = args.firstOrNull { it.startsWith("--gap=") }?.substringAfter('=')?.toIntOrNull() ?: 8
  // 只数**模型真会处理的那些**：语料目录里带 `index.tsv`（CorpusCapture 抓的）时，
  // 按它的 `mono` 列排除自带单色层的 —— 那批按硬规则一个像素都不碰，数进来等于给自己刷分。
  val skipAdapted = File(dir, "index.tsv").isFile
  val adapted = if (skipAdapted) File(dir, "index.tsv").readLines().drop(1)
    .filter { it.split('\t').getOrNull(8) == "有" }.map { it.split('\t')[2] }.toSet() else emptySet()
  // ⚠ **这一台以前是 3 参调用 `build(src, w, h)`** —— 等于把装机那两位（`plateIsLayer`、`under`）全丢了：
  //   35 张"只给前景层"的按"整张都有盘"跑，20 张有盘的 `盘刀` 根本不开火。而 `盘刀` 不开火时
  //   translate 那块浅灰盘会被整片交成一块墨，**平色饼当场把它判成"塌"**（源 4 档→交付 1.1 档）。
  //   这条闸是台子里唯一不看外部答案的质量闸，输入口径错了它就把"修好了"报成"塌了"。
  val plateOf = HashMap<String, Boolean>(); val underOf = HashMap<String, String>()
  if (skipAdapted) File(dir, "index.tsv").readLines().drop(1).forEach { l ->
    val c = l.split('\t')
    if (c.size >= 8) plateOf[c[2]] = c[5].contains("Adaptive") && c[7] == "ColorDrawable"
    if (c.size >= 19 && c[18] != "-" && c[18] != "null" && c[18] != "err") underOf[c[2]] = c[18]
  }
  var a = 0; var b = 0; var one = 0
  var shown = 0
  val rows = ArrayList<Pair<String, Pair<Int, Int>>>()
  val tonesAll = ArrayList<Int>(); val srcAll = ArrayList<Int>(); val flatInfo = ArrayList<Triple<String, Int, Double>>(); val objs = ArrayList<Int>(); val flat = ArrayList<Pair<String, Int>>()
  for (f in dir.listFiles { x: File -> x.extension == "png" && !x.name.contains("preview") &&
      !x.name.contains("_mono") && !x.name.endsWith("_modelbg.png") }!!.sortedBy { it.name }) {
    if (adapted.contains(f.name)) continue
    val img = ImageIO.read(f)
    val w = img.width; val h = img.height
    val src = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
    val und = underOf[f.name]?.let { u ->
      runCatching {
        val uf = File(dir, u)
        val bi = if (uf.isFile) ImageIO.read(uf) else null
        if (bi == null || bi.width != w || bi.height != h) null
        else IntArray(w * h).also { bi.getRGB(0, 0, w, h, it, 0, w) }
      }.getOrNull()
    }
    val r = MonochromeMask.build(src, w, h, plateOf[f.name] == true, und)
    val out = r.mask ?: continue
    val n = w * h
    // 只数"交出去有墨"的格
    val ink = BooleanArray(n) { (out[it] ushr 24) and 0xff >= 128 }
    var inkTotal = 0
    for (i in 0 until n) if (ink[i]) inkTotal++
    if (inkTotal == 0) continue
    // 4 邻接连通块（扫描线栈，避免递归）
    val seen = BooleanArray(n)
    var big = 0
    val stack = IntArray(n)
    for (i in 0 until n) {
      if (!ink[i] || seen[i]) continue
      var sp = 0; var size = 0
      stack[sp++] = i; seen[i] = true
      while (sp > 0) {
        val c = stack[--sp]; size++
        val x = c % w; val y = c / w
        if (x > 0 && ink[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
        if (x + 1 < w && ink[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
        if (y > 0 && ink[c - w] && !seen[c - w]) { seen[c - w] = true; stack[sp++] = c - w }
        if (y + 1 < h && ink[c + w] && !seen[c + w]) { seen[c + w] = true; stack[sp++] = c + w }
      }
      if (size.toDouble() / n >= minShare) big++
    }
    // 实际交出去有几种浓度（差 ≥8 才算两种，避开抗锯齿抖）
    val tones = ArrayList<Int>()
    for (i in 0 until n) {
      val a0 = (out[i] ushr 24) and 0xff
      if (a0 < 128) continue
      if (tones.none { kotlin.math.abs(it - a0) < toneGap }) tones.add(a0)
    }
    // **有效档数 Neff**：把交出去的 alpha 分 16 桶，按像素重量算 exp(-Σp·lnp)。
    // 为什么不能用「出现过几种浓度」：一张平色饼照样有几万个抗锯齿与渐变的中间值，
    // 那种数法**永远数得出几十种** —— 拿旧版（两刀之前）做阳性对照时它报 0，牙都没有。
    // Neff 量的是「重量摊在几档上」：全一档 = 1，均匀摊 16 档 = 16。
    // **源里本来有几档**（不透明像素按亮度分 16 桶，数占 ≥3% 的桶）。
    // 这是唯一能把「双色 logo 正确交出一张实心剪影」和「插画被压平」分开的东西：
    // 前者源里就只有 2 档，塌是**对的**；后者源里有 6 档，塌下来才是把层次丢了。
    val srcBins = IntArray(16)
    var srcOpaque = 0
    // ⚠ **那块盘不算「源里本来有的层次」**。自适应图标的背景层就是盘，装机那条路会把它整片交回桌面，
    //   而模型里的 `盘刀` 正是为此存在。不排掉它，`盘刀` 修好的那张（translate）在这道闸里永远是
    //   "源 4 档 → 交付 1.6 档 = 塌" —— **把修好报成塌**。判据与模型同一句：背景层不透明格里
    //   最大的 /24 颜色族占 ≥85%，那族就是盘（`MonochromeMask` 的 `PLATE_ALT_MIN`）。
    fun famKey(p: Int) = ((((p shr 16) and 0xff) / 24) shl 10) or
      ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)
    val plateKey = und?.let { u ->
      val fam = HashMap<Int, Int>(); var sol = 0
      for (i in 0 until n) {
        if (((u[i] ushr 24) and 0xff) < 128) continue
        sol++; val k = famKey(u[i]); fam[k] = (fam[k] ?: 0) + 1
      }
      val top = fam.maxByOrNull { it.value }
      if (top != null && sol > 0 && top.value * 100 >= sol * 85) top.key else null
    }
    for (i in 0 until n) {
      val p = src[i]; if ((p ushr 24) and 0xff < 250) continue
      if (plateKey != null && famKey(p) == plateKey) continue
      val l = (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff))
      srcBins[(l.toInt() / 16).coerceAtMost(15)]++; srcOpaque++
    }
    val srcLevels = if (srcOpaque == 0) 0 else srcBins.count { it * 100 >= srcOpaque * 3 }
    val bins = IntArray(16)
    for (i in 0 until n) { val a0 = (out[i] ushr 24) and 0xff; if (a0 >= 128) bins[(a0 - 128) / 8]++ }
    val tot = bins.sum().coerceAtLeast(1)
    var ent = 0.0
    for (c in bins) if (c > 0) { val p = c.toDouble() / tot; ent -= p * kotlin.math.ln(p) }
    val neff = kotlin.math.exp(ent)
    val distinct = tones.size
    if (shown < 10) { shown++; println("  样本 %-46s 墨=%d 物体=%d 浓度=%d".format(f.nameWithoutExtension.take(46), inkTotal, big, distinct)) }
    when {
      big >= 3 && neff <= neffMax -> { a++; rows.add(f.nameWithoutExtension to (big to neff.toInt())) }
      neff <= neffMax -> b++
    }
    srcAll.add(srcLevels)
    if (srcLevels >= srcMin && neff <= neffMax) { one++; flatInfo.add(Triple(f.nameWithoutExtension, srcLevels, neff)) }
    tonesAll.add(distinct); objs.add(big); flat.add(Pair(f.nameWithoutExtension, distinct))
  }
  println("FLAT n=${tonesAll.size} flat=${one} 源档中位=${if (srcAll.isEmpty()) 0 else srcAll.sorted()[srcAll.size/2]} neff中位=${if (tonesAll.isEmpty()) 0 else tonesAll.sorted()[tonesAll.size/2]} adapted_skipped=${adapted.size}")
  for ((nm, sl, nf) in flatInfo.sortedBy { it.third }) println("  塌 %-50s 源 %d 档 · 交付 %.1f 档".format(nm.replace(Regex("_[0-9a-f]{12}"), ""), sl, nf))
  println("目录 ${dir.path}：A 型（≥3 个互不相连的物体、Neff ≤ $neffMax）= $a 张")
  println("              B 型（整张就一块墨、≤2 种浓度）= $b 张   ·合计塌掉的 ${one} 张")
  println("\nA 型名单（这一刀能救的）：")
  for ((name, v) in rows.sortedByDescending { it.second.first })
    println("  %-52s 物体 %d 个 · 浓度 %d 种".format(name.replace(Regex("_[0-9a-f]{12}"), ""), v.first, v.second))
}
