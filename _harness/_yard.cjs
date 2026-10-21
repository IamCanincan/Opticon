const fs = require('fs');
function rep(f) { const m = new Map();
  for (const blk of fs.readFileSync(f, 'utf8').split(/\n(?=--- )/)) {
    const h = blk.match(/^--- (\S+)/); if (!h) continue;
    const g = {}; for (const kv of blk.matchAll(/([^\s=]+)=([^\s]+)/g)) if (!(kv[1] in g)) g[kv[1]] = kv[2];
    m.set(h[1].replace(/_[0-9a-f]{12}(\.png)?$/, ''), g);
  } return m; }
const now = rep('bench/all_now.txt'), v = rep('bench/all_var.txt');
const idx = fs.readFileSync('corpus_dev2/index.tsv', 'utf8').trim().split('\n').slice(1);
const rows = [];
for (const l of idx) { const c = l.split('\t');
  if (!c[12] || c[12] === '-') continue;                      // 只有带人画单色层的
  const human = +/^(\d+)\/(\d+)$/.exec(c[13]).slice(1)[1];    // 实心%
  const key = c[2].replace(/\.png$/, '').replace(/_[0-9a-f]{12}$/, '');
  const a = now.get(key), b = v.get(key); if (!a || !b) continue;
  rows.push({ pkg: c[0], human, old: +(a['框内'] * 100).toFixed(0), new: +(b['框内'] * 100).toFixed(0) });
}
const d0 = rows.map(r => Math.abs(r.old - r.human)), d1 = rows.map(r => Math.abs(r.new - r.human));
const mean = (x) => (x.reduce((s, v) => s + v, 0) / x.length).toFixed(1);
const med = (x) => x.slice().sort((p, q) => p - q)[x.length >> 1];
console.log(`配对 ${rows.length} 张（人画的墨占比 vs 我们交出去的框内）`);
console.log(`  平均偏差  改闸前 ${mean(d0)} 点 → 改闸后 ${mean(d1)} 点`);
console.log(`  中位偏差  改闸前 ${med(d0)} 点 → 改闸后 ${med(d1)} 点`);
console.log(`  更准 / 更差 / 不动：${d1.filter((x,i)=>x<d0[i]).length} / ${d1.filter((x,i)=>x>d0[i]).length} / ${d1.filter((x,i)=>x===d0[i]).length}`);
console.log(`  人画墨占比 中位 ${med(rows.map(r=>r.human))}%   我们(改闸后) 中位 ${med(rows.map(r=>r.new))}%`);
rows.sort((p, q) => Math.abs(q.new - q.human) - Math.abs(p.new - p.human));
console.log('\n最离谱的 12 张（人画% / 改闸前 / 改闸后）：');
for (const r of rows.slice(0, 12)) console.log(`  ${r.pkg.padEnd(40)} 人画 ${String(r.human).padStart(3)}%  旧 ${String(r.old).padStart(3)}%  新 ${String(r.new).padStart(3)}%`);
console.log('\n对得最近的 8 张：');
for (const r of rows.slice(-8)) console.log(`  ${r.pkg.padEnd(40)} 人画 ${String(r.human).padStart(3)}%  旧 ${String(r.old).padStart(3)}%  新 ${String(r.new).padStart(3)}%`);
