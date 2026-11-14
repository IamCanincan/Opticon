// 一次性：比两份 JSON（认账基线时**必须**先看它到底动了几个字段）。
// 用法：node _harness/_jsondiff.cjs <旧.json> <新.json>
const fs = require('fs');
const rd = (f) => JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));
const [fa, fb] = process.argv.slice(2);
const a = rd(fa), b = rd(fb);
const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
let n = 0;
for (const k of [...keys].sort()) {
  const x = JSON.stringify(a[k]), y = JSON.stringify(b[k]);
  if (x !== y) { n++; console.log('  ' + k + ': ' + x + ' → ' + y); }
}
console.log(`动了 ${n} 个字段（共 ${keys.size} 个，其余一字未改）`);
process.exit(n === 0 ? 1 : 0);   // 一个都没动 = 这次"认账"什么都没做，别当证据
