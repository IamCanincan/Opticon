// 沿 +x / -y 轴打印 (alpha,R) 剖面，用来 fitted 缩放比：源 round png vs 真机 render。
const fs = require('fs'), zlib = require('zlib');
function decode(p) {
  const b = fs.readFileSync(p);
  let off = 8, w = 0, h = 0, ct = 0, idat = [];
  while (off < b.length) {
    const len = b.readUInt32BE(off), type = b.toString('ascii', off + 4, off + 8);
    const d = b.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); ct = d[9]; }
    else if (type === 'IDAT') idat.push(d);
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const ch = ct === 6 ? 4 : ct === 2 ? 3 : 1;
  const stride = w * ch, out = Buffer.alloc(h * stride);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[pos++];
    for (let x = 0; x < stride; x++) {
      const v = raw[pos + x];
      const a = x >= ch ? out[y * stride + x - ch] : 0;
      const bb = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = (x >= ch && y > 0) ? out[(y - 1) * stride + x - ch] : 0;
      let r;
      if (f === 0) r = v;
      else if (f === 1) r = v + a;
      else if (f === 2) r = v + bb;
      else if (f === 3) r = v + ((a + bb) >> 1);
      else {
        const pp = a + bb - c, pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - c);
        r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? bb : c);
      }
      out[y * stride + x] = r & 255;
    }
    pos += stride;
  }
  return { w, h, ch, px: out };
}
for (const p of process.argv.slice(2)) {
  const { w, h, ch, px } = decode(p);
  const C = w >> 1;
  const at = (x, y) => { const i = (y * w + x) * ch; return ch === 4 ? px[i + 3] + ',' + px[i] : '255,' + px[i]; };
  const prof = [];
  for (let x = C; x < w; x++) prof.push(at(x, C));
  // 压缩跑长
  const runs = [];
  for (let k = 0; k < prof.length; k++) {
    const last = runs[runs.length - 1];
    if (last && last.key === prof[k]) last.n++;
    else runs.push({ key: prof[k], n: 1, x: C + k });
  }
  console.log(p.split('/').pop() + ' ' + w + 'x' + h + ' +x: ' + runs.map(r => r.n + 'x' + r.key + '@' + r.x).join(' '));
}