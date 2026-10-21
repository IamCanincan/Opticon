// 满幅那一支的**换支账**按进程摊开（调=1 开火 / 调跨= 算了没过线 / 调未算= 没算）。
//
// 为什么单独一台：`调跨=`/`调未算=` 是 2026-10-17 凌晨才加的两列，而一份 `logcat -d` 缓冲
// 横跨**多次模块加载** —— 旧进程那批行压根没有这两列。整份缓冲一起数，读到的是
// "几版代码字段的并集"，不是任何一版的真值。所以**必须按 pid 分摊**。
//
// 三道自检（本仓吃过"永远不响的尺"的亏）：
//   ① `调=` 这条探针必须看得见开火行，且与同行里的 `纹理=` **逐行同时出现** ——
//      这一条是防我自己：`(?:^| )调=` 与 `(?:^| )调=1=` 两种写法只差一个等号，
//      后者恒 0，会把"65 张开火"报成"一张都没有"（本轮真错过一次）。
//   ② 每个 pid 的三类必须**恰好盖满**该 pid 的行数，多出"都没有"就说明有第四个出口没插桩。
//   ③ 分母为 0 直接非零退出，不许交空结论。
//
// 用法：node _tonalacct.cjs [日志] [--need-跨=1]
const fs = require('fs');
const LOG = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : '_devlog.txt';

const has = (l, k) => new RegExp('(?:^| )' + k + '=').test(l);
const g = (l, k) => ((l.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');

const rows = fs.readFileSync(LOG, 'utf8').split('\n').filter(l => /Mono: diag/.test(l) && l.includes('盘实='));
if (rows.length === 0) { console.log('!! 没有一条带判据的 diag 行（分母 0）'); process.exit(1); }

// ── 自检 ① ──
const fire = rows.filter(l => has(l, '调'));
const tex = rows.filter(l => has(l, '纹理'));
if (fire.length === 0) { console.log(`!! 探针看不见任何开火行（全缓冲 ${rows.length} 行）—— 尺子在撒谎`); process.exit(1); }
if (fire.length !== tex.length || fire.some(l => !has(l, '纹理')))
  console.log(`!! 自检① 红：调= 与 纹理= 不同源（${fire.length} vs ${tex.length}）—— 这一台的读数别用`);

const byPid = new Map();
for (const l of rows) {
  const pid = l.split(/\s+/)[2];
  const a = byPid.get(pid) || { rows: 0, fire: 0, span: 0, why: {}, none: 0, t: ['-', '-'] };
  a.rows++;
  if (has(l, '调')) a.fire++;
  else if (has(l, '调跨')) a.span++;
  else if (has(l, '调未算')) a.why[g(l, '调未算')] = (a.why[g(l, '调未算')] || 0) + 1;
  else a.none++;
  a.t[0] = a.t[0] === '-' ? l.slice(0, 15) : a.t[0];
  a.t[1] = l.slice(0, 15);
  byPid.set(pid, a);
}

console.log(`== 换支账按进程摊开（全缓冲 ${rows.length} 行带判据，开火 ${fire.length} 行）==`);
for (const [pid, a] of byPid) {
  const w = Object.entries(a.why).map(([k, v]) => `未算${k}=${v}`).join(' ');
  console.log(`  pid ${pid.padEnd(6)} ${a.t[0]} ~ ${a.t[1]}  行=${String(a.rows).padStart(4)}  ` +
    `开火=${String(a.fire).padStart(3)}  调跨=${String(a.span).padStart(3)}  ${w || '调未算=0'}  ` +
    `${a.none ? '⚠ 三类都没有=' + a.none : '✓ 三类盖满'}`);
}
// 只有**最新那个 pid**才是屏上现在跑的那一版
const last = [...byPid.entries()][byPid.size - 1];
console.log(`  → 屏上现在这一版 = pid ${last[0]}：开火 ${last[1].fire} / 调跨 ${last[1].span} / ` +
  `没算 ${Object.values(last[1].why).reduce((x, y) => x + y, 0)}（共 ${last[1].rows} 行）`);
