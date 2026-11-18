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
  const px = (x, y) => {
    const j = (y * w + x) * chans;
    if (color === 3) { const i = out[j]; return [plte[i*3], plte[i*3+1], plte[i*3+2], trns && i < trns.length ? trns[i] : 255]; }
    return chans === 4 ? [out[j], out[j+1], out[j+2], out[j+3]] : chans === 3 ? [out[j], out[j+1], out[j+2], 255] : [out[j], out[j], out[j], chans === 2 ? out[j+1] : 255];
  };
  return { w, h, px };
}
const A = decode(fs.readFileSync(process.argv[2])); // blob (RGB)
const B = decode(fs.readFileSync(process.argv[3])); // mark_in (RGBA 256)
let opMatch = 0, opDiff = 0, trN = 0, trSameAsDisc = 0; const diffs = [];
for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
  const a = A.px(x, y), b = B.px(x, y);
  if (b[3] === 0) { trN++; continue; }
  // opaque source: premultiplied-over-black expectation vs blob RGB
  const f = b[3] / 255;
  const er = Math.round(b[0] * f), eg = Math.round(b[1] * f), eb = Math.round(b[2] * f);
  const d = Math.max(Math.abs(a[0] - er), Math.abs(a[1] - eg), Math.abs(a[2] - eb));
  if (d <= 2) opMatch++; else { opDiff++; if (diffs.length < 12) diffs.push([x, y, a.slice(0, 3).join(','), b.join(','), d]); }
}
console.log('opaqueSrc match=' + opMatch, 'diff=' + opDiff, 'transparentSrc=' + trN);
console.log('sample diffs', JSON.stringify(diffs));
// what fraction of transparent-corner pixels are "garbage" (not disc/black/white)?
let g = 0; const gh = new Map();
for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
  if (B.px(x, y)[3] !== 0) continue;
  const a = A.px(x, y); const k = a.slice(0, 3).map(v => v.toString(16).padStart(2, '0')).join('');
  gh.set(k, (gh.get(k) || 0) + 1);
}
console.log('corner distinct=' + gh.size, 'top', [...gh.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, n]) => k + ':' + n).join(' '));