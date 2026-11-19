// 离线复现彩色「边角延伸」管线（CircleIconDrawable.renderInto 的 r3..r7），量交界处色差。
// 两臂：--mode=rowcol（旧：行/列边色平均，负对照）/ --mode=local（新：轮廓边界多源 BFS）。
// --mode=prod = 生产 computeFill 逐字镜像（含 soft/α255 种子/cnt 防溢出/盒抹/step5）；
// 叠 --slots = 设计留缝臂（unmarkSlots）。local/local2 是 11-18 前旧臂，只作历史负对照。
// ⚠ 直接给 png/目录时包名过滤只收**不存在的路径**参数（旧版把路径自己当过滤词 ⇒ 静默 0 张）。
// 用法：node _harness/_extfill.cjs [--mode=rowcol|local] [--size=256] [--zoom] [--out=目录]
//       (--geom=<geom.tsv> --index=<index.tsv> | <png 或目录…>) [包名子串…]
// 接缝读数：补边色 vs 相邻实色图标格的最大通道差（硬接缝 α≥250 / 软边 160..249），
// 以及补边内部相邻格差（轴向拖痕）。--zoom 出前 6 张整图+最坏接缝 4× 放大对照。
const fs = require('fs'), path = require('path'), zlib = require('zlib');
function decode(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  if (!chans) return null;
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  return { w, h, chans, out, color };
}
const ROOT = 'D:/Github Repositories/Opticon/_harness';
const args = process.argv.slice(2);
const S = +(args.find(a => a.startsWith('--size='))?.slice(7) || 256);
const ZOOM = args.includes('--zoom');
const MODE = args.find(a => a.startsWith('--mode='))?.slice(7) || 'rowcol';
const SLOTS = args.includes('--slots');
const OUT = args.find(a => a.startsWith('--out='))?.slice(6);
const needles = args.filter(a => !a.startsWith('--') && !fs.existsSync(a));

function rgba(img) {
  const { w, h, chans, out } = img, n = w * h, r = new Uint8ClampedArray(n * 4);
  for (let i = 0; i < n; i++) {
    const j = i * chans;
    if (chans >= 3) { r[i * 4] = out[j]; r[i * 4 + 1] = out[j + 1]; r[i * 4 + 2] = out[j + 2]; r[i * 4 + 3] = chans === 4 ? out[j + 3] : 255; }
    else { const v = out[j]; r[i * 4] = r[i * 4 + 1] = r[i * 4 + 2] = v; r[i * 4 + 3] = chans === 2 ? out[j + 1] : 255; }
  }
  return r;
}
// 双线性采样（边缘钳制），与 Android BitmapDrawable filter 同口径
function sample(src, sw, sh, x, y) {
  const x0 = Math.max(0, Math.min(sw - 1, Math.floor(x))), y0 = Math.max(0, Math.min(sh - 1, Math.floor(y)));
  const x1 = Math.min(sw - 1, x0 + 1), y1 = Math.min(sh - 1, y0 + 1);
  const fx = Math.max(0, Math.min(1, x - x0)), fy = Math.max(0, Math.min(1, y - y0));
  const o = [0, 0, 0, 0];
  for (let c = 0; c < 4; c++) {
    const a = src[(y0 * sw + x0) * 4 + c], b = src[(y0 * sw + x1) * 4 + c];
    const d = src[(y1 * sw + x0) * 4 + c], e = src[(y1 * sw + x1) * 4 + c];
    o[c] = Math.round((a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy);
  }
  return o;
}
function raster(src, sw, sh, W, H) {
  const out = new Uint8ClampedArray(W * H * 4);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const p = sample(src, sw, sh, (x + 0.5) * sw / W - 0.5, (y + 0.5) * sh / H - 0.5);
    out.set(p, (y * W + x) * 4);
  }
  return out;
}
const A = (px, i) => px[i * 4 + 3];
function planOf(px, w, h) {
  let l = w, t = h, r = -1, b = -1, vis = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (A(px, y * w + x) <= 8) continue;
    vis++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  if (r < 0) return null;
  const bw = r - l + 1, bh = b - t + 1, cx = (l + r + 1) / 2, cy = (t + b + 1) / 2;
  const s1 = Math.min(w / bw, h / bh);
  const fscale = Math.abs(s1 - 1) < 0.01 ? 1 : s1;
  const fill = vis * fscale * fscale / (w * h);
  return { scale: fscale, cx, cy, fill, plate: fill < 0.785 };
}
function redraw(px, w, h, plan) {
  const left = w / 2 - plan.cx * plan.scale, top = h / 2 - plan.cy * plan.scale;
  const out = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const sx = (x + 0.5 - left) / plan.scale - 0.5, sy = (y + 0.5 - top) / plan.scale - 0.5;
    if (sx < -0.5 || sy < -0.5 || sx > w - 0.5 || sy > h - 0.5) continue;
    out.set(sample(px, w, h, sx, sy), (y * w + x) * 4);
  }
  return out;
}
function bleedEdgeColor(px, w, h) {
  const n = w * h, snap = new Uint8ClampedArray(n * 4);
  for (let pass = 0; pass < 3; pass++) {
    snap.set(px);
    for (let i = 0; i < n; i++) {
      const a = snap[i * 4 + 3];
      if (a === 0 || a === 255) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, cnt = 0, best = -1;
      const take = j => {
        const al = snap[j * 4 + 3];
        if (al <= 8) return;
        if (al > best) { best = al; sr = snap[j * 4]; sg = snap[j * 4 + 1]; sb = snap[j * 4 + 2]; cnt = 1; }
        else if (al === best) { sr += snap[j * 4]; sg += snap[j * 4 + 1]; sb += snap[j * 4 + 2]; cnt++; }
      };
      if (x > 0) take(i - 1); if (x + 1 < w) take(i + 1);
      if (y > 0) take(i - w); if (y + 1 < h) take(i + w);
      if (cnt > 0) { px[i * 4] = Math.round(sr / cnt); px[i * 4 + 1] = Math.round(sg / cnt); px[i * 4 + 2] = Math.round(sb / cnt); }
    }
  }
}
const lum = (px, i) => (px[i * 4] * 299 + px[i * 4 + 1] * 587 + px[i * 4 + 2] * 114) / 1000 | 0;
function deFringe(px, w, h) {
  const src = px.slice(), n = w * h;
  const vis = i => src[i * 4 + 3] > 8;
  const rim = (i, inner) => {
    if (!vis(i)) return;
    const a = src[i * 4 + 3];
    if (a < 250) { px[i * 4 + 3] = 0; return; }
    if (lum(src, i) < 60 && lum(src, inner) - lum(src, i) > 40) px[i * 4 + 3] = 0;
  };
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let x0 = 0; while (x0 < w && !vis(row + x0)) x0++;
    if (x0 < w) for (let k = 0; k < 2; k++) { const x = x0 + k; if (x < w && vis(row + x)) rim(row + x, row + Math.min(x0 + 2, w - 1)); }
    let x1 = w - 1; while (x1 >= 0 && !vis(row + x1)) x1--;
    if (x1 >= 0) for (let k = 0; k < 2; k++) { const x = x1 - k; if (x >= 0 && vis(row + x)) rim(row + x, row + Math.max(x1 - 2, 0)); }
  }
  for (let x = 0; x < w; x++) {
    let y0 = 0; while (y0 < h && !vis(y0 * w + x)) y0++;
    if (y0 < h) for (let k = 0; k < 2; k++) { const y = y0 + k; if (y < h && vis(y * w + x)) rim(y * w + x, Math.min(y0 + 2, h - 1) * w + x); }
    let y1 = h - 1; while (y1 >= 0 && !vis(y1 * w + x)) y1--;
    if (y1 >= 0) for (let k = 0; k < 2; k++) { const y = y1 - k; if (y >= 0 && vis(y * w + x)) rim(y * w + x, Math.max(y1 - 2, 0) * w + x); }
  }
}
function computeFill(px, w, h) {
  const n = w * h, fill = new Int32Array(n);
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(px, i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < w; x++) { mark(x); mark((h - 1) * w + x); }
  for (let y = 0; y < h; y++) { mark(y * w); mark(y * w + w - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % w, y = (i / w) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < w) mark(i + 1);
    if (y > 0) mark(i - w); if (y + 1 < h) mark(i + w);
  }
  const rowLx = new Int32Array(h).fill(-1), rowRx = new Int32Array(h).fill(-1);
  const rowLc = new Int32Array(h), rowRc = new Int32Array(h);
  const colTy = new Int32Array(w).fill(-1), colBy = new Int32Array(w).fill(-1);
  const colTc = new Int32Array(w), colBc = new Int32Array(w);
  const getC = i => (px[i * 4] << 16) | (px[i * 4 + 1] << 8) | px[i * 4 + 2];
  for (let y = 0; y < h; y++) {
    let lx = -1, rx = -1;
    for (let x = 0; x < w; x++) if (A(px, y * w + x) >= 160) { if (lx < 0) lx = x; rx = x; }
    if (lx < 0) for (let x = 0; x < w; x++) if (A(px, y * w + x) > 8) { if (lx < 0) lx = x; rx = x; }
    if (lx >= 0) { rowLx[y] = lx; rowRx[y] = rx; rowLc[y] = getC(y * w + Math.min(lx + 1, rx)); rowRc[y] = getC(y * w + Math.max(rx - 1, lx)); }
  }
  for (let x = 0; x < w; x++) {
    let ty = -1, by = -1;
    for (let y = 0; y < h; y++) if (A(px, y * w + x) >= 160) { if (ty < 0) ty = y; by = y; }
    if (ty < 0) for (let y = 0; y < h; y++) if (A(px, y * w + x) > 8) { if (ty < 0) ty = y; by = y; }
    if (ty >= 0) { colTy[x] = ty; colBy[x] = by; colTc[x] = getC(Math.min(ty + 1, by) * w + x); colBc[x] = getC(Math.max(by - 1, ty) * w + x); }
  }
  const filled = new Uint8Array(n), color = new Int32Array(n);
  for (let i = 0; i < n; i++) {
    if (!outside[i]) continue;
    const x = i % w, y = (i / w) | 0;
    let sr = 0, sg = 0, sb = 0, cnt = 0;
    if (rowLx[y] >= 0) {
      const c = x <= rowLx[y] ? rowLc[y] : x >= rowRx[y] ? rowRc[y] : 0;
      if (c) { sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++; }
    }
    if (colTy[x] >= 0) {
      const c = y <= colTy[x] ? colTc[x] : y >= colBy[x] ? colBc[x] : 0;
      if (c) { sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++; }
    }
    if (cnt > 0) { color[i] = (Math.round(sr / cnt) << 16) | (Math.round(sg / cnt) << 8) | Math.round(sb / cnt); filled[i] = 1; }
  }
  let head = 0, tail = 0;
  for (let i = 0; i < n; i++) if (filled[i]) work[tail++] = i;
  const spread = (j, c) => { if (outside[j] && !filled[j]) { filled[j] = 1; color[j] = c; work[tail++] = j; } };
  while (head < tail) {
    const i = work[head++], c = color[i], x = i % w, y = (i / w) | 0;
    if (x > 0) spread(i - 1, c); if (x + 1 < w) spread(i + 1, c);
    if (y > 0) spread(i - w, c); if (y + 1 < h) spread(i + w, c);
    if (x > 0 && y > 0) spread(i - w - 1, c); if (x + 1 < w && y > 0) spread(i - w + 1, c);
    if (x > 0 && y + 1 < h) spread(i + w - 1, c); if (x + 1 < w && y + 1 < h) spread(i + w + 1, c);
  }
  for (let i = 0; i < n; i++) if (outside[i] && filled[i]) fill[i] = color[i] | 0xFF000000;
  const snap = new Int32Array(n);
  for (let pass = 0; pass < 2; pass++) {
    snap.set(fill);
    for (let i = 0; i < n; i++) {
      if (fill[i] !== 0 || A(px, i) >= 250) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, cnt = 0;
      const near = j => { const c = snap[j]; if (!c) return; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++; };
      if (x > 0) near(i - 1); if (x + 1 < w) near(i + 1);
      if (y > 0) near(i - w); if (y + 1 < h) near(i + w);
      if (cnt > 0) fill[i] = (0xFF000000 | (Math.round(sr / cnt) << 16) | (Math.round(sg / cnt) << 8) | Math.round(sb / cnt)) | 0;
    }
  }
  return { fill, outside };
}
// 原型改法：多源 BFS 从**真实轮廓边界**（α≥160 且八邻有 outside）出发，每个边界点带自己的颜色
// 往外拓；等距波前相遇按种子数加权平均（消掉行/列采样的轴向拖痕与局部错色）。
function computeFillLocal(px, w, h) {
  const n = w * h, fill = new Int32Array(n);
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(px, i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < w; x++) { mark(x); mark((h - 1) * w + x); }
  for (let y = 0; y < h; y++) { mark(y * w); mark(y * w + w - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % w, y = (i / w) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < w) mark(i + 1);
    if (y > 0) mark(i - w); if (y + 1 < h) mark(i + w);
  }
  const dist = new Int32Array(n).fill(-1);
  const sumR = new Int32Array(n), sumG = new Int32Array(n), sumB = new Int32Array(n), cnt = new Int32Array(n);
  let head = 0, tail = 0;
  const NB = [-1, 1, -w, w, -w - 1, -w + 1, w - 1, w + 1];
  for (let i = 0; i < n; i++) {
    if (A(px, i) < 160) continue;
    const x = i % w, y = (i / w) | 0;
    let seed = false;
    for (const d of NB) {
      const j = i + d;
      if (j < 0 || j >= n) continue;
      const jx = j % w, jy = (j / w) | 0;
      if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
      if (outside[j]) { seed = true; break; }
    }
    if (!seed) continue;
    dist[i] = 0; cnt[i] = 1;
    sumR[i] = px[i * 4]; sumG[i] = px[i * 4 + 1]; sumB[i] = px[i * 4 + 2];
    work[tail++] = i;
  }
  while (head < tail) {
    const i = work[head++], x = i % w, y = (i / w) | 0, d = dist[i] + 1;
    for (const off of NB) {
      const j = i + off;
      if (j < 0 || j >= n) continue;
      const jx = j % w, jy = (j / w) | 0;
      if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
      if (!outside[j]) continue;
      if (dist[j] < 0) {
        dist[j] = d; cnt[j] = cnt[i];
        sumR[j] = sumR[i]; sumG[j] = sumG[i]; sumB[j] = sumB[i];
        work[tail++] = j;
      } else if (dist[j] === d) {
        cnt[j] += cnt[i]; sumR[j] += sumR[i]; sumG[j] += sumG[i]; sumB[j] += sumB[i];
      }
    }
  }
  for (let i = 0; i < n; i++) {
    if (!outside[i] || dist[i] < 0) continue;
    fill[i] = (0xFF000000 | (Math.round(sumR[i] / cnt[i]) << 16) | (Math.round(sumG[i] / cnt[i]) << 8) | Math.round(sumB[i] / cnt[i])) | 0;
  }
  const snap = new Int32Array(n);
  for (let pass = 0; pass < 2; pass++) {
    snap.set(fill);
    for (let i = 0; i < n; i++) {
      if (fill[i] !== 0 || A(px, i) >= 250) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, c2 = 0;
      const near = j => { const c = snap[j]; if (!c) return; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; c2++; };
      if (x > 0) near(i - 1); if (x + 1 < w) near(i + 1);
      if (y > 0) near(i - w); if (y + 1 < h) near(i + w);
      if (c2 > 0) fill[i] = (0xFF000000 | (Math.round(sr / c2) << 16) | (Math.round(sg / c2) << 8) | Math.round(sb / c2)) | 0;
    }
  }
  return { fill, outside };
}
function computeFillLocal2(px, w, h) {
  const n = w * h, fill = new Int32Array(n);
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(px, i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < w; x++) { mark(x); mark((h - 1) * w + x); }
  for (let y = 0; y < h; y++) { mark(y * w); mark(y * w + w - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % w, y = (i / w) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < w) mark(i + 1);
    if (y > 0) mark(i - w); if (y + 1 < h) mark(i + w);
  }
  const dist = new Int32Array(n).fill(-1);
  const sumR = new Int32Array(n), sumG = new Int32Array(n), sumB = new Int32Array(n), cnt = new Int32Array(n);
  let head = 0, tail = 0;
  const NB = [-1, 1, -w, w, -w - 1, -w + 1, w - 1, w + 1];
  for (let i = 0; i < n; i++) {
    if (A(px, i) < 160) continue;
    const x = i % w, y = (i / w) | 0;
    let seed = false;
    for (const d of NB) {
      const j = i + d;
      if (j < 0 || j >= n) continue;
      const jx = j % w, jy = (j / w) | 0;
      if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
      if (A(px, j) < 160) { seed = true; break; }
    }
    if (!seed) continue;
    dist[i] = 0; cnt[i] = 1;
    sumR[i] = px[i * 4]; sumG[i] = px[i * 4 + 1]; sumB[i] = px[i * 4 + 2];
    work[tail++] = i;
  }
  while (head < tail) {
    const i = work[head++], x = i % w, y = (i / w) | 0, d = dist[i] + 1;
    for (const off of NB) {
      const j = i + off;
      if (j < 0 || j >= n) continue;
      const jx = j % w, jy = (j / w) | 0;
      if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
      if (A(px, j) >= 160) continue;
      if (dist[j] < 0) {
        dist[j] = d; cnt[j] = cnt[i];
        sumR[j] = sumR[i]; sumG[j] = sumG[i]; sumB[j] = sumB[i];
        work[tail++] = j;
      } else if (dist[j] === d) {
        cnt[j] += cnt[i]; sumR[j] += sumR[i]; sumG[j] += sumG[i]; sumB[j] += sumB[i];
      }
    }
  }
  for (let i = 0; i < n; i++) {
    if (!outside[i] || dist[i] < 0) continue;
    fill[i] = (0xFF000000 | (Math.round(sumR[i] / cnt[i]) << 16) | (Math.round(sumG[i] / cnt[i]) << 8) | Math.round(sumB[i] / cnt[i])) | 0;
  }
  const snap = new Int32Array(n);
  for (let pass = 0; pass < 2; pass++) {
    snap.set(fill);
    for (let i = 0; i < n; i++) {
      if (fill[i] !== 0 || A(px, i) >= 250) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, c2 = 0;
      const near = j => { const c = snap[j]; if (!c) return; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; c2++; };
      if (x > 0) near(i - 1); if (x + 1 < w) near(i + 1);
      if (y > 0) near(i - w); if (y + 1 < h) near(i + w);
      if (c2 > 0) fill[i] = (0xFF000000 | (Math.round(sr / c2) << 16) | (Math.round(sg / c2) << 8) | Math.round(sb / c2)) | 0;
    }
  }
  return { fill, outside };
}
// 设计留缝：上下（或左右）被实色夹住、且连续长度≥半边长的透明通道 = 图标自己的缝
// （outlook 查看日历那根横档），不是背景 ⇒ 从 outside 退标，不补色、留 α0 交镂空掩码。
// 通道厚度 = 到上/下实色距离之和（α≥160，与种子兜底同数）；厚度≤边长/8（384⇒48，
// 盖住放大后 27px 的横档）且连续长度≥半边长 = 设计缝。半厚口径会漏缝的首尾几行（D=24 时 26.7 厚的缝只退中间 17 行）。
function unmarkSlots(px, w, h, outside) {
  const n = w * h, D = Math.max(4, Math.round(w / 8)), BIG = 1 << 28;
  const a = new Int32Array(n), b = new Int32Array(n);
  const ink = i => px[i * 4 + 3] >= 160;
  for (let x = 0; x < w; x++) {
    let t = BIG;
    for (let y = 0; y < h; y++) { const i = y * w + x; a[i] = t; t = ink(i) ? 0 : t + 1; }
    t = BIG;
    for (let y = h - 1; y >= 0; y--) { const i = y * w + x; b[i] = t; t = ink(i) ? 0 : t + 1; }
  }
  let un = 0;
  const slot = new Uint8Array(n);
  const sandV = i => outside[i] === 1 && a[i] + b[i] <= D + 1;
  for (let y = 0; y < h; y++) {
    let x = 0;
    while (x < w) {
      if (!sandV(y * w + x)) { x++; continue; }
      let x2 = x; while (x2 < w && sandV(y * w + x2)) x2++;
      if (x2 - x >= w / 2) for (let xx = x; xx < x2; xx++) { outside[y * w + xx] = 2; slot[y * w + xx] = 1; un++; }
      x = x2;
    }
  }
  for (let y = 0; y < h; y++) {
    let t = BIG;
    for (let x = 0; x < w; x++) { const i = y * w + x; a[i] = t; t = ink(i) ? 0 : t + 1; }
    t = BIG;
    for (let x = w - 1; x >= 0; x--) { const i = y * w + x; b[i] = t; t = ink(i) ? 0 : t + 1; }
  }
  const sandH = i => outside[i] === 1 && a[i] + b[i] <= D + 1;
  for (let x = 0; x < w; x++) {
    let y = 0;
    while (y < h) {
      if (!sandH(y * w + x)) { y++; continue; }
      let y2 = y; while (y2 < h && sandH(y2 * w + x)) y2++;
      if (y2 - y >= h / 2) for (let yy = y; yy < y2; yy++) { outside[yy * w + x] = 2; slot[yy * w + x] = 1; un++; }
      y = y2;
    }
  }
  // 缝缘一圈 α 渐隐格也归 α0：它们不在 outside 里 ⇒ 补色场在它们是 0，composeOver
  // 会把半透明压在黑底上 = 缝上下两条黑细线（384 正对照看图抓到的）。缝是直边，抹渐隐无锯齿代价。
  // ⚠ 只抹**退标过的缝**的缘，不碰封闭圆点那族的渐隐（那族压在蓝底上是已验收的样子）。
  for (let i = 0; i < n; i++) {
    if (!slot[i]) continue;
    const x = i % w, y = (i / w) | 0;
    for (const j of [x > 0 ? i - 1 : -1, x + 1 < w ? i + 1 : -1, y > 0 ? i - w : -1, y + 1 < h ? i + w : -1]) {
      if (j < 0) continue;
      const al = A(px, j);
      if (al > 8 && al < 255) { px[j * 4 + 3] = 0; outside[j] = 2; }
    }
  }
  return un;
}
// 生产 computeFill 逐字移植（2026-11-19 版：soft 通行证 + α255 种子 + cnt 平均防溢出 + 盒抹两遍）。
// local/local2 是 11-18 之前的旧臂，只作历史负对照，别拿它们当生产镜像。
function computeFillProd(px, w, h, slots) {
  const n = w * h, fill = new Int32Array(n);
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(px, i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < w; x++) { mark(x); mark((h - 1) * w + x); }
  for (let y = 0; y < h; y++) { mark(y * w); mark(y * w + w - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % w, y = (i / w) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < w) mark(i + 1);
    if (y > 0) mark(i - w); if (y + 1 < h) mark(i + w);
  }
  const slotN = slots ? unmarkSlots(px, w, h, outside) : 0;
  const soft = new Uint8Array(n);
  let sp2 = 0;
  for (let i = 0; i < n; i++) if (outside[i] === 1) { soft[i] = 1; work[sp2++] = i; }
  while (sp2 > 0) {
    const i = work[--sp2], x = i % w, y = (i / w) | 0;
    const pass = j => { if (!soft[j] && A(px, j) < 255) { soft[j] = 1; work[sp2++] = j; } };
    if (x > 0) pass(i - 1); if (x + 1 < w) pass(i + 1);
    if (y > 0) pass(i - w); if (y + 1 < h) pass(i + w);
  }
  const dist = new Int32Array(n).fill(-1);
  const sumR = new Int32Array(n), sumG = new Int32Array(n), sumB = new Int32Array(n), cnt = new Int32Array(n);
  let head = 0, tail = 0;
  const NB = [-1, 1, -w, w, -w - 1, -w + 1, w - 1, w + 1];
  for (const threshold of [255, 160]) {
    for (let i = 0; i < n; i++) {
      if (A(px, i) < threshold) continue;
      const x = i % w, y = (i / w) | 0;
      let seed = false;
      for (const d of NB) {
        const j = i + d;
        if (j < 0 || j >= n) continue;
        const jx = j % w, jy = (j / w) | 0;
        if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
        if (soft[j]) { seed = true; break; }
      }
      if (!seed) continue;
      dist[i] = 0; cnt[i] = 1;
      sumR[i] = px[i * 4]; sumG[i] = px[i * 4 + 1]; sumB[i] = px[i * 4 + 2];
      work[tail++] = i;
    }
    if (tail > 0) break;
    dist.fill(-1);
  }
  while (head < tail) {
    const i = work[head++], x = i % w, y = (i / w) | 0, d = dist[i] + 1;
    for (const off of NB) {
      const j = i + off;
      if (j < 0 || j >= n) continue;
      const jx = j % w, jy = (j / w) | 0;
      if (Math.abs(jx - x) > 1 || Math.abs(jy - y) > 1) continue;
      if (!soft[j]) continue;
      if (dist[j] < 0) {
        dist[j] = d; cnt[j] = cnt[i];
        sumR[j] = sumR[i]; sumG[j] = sumG[i]; sumB[j] = sumB[i];
        work[tail++] = j;
      } else if (dist[j] === d) {
        cnt[j] += 1;
        sumR[j] += Math.trunc((sumR[i] + Math.trunc(cnt[i] / 2)) / cnt[i]);
        sumG[j] += Math.trunc((sumG[i] + Math.trunc(cnt[i] / 2)) / cnt[i]);
        sumB[j] += Math.trunc((sumB[i] + Math.trunc(cnt[i] / 2)) / cnt[i]);
      }
    }
  }
  for (let i = 0; i < n; i++) {
    if (outside[i] === 1 && dist[i] >= 0) {
      fill[i] = (0xFF000000 | (Math.trunc((sumR[i] + Math.trunc(cnt[i] / 2)) / cnt[i]) << 16) |
        (Math.trunc((sumG[i] + Math.trunc(cnt[i] / 2)) / cnt[i]) << 8) |
        Math.trunc((sumB[i] + Math.trunc(cnt[i] / 2)) / cnt[i])) | 0;
    }
  }
  const snap = new Int32Array(n);
  for (let pass = 0; pass < 2; pass++) {
    snap.set(fill);
    for (let i = 0; i < n; i++) {
      if (outside[i] !== 1 || snap[i] === 0) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, c2 = 0;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
        const c = snap[ny * w + nx];
        if (!c) continue;
        sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; c2++;
      }
      fill[i] = (0xFF000000 | (Math.trunc(sr / c2) << 16) | (Math.trunc(sg / c2) << 8) | Math.trunc(sb / c2)) | 0;
    }
  }
  // 5) 再往图标里垫（α<250 且 fill=0，4 邻有补色，垫到不再长、上限 8 遍）。
  //    ⚠ 缝格/缝缘（outside=2）跳过：不跳的话 step5 从缝口把横档逐环吃回去。
  let pass5 = 0, added5 = true;
  while (added5 && pass5 < 8) {
    added5 = false; pass5++;
    snap.set(fill);
    for (let i = 0; i < n; i++) {
      if (fill[i] !== 0 || A(px, i) >= 250 || outside[i] === 2) continue;
      const x = i % w, y = (i / w) | 0;
      let sr = 0, sg = 0, sb = 0, c2 = 0;
      const near = j => { const c = snap[j]; if (!c) return; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; c2++; };
      if (x > 0) near(i - 1); if (x + 1 < w) near(i + 1);
      if (y > 0) near(i - w); if (y + 1 < h) near(i + w);
      if (c2 > 0) { fill[i] = (0xFF000000 | (Math.trunc(sr / c2) << 16) | (Math.trunc(sg / c2) << 8) | Math.trunc(sb / c2)) | 0; added5 = true; }
    }
  }
  return { fill, outside, slots: slotN };
}
function composeOver(px, fill) {
  const out = px.slice();
  for (let i = 0; i < px.length / 4; i++) {
    const a = px[i * 4 + 3];
    if (a === 255) continue;
    const f = fill[i];
    if (a === 0) { if (f) { out[i * 4] = (f >> 16) & 255; out[i * 4 + 1] = (f >> 8) & 255; out[i * 4 + 2] = f & 255; out[i * 4 + 3] = 255; } continue; }
    if (!f) continue;
    const fr = (f >> 16) & 255, fg = (f >> 8) & 255, fb = f & 255;
    out[i * 4] = Math.round((px[i * 4] * a + fr * (255 - a)) / 255);
    out[i * 4 + 1] = Math.round((px[i * 4 + 1] * a + fg * (255 - a)) / 255);
    out[i * 4 + 2] = Math.round((px[i * 4 + 2] * a + fb * (255 - a)) / 255);
    out[i * 4 + 3] = 255;
  }
  return out;
}
function encPNG(W, H, rgbaBuf) {
  const chunk = (kind, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const k = Buffer.from(kind, 'latin1'); const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(Buffer.concat([k, data]))); return Buffer.concat([len, k, data, crc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6;
  const raw = Buffer.alloc((W * 4 + 1) * H);
  for (let y = 0; y < H; y++) { raw[y * (W * 4 + 1)] = 0; Buffer.from(rgbaBuf.buffer, rgbaBuf.byteOffset + y * W * 4, W * 4).copy(raw, y * (W * 4 + 1) + 1); }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const q = (v, p) => v.length ? v.slice().sort((a, b) => a - b)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;

// 名单两种给法：--geom/+--index（设备几何索引里 支=延伸 的那批，去语料找源图），或直接给 png/目录。
const geomArg = args.find(a => a.startsWith('--geom='))?.slice(7);
const indexArg = args.find(a => a.startsWith('--index='))?.slice(8);
const dirs = [path.join(ROOT, 'corpus_runtime'), path.join(ROOT, 'corpus')];
function findPkg(pkg, comp) {
  const seg = comp.split('/').pop();
  for (const d of dirs) {
    const hit = fs.readdirSync(d).filter(f => f.startsWith(pkg + '_' + seg + '_') && f.endsWith('.png'));
    if (hit.length) return path.join(d, hit[0]);
    const any = fs.readdirSync(d).filter(f => f.startsWith(pkg + '_') && f.endsWith('.png') && !f.includes('_modelbg'));
    if (any.length) return path.join(d, any[0]);
  }
  return null;
}
const jobs = [];
if (geomArg && indexArg) {
  const idx = new Map(fs.readFileSync(indexArg, 'utf8').trim().split('\n').map(l => [l.split('\t')[0], l]));
  for (const l of fs.readFileSync(geomArg, 'utf8').trim().split('\n')) {
    const [hex, , note] = l.split('\t');
    if (!note || !note.includes('支=延伸')) continue;
    const row = idx.get(hex);
    if (!row) continue;
    const comp = row.split('\t')[2].split(' ')[0];
    const pkg = comp.split('/')[0];
    const f = findPkg(pkg, comp);
    if (!f) { console.log('缺语料 ' + pkg); continue; }
    jobs.push({ pkg, file: f });
  }
} else {
  for (const a of args.filter(x => !x.startsWith('--') && fs.existsSync(x))) {
    const st = fs.statSync(a);
    const files = st.isDirectory()
      ? fs.readdirSync(a).filter(f => f.endsWith('.png') && !f.includes('preview') && !f.endsWith('_modelbg.png') && !f.endsWith('_winab.png') && !f.endsWith('_ab.png')).sort().map(f => path.join(a, f))
      : [a];
    for (const f of files) jobs.push({ pkg: path.basename(f).replace(/\.png$/, ''), file: f });
  }
}
const OUTD = OUT || (MODE === 'rowcol' ? 'D:/tmp/extout' : 'D:/tmp/extout_' + MODE);
fs.mkdirSync(OUTD, { recursive: true });
const rows = [];
for (const { pkg, file } of jobs) {
  if (needles.length && !needles.some(n => pkg.includes(n))) continue;
  const f = file;
  const img = decode(fs.readFileSync(f));
  if (!img) { console.log(`解不出 ${pkg}`); continue; }
  const src = rgba(img);
  let px = raster(src, img.w, img.h, S, S);
  const plan = planOf(px, S, S);
  if (!plan || plan.plate) { console.log(`非延伸 ${pkg} fill=${plan ? plan.fill.toFixed(2) : '-'}`); continue; }
  if (Math.abs(plan.scale - 1) >= 0.01) px = redraw(px, S, S, plan);
  bleedEdgeColor(px, S, S);
  deFringe(px, S, S);
  const cf = MODE === 'prod' ? computeFillProd(px, S, S, SLOTS) : MODE === 'local' ? computeFillLocal(px, S, S) : MODE === 'local2' ? computeFillLocal2(px, S, S) : computeFill(px, S, S);
  const { fill, outside } = cf;
  // 接缝：补边格 vs 相邻实色图标格（α≥250 硬台阶 / 160..249 软边）
  const hard = [], soft = [], band = [];
  let worst = { d: -1, i: -1 };
  for (let i = 0; i < S * S; i++) {
    if (!outside[i] || !fill[i]) continue;
    const x = i % S, y = (i / S) | 0;
    const fr = (fill[i] >> 16) & 255, fg = (fill[i] >> 8) & 255, fb = fill[i] & 255;
    for (const j of [x > 0 ? i - 1 : -1, x + 1 < S ? i + 1 : -1, y > 0 ? i - S : -1, y + 1 < S ? i + S : -1]) {
      if (j < 0) continue;
      if (outside[j]) {
        if (fill[j]) { const d = Math.max(Math.abs(fr - ((fill[j] >> 16) & 255)), Math.abs(fg - ((fill[j] >> 8) & 255)), Math.abs(fb - (fill[j] & 255))); band.push(d); }
        continue;
      }
      const a = A(px, j);
      if (a < 160) continue;
      const d = Math.max(Math.abs(fr - px[j * 4]), Math.abs(fg - px[j * 4 + 1]), Math.abs(fb - px[j * 4 + 2]));
      (a >= 250 ? hard : soft).push(d);
      if (d > worst.d) worst = { d, i, j };
    }
  }
  const composed = composeOver(px, fill);
  fs.writeFileSync(`${OUTD}/${pkg}.png`, encPNG(S, S, composed));
  rows.push({ pkg, hard, soft, band, worst, fill, outside, px, composed, slots: cf.slots || 0 });
}
rows.sort((a, b) => (q(b.hard, 0.9) || 0) - (q(a.hard, 0.9) || 0));
console.log(`延伸支 ${rows.length} 张 @${S}px（接缝=补边色 vs 相邻图标格色，最大通道差）`);
console.log('pkg                          硬接缝n  中位  p90  max  >30占比  软边p90  补边内带p90');
for (const r of rows) {
  const over = r.hard.filter(d => d > 30).length;
  console.log(`${r.pkg.padEnd(30)} ${String(r.hard.length).padStart(6)} ${q(r.hard, 0.5).toFixed(0).padStart(4)} ${q(r.hard, 0.9).toFixed(0).padStart(4)} ${q(r.hard, 1).toFixed(0).padStart(4)} ${(r.hard.length ? (over / r.hard.length * 100).toFixed(0) : '0').padStart(6)}% ${q(r.soft, 0.9).toFixed(0).padStart(7)} ${q(r.band, 0.9).toFixed(0).padStart(10)}${MODE === 'prod' ? ' 缝=' + String(r.slots).padStart(6) : ''}`);
}
if (MODE === 'prod') fs.writeFileSync('D:/tmp/ext_slots.tsv', rows.map(r => `${r.pkg}\t${r.slots}`).join('\n') + '\n');
fs.writeFileSync('D:/tmp/ext_rows.tsv', rows.map(r => `${r.pkg}\t${r.hard.length}\t${q(r.hard, 0.5).toFixed(1)}\t${q(r.hard, 0.9).toFixed(1)}\t${q(r.hard, 1).toFixed(1)}\t${q(r.soft, 0.9).toFixed(1)}\t${q(r.band, 0.9).toFixed(1)}\t${r.worst.i}`).join('\n') + '\n');
if (ZOOM) {
  // 前 6 张：整图 + 最坏接缝 64×64 放大 4 倍
  const top = rows.slice(0, 6), CELL = S, Z = 64, ZS = 4, PAD = 6, LBL = 22;
  const W = PAD + top.length * (CELL + PAD + Z * ZS + PAD), H = PAD + CELL + LBL + PAD;
  const sheet = new Uint8ClampedArray(W * H * 4).fill(24);
  const put = (buf, bw, bh, dx, dy, scale = 1) => {
    for (let y = 0; y < bh * scale; y++) for (let x = 0; x < bw * scale; x++) {
      const sx = (x / scale) | 0, sy = (y / scale) | 0;
      const o = ((dy + y) * W + dx + x) * 4, s = (sy * bw + sx) * 4;
      sheet[o] = buf[s]; sheet[o + 1] = buf[s + 1]; sheet[o + 2] = buf[s + 2]; sheet[o + 3] = 255;
    }
  };
  top.forEach((r, k) => {
    const cx = PAD + k * (CELL + PAD + Z * ZS + PAD);
    put(r.composed, S, S, cx, PAD);
    const wi = r.worst.i, wx = wi % S, wy = (wi / S) | 0;
    const x0 = Math.max(0, Math.min(S - Z, wx - Z / 2)), y0 = Math.max(0, Math.min(S - Z, wy - Z / 2));
    const crop = new Uint8ClampedArray(Z * Z * 4);
    for (let y = 0; y < Z; y++) for (let x = 0; x < Z; x++) {
      const s = ((y0 + y) * S + x0 + x) * 4, o = (y * Z + x) * 4;
      crop[o] = r.composed[s]; crop[o + 1] = r.composed[s + 1]; crop[o + 2] = r.composed[s + 2]; crop[o + 3] = 255;
    }
    put(crop, Z, Z, cx + CELL + PAD, PAD, ZS);
  });
  fs.writeFileSync('D:/tmp/ext_zoom_' + MODE + '.png', encPNG(W, H, sheet));
  console.log('zoom → D:/tmp/ext_zoom_' + MODE + '.png');
}