const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
function decode(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'PLTE') plte = data; else if (kind === 'tRNS') trns = data;
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  if (!ihdr) return null;
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
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
  const px = (x, y) => {
    const j = (y * w + x) * chans;
    if (color === 3) { const i = out[j]; return [plte[i*3], plte[i*3+1], plte[i*3+2], trns && i < trns.length ? trns[i] : 255]; }
    return chans === 4 ? [out[j], out[j+1], out[j+2], out[j+3]] : chans === 3 ? [out[j], out[j+1], out[j+2], 255] : [out[j], out[j], out[j], chans === 2 ? out[j+1] : 255];
  };
  return { w, h, px };
}
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const rows = db.prepare('select componentName, icon from icons').all();
const classes = { plate: [], garbage: [], opaque: [], other: [] };
for (const r of rows) {
  if (!r.icon || r.icon.length < 100) continue;
  const d = decode(Buffer.from(r.icon));
  if (!d || d.w !== 256) { classes.other.push(r.componentName); continue; }
  let white = 0; const corner = new Set(); let yellow = 0;
  for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
    const dx = x - 127.5, dy = y - 127.5;
    const [R, G, B] = d.px(x, y);
    if (R > 245 && G > 245 && B > 245) white++;
    if (R > 200 && G > 200 && B < 100) yellow++;
    if (dx * dx + dy * dy > 120 * 120) corner.add((R << 16) | (G << 8) | B);
  }
  const name = r.componentName;
  if (white > 15000) classes.plate.push(name);
  else if (corner.size > 60 || yellow > 200) classes.garbage.push(name + ' [corner=' + corner.size + ' yellow=' + yellow + ' white=' + white + ']');
  else classes.opaque.push(name);
}
for (const k of Object.keys(classes)) {
  console.log('== ' + k + ' n=' + classes[k].length);
  for (const n of classes[k].slice(0, 60)) console.log('   ' + n);
}