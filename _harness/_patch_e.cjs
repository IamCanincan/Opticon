const fs = require('fs');
const p = 'var_e/MonochromeMask.kt';
let t = fs.readFileSync('../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt', 'utf8');
const acc = 'if (alt.share < PLATE_ALL_SHARE && !vac && !thin) {';
const accNew = 'if (alt.share < PLATE_ALL_SHARE && !vac && !thin && alt.solid - alt.zero >= GROUND_MIN_SPAN) {';
const anchor = '  private const val SEP_MIN_GAP = 30';
const decl = '  /** 一块参照至少要留出多宽的浓度窗口才接得住手：低于它，交付出来的斜坡只剩几十级，\n' +
  '   *  dep 上一点点噪声就被放大成「要么实心要么擦掉」。真机 wisdomgame 就是 `零=53 实=99`（跨 46）、\n' +
  '   *  交出去碎成 23 块。量在 235 张上：64 这一档只碰到 wisdomgame 两张，其余一张不动。 */\n' +
  '  private const val GROUND_MIN_SPAN = 64\n\n' + anchor;
if (!t.includes(acc) || !t.includes(anchor)) { console.log('!! 锚点没对上，别硬改'); process.exit(1); }
t = t.replace(acc, accNew).replace(anchor, decl);
fs.writeFileSync(p, t);
console.log('变体已生成：入场闸加了 GROUND_MIN_SPAN');
