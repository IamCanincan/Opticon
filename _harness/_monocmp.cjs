// **装的这版有没有动画面：逐张比 `app_icons.db` 里屏幕上那张 256² 单色位图。**
//
// 为什么用这张库而不是比缓存 `.a8`：**缓存文件名里的 `键=` 就是内容哈希**，
//   同名必然同字节 —— 拿它当"没变"的证据是**循环论证**（一把自己给自己打满分的尺）。
//   而 `app_icons.db.mono_icon` 是**桌面自己存的那一张**（按 `componentName` 存行），
//   它不因为我们改了写缓存的逻辑而变，所以它是独立的一岸。
//
// 跑法：node _monocmp.cjs <旧库> <新库>
// ⚠ 两边都必须是**同一台桌面、同一份 workspace**，且旧那份要来自"行为基线"那版；
//   这里旧库 = 2026-10-20 22:55（17:12 装的那版跑出来的），新库 = 今天装了归属索引之后。
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const [A, B] = process.argv.slice(2);
if (!A || !B) { console.error('用法: node _monocmp.cjs <旧库> <新库>'); process.exit(2); }
const load = f => {
  const db = new DatabaseSync(f, { readOnly: true });
  const m = new Map();
  for (const r of db.prepare('select componentName as c, mono_icon as m from Icons').all()) {
    if (!r.m) continue;
    m.set(String(r.c), r.m instanceof Uint8Array ? r.m : new Uint8Array(r.m));
  }
  db.close(); return m;
};
const a = load(A), b = load(B);
console.log(`旧库 ${a.size} 张、新库 ${b.size} 张有 mono_icon`);
let same = 0, diff = 0, sizeOnly = 0; const bad = []; const diffNames = [];
const dumpArg = process.argv.find(x => x.startsWith('--dump='));
for (const [k, va] of a) {
  const vb = b.get(k);
  if (!vb) continue;                       // 新库里没有这行 —— 单独数
  if (va.length !== vb.length) { sizeOnly++; diffNames.push(k); bad.push(`  尺寸变 ${k}: ${va.length} → ${vb.length}`); continue; }
  let nd = 0, max = 0;
  for (let i = 0; i < va.length; i++) if (va[i] !== vb[i]) { nd++; if (Math.abs(va[i] - vb[i]) > max) max = Math.abs(va[i] - vb[i]); }
  if (nd === 0) same++; else { diff++; diffNames.push(k); bad.push(`  变了 ${k}: ${nd} 格不同、最大 |Δα|=${max}`); }
}
const onlyOld = [...a.keys()].filter(k => !b.has(k));
const onlyNew = [...b.keys()].filter(k => !a.has(k));
console.log(`可比 ${same + diff + sizeOnly} 张：**逐字节相同 ${same}** / 有差异 ${diff} / 尺寸就变了 ${sizeOnly}`);
console.log(`只在一边的：旧有新无 ${onlyOld.length}、新有旧无 ${onlyNew.length}`);
if (onlyOld.length) console.log('  旧有新无（多半是这次没被用到的组件）：' + onlyOld.slice(0, 6).join(', '));
if (onlyNew.length) console.log('  新有旧无：' + onlyNew.slice(0, 6).join(', '));
bad.slice(0, 8).forEach(l => console.log(l));
// `--dump=`：把**差异组件名单**落盘，好把两次比较做交集（"装了这版之后变了哪些" vs
// "同一版代码两次抓取本来就变哪些"）。没有这一步，19 张差异里"哪几张是我弄的"只能靠眼看。
if (dumpArg) fs.writeFileSync(dumpArg.substring(7), diffNames.join('\n') + '\n');

// **正对照：这台比得出"不一样"吗。** 少了这一条，"0 张不同"与"比较函数恒说相同"长得一模一样
// —— 本仓那条老规矩：任何用 0 下结论的场合，先拿一个已知会命中的样本验它能数到非零。
{
  const anyK = [...a.keys()].find(k => b.has(k));
  if (!anyK) { console.error('!! 两库没有任何共同组件 —— 这台比不了，"0 差异"不算证过'); process.exit(2); }
  const vb = new Uint8Array(b.get(anyK));
  vb[0] = vb[0] ^ 0xff;                                   // 亲手改 1 格
  let nd = 0; const va = a.get(anyK);
  for (let i = 0; i < va.length; i++) if (va[i] !== vb[i]) nd++;
  console.log(`正对照：把 ${anyK.slice(0, 34)} 的第 0 格翻转 ⇒ 数到 ${nd} 格不同（必须 > 0）`);
  if (nd === 0) { console.error('!! 正对照失败 —— 比较器看不见改动，上面那个 0 不能用'); process.exit(1); }
}
