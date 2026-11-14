// 主人那句「单色和彩色一样的几何结构」的**唯一该看的表**：同一个图标的两栏，谁还不一样大。
//   分母不是平台、不是源图，就是**这个图标自己的彩色**（`_pairshot.cjs` 的两栏外接框）。
// 用法：node _harness/_pairlist.cjs <app_icons.db> [容差=0.05]
const { execFileSync } = require('child_process');
const [dbp, tolS] = process.argv.slice(2);
const tol = tolS ? +tolS : 0.05;
const txt = execFileSync('node', ['_harness/_pairshot.cjs', dbp], { encoding: 'utf8', maxBuffer: 1 << 28 });
const byPkg = new Map();
for (const l of txt.split('\n')) {
  const t = l.trim();
  if (!t || t.startsWith('组件') || t.startsWith('共') || t.startsWith('缩进')) continue;
  const p = t.split(/\s+/);
  const nums = p.slice(-5).map(Number);
  if (nums.some(Number.isNaN)) continue;
  const c = p[0], pkg = c.split('/')[0];
  if (!byPkg.has(pkg)) byPkg.set(pkg, { c, ratio: nums[2], cw: nums[0], mw: nums[1] });
}
const all = [...byPkg.values()];
const off = all.filter((x) => x.ratio < 1 - tol || x.ratio > 1 + tol).sort((a, b) => a.ratio - b.ratio);
console.log(`按包去重 ${all.length} 个：单色÷彩色 在 ${(1 - tol).toFixed(2)}~${(1 + tol).toFixed(2)} 之间 **${all.length - off.length} 个**，还不一样大的 **${off.length} 个**`);
const small = off.filter((x) => x.ratio < 1 - tol), big = off.filter((x) => x.ratio > 1 + tol).reverse();
console.log(`  偏小 ${small.length} 个：`);
for (const x of small) console.log(`    ${x.ratio.toFixed(3)}  ${x.c.slice(0, 54)}`);
console.log(`  偏大 ${big.length} 个：`);
for (const x of big) console.log(`    ${x.ratio.toFixed(3)}  ${x.c.slice(0, 54)}`);
