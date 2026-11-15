// 「同一个图案，单色画得比彩色大还是小」——主人看图之后要的那把尺。
//   之前所有版本都比的是"单色 ÷ 彩色整张"，而彩色常常是**一整块底 + 图案** ⇒ 那个比值
//   量的是"图案占自己不画底时的几成"，跟几何对不对没关系（webapk 0.438 就是这么被误读的）。
//   这一把只比**同一个主体**：彩色那边把"从边框连得过去、每步色差 ≤T"的那一整片当背景洪泛掉，
//   剩下的是主体；单色那边 α>8 就是主体。两边各取外接框。
// 用法：node _harness/_subj.cjs <app_icons.db> [包名子串…] [--tol=24]
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const args = process.argv.slice(2);
const dbp = args.shift();
const T = +(args.find((a) => a.startsWith('--tol='))?.slice(6) || 24);
const needles = args.filter((a) => !a.startsWith('--'));

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
const at = (img, x, y) => { const i = (y * img.w + x) * img.chans; return [img.out[i], img.out[i + 1], img.out[i + 2]]; };
/** 从四条边洪泛：相邻格每步最大通道差 ≤T 才算连得上 ⇒ 渐变底也连得过去，硬边缘挡住 */
function floodBg(img) {
  const { w, h } = img, seen = new Uint8Array(w * h), st = [];
  const seed = (x, y) => { const k = y * w + x; if (!seen[k]) { seen[k] = 1; st.push(k); } };
  for (let x = 0; x < w; x++) { seed(x, 0); seed(x, 1); seed(x, h - 1); seed(x, h - 2); }
  for (let y = 2; y < h - 2; y++) { seed(0, y); seed(1, y); seed(w - 1, y); seed(w - 2, y); }
  const near = (a, b) => Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])) <= T;
  while (st.length) {
    const k = st.pop(), x = k % w, y = (k / w) | 0, c = at(img, x, y);
    for (const [nx, ny] of [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]]) {
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const nk = ny * w + nx; if (seen[nk]) continue;
      if (!near(c, at(img, nx, ny))) continue;
      seen[nk] = 1; st.push(nk);
    }
  }
  return seen;
}
function bbox(n, w, pred) {
  let l = w, t = n, r = -1, b = -1, cnt = 0;
  for (let y = 0; y < n / w; y++) for (let x = 0; x < w; x++) {
    if (!pred(x, y)) continue;
    cnt++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return { ext: r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / w, share: cnt / n };
}
const d = new DatabaseSync(dbp, { readOnly: true });
// 行钉死启动图标（与 `_fgpair.cjs` 同一张钉表）：库首行可能是小组件/快捷方式/空段占位行
//   （2026-11-15 现量 36 个老式包里 14 个首行判错）。没钉表或库里缺那一行 ⇒ 回退首行并报数。
const ROWS = new Map();
try {
  for (const l of fs.readFileSync(require('path').join(__dirname, 'bench/reg/launcher_rows.tsv'), 'utf8').trim().split('\n')) {
    const [p, c] = l.split('\t'); if (p && c) ROWS.set(p, c);
  }
} catch { /* 回退首行 */ }
const byPkg = new Map();
for (const r of d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all()) {
  const pkg = r.c.split('/')[0];
  if (!r.i || !r.m || !pkg.includes('.')) continue;
  if (needles.length && !needles.some((n) => r.c.includes(n))) continue;
  if (!byPkg.has(pkg)) byPkg.set(pkg, []);
  byPkg.get(pkg).push(r);
}
const picked = new Map();
let pinN = 0, fallN = 0;
for (const [pkg, list] of byPkg) {
  const want = ROWS.get(pkg);
  const hit = want && list.find((r) => r.c === want);
  if (hit) { pinN++; picked.set(pkg, hit); } else { fallN++; picked.set(pkg, list[0]); }
}
const rows = [];
// 库里那张单色**是谁画的**：`--log=<logcat>` 才分得开。透传的那张是平台自己画的矢量，
// 拿它跟我们的几何对表 = 把别人的行算进我们的分母（2026-11-15 装机的"偏小尾巴 6 张"全是透传，
// 就是这么被误读成几何故障的）。只认两条明确的证据：`透传 … 是谁=包/…` 与 `Mono: 跟彩色 … label=包/…`。
const PASS = new Set(), OURS = new Set();
for (const f of (process.env.LOG || '').split(',').filter(Boolean)) {
  const txt = fs.readFileSync(f, 'utf8');
  for (const m of txt.matchAll(/透传 #\d+ 是谁=([\w.]+)\//g)) PASS.add(m[1]);
  for (const m of txt.matchAll(/Mono: 跟彩色(?:\(留档\))?[^\n]*?label=([\w.]+)\//g)) OURS.add(m[1]);
  for (const m of txt.matchAll(/Mono: diag#\d+[^\n]*?键=\w+ ([\w.]+)\//g)) OURS.add(m[1]);
}
const cls = (pkg) => (OURS.has(pkg) && PASS.has(pkg) ? '混合' : OURS.has(pkg) ? '我们' : PASS.has(pkg) ? '透传' : OURS.size + PASS.size ? '未知' : '没给log');
for (const [pkg, r] of picked) {
  const ci = decode(Buffer.from(r.i)); if (!ci) continue;
  const seen = floodBg(ci);
  const subj = bbox(ci.w * ci.h, ci.w, (x, y) => !seen[y * ci.w + x]);
  const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length));
  if (n * n !== mb.length) continue;
  const mo = bbox(n * n, n, (x, y) => mb[y * n + x] > 8);
  if (!subj.ext || !mo.ext) continue;
  rows.push([pkg, mo.ext / subj.ext, subj.ext, subj.share, mo.ext, r.c, cls(pkg)]);
}
rows.sort((a, b) => a[1] - b[1]);
if (process.env.DUMP) fs.writeFileSync(process.env.DUMP, rows.map((x) => `${x[0]}\t${x[1].toFixed(4)}\t${x[2].toFixed(4)}\t${x[3].toFixed(4)}\t${x[4].toFixed(4)}\t${x[5]}\t${x[6]}`).join('\n') + '\n');
const q = (v, p) => v.length ? v.slice().sort((x, y) => x - y)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;
const say = (tag, rs) => {
  const v = rs.map((x) => x[1]);
  console.log(`  【${tag}】${rs.length} 个包  p10 ${q(v, 0.1).toFixed(3)}  中位 ${q(v, 0.5).toFixed(3)}  p90 ${q(v, 0.9).toFixed(3)}`);
  if (tag !== '透传' && tag !== '没给log') {
    console.log(`    最小的 4 个（单色比彩色里的同一个主体小）：`);
    for (const [pkg, r, , ss, me, , w] of rs.slice(0, 4)) console.log(`      ${r.toFixed(3)}  ${pkg.padEnd(30)} 洪泛后主体占 ${(ss * 100).toFixed(1)}%  单色 ${me.toFixed(3)}  ${w}`);
    console.log(`    最大的 4 个（单色比彩色里的同一个主体大）：`);
    for (const [pkg, r, , ss, me, , w] of rs.slice(-4).reverse()) console.log(`      ${r.toFixed(3)}  ${pkg.padEnd(30)} 洪泛后主体占 ${(ss * 100).toFixed(1)}%  单色 ${me.toFixed(3)}  ${w}`);
  }
};
console.log(`同一个主体比（单色主体幅 ÷ 彩色洪泛掉底之后的主体幅；容差 T=${T}）：${rows.length} 个包（行：钉住 ${pinN} / 回退首行 ${fallN}）${process.env.LOG ? '' : '（没给 LOG= ⇒ 分不出谁画的，全部混在一起读）'}`);
for (const tag of ['我们', '透传', '混合', '未知', '没给log']) {
  const rs = rows.filter((x) => x[6].startsWith(tag));
  if (rs.length) say(tag, rs);
}
