// 两个桌面库逐行对比：任何 mono_icon 不一致的行都报出来。
// 用法：node dbdiff.cjs
const { DatabaseSync } = require('node:sqlite');
function load(p) {
  const db = new DatabaseSync(p, { readOnly: true });
  const rows = db.prepare('SELECT componentName c, mono_icon m FROM icons').all();
  const map = new Map();
  for (const r of rows) map.set(r.c, r.m == null ? null : Buffer.from(r.m));
  return map;
}
const A = load('D:/tmp/app_icons_before.db');
const B = load('D:/tmp/app_icons_after.db');
function stats(b) {
  let off = 0;
  if (b.length === 65544) off = 8; else if (b.length !== 65536) return null;
  let ink = 0, solid = 0;
  for (let i = off; i < b.length; i++) { const a = b[i]; if (a > 0) ink++; if (a === 255) solid++; }
  const n = b.length - off;
  return [(ink / n * 100).toFixed(2), (solid / n * 100).toFixed(2)];
}
console.log('before 行数 ' + A.size + '   after 行数 ' + B.size);
const onlyA = [...A.keys()].filter(k => !B.has(k));
const onlyB = [...B.keys()].filter(k => !A.has(k));
if (onlyA.length) console.log('只在 before：' + onlyA.join(', '));
if (onlyB.length) console.log('只在 after：' + onlyB.join(', '));
// ⚠ 这一行是 2026-10-20 补的：那天我拿一份**缺了目标行**的快照做对照，目标那张老老实实列在
//   上面那串 `只在 after：` 里，我没读，于是把"没进差异清单"读成了"改后没变"，
//   当场下过一个"判据开火两次却逐字节没动"的错结论。**配不上对 ≠ 没变。**
if (onlyA.length + onlyB.length > 0) {
  console.log('!! 两边键集合不等：' + onlyA.length + ' 行只在前 / ' + onlyB.length + ' 行只在后 —— ' +
    '下面那个"不同 N 张"**不包括**这些行，要判的那张若在其中，本轮不能得出"没变"的结论');
}
let same = 0, diff = [];
for (const [k, a] of A) {
  if (!B.has(k)) continue;
  const b = B.get(k);
  if (a === null || b === null) { if (a !== b) diff.push(`${k}\n    改前 mono=${a === null ? '无' : '有'}\n    改后 mono=${b === null ? '无' : '有'}`); else same++; continue; }
  if (a.equals(b)) { same++; continue; }
  const sa = stats(a), sb = stats(b);
  const la = sa ? sa.join(' / ') : 'len=' + a.length;
  const lb = sb ? sb.join(' / ') : 'len=' + b.length;
  diff.push(`${k}\n    改前 有墨 ${la}%\n    改后 有墨 ${lb}%`);
}
console.log('逐字节相同 ' + same + ' 张，不同 ' + diff.length + ' 张');
for (const d of diff) console.log('  · ' + d);
