// **同一个图标、桌面上两张真张**之间的判据差 —— 这是"分档会不会随尺寸掉"的唯一硬账。
//
// 为什么不能拿 `SizeVarKt` 那个数当答案：语料存的就是 384，那台做的是"绕到 X 再落回 384"
// = **二次重采样**；而设备那两个 192/288 是**原生光栅化**。原生那一轴只有 MonoDump 带回来的
// `*-src.png` 里有，而它在盘上对同一个 drawable 给出了**不同 sig 的两张**（`_multisig.cjs` 数出 14 个）。
// 这一台就是拿那 14 个当 A/B —— 不重采样、不换算、不改代码。
//
// 用法：node _pairvar.cjs [MaskBench 汇总文件]
const fs = require('fs');
const FILE = process.argv[2] || '_srcdump_all.txt';
const F = ['分档', '底占', '淡', '框内', '色驱', '盘实', '零', '实', '调跨'];

const lines = fs.readFileSync(FILE, 'utf8').split('\n');
const rows = [];  // {sig, name, dir, f:{}}
let curDir = '?';
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  if (l.startsWith('### DIR ')) { curDir = l.slice(8).trim(); continue; }
  if (!l.startsWith('---')) continue;
  const body = lines.slice(i + 1, i + 3).join(' ');
  if (!body.includes('盘实=')) continue;
  const name = l.slice(3).trim().split(/\s+/)[0];
  const sig = (name.match(/[0-9a-f]{12}/) || [])[0];
  if (!sig) continue;
  const f = {};
  for (const k of F) { const m = body.match(new RegExp('(?:^| )' + k + '=([^ ]*)')); f[k] = m ? m[1] : '-'; }
  rows.push({ sig, name, dir: curDir, f });
}
if (rows.length === 0) { console.log('!! 一条判据行都没接到 —— 上游没跑'); process.exit(1); }

// 按"图标名（去 sig、去 iconN_、去 -src）"归组；**没有包名的键丢掉** ——
// 那些是标签解不出来的快捷方式，全都会被归成同一个空键（`__.png` 那组 6 个 sig 就是这么来的假对照）。
const keyOf = n => n.replace(/^icon\d+_/, '').replace(/[0-9a-f]{12}/g, '').replace(/-?src$/, '');
const groups = new Map();
let dropped = 0;
for (const r of rows) {
  const k = keyOf(r.name);
  if (!/[a-z]+\.[a-z]+/.test(k)) { dropped++; continue; }   // 键里必须还剩一个像包名的东西
  if (!groups.has(k)) groups.set(k, new Map());
  const g = groups.get(k);
  if (!g.has(r.sig)) g.set(r.sig, r);
}
const multi = [...groups].filter(([, g]) => g.size >= 2);
console.log(`== 真张对照：判据行 ${rows.length} 条 / 图标 ${groups.size} 个（丢掉无包名键 ${dropped} 条）==`);
console.log(`  **有 ≥2 张真不同的张的图标：${multi.length} 个**`);

const lv = s => { const m = (s || '').match(/^(\d+)\//); return m ? +m[1] : NaN; };
let same = 0, spreadN = 0, worst = 0;
for (const [k, g] of multi) {
  const list = [...g.values()];
  const levels = list.map(r => lv(r.f['分档']));
  const spread = Math.max(...levels) - Math.min(...levels);
  if (spread === 0) same++; else { spreadN++; worst = Math.max(worst, spread); }
  for (const r of list) {
    console.log(`  ${r.f['分档'].padEnd(20)} 底占=${r.f['底占']} 淡=${r.f['淡']} 框内=${r.f['框内']} 色驱=${r.f['色驱']} 盘实=${r.f['盘实']}  ${r.dir}/${r.name}`
      .replace(/icon\d+_/, '  '));
  }
  console.log(`  ── ${k.replace(/_[a-z0-9.]+$/i, '')}  档数跨度 ${spread}（${levels.join('/')}）` +
    (spread === 0 ? ' 同一档数\n' : ' **同图标两真张，层次不一样**\n'));
}
console.log(`== 结论：${multi.length} 个有多真张的图标里，**档数有跨度 ${spreadN} 个**（最大跨度 ${worst}）、完全一致的 ${same} 个 ==`);
