// 汇总 `MaskBench --feet` 的输出：这把"删掉 <1/5 最大块的孤立实心件"的补刀**在真流水线上动几张、动哪几张**。
// 跑法：`sh run.sh harness.MaskBenchKt --edge=384 --feet rec_model > bench/feet.txt` 然后 `node _feet.cjs`。
// 为什么单独一个脚本：这些数以前只出现在我的转述里。第十段那句"先量，别先找第三把尺"要的是**一张表**，
// 不是一句"量过了"。
const fs = require('fs');
const txt = fs.readFileSync(process.argv[2] || 'bench/feet.txt', 'utf8').split(/\r?\n/);
const R = [];
let cur = null;
const flush = () => { if (cur && cur.done) R.push(cur); cur = null; };
for (const l of txt) {
  if (l.startsWith('--- ')) { flush(); cur = { n: l.slice(4).split(/\s/)[0] }; continue; }
  if (!cur) continue;
  let m = l.match(/实255=(\d+)\]/); if (m) cur.solid = +m[1];
  m = l.match(/框内=([0-9.]+)/); if (m) cur.fill = +m[1];
  m = l.match(/〔补刀〕件=(\d+) 最大=(\d+) 实心=(\d+) 候选件=(\d+) 隙0=(\d+)件\/(\d+)格.*?隙8=(\d+)件\/(\d+)格.*?隙16=(\d+)件\/(\d+)格/);
  if (m) {
    cur.pieces = +m[1]; cur.big = +m[2]; cur.ink = +m[3]; cur.cand = +m[4];
    cur.d0 = +m[5]; cur.g0 = +m[6]; cur.d8 = +m[7]; cur.g8 = +m[8]; cur.d16 = +m[9]; cur.g16 = +m[10];
    cur.done = 1;
  }
}
flush();
console.log(`表上 ${R.length} 张（都有 〔补刀〕 行）`);
for (const [k, label] of [['g0', '隙0（只要不连着）'], ['g8', '隙8'], ['g16', '隙16']]) {
  const f = R.filter(r => r[k] > 0);
  console.log(`  ${label}: 动 ${f.length}/${R.length} 张，共 ${f.reduce((a, b) => a + b[k], 0)} 格，` +
    `件数中位 ${f.length ? f.map(r => r[k.replace('g', 'd')]).sort((a, b) => a - b)[f.length >> 1] : 0}`);
}
// ⚠ 分母用 `ink`（补刀自己那本账：α≥250 的实心格），**不用 `实255`**（α 正好 255）。
//   两者差 250..254 那一层，azure 那张就这么量出过 "167%"（删 294 格 / 实255 只有 176 格）。
const rel = R.filter(r => r.g16 > 0).map(r => ({ ...r, pct: r.g16 * 100 / r.ink })).sort((a, b) => b.pct - a.pct);
console.log('\n### 隙16 那一档，按「删掉的格占这张实心(α≥250) 的几成」排');
for (const r of rel)
  console.log(`   ${r.pct.toFixed(1).padStart(5)}%  (${r.g16}/${r.ink} 格, ${r.d16} 件)  最大件占实心 ${(r.big * 100 / r.ink).toFixed(0)}%  框内${r.fill}  ${r.n.slice(0, 46)}`);
const t = R.filter(r => r.g0 > 0).map(r => ({ ...r, pct: r.g0 * 100 / r.ink })).sort((a, b) => b.pct - a.pct);
console.log('\n### 隙0 那一档，占几成最高的 12 张');
for (const r of t.slice(0, 12))
  console.log(`   ${r.pct.toFixed(1).padStart(5)}%  (${r.g0} 格 / ${r.d0} 件)  ${r.n.slice(0, 46)}`);
console.log(`\n### 「四块脚」这个说法对不对：隙0 抓到 >10 件的张数 = ${R.filter(r => r.d0 > 10).length}` +
  `（一件只几格的碎屑会被一起算进"孤立件"）`);
const mal = R.find(r => r.n.includes('mugzone'));
if (mal) console.log(`malody：件=${mal.pieces} 候选=${mal.cand} 实心=${mal.ink} 实255=${mal.solid}  ` +
  `隙0=${mal.d0}件/${mal.g0}格  隙8=${mal.d8}/${mal.g8}  隙16=${mal.d16}/${mal.g16}`);
