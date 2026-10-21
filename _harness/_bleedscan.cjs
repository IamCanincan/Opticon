// 摊分布：按"外圈是不是近白 + 图案离画布中心的最远半径有没有超过内切半径的 0.95"
// 扫一遍语料，报告这条判据会碰到哪几张、都是谁。判据还没写进 Kotlin —— 先看名单再动手。
// 用法：node _bleedscan.cjs <目录...> [--ratio=0.95]
const fs = require("fs"), zlib = require("zlib"), path = require("path");

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
const RATIO = +(args.find(a => a.startsWith("--ratio="))?.slice(8) ?? 0.95);
const dirs = args.filter(a => !a.startsWith("--"));
const files = [];
for (const d of dirs) for (const e of fs.readdirSync(d)) if (/\.png$/i.test(e)) files.push(path.join(d, e));

const hit = [], near = [];
for (const f of files) {
  let s;
  try { s = decodePng(fs.readFileSync(f)); } catch (e) { continue; }
  const { w, h, px } = s;
  // 背景色：**四角**各取一块（12×12）的众数色 —— 不能用整圈：目标这一类（图案顶到画布边）
  // 恰恰会把外圈吃出图案色，整圈众数占比掉到 80% 上下，自己把自己挡在门外（2026-10-15 实测）。
  const B = 12, tally = new Map();
  const cxs = [0, w - B], cys = [0, h - B];
  for (const oy of cys) for (const ox of cxs) for (let y = oy; y < oy + B; y++) for (let x = ox; x < ox + B; x++) {
    const i = y * w + x, a = px[i * 4 + 3];
    const key = a < 8 ? "T" : (px[i * 4] >> 3) + "," + (px[i * 4 + 1] >> 3) + "," + (px[i * 4 + 2] >> 3);
    const c = tally.get(key) || { n: 0, r: px[i * 4], g: px[i * 4 + 1], b: px[i * 4 + 2], a };
    c.n++; tally.set(key, c);
  }
  const bg = [...tally.values()].sort((a, b) => b.n - a.n)[0];
  const total = B * B * 4;
  const ringWhite = bg.a >= 8 && bg.r > 235 && bg.g > 235 && bg.b > 235 && bg.n / total > 0.6;
  // 图案（与底色差得开）离画布中心的最远半径
  const cx = (w - 1) / 2, cy = (h - 1) / 2;
  let far = 0, n = 0, minx = w, maxx = -1, miny = h, maxy = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x, a = px[i * 4 + 3];
    let isArt = false;
    if (bg.a < 8) isArt = a >= 8;
    else if (a >= 8) isArt = Math.abs(px[i * 4] - bg.r) + Math.abs(px[i * 4 + 1] - bg.g) + Math.abs(px[i * 4 + 2] - bg.b) > 60;
    if (!isArt) continue;
    n++;
    if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
    const d = Math.hypot(x - cx, y - cy);
    if (d > far) far = d;
  }
  if (n === 0) continue;
  const R = Math.min(w, h) / 2;
  const rel = far / R;
  const row = { f: path.basename(f), w, h, bg: bg.a < 8 ? "透明" : `#${bg.r.toString(16).padStart(2, "0")}${bg.g.toString(16).padStart(2, "0")}${bg.b.toString(16).padStart(2, "0")}`, ring: (bg.n / total * 100).toFixed(0) + "%", box: `${maxx - minx + 1}x${maxy - miny + 1}`, far: rel };
  if (ringWhite && rel > RATIO) hit.push(row);
  else if (rel > RATIO) near.push(row);
}
const fmt = r => `${r.f.slice(0, 46).padEnd(48)} ${r.w}x${r.h} 底色${r.bg}(${r.ring}) 图案${r.box.padEnd(9)} 最远${(r.far * 100).toFixed(0)}%`;
console.log(`扫了 ${files.length} 张；判据 = 外圈近白(>90%) 且 图案最远离中心 > ${RATIO}×内切半径\n`);
console.log(`=== 会开火（${hit.length} 张）===`);
for (const r of hit.sort((a, b) => b.far - a.far)) console.log(fmt(r));
console.log(`\n=== 到了半径但底色不是白（不开火，${near.length} 张，只报数）===`);
for (const r of near.sort((a, b) => b.far - a.far).slice(0, 12)) console.log(fmt(r));
