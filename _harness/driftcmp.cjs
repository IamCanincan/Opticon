// 四档边长下「框内」的最大跨度：改前 vs 改后，点名谁新掉进 >0.15。
const fs = require('fs');
const EDGES = [96, 192, 384, 768];
function load(dir) {
  const m = new Map();
  for (const e of EDGES) {
    let cur = null;
    for (const l of fs.readFileSync(`${dir}/bench_${e}.txt`, 'utf8').split(/\r?\n/)) {
      const h = l.match(/^--- (\S+)/);
      if (h) { cur = h[1]; continue }
      if (cur) {
        const g = l.match(/框内=([\d.]+)/);
        if (g) { if (!m.has(cur)) m.set(cur, {}); m.get(cur)[e] = +g[1]; cur = null; }
      }
    }
  }
  const out = new Map();
  for (const [k, v] of m) {
    const a = EDGES.map(e => v[e]).filter(x => x !== undefined);
    if (a.length < 2) continue;
    out.set(k, Math.max(...a) - Math.min(...a));
  }
  return out;
}
const A = load(process.argv[2]), B = load(process.argv[3]);
const TH = 0.15;
const bad = s => [...s].filter(([, d]) => d > TH).map(([k, d]) => [k.split('_')[0], +d.toFixed(2)]);
const oldBad = new Map(bad(A)), newBad = new Map(bad(B));
console.log(`改前 >${TH}：${oldBad.size} 张   改后：${newBad.size} 张`);
console.log('改后新掉进来的：');
for (const [k, d] of newBad) if (!oldBad.has(k)) {
  const perEdge = EDGES.map(e => `${e}:${((A.get(k) || {})[e] ?? '?')}`).join(' ');
  console.log(`   跨度 ${String(d).padStart(5)}  ${k}   改前各档 ${perEdge}`);
}
console.log('改后不再漂的：');
for (const [k, d] of oldBad) if (!newBad.has(k)) console.log(`   ${(A.get(k) ?? 0).toFixed(2)}→${(B.get(k) ?? 0).toFixed(2)}  ${k}`);
console.log('两边都漂、但幅度变了 ≥0.05 的：');
for (const [k, d] of newBad) if (oldBad.has(k) && Math.abs(d - oldBad.get(k)) >= 0.05)
  console.log(`   ${oldBad.get(k)} → ${d}  ${k}`);
