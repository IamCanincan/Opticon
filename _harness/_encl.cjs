// 读候选的报告行：新那条「围合 + 白盘」到底翻到了谁、漏了谁。
const fs = require('fs');
const norm = s => s.replace(/\r/g, '').replace(/\.png$/, '').replace(/-src$/, '')
  .replace(/^icon\d+_/, '').replace(/_[0-9a-f]{12}$/, '');
const g = (l, k) => { const m = l.match(new RegExp(k + '(\\S+)')); return m ? m[1] : '?'; };
const rows = [];
for (const b of fs.readFileSync(process.argv[2] || 'cand_encl.txt', 'utf8').split(/^--- /m).slice(1)) {
  const nm = norm(b.split(/\s+/)[0]);
  const rep = b.split('\n').find(l => l.includes('围='));
  if (!rep) continue;
  rows.push({
    nm, encl: +g(rep, '围='), hi: +g(rep, '坡='), gL: +g(rep, '底L='), flip: +g(rep, '翻='),
    conc: (b.split('\n').find(l => l.includes('浓度[')) || '').replace(/^\s+/, ''),
  });
}
console.log('在满幅支上 ' + rows.length + ' 张');
console.log('—— 翻=1 ——');
for (const r of rows.filter(x => x.flip === 1))
  console.log('  ' + r.nm.slice(0, 30).padEnd(31) + '围' + String(r.encl).padStart(4) + '%  坡hi=' + String(r.hi).padStart(3) +
    '  底L=' + String(r.gL).padStart(3) + '  ' + r.conc);
console.log('—— 翻=0 但围合≥80%（新条件没抓到的，看是不是被"底不在最亮端"挡下）——');
for (const r of rows.filter(x => x.flip === 0 && x.encl >= 80))
  console.log('  ' + r.nm.slice(0, 30).padEnd(31) + '围' + String(r.encl).padStart(4) + '%  坡hi=' + String(r.hi).padStart(3) +
    '  底L=' + String(r.gL).padStart(3) + '  差=' + (r.hi - r.gL));
console.log('—— 围合最高的十张（不管翻没翻）——');
for (const r of [...rows].sort((a, b) => b.encl - a.encl).slice(0, 10))
  console.log('  ' + r.nm.slice(0, 30).padEnd(31) + '围' + String(r.encl).padStart(4) + '%  坡hi=' + String(r.hi).padStart(3) +
    '  底L=' + String(r.gL).padStart(3) + '  翻=' + r.flip);
