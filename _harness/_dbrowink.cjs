// **磁盘上那把全零掩码有没有变成屏上那一张：直接查桌面库里那一行的 α 分布。**
// 跑法：node _dbrowink.cjs <app_icons.db> <组件子串> [<组件子串> …]
// ⚠ 库里那张 `mono_icon`（256² ALPHA_8）是**桌面自己存的那一张**；它不等于我们交的掩码
//   （10-20 对菜鸟量过 IoU 0.001）。所以这一台回答的是"屏上那张是不是空的"，
//   不回答"我们交的是不是空的" —— 后者已经由 `_maskzero.cjs` 在磁盘上量掉了。
const { DatabaseSync } = require('node:sqlite');
const [dbFile, ...needles] = process.argv.slice(2);
if (!dbFile || !needles.length) { console.error('用法: node _dbrowink.cjs <db> <组件子串> …'); process.exit(2); }
const db = new DatabaseSync(dbFile, { readOnly: true });
const all = db.prepare('select componentName as c, icon as i, mono_icon as m from Icons').all();
db.close();
let hits = 0;
const prof = b => {
  const u = b instanceof Uint8Array ? b : Buffer.from(b);
  let max = 0, ink = 0;
  for (let i = 0; i < u.length; i++) { if (u[i] > max) max = u[i]; if (u[i] > 0) ink++; }
  return `${u.length} 字节 max=${max} 上墨 ${(100 * ink / u.length).toFixed(2)}%`;
};
for (const n of needles) {
  const rows = all.filter(r => String(r.c).includes(n));
  if (!rows.length) { console.log(`!! 「${n}」在库里 0 行 —— 这一轮没被画（不是"没问题"）`); continue; }
  for (const r of rows) {
    hits++;
    console.log(`${r.c}`);
    console.log(`   mono_icon: ${r.m ? prof(r.m) : 'NULL'}`);
    console.log(`   icon     : ${r.i ? prof(r.i) : 'NULL'}`);
  }
}
const zeroMono = all.filter(r => r.m && r.m.length && (() => { const u = r.m instanceof Uint8Array ? r.m : Buffer.from(r.m); for (let i = 0; i < u.length; i++) if (u[i]) return false; return true; })());
console.log(`全库 ${all.length} 行里 mono_icon 整张全零的：**${zeroMono.length}**${zeroMono.length ? ' → ' + zeroMono.map(r => r.c).slice(0, 5).join(', ') : ''}`);
process.exit(hits ? 0 : 1);
