// 老式那 36 个包，换一根分母再问一遍：单色 ÷ **洪泛掉底之后的主体**（`_subj.cjs` 那把尺）。
//   为什么要换：`_fgpair.cjs` 第二条腿的"剥边框主色族"对**渐变底**（webapk 橙）和
//   **半透明投影底**（kodi 灰影）都剥不动 —— 现量 webapk 边框主色族只占环 38.1%（<50% 不开火），
//   kodi 剥了白底却留下灰影 ⇒ 两张**几何正确**的图标被冤枉（看图 2026-11-15）。
//   洪泛尺不问"哪个色族是底"，只问"从边框每步色差 ≤24 连得过去的那一整片" ⇒ 渐变/投影都连得过去。
// 用法：node _harness/_leg2flood.cjs <fgpair --tiers 输出> <_subj DUMP.tsv>
const fs = require('fs');
const [tiersP, tsvP] = process.argv.slice(2);
const legacy = fs.readFileSync(tiersP, 'utf8').split('\n')
  .filter((l) => /^\s+\d+\.\d%/.test(l)).map((l) => l.trim().split(/\s+/)[2]);
const tsv = new Map(fs.readFileSync(tsvP, 'utf8').trim().split('\n').map((l) => { const p = l.split('\t'); return [p[0], p]; }));
const rows = [];
for (const p of legacy) {
  const r = tsv.get(p);
  rows.push(r ? [p, +r[1], +r[2], +r[4], r[6]] : [p, NaN, NaN, NaN, '缺']);
}
rows.sort((a, b) => a[1] - b[1]);
console.log(`老式 ${legacy.length} 包 × 洪泛尺（单色 ÷ 洪泛后主体；DUMP=${tsvP}）：`);
for (const [p, v, se, me, c] of rows) {
  console.log(`  ${Number.isNaN(v) ? '  NaN' : v.toFixed(3)}  ${p.padEnd(42)} 主体幅 ${Number.isNaN(se) ? '-' : se.toFixed(3)}  单色幅 ${Number.isNaN(me) ? '-' : me.toFixed(3)}  ${c}`);
}
const off = rows.filter((r) => Number.isFinite(r[1]) && Math.abs(r[1] - 1) > 0.05);
console.log(`带外（|比-1|>0.05）${off.length} 个：` + off.map((r) => `${r[0]} ${r[1].toFixed(3)}`).join(', '));
const miss = rows.filter((r) => !Number.isFinite(r[1]));
if (miss.length) console.log(`!! 洪泛尺没覆盖 ${miss.length} 个：` + miss.map((r) => r[0]).join(', '));