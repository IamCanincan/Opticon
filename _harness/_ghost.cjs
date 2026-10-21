const fs = require('fs');
const path = require('path');
const H = __dirname;
// 扫判据台输出：找「交付里没有一个实心像素」与「实心极少」的那些 = 屏幕上的幽灵
const files = process.argv.slice(2);
const idx = fs.readFileSync(path.join(H, 'corpus_runtime', 'index.tsv'), 'utf8').trim().split(/\r?\n/);
const head = idx[0].split('\t');
const rows = idx.slice(1).map(l => { const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]); return o; });
const pkgs = [...new Set(rows.map(r => r.pkg))];
function chan(name) {
  let r = rows.find(x => x.file.replace(/\.png$/, '') === name);
  if (!r) { const p = pkgs.filter(p => name.includes(p)).sort((a, b) => b.length - a.length)[0]; r = p && rows.find(x => x.pkg === p); }
  if (!r) return '?';
  if (r.mono === '有') return '直通';
  return r.cls === 'AdaptiveIconDrawable' ? (r.bg === 'ColorDrawable' ? '甲' : '乙') : '丙';
}
for (const f of files) {
  const txt = fs.readFileSync(path.join(H, f), 'utf8').split(/\r?\n/);
  let cur = null; const out = [];
  for (const l of txt) {
    if (l.startsWith('--- ')) { cur = { n: l.slice(4).trim().split(/\s/)[0] }; continue; }
    if (!cur) continue;
    let m = l.match(/浓度\[0=(\d+) 淡1-127=(\d+) 中128-254=(\d+) 实255=(\d+)\]/);
    if (m) { cur.s0 = +m[1]; cur.soft = +m[2]; cur.mid = +m[3]; cur.solid = +m[4]; }
    m = l.match(/盘实=(\d+)\/(\d+)/); if (m) { cur.gN = +m[1]; cur.iN = +m[2]; }
    m = l.match(/框内=([0-9.]+)/); if (m) cur.fill = +m[1];
    m = l.match(/调=1/); if (m) cur.tonal = 1;
    m = l.match(/字均净=(-?\d+)/); if (m) cur.net = +m[1];
    if (cur.solid !== undefined && !cur.done) { cur.done = 1; out.push(cur); }
  }
  const ghost = out.filter(r => r.solid === 0);
  const near = out.filter(r => r.solid > 0 && r.solid * 100 < (r.solid + r.mid + r.soft) && r.solid * 100 < 2000);
  console.log(`\n##### ${f}: ${out.length} 行`);
  console.log(`** 交付里一个实心像素都没有（屏幕上就是一层灰）= ${ghost.length} 张`);
  for (const r of ghost) console.log(`   ${chan(r.n).padEnd(3)} ${r.tonal ? '满幅' : '参照'} 框内=${r.fill} 淡=${r.soft} 中=${r.mid} 盘实=${r.gN}/${r.iN}  ${r.n.slice(0, 52)}`);
  console.log(`** 实心不到 2000 格的（近ghost）= ${near.length} 张`);
  for (const r of near.slice(0, 12)) console.log(`   ${chan(r.n).padEnd(3)} ${r.tonal ? '满幅' : '参照'} 框内=${r.fill} 实=${r.solid} 中=${r.mid} 盘实=${r.gN}/${r.iN}  ${r.n.slice(0, 52)}`);
}
