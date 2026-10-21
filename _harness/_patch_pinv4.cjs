// 把仓里那份 MonochromeMask 改成「平盘反相 v3」候选，落到 cand/PlateInvert.kt。
// v1 用色族桶 —— 被支付宝骗了（它的「支」根本不在背景层里）。
// v2 改用"离盘中位亮度多远" —— 结果在**对表台**上开火 10 张（计算器/联系人/时钟…），
//    那 10 张设计师的答案是浅盘深字，翻完 iouMed 0.94→0.845、**10 张全变差、零张变好**。
// v3 加第 ④ 条：**这块盘得被前景层盖住 ≥25%** —— 也就是"它是一张垫在别的卡底下的卡"，
//    而不是"整个图标的底"。translate 盖住 55%，那 10 张只有 1%–12%。
// 跑法：node _patch_pinv3.cjs && sh cand.sh cand/PlateInvert.kt pinv3 rec_model yard_model
const fs = require('fs');
const SRC = process.env.SRC || 'D:/tmp/base_mask.kt';   // 从**没有反相那一刀**的那份出发（HEAD~1），别从已带 v3 的工作树出发
let s = fs.readFileSync(SRC, 'utf8');
const crlf = s.includes('\r\n');
const cuts = [];
function sub(needle, repl, tag) {
  const n = s.split(needle).length - 1;
  cuts.push(tag + ' 命中 ' + n + ' 处');
  if (n !== 1) throw new Error('变异没落地：' + tag + ' 命中 ' + n + ' 处（要求恰好 1）');
  s = s.replace(needle, repl);
}

sub(
  '    var plateCut = 0; var plateCand = -1',
  '    var plateCut = 0; var plateCand = -1; var plateInv = 0; var plateHole = 0',
  '① 计数器'
);

sub(
  `      val top = fam.maxByOrNull { it.value }
      if (top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        plateCand = 0`,
  `      val top = fam.maxByOrNull { it.value }
      // ── 「一块被别的卡压着的平色卡 + 卡上画着标记」：卡交墨、标记镂空 ──
      // 主人 2026-10-12 点名 translate：「右半边全黑、只有"文"是镂空的会更好」。
      // 这一族与下面盘刀的动作**正好相反**，所以放在同一个入口、由类判据二选一
      // （plateInv != 0 时盘刀整个跳过），不会出现两把刀抢同一张。
      // ⚠ 四条判据，数都是 _harness/PlateSplitKt.kt 在**两套语料 124 张**上量的：
      //   ① 盘 = 背景层亮度的**众数**，占实心格 ≥ PLATE_INV_L_MIN
      //   ② 标记 = 离盘 ≥ PLATE_INV_DELTA，**不分方向**（translate 的字比盘暗 119；白字蓝盘那种比盘亮）
      //      —— ⚠ **不用色族桶**：v1 用桶把支付宝算进来了，它背景层按桶 96.4% 是一族、按亮度众数只有
      //      51.4%，而那一层里**压根没有标记**（「支」在前景层）。桶把一道渐变数成了"一档"。
      //   ③ 标记要真有 ≥ PLATE_INV_MARK_MIN —— 拦那 7 张"整层就是一块纯色空盘"的：翻了就是交黑方
      //   ④ **这块盘要 ≥ PLATE_INV_OCCL_MIN 被前景层盖住** —— 这一条是"翻对/翻错"的分界：
      //      translate 的盘被气泡盖住 **55%**（它是**一张垫在别的卡底下的卡**，翻它 = 后卡黑前卡亮，读得通）；
      //      而对表台上同样"平盘+平标记"的 **10 张系统图标**（计算器/联系人/时钟/短信/设置/光圈/日历/
      //      浏览器/录音机/音乐）只被盖住 **1%–12%** —— 那块盘**就是整个图标的底**，上面没有第二张卡，
      //      翻它等于把字翻成墨底，而设计师画的答案恰恰是浅盘深字。v2 没有这一条，
      //      对表台当场 iouMed **0.94 → 0.845**、10 张全变差、零张变差里没有一个变好。
      // ⚠ 只动"背景层露在外面"的格：合成图那一格离背景层自己太远 = 被前景盖住了，不越权去改
      //   （气泡压在盘上的那一截、气泡投在盘上的那道影，都是靠这一条保住的）。
      if (under != null && under.size >= total) {
        val hL = IntArray(256); var sol = 0
        for (i in 0 until total) {
          if (Color.alpha(under[i]) < A_SOLID) continue
          sol++; hL[invLuma(under[i])]++
        }
        val plateL = (0..255).maxByOrNull { hL[it] } ?: -1
        var markN = 0; var covN = 0
        if (plateL >= 0) for (i in 0 until total) {
          if (Color.alpha(under[i]) < A_SOLID) continue
          val lu = invLuma(under[i])
          if (Math.abs(lu - plateL) >= PLATE_INV_DELTA) markN++
          if (Math.abs(invLuma(pixels[i]) - lu) >= PLATE_INV_COVER) covN++
        }
        if (sol > 0 && plateL >= 0 && hL[plateL] * 100 >= sol * PLATE_INV_L_MIN &&
            markN * 1000 >= sol * PLATE_INV_MARK_MIN &&
            covN * 100 >= sol * PLATE_INV_OCCL_MIN) {
          // 压在盘上的**那张卡自己多亮**：只看"背景层是盘、而合成图离盘很远"的那些格取亮度众数
          // （translate 就是气泡的 132）。有了它，斜边那道混色带不用再靠一个魔数判"被盖住"。
          val hT = IntArray(256)
          for (i in 0 until total) {
            if (Color.alpha(under[i]) < A_SOLID) continue
            if (Math.abs(invLuma(under[i]) - plateL) >= PLATE_INV_DELTA) continue
            val lc = invLuma(pixels[i])
            if (Math.abs(lc - plateL) < PLATE_INV_DELTA) continue
            hT[lc]++
          }
          val topL = (0..255).maxByOrNull { hT[it] } ?: plateL
          var inked = 0; var holed = 0
          for (i in 0 until total) {
            if (Color.alpha(under[i]) < A_SOLID) continue
            val lu = invLuma(under[i])
            val lc = invLuma(pixels[i])
            val isMark = Math.abs(lu - plateL) >= PLATE_INV_DELTA
            if (Math.abs(lc - lu) >= PLATE_INV_COVER && !stillPlate(lc, plateL, topL)) continue
            if (isMark) { out[i] = 0; holed++ }
            else { out[i] = (255 shl 24) or 0x00FFFFFF; inked++ }
          }
          plateInv = inked; plateHole = holed
        }
      }
      if (plateInv == 0 && top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        plateCand = 0`,
  '② 反相那一刀 v3'
);

sub(
  '  private fun plateKey(p: Int): Int =',
  `  /** 反相那一刀用的亮度（盘刀用色族桶，这一刀用"离盘中位亮度多远"—— 见调用点那段）。 */
  private fun invLuma(p: Int): Int =
    (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt()

  /**
   * 这一格**离盘近，还是离压在盘上的那张卡近** —— 近盘就算还是盘。
   *
   * 为什么要有这一条：判"被前景盖住"原来只看一个数（合成图离背景层 ≥24）。可气泡与盘之间那道
   * **混色带**（抗锯齿，加上贴着边的影子核心）差值正好落在 24..106 —— 它是盘，却被判成"盖住了"，
   * 于是铺不上墨。主人 2026-10-12 装机后点名的「"文"左边还有一小块也是空着的」就是它：
   * 量出来 **2919 格 = 画布 2.0%，贴着斜边 0..11 格、平均 4 格**。
   * 换成关系判据之后，边界落在"盘色 ↔ 卡色"的中点上，不再靠那个 24。
   */
  private fun stillPlate(lc: Int, plateL: Int, topL: Int): Boolean =
    Math.abs(lc - plateL) <= Math.abs(lc - topL)

  private fun plateKey(p: Int): Int =`,
  '③ invLuma'
);

sub(
  '  private const val PLATE_ALT_MIN = 85',
  `  private const val PLATE_ALT_MIN = 85
  /** 反相那一族：背景层亮度的众数要占到这么多，才算"这一层自己就是一块平色卡"。 */
  private const val PLATE_INV_L_MIN = 85
  /** 离盘这么远就算"那是画在卡上的标记"，不是卡自己。**不分方向**。 */
  private const val PLATE_INV_DELTA = 40
  /** 标记至少要占背景层实心格的这么多（千分比），否则那是**一块纯色空盘**，翻了就是交黑方。 */
  private const val PLATE_INV_MARK_MIN = 10
  /** 合成图那一格离背景层自己这么远 = 被前景层盖住了，这一刀不越权。 */
  private const val PLATE_INV_COVER = 24
  /** 这块盘至少要被前景层盖住这么多，才算"一张垫在别的卡底下的卡"，而不是"整个图标的底"。 */
  private const val PLATE_INV_OCCL_MIN = 25`,
  '④ 常量'
);

sub(
  ' " 盘刀=$plateCut 盘候=$plateCand 盘层=${if (under == null) 0 else 1}"',
  ' " 盘刀=$plateCut 盘候=$plateCand 盘层=${if (under == null) 0 else 1} 反相=$plateInv/$plateHole"',
  '⑤ 报告行'
);

if (crlf) s = s.replace(/\n/g, '\r\n');
fs.writeFileSync('cand/PlateInvert.kt', s);
console.log(cuts.join('\n'));
console.log('写出 cand/PlateInvert.kt（' + s.length + ' 字节）');
