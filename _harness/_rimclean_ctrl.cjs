// cleanRim 的正/负对照（镜像生产 cleanRim，逐字同判据）：
// 正：噪实心环盘（r=R-1/R 两圈 α255 带 ±20 噪）⇒ 环逐度相邻 ≤2，且补色场条纹 ≤2；
// 负：光滑环盘 / ±1 抖动环盘 / 2px 细笔画 ⇒ 逐字节不变。
const fs = require('fs');
const S = 256, C = S / 2, R = 110;
function disc(noise, dither) {
  const px = new Int32Array(S * S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const d = Math.hypot(x - C + 0.5, y - C + 0.5);
    const t = Math.min(1, Math.max(0, y / S));
    let v = Math.round(40 + (10 - 40) * t);
    let a;
    if (d <= R - 2) a = 255;
    else if (d >= R + 4) a = 0;
    else if (d <= R) { a = 255; if (noise) v = Math.max(0, Math.min(255, v + (((x * 73856093) ^ (y * 19349663)) % 41) - 20)); else if (dither) v += ((x + y) % 2); }
    else a = Math.round(255 * (R + 4 - d) / 4);
    px[y * S + x] = (a << 24) | (v << 16) | (v << 8) | v;
  }
  return px;
}
function stroke() {
  const px = new Int32Array(S * S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const on = (Math.abs(x - C) <= 1 && y > 40 && y < 216) || (Math.abs(y - C) <= 1 && x > 40 && x < 216);
    px[y * S + x] = on ? (255 << 24) | (200 << 16) | (30 << 8) | 30 : 0;
  }
  return px;
}
function cleanRim(px) {
  const n = S * S, A = i => px[i] >>> 24;
  const depth = new Int32Array(n);
  for (let i = 0; i < n; i++) depth[i] = A(i) < 255 ? 0 : 1 << 20;
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const i = y * S + x;
    if (!depth[i]) continue;
    let d = depth[i];
    for (let dy = -1; dy <= 0; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (dy === 0 && dx >= 0) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
      d = Math.min(d, depth[ny * S + nx] + 1);
    }
    depth[i] = d;
  }
  for (let y = S - 1; y >= 0; y--) for (let x = S - 1; x >= 0; x--) {
    const i = y * S + x;
    if (!depth[i]) continue;
    let d = depth[i];
    for (let dy = 0; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (dy === 0 && dx <= 0) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
      d = Math.min(d, depth[ny * S + nx] + 1);
    }
    depth[i] = d;
  }
  const snap = px.slice();
  let written = 0;
  for (let i = 0; i < n; i++) {
    if (A(i) !== 255 || depth[i] > 3) continue;
    const x = i % S, y = (i / S) | 0;
    let sr = 0, sg = 0, sb = 0, cnt = 0;
    for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) {
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
      const j = ny * S + nx;
      if (depth[j] < 4) continue;
      sr += (snap[j] >> 16) & 255; sg += (snap[j] >> 8) & 255; sb += snap[j] & 255; cnt++;
    }
    if (!cnt) continue;
    const r = (sr / cnt) | 0, g = (sg / cnt) | 0, b = (sb / cnt) | 0;
    if (Math.abs(r - ((snap[i] >> 16) & 255)) <= 2 && Math.abs(g - ((snap[i] >> 8) & 255)) <= 2 && Math.abs(b - (snap[i] & 255)) <= 2) continue;
    px[i] = (255 << 24) | (r << 16) | (g << 8) | b;
    written++;
  }
  return written;
}
const srcTxt = fs.readFileSync(__dirname + '/_strirelab.cjs', 'utf8');
const fill = new Function(srcTxt.slice(0, srcTxt.indexOf('const ring =')) + '; return fill;')();
const ringAdj = (px, r, sec) => {
  const vals = [];
  for (let deg = sec; deg < sec + 40; deg++) {
    const t = deg * Math.PI / 180;
    const x = Math.round(C + r * Math.cos(t)), y = Math.round(C + r * Math.sin(t));
    if (x < 0 || y < 0 || x >= S || y >= S) continue;
    vals.push((px[y * S + x] >> 16) & 255);
  }
  let mx = 0;
  for (let k = 1; k < vals.length; k++) mx = Math.max(mx, Math.abs(vals[k] - vals[k - 1]));
  return mx;
};
let bad = 0;
// 正对照
{
  const px = disc(true, false);
  const before = [210, 300].map(s => ringAdj(px, R - 1, s));
  const w = cleanRim(px);
  const after = [210, 300].map(s => ringAdj(px, R - 1, s));
  const f = fill(px, 2, 'tie');
  const stripe = [210, 300, 120, 60].map(s => ringAdj(f, 150, s));
  console.log('噪环盘 写格=' + w + ' 环相邻 前=' + before.join('/') + ' 后=' + after.join('/') + ' 补色条纹r150=' + stripe.join('/'));
  if (!(w > 500)) { console.log('  ✗ 噪环没被写（刀没开火）'); bad++; }
  if (!(after.every(v => v <= 2))) { console.log('  ✗ 环没抹平'); bad++; }
  if (!(stripe.every(v => v <= 2))) { console.log('  ✗ 补色条纹没压到 ≤2'); bad++; }
  const f0 = fill(disc(true, false), 2, 'tie');
  const stripe0 = [210, 300].map(s => ringAdj(f0, 150, s));
  console.log('  未下刀补色条纹r150 上左/上右=' + stripe0.join('/') + '（应 ≥6，证明正对照真有病）');
  if (!(stripe0.some(v => v >= 6))) { console.log('  ✗ 正对照构造没复现条纹'); bad++; }
}
// 负对照：逐字节不变
for (const [tag, px] of [['光滑环', disc(false, false)], ['±1抖动环', disc(false, true)], ['2px细笔画', stroke()]]) {
  const cp = px.slice();
  const w = cleanRim(cp);
  let d = 0;
  for (let i = 0; i < cp.length; i++) if (cp[i] !== px[i]) d++;
  console.log('负对照 ' + tag + ' 写格=' + w + ' 差异=' + d);
  if (d !== 0 || w !== 0) { console.log('  ✗ 负对照被误伤'); bad++; }
}
console.log(bad === 0 ? 'cleanRim 对照全过' : '对照红 ' + bad + ' 条');
process.exit(bad === 0 ? 0 : 1);