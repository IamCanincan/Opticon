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
const picked = new Map();
for (const r of d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all()) {
  const pkg = r.c.split('/')[0];
  if (!r.i || !r.m || !pkg.includes('.')) continue;
  if (needles.length && !needles.some((n) => r.c.includes(n))) continue;
  if (!picked.has(pkg)) picked.set(pkg, r);
}
const rows = [];
for (const [pkg, r] of picked) {
  const ci = decode(Buffer.from(r.i)); if (!ci) continue;
  const seen = floodBg(ci);
  const subj = bbox(ci.w * ci.h, ci.w, (x, y) => !seen[y * ci.w + x]);
  const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length));
  if (n * n !== mb.length) continue;
  const mo = bbox(n * n, n, (x, y) => mb[y * n + x] > 8);
  if (!subj.ext || !mo.ext) continue;
  rows.push([pkg, mo.ext / subj.ext, subj.ext, subj.share, mo.ext]);
}
rows.sort((a, b) => a[1] - b[1]);
const q = (v, p) => v.length ? v.slice().sort((x, y) => x - y)[Math.min(v.length - 1, Math.round(p * (v.length - 1)))] : NaN;
console.log(`同一个主体比（单色主体幅 ÷ 彩色洪泛掉底之后的主体幅；容差 T=${T}）：${rows.length} 个包`);
const v = rows.map((x) => x[1]);
console.log(`  p10 ${q(v, 0.1).toFixed(3)}  中位 ${q(v, 0.5).toFixed(3)}  p90 ${q(v, 0.9).toFixed(3)}`);
console.log(`  最小的 6 个（单色比彩色里的同一个主体小）：`);
for (const [pkg, r, se, ss, me] of rows.slice(0, 6)) console.log(`    ${r.toFixed(3)}  ${pkg.padEnd(32)} 彩色主体 ${se.toFixed(3)}（洪泛掉底后占 ${(ss * 100).toFixed(1)}%）  单色 ${me.toFixed(3)}`);
console.log(`  最大的 6 个（单色比彩色里的同一个主体大）：`);
for (const [pkg, r, se, ss, me] of rows.slice(-6).reverse()) console.log(`    ${r.toFixed(3)}  ${pkg.padEnd(32)} 彩色主体 ${se.toFixed(3)}（洪泛掉底后占 ${(ss * 100).toFixed(1)}%）  单色 ${me.toFixed(3)}`);
