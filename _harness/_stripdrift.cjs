// 横幅刀的密度依赖账：读两份 MaskBench 输出（不同 --edge），逐张比 退族= 与 框内=。
// 用法：node _stripdrift.cjs bench/reg/stripchk_384.txt bench/reg/stripchk_768.txt
const fs = require('fs');

function parse(p) {
  const out = new Map();
  let cur = null;
  for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = /^--- (\S+)/.exec(line);
    if (m) { cur = m[1]; continue; }
    if (cur && line.includes('退族=')) {
      const num = (k) => { const r = new RegExp(k + '=(-?\\d+(?:\\.\\d+)?)').exec(line); return r ? Number(r[1]) : null; };
      out.set(cur, { drop: num('退族'), boxin: num('框内'), hyp: num('H'), fams: num('族'), ink: num('底占') });
      cur = null;
    }
  }
  return out;
}

const a = parse(process.argv[2]), b = parse(process.argv[3]);
const keys = [...new Set([...a.keys(), ...b.keys()])];
let dropDiff = [], drift = [];
for (const k of keys) {
  const x = a.get(k), y = b.get(k);
  if (!x || !y) { console.log(`只在单边出现：${k} ${x ? '仅前' : ''}${y ? '仅后' : ''}`); continue; }
  if (x.drop !== y.drop) dropDiff.push(`${k} 退族 ${x.drop}→${y.drop}（框内 ${x.boxin}→${y.boxin}）`);
  const d = Math.abs(x.boxin - y.boxin);
  if (d > 0.15) drift.push(`${k} |Δ框内|=${d.toFixed(2)} 退族 ${x.drop}/${y.drop}`);
}
console.log(`比了 ${keys.length} 张`);
console.log(`\n退族随边长翻的（=这一刀自己的密度依赖）：${dropDiff.length}`);
dropDiff.forEach(s => console.log('  ' + s));
console.log(`\n框内漂移>0.15 的（=账本 driftN 的口径）：${drift.length}`);
drift.forEach(s => console.log('  ' + s));
