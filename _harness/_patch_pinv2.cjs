// 把仓里那份 MonochromeMask 改成「平盘反相 v2」候选，落到 cand/PlateInvert.kt。
// v1 用 24 分一档的色族桶 —— 被支付宝骗了（它的「支」根本不在背景层里，Δ>40 的格是 0.0%）。
// v2 改用"离盘中位亮度多远"。跑法：node _patch_pinv2.cjs && sh cand.sh cand/PlateInvert.kt pinv2 rec_model
const fs = require('fs');
const SRC = '../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt';
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
      // ── 「平盘 + 盘上一块离盘很远的标记」：盘交墨、标记镂空 ──
      // 主人 2026-10-12 点名 translate：「右半边全黑、只有"文"是镂空的会更好」。
      // 这一族与下面盘刀的动作**正好相反**，所以放在同一个入口、由类判据二选一
      // （plateInv != 0 时盘刀整个跳过），不会出现两把刀抢同一张。
      // ⚠ **分盘与标记不用色族桶，用"离盘中位亮度多远"。** v1 用 24 分一档的桶，把支付宝也算进来了：
      //   它的背景层按桶 96.4% 是一族，按亮度众数只有 51.4%，而**那一层里压根没有标记**
      //   （「支」在前景层）—— PlateSplitKt 量到它背景层 Δ>40 的格是 **0.0%**。
      //   桶把一道渐变数成了"一档"：眼睛没被骗，桶被骗了。
      // 三条判据（数都是 _harness/PlateSplitKt.kt 量的）：
      //   ① 盘 = 背景层亮度的**众数**，占实心格 ≥ PLATE_INV_L_MIN
      //   ② 标记 = 离盘 ≥ PLATE_INV_DELTA，**不分方向**（translate 的字比盘暗 119；白字蓝盘那种比盘亮）
      //   ③ 标记还得真有 ≥ PLATE_INV_MARK_MIN —— 专拦那 7 张"整层就是一块纯色空盘"的：
      //      对它们翻极性 = 交出一整块黑方，正是 2026-10-08 真机炸掉的形状（第八闸钉着）
      // ⚠ 只动"背景层露在外面"的格：合成图那一格离背景层自己太远 = 被前景层盖住了，不越权去改
      //   （translate 气泡压在盘上的那一截、气泡投在盘上的那道影，都是靠这一条保住的）。
      if (under != null && under.size >= total) {
        val hL = IntArray(256); var sol = 0
        for (i in 0 until total) {
          if (Color.alpha(under[i]) < A_SOLID) continue
          sol++; hL[invLuma(under[i])]++
        }
        val plateL = (0..255).maxByOrNull { hL[it] } ?: -1
        var markN = 0
        if (plateL >= 0) for (i in 0 until total) {
          if (Color.alpha(under[i]) >= A_SOLID &&
              Math.abs(invLuma(under[i]) - plateL) >= PLATE_INV_DELTA) markN++
        }
        if (sol > 0 && plateL >= 0 && hL[plateL] * 100 >= sol * PLATE_INV_L_MIN &&
            markN * 1000 >= sol * PLATE_INV_MARK_MIN) {
          var inked = 0; var holed = 0
          for (i in 0 until total) {
            if (Color.alpha(under[i]) < A_SOLID) continue
            val lu = invLuma(under[i])
            if (Math.abs(invLuma(pixels[i]) - lu) >= PLATE_INV_COVER) continue   // 被前景盖住
            if (Math.abs(lu - plateL) >= PLATE_INV_DELTA) { out[i] = 0; holed++ }
            else { out[i] = (255 shl 24) or 0x00FFFFFF; inked++ }
          }
          plateInv = inked; plateHole = holed
        }
      }
      if (plateInv == 0 && top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        plateCand = 0`,
  '② 反相那一刀 v2'
);

sub(
  '  private fun plateKey(p: Int): Int =',
  `  /** 反相那一刀用的亮度（[盘刀] 用色族桶，这一刀用离盘中位亮度多远 —— 见调用点那段）。 */
  private fun invLuma(p: Int): Int =
    (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)).toInt()

  private fun plateKey(p: Int): Int =`,
  '③ invLuma'
);

sub(
  '  private const val PLATE_ALT_MIN = 85',
  `  private const val PLATE_ALT_MIN = 85
  /** 反相那一族：背景层亮度的众数要占到这么多，才算"这一层自己就是一块平色盘"。 */
  private const val PLATE_INV_L_MIN = 85
  /** 离盘这么远就算"那是画在盘上的标记"，不是盘的一部分。**不分方向**。 */
  private const val PLATE_INV_DELTA = 40
  /** 标记至少要占背景层实心格的这么多（千分比），否则那是**一块纯色空盘**，翻了就是交黑方。 */
  private const val PLATE_INV_MARK_MIN = 10
  /** 合成图那一格离背景层自己这么远 = 被前景层盖住了，这一刀不越权。 */
  private const val PLATE_INV_COVER = 24`,
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
