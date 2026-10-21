// 量屏上那张图标：白底圆的直径 vs 里面图案（非白像素）的外接边长 —— "大了多少"要落成一个数。
// 用法：node "D:/.../_discmeasure.cjs" <png> <x,y,w,h>
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
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
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
const args = process.argv.slice(2);
const src = decodePng(fs.readFileSync(args[0]));
const [x0, y0, cw, ch] = args[1].split(",").map(Number);
const isWhite = (x, y) => { const i = ((y0 + y) * src.w + x0 + x) * 4; return src.px[i] > 246 && src.px[i + 1] > 246 && src.px[i + 2] > 246; };
// 白底圆 = 区域里**所有近白像素**的包围盒（页面底色是粉的、不是白；白圆里的白装饰也在圆内 → 不会撑大）
let L = cw, R = -1, T = ch, B = -1;
for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) {
  if (!isWhite(x, y)) continue;
  if (x < L) L = x; if (x > R) R = x; if (y < T) T = y; if (y > B) B = y;
}
const discD = Math.max(R - L + 1, B - T + 1), dcx = (L + R) / 2, dcy = (T + B) / 2;
// 图案：白圆内、退掉 5% 半径（避开圆边那圈柔和投影）之后的非白像素
const inset = discD * 0.05, rr = discD / 2 - inset;
let aL = cw, aR = -1, aT = ch, aB = -1, n = 0;
for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) {
  if (Math.hypot(x - dcx, y - dcy) > rr) continue;
  if (isWhite(x, y)) continue;
  n++;
  if (x < aL) aL = x; if (x > aR) aR = x; if (y < aT) aT = y; if (y > aB) aB = y;
}
const drawW = aR - aL + 1, drawH = aB - aT + 1;
console.log(`白底圆: x ${L}..${R}  y ${T}..${B}  直径 ${discD}`);
console.log(`图案(非白): x ${aL}..${aR}  y ${aT}..${aB}  ${drawW}x${drawH}  像素 ${n}`);
console.log(`图案 / 白圆直径: 宽 ${(drawW / discD * 100).toFixed(1)}%  高 ${(drawH / discD * 100).toFixed(1)}%  取大者 ${(Math.max(drawW, drawH) / discD * 100).toFixed(1)}%`);
console.log(`→ 图案外缘到白圆边的余量: 宽 ${((discD - drawW) / 2 / discD * 100).toFixed(1)}% 半径  高 ${((discD - drawH) / 2 / discD * 100).toFixed(1)}% 半径`);
console.log(`→ 图案中心相对圆心的偏移: dx ${(aL + drawW / 2 - dcx).toFixed(1)}  dy ${(aT + drawH / 2 - dcy).toFixed(1)}`);
