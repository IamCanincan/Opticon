const fs = require('fs');
const p = 'app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt';
let t = fs.readFileSync(p, 'utf8');
const decl = '  /** 一块参照至少要留出多宽的浓度窗口，才**接得住手**。\n' +
  '   *\n' +
  '   * 低于它，交付出来的斜坡只剩几十级：dep 上一点点噪声就被放大成「要么实心、要么擦掉」，\n' +
  '   * 屏幕上就是**碎成几十块**而不是一个图形。真机 wisdomgame（灰发蓝眼、低饱和插画）H2 接手后\n' +
  '   * `零=53 实=99`（跨 46）、`cc=23`、框内 0.08 —— 主人一句「很奇怪」。\n' +
  '   * 量在 235 张（122 真机 + 113 老语料）上：**64 这一档只碰到 wisdomgame 两张**，其余一张不动。\n' +
  '   * ⚠ 它量的是**交付出来的动态范围**，不是「这块参照长什么样」—— 前五把尺子（占比/厚度/围合/\n' +
  '   *  环散/轮廓归一）都是后者，都量死过。所以这一条不是第六个阈值挑数，是换了一类证据。\n' +
  '   * 拒了要**自己报**：见 `拦=` 里的第 32 位。\n' +
  '   */\n' +
  '  private const val GROUND_MIN_SPAN = 64\n\n' +
  '  private const val SEP_MIN_GAP = 30';
const h2 = '      val vac = vacuous(s, alt)\n      val thin = thinPlate(alt)\n      if (alt.share < PLATE_ALL_SHARE && !vac && !thin) {';
const h2n = '      val vac = vacuous(s, alt)\n      val thin = thinPlate(alt)\n      val narrow = alt.solid - alt.zero < GROUND_MIN_SPAN\n      if (alt.share < PLATE_ALL_SHARE && !vac && !thin && !narrow) {';
const h2v = '      if (thin) s.veto = s.veto or 8';
const h2vn = '      if (thin) s.veto = s.veto or 8\n      if (narrow) s.veto = s.veto or 32';
const h1 = '      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p)) return p';
const h1n = '      val h1narrow = p.solid - p.zero < GROUND_MIN_SPAN\n      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p) && !h1narrow) return p';
const h1v = '      if (vacuous(s, p)) s.veto = s.veto or 1';
const h1vn = '      if (vacuous(s, p)) s.veto = s.veto or 1\n      if (h1narrow) s.veto = s.veto or 32';
for (const [name, from] of [['声明', '  private const val SEP_MIN_GAP = 30'], ['H2 入场', h2], ['H2 报', h2v], ['H1 入场', h1], ['H1 报', h1v]])
  if (!t.includes(from)) { console.log('!! 锚点对不上：' + name + ' —— 别硬改'); process.exit(1); }
t = t.replace('  private const val SEP_MIN_GAP = 30', decl)
     .replace(h2, h2n).replace(h2v, h2vn).replace(h1, h1n).replace(h1v, h1vn);
fs.writeFileSync(p, t);
console.log('已落进仓里：GROUND_MIN_SPAN=64，H1/H2 两条入场路都过这一关，拒了记 `拦` 第 32 位');
