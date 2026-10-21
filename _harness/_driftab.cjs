// 同一份漂移算法，跑在**任意一组** bench_<edge>.txt 上 —— 用来回答
// "全扫那一栏 12 vs 基线 6 是这一刀造成的，还是基线本来就没在全扫下记过"。
const fs = require('fs');
const files = process.argv.slice(2);
const box = new Map();
for (const f of files) {
  let cur = null;
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (l.startsWith('--- ')) { cur = l.slice(4).trim().split(/\s/)[0]; continue; }
    const m = cur && l.match(/框内=([0-9.]+)/);
    if (m) { if (!box.has(cur)) box.set(cur, []); box.get(cur).push(parseFloat(m[1])); }
  }
}
const rows = [];
for (const [n, a] of box) if (a.length > 1) rows.push([Math.max(...a) - Math.min(...a), n, a.join('/')]);
rows.sort((x, y) => y[0] - x[0]);
const bad = rows.filter(r => r[0] > 0.15);
console.log(`跑了 ${files.length} 档 × ${box.size} 张 → 漂移>0.15 的张数 = ${bad.length}`);
bad.slice(0, 16).forEach(r => console.log(`  ${r[0].toFixed(3)}  ${r[2].padEnd(26)} ${r[1].slice(0, 46)}`));
