// 把两张图里各自的「白底圆」抠出来，缩到同一个盘径、并排成一张 —— 回答
// 「同一个图标在桌面和应用信息页是不是同一个样」这种只能拿眼睛判的问题。
// 为什么必须归一化盘径：两处的画布尺寸本来就不同（284 vs 236），只有把盘拉齐了，
// 里面图案占几成才是可比的。
// 用法：node _sbs.cjs <pngA> <x,y,w,h> <pngB> <x,y,w,h> <out.png> [--panel=300]
const fs = require("fs"), zlib = require("zlib");

function decodePng(buf) {
  let pos = 8, idat = [], ihdr = null;
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString("latin1", pos + 4, pos + 8);
    const d = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === "IHDR") ihdr = { w: d.readUInt32BE(0), h: d.readUInt32BE(4), depth: d[8], color: d[9], interlace: d[12] };
    else if (kind === "IDAT") idat.push(d);
    else if (kind === "IEND") break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (f === 1) p = a; else if (f === 2) p = b; else if (f === 3) p = (a + b) >> 1;
      else if (f === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  const px = new Uint8Array(w * h * 4);
  for (let i = 0, n = w * h; i < n; i++) {
    const s = i * chans, t = i * 4;
    if (color === 6) { px[t] = out[s]; px[t + 1] = out[s + 1]; px[t + 2] = out[s + 2]; px[t + 3] = out[s + 3]; }
    else if (color === 4) { px[t] = px[t + 1] = px[t + 2] = out[s]; px[t + 3] = out[s + 1]; }
    else if (color === 0) { px[t] = px[t + 1] = px[t + 2] = out[s]; px[t + 3] = 255; }
    else if (color === 2) { px[t] = out[s]; px[t + 1] = out[s + 1]; px[t + 2] = out[s + 2]; px[t + 3] = 255; }
  }
  return { w, h, px };
}

const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return b => { let c = -1; for (const x of b) c = t[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }; })();
function encodePng(w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) px.copy ? px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4) : Buffer.from(px.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
  for (let y = 0; y < h; y++) raw[y * (w * 4 + 1)] = 0;
  const chunk = (kind, payload) => { const b = Buffer.concat([Buffer.from(kind, "latin1"), payload]); const o = Buffer.alloc(8 + payload.length + 4); o.writeUInt32BE(payload.length, 0); b.copy(o, 4); o.writeUInt32BE(CRC(b), 8 + payload.length); return o; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

// 在一个矩形里找「白底圆」：所有三通道都 >246 的像素的包围盒（与 _discmeasure 同一条门槛）
function findDisc(src, x0, y0, w, h) {
  const isW = (x, y) => { const i = ((y0 + y) * src.w + x0 + x) * 4; return src.px[i] > 246 && src.px[i + 1] > 246 && src.px[i + 2] > 246; };
  let L = w, R = -1, T = h, B = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { if (!isW(x, y)) continue; if (x < L) L = x; if (x > R) R = x; if (y < T) T = y; if (y > B) B = y; }
  if (R < 0) return null;
  return { cx: x0 + (L + R) / 2, cy: y0 + (T + B) / 2, d: Math.max(R - L + 1, B - T + 1) };
}

const args = process.argv.slice(2);
const panel = +(args.find(a => a.startsWith("--panel="))?.slice(8) ?? 300);
const positional = args.filter(a => !a.startsWith("--"));
const [fa, ra, fb, rb, outPath] = positional;
const A = decodePng(fs.readFileSync(fa)), B = decodePng(fs.readFileSync(fb));
const rectA = ra.split(",").map(Number), rectB = rb.split(",").map(Number);
const dA = findDisc(A, ...rectA), dB = findDisc(B, ...rectB);
if (!dA || !dB) { console.error("有一边没找到白底圆"); process.exit(2); }

// 两边都以盘心为中心、按 (盘径 × 1.06) 取正方形，再缩到 panel×panel（最近邻）
function panelOf(src, disc) {
  const side = disc.d * 1.06, x0 = disc.cx - side / 2, y0 = disc.cy - side / 2;
  const px = Buffer.alloc(panel * panel * 4);
  for (let y = 0; y < panel; y++) for (let x = 0; x < panel; x++) {
    const sx = Math.round(x0 + (x + .5) * side / panel), sy = Math.round(y0 + (y + .5) * side / panel);
    const t = (y * panel + x) * 4;
    if (sx < 0 || sy < 0 || sx >= src.w || sy >= src.h) { px[t] = px[t + 1] = px[t + 2] = 0; px[t + 3] = 255; continue; }
    const s = (sy * src.w + sx) * 4;
    px[t] = src.px[s]; px[t + 1] = src.px[s + 1]; px[t + 2] = src.px[s + 2]; px[t + 3] = 255;
  }
  return px;
}
const pa = panelOf(A, dA), pb = panelOf(B, dB);
const gap = 8, W = panel * 2 + gap * 3, H = panel + gap * 2;
const canvas = Buffer.alloc(W * H * 4);
for (let i = 0; i < W * H; i++) { canvas[i * 4] = 0x30; canvas[i * 4 + 1] = 0x28; canvas[i * 4 + 2] = 0x28; canvas[i * 4 + 3] = 255; }
const paste = (p, ox) => { for (let y = 0; y < panel; y++) Buffer.from(p.buffer, y * panel * 4, panel * 4).copy(canvas, ((y + gap) * W + ox) * 4); };
paste(pa, gap); paste(pb, gap * 2 + panel);
fs.writeFileSync(outPath, encodePng(W, H, canvas));
console.log(`盘径 A ${dA.d} → B ${dB.d}；两格都归一到 ${panel}px，盘占 ${(panel / 1.06).toFixed(0)}px。左=A 右=B`);
console.log(`写出 ${outPath} ${W}x${H}`);
