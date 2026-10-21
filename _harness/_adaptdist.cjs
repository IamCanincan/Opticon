// 摊一次「自带单色层」这一类的 `字均净` 分布（2026-10-22）。
//
// 为什么要摊：主人问"能不能全覆盖，尤其飞鸟那种"。飞鸟被 veto 接管之后交出去的字标只有同类
// 三分之一的笔画粗（68 vs 中位 244）—— 但**"薄"本身不是病**：线稿类图标天然就薄。
// 所以动算法之前必须先知道：这一类里有多少张和飞鸟同档？它们是人眼也分不出的细，还是被我们啃断的？
// 这一台子**只摊分布、不拍阈值、不改代码**（拍常数在这一族已经量死过七次）。
//
// 用法：node _adaptdist.cjs <MaskBench 输出文件> [--take=pkgA,pkgB]
const fs = require('fs');
const file = process.argv[2];
if (!file || !fs.existsSync(file)) { console.log('用法: node _adaptdist.cjs <MaskBench 输出> [--take=…]'); process.exit(2); }
const take = ((process.argv.find((a) => a.startsWith('--take=')) || '').substring(7).split(',').filter(Boolean));
const text = fs.readFileSync(file, 'utf8');

// MaskBench 一台一张：`--- <文件名>` 行后面跟判据行（字段 `字均=` / `零=` / `框内=` / `H=`）。
const rows = [];
let cur = null;
for (const l of text.split(/\r?\n/)) {
  const h = /^--- (\S+)/.exec(l);
  if (h) { if (cur && cur.ink !== undefined) rows.push(cur); cur = { name: h[1] }; continue; }
  if (!cur) continue;
  let m;
  if ((m = /字均=(-?[\d.]+)/.exec(l))) cur.ink = +m[1];
  if ((m = /零=(-?[\d.]+)/.exec(l))) cur.zero = +m[1];
  if ((m = /框内=(-?[\d.]+)/.exec(l))) cur.inBox = +m[1];
  if ((m = /H=(-?\d+)/.exec(l))) cur.H = +m[1];
  if ((m = /淡=(-?[\d.]+)/.exec(l))) cur.faint = +m[1];
}
if (cur && cur.ink !== undefined) rows.push(cur);

if (rows.length < 20) { console.log(`!! 只解析出 ${rows.length} 张（<20）—— 分母是空的，这张表不能读`); process.exit(1); }
const ink = rows.map((r) => r.ink).sort((a, b) => a - b);
const q = (p) => ink[Math.min(ink.length - 1, Math.round(p * (ink.length - 1)))];
console.log(`这一类 ${rows.length} 张的 字均净 分布：` +
  `p10=${q(0.10)} p25=${q(0.25)} 中位=${q(0.50)} p75=${q(0.75)} p90=${q(0.90)} 最细=${ink[0]} 最粗=${ink[ink.length - 1]}`);
// **先报量程再报分位**（本仓那条）：直方图分箱也一起给，免得把"薄"读成"少"。
const bins = [0, 40, 80, 120, 160, 200, 240, 280, 1e9];
const hist = bins.slice(0, -1).map((lo, i) => {
  const hi = bins[i + 1];
  return `${lo}${hi > 500 ? '+' : '-' + hi}:${rows.filter((r) => r.ink >= lo && r.ink < hi).length}`;
});
console.log('  分箱 ' + hist.join('  '));
const thin = rows.filter((r) => r.ink <= 100).sort((a, b) => a.ink - b.ink);
console.log(`细到 ≤100 的有 ${thin.length} 张：` + thin.map((r) => r.name.split('_').slice(0, 2).join('_') + ' ' + r.ink).join(' / ') || '（无）');
// **最细的六张连号打出来**：拍阈值之前必须先问"这是一族还是一张"——
// 一张孤点上的"改进"就是把另外 66 张一起拖下水（本仓那条"一张上的干净窗口不是通则"）。
const low6 = rows.slice().sort((a, b) => a.ink - b.ink).slice(0, 6);
console.log('  最细六张：' + low6.map((r) => r.name.split('_').slice(0, 2).join('_') + '=' + r.ink).join('  '));
const sorted = rows.slice().sort((a, b) => a.ink - b.ink);
console.log(`  第二细与最细之间差 ${sorted[1].ink - sorted[0].ink} 个点（差越大越像孤点，越小越像一族）`);
for (const t of take) {
  const r = rows.find((x) => x.name.startsWith(t));
  if (!r) { console.log(`!! 名单里那张 ${t} 不在这份输出里 —— 别拿"没找到"当"不在这一类"`); continue; }
  const below = ink.filter((v) => v < r.ink).length;
  console.log(`  接管中的 ${t}：字均=${r.ink} → 这一类里比它细的 ${below} 张（百分位 ${(below / ink.length * 100).toFixed(0)}%）` +
    ` H=${r.H} 框内=${r.inBox} 淡=${r.faint} 零=${r.zero}`);
}
console.log(`ADIST n=${rows.length} p10=${q(0.10)} 中位=${q(0.50)} p90=${q(0.90)} 细≤100=${thin.length}`);
