// 放射条纹读数 + 盒抹疗效：在真渲染的补色区（r>132）上量抹前后相邻差/极差。
const fs = require('fs'), zlib = require('zlib');
function decode(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'PLTE') plte = data; else if (kind === 'tRNS') trns = data;
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
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
  return { w, h, chans, out, color, plte, trns };
}
const img = decode(fs.readFileSync(process.argv[2]));
const { w, h, chans, out } = img;
const R = (x, y) => out[(y * w + x) * chans];
const mask = new Uint8Array(w * h);
for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
  if (Math.hypot(x - w / 2 + 0.5, y - h / 2 + 0.5) > 132) mask[y * w + x] = 1;
}
let cur = Int32Array.from({ length: w * h }, (_, i) => R(i % w, (i / w) | 0));
function stats(a, tag) {
  let maxAdj = 0, sumAdj = 0, nAdj = 0, lo = 255, hi = 0, at = '';
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    if (!mask[i]) continue;
    lo = Math.min(lo, a[i]); hi = Math.max(hi, a[i]);
    if (x + 1 < w && mask[i + 1]) { const d = Math.abs(a[i] - a[i + 1]); if (d > maxAdj) { maxAdj = d; at = x + ',' + y + ':' + a[i] + '/' + a[i + 1]; } sumAdj += d; nAdj++; }
    if (y + 1 < h && mask[i + w]) { const d = Math.abs(a[i] - a[i + w]); if (d > maxAdj) { maxAdj = d; at = x + ',' + y + ':' + a[i] + '/' + a[i + w]; } sumAdj += d; nAdj++; }
  }
  console.log(tag, '极差=' + (hi - lo), '相邻max=' + maxAdj + '@' + at, '相邻mean=' + (sumAdj / nAdj).toFixed(3));
}
stats(cur, '抹前');
for (let p = 0; p < 2; p++) {
  const snap = cur.slice();
  const next = cur.slice();
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    if (!mask[i]) continue;
    let s = 0, c = 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const j = ny * w + nx;
      if (!mask[j]) continue;
      s += snap[j]; c++;
    }
    next[i] = Math.round(s / c);
  }
  cur = next;
}
stats(cur, '抹后');