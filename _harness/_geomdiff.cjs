// 几何一份化（`IconGeom.kt`）的差分台。
//   旧 = 彩色先前那**两遍**：按 s1 重画一遍填满正方形 → 在重画后的格子上数占比 → 小占比再按重画后的半径缩。
//   新 = `IconGeom.planOf` **一步到位**：占比按 s1² 解析、半径在未缩放的格子上量（两步的 s1 正好约掉）。
// 逐张比【分档】与【总倍率】。这是 JS 镜像，不是真机产物；真机那一版另由装机 + logcat 的 `圆内缩`/`Mono: 跟彩色` 验。
// 用法：node _harness/_geomdiff.cjs [目录=_harness/corpus] [--edge=384] [--all]
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ALPHA_MIN = 8;
const INSCRIBED_SHARE = 0.785;
const CIRCLE_FIT = 0.88;
const SKIP = 0.01;

function decodePng(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === 'PLTE') plte = data;
    else if (kind === 'tRNS') trns = data;
    else if (kind === 'IDAT') idat.push(data);
    else if (kind === 'IEND') break;
    pos += len + 12;
  }
  if (!ihdr || ihdr.interlace) return null;
  const { w, h, color, depth } = ihdr;
  const chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!chans) return null;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * chans, out = Buffer.alloc(stride * h);
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
  const a = new Float64Array(w * h);
  let solid = 0;
  for (let i = 0, n = w * h; i < n; i++) {
    let v;
    if (color === 6) v = out[i * 4 + 3];
    else if (color === 4) v = out[i * chans + 1];
    else if (color === 3) { const idx = out[i]; v = trns && idx < trns.length ? trns[idx] : 255; }
    else v = 255;
    a[i] = v;
    if (v > ALPHA_MIN) solid++;
  }
  return { w, h, a, opaque: solid === w * h };
}

/** 把源图**拉满** edge×edge（彩色 drawIcon 那一步就是 setBounds(0,0,w,h)），只搬 alpha、双线性。 */
function stretch(img, edge) {
  const out = new Float64Array(edge * edge);
  const sx = img.w / edge, sy = img.h / edge;
  for (let y = 0; y < edge; y++) {
    const fy = Math.min(img.h - 1, Math.max(0, (y + 0.5) * sy - 0.5)), y0 = Math.floor(fy), wy = fy - y0;
    const y1 = Math.min(img.h - 1, y0 + 1);
    for (let x = 0; x < edge; x++) {
      const fx = Math.min(img.w - 1, Math.max(0, (x + 0.5) * sx - 0.5)), x0 = Math.floor(fx), wx = fx - x0;
      const x1 = Math.min(img.w - 1, x0 + 1);
      const p = img.a;
      out[y * edge + x] = (p[y0 * img.w + x0] * (1 - wx) + p[y0 * img.w + x1] * wx) * (1 - wy) +
        (p[y1 * img.w + x0] * (1 - wx) + p[y1 * img.w + x1] * wx) * wy;
    }
  }
  return out;
}

/** 以 (cx,cy) 为原点等比缩放 s、再把中心对到画布中心（双线性，与真机重画同一档近似）。 */
function scaleAbout(px, edge, s, cx, cy) {
  const out = new Float64Array(edge * edge);
  for (let y = 0; y < edge; y++) {
    const sy = (y - edge / 2) / s + cy, y0 = Math.floor(sy), wy = sy - y0;
    if (sy < -1 || sy > edge) continue;
    for (let x = 0; x < edge; x++) {
      const sxp = (x - edge / 2) / s + cx, x0 = Math.floor(sxp), wx = sxp - x0;
      if (sxp < -1 || sxp > edge) continue;
      const g = (xx, yy) => (xx < 0 || yy < 0 || xx >= edge || yy >= edge) ? 0 : px[yy * edge + xx];
      out[y * edge + x] = (g(x0, y0) * (1 - wx) + g(x0 + 1, y0) * wx) * (1 - wy) +
        (g(x0, y0 + 1) * (1 - wx) + g(x0 + 1, y0 + 1) * wx) * wy;
    }
  }
  return out;
}

function bboxOf(px, edge) {
  let l = edge, t = edge, r = -1, b = -1, vis = 0;
  for (let y = 0; y < edge; y++) for (let x = 0; x < edge; x++) {
    if (px[y * edge + x] <= ALPHA_MIN) continue;
    vis++;
    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return { l, t, r, b, vis };
}

function maxRadius(px, edge, bb, cx, cy) {
  let m2 = 0;
  for (let y = bb.t; y <= bb.b; y++) {
    const dy = y + 0.5 - cy;
    for (let x = bb.l; x <= bb.r; x++) {
      if (px[y * edge + x] <= ALPHA_MIN) continue;
      const dx = x + 0.5 - cx, d2 = dx * dx + dy * dy;
      if (d2 > m2) m2 = d2;
    }
  }
  return Math.sqrt(m2);
}

/** 旧：彩色先前那两遍（重画 → 数整张 → 按重画后的半径缩）。total = 落在原图上的**合成**倍率。 */
function oldPlan(px, edge) {
  let bb = bboxOf(px, edge);
  if (bb.r < 0) return null;
  const bw = bb.r - bb.l + 1, bh = bb.b - bb.t + 1;
  const s1 = Math.min(edge / bw, edge / bh);
  const scale1 = Math.abs(s1 - 1) < SKIP ? 1 : s1;
  let cur = px, drew = false;
  if (scale1 !== 1) { cur = scaleAbout(px, edge, scale1, (bb.l + bb.r + 1) / 2, (bb.t + bb.b + 1) / 2); drew = true; }
  let vis = 0;
  if (drew) vis = bboxOf(cur, edge).vis; else vis = bb.vis;
  const cover = vis / (edge * edge);
  const plate = cover < INSCRIBED_SHARE;
  let total = scale1;
  if (plate) {
    bb = bboxOf(cur, edge);
    const cx = (bb.l + bb.r + 1) / 2, cy = (bb.t + bb.b + 1) / 2;
    const maxR = maxRadius(cur, edge, bb, cx, cy);
    const need = (edge / 2) * CIRCLE_FIT;
    if (maxR > need) total = scale1 * (need / maxR);
  }
  return { plate, total, share: cover };
}

/** 新：`IconGeom.planOf` 的解析式（一遍都不重画）。 */
function newPlan(px, edge) {
  const bb = bboxOf(px, edge);
  if (bb.r < 0) return null;
  const bw = bb.r - bb.l + 1, bh = bb.b - bb.t + 1;
  const cx = (bb.l + bb.r + 1) / 2, cy = (bb.t + bb.b + 1) / 2;
  const s1 = Math.min(edge / bw, edge / bh);
  const fillScale = Math.abs(s1 - 1) < SKIP ? 1 : s1;
  const fill = bb.vis * fillScale * fillScale / (edge * edge);
  const plate = fill < INSCRIBED_SHARE;
  let total = fillScale;
  if (plate) {
    const maxR = maxRadius(px, edge, bb, cx, cy);
    const need = (edge / 2) * CIRCLE_FIT;
    if (maxR * fillScale > need) total = need / maxR;
  }
  return { plate, total, share: fill };
}

/** 旧·单色那边那份**抄本**（2eebf26 的 `fitLikeColor`）：占比按内容外接框归一、半径在未缩放的格子上量、
 *  且没有"已经进圆就不动"那道闸（倍率可以 >1，等于把稀疏图标又放大）。 */
function copyPlan(px, edge) {
  const bb = bboxOf(px, edge);
  if (bb.r < 0) return null;
  const bw = bb.r - bb.l + 1, bh = bb.b - bb.t + 1;
  const cx = (bb.l + bb.r + 1) / 2, cy = (bb.t + bb.b + 1) / 2;
  const fill = bb.vis / (bw * bh);
  const plate = fill < INSCRIBED_SHARE;
  let total;
  if (plate) total = ((edge / 2) * CIRCLE_FIT) / maxRadius(px, edge, bb, cx, cy);
  else total = Math.min(edge / bw, edge / bh);
  if (Math.abs(total - 1) < SKIP) total = 1;
  return { plate, total, share: fill };
}

const args = process.argv.slice(2);
const dir = (args.find((a) => !a.startsWith('--')) || path.join(__dirname, 'corpus'));
const edge = +((args.find((a) => a.startsWith('--edge=')) || '--edge=384').split('=')[1]);
const showAll = args.includes('--all');

const files = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.png'))
  .filter((f) => !/_(fg|bg|model|modelbg|mono|src|shot)\w*\.png$/i.test(f));
const rows = [];
let skipped = 0, opaque = 0;
for (const f of files) {
  let img = null;
  try { img = decodePng(fs.readFileSync(path.join(dir, f))); } catch (e) { img = null; }
  if (!img) { skipped++; continue; }
  if (img.opaque) opaque++;
  const px = stretch(img, edge);
  const o = oldPlan(px, edge), n = newPlan(px, edge), c = copyPlan(px, edge);
  if (!o || !n) { rows.push({ f, o: null, n: null, c: null }); continue; }
  rows.push({ f, o, n, c, d: n.total - o.total, flip: o.plate !== n.plate,
    dc: n.total - c.total, flipc: c.plate !== n.plate });
}
const done = rows.filter((r) => r.o && r.n && !r.syn);
const syn = rows.filter((r) => r.o && r.n && r.syn);
// 正对照（这台子要是恒 0 命中，得先证明它**能**命中）：按定义会翻面的形状，手写进画布。
function blank(edge, paint) {
  const px = new Float64Array(edge * edge);
  paint(px, edge);
  return px;
}
const shapes = {
  '正对照·横长条': (px, e) => { for (let y = 160; y < 224; y++) for (let x = 20; x < 364; x++) px[y * e + x] = 255; },
  '正对照·竖长条': (px, e) => { for (let y = 20; y < 364; y++) for (let x = 160; x < 224; x++) px[y * e + x] = 255; },
  '正对照·四角星(细线)': (px, e) => { for (let i = 0; i < e; i++) { px[8 * e + i] = 255; px[(e - 8) * e + i] = 255; px[i * e + 8] = 255; px[i * e + (e - 8)] = 255; } },
  '正对照·实心方块': (px, e) => { for (let y = 40; y < 344; y++) for (let x = 40; x < 344; x++) px[y * e + x] = 255; },
};
for (const [name, paint] of Object.entries(shapes)) {
  const px = blank(edge, paint);
  const o = oldPlan(px, edge), n = newPlan(px, edge), c = copyPlan(px, edge);
  syn.push({ f: name, o, n, c, d: n.total - o.total, flip: o.plate !== n.plate,
    dc: n.total - c.total, flipc: c.plate !== n.plate });
}
console.log('正对照（同一台子、按定义该有读数的形状）：');
for (const r of syn) {
  console.log(`  ${r.f.padEnd(20)} 彩色旧${r.o.plate ? '铺底' : '延伸'}×${r.o.total.toFixed(3)} 抄本${r.c.plate ? '铺底' : '延伸'}×${r.c.total.toFixed(3)} 新${r.n.plate ? '铺底' : '延伸'}×${r.n.total.toFixed(3)}` +
    `${r.flip ? ' ⚠新旧分档翻面' : ''}${r.flipc || Math.abs(r.dc) >= 0.01 ? ' ✅抓到抄本与彩色分家' : ''}`);
}
const plateN = done.filter((r) => r.n.plate).length;
const redrew = done.filter((r) => Math.abs(r.n.total - 1) >= 0.01).length;
console.log(`目录 ${dir}：清单 ${files.length}、解出 ${done.length}、解不出 ${skipped}、整张不透明 ${opaque}、edge=${edge}`);
console.log(`新算式（IconGeom）落点：铺底 ${plateN}、延伸 ${done.length - plateN}、真的重画 ${redrew}`);

function arm(name, key, flipKey) {
  const flips = done.filter((r) => r[flipKey]);
  const diffs = done.map((r) => Math.abs(r[key])).sort((a, b) => a - b);
  const moved = done.filter((r) => Math.abs(r[key]) >= 0.01);
  console.log(`${name}：分档翻面 ${flips.length} 张、总倍率差 中位 ${diffs[Math.floor(diffs.length / 2)].toFixed(4)} / 最大 ${diffs[diffs.length - 1].toFixed(4)}、差 ≥1% 的 ${moved.length} 张`);
  for (const r of flips.slice(0, 10)) {
    console.log(`  翻面 ${r.f.slice(0, 44).padEnd(44)} 彩${r.o.plate ? '铺底' : '延伸'}(${r.o.share.toFixed(3)}) 新${r.n.plate ? '铺底' : '延伸'}(${r.n.share.toFixed(3)})`);
  }
  const sorted = moved.slice().sort((a, b) => Math.abs(b[key]) - Math.abs(a[key]));
  for (const r of sorted.slice(0, showAll ? sorted.length : 10)) {
    console.log(`  ${r.f.slice(0, 44).padEnd(44)} ${key === 'd' ? '旧彩色' : '抄本'}${(key === 'd' ? r.o.total : r.c.total).toFixed(3)} → 新${r.n.total.toFixed(3)} (${r[key] >= 0 ? '+' : ''}${r[key].toFixed(3)})`);
  }
}
arm('新算式 vs 彩色旧两遍（应当逐张相同）', 'd', 'flip');
arm('新算式 vs 单色旧抄本（这就是"单色与彩色不符"的那笔账）', 'dc', 'flipc');

