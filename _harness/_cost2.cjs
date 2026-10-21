// 成对比两遍 MaskBench 的 ms/张 —— 选支要算两遍，代价必须落在**同一张**上比，
// 不能拿两个中位数比（那两张不是同一批热态，第一次跑还带着 JIT 冷启动）。
const fs = require('fs');
const grab = f => {
  const m = new Map(); let cur = null;
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    const t = l.match(/^--- (\S+).*?\s(\d+)ms/);
    if (t) { cur = t[1].replace(/^icon\d+_/, '').replace(/_.*$/, ''); m.set(cur, +t[2]); }
  }
  return m;
};
const a = grab(process.argv[2]), b = grab(process.argv[3]);
const rows = [...a.keys()].filter(k => b.has(k)).map(k => [k, a.get(k), b.get(k), b.get(k) - a.get(k)])
  .sort((x, y) => y[3] - x[3]);
const d = rows.map(r => r[3]).sort((x, y) => x - y);
const q = p => d[Math.min(d.length - 1, Math.floor(d.length * p))];
console.log(`成对 ${rows.length} 张   Δms  中位 ${q(0.5)}  p90 ${q(0.9)}  最大 ${d[d.length - 1]}`);
console.log('  加得最狠的 6 张：');
for (const r of rows.slice(0, 6)) console.log(`   ${String(r[3]).padStart(5)}ms  ${String(r[1]).padStart(4)}→${String(r[2]).padStart(4)}  ${r[0]}`);
console.log(`  变慢超过 100ms 的：${rows.filter(r => r[3] > 100).length} 张   反而快了的：${rows.filter(r => r[3] < 0).length} 张`);
