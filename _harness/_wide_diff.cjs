// 装机 vs 「开火的全走广色度」：哪些图会换交付、换完之后掉多少墨。
const fs = require('fs');
function rows(p) {
  const out = new Map(); let cur = null;
  for (const l of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = l.match(/^--- (\S+)/); if (m) { cur = m[1]; continue }
    if (cur && /字均净=/.test(l)) {
      const g = (re) => { const x = l.match(re); return x ? x[1] : '' };
      out.set(cur, { wide: /调=1/.test(l), z: +g(/字均净=(-?\d+)/), k: +g(/框内=([\d.]+)/) });
      cur = null;
    }
  }
  return out;
}
const [a, b] = process.argv.slice(2);
const A = rows(a), B = rows(b);
const changed = [...B.keys()].filter(k => A.has(k) && !A.get(k).wide && B.get(k).wide);
const worse = changed.map(k => ({ k, dz: B.get(k).z - A.get(k).z, kA: A.get(k).k, kB: B.get(k).k }))
  .sort((x, y) => x.dz - y.dz);
console.log(`换交付的张数 ${changed.length} ｜ 装机交参照、这一版交广`);
console.log(`掉墨最狠的 10 张（字均净 差值）：`);
for (const r of worse.slice(0, 10)) console.log(`   ${String(r.dz).padStart(5)}  ${r.k.split('_')[0].slice(0, 34)}`);
console.log(`变实的最前 5 张：`);
for (const r of worse.slice(-5).reverse()) console.log(`   ${String(r.dz).padStart(5)}  ${r.k.split('_')[0].slice(0, 34)}`);
fs.writeFileSync('bench/reg/wide_changed.txt', changed.map(x => x.k).join('\n') + '\n');
console.log(`名单写在 bench/reg/wide_changed.txt（${changed.length} 行）`);
