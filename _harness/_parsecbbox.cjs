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
// logo = 饱和彩色格（粉/蓝）：max-min 通道差 > 60
for (const f of process.argv.slice(2)) {
  const { w, h, px } = decode(fs.readFileSync(f));
  let x0 = w, y0 = h, x1 = -1, y1 = -1, n = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const [r, g, b, a] = px(x, y);
    if (a < 128) continue;
    if (Math.max(r, g, b) - Math.min(r, g, b) > 60) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  console.log(f.split('/').pop(), w + 'x' + h, 'logoBBox=' + [x0, y0, x1, y1].join(','),
    'fracW=' + ((x1 - x0 + 1) / w).toFixed(3), 'fracH=' + ((y1 - y0 + 1) / h).toFixed(3), 'n=' + n);
}