// 单色 vs 彩色：**上屏那一张**的尺寸对照台。
//   读桌面 `app_icons.db` 的 `icons` 表：`mono_icon` 是 256² ALPHA_8（我们交付的那张剪影），
//   `icon` 是桌面存盘的彩色成品（⚠ 裁过形、可能不带 alpha —— 见 docs 里那条"圆周 AA 预乘发黑"）。
//   所以彩色那一栏不看库，看**真机日志**里彩色自己打的 `自适应几何 … fill= scale= 支=`；
//   这一台只回答一件事：**屏上这张单色的墨有多大面积、最大半径多少**，配 `--base=<旧库>` 比字节。
// 用法：node _harness/_monosize.cjs <app_icons.db> [--only=包名子串,…] [--base=<旧 db>]
const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');

const args = process.argv.slice(2);
const dbPath = args.find((a) => !a.startsWith('--'));
if (!dbPath) { console.log('用法：node _harness/_monosize.cjs <app_icons.db> [--only=a,b] [--base=<旧 db>]'); process.exit(2); }
const only = ((args.find((a) => a.startsWith('--only=')) || '').split('=')[1] || '').split(',').filter(Boolean);
const baseArg = (args.find((a) => a.startsWith('--base=')) || '').split('=')[1];

function load(p) {
  const db = new DatabaseSync(p, { readOnly: true });
  const rows = db.prepare('SELECT componentName c, label l, mono_icon m FROM icons').all();
  db.close();
  const map = new Map();
  for (const r of rows) map.set(r.c, { label: r.l, m: r.m == null ? null : Buffer.from(r.m) });
  return map;
}

/** ALPHA_8（256²，可能带 9 字节管线头）→ 墨的外接框、占比、最大半径/半边长。 */
function measure(buf) {
  if (!buf) return null;
  let a = buf, edge = 0;
  if (buf.length === 65536 + 9) { edge = buf.readUInt16LE(2); a = buf.subarray(9); }
  else if (buf.length === 65536) edge = 256;
  else return { bad: `字节数 ${buf.length}` };
  if (edge * edge !== a.length) return { bad: `头说 edge=${edge}，剩 ${a.length} 字节` };
  let l = edge, t = edge, r = -1, b = -1, ink = 0;
  for (let y = 0; y < edge; y++) for (let x = 0; x < edge; x++) {
    if (a[y * edge + x] <= 8) continue;
    ink++;
    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  if (r < 0) return { empty: true, edge };
  const bw = r - l + 1, bh = b - t + 1;
  const cx = (l + r + 1) / 2, cy = (t + b + 1) / 2;
  let maxR = 0;
  for (let y = t; y <= b; y++) for (let x = l; x <= r; x++) {
    if (a[y * edge + x] <= 8) continue;
    const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
    if (d > maxR) maxR = d;
  }
  return {
    edge, inkShare: ink / (edge * edge), boxShare: (bw * bh) / (edge * edge),
    box: `${bw}x${bh}`, radius: maxR / (edge / 2),
  };
}

const cur = load(dbPath);
const base = baseArg ? load(baseArg) : null;
const rows = [];
for (const [c, v] of cur) {
  const pkg = c.split('/')[0];
  if (only.length && !only.some((s) => c.includes(s) || pkg.includes(s))) continue;
  const m = measure(v.m);
  const b = base && base.get(c);
  const bm = b ? measure(b.m) : null;
  const same = b && v.m && b.m && Buffer.compare(v.m, b.m) === 0;
  rows.push({ c, label: v.label, m, bm, same, has: !!v.m });
}
rows.sort((x, y) => (y.m && y.m.inkShare || 0) - (x.m && x.m.inkShare || 0));
console.log(`库 ${dbPath}：命中 ${rows.length} 行` + (baseArg ? `（基线 ${baseArg}）` : ''));
console.log('  组件/label'.padEnd(58) + ' 有mono  墨占比   框占比   框      半径/半  ' + (baseArg ? '与基线同字节' : ''));
for (const r of rows) {
  const f = (v, k, d = 3) => (v && v[k] !== undefined ? v[k].toFixed(d) : '-');
  console.log('  ' + (r.c + ' ' + (r.label || '')).slice(0, 56).padEnd(58) +
    String(r.has ? '有' : '无').padStart(4) + '  ' +
    f(r.m, 'inkShare').padStart(6) + '   ' + f(r.m, 'boxShare').padStart(6) + '   ' +
    ((r.m && r.m.box) || (r.m && r.m.empty ? '全空' : '') || (r.m && r.m.bad) || '-').padEnd(9) + '  ' +
    f(r.m, 'radius').padStart(6) + '  ' +
    (baseArg ? String(r.has && r.bm ? (r.same ? '同' : '变了') : '-').padStart(6) : ''));
}
if (baseArg) {
  const both = rows.filter((r) => r.has && r.bm);
  console.log(`与基线：可比 ${both.length} 行、变了 ${both.filter((r) => !r.same).length} 行、逐字节相同 ${both.filter((r) => r.same).length} 行`);
}
