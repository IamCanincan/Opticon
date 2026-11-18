// 真机 pre.png 上的离线刀台：镜像 computeFill（正确合并数学）+ pad + composeOver，
// 先与真机 fill.png 逐格对账证镜像可信，再比各变体的条纹读数。
// 用法: node _prelab.cjs <pre.png> <fill.png> <render.png>
const fs = require('fs'), zlib = require('zlib');
function decode(p) {
  const b = fs.readFileSync(p);
  let off = 8, w = 0, h = 0, ct = 0, idat = [];
  while (off < b.length) {
    const len = b.readUInt32BE(off), type = b.toString('ascii', off + 4, off + 8);
    const d = b.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); ct = d[9]; }
    else if (type === 'IDAT') idat.push(d);
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const ch = ct === 6 ? 4 : ct === 2 ? 3 : 1;
  const stride = w * ch, out = Buffer.alloc(h * stride);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[pos++];
    for (let x = 0; x < stride; x++) {
      const v = raw[pos + x];
      const a = x >= ch ? out[y * stride + x - ch] : 0;
      const bb = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = (x >= ch && y > 0) ? out[(y - 1) * stride + x - ch] : 0;
      let r;
      if (f === 0) r = v;
      else if (f === 1) r = v + a;
      else if (f === 2) r = v + bb;
      else if (f === 3) r = v + ((a + bb) >> 1);
      else {
        const pp = a + bb - c, pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - c);
        r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? bb : c);
      }
      out[y * stride + x] = r & 255;
    }
    pos += stride;
  }
  const px = new Int32Array(w * h);
  for (let i = 0; i < w * h; i++) px[i] = (out[i * ch + (ch === 4 ? 3 : 0)] << 24) | (out[i * ch] << 16) | (out[i * ch + 1] << 8) | out[i * ch + 2];
  return { w, h, px };
}
const [preP, fillP, renderP] = process.argv.slice(2);
const pre = decode(preP), devFill = decode(fillP), devRender = decode(renderP);
const W = pre.w, H = pre.h, n = W * H;
function computeFill(px, blurPasses) {
  const A = i => px[i] >>> 24;
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < W; x++) { mark(x); mark((H - 1) * W + x); }
  for (let y = 0; y < H; y++) { mark(y * W); mark(y * W + W - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % W, y = (i / W) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < W) mark(i + 1);
    if (y > 0) mark(i - W); if (y + 1 < H) mark(i + W);
  }
  const soft = new Uint8Array(n);
  let sp2 = 0;
  for (let i = 0; i < n; i++) if (outside[i]) { soft[i] = 1; work[sp2++] = i; }
  while (sp2 > 0) {
    const i = work[--sp2], x = i % W, y = (i / W) | 0;
    const push = j => { if (!soft[j] && A(j) < 255) { soft[j] = 1; work[sp2++] = j; } };
    if (x > 0) push(i - 1); if (x + 1 < W) push(i + 1);
    if (y > 0) push(i - W); if (y + 1 < H) push(i + W);
  }
  const dist = new Int32Array(n).fill(-1), cnt = new Int32Array(n);
  const sR = new Int32Array(n), sG = new Int32Array(n), sB = new Int32Array(n);
  let head = 0, tail = 0;
  for (const threshold of [255, 160]) {
    for (let i = 0; i < n; i++) {
      if (A(i) < threshold) continue;
      const x = i % W, y = (i / W) | 0;
      let seed = false;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        if (!dx && !dy) continue;
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
        if (soft[ny * W + nx]) seed = true;
      }
      if (!seed) continue;
      dist[i] = 0; cnt[i] = 1;
      sR[i] = (px[i] >> 16) & 255; sG[i] = (px[i] >> 8) & 255; sB[i] = px[i] & 255;
      work[tail++] = i;
    }
    if (tail > 0) break;
    dist.fill(-1);
  }
  while (head < tail) {
    const i = work[head++], x = i % W, y = (i / W) | 0, d = dist[i] + 1;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
      const j = ny * W + nx;
      if (!soft[j]) continue;
      if (dist[j] < 0) {
        dist[j] = d; cnt[j] = cnt[i]; sR[j] = sR[i]; sG[j] = sG[i]; sB[j] = sB[i];
        work[tail++] = j;
      } else if (dist[j] === d) {
        cnt[j] += 1;
        sR[j] += ((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
        sG[j] += ((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
        sB[j] += ((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
      }
    }
  }
  const out = new Int32Array(n);
  for (let i = 0; i < n; i++) if (outside[i] && dist[i] >= 0) {
    out[i] = (255 << 24) | (((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 16 |
      (((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 8 | (((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0);
  }
  for (let p = 0; p < blurPasses; p++) {
    const snap = out.slice();
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = y * W + x;
      if (!outside[i] || !snap[i]) continue;
      let sr = 0, sg = 0, sb = 0, c = 0;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
        const j = ny * W + nx;
        if (!snap[j]) continue;
        sr += (snap[j] >> 16) & 255; sg += (snap[j] >> 8) & 255; sb += snap[j] & 255; c++;
      }
      out[i] = (255 << 24) | ((sr / c) | 0) << 16 | ((sg / c) | 0) << 8 | ((sb / c) | 0);
    }
  }
  let pass = 0, added = true;
  while (added && pass < 8) {
    added = false; pass++;
    const snap = out.slice();
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = y * W + x;
      if (out[i] !== 0) continue;
      if (A(i) >= 250) continue;
      let sr = 0, sg = 0, sb = 0, c = 0;
      if (x > 0 && snap[i - 1]) { sr += (snap[i - 1] >> 16) & 255; sg += (snap[i - 1] >> 8) & 255; sb += snap[i - 1] & 255; c++; }
      if (x + 1 < W && snap[i + 1]) { sr += (snap[i + 1] >> 16) & 255; sg += (snap[i + 1] >> 8) & 255; sb += snap[i + 1] & 255; c++; }
      if (y > 0 && snap[i - W]) { sr += (snap[i - W] >> 16) & 255; sg += (snap[i - W] >> 8) & 255; sb += snap[i - W] & 255; c++; }
      if (y + 1 < H && snap[i + W]) { sr += (snap[i + W] >> 16) & 255; sg += (snap[i + W] >> 8) & 255; sb += snap[i + W] & 255; c++; }
      if (c > 0) { out[i] = (255 << 24) | ((sr / c) | 0) << 16 | ((sg / c) | 0) << 8 | ((sb / c) | 0); added = true; }
    }
  }
  return out;
}
function compose(px, f, replaceEdge) {
  const out = px.slice();
  for (let i = 0; i < n; i++) {
    const a = px[i] >>> 24;
    if (a === 255) continue;
    const fv = f[i];
    if (a === 0) { if (fv !== 0) out[i] = fv; continue; }
    if (fv === 0) continue;
    if (replaceEdge) { out[i] = fv; continue; }
    const sr = (px[i] >> 16) & 255, sg = (px[i] >> 8) & 255, sb = px[i] & 255;
    const fr = (fv >> 16) & 255, fg = (fv >> 8) & 255, fb = fv & 255;
    out[i] = (255 << 24) | (((sr * a + fr * (255 - a)) / 255) | 0) << 16 | (((sg * a + fg * (255 - a)) / 255) | 0) << 8 | (((sb * a + fb * (255 - a)) / 255) | 0);
  }
  return out;
}
const ring = (px, r, sec) => {
  const vals = [];
  for (let deg = sec; deg < sec + 40; deg++) {
    const t = deg * Math.PI / 180;
    const x = Math.round(W / 2 + r * Math.cos(t)), y = Math.round(H / 2 + r * Math.sin(t));
    if (x < 0 || y < 0 || x >= W || y >= H) continue;
    vals.push((px[y * W + x] >> 16) & 255);
  }
  let mx = 0;
  for (let k = 1; k < vals.length; k++) mx = Math.max(mx, Math.abs(vals[k] - vals[k - 1]));
  return mx + ':' + (vals.length ? Math.max(...vals) - Math.min(...vals) : -1);
};
const stats = (tag, px) => console.log(tag + ' r150[上左/上右/下左/下右]=' + [210, 300, 120, 60].map(s => ring(px, 150, s)).join(' ') + ' r126=' + [210, 300, 120, 60].map(s => ring(px, 126, s)).join(' '));
// ① 镜像对账
const mir = computeFill(pre.px, 2);
let d = 0, mx = 0;
for (let i = 0; i < n; i++) { const dd = Math.abs(((mir[i] >> 16) & 255) - ((devFill.px[i] >> 16) & 255)); if (dd) d++; mx = Math.max(mx, dd); }
console.log('镜像fill vs 真机fill: 差异格=' + d + '/' + n + ' max=' + mx);
stats('真机pre ', pre.px);
stats('真机fill', devFill.px);
stats('镜像fill', mir);
stats('真机render', devRender.px);
// ② 变体
for (const bp of [4, 6]) {
  const f = computeFill(pre.px, bp);
  stats('镜像blur' + bp, f);
  stats('  合成后', compose(pre.px, f, false));
}
const f2 = computeFill(pre.px, 2);
stats('替换边合成', compose(pre.px, f2, true));