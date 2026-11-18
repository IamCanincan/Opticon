// 盘边环向读数：r 固定，角度扫一圈，看高光弧到底多宽多亮（决定抹窗要多大）。
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
  return { w, h, chans, out };
}
const img = decode(fs.readFileSync(process.argv[2]));
const { w, h, chans, out } = img;
const r = parseFloat(process.argv[3] || '122');
const runs = [];
for (let k = 0; k < 360; k++) {
  const a = k * Math.PI / 180;
  const x = Math.round(w / 2 - 0.5 + r * Math.cos(a)), y = Math.round(h / 2 - 0.5 + r * Math.sin(a));
  if (x < 0 || y < 0 || x >= w || y >= h) continue;
  const v = out[(y * w + x) * chans];
  if (runs.length && runs[runs.length - 1].v === v) runs[runs.length - 1].n++;
  else runs.push({ v, n: 1, at: k });
}
console.log('r=' + r, runs.map(q => q.v + 'x' + q.n + '@' + q.at).join(' '));