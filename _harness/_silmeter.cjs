// 「剪影该多大」这把尺的对岸 = **桌面自己透传的那批取色图标**（应用自带的 monochrome 层，我们一个像素没碰）。
// 那批的比例就是平台给的答案：设计师画剪影时，墨占自己那张图案幅面的几成。
// 我们合成的图标只有落进这个带里才算"大小对"，落在带外才是病 —— 不用我拍数。
//
// 用法：node _harness/_silmeter.cjs <app_icons.db> <index.tsv> [包名子串…]
//   两边同一条定义：彩色那栏的"图案幅" = 去掉**边框主色**（2px 边框里最常见的那族）之后的墨外接框；
//   单色那栏 = α>8 的外接框。定义对两组一字不差，所以组间可比。
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');

function decode(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
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
  return { w, h, chans, out, alphaless: color === 2 || color === 0 };
}
function decodeMono(buf) {
  if (buf.length > 8 && buf[0] === 137) return decode(buf);
  const side = Math.round(Math.sqrt(buf.length));
  if (side * side !== buf.length) return null;
  return { w: side, h: side, raw: buf };
}
const fam = (r, g, b) => ((r / 24) | 0) * 625 + ((g / 24) | 0) * 25 + ((b / 24) | 0);
// 彩色那栏：先取边框主色（那族就是盘/背景），墨 = 与它不同族的格
function colorExtent(img) {
  const { w, h, chans, out } = img, cnt = new Map();
  const at = (x, y) => { const i = (y * w + x) * chans; return [out[i], out[i + 1], out[i + 2]]; };
  for (let x = 0; x < w; x++) for (const y of [0, 1, h - 2, h - 1]) { const k = fam(...at(x, y)); cnt.set(k, (cnt.get(k) || 0) + 1); }
  for (let y = 2; y < h - 2; y++) for (const x of [0, 1, w - 2, w - 1]) { const k = fam(...at(x, y)); cnt.set(k, (cnt.get(k) || 0) + 1); }
  let bg = null, best = -1;
  for (const [k, c] of cnt) if (c > best) { best = c; bg = k; }
  let l = w, t = h, r = -1, b = -1, ink = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (fam(...at(x, y)) === bg) continue;
    ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return r < 0 ? { share: 0, ext: 0 } : { share: ink / (w * h), ext: Math.max(r - l + 1, b - t + 1) / w };
}
function monoExtent(img) {
  if (img.raw) {
    const n = img.w; let l = n, t = n, r = -1, b = -1, ink = 0;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
      if (img.raw[y * n + x] <= 8) continue;
      ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
    }
    return r < 0 ? { share: 0, ext: 0 } : { share: ink / (n * n), ext: Math.max(r - l + 1, b - t + 1) / n };
  }
  const { w, h, chans, out } = img; let l = w, t = h, r = -1, b = -1, ink = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const a = out[(y * w + x) * chans + chans - 1];
    if (a <= 8) continue;
    ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return r < 0 ? { share: 0, ext: 0 } : { share: ink / (w * h), ext: Math.max(r - l + 1, b - t + 1) / w };
}
const q = (a, p) => { const s = a.slice().sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.round(p * (s.length - 1)))] : NaN; };

const args = process.argv.slice(2);
const dbp = args.shift(), idxp = args.shift();
const needles = args;
// index.tsv：col1=pkg，col6=cls，col9=mono（有 = 应用自带单色层，我们一个像素没碰）
const cls = new Map(), ownMono = new Set();
for (const l of fs.readFileSync(idxp, 'utf8').replace(/\r/g, '').split('\n').slice(1)) {
  const c = l.split('\t');
  if (c.length < 9) continue;
  cls.set(c[0], c[5]);
  if (c[8] === '有') ownMono.add(c[0]);
}
const d = new DatabaseSync(dbp, { readOnly: true });
const rows = d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all();
const groups = { 透传: [], 我们合成: [], 老式: [] };
const named = [];
// ⚠ **一个包在库里有很多行**（启动图标 + 快捷方式 + 桌面组件），blob 常常同一张，但组件名不同。
//   按"包"随机取行会把快捷方式那行冒充成启动图标 —— 现量：闲鱼随机行 0.466，
//   而它真正的启动图标那行是 **0.867**（差 0.4，足以把一张在带内的图标写进"要修"名单）。
//   取行规则钉死：**第二段以包名开头**（`pkg/pkg.xxx.MainActivity`）优先，其次第二段最短的那行；
//   解不出/空墨的整包**不判**（宁可少一条，不给假条目）。
const pick = new Map();
for (const r of rows) {
  if (!r.i || !r.m) continue;
  const pkg = r.c.split('/')[0], seg = r.c.split('/')[1] || '';
  const main = seg.startsWith(pkg) ? 0 : 1;
  const cur = pick.get(pkg);
  if (!cur || main < cur.main || (main === cur.main && seg.length < cur.seg.length)) pick.set(pkg, { r, main, seg });
}
let skipped = 0;
for (const [pkg, { r }] of pick) {
  const ci = decode(Buffer.from(r.i)), mi = decodeMono(Buffer.from(r.m));
  if (!ci || !mi) { skipped++; continue; }
  const ce = colorExtent(ci), me = monoExtent(mi);
  if (!ce.ext || !me.ext) { skipped++; continue; }
  const ratio = me.ext / ce.ext;
  const g = ownMono.has(pkg) ? '透传' : (cls.get(pkg) || '').includes('Adaptive') ? '我们合成' : '老式';
  groups[g].push([r.c, ratio, ce, me]);
  if (needles.some((n) => r.c.includes(n))) named.push([r.c, g, ratio]);
}
console.log('同一把尺（墨外接框 ÷ 去底图案幅），三组分开摊：');
console.log(`  库里 ${pick.size} 个包，取到启动图标那行并量出数的 ${[...Object.values(groups)].reduce((s, a) => s + a.length, 0)} 个，**没判的 ${skipped} 个**（解不出图 / 空墨）`);
for (const [g, a] of Object.entries(groups)) {
  const v = a.map((x) => x[1]);
  if (!v.length) { console.log(`  ${g.padEnd(6)} 0 张 —— 这一组没吃到样本，**不能读成"没问题"**`); continue; }
  console.log(`  ${g.padEnd(6)} ${String(v.length).padStart(3)} 张   p10 ${q(v, 0.1).toFixed(3)}  中位 ${q(v, 0.5).toFixed(3)}  p90 ${q(v, 0.9).toFixed(3)}  最狠 ${Math.min(...v).toFixed(3)}~${Math.max(...v).toFixed(3)}`);
  const bins = new Array(10).fill(0);
  for (const x of v) bins[Math.min(9, Math.floor(x * 10))]++;
  console.log('        分布 ' + bins.map((c, i) => `${(i / 10).toFixed(1)}-${((i + 1) / 10).toFixed(1)}:${c}`).join(' '));
}
if (named.length) {
  console.log('\n点名的这几张：');
  for (const [c, g, r] of named) console.log(`  ${c.slice(0, 52).padEnd(53)} ${g.padEnd(5)} 比 ${r.toFixed(3)}`);
}
// **带外名单**：以"透传那组"的 p10 与 1.10 当上下界 —— 上界是平台自己不会把剪影画得比图案还大
// 这条常识（最狠 1.011），下界直接取平台那组的 p10，不拍新数。
const nat = (groups['透传'] || []).map((x) => x[1]);
if (!nat.length) console.log('\n!! 透传那组 0 张 —— 没有对岸，下面这份名单不构成结论');
else {
  const lo = q(nat, 0.1), hi = 1.10;
  const out = [];
  for (const [g, a] of Object.entries(groups)) {
    if (g === '透传') continue;
    const byPkg = new Map();
    for (const [c, r, ce, me] of a) {
      const pkg = c.split('/')[0];
      if (byPkg.has(pkg)) continue;
      if (r < lo || r > hi) byPkg.set(pkg, [c, r, ce, me]);
    }
    for (const [, [c, r, ce, me]] of byPkg) out.push([g, c, r, ce, me]);
  }
  out.sort((x, y) => x[2] - y[2]);
  console.log(`\n带外（界 = 透传组 p10 ${lo.toFixed(3)} ~ 1.100）：${out.length} 个包`);
  for (const [g, c, r, ce, me] of out) {
    console.log(`  ${g.padEnd(5)} ${c.split('/')[0].padEnd(34)} 比 ${r.toFixed(3)}` +
      `   去底图案幅 ${ce.ext.toFixed(3)}（图案占画幅 ${(ce.share * 100).toFixed(1)}%）  剪影幅 ${me.ext.toFixed(3)}`);
  }
}
