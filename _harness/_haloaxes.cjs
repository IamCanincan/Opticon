// 「老式图标背景残膜」那一刀的**十轴验尸台**（2026-11-16）。
// 问题：老式（无前景层）图标的单色掩码里，背景常被画成淡雾（α 1..199），把剪影外接框撑大
//   （autonavi 1.320 / cainiao 1.184 / tmri 1.176，验收线 1.000±0.05）。
// 想下一刀「把背景淡雾归零」。这台子把十根候选轴逐根量**两侧**（命中 + 误判），
// 结论：**十轴全死** —— 唯一反例 com.unionpay 的「银联三色标志」本身就是一团淡雾（内容！），
//   它在每一根轴上都与 tmri/autonavi 的背景淡雾同形状，只差"人叫它内容还是背景"。
//   擦掉它 = 擦掉标志（看图可见），所以这一刀不下，生产一行不动。详见 README「残膜十轴」。
// 用法：node _harness/_haloaxes.cjs <app_icons.db> <身份表 iconclass.json> <钉表 launcher_rows.tsv> [--sheet=出图.png]
// 自证：autonavi 必须在「距离轴」命中栏出现（它是已知正对照）；unionpay 必须在每轴误判栏出现。
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');
const [dbp, clsp, rowsP] = process.argv.slice(2);
const SHEET = (process.argv.find((a) => a.startsWith('--sheet=')) || '').slice(8);

function decode(buf) {
  let pos = 8, ihdr = null, trns = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'tRNS') trns = data; else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color, depth, interlace } = ihdr, chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!chans || depth !== 8 || interlace) return null;
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0; let p = 0;
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
  return { w, h, A, out, chans };
}
const rgbOf = (img) => { const { w, h, out, chans } = img; const rgb = new Array(w * h);
  for (let i = 0; i < w * h; i++) { const j = i * chans; const r = out[j], g = chans >= 3 ? out[j + 1] : out[j], b = chans >= 3 ? out[j + 2] : out[j]; rgb[i] = (r << 16) | (g << 8) | b; } return rgb; };
function floodSeen(img, T) {
  const { w, h, chans, out } = img;
  const at = (x, y) => { const i = (y * w + x) * chans; return (chans === 3 || chans === 6) ? [out[i], out[i + 1], out[i + 2]] : [out[i], out[i], out[i]]; };
  const seen = new Uint8Array(w * h), st = []; const seed = (x, y) => { const k = y * w + x; if (!seen[k]) { seen[k] = 1; st.push(k); } };
  for (let x = 0; x < w; x++) { seed(x, 0); seed(x, 1); seed(x, h - 1); seed(x, h - 2); }
  for (let y = 2; y < h - 2; y++) { seed(0, y); seed(1, y); seed(w - 1, y); seed(w - 2, y); }
  const near = (a, b) => Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])) <= T;
  while (st.length) {
    const k = st.pop(), x = k % w, y = (k / w) | 0, c = at(x, y);
    for (const [nx, ny] of [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]]) {
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const nk = ny * w + nx; if (seen[nk]) continue; if (!near(c, at(nx, ny))) continue; seen[nk] = 1; st.push(nk);
    }
  }
  return seen;
}
const ext = (A, n, thr) => { let l = n, t = n, r = -1, b = -1;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { if (!thr(A[y * n + x])) continue; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; }
  return r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / n; };
function bfs(n, seedPred) {
  const dist = new Int32Array(n * n).fill(1 << 20), q = new Int32Array(n * n); let h = 0, t = 0;
  for (let i = 0; i < n * n; i++) if (seedPred(i)) { dist[i] = 0; q[t++] = i; }
  while (h < t) {
    const p = q[h++], x = p % n, y = (p / n) | 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue; const qx = x + dx, qy = y + dy;
      if (qx < 0 || qy < 0 || qx >= n || qy >= n) continue; const q2 = qy * n + qx;
      if (dist[q2] > dist[p] + 1) { dist[q2] = dist[p] + 1; q[t++] = q2; }
    }
  }
  return dist;
}
function lab(px) {
  const f = (v) => v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92;
  const R = f(((px >> 16) & 255) / 255), G = f(((px >> 8) & 255) / 255), B = f((px & 255) / 255);
  let X = (0.4124 * R + 0.3576 * G + 0.1805 * B) / 0.95047, Y = 0.2126 * R + 0.7152 * G + 0.0722 * B, Z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / 1.08883;
  const h = (v) => v > 0.008856 ? Math.cbrt(v) : 7.787 * v + 16 / 116;
  X = h(X); Y = h(Y); Z = h(Z);
  return [116 * Y - 16, 500 * (X - Y), 200 * (Y - Z)];
}
const de = (a, b) => Math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2);

const ROWS = new Map(fs.readFileSync(rowsP, 'utf8').trim().split('\n').map((l) => l.split('\t')));
const ev = new Map(); for (const r of JSON.parse(fs.readFileSync(clsp, 'utf8'))) ev.set(r.pkg, r);
const rowsAll = new DatabaseSync(dbp, { readOnly: true }).prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all();
const byPkg = new Map();
for (const r of rowsAll) { const p = r.c.split('/')[0]; if (!r.i || !r.m) continue; if (!byPkg.has(p)) byPkg.set(p, []); byPkg.get(p).push(r); }
const targets = [];
for (const [pkg, list] of byPkg) {
  if (!pkg.includes('.')) continue;
  const e = ev.get(pkg); if (!e || e.mono === '有' || e.cls.includes('Adaptive')) continue;   // 只看老式·我们合成
  const want = ROWS.get(pkg); const r = (want && list.find((x) => x.c === want)) || list[0];
  const ci = decode(Buffer.from(r.i)); if (!ci) continue;
  const seen = floodSeen(ci, 24);
  let l = ci.w, t = ci.h, rr = -1, bb = -1;
  for (let y = 0; y < ci.h; y++) for (let x = 0; x < ci.w; x++) { if (seen[y * ci.w + x]) continue; if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y; }
  const se = rr < 0 ? 0 : Math.max(rr - l + 1, bb - t + 1) / ci.w;
  if (!se) continue;
  const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length)); if (n * n !== mb.length) continue;
  const A = new Int32Array(n * n); for (let i = 0; i < n * n; i++) A[i] = mb[i];
  targets.push({ pkg, ci, se, A, n, rgb: rgbOf(ci), seen });
}
console.log(`老式·我们合成 且有洪泛主体：${targets.length} 张`);
if (!targets.length) { console.log('!! 0 张 —— 台子没吃到数据，不是"没问题"'); process.exit(1); }

// 每轴给出 cut 谓词；统一报两侧。worse 的灵敏度 0.004 比验收线 0.05 严 —— 故意：
// 银联 0.983→0.970 在验收带内但**看图是擦掉标志**，数值带会放过它，所以这里不用验收带当误判线。
function run(name, predFn) {
  const fired = [], hurt = [], silent = [];
  for (const t of targets) {
    const { A, n, se } = t; const B = Int32Array.from(A); let cut = 0;
    const ctx = predFn.ctx ? predFn.ctx(t) : null;
    for (let i = 0; i < n * n; i++) { const a = A[i]; if (a < 1 || a > 199) continue; if (predFn(t, i, a, ctx)) { B[i] = 0; cut++; } }
    if (!cut) continue;
    const before = ext(A, n, (a) => a > 8) / se, after = ext(B, n, (a) => a > 8) / se;
    // ⚠ bbox 不动 ≠ 没伤：擦掉标志**内部**而留下外 AA 时外接框一字不变（unionpay D30 cut=445、
    //   wisdomtree cut=3690 都是这么躲过"0 误判"的）⇒ 静默损伤单独列，看图才算数。
    if (Math.abs(after - before) <= 0.004) { silent.push(`${t.pkg} cut=${cut}`); continue; }
    const worse = Math.abs(after - 1) > Math.abs(before - 1) + 0.004;
    (worse ? hurt : fired).push(`${t.pkg} ${before.toFixed(3)}→${after.toFixed(3)} cut=${cut}`);
  }
  console.log(`${name}: 动 ${fired.length + hurt.length}（好/中 ${fired.length}、坏 ${hurt.length}）静默擦除 ${silent.length}`);
  for (const s of fired) console.log('    + ' + s);
  for (const s of hurt) console.log('    - ' + s);
  for (const s of silent) console.log('    ~ ' + s + '（bbox 不动，看图才看见）');
  return { fired, hurt, silent };
}
const dsCache = new Map();
const dsOf = (t) => { if (!dsCache.has(t.pkg)) dsCache.set(t.pkg, bfs(t.n, (i) => t.A[i] >= 200)); return dsCache.get(t.pkg); };

// 轴1 颜色洪泛 + 实墨占比闸（_borderfog_sim 的原案）：软投影把主体连进"背景"，闸把三张目标全挡死
{
  const eps = 0.02;
  const f = (t, i, a, ctx) => ctx.ok && !!t.seen[i];
  f.ctx = (t) => { let region = 0, solid = 0;
    for (let i = 0; i < t.n * t.n; i++) if (t.seen[i]) { region++; if (t.A[i] >= 200) solid++; }
    return { ok: region > 0 && solid / region <= eps }; };
  run(`轴1 色洪泛T=24+实墨占比≤${eps}`, f);
}
// 轴2 距离实墨 > R，α≤cap
for (const R of [2, 4]) for (const cap of [64, 96, 128]) {
  run(`轴2 离实墨>${R} α≤${cap}`, (t, i, a) => a <= cap && dsOf(t)[i] > R);
}
// 轴3 厚度（侵蚀）：离"非雾"也要 > K
for (const K of [2, 3]) {
  const dfc = new Map();
  run(`轴3 轴2(R2,96)+厚度>${K}`, (t, i, a) => {
    if (!(a <= 96 && dsOf(t)[i] > 2)) return false;
    if (!dfc.has(t.pkg)) dfc.set(t.pkg, bfs(t.n, (j) => !(t.A[j] >= 1 && t.A[j] <= 96)));
    return dfc.get(t.pkg)[i] > K;
  });
}
// 轴4 源色到墨调色板 ΔE > D
let r4d30 = null;
for (const D of [30, 50]) {
  const palc = new Map();
  const r4 = run(`轴4 轴2+源色离墨ΔE>${D}`, (t, i, a) => {
    if (!(a <= 96 && dsOf(t)[i] > 2)) return false;
    if (!palc.has(t.pkg)) {
      const m = new Map();
      for (let j = 0; j < t.n * t.n; j++) if (t.A[j] >= 160) { const c = t.rgb[j]; const k = ((c >> 19) << 10) | (((c >> 8) & 255) >> 3 << 5) | ((c & 255) >> 3); if (!m.has(k)) m.set(k, lab(c)); }
      palc.set(t.pkg, [...m.values()]);
    }
    const L = lab(t.rgb[i]); let best = 1e9; for (const p of palc.get(t.pkg)) best = Math.min(best, de(L, p));
    return best > D;
  });
  if (D === 30) r4d30 = r4;
}
// 轴5 源色彩度 C* < C（背景淡 = 低彩度？）
for (const C of [40, 60]) {
  run(`轴5 轴2+彩度<${C}`, (t, i, a) => {
    if (!(a <= 96 && dsOf(t)[i] > 2)) return false;
    const L = lab(t.rgb[i]); return Math.sqrt(L[1] * L[1] + L[2] * L[2]) < C;
  });
}
// 轴6 雾连通块不碰实墨（_detach_）
const compCache = new Map();
function compsOf(t, cap) {
  const key = t.pkg + ':' + cap;
  if (compCache.has(key)) return compCache.get(key);
  const { A, n } = t; const fog = new Uint8Array(n * n), sol = new Uint8Array(n * n);
  for (let i = 0; i < n * n; i++) { fog[i] = (A[i] >= 1 && A[i] <= cap) ? 1 : 0; sol[i] = A[i] >= 200 ? 1 : 0; }
  const labl = new Int32Array(n * n).fill(-1); const touch = []; let nc = 0;
  for (let s = 0; s < n * n; s++) {
    if (!fog[s] || labl[s] >= 0) continue; const id = nc++; touch[id] = 0; const st = [s]; labl[s] = id;
    while (st.length) {
      const p = st.pop(), x = p % n, y = (p / n) | 0;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const qx = x + dx, qy = y + dy; if (qx < 0 || qy < 0 || qx >= n || qy >= n) continue; const q = qy * n + qx;
        if (sol[q]) touch[id] = 1;
        if (fog[q] && labl[q] < 0) { labl[q] = id; st.push(q); }
      }
    }
  }
  const out = { labl, touch }; compCache.set(key, out); return out;
}
run('轴6 雾块整块不碰实墨', (t, i, a) => { const c = compsOf(t, 199); return !c.touch[c.labl[i]]; });
// 轴7 轴2 + 雾块贴外环 W
for (const W of [0.06, 0.12]) {
  run(`轴7 轴2+雾块贴外环${W}`, (t, i, a) => {
    if (!(a <= 96 && dsOf(t)[i] > 2)) return false;
    const c = compsOf(t, 96); const id = c.labl[i];
    if (c.ring === undefined) c.ring = {};
    const rk = W + ':' + id;
    if (c.ring[rk] === undefined) {
      const ring = Math.max(2, Math.round(t.n * W)); let hit = 0;
      for (let j = 0; j < t.n * t.n; j++) if (c.labl[j] === id) { const x = j % t.n, y = (j / t.n) | 0; if (x < ring || y < ring || x >= t.n - ring || y >= t.n - ring) { hit = 1; break; } }
      c.ring[rk] = hit;
    }
    return !!c.ring[rk];
  });
}
// 轴8 验收洪泛成员（T 扫）：淡标志的外 AA 在任何 T 都连得进背景
for (const T of [2, 8, 24]) {
  const sc = new Map();
  run(`轴8 轴2+验收洪泛T=${T}成员`, (t, i, a) => {
    if (!(a <= 96 && dsOf(t)[i] > 2)) return false;
    if (!sc.has(t.pkg)) sc.set(t.pkg, T === 24 ? t.seen : floodSeen(t.ci, T));
    return !!sc.get(t.pkg)[i];
  });
}
// 轴9 有实墨才开火（"全中间调图标不可分"前提）—— 现量：36 张全有实墨，前提不成立
{
  const noSolid = targets.filter((t) => { let s = 0; for (let i = 0; i < t.n * t.n; i++) if (t.A[i] >= 200) s++; return s === 0; });
  console.log(`轴9 前提「无实墨图标跳过」：无实墨的 ${noSolid.length} 张（${noSolid.map((t) => t.pkg).join(',') || '无'}）⇒ 前提不成立，unionpay 有实墨`);
}
// 轴10 角部环 + 不碰实墨（cainiao 四角专属形状）
run('轴10 不碰实墨+块在外13%环', (t, i, a) => {
  const c = compsOf(t, 199); const id = c.labl[i]; if (c.touch[id]) return false;
  const x = i % t.n, y = (i / t.n) | 0; const ring = Math.round(t.n * 0.13);
  return x < ring || y < ring || x >= t.n - ring || y >= t.n - ring;
});

// 正/负对照自证
const a2 = run('自证·轴2(R2,96) 复跑', (t, i, a) => a <= 96 && dsOf(t)[i] > 2);
if (!a2.fired.some((s) => s.startsWith('com.autonavi.minimap'))) { console.log('!! 正对照没响：autonavi 不在命中栏'); process.exit(1); }
if (!a2.hurt.some((s) => s.startsWith('com.unionpay'))) { console.log('!! 负对照没响：unionpay 不在误判栏'); process.exit(1); }
// 静默列自己的正对照：D30 擦 unionpay 标志内部 / wisdomtree 整字而 bbox 不动 —— 静默列必须看见它们
if (!r4d30 || !r4d30.silent.some((s) => s.startsWith('com.unionpay')) || !r4d30.silent.some((s) => s.startsWith('com.able.wisdomtree'))) {
  console.log('!! 静默列没牙：D30 的 unionpay/wisdomtree 内部擦除没被列出'); process.exit(1);
}
console.log('自证 OK：正对照 autonavi 在命中栏、负对照 unionpay 在误判栏、静默列看见 D30 的内部擦除');

if (SHEET) {
  // 出图：彩色 | 单色before | 轴2 after，按 |before-1| 排序
  function crc32(b) { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
    let crc = 0xffffffff; for (const x of b) crc = t[(crc ^ x) & 255] ^ (crc >>> 8); return (crc ^ 0xffffffff) >>> 0; }
  function chunk(kind, data) { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const k = Buffer.from(kind, 'latin1');
    const c = Buffer.alloc(4); c.writeUInt32BE(crc32(Buffer.concat([k, data]))); return Buffer.concat([len, k, data, c]); }
  function png(w, h, rgba) { const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
    const raw = Buffer.alloc((w * 4 + 1) * h); for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]); }
  const moved = [];
  for (const t of targets) {
    const { A, n, se } = t; const ds = dsOf(t); const B = Int32Array.from(A); let cut = 0;
    for (let i = 0; i < n * n; i++) { const a = A[i]; if (a >= 1 && a <= 96 && ds[i] > 2) { B[i] = 0; cut++; } }
    if (!cut) continue;
    const before = ext(A, n, (a) => a > 8) / se, after = ext(B, n, (a) => a > 8) / se;
    if (Math.abs(after - before) <= 0.004) continue;
    moved.push({ t, B, before, after });
  }
  moved.sort((a, b) => Math.abs(b.before - 1) - Math.abs(a.before - 1));
  const CELL = 140, PAD = 6, cols = 3;
  const W = cols * CELL + (cols + 1) * PAD, H = moved.length * CELL + (moved.length + 1) * PAD;
  const sheet = Buffer.alloc(W * H * 4);
  for (let i = 0; i < W * H; i++) { sheet[i * 4] = 255; sheet[i * 4 + 1] = 255; sheet[i * 4 + 2] = 255; sheet[i * 4 + 3] = 255; }
  const blit = (img, x0, y0, mode) => { const { w, h } = img;
    for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
      const sx = Math.min(w - 1, (x * w / CELL) | 0), sy = Math.min(h - 1, (y * h / CELL) | 0);
      let r, g, b, a;
      if (mode === 'color') { const j = (sy * w + sx) * img.chans; r = img.chans >= 3 ? img.out[j] : img.out[j]; g = img.chans >= 3 ? img.out[j + 1] : img.out[j]; b = img.chans >= 3 ? img.out[j + 2] : img.out[j]; a = img.A[sy * w + sx]; }
      else { const v = mode === 'before' ? img.A[sy * w + sx] : img.B[sy * w + sx]; r = g = b = 0; a = v; }
      const di = ((y0 + y) * W + (x0 + x)) * 4; const al = a / 255;
      sheet[di] = Math.round(r * al + sheet[di] * (1 - al)); sheet[di + 1] = Math.round(g * al + sheet[di + 1] * (1 - al)); sheet[di + 2] = Math.round(b * al + sheet[di + 2] * (1 - al)); sheet[di + 3] = 255;
    } };
  moved.forEach((m, i) => { const y0 = PAD + i * (CELL + PAD);
    blit(m.t.ci, PAD, y0, 'color');
    blit({ w: m.t.n, h: m.t.n, A: m.t.A, B: m.B }, CELL + 2 * PAD, y0, 'before');
    blit({ w: m.t.n, h: m.t.n, A: m.t.A, B: m.B }, 2 * CELL + 3 * PAD, y0, 'after'); });
  fs.writeFileSync(SHEET, png(W, H, sheet));
  console.log(`对照图 ${W}x${H} → ${SHEET}（${moved.length} 行：彩色 | before | 轴2 after）`);
}