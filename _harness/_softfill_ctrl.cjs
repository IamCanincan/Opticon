// 软边种子修复的正/负对照：镜像生产 computeFill（outside 洪泛 → soft 洪泛 → 种子 → BFS）。
// 正对照：6 格 α 坡 + 纵向渐变圆盘 ⇒ 新算法底角≈局部边色、旧算法底角≈顶弧色（病复现）。
// 负对照：硬边圆盘（α 0→255 直跳，含渐变）⇒ 新旧 fill 逐格相同。
const S = 128, C = S / 2, R = 56;
function disc(ramp, grad, softBottom, rim) {
  const px = new Int32Array(S * S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const d = Math.hypot(x - C + 0.5, y - C + 0.5);
    const t = grad ? Math.min(1, Math.max(0, y / S)) : 0.5;
    let v = Math.round(30 + (11 - 30) * t);
    const rp = softBottom && y < C - R * 0.5 ? 0 : ramp;
    let a;
    if (d <= R - rp) a = 255;
    else if (d >= R) a = 0;
    else a = Math.round(255 * (R - d) / rp);
    if (rim && a > 0 && a < 255) v = Math.max(0, Math.min(255, Math.round(v * 255 / a) + (((x * 73856093) ^ (y * 19349663)) % 21) - 10));
    px[y * S + x] = (a << 24) | (v << 16) | (v << 8) | v;
  }
  return px;
}
function fill(px, softPass, smooth, legacySeed) {
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
  if (softPass) {
    let sp2 = 0;
    for (let i = 0; i < n; i++) if (outside[i]) { soft[i] = 1; work[sp2++] = i; }
    while (sp2 > 0) {
      const i = work[--sp2], x = i % S, y = (i / S) | 0;
      const push = j => { if (!soft[j] && A(j) < 255) { soft[j] = 1; work[sp2++] = j; } };
      if (x > 0) push(i - 1); if (x + 1 < S) push(i + 1);
      if (y > 0) push(i - S); if (y + 1 < S) push(i + S);
    }
  } else soft.set(outside);
  const dist = new Int32Array(n).fill(-1), cnt = new Int32Array(n);
  const sR = new Int32Array(n), sG = new Int32Array(n), sB = new Int32Array(n);
  let head = 0, tail = 0;
  for (const threshold of (legacySeed ? [160] : [255, 160])) {
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
        sR[j] += ((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
        sG[j] += ((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
        sB[j] += ((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0;
      }
    }
  }
  const out = new Int32Array(n);
  for (let i = 0; i < n; i++) if (outside[i] && dist[i] >= 0) {
    out[i] = (255 << 24) | (((sR[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 16 |
      (((sG[i] + (cnt[i] >> 1)) / cnt[i]) | 0) << 8 | (((sB[i] + (cnt[i] >> 1)) / cnt[i]) | 0);
  }
  if (smooth) {
    for (let p = 0; p < 2; p++) {
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
  }
  return { out, dist, cnt, outside };
}
const rgb = c => [(c >> 16) & 255, (c >> 8) & 255, c & 255];
const corners = f => [[0, 0], [S - 1, 0], [0, S - 1], [S - 1, S - 1]].map(([x, y]) => rgb(f.out[y * S + x]).join(','));
let bad = 0;
// 正对照：软边渐变盘 + α 坡 unpremultiply 噪声；旧臂=软通路+160 种子（放射条纹那版）
{
  const px = disc(6, true, true, true);
  const oldF = fill(px, true, false, true), newF = fill(px, true, true, false);
  const blNew = rgb(newF.out[(S - 1) * S])[0], blOld = rgb(oldF.out[(S - 1) * S])[0];
  console.log('软边渐变+高光盘 旧四角', corners(oldF).join(' | '), ' 新四角', corners(newF).join(' | '));
  console.log('  底左角 旧=' + blOld + ' 新=' + blNew + ' 局部边色≈12');
  if (!(Math.abs(blOld - 12) >= 4)) { console.log('  ✗ 旧算法底角没复现噪声/借色'); bad++; }
  if (!(Math.abs(blNew - 12) <= 3)) { console.log('  ✗ 新算法底角没贴局部边色'); bad++; }
  if (!(newF.dist[(S - 1) * S] < 60)) { console.log('  ✗ 新算法底角距离仍远（种子没通路）'); bad++; }
  const adj = f => {
    let mx = 0;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = y * S + x;
      if (!f.outside[i] || !f.out[i]) continue;
      const v = (f.out[i] >> 16) & 255;
      if (x + 1 < S && f.outside[i + 1] && f.out[i + 1]) mx = Math.max(mx, Math.abs(v - ((f.out[i + 1] >> 16) & 255)));
      if (y + 1 < S && f.outside[i + S] && f.out[i + S]) mx = Math.max(mx, Math.abs(v - ((f.out[i + S] >> 16) & 255)));
    }
    return mx;
  };
  const aOld = adj(oldF), aNew = adj(newF);
  console.log('  补色区相邻max 旧=' + aOld + ' 新=' + aNew);
  if (!(aOld >= 10)) { console.log('  ✗ 旧算法没复现放射条纹'); bad++; }
  if (!(aNew <= 3)) { console.log('  ✗ 新算法条纹没压到 ≤3'); bad++; }
}
// 负对照：硬边（平色 + 渐变）新旧逐格相同
for (const grad of [false, true]) {
  const px = disc(0, grad);
  const a = fill(px, false).out, b = fill(px, true).out;
  let diff = 0;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) diff++;
  console.log('硬边' + (grad ? '渐变' : '平色') + '盘 新旧差异格=' + diff);
  if (diff !== 0) bad++;
}
console.log(bad === 0 ? '对照全过' : '对照红 ' + bad + ' 条');
process.exit(bad === 0 ? 0 : 1);