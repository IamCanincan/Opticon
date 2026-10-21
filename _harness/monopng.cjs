// 把桌面库里某一张的 mono_icon（256² ALPHA_8）导成 PNG，改前/改后各一张。
// 用法：node _harness/monopng.cjs <组件名前缀> <输出前缀>
//   读 D:/tmp/app_icons_before.db 与 D:/tmp/app_icons_after.db
// 配色：墨 = 黑、底 = 白（alpha 直接当灰度反向），2 倍最近邻放大便于看边。
const fs = require('fs');
const zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');

const prefix = process.argv[2] || 'me.mugzone.emiria';
const outPrefix = process.argv[3] || 'D:/tmp/malody';

function pick(dbPath) {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  const rows = db.prepare('SELECT componentName c, mono_icon m FROM icons WHERE componentName LIKE ?')
    .all(prefix + '/%');
  if (!rows.length) return null;
  const r = rows[0];
  if (r.m == null) return null;
  const b = Buffer.from(r.m);
  const off = b.length === 65544 ? 8 : 0;
  if (b.length - off !== 256 * 256) throw new Error('mono 长度 ' + b.length + '，不是 256² ALPHA_8');
  return { name: r.c, a: b.subarray(off) };
}

const T = [];
const crc32 = (buf) => {
  if (!T.length) for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; T[n] = c >>> 0; }
  let c = 0xffffffff;
  for (const b of buf) c = T[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (t, d) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(d.length);
  const td = Buffer.concat([Buffer.from(t, 'ascii'), d]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
};
function writePng(path, a, scale) {
  const S = 256 * scale;
  const raw = Buffer.alloc((S * 3 + 1) * S);
  for (let y = 0; y < S; y++) {
    const row = y * (S * 3 + 1);
    raw[row] = 0;
    const sy = Math.floor(y / scale);
    for (let x = 0; x < S; x++) {
      const v = 255 - a[sy * 256 + Math.floor(x / scale)];
      raw[row + 1 + x * 3] = v; raw[row + 2 + x * 3] = v; raw[row + 3 + x * 3] = v;
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4); ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(path, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
}

function stats(a) {
  let ink = 0, solid = 0;
  for (const v of a) { if (v > 0) ink++; if (v === 255) solid++; }
  const n = a.length;
  return `有墨 ${(ink / n * 100).toFixed(2)}%  全实 ${(solid / n * 100).toFixed(2)}%`;
}

for (const [tag, dbPath] of [['改前', 'D:/tmp/app_icons_before.db'], ['改后', 'D:/tmp/app_icons_after.db']]) {
  const r = pick(dbPath);
  if (!r) { console.log(tag + '：库里没有这一行'); continue; }
  const out = `${outPrefix}_${tag}.png`;
  writePng(out, r.a, 2);
  console.log(`${tag}  ${r.name}  ${stats(r.a)}  → ${out}`);
}
