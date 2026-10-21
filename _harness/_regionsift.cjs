// 读 `YardKt --regions=` 那张表，回答 KNOWN-LIMITS 那条重开入口唯一要回答的一句话：
// **「主体内部那块该不该交成底」这六个数里，有没有一个分得开设计师的取舍？**
//
// 标签**不是我给的**：`设计师α` = 应用自带的那层单色，在同一块区域上的平均 alpha。
// 两类：`涂墨` α≥200、`留空` α≤40；中间那批（合法的层次）**不参与分界判定**，
// 但条数要报出来 —— 中间调占大头就说明这六把尺问的根本不是设计师在做的事。
//
// 跑法：`node _regionsift.cjs bench/regions.tsv`
const fs = require('fs');
const L = fs.readFileSync(process.argv[2] || 'bench/regions.tsv', 'utf8').trim().split(/\r?\n/);
const h = L[0].split('\t');
const R = L.slice(1).map(l => {
  const c = l.split('\t'); const o = {}; h.forEach((k, i) => o[k] = c[i]);
  if (o.reg === '噪点') { o.noise = +o['面积%']; return o; }
  ['面积%', '离边', '离空', '明度', '饱和', '围合', '设计师α', '我们α', '触边'].forEach(k => o[k] = parseFloat(o[k]));
  return o;
}).filter(o => o.reg !== '噪点');
const noise = L.slice(1).filter(l => l.split('\t')[1] === '噪点').length;
const ink = R.filter(r => r['设计师α'] >= 200), empty = R.filter(r => r['设计师α'] <= 40);
const mid = R.filter(r => r['设计师α'] > 40 && r['设计师α'] < 200);
console.log(`区域 ${R.length} 块（来自 ${new Set(R.map(r => r.pkg)).size} 对；每包平均 ${(R.length / new Set(R.map(r => r.pkg)).size).toFixed(1)} 块）` +
  `；被当噪点丢掉的碎块分布在 ${noise} 包里`);
console.log(`按设计师的取舍分：涂墨 ${ink.length}  留空 ${empty.length}  中间调 ${mid.length}` +
  ` —— 中间调占 ${(mid.length * 100 / R.length).toFixed(0)}%，这六把尺问的不是它`);

// Mann-Whitney AUC：>0.5 = 该值越大越可能是"涂墨"；<0.5 反之；越接近 0.5 越没用。
function auc(f, A, B) {
  let u = 0, ties = 0;
  for (const a of A) for (const b of B) { if (a[f] > b[f]) u++; else if (a[f] < b[f]) u += 0; else { ties++; u += 0.5 } }
  return u / (A.length * B.length);
}
const FEAT = ['面积%', '触边', '离边', '离空', '明度', '饱和', '围合'];
// **先报量程再报 AUC**：`围合` 上一版就是死在这上面 —— 645 块里 594 块 = 1.00，
// AUC 0.504 量的是"这把尺没有刻度"，不是"包不包住没有信息"。没量程的尺不许下结论。
console.log('\n### 每个数的量程（min / p25 / 中位 / p75 / max，以及"挤在同一个值上"的比例）');
for (const f of FEAT) {
  const v = R.map(r => r[f]).sort((a, b) => a - b);
  const top = {}; for (const x of v) top[x] = (top[x] || 0) + 1;
  const mode = Object.entries(top).sort((a, b) => b[1] - a[1])[0];
  console.log(`   ${f.padEnd(6)} ${String(v[0]).padStart(5)} / ${String(v[v.length >> 2]).padStart(5)} / ${String(v[v.length >> 1]).padStart(5)} / ${String(v[v.length * 3 >> 2]).padStart(5)} / ${String(v[v.length - 1]).padStart(5)}   众数 ${mode[0]} 占 ${(mode[1] * 100 / v.length).toFixed(0)}%`);
}
const line = f => `   ${f.padEnd(6)} AUC ${auc(f, ink, empty).toFixed(3)}`;
console.log('\n### 全量：每个数单独能不能分开「涂墨 / 留空」（0.5 = 完全分不开，0.9 才算能用）');
FEAT.forEach(f => console.log(line(f)));

// "主体内部"改用**有量程的**那把：离空 ≥ 12 格才碰不到任何空气/画布外。
const interior = R.filter(r => r['离空'] >= 12);
const iInk = interior.filter(r => r['设计师α'] >= 200), iEmp = interior.filter(r => r['设计师α'] <= 40);
console.log(`\n### 只看**主体内部**（离空 ≥ 12 格 = 碰不到任何空气、也不贴画布外）：${interior.length} 块 = 涂墨 ${iInk.length} / 留空 ${iEmp.length} / 中间调 ${interior.length - iInk.length - iEmp.length}`);
if (iInk.length > 5 && iEmp.length > 5) {
  FEAT.filter(f => !['触边', '围合', '离空'].includes(f)).forEach(f => {
    let u = 0; for (const a of iInk) for (const b of iEmp) if (a[f] > b[f]) u++; else if (a[f] === b[f]) u += 0.5;
    console.log(`   ${f.padEnd(6)} AUC ${(u / (iInk.length * iEmp.length)).toFixed(3)}`);
  });
} else console.log('   两类里有一类 ≤5 块 —— **这台仪器在这个子集上没有量程**，不报 AUC');

// 我们现在在做什么：同一批块上"我们α"与"设计师α"的差，按设计师的取舍分栏
const mean = (a, f) => a.reduce((s, r) => s + r[f], 0) / Math.max(a.length, 1);
console.log('\n### 我们现在交的是什么（同一块上 我们α 的均值）');
for (const [nm, g] of [['设计师涂墨的块', ink], ['设计师留空的块', empty], ['内部·涂墨', iInk], ['内部·留空', iEmp]])
  if (g.length) console.log(`   ${nm.padEnd(14)} n=${String(g.length).padStart(4)}  设计师α ${mean(g, '设计师α').toFixed(0).padStart(3)}  我们α ${mean(g, '我们α').toFixed(0).padStart(3)}  面积中位 ${g.map(r => r['面积%']).sort((a, b) => a - b)[g.length >> 1].toFixed(1)}%`);

// 最伤的几张：设计师涂墨、我们交空，且是内部大块 —— 这就是那堵墙要的东西
const miss = R.filter(r => r['设计师α'] >= 200 && r['我们α'] <= 40).sort((a, b) => b['面积%'] - a['面积%']);
console.log(`\n### 设计师涂墨、我们交空的块 = ${miss.length} 块（这堵墙的具体形状）`);
for (const m of miss.slice(0, 12))
  console.log(`   ${m['面积%'].toFixed(1).padStart(5)}%  离空${String(m['离空']).padStart(3)} 离边${m['离边']} 明度${m['明度']} 饱和${m['饱和']}  设${m['设计师α']} 我${m['我们α']}  ${m.pkg.slice(0, 34)}`);
