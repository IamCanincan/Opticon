// 从一张截图里裁一块并放大 —— 判断"屏上那一小块到底长什么样"的眼睛。
// 用法：node "D:/.../_cropzoom.cjs" <png> <x,y,w,h> <倍数> [--out=路径]
const fs = require("fs"), zlib = require("zlib");

function decodePng(buf) {
  let pos = 8, idat = [], ihdr = null;
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString("latin1", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === "IHDR") ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === "IDAT") idat.push(data);
    else if (kind === "IEND") break;
    pos += len + 12;
  }
  if (ihdr.depth !== 8 || ihdr.interlace) throw new Error("unsupported");
  const { w, h, color } = ihdr;
  const chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  if (!chans) throw new Error("palette unsupported");
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  const px = Buffer.alloc(w * h * 4);
  for (let i = 0, n = w * h; i < n; i++) {
    const s = i * chans, d = i * 4;
    if (color === 6) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = out[s + 3]; }
    else if (color === 2) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = 255; }
    else if (color === 4) { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = out[s + 1]; }
    else { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = 255; }
  }
  return { w, h, px };
}
const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return b => { let c = -1; for (const x of b) c = t[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }; })();
function encodePng(w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (kind, pl) => { const b = Buffer.concat([Buffer.from(kind, "latin1"), pl]); const o = Buffer.alloc(8 + pl.length + 4); o.writeUInt32BE(pl.length, 0); b.copy(o, 4); o.writeUInt32BE(CRC(b), 8 + pl.length); return o; };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ih), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

const args = process.argv.slice(2);
const src = decodePng(fs.readFileSync(args[0]));
const [x0, y0, cw, ch] = args[1].split(",").map(Number);
const zoom = +args[2];
const outPath = args.find(a => a.startsWith("--out="))?.slice(6) ?? "D:/Github Repositories/Opticon/_harness/_cropzoom.png";
const W = cw * zoom, H = ch * zoom, dst = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const sx = Math.min(src.w - 1, x0 + Math.floor(x / zoom)), sy = Math.min(src.h - 1, y0 + Math.floor(y / zoom));
  src.px.copy(dst, (y * W + x) * 4, (sy * src.w + sx) * 4, (sy * src.w + sx) * 4 + 4);
}
fs.writeFileSync(outPath, encodePng(W, H, dst));
console.log(`写出 ${outPath} ${W}x${H}（源 ${src.w}x${src.h} 的 ${x0},${y0} ${cw}x${ch} ×${zoom}）`);
