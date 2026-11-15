// 残膜归属探针：单色掩码里那些 α∈[9,199] 的"淡墨"格，在**彩色源**里到底是什么颜色？
//   若淡墨格的源色 == 整张主色（底）⇒ 淡墨是我们/模型凭空画的（源里没有这道边）；
//   若源色自成一块（与底差得开）⇒ 源里真有这道边，是我们把它留成了淡墨。
// 用法：node _harness/_remnant.cjs <app_icons.db> <包名> [--rows=<tsv>]
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const [dbp, pkg] = process.argv.slice(2);
function decode(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  if (!chans) return null;
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  return { w, h, chans, out };
}
const rowsFile = process.argv.find((a) => a.startsWith('--rows='))?.slice(7);
let want = null;
if (rowsFile) for (const l of fs.readFileSync(rowsFile, 'utf8').trim().split('\n')) { const [p, c] = l.split('\t'); if (p === pkg) want = c; }
const d = new DatabaseSync(dbp, { readOnly: true });
const all = d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all().filter((r) => r.c.split('/')[0] === pkg && r.i && r.m);
const row = (want && all.find((r) => r.c === want)) || all[0];
if (!row) { console.log('!! 库里没有 ' + pkg); process.exit(1); }
console.log(`行 = ${row.c}`);
const ci = decode(Buffer.from(row.i));
const mb = Buffer.from(row.m), n = Math.round(Math.sqrt(mb.length));
const rgb = (x, y) => { const i = (y * ci.w + x) * ci.chans; return ci.chans >= 3 && ci.chans !== 4 ? [ci.out[i], ci.out[i + 1], ci.out[i + 2]] : [ci.out[i], ci.out[i], ci.out[i]]; };
// 主色 = 整张出现最多的精确色
const cnt = new Map();
for (let y = 0; y < ci.h; y++) for (let x = 0; x < ci.w; x++) { const k = rgb(x, y).join(','); cnt.set(k, (cnt.get(k) || 0) + 1); }
let plate = null, pc = -1;
for (const [k, c] of cnt) if (c > pc) { pc = c; plate = k; }
console.log(`彩色主色 = rgb(${plate}) 占 ${(pc / (ci.w * ci.h) * 100).toFixed(1)}%`);
// 淡墨格（掩码 α 9..199）映射到彩色同位置（两者同尺寸？不同则按比例）
const fog = [];
for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
  const a = mb[y * n + x];
  if (a >= 9 && a <= 199) fog.push([x, y, a]);
}
const same = fog.filter(([x, y]) => rgb(Math.min(ci.w - 1, x * ci.w / n | 0), Math.min(ci.h - 1, y * ci.h / n | 0)).join(',') === plate);
console.log(`淡墨格 ${fog.length} 个；其中源色==主色的 ${same.length} 个（${(same.length / Math.max(1, fog.length) * 100).toFixed(1)}%）`);
const box = (list) => {
  if (!list.length) return null;
  let l = n, t = n, r = -1, b = -1;
  for (const [x, y] of list) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; }
  return [l / n, t / n, r / n, b / n].map((v) => v.toFixed(3)).join(',');
};
console.log(`淡墨格 bbox(归一) = ${box(fog)}；其中源色==主色那批 bbox = ${box(same)}`);
// 淡墨格里源色!=主色的那些，源色都是什么（前 6）
const other = new Map();
for (const [x, y] of fog) {
  const k = rgb(Math.min(ci.w - 1, x * ci.w / n | 0), Math.min(ci.h - 1, y * ci.h / n | 0)).join(',');
  if (k !== plate) other.set(k, (other.get(k) || 0) + 1);
}
console.log('淡墨格里非主色的源色 top6：' + [...other].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([k, c]) => `rgb(${k})×${c}`).join('  '));
// 实心墨（α≥200）的 bbox 作对照
const solid = [];
for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (mb[y * n + x] >= 200) solid.push([x, y]);
console.log(`实心墨 ${solid.length} 格 bbox = ${box(solid)}`);