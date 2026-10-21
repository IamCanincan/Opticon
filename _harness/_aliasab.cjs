// 「应用自带的 <monochrome> 其实是前景层复制的那一份」——这条到底能不能当判据。
// 飞鸟那一轮把它否掉了（23/131 个包都这样，其中 22 张屏上正常），但那次的数混了三种量法。
// 这一台把三件事一次量齐：
//   1. 自画单色层与前景层**是不是同一份像素**（别名，不看实例身份，看内容）；
//   2. 那一份**是不是满幅**（四角与边界环的不透明占比 —— 满幅=它是一张画，不是抠出来的剪影）；
//   3. 屏上**实际交付的那张**有多少墨（桌面 app_icons.db 的 mono_icon，逐张对上包名）。
// 用法：node _harness/_aliasab.cjs <audit目录> <mono_fresh目录>
const fs = require('fs'), path = require('path'), zlib = require('zlib');

function decode(p) {
  const b = fs.readFileSync(p);
  let o = 8, idat = [], W = 0, H = 0, color = 0, depth = 0;
  while (o + 8 <= b.length) {
    const len = b.readUInt32BE(o), t = b.toString('ascii', o + 4, o + 8);
    if (t === 'IHDR') { W = b.readUInt32BE(o + 8); H = b.readUInt32BE(o + 12); depth = b[o + 16]; color = b[o + 17]; }
    if (t === 'IDAT') idat.push(b.slice(o + 8, o + 8 + len));
    if (t === 'IEND') break;
    o += 12 + len;
  }
  if (depth !== 8) throw new Error('位深 ' + depth);
  const bpp = color === 6 ? 4 : color === 0 ? 1 : color === 4 ? 2 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = W * bpp, out = Buffer.alloc(H * stride);
  let q = 0;
  for (let y = 0; y < H; y++) {
    const f = raw[q++];
    for (let x = 0; x < stride; x++) {
      const v = raw[q++];
      const a = x >= bpp ? out[y * stride + x - bpp] : 0;
      const bb = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = (x >= bpp && y > 0) ? out[(y - 1) * stride + x - bpp] : 0;
      let r;
      if (f === 0) r = v; else if (f === 1) r = v + a; else if (f === 2) r = v + bb;
      else if (f === 3) r = v + ((a + bb) >> 1);
      else { const pp = a + bb - c, pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - c);
        r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? bb : c); }
      out[y * stride + x] = r & 0xff;
    }
  }
  return { W, H, bpp, d: out };
}
const alphaOf = (im, x, y) => im.d[y * im.W * im.bpp + x * im.bpp + (im.bpp === 4 ? 3 : 0)];

/** 满幅程度：四角 16×16 的均值 + 边界环上 α≥128 的占比。 */
function bleed(im) {
  const S = 16; let corners = 0, n = 0;
  for (const [cx, cy] of [[0, 0], [im.W - S, 0], [0, im.H - S], [im.W - S, im.H - S]]) {
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) { corners += alphaOf(im, cx + x, cy + y); n++; }
  }
  let ring = 0, ringN = 0;
  for (let x = 0; x < im.W; x++) { for (const y of [0, 1, im.H - 2, im.H - 1]) { ring += alphaOf(im, x, y) > 127 ? 1 : 0; ringN++; } }
  for (let y = 0; y < im.H; y++) { for (const x of [0, 1, im.W - 2, im.W - 1]) { ring += alphaOf(im, x, y) > 127 ? 1 : 0; ringN++; } }
  return { corner: corners / n, ring: ring / ringN };
}
/** **可见圆内**的不透明占比 —— 自适应图标的四角本来就在遮罩外，量四角等于量了个恒 0 的格子。
 *  桌面按 66dp 直径取中央那块（= 画布的 61%），所以半径取 0.305·W。 */
function disc(im) {
  const cx = (im.W - 1) / 2, cy = (im.H - 1) / 2, r = 0.305 * im.W, r2 = r * r;
  let cov = 0, sol = 0, n = 0;
  for (let y = 0; y < im.H; y++) {
    const dy = y - cy;
    for (let x = 0; x < im.W; x++) {
      const dx = x - cx;
      if (dx * dx + dy * dy > r2) continue;
      n++;
      const a = alphaOf(im, x, y);
      if (a > 0) cov++;
      if (a >= 224) sol++;
    }
  }
  return { cov: cov / n, sol: sol / n, n };
}
function alphaStats(im) {
  let cov = 0, sol = 0, mass = 0; const N = im.W * im.H;
  for (let i = 0; i < N; i++) { const a = im.d[i * im.bpp + (im.bpp === 4 ? 3 : 0)]; if (a > 0) cov++; if (a >= 224) sol++; mass += a; }
  return { cov: cov / N, sol: sol / N, mean: mass / N };
}
/** 同一份像素？两边尺寸不同就按各自尺寸比 alpha 序列（抓回来的边长可能不同）。 */
function samePixels(A, B) {
  const sa = alphaStats(A), sb = alphaStats(B);
  if (A.W !== B.W || A.H !== B.H) {
    return { eq: false, near: Math.abs(sa.cov - sb.cov) < 0.002 && Math.abs(sa.mean - sb.mean) < 1.5, note: '尺寸不同' };
  }
  let diff = 0, maxd = 0;
  for (let i = 0; i < A.W * A.H; i++) {
    const d = Math.abs(A.d[i * A.bpp + (A.bpp === 4 ? 3 : 0)] - B.d[i * B.bpp + (B.bpp === 4 ? 3 : 0)]);
    if (d) { diff++; if (d > maxd) maxd = d; }
  }
  return { eq: diff === 0, near: diff / (A.W * A.H) < 0.002, diffShare: diff / (A.W * A.H), maxd };
}

const auditDir = process.argv[2] || 'D:/Github Repositories/Opticon/_harness/corpus_audit3';
const dbDir = process.argv[3] || 'D:/tmp/mono_fresh';
const probe = (process.argv.find(a => a.startsWith('--probe=')) || '').slice(8);
if (probe) {
  const im = decode(probe), d = disc(im), s = alphaStats(im);
  console.log(probe.replace(/^.*\//, '') + '  可见圆 ' + (d.cov * 100).toFixed(1) + '% 有墨 / ' + (d.sol * 100).toFixed(1) + '% 全实' +
    '   整张 ' + (s.cov * 100).toFixed(1) + '% 有墨 / ' + (s.sol * 100).toFixed(1) + '% 全实   尺寸 ' + im.W + 'x' + im.H);
  process.exit(0);
}
const N = 256;
const gray = new Map();
for (const f of fs.readdirSync(dbDir)) {
  if (!f.endsWith('.gray')) continue;
  const a = fs.readFileSync(path.join(dbDir, f));
  if (a.length !== N * N) continue;
  let ink = 0, sol = 0, dInk = 0, dSol = 0, dn = 0;
  const R = 0.305 * N, R2 = R * R, C = (N - 1) / 2;
  for (let y = 0; y < N; y++) {
    const dy = y - C;
    for (let x = 0; x < N; x++) {
      const i = y * N + x, v = a[i];
      if (v > 0) ink++;
      if (v >= 224) sol++;
      const dx = x - C;
      if (dx * dx + dy * dy > R2) continue;
      dn++;
      if (v > 0) dInk++;
      if (v >= 224) dSol++;
    }
  }
  gray.set(f.replace(/\.gray$/, ''), { ink: ink / N / N, sol: sol / N / N, dInk: dInk / dn, dSol: dSol / dn });
}
const findDelivered = (pkg) => {
  let best = null;
  for (const [k, v] of gray) if (k.startsWith(pkg + '_') && (!best || v.dInk > best.dInk)) best = { ...v, k };
  return best;
};

const rows = [];
let pairedFg = 0, pairedWhole = 0;
for (const f of fs.readdirSync(auditDir)) {
  if (!f.endsWith('_mono.png')) continue;
  const stem = f.replace(/_mono\.png$/, '');
  // 前景层是 `<名>_fg.png`；裸名那张是**整图标合成图**，拿它比"同一份"会配错对象。
  const fgName = stem + '_fg.png', wholeName = stem + '.png';
  const useFg = fs.existsSync(path.join(auditDir, fgName));
  const base = useFg ? fgName : (fs.existsSync(path.join(auditDir, wholeName)) ? wholeName : null);
  if (!base) continue;
  if (useFg) pairedFg++; else pairedWhole++;
  const pkg = f.split('_')[0];
  let M, F;
  try { M = decode(path.join(auditDir, f)); F = decode(path.join(auditDir, base)); }
  catch (e) { console.log('跳过 ' + f + '：' + e.message); continue; }
  const sp = samePixels(M, F), dd = disc(M), st = alphaStats(M);
  const d = findDelivered(pkg);
  rows.push({ pkg, mono: f, same: sp.eq, near: !!sp.near, layerDisc: dd.cov, layerDiscSol: dd.sol,
    note: (sp.note ? sp.note + ' ' : '') + (useFg ? '对前景层' : '对整图标'),
    cov: st.cov, sol: st.sol, devInk: d ? d.ink : null, devSol: d ? d.sol : null,
    devDiscInk: d ? d.dInk : null });
}
if (pairedFg === 0) { console.log('!! 一张前景层配对都没有 —— 这台子在比错对象，读数全部作废'); process.exit(1); }
rows.sort((a, b) => (b.devDiscInk || 0) - (a.devDiscInk || 0));
const pct = (v) => v === null || v === undefined ? '  -  ' : (v * 100).toFixed(1).padStart(5);
console.log('包名'.padEnd(36) + '同一份 近 | 层·可见圆有墨 全实 | 屏上·可见圆有墨 全画布墨 全画布实  比的是谁');
for (const r of rows) {
  console.log(r.pkg.padEnd(36) + (r.same ? ' 是 ' : ' 否 ') + (r.near ? '是' : ' ') + ' | ' +
    pct(r.layerDisc) + ' ' + pct(r.layerDiscSol) + ' | ' +
    pct(r.devDiscInk) + ' ' + pct(r.devInk) + ' ' + pct(r.devSol) + '  ' + r.note);
}
const same = rows.filter(r => r.same || r.near);
console.log('\n对前景层 ' + pairedFg + ' 张 / 对整图标 ' + pairedWhole + ' 张');
console.log('同一份(含近似) ' + same.length + ' / ' + rows.length + ' 张配对');
console.log('\n按"层在可见圆里有墨%"排序（这一列就是判据本身）：');
rows.slice(0, 12).forEach(r => console.log('  ' + r.pkg.padEnd(36) + ' 层' + (r.layerDisc * 100).toFixed(1) + '%  屏上' + ((r.devDiscInk || 0) * 100).toFixed(1) + '%  ' + (r.same ? '同一份' : '另画')));
