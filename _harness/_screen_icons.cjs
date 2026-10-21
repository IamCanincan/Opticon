// 整屏找「白盘图标」并逐张量：白底圆直径、盘内图案外接、图案占盘几成。
// 为什么要自动找：手动给裁剪框量一张要试三次（我这一轮就在这上面绕了），而且框一偏
// 就把图标下面的应用名算进"图案"里。判据：白斑（三通道>246）的连通域，面积/外框 ≈ π/4 = 0.785
// 才是盘；图案被压在盘上会把白斑挖空，所以比值放宽到 0.45~0.88。
// 用法：node _screen_icons.cjs <screenshot.png> [--min=60]
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

const args = process.argv.slice(2);
const minSide = +(args.find(a => a.startsWith("--min="))?.slice(6) ?? 60);
const file = args.find(a => !a.startsWith("--"));
const { w, h, px } = decodePng(fs.readFileSync(file));
const W = (i) => px[i * 4] > 246 && px[i * 4 + 1] > 246 && px[i * 4 + 2] > 246;

const mask = new Uint8Array(w * h);
for (let i = 0; i < w * h; i++) mask[i] = W(i) ? 1 : 0;
const seen = new Uint8Array(w * h);
const rows = [];
const stack = new Int32Array(w * h);

for (let start = 0; start < w * h; start++) {
  if (!mask[start] || seen[start]) continue;
  let sp = 0; stack[sp++] = start; seen[start] = 1;
  let minx = w, maxx = -1, miny = h, maxy = -1, area = 0;
  while (sp > 0) {
    const p = stack[--sp]; const x = p % w, y = (p / w) | 0; area++;
    if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
    if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = 1; stack[sp++] = p - 1; }
    if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = 1; stack[sp++] = p + 1; }
    if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = 1; stack[sp++] = p - w; }
    if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = 1; stack[sp++] = p + w; }
  }
  const bw = maxx - minx + 1, bh = maxy - miny + 1;
  if (bw < minSide || bh < minSide) continue;
  if (Math.abs(bw - bh) > 0.08 * Math.max(bw, bh)) continue;
  const fill = area / (bw * bh);
  if (fill < 0.45 || fill > 0.88) continue;      // 盘 = π/4 ≈ 0.785；图案挖空会降到 0.45

  const cx = (minx + maxx) / 2, cy = (miny + maxy) / 2, d = Math.max(bw, bh);
  const rr = d / 2 - d * 0.05;
  let aL = bw, aR = -1, aT = bh, aB = -1;
  for (let y = miny; y <= maxy; y++) for (let x = minx; x <= maxx; x++) {
    const dx = x - cx, dy = y - cy;
    if (dx * dx + dy * dy > rr * rr) continue;
    const i = y * w + x;
    if (W(i)) continue;
    const lx = x - minx, ly = y - miny;
    if (lx < aL) aL = lx; if (lx > aR) aR = lx; if (ly < aT) aT = ly; if (ly > aB) aB = ly;
  }
  const iw = aR - aL + 1, ih = aB - aT + 1;
  rows.push({ x: minx, y: miny, d, fill, iw, ih, rw: iw / d, rh: ih / d });
}
rows.sort((a, b) => b.rw - a.rw);
console.log("x,y 是白盘外框左上角；盘径 d；盘内填充=白斑/外框(π/4≈0.79)；图案/盘 = 盘内非白的外接比");
for (const r of rows)
  console.log(`(${String(r.x).padStart(4)},${String(r.y).padStart(4)}) d=${String(r.d).padStart(3)} 填充${(r.fill * 100).toFixed(0)}%  图案/盘 宽${(r.rw * 100).toFixed(1)}% 高${(r.rh * 100).toFixed(1)}%`);
console.log(`\n共 ${rows.length} 张白盘`);
