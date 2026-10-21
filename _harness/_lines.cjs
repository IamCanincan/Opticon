// 「这条线画在空档里还是人群里」—— 对每一条闸做同一个检查。
// 用法：node _lines.cjs bench/dist.tsv
const fs = require('fs');
const lines = fs.readFileSync(process.argv[2] || 'bench/dist.tsv', 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const idx = fs.readFileSync('corpus_runtime/index.tsv', 'utf8').trim().split(/\r?\n/).slice(1).map(x => x.split('\t'));
const mono = new Map(idx.map(c => [c[0], c[8]]));
const pkgs = [...mono.keys()];
const rows = lines.slice(1).map(x => {
  const c = x.split('\t'); const o = {};
  head.forEach((k, i) => o[k] = c[i]);
  const p = pkgs.filter(p => o.icon.startsWith(p)).sort((a, b) => b.length - a.length)[0];
  o.pass = p && mono.get(p) === '有' ? 1 : 0;
  return o;
}).filter(r => +r.span >= 0 || +r.airCanvas >= 0);

const onModel = rows.filter(r => !r.pass);
console.log(`语料 ${rows.length} 行，其中**进模型** ${onModel.length} 行（其余是透传，永远不上屏）\n`);

// 每条闸：读哪一列、阈值多少、比较方向
const gates = [
  ['TONAL_SPAN_MIN', 'span', 35, '≥ 才走满幅那一路'],
  ['TONAL_AIR_MIN(画布)', 'airCanvas', 25, '≥ 才承认"盘在另一层"'],
  ['TONAL_AIR_MIN(内容框)', 'airBox', 25, '≥ 就不许翻极性'],
  ['PLATE_FLAT_MIN', 'flat', 40, '≥ 才许把判成底的格归零'],
  ['极性关系 iL>gL', 'iLgL', 0, '> 才翻成"亮=墨"'],
  ['底占两成门槛', 'gShare', 20, '≥ 才谈得上"这张有一块底"'],
];

function analyse(col, data) {
  const v = data.map(r => +r[col]).filter(x => !isNaN(x) && x >= -0.9);
  v.sort((a, b) => a - b);
  return v;
}
function crowd(name, col, th, note, data) {
  let d2 = data;
  if (col === 'iLgL') { d2 = data.map(r => ({ ...r, iLgL: +r.iL - +r.gL })).filter(r => +r.gL >= 0 && +r.iL >= 0); }
  if (col === 'gShare') { d2 = data.map(r => ({ ...r, gShare: 100 * +r.gN / Math.max(1, +r.gN + +r.iN) })).filter(r => +r.gN + +r.iN > 0); }
  const v = analyse(col, d2);
  if (!v.length) { console.log(`${name}: 没有可用读数`); return; }
  const below = v.filter(x => x < th), above = v.filter(x => x >= th);
  const nearUp = v.filter(x => x >= th)[0];
  const nearDn = [...v].filter(x => x < th).pop();
  // 线上/线下各 10% 量程内的"人群"
  const lo = th - Math.max(3, th * 0.25), hi = th + Math.max(3, th * 0.25);
  const crowdN = v.filter(x => x > lo && x < hi && x !== th).length;
  console.log(`${name.padEnd(24)} 阈值=${String(th).padStart(5)}  ${note}`);
  console.log(`   线下 ${String(below.length).padStart(3)} 张 / 线上 ${String(above.length).padStart(3)} 张` +
    `   最近的两侧邻居：${nearDn === undefined ? '—' : nearDn.toFixed(1)} | ${nearUp === undefined ? '—' : nearUp.toFixed(1)}` +
    `   **阈值 ±25% 区间里挤了 ${crowdN} 张**`);
}
for (const [n, c, t, note] of gates) crowd(n, c, t, note, onModel);
console.log('\n（同一张表跑全部 219 行，看透传那批会不会改变结论：）');
for (const [n, c, t, note] of gates) crowd(n, c, t, note, rows);
