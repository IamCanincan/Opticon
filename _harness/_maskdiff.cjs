// 一次性：两版掩码 PNG 逐格比 alpha，并报各自的浓度分布。
// 用法：node _harness/_maskdiff.cjs <前.png> <后.png>
const fs = require('fs'), zlib = require('zlib');
function alpha(f) {
  const b = fs.readFileSync(f);
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= b.length) {
    const len = b.readUInt32BE(pos), k = b.toString('latin1', pos + 4, pos + 8), d = b.subarray(pos + 8, pos + 8 + len);
    if (k === 'IHDR') ihdr = { w: d.readUInt32BE(0), h: d.readUInt32BE(4), depth: d[8], color: d[9] };
    else if (k === 'IDAT') idat.push(d); else if (k === 'PLTE') plte = d; else if (k === 'tRNS') trns = d;
    else if (k === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color, depth } = ihdr, ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * ch, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? line[i - ch] : 0, bb = prev[i], c = i >= ch ? prev[i - ch] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = bb; else if (filt === 3) p = (a + bb) >> 1;
      else if (filt === 4) { const q = a + bb - c, pa = Math.abs(q - a), pb = Math.abs(q - bb), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? bb : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  const A = new Int32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const j = i * ch;
    A[i] = color === 6 ? out[j + 3] : color === 4 ? out[j + 1] : color === 3 ? (trns ? trns[out[j]] : 255) : 255;
  }
  return { w, h, A, n: w * h };
}
const dist = (x) => { const h = new Int32Array(256); for (const v of x.A) h[v]++; return [...h].map((c, i) => [i, c]).filter(([, c]) => c * 400 >= x.n); };
const [fa, fb] = process.argv.slice(2);
const a = alpha(fa), b = alpha(fb);
let diff = 0, maxd = 0, sumd = 0;
for (let i = 0; i < a.n; i++) { const d = Math.abs(a.A[i] - b.A[i]); if (d) { diff++; sumd += d; if (d > maxd) maxd = d; } }
console.log(`格子 ${a.n}（${a.w}×${a.h} vs ${b.w}×${b.h}）：不同的格 ${diff}（${(diff * 100 / a.n).toFixed(2)}%），最大差 ${maxd}，差的格上平均差 ${(diff ? sumd / diff : 0).toFixed(1)}`);
console.log('  前 每种至少占 0.25% 的浓度：', dist(a).map(([v, c]) => `${v}(${(c * 100 / a.n).toFixed(1)}%)`).join(' '));
console.log('  后 每种至少占 0.25% 的浓度：', dist(b).map(([v, c]) => `${v}(${(c * 100 / b.n).toFixed(1)}%)`).join(' '));
