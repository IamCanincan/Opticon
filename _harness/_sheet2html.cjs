// 把 `_shotcmp.cjs` 出的那张大表切成一格一格，**贴进对话**用的自包含 HTML（图片走 base64，不给路径）。
// 用法：node _harness/_sheet2html.cjs <大表.png> <标题> <行说明JSON文件> <输出.html>
//   行说明 JSON：[{"name":"…","ratio":"1.156","plate":"41.3","note":"该看什么"}, …]（顺序 = 大表从上到下）
const fs = require('fs'), zlib = require('zlib');
const [sheetP, title, rowsP, outP] = process.argv.slice(2);
const buf = fs.readFileSync(sheetP);
let pos = 8, ihdr = null; const idat = [];
while (pos + 8 <= buf.length) {
  const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
  if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9] };
  else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
  pos += len + 12;
}
const W = ihdr.w, H = ihdr.h;
const raw = zlib.inflateSync(Buffer.concat(idat)), stride = W * 4 + 1;
const px = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) {                        // 这张是我们自己写的：每行 filter=0，无需还原
  raw.copy(px, y * W * 4, y * stride + 1, y * stride + 1 + W * 4);
}
const CRC_T = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
function crc32(b) { let c = ~0; for (const x of b) c = CRC_T[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; }
function encode(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (k, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const body = Buffer.concat([Buffer.from(k, 'latin1'), d]); const t = Buffer.alloc(4); t.writeUInt32BE(crc32(body)); return Buffer.concat([l, body, t]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
const S = 236, GAP = 8;                             // 与 _shotcmp 同一套排版
const NP = +(process.env.NP || 3);                 // 大表每行几栏（_shotcmp 两联时传 2）
const rows = JSON.parse(fs.readFileSync(rowsP, 'utf8'));
const cell = (k, i, j) => {                          // k=第几行, i=第几栏（0=彩色 1=单色）, j=第几格像素
  const ox = GAP + i * (S + GAP), oy = GAP + k * (S + GAP);
  return px.slice((oy * W + ox) * 4, (oy * W + ox) * 4 + S * 4);
};
const b64 = (w, h, rgba) => 'data:image/png;base64,' + encode(w, h, rgba).toString('base64');
const cells = [];
for (let k = 0; k < rows.length; k++) {
  const a = Buffer.alloc(S * S * 4), b = Buffer.alloc(S * S * 4);
  const ox = GAP, oy = GAP + k * (S + GAP);
  for (let y = 0; y < S; y++) {
    px.copy(a, y * S * 4, ((oy + y) * W + ox) * 4, ((oy + y) * W + ox) * 4 + S * 4);
    px.copy(b, y * S * 4, ((oy + y) * W + ox + S + GAP) * 4, ((oy + y) * W + ox + S + GAP) * 4 + S * 4);
  }
  cells.push([b64(S, S, a), b64(S, S, b)]);
}
const html = `<div style="font:14px/1.5 system-ui,'Microsoft YaHei',sans-serif;color:#eaeaea;background:#111;padding:14px;border-radius:12px">
<h3 style="margin:0 0 4px">${title}</h3>
<p style="margin:0 0 12px;color:#9aa">${process.env.CAP || '左 = 桌面上那张彩色，右 = 我们交出去的单色（都取自 app_icons.db）。'}</p>
${rows.map((r, k) => `<div style="display:flex;gap:10px;align-items:center;padding:10px 0;border-top:1px solid #2a2a2e">
  <div style="flex:0 0 250px">
    <div style="font-weight:600">${r.name}</div>
    <div style="color:${r.warn ? '#ff9a6a' : '#8fd08f'}">比值 ${r.ratio}${r.plate ? ` （图案占画幅 ${r.plate}%）` : ''}</div>
    <div style="color:#9aa;font-size:12.5px">${r.note}</div>
  </div>
  <img src="${cells[k][0]}" width="150" height="150" style="border:1px solid #333;border-radius:8px;image-rendering:auto">
  <img src="${cells[k][1]}" width="150" height="150" style="border:1px solid #333;border-radius:8px;image-rendering:auto">
</div>`).join('\n')}
</div>`;
fs.writeFileSync(outP, html);
console.log(`写了 ${outP}（${rows.length} 行，图片内嵌 ${(html.length / 1024).toFixed(0)} KB）`);
