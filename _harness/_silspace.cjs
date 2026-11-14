// 「剪影占可见区几成」——一把不需要分母、也不需要剥背景的尺。
//   两边同一块画布：`app_icons.db` 的 `mono_icon` 就是 256² 的可见区那张
//   （透传那批 = 应用自己的 monochrome 层被桌面裁出来的同一口径；我们那批 = 我们交付的掩码被裁出来的）。
//   所以"该是几"不由我定：**透传那组的分布就是平台的答案**，我们只要落在里面。
// 用法：node _harness/_silspace.cjs <app_icons.db> <身份表.json>
// ⚠ 与 `_silmeter.cjs` 的区别写在明处：那一台比的是"剪影 ÷ 图案"，而"图案"在有背景层的图标上
//   要先把背景剥掉 —— 剥出来的东西在两组里不是同一个物，跨组不可比（2026-11-15 试了两次都栽在这）。
//   这一台**不做任何减法**，只量同一块画布上墨的外接框。
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const [dbp, clsP] = process.argv.slice(2);
const ev = new Map();
for (const r of JSON.parse(fs.readFileSync(clsP, 'utf8'))) ev.set(r.pkg, r);
const groupOf = (pkg) => {
  const e = ev.get(pkg);
  if (!e) return '待查';
  if (e.mono === '有') return '透传';
  return e.cls.includes('Adaptive') ? '合成' : e.cls === '要拆 APK' ? '待查' : '老式';
};
const d = new DatabaseSync(dbp, { readOnly: true });
const pick = new Map();
for (const r of d.prepare('SELECT componentName c, mono_icon m FROM icons').all()) {
  if (!r.m) continue;
  const pkg = r.c.split('/')[0], seg = r.c.split('/')[1] || '';
  const main = seg.startsWith(pkg) ? 0 : 1;
  const cur = pick.get(pkg);
  if (!cur || main < cur.main || (main === cur.main && seg.length < cur.seg.length)) pick.set(pkg, { r, main, seg });
}
const rows = [];
for (const [pkg, { r }] of pick) {
  const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length));
  if (n * n !== mb.length) continue;
  let l = n, t = n, rr = -1, bb = -1, ink = 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (mb[y * n + x] <= 8) continue;
    ink++;
    if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y;
  }
  if (rr < 0) continue;
  rows.push([pkg, groupOf(pkg), Math.max(rr - l + 1, bb - t + 1) / n, ink / (n * n)]);
}
const by = {};
for (const x of rows) (by[x[1]] = by[x[1]] || []).push(x);
const qq = (v, p) => v.length ? v.slice().sort((a, b) => a - b)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;
console.log(`剪影占可见区几成（同一块 256² 画布，α>8 的外接框；不剥任何背景）：共 ${rows.length} 个包`);
for (const [g, a] of Object.entries(by)) {
  const v = a.map((x) => x[2]);
  console.log(`  ${g.padEnd(4)} ${String(v.length).padStart(3)} 张  p10 ${qq(v, 0.1).toFixed(3)}  中位 ${qq(v, 0.5).toFixed(3)}  p90 ${qq(v, 0.9).toFixed(3)}  最狠 ${Math.min(...v).toFixed(3)}~${Math.max(...v).toFixed(3)}`);
}
const nat = (by['透传'] || []).map((x) => x[2]);
if (!nat.length) { console.log('!! 透传那组 0 张 —— 没有对岸，下面不构成结论'); process.exit(1); }
const lo = qq(nat, 0.1), hi = qq(nat, 0.9);
console.log(`\n带 = 透传组 p10~p90 = ${lo.toFixed(3)} ~ ${hi.toFixed(3)}。未适配（合成 + 老式 + 待查）里落在带外的：`);
const bad = [].concat(by['合成'] || [], by['老式'] || [], by['待查'] || [])
  .filter((x) => x[2] < lo || x[2] > hi).sort((x, y) => x[2] - y[2]);
if (!bad.length) console.log('    （0 张）');
for (const [pkg, g, ext, share] of bad) {
  console.log(`    ${ext.toFixed(3)}  ${pkg.padEnd(32)} ${g}   墨占画布 ${(share * 100).toFixed(1)}%`);
}
console.log(`\n⚠ 这把尺只说"多大"，不说"该不该这么大"：一个细线稿 logo 的剪影本来就小。` +
  `带外 = 值得看一眼，不等于要改算法。`);
