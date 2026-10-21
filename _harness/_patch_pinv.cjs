// 把仓里那份 MonochromeMask 改成「平盘反相」候选，落到 cand/PlateInvert.kt。
// 为什么写成脚本而不是手改：候选要能**逐字重做**，也要能一眼看出动了哪几处（手改的候选三天后就是一坨）。
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

// ① 计数器
sub(
  '    var plateCut = 0; var plateCand = -1',
  '    var plateCut = 0; var plateCand = -1; var plateInv = 0',
  '① 计数器'
);

// ② 反相那一刀，插在 val top = ... 之后、盘刀 那个 if 之前
sub(
  `      val top = fam.maxByOrNull { it.value }
      if (top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        plateCand = 0`,
  `      val top = fam.maxByOrNull { it.value }
      // ── 「平盘 + 一块平色标记」那一族：**盘才是那张卡，标记才是内容** → 盘交墨、标记镂空 ──
      // 主人 2026-10-12 点名 translate：「右半边全黑、只有"文"是镂空的会更好」。
      // 这一族与下面 [盘刀] 的动作正好相反，所以放在**同一个入口**、由同一个类判据二选一 ——
      // 不会出现两把刀抢同一张（plateInv != 0 时盘刀整个跳过）。
      // 三条判据（_harness/PlateInkKt.kt 量出来的适用面）：
      //   ① 盘 ≥ PLATE_ALT_MIN（沿用盘刀那一条，**不放宽** —— 放宽就会把满幅插画也卷进来）
      //   ② 盘外那一堆自己也得是**一块平色**（次大族 ≥ PLATE_INV_MARK_SHARE）→ 是"盘 + 字"，不是"盘 + 画"
      //   ③ 盘外那一堆还得**真有东西**（≥ PLATE_INV_MARK_MIN）→ 专拦那 7 张"背景层 100% 纯色、
      //      盘上没标记"的：对它们翻极性 = 交一整块黑方，正是 2026-10-08 真机炸掉的形状（第八闸钉着）
      if (top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        val rest = HashMap(fam); rest.remove(top.key)
        val restN = rest.values.sum()
        val mark = rest.maxByOrNull { it.value }
        if (mark != null && restN * 1000 >= solid * PLATE_INV_MARK_MIN &&
            mark.value * 100 >= restN * PLATE_INV_MARK_SHARE) {
          var inked = 0; var holed = 0
          for (i in 0 until total) {
            if (Color.alpha(under[i]) < A_SOLID) continue
            val ku = plateKey(under[i])
            // ⚠ **只动"背景层自己露在外面"的格**：被前景层盖住的那一块是别人的画，不越权去改
            //   （translate 那条斜边左边、气泡压在盘上的那一截就是这么保住的）。
            if (plateKey(pixels[i]) != ku) continue
            if (ku == top.key) { out[i] = (255 shl 24) or 0x00FFFFFF; inked++ }
            else if (ku == mark.key) { out[i] = 0; holed++ }
          }
          plateInv = inked
        }
      }
      if (plateInv == 0 && top != null && solid > 0 && top.value * 100 >= solid * PLATE_ALT_MIN) {
        plateCand = 0`,
  '② 反相那一刀'
);

// ③ 常量
sub(
  '  private const val PLATE_ALT_MIN = 85',
  `  private const val PLATE_ALT_MIN = 85
  /** 反相那一族：盘外那一堆里次大族要占到这么多，才算"盘上画的是一块标记"而不是"一张画"。 */
  private const val PLATE_INV_MARK_SHARE = 50
  /** 盘外那一堆至少要占背景层实心格的这么多（千分比），否则那是**一块纯色空盘**，翻了就是交黑方。 */
  private const val PLATE_INV_MARK_MIN = 10`,
  '③ 常量'
);

// ④ 报告行（放最末尾，别挪已有列的位置）
sub(
  ` " 盘刀=$plateCut 盘候=$plateCand 盘层=\${if (under == null) 0 else 1}"`,
  ` " 盘刀=$plateCut 盘候=$plateCand 盘层=\${if (under == null) 0 else 1} 反相=$plateInv"`,
  '④ 报告行'
);

if (crlf) s = s.replace(/\n/g, '\r\n');
fs.writeFileSync('cand/PlateInvert.kt', s);
console.log(cuts.join('\n'));
console.log('写出 cand/PlateInvert.kt（' + s.length + ' 字节）');
