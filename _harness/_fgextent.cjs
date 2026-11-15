// 量「前景层落在可见区里的幅面」——`_pullfg.sh` 的读数那一半。
// 用法：node _harness/_fgextent.cjs <包名> <前景层.png> [<app_icons.db>]
// ⚠ 为什么要"裁中间 2/3"再量：桌面画图层时按 `1+2f` 倍铺出去、只留中间那 2/3（`AdaptiveIconDrawable.draw()`），
//   所以**前景层自己那张画布上的像素不是屏上的东西**；我们交付的单色层也走同一刀。
//   两边都先裁到可见区，才是"同一个槽位的两层"在比 —— 这正是主人那句「一样的几何结构」的可算形式。
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const [pkg, png, dbp] = process.argv.slice(2);

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
  if (!chans || depth !== 8 || interlace) throw new Error(`不支持 depth=${depth} color=${color} interlace=${interlace}`);
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
  const A = new Int32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const j = i * chans;
    A[i] = color === 6 ? out[j + 3] : color === 4 ? out[j + 1] : color === 3 ? (trns ? trns[out[j]] : 255) : 255;
  }
  return { w, h, A };
}
/** 在 [x0,x1)×[y0,y1) 这块里量墨的外接框（α≥64），返回相对**这块**边长的比 */
function ext(img, x0, y0, x1, y1) {
  const { w, A } = img; let l = w, t = y1, r = -1, b = y0 - 1;
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    if (A[y * w + x] < 64) continue;
    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  const side = Math.max(1, Math.min(x1 - x0, y1 - y0));
  return r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / side;
}
let img; try { img = decode(fs.readFileSync(png)); } catch (e) { console.log(`  !! ${pkg} 读图失败：${e.message}`); process.exit(1); }
const full = ext(img, 0, 0, img.w, img.h);
// 可见区 = 中间 2/3（`f = (1-2/3)/2 = 1/6`，inset = 边/6）
const ins = Math.round(img.w / 6);
const vis = ext(img, ins, ins, img.w - ins, img.h - ins);
let ours = '-', ratio = '-';
if (dbp) {
  const d = new DatabaseSync(dbp, { readOnly: true });
  const mb = (() => {
    for (const r of d.prepare('SELECT componentName c, mono_icon m FROM icons').all()) {
      if (r.c.split('/')[0] === pkg && r.m) return Buffer.from(r.m);
    }
    return null;
  })();
  if (mb) {
    const n = Math.round(Math.sqrt(mb.length));
    if (n * n === mb.length) {
      let l = n, t = n, r = -1, b = -1;
      for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
        if (mb[y * n + x] <= 8) continue;
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
      }
      ours = r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / n;
      ratio = typeof ours === 'number' && vis ? (ours / vis).toFixed(3) : '-';
    }
  }
}
const line = `${pkg}\t位图\t${img.w}×${img.h}\t${vis.toFixed(3)}\t${full.toFixed(3)}\t单色 ${typeof ours === 'number' ? ours.toFixed(3) : ours} 比 ${ratio}`;
console.log('  ' + line);
fs.appendFileSync(`${__dirname}/bench/reg/fg_extents.tsv`, line + '\n');
