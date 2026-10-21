// 把 dumpmono.cjs 导出的 .gray（256² ALPHA_8 = 桌面上屏那一张）导成 PNG 看。
// 用法：node _harness/gray2png.cjs <a.gray> [b.gray ...] [--out=目录] [--zoom=2]
// 配色：白底 + 墨=黑（alpha 直接当不透明度），最近邻放大 —— 目的是"屏上到底长什么样"，不是好看。
const fs = require('fs'), path = require('path'), zlib = require('zlib');

const args = process.argv.slice(2).filter(Boolean);
const outDir = (args.find(a => a.startsWith('--out=')) || '--out=D:/tmp/gray2png').slice(6);
const zoom = +((args.find(a => a.startsWith('--zoom=')) || '--zoom=2').split('=')[1]);
const files = args.filter(a => !a.startsWith('--'));
if (!files.length) { console.error('用法: node _harness/gray2png.cjs <a.gray> [...] [--out=] [--zoom=]'); process.exit(2); }
fs.mkdirSync(outDir, { recursive: true });

function png(w, h, rgb) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crcTable = png.c || (png.c = (() => {
      const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t;
    })());
    let c = 0xffffffff; for (const b of td) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    const crc = Buffer.alloc(4); crc.writeUInt32BE((c ^ 0xffffffff) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

for (const f of files) {
  const b = fs.readFileSync(f);
  const side = Math.round(Math.sqrt(b.length));
  if (side * side !== b.length) { console.log(`!! ${f} 不是方形灰度（${b.length} 字节）`); continue; }
  const w = side * zoom;
  const rgb = Buffer.alloc(w * w * 3);
  for (let y = 0; y < w; y++) {
    const sy = (y / zoom) | 0;
    for (let x = 0; x < w; x++) {
      const a = b[sy * side + ((x / zoom) | 0)];
      const v = 255 - a; // 白底黑墨
      const o = (y * w + x) * 3;
      rgb[o] = v; rgb[o + 1] = v; rgb[o + 2] = v;
    }
  }
  const out = path.join(outDir, path.basename(f).replace(/\.gray$/, '') + '.png');
  fs.writeFileSync(out, png(w, w, rgb));
  let ink = 0, solid = 0, mass = 0;
  for (const v of b) { if (v > 0) ink++; if (v >= 255) solid++; mass += v; }
  console.log(`${out}  ${side}²  有墨 ${(ink * 100 / b.length).toFixed(1)}% 全实 ${(solid * 100 / b.length).toFixed(1)}% 均α ${(mass / b.length).toFixed(1)}`);
}
