// 「单色和彩色一样的几何结构」这把尺的**最终形式**：同一个槽位的两层直接比 ——
//     比 = 单色层墨的外接框 ÷ 前景层墨的外接框   （两边都按装机口径落到可见区）
//   · 前景层 = 抓取里那 109 张 `_fg.png`（`CorpusCapture.dumpForeground`，运行时真正交给模型的那一层）
//   · 单色层 = `app_icons.db` 的 `mono_icon`（256² 可见区那张；透传那批就是应用自己画的 monochrome）
// 不再剥底色、不再用合成图当代理、不跟"平台该多大"猜 —— 前两次都栽在代理上（见 dde73d5 / 8105523）。
// 用法：node _harness/_fgpair.cjs <corpus 目录> <app_icons.db> <index.tsv> [--full]
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const [dir, dbp, idxp] = process.argv.slice(2);
const CROP = !process.argv.includes('--full');        // 默认可见区口径（桌面只留中间 2/3）

function decode(buf) {
  let pos = 8, ihdr = null, plte = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'PLTE') plte = data; else if (kind === 'tRNS') trns = data;
    else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color, depth, interlace } = ihdr, chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!chans || depth !== 8 || interlace) return null;
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
function extent(A, w, x0, y0, x1, y1, thr) {
  let l = w, t = y1, r = -1, b = y0 - 1;
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    if (!thr(A[y * w + x])) continue;
    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  if (r < 0) return 0;
  return Math.max(r - l + 1, b - t + 1) / Math.min(x1 - x0, y1 - y0);
}
const rows = new DatabaseSync(dbp, { readOnly: true }).prepare('SELECT componentName c, mono_icon m FROM icons').all();
const monoByPkg = new Map();
for (const r of rows) { const p = r.c.split('/')[0]; if (r.m && !monoByPkg.has(p)) monoByPkg.set(p, Buffer.from(r.m)); }
// ⚠ **不要按 `index.tsv` 里的 `fgFile` 文件名去配对**：那份台账描述的是 `corpus_runtime/`，
//   而 `corpus/` 是**另一代抓取**（现量：index 里 grok 的 sig 是 `a49c285ba9fc`，盘上那张是
//   `63085cf2d53b`，`corpus/` 里搜 `a49c285ba9fc` = **0 命中**）—— 按文件名配会**静默得到 0 张**，
//   而 0 张在这把尺上长得和"全都合格"一模一样。这里改成：**以盘上的 `_fg.png` 为准**，
//   键取包名（`<包名>_<资源名>_<sig>_fg.png` 的第一段），身份从 `_unadapted.cjs` 那份证据表读。
const ev = new Map();
try { for (const r of JSON.parse(fs.readFileSync(process.argv.find((a) => a.startsWith('--cls='))?.slice(6) || '_harness/../iconclass.json', 'utf8'))) ev.set(r.pkg, r); } catch { /* 没身份表就都算"我们合成"，下面会说明 */ }
const out = [];
for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('_fg.png')).sort()) {
  const pkg = f.split('_')[0];
  const img = decode(fs.readFileSync(`${dir}/${f}`));
  if (!img) continue;
  const ins = CROP ? Math.round(img.w / 6) : 0;
  const fg = extent(img.A, img.w, ins, ins, img.w - ins, img.h - ins, (a) => a >= 64);
  const mb = monoByPkg.get(pkg);
  if (!mb || !fg) continue;
  const n = Math.round(Math.sqrt(mb.length));
  if (n * n !== mb.length) continue;
  const A = new Int32Array(n * n);
  for (let i = 0; i < n * n; i++) A[i] = mb[i];
  const mo = extent(A, n, 0, 0, n, n, (a) => a > 8);
  if (!mo) continue;
  out.push([pkg, mo / fg, fg, mo, (ev.get(pkg) || {}).mono === '有']);
}
out.sort((a, b) => a[1] - b[1]);
const seenFg = fs.readdirSync(dir).filter((x) => x.endsWith('_fg.png')).length;
console.log(`盘上 _fg.png ${seenFg} 张 → 配上库里那张单色的 **${out.length} 张**（配不上 ${seenFg - out.length} 张）`);
if (!out.length) { console.log('!! 0 张配上 —— 这不是"全都合格"，是**这把尺没吃到数据**（包名对不上？库里没这些包？）'); process.exit(1); }
const q = (v, p) => v.length ? v.slice().sort((a, b) => a - b)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;
const own = out.filter((x) => x[4]).map((x) => x[1]), syn = out.filter((x) => !x[4]).map((x) => x[1]);
console.log(`单色层 ÷ 前景层（${CROP ? '可见区口径' : '整幅口径'}），共 ${out.length} 张`);
console.log(`  应用自带单色层（别管那批，它就是平台的答案）：${own.length} 张  p10 ${q(own, 0.1).toFixed(3)}  中位 ${q(own, 0.5).toFixed(3)}  p90 ${q(own, 0.9).toFixed(3)}`);
console.log(`  我们合成的：                                  ${syn.length} 张  p10 ${q(syn, 0.1).toFixed(3)}  中位 ${q(syn, 0.5).toFixed(3)}  p90 ${q(syn, 0.9).toFixed(3)}`);
const lo = q(own, 0.1), hi = q(own, 0.9);
console.log(`\n带 = 自带那组 p10~p90 = ${lo.toFixed(3)} ~ ${hi.toFixed(3)}。我们合成的里落在带外的：`);
const bad = out.filter((x) => !x[4] && (x[1] < lo || x[1] > hi));
if (!bad.length) console.log('    （0 张）');
for (const [pkg, r, fg, mo] of bad) console.log(`    ${r.toFixed(3)}  ${pkg.padEnd(34)} 前景层 ${fg.toFixed(3)}  单色 ${mo.toFixed(3)}`);
