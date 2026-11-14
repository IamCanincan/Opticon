// 一次性：屏上那一对（彩色 PNG + 单色 ALPHA_8）逐张量幅面比。
// 用法：node _harness/_pairshot.cjs <app_icons.db> [包名子串 …]
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');

function decodePng(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 2: 3, 6: 4 }[color];
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
function boxOf(get, n, thr) {
  let l = n, t = n, r = -1, b = -1, ink = 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!thr(get(y, x))) continue;
    ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  if (r < 0) return null;
  return { w: r - l + 1, h: b - t + 1, share: ink / (n * n) };
}

const args = process.argv.slice(2);
const dbp = args.shift();
const d = new DatabaseSync(dbp, { readOnly: true });
const rows = d.prepare('SELECT componentName c, label l, icon i, mono_icon m FROM icons').all();
let n = 0;
console.log('组件'.padEnd(46) + ' 彩色幅  单色幅  幅面比  彩色墨%  单色墨%');
for (const r of rows) {
  if (!r.i || !r.m) continue;
  if (args.length && !args.some((s) => r.c.includes(s))) continue;
  const p = decodePng(Buffer.from(r.i));
  if (!p) continue;
  const col = boxOf((y, x) => {
    const i = (y * p.w + x) * p.chans;
    return Math.min(p.out[i], p.out[i + 1], p.out[i + 2]);
  }, p.w, (v) => v < 235);
  const mb = Buffer.from(r.m), side = Math.round(Math.sqrt(mb.length));
  if (side * side !== mb.length) continue;
  const mono = boxOf((y, x) => mb[y * side + x], side, (v) => v > 8);
  if (!col || !mono) continue;
  const cw = col.w / p.w, mw = mono.w / side;
  console.log('  ' + (r.c + ' ' + (r.l || '')).slice(0, 44).padEnd(44) +
    (cw).toFixed(3) + '  ' + (mw).toFixed(3) + '  ' + (mw / cw).toFixed(3) + '   ' +
    (col.share * 100).toFixed(1).padStart(5) + '    ' + (mono.share * 100).toFixed(1).padStart(5));
  n++;
}
console.log('共', n, '对');
