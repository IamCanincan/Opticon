// 对比两份 MaskBench 报告：装机 vs 全部广色度。只报数，不改任何东西。
const fs = require('fs');
function stats(p) {
  const rows = []; let cur = null;
  for (const l of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = l.match(/^--- (\S+)/);
    if (m) { cur = m[1]; continue }
    if (cur && /字均净=/.test(l)) {
      const g = (re) => { const x = l.match(re); return x ? +x[1] : NaN };
      rows.push({ name: cur.split('_')[0], z: g(/字均净=(-?\d+)/), k: g(/框内=([\d.]+)/), d: g(/淡=([\d.]+)/) });
      cur = null;
    }
  }
  const zs = rows.map(r => r.z).sort((a, b) => a - b), ks = rows.map(r => r.k).sort((a, b) => a - b);
  return {
    n: rows.length, medZ: zs[zs.length >> 1], medK: ks[ks.length >> 1],
    faint: rows.filter(r => r.z < 200).length, thin: rows.filter(r => r.k < 0.10).length,
    dead: rows.filter(r => r.k < 0.02).length,
    worst: rows.slice().sort((a, b) => a.z - b.z).slice(0, 8).map(r => `${r.name}:${r.z}`).join('  '),
  };
}
const [a, b] = process.argv.slice(2);
for (const [tag, f] of [['装机  ', a], ['全广  ', b]]) {
  const s = stats(f);
  console.log(`${tag} n=${s.n}  字均净中位=${s.medZ}  框内中位=${s.medK}  淡(字均净<200)=${s.faint}  ` +
    `框内<0.10=${s.thin}  框内<0.02=${s.dead}`);
  console.log(`      最掉墨的八张 ${s.worst}`);
}
