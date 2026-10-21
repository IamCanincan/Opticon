// 读 bench/pnum.txt：把极性判据的三个输入摊开（gL / iL / gN% / 翻没翻），
// 并标出主人点名的那几张。用来决定"翻不翻"还能问什么，不再凭感觉。
const fs = require('fs');
const SRC = process.argv[2] || 'bench/pnum.txt';
const rows = [];
for (const b of fs.readFileSync(SRC, 'utf8').split(/^--- /m).slice(1)) {
  const nm = b.split(/\s+/)[0].replace(/^icon\d+_/, '').replace(/-src$/, '').replace(/_[0-9a-f]{12}$/, '');
  const rep = b.split('\n').find(l => l.includes('gL='));
  if (!rep) continue;
  const g = k => { const m = rep.match(new RegExp(k + '(\\S+)')); return m ? m[1] : '?'; };
  const conc = (b.split('\n').find(l => l.includes('浓度[')) || '').match(/中128-254=(\d+)/);
  rows.push({
    nm, gL: +g('gL='), iL: +g('iL='), gN: +g('gN='), iN: +g('iN='),
    flip: +g('翻='), air: +g('气='), slope: g('坡='), mid: conc ? +conc[1] : 0,
  });
}
const star = /browndust2|predidit|jingdong|sgameGlobal|feimo|tmgp\.sgame|wisdomgame/;
rows.sort((a, b) => (b.iL - b.gL) - (a.iL - a.gL));
console.log('在满幅支上 ' + rows.length + ' 张，按 iL−gL 排（>0 才可能翻）');
console.log('  图标'.padEnd(36) + '  gL   iL  iL-gL   gN%   翻   坡lo/hi  中128+');
for (const r of rows) {
  const tot = r.gN + r.iN;
  console.log('  ' + r.nm.slice(0, 34).padEnd(34) +
    String(r.gL).padStart(5) + String(r.iL).padStart(5) + String(r.iL - r.gL).padStart(7) +
    String(Math.round(r.gN * 100 / Math.max(tot, 1))).padStart(6) + String(r.flip).padStart(5) +
    ('   ' + r.slope).padStart(10) + String(r.mid).padStart(9) + (star.test(r.nm) ? '  ★' : ''));
}

// 第二张表：**这张自己有没有暗部**（坡 lo = 全图实心像素 L 的 p5）。
// 猜的那条是「lo 已经到中性灰 → 暗=墨没东西可印 → 该翻」。先摊分布再画线。
const withSlope = rows.filter(r => /^\d+\/\d+$/.test(r.slope)).map(r => {
  const [lo, hi] = r.slope.split('/').map(Number);
  return { ...r, lo, hi };
}).sort((a, b) => a.lo - b.lo);
console.log('\n按「这张自己的 p5 亮度 lo」排（lo 大 = 整张没有暗部）：');
let n40 = 0, n50 = 0;
for (const r of withSlope) {
  if (r.lo >= 40) n40++;
  if (r.lo >= 50) n50++;
  console.log('  lo=' + String(r.lo).padStart(3) + ' hi=' + String(r.hi).padStart(3) +
    '  翻=' + r.flip + '  中128+ ' + String(r.mid).padStart(6) + '  ' + r.nm.slice(0, 34) +
    (star.test(r.nm) ? '  ★' : '') + (r.lo >= 40 ? '   ← lo≥40' : ''));
}
console.log('  lo≥40 的 ' + n40 + ' 张 / lo≥50 的 ' + n50 + ' 张（共 ' + withSlope.length + '）');
