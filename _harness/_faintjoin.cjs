// 屏上那批"淡"的（桌面库 `mono_icon` 均α 很低）**有没有一张真源能对得上**。
//
//   左：`dumpmono.cjs` 从桌面 `app_icons.db` 倒的**上屏那张**（256² ALPHA_8，均α 就是屏幕上的浓度）
//   右：`GhostSweepKt` 在同名包上复放出来的**我们这一趟的均α**（设备喂进来的原尺寸）
//
// 只按**包名** join：库里 componentName 是 `包/Activity`，dump 的 label 是 `包/res/名_签名`，
// 只有包这一层是共同的。所以这张表能回答"这一族是不是我们的"，
// 不能回答"这一个组件是不是我们的"—— 后者要靠清缓存当场落盘的 键= 归属清单（余账 #5）。
const fs = require('fs');
const DB = process.argv[2] || 'D:/tmp/mono_1529/manifest.tsv';
const GH = process.argv[3] || 'D:/tmp/ghost.tsv';
const CUT = parseFloat(process.argv[4] || '15');

const man = fs.readFileSync(DB, 'utf8').trim().split(/\r?\n/).slice(1);
const db = [];
for (const l of man) {
  const [comp, file, note] = l.split('\t');
  const m = /均α ([\d.]+)/.exec(note || '');
  if (!m) continue;
  db.push({ comp, file, mean: parseFloat(m[1]), pkg: (comp || '').split('/')[0] });
}
const g = fs.readFileSync(GH, 'utf8').trim().split(/\r?\n/);
const head = g[0].split('\t');
const byPkg = new Map();
for (const l of g.slice(1)) {
  const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]);
  const pkg = o.label.split('/')[0];
  if (!byPkg.has(pkg)) byPkg.set(pkg, []);
  byPkg.get(pkg).push(o);
}
const faint = db.filter(d => d.mean <= CUT).sort((a, b) => a.mean - b.mean);
console.log(`屏上均α ≤ ${CUT} 的组件 ${faint.length} 个 / 库里共 ${db.length} 行`);
console.log(`真源表覆盖的包 ${byPkg.size} 个`);
let hit = 0;
for (const d of faint) {
  const rows = byPkg.get(d.pkg);
  if (!rows) continue;
  hit++;
  const best = rows.map(r => ({ r, m: parseFloat(r['均α']) })).sort((a, b) => b.m - a.m)[0];
  console.log(`  屏 ${String(d.mean).padStart(5)}  离线复放 ${best.m.toFixed(1).padStart(6)}  ` +
    `盘${best.r['盘%']}% 暗${best.r['暗%']}% 透明${best.r['透明%']}% 底占备${best.r.备底占}/离${best.r.离底占} H备${best.r.备H}/离${best.r.离H} ` +
    `| ${d.pkg}  ← ${best.r.label.slice(0, 40)}`);
}
console.log(`\n有真源可对上的 ${hit} 个 / 没有的 ${faint.length - hit} 个（这些**一张像素都没回来过**，只能再抓）`);
