// 换分母：库里那张彩色 blob 是**桌面裁过形状**的成品（四角一圈 AA 会被"非边框主色"当成墨），
// 所以判"剪影该多大"必须用**运行时源图**（`corpus_runtime` 里那张，带真 alpha）当分母。
// 用法：node _harness/_srcmeter.cjs <源图.png> <app_icons.db> <组件子串>
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const [srcP, dbp, needle] = process.argv.slice(2);

function decode(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'PLTE') plte = data; else if (kind === 'tRNS') trns = data;
    else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color, depth, interlace } = ihdr;
  const chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!chans || depth !== 8 || interlace) throw new Error(`不支持：colorType=${color} depth=${depth} interlace=${interlace}`);
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
  // → RGBA
  const rgba = Buffer.alloc(w * h * 4);
  for (let i = 0, j = 0; i < w * h; i++, j += chans) {
    let r, g, b, al = 255;
    if (color === 3) { const k = out[j]; r = plte[k * 3]; g = plte[k * 3 + 1]; b = plte[k * 3 + 2]; if (trns && k < trns.length) al = trns[k]; }
    else if (color === 0) { r = g = b = out[j]; }
    else if (color === 4) { r = g = b = out[j]; al = out[j + 1]; }
    else if (color === 2) { r = out[j]; g = out[j + 1]; b = out[j + 2]; }
    else { r = out[j]; g = out[j + 1]; b = out[j + 2]; al = out[j + 3]; }
    rgba[i * 4] = r; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = b; rgba[i * 4 + 3] = al;
  }
  return { w, h, rgba };
}
const fam = (r, g, b) => ((r / 24) | 0) * 625 + ((g / 24) | 0) * 25 + ((b / 24) | 0);
function extent(img, pred) {
  const { w, h, rgba } = img; let l = w, t = h, r = -1, b = -1, ink = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4;
    if (!pred(rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3], x, y)) continue;
    ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return { ext: r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / w, share: ink / (w * h) };
}
// 边框主色族（=盘/背景）：只从**内部**取边框（离边 12%，避开裁形 AA 那一圈）
function borderFam(img) {
  const { w, h, rgba } = img, m = Math.max(1, (w * 0.12) | 0), cnt = new Map();
  const bump = (x, y) => { const i = (y * w + x) * 4; const k = fam(rgba[i], rgba[i + 1], rgba[i + 2]); cnt.set(k, (cnt.get(k) || 0) + 1); };
  for (let x = m; x < w - m; x++) { bump(x, m); bump(x, m + 1); bump(x, h - m - 1); bump(x, h - m - 2); }
  for (let y = m; y < h - m; y++) { bump(m, y); bump(m + 1, y); bump(w - m - 1, y); bump(w - m - 2, y); }
  let best = null, bc = -1;
  for (const [k, c] of cnt) if (c > bc) { bc = c; best = k; }
  return best;
}
// —— 批量模式：`--batch=<源图目录> --db=<库>`，把上面那**同一份**定义跑遍所有源图 ——
//   为什么不给"批量"另写一遍：两份实现会漂（本仓第十五/十六闸同一课）。
const bdir = (process.argv.find((a) => a.startsWith('--batch=')) || '').slice(8);
if (bdir) {
  const dbArg = (process.argv.find((a) => a.startsWith('--db=')) || '').slice(5);
  const dd = new DatabaseSync(dbArg, { readOnly: true });
  const rowsAll = dd.prepare('SELECT componentName c, mono_icon m FROM icons').all().filter((r) => r.m);
  const monoOf = new Map();
  for (const r of rowsAll) { const p = r.c.split('/')[0]; if (!monoOf.has(p)) monoOf.set(p, Buffer.from(r.m)); }
  const files = fs.readdirSync(bdir).filter((f) => f.endsWith('.png') && !f.includes('modelbg') && !f.endsWith('_model.png'));
  const out = [];
  const evArg = (process.argv.find((a) => a.startsWith('--cls=')) || '').slice(6);
  const ev = new Map();
  if (evArg) for (const r of JSON.parse(fs.readFileSync(evArg, 'utf8'))) ev.set(r.pkg, r);
  const groupOf = (pkg) => {
    const e = ev.get(pkg);
    if (!e) return '待查';
    if (e.mono === '有') return '透传';
    return e.cls.includes('Adaptive') ? '合成' : e.cls === '要拆 APK' ? '待查' : '老式';
  };
  for (const f of files) {
    const pkg = f.split('_')[0];
    const mb = monoOf.get(pkg);
    if (!mb) continue;
    let img; try { img = decode(fs.readFileSync(bdir + '/' + f)); } catch { continue; }
    const side = Math.round(Math.sqrt(mb.length));
    if (side * side !== mb.length) continue;
    const bg = borderFam(img);
    const art = extent(img, (r, g, b, a) => a >= 64 && fam(r, g, b) !== bg);
    const mono = extent({ w: side, h: side, rgba: mb }, (r) => r > 8);
    if (!art.ext || !mono.ext) continue;
    out.push([pkg, mono.ext / art.ext, art.ext, mono.ext, art.share, groupOf(pkg)]);
  }
  const byGroup = {};
  for (const x of out) (byGroup[x[5]] = byGroup[x[5]] || []).push(x);
  const qq = (v, p) => v.length ? v.slice().sort((a, b) => a - b)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;
  console.log(`源图分母批量：${out.length} 张（分母 = 源图去掉边框主色后的图案幅，两边同一条 α≥64 / >8）`);
  for (const [g, a] of Object.entries(byGroup)) {
    const v = a.map((x) => x[1]);
    console.log(`  ${g.padEnd(4)} ${String(v.length).padStart(3)} 张  p05 ${qq(v, 0.05).toFixed(3)}  p10 ${qq(v, 0.1).toFixed(3)}  中位 ${qq(v, 0.5).toFixed(3)}  p90 ${qq(v, 0.9).toFixed(3)}  最狠 ${Math.min(...v).toFixed(3)}~${Math.max(...v).toFixed(3)}`);
  }
  // **界取透传那组的 p10**（主人明确：有单色层的那批别管，它们就是平台给的答案）
  const nat = (byGroup['透传'] || []).map((x) => x[1]).sort((a, b) => a - b);
  const lo = nat.length ? qq(nat, 0.1) : NaN, hi = 1.10;
  console.log(`\n界 = 透传组 p10 ${lo.toFixed(3)} ~ ${hi.toFixed(3)}；未适配（合成 + 老式）里带外的：`);
  const bad = (byGroup['合成'] || []).concat(byGroup['老式'] || []).filter((x) => x[1] < lo || x[1] > hi).sort((x, y) => x[1] - y[1]);
  if (!bad.length) console.log('    （0 张）');
  for (const [pkg, r, ae, me, share] of bad) {
    console.log(`    ${r.toFixed(3)}  ${pkg.padEnd(32)} 图案幅 ${ae.toFixed(3)}（占 ${(share * 100).toFixed(1)}%） 剪影幅 ${me.toFixed(3)}` +
      (share < 0.15 ? '   ⚠ 图案占画幅太小，这行比值不可信' : ''));
  }
  process.exit(0);
}

const src = decode(fs.readFileSync(srcP));const bg = borderFam(src);
const art = extent(src, (r, g, b, a, x, y) => a >= 64 && fam(r, g, b) !== bg);
const artAll = extent(src, (r, g, b, a) => a >= 64);
const d = new DatabaseSync(dbp, { readOnly: true });
const row = d.prepare('SELECT componentName c, mono_icon m FROM icons').all().find((r) => r.c.includes(needle) && r.m);
if (!row) { console.log('!! 库里没找到这一张'); process.exit(1); }
const mb = Buffer.from(row.m), side = Math.round(Math.sqrt(mb.length));
const mono = extent({ w: side, h: side, rgba: Buffer.from(mb) }, (r) => r > 8);
console.log(`源图 ${src.w}×${src.h}（带真 alpha）  库里单色 ${side}²`);
console.log(`  源·整张不透明幅 ${artAll.ext.toFixed(3)}（占 ${(artAll.share * 100).toFixed(1)}%）`);
console.log(`  源·去掉边框主色后 ${art.ext.toFixed(3)}（占 ${(art.share * 100).toFixed(1)}%）  ← 这才是"图案该有多大"的分母`);
console.log(`  我们交出的剪影幅   ${mono.ext.toFixed(3)}（占 ${(mono.share * 100).toFixed(1)}%）`);
console.log(`  剪影 ÷ 源图案 = ${(mono.ext / art.ext).toFixed(3)}    剪影 ÷ 源整张 = ${(mono.ext / artAll.ext).toFixed(3)}`);
console.log(`  ${row.c}`);
