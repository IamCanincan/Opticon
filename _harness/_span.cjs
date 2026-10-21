const fs = require('fs');
function rows(f, tag) {
  const out = new Map();
  for (const blk of fs.readFileSync(f, 'utf8').split(/\n(?=--- )/)) {
    const h = blk.match(/^--- (\S+)/); if (!h) continue;
    const g = {}; for (const kv of blk.matchAll(/(?:^|\s)([^\s=]+)=([^\s]+)/g)) if (!(kv[1] in g)) g[kv[1]] = kv[2];
    const key = h[1].replace(/_[0-9a-f]{12}(\.png)?$/, '').replace(/^icon\d+_/, '');
    if (!key.startsWith('com.') && !key.match(/^[a-z]/)) continue;
    out.set(key, { tag, H: g['H'], zero: +(g['零'] || 0), sol: +(g['实'] || 0), fill: g['框内'],
      cc: +(g['cc'] || 0), mark: +(g['字均净'] || 0), lv: g['分档'], hyp: g['H0'] });
  }
  return out;
}
const a = rows('bench/all_now.txt', 'now'), b = rows('bench/all_var.txt', 'var');
const ch = [];
for (const [k, y] of b) { const x = a.get(k); if (!x) continue;
  if (x.fill === y.fill && x.lv === y.lv) continue; ch.push(k); }
console.log(`换支且交付变动的：${ch.length} 张（真机语料里）\n`);
console.log('包名'.padEnd(40) + '现在: H 零/实 跨度 框内 cc'.padEnd(34) + '换闸后: H 零/实 跨度 框内 cc 字均净');
for (const k of ch) { const x = a.get(k), y = b.get(k);
  const f = r => `${r.H} ${r.zero}/${r.sol} 跨${String(r.sol - r.zero).padStart(3)} ${String(r.fill).padStart(4)} cc${String(r.cc).padStart(3)}`;
  console.log(k.slice(0, 38).padEnd(40) + f(x).padEnd(34) + f(y) + ` 净${y.mark}`); }
const sp = ch.map(k => b.get(k).sol - b.get(k).zero).sort((p, q) => p - q);
console.log('\n换闸后跨度：', sp.join(', '));
