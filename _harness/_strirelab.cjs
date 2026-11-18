// 放射条纹台：真实尺度圆盘（S=256 R=110 纵向渐变 40→10 软边6），
// 比较 BFS 等距合并的两种写法 × 盒抹遍数：条纹幅度（r150/170 逐度相邻max）vs 角部贴边色。
// 负对照：硬边平色/渐变盘，各变体与 blur2/tie 基线逐格相同（平色）或条纹读数（渐变）。
const S = 256, C = S / 2, R = 110;
function disc(ramp, grad) {
  const px = new Int32Array(S * S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const d = Math.hypot(x - C + 0.5, y - C + 0.5);
    const t = grad ? Math.min(1, Math.max(0, y / S)) : 0.5;
    const v = Math.round(40 + (10 - 40) * t);
    let a;
    if (d <= R - ramp) a = 255;
    else if (d >= R) a = 0;
    else a = Math.round(255 * (R - d) / ramp);
    px[y * S + x] = (a << 24) | (v << 16) | (v << 8) | v;
  }
  return px;
}
function fill(px, blurPasses, merge) {
  const n = S * S, A = i => px[i] >>> 24;
  const outside = new Uint8Array(n), work = new Int32Array(n);
  let sp = 0;
  const mark = i => { if (!outside[i] && A(i) <= 8) { outside[i] = 1; work[sp++] = i; } };
  for (let x = 0; x < S; x++) { mark(x); mark((S - 1) * S + x); }
  for (let y = 0; y < S; y++) { mark(y * S); mark(y * S + S - 1); }
  while (sp > 0) {
    const i = work[--sp], x = i % S, y = (i / S) | 0;
    if (x > 0) mark(i - 1); if (x + 1 < S) mark(i + 1);
    if (y > 0) mark(i - S); if (y + 1 < S) mark(i + S);
  }
  const soft = new Uint8Array(n);
  let sp2 = 0;
  for (let i = 0; i < n; i++) if (outside[i]) { soft[i] = 1; work[sp2++] = i; }
  while (sp2 > 0) {
    const i = work[--sp2], x = i % S, y = (i / S) | 0;
    const push = j => { if (!soft[j] && A(j) < 255) { soft[j] = 1; work[sp2++] = j; } };
    if (x > 0) push(i - 1); if (x + 1 < S) push(i + 1);
    if (y > 0) push(i - S); if (y + 1 < S) push(i + S);
  }
  const dist = new Int32Array(n).fill(-1), cnt = new Int32Array(n);
  const sR = new Int32Array(n), sG = new Int32Array(n), sB = new Int32Array(n);
  let head = 0, tail = 0;
  for (const threshold of [255, 160]) {
    for (let i = 0; i < n; i++) {
      if (A(i) < threshold) continue;
      const x = i % S, y = (i / S) | 0;
      let seed = false;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        if (!dx && !dy) continue;
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
        if (soft[ny * S + nx]) seed = true;
      }
      if (!seed) continue;
      dist[i] = 0; cnt[i] = 1;
      sR[i] = (px[i] >> 16) & 255; sG[i] = (px[i] >> 8) & 255; sB[i] = px[i] & 255;
      work[tail++] = i;
    }
    if (tail > 0) break;
    dist.fill(-1);
  }
  while (head < tail) {
    const i = work[head++], x = i % S, y = (i / S) | 0, d = dist[i] + 1;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
      const j = ny * S + nx;
      if (!soft[j]) continue;
      if (dist[j] < 0) {
        dist[j] = d; cnt[j] = cnt[i]; sR[j] = sR[i]; sG[j] = sG[i]; sB[j] = sB[i];
        work[tail++] = j;
      } else if (dist[j] === d) {
        cnt[j] += 1;
        if (merge === 'tie') {
          sR[j] += ((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
          sG[j] += ((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
          sB[j] += ((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
        } else {
          sR[j] += sR[i]; sG[j] += sG[i]; sB[j] += sB[i];
        }
      }
    }
  }
  const out = new Int32Array(n);
  for (let i = 0; i < n; i++) if (outside[i] && dist[i] >= 0) {
    out[i] = (255 << 24) | (((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 16 |
      (((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 8 | (((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0);
  }
  for (let p = 0; p < blurPasses; p++) {
    const snap = out.slice();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = y * S + x;
      if (!outside[i] || !snap[i]) continue;
      let sr = 0, sg = 0, sb = 0, c = 0;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= S || ny >= S) continue;
        const j = ny * S + nx;
        if (!snap[j]) continue;
        sr += (snap[j] >> 16) & 255; sg += (snap[j] >> 8) & 255; sb += snap[j] & 255; c++;
      }
      out[i] = (255 << 24) | ((sr / c) | 0) << 16 | ((sg / c) | 0) << 8 | ((sb / c) | 0);
    }
  }
  return out;
}
const ring = (out, r, sec) => {
  const vals = [];
  for (let deg = sec; deg < sec + 40; deg++) {
    const t = deg * Math.PI / 180;
    const x = Math.round(C + r * Math.cos(t)), y = Math.round(C + r * Math.sin(t));
    if (x < 0 || y < 0 || x >= S || y >= S) continue;
    vals.push((out[y * S + x] >> 16) & 255);
  }
  let mx = 0;
  for (let k = 1; k < vals.length; k++) mx = Math.max(mx, Math.abs(vals[k] - vals[k - 1]));
  return mx;
};
const px = disc(6, true);
for (const merge of ['tie', 'sum']) for (const bp of [2, 4, 6, 8]) {
  const out = fill(px, bp, merge);
  const tl = (out[0] >> 16) & 255, tr = (out[S - 1] >> 16) & 255;
  const bl = (out[(S - 1) * S] >> 16) & 255, br = (out[S * S - 1] >> 16) & 255;
  console.log(merge + ' blur' + bp +
    ' 条纹max r150[上左/上右/下左/下右]=' + [210, 300, 120, 60].map(s => ring(out, 150, s)).join('/') +
    ' r170=' + [210, 300, 120, 60].map(s => ring(out, 170, s)).join('/') +
    ' 四角=' + [tl, tr, bl, br].join(',') + ' (局部边色 上≈33 下≈13 左≈25 右≈25)');
}
// 负对照：硬边平色盘所有变体逐格相同
const flat = disc(0, false);
const base = fill(flat, 2, 'tie');
let bad = 0;
for (const merge of ['tie', 'sum']) for (const bp of [2, 4, 6, 8]) {
  const o = fill(flat, bp, merge);
  let d = 0;
  for (let i = 0; i < o.length; i++) if (o[i] !== base[i]) d++;
  if (d !== 0) { console.log('✗ 硬边平色 ' + merge + ' blur' + bp + ' 差异=' + d); bad++; }
}
console.log(bad === 0 ? '硬边负对照全过' : '负对照红 ' + bad);