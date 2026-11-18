const fs = require('fs'), zlib = require('zlib');
function decode(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'PLTE') plte = data;
    else if (kind === 'tRNS') trns = data;
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
for (const f of process.argv.slice(2)) {
  const img = decode(fs.readFileSync(f));
  const { w, h, chans, out, color, plte, trns } = img;
  let a0 = 0, a255 = 0, mid = 0; const corners = [];
  const px = (x, y) => {
    const j = (y * w + x) * chans;
    if (color === 3) { const idx = out[j]; return [plte[idx*3], plte[idx*3+1], plte[idx*3+2], trns && idx < trns.length ? trns[idx] : 255]; }
    return chans === 4 ? [out[j], out[j+1], out[j+2], out[j+3]] : chans === 3 ? [out[j], out[j+1], out[j+2], 255] : [out[j], out[j], out[j], chans === 2 ? out[j+1] : 255];
  };
  for (let i = 0; i < w * h; i++) { const a = px(i % w, (i / w) | 0)[3]; if (a === 0) a0++; else if (a === 255) a255++; else mid++; }
  for (const [x, y] of [[0,0],[w-1,0],[0,h-1],[w-1,h-1],[w>>1,h>>1]]) corners.push(px(x, y).join(','));
  console.log(f, w + 'x' + h, 'alpha0=' + a0, 'alpha255=' + a255, 'mid=' + mid, 'corners+center=[' + corners.join(' | ') + ']');
}