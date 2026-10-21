// **空掩码的主人能不能不装机就认出来：去桌面自己的 `app_icons.db` 里找那张全透明的 `mono_icon`。**
//
// 为什么这条路值得走一遍：`docs/devlog` 那笔"全空掩码 `dac950aa…` 归属未定"挂了一整天 ——
//   `键=` 是内容哈希（不带身份）、`sig=dce53c1df856` 一把盖住 36 个标签，两条都已被我否决过。
//   剩下的唯一对岸是**桌面自己存的那张位图**：它按 `componentName` 存行，
//   而 `mono_icon` 那一列就是屏幕上那张 ALPHA_8。若某行的 mono_icon **每个字节都是 0**，
//   那"我们交了一张空"与"桌面为这个组件存了一张空"就在同一行上对上号了。
//
// ⚠ **这条对表有两个已知的不成立处，结论只能当"候选归属"，不能当定案**：
//   ① 库里的 `icon` blob **不是钩子收到的那张 drawable**（2026-10-20 记过）—— 它可能被桌面加工过；
//   ② 桌面只在 `getMonochrome()==null` 时才用我们的遮罩，且**换模块不会重压这张库**，
//      所以行可能是**旧版**留下的。要看新行为得点「清图标缓存并重启桌面」。
//   ⇒ 所以这一台报的是"哪些组件存着一张全透明 mono_icon"，不报"所以 dac950aa 就是它"。
// 跑法：node _blankdb.cjs D:/tmp/app_icons.db [--col=mono_icon]
const { DatabaseSync } = require('node:sqlite');
const f = process.argv[2];
if (!f) { console.error('用法：node _blankdb.cjs <app_icons.db>'); process.exit(2); }
const db = new DatabaseSync(f, { readOnly: true });
const cols = db.prepare('PRAGMA table_info(Icons)').all().map(c => c.name);
console.log('Icons 列：', cols.join(', '));
const MONO = (process.argv.find(a => a.startsWith('--col=')) || '--col=mono_icon').substring(6);
if (!cols.includes(MONO)) { console.error(`!! 没有 ${MONO} 这一列 —— 别把"列名不对"读成"一张空都没有"`); process.exit(2); }
const rows = db.prepare(`select componentName, profileId, label, ${MONO} as m, icon is not null as hasIcon from Icons`).all();
let has = 0, blank = [], short = [];
const lens = new Map();   // 字节数分布：`mono_icon 是 256²` 这种话要自己量出来，不能引用记忆
for (const r of rows) {
  if (!r.m) continue;
  has++;
  const b = r.m instanceof Uint8Array ? r.m : new Uint8Array(r.m);
  lens.set(b.length, (lens.get(b.length) || 0) + 1);
  // ALPHA_8：一个字节 = 一个像素的不透明度。**全 0 = 整张透明**。
  let nz = 0; for (const x of b) if (x !== 0) { nz++; if (nz > 8) break; }
  if (nz === 0) blank.push([r.componentName, r.label, r.profileId, b.length, r.hasIcon]);
  else if (b.length < 1024) short.push([r.componentName, b.length]);
}
console.log(`\n表里 ${rows.length} 行，${MONO} 非空 ${has} 行`);
console.log('字节数分布（`side²`=边长）：' + [...lens.entries()].sort((a, b) => b[1] - a[1])
  .map(([n, c]) => `${n}(=${Math.round(Math.sqrt(n))}²)×${c}`).join('  '));
console.log(`其中**整张透明**（非零字节 0 个）：${blank.length} 行`);
for (const [c, lb, prof, len, hi] of blank.slice(0, 40))
  console.log(`  ${String(c).padEnd(48)} 标签=${String(lb).padEnd(18)} profile=${prof} 字节=${len} icon列有图=${hi ? 'Y' : 'N'}`);
if (blank.length > 40) console.log(`  …共 ${blank.length} 行`);
console.log(`\n对照：<1024 字节但非全空的那些（小尺寸快捷方式之类）= ${short.length} 行`);
for (const [c, len] of short.slice(0, 6)) console.log(`  ${c} ${len} 字节`);
// `mono_icon` 为 null 的那几行也要点名：**"库里一张空都没有"这句话，只有把"根本没有这一行"
// 与"有行但是空的"分开数才算数**（否则 0 行的结论里混着"桌面没存"那一类）。
console.log('\n== mono_icon 为 NULL 的行 ==');
for (const r of rows.filter(x => !x.m).slice(0, 20))
  console.log(`  ${String(r.componentName).padEnd(52)} 标签=${r.label} icon列有图=${r.hasIcon ? 'Y' : 'N'}`);
db.close();
