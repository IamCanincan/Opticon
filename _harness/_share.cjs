// 满幅支上「底占」的分布：这条线要搬走谁。
const fs = require('fs');
const rows = [];
for (const b of fs.readFileSync(process.argv[2] || 'bench/reg/bench_384.txt', 'utf8').split(/^--- /m).slice(1)) {
  const nm = b.split(/\s+/)[0].replace(/^icon\d+_/, '').replace(/-src$/, '').replace(/_[0-9a-f]{12}$/, '');
  const rep = b.split('\n').find(l => l.includes('票='));
  if (!rep || !rep.includes('调=1')) continue;
  const g = k => { const m = rep.match(new RegExp(k + '=([^ ]+)')); return m ? m[1] : '?'; };
  rows.push({ nm, share: +g('底占'), plate: g('盘实'), ink: +g('框内'), span: +g('跨') });
}
rows.sort((a, b) => a.share - b.share);
console.log('满幅支 ' + rows.length + ' 张，按 底占 排（底占 = 参照说"这张有多少是盘"）：');
for (const r of rows)
  console.log('  ' + String(r.share).padStart(5) + '  跨' + String(r.span).padStart(4) + '  盘实=' +
    String(r.plate).padEnd(14) + r.nm.slice(0, 38) + (r.share <= 0.6 ? '   ← ≤0.6' : ''));
for (const t of [0.5, 0.6, 0.7, 0.8, 0.85])
  console.log('  底占≤' + t + ' 的 ' + rows.filter(r => r.share <= t).length + ' 张');
