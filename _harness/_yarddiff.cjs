// 把两次对表逐张做差：仓里（有豁免）vs 候选（关掉 H1 的 families<=1 豁免）。
// 为什么单独一张台：汇总的中位数会**一张变好一张变坏时不动**，而这里要回答的正是
// "这一刀改了哪几张真图、里面有没有'整张纯色'那一种"。
const fs = require('fs');
const read = (p) => fs.readFileSync(p, 'utf8').trim().split('\n').slice(1)
  .map((l) => l.split('\t'))
  .map(([pkg, iou, da, us, them]) => ({ pkg, iou: +iou, da: +da, us: +us, them: +them }));
const A = new Map(read(process.argv[2]).map((r) => [r.pkg, r]));
const B = new Map(read(process.argv[3]).map((r) => [r.pkg, r]));

const rows = [];
for (const [pkg, b] of B) {
  const a = A.get(pkg);
  if (!a) { rows.push({ pkg, a: null, b, d: null }); continue; }
  rows.push({ pkg, a, b, d: b.iou - a.iou });
}
const miss = [...A.keys()].filter((k) => !B.has(k));
if (miss.length) console.log('!! 只在一次里出现的：' + miss.join(', '));

const better = rows.filter((r) => r.d > 0.005).sort((x, y) => y.d - x.d);
const worse = rows.filter((r) => r.d < -0.005).sort((x, y) => x.d - y.d);
const same = rows.filter((r) => Math.abs(r.d) <= 0.005);

const f = (n) => n.toFixed(3);
const sh = (r) => `${f(r.iou)}(墨${r.us.toFixed(1)}%)`;

console.log(`配对 ${rows.length} 张  变好 ${better.length}  变坏 ${worse.length}  不动 ${same.length}`);
console.log('\n变好（IoU 增）：');
for (const r of better) console.log(`  ${r.pkg.padEnd(44)} ${sh(r.a)} → ${sh(r.b)}  +${f(r.d)}`);
console.log('\n变坏（IoU 减）：');
for (const r of worse) console.log(`  ${r.pkg.padEnd(44)} ${sh(r.a)} → ${sh(r.b)}  ${f(r.d)}`);
console.log('\n不动的里面，两边都交了空掩码的（这一刀没救到的）：');
for (const r of same.filter((x) => x.b.us < 0.5 && x.b.them >= 0.5))
  console.log(`  ${r.pkg.padEnd(44)} 我们 0 墨 / 人画 ${r.b.them.toFixed(1)}%`);
console.log('\n不动的里面，人画那层本身就是空图的（不参与判断）：');
for (const r of same.filter((x) => x.b.them < 0.5))
  console.log(`  ${r.pkg.padEnd(44)} 人画 ${r.b.them.toFixed(1)}% 我们 ${r.b.us.toFixed(1)}%`);
console.log('\n变坏的里面，"我们比人画多涂一大块"的（差 >25 个百分点）：');
for (const r of worse.filter((x) => x.b.us - x.b.them > 25))
  console.log(`  ${r.pkg.padEnd(44)} 关豁免后我们 ${r.b.us.toFixed(1)}% / 人画 ${r.b.them.toFixed(1)}%`);
