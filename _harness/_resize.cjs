// 把一张 PNG 按面积平均缩到指定边长（模拟"桌面上这个 drawable 的 intrinsic 只有 105×105"）。
// 用法：node _harness/_resize.cjs <in.png> <out.png> <边长>
const fs = require('fs'), zlib = require('zlib');
const [, , inp, outp, sideS] = process.argv;
const side = +sideS;
if (!inp || !outp || !(side > 0)) { console.error('用法: node _harness/_resize.cjs <in> <out> <边长>'); process.exit(2); }

const buf = fs.readFileSync(inp);
let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0; const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8), data = buf.slice(pos + 8, pos + 8 + len);
  if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
  else if (type === 'IDAT') idat.push(data);
  else if (type === 'IEND') break;
  pos += 12 + len;
}
if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) throw new Error(`只支持 8bit RGB/RGBA，得到 depth=${bitDepth} color=${colorType}`);
const ch = colorType === 6 ? 4 : 3;
const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * ch, px = Buffer.alloc(h * stride);
let rp = 0;
for (let y = 0; y < h; y++) {
  const filt = raw[rp++], line = raw.slice(rp, rp + stride); rp += stride;
  const prev = y ? px.slice((y - 1) * stride, y * stride) : Buffer.alloc(stride);
  for (let x = 0; x < stride; x++) {
    const a = x >= ch ? px[y * stride + x - ch] : 0, b = prev[x], c = x >= ch ? prev[x - ch] : 0;
    let v;
    if (filt === 0) v = line[x];
    else if (filt === 1) v = line[x] + a;
    else if (filt === 2) v = line[x] + b;
    else if (filt === 3) v = line[x] + ((a + b) >> 1);
    else { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      v = line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c); }
    px[y * stride + x] = v & 0xff;
  }
}
// 面积平均（含 alpha 预乘，避免把透明像素的颜色算成黑的）
const out = Buffer.alloc(side * side * ch);
for (let oy = 0; oy < side; oy++) for (let ox = 0; ox < side; ox++) {
  const x0 = Math.floor(ox * w / side), x1 = Math.max(x0 + 1, Math.floor((ox + 1) * w / side));
  const y0 = Math.floor(oy * h / side), y1 = Math.max(y0 + 1, Math.floor((oy + 1) * h / side));
  let r = 0, g = 0, b = 0, al = 0, n = 0;
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    const i = (y * w + x) * ch, A = ch === 4 ? px[i + 3] : 255;
    r += px[i] * A; g += px[i + 1] * A; b += px[i + 2] * A; al += A; n++;
  }
  const A = Math.round(al / n), o = (oy * side + ox) * ch;
  if (A === 0) { out[o] = out[o + 1] = out[o + 2] = 0; if (ch === 4) out[o + 3] = 0; continue; }
  out[o] = Math.round(r / al); out[o + 1] = Math.round(g / al); out[o + 2] = Math.round(b / al);
  if (ch === 4) out[o + 3] = A;
}
const chunk = (type, data) => {
  const tbl = (() => { const t = []; for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[i] = c >>> 0; } return t; })();
  let c = 0xffffffff; for (const by of Buffer.concat([Buffer.from(type, 'ascii'), data])) c = tbl[(c ^ by) & 0xff] ^ (c >>> 8);
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE((c ^ 0xffffffff) >>> 0);
  return Buffer.concat([len, Buffer.from(type, 'ascii'), data, crc]);
};
const rawOut = Buffer.alloc((side * ch + 1) * side);
for (let y = 0; y < side; y++) { rawOut[y * (side * ch + 1)] = 0; out.copy(rawOut, y * (side * ch + 1) + 1, y * side * ch, (y + 1) * side * ch); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(side, 0); ihdr.writeUInt32BE(side, 4); ihdr[8] = 8; ihdr[9] = colorType;
fs.writeFileSync(outp, Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(rawOut)), chunk('IEND', Buffer.alloc(0)),
]));
console.log(`${w}x${h} → ${side}x${side}  ${outp}`);
