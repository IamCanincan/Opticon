const fs = require('fs');
function rows(f, tag) {
  const out = [];
  for (const blk of fs.readFileSync(f, 'utf8').split(/\n(?=--- )/)) {
    const h = blk.match(/^--- (\S+)/); if (!h) continue;
    if (/^icon\d+_/.test(h[1]) === (tag === 'dev')) continue;   // dev=无 iconN 前缀
    const g = {}; for (const kv of blk.matchAll(/([^\s=]+)=([^\s]+)/g)) if (!(kv[1] in g)) g[kv[1]] = kv[2];
    const m = (g['分档'] || '').match(/^(\d+)\/(\d+)\(([^)]*)\)/); if (!m) continue;
    const parts = m[3].split(',').map(Number);
    const top = parts.filter(p => p >= 250).length;
    out.push({ tag, n: h[1], lv: +m[1], np: parts.length, top, fill: +(g['框内'] || 0), H: g['H'],
      cov: +(g['色驱'] || 0), mark: +(g['字均净'] || 0), stretch: g['色阶'] || '' });
  }
  return out;
}
const all = [...rows('bench/g_dev.txt', 'dev'), ...rows('bench/g_pool.txt', 'pool')];
const bad = all.filter(r => r.np >= 3 && r.top === r.np);      // 三块以上、**全部**贴顶
const part = all.filter(r => r.np >= 3 && r.top >= 2 && r.top < r.np);
console.log(`总 ${all.length} 张（dev ${all.filter(r=>r.tag==='dev').length} / pool ${all.filter(r=>r.tag==='pool').length}）`);
console.log(`  **≥3 块且全部贴到 250+**（块与块之间已被饱和并掉）：${bad.length} 张`);
console.log(`  ≥3 块、其中 ≥2 块贴顶但还有别的档：${part.length} 张`);
console.log(`  贴顶块数中位：${all.map(r => r.top).sort((a,b)=>a-b)[all.length>>1]} / 块数中位 ${all.map(r=>r.np).sort((a,b)=>a-b)[all.length>>1]}`);
const byH = {}; for (const r of bad) byH[r.H] = (byH[r.H] || 0) + 1;
console.log(`  全贴顶那些走的是哪支配法：${JSON.stringify(byH)}`);
const st = {}; for (const r of bad) st[r.stretch] = (st[r.stretch] || 0) + 1;
console.log(`  它们的「色阶」列（谁抬的顶）：`);
for (const [k, v] of Object.entries(st).sort((a,b)=>b[1]-a[1]).slice(0,6)) console.log(`    ${String(v).padStart(3)} × ${k}`);
console.log('\n全贴顶的名单（前 14）：');
for (const r of bad.slice(0, 14)) console.log(`  ${(r.tag+' '+r.n.replace(/_[0-9a-f]{12}(-src)?(\.png)?$/,'').replace(/^icon\d+_/,'')).slice(0,48).padEnd(48)} H=${r.H} ${r.np}块全贴顶 框内=${r.fill} 色驱=${r.cov} 净=${r.mark}`);
