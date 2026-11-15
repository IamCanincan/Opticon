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
/** 颜色族（与 `ObjKt`/`_srcmeter` 同一档粗度：每通道 /24）—— 用来认"那一整块底" */
const fam = (r, g, b) => ((r / 24) | 0) * 625 + ((g / 24) | 0) * 25 + ((b / 24) | 0);

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
  return { w, h, A, out, chans };
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
// ⚠ **上界必须按子类算**：前景层本来就铺满画面（幅面 ≥0.99）的图标，剪影铺满就是 1.000 ——
//   拿"平台整体 p90=0.949"去判它们，会把一批**正确**的图标报成带外（上一版就是这么报了 11 张）。
//   所以这里把两子类各自摊，界也各自取。
const sub = (x) => (x[2] >= 0.99 ? '满幅前景' : '有留白前景');
const bands = {};
for (const [pkg, r, fg, mo, isOwn] of out) {
  const k = sub([pkg, r, fg, mo, isOwn]) + (isOwn ? '·自带' : '·我们');
  (bands[k] = bands[k] || []).push(r);
}
console.log('  按子类分开摊（界只在**同类**里取）：');
for (const [k, v] of Object.entries(bands)) {
  console.log(`    ${k.padEnd(16)} ${String(v.length).padStart(3)} 张  p10 ${q(v, 0.1).toFixed(3)}  中位 ${q(v, 0.5).toFixed(3)}  p90 ${q(v, 0.9).toFixed(3)}`);
}
const lo = q(own, 0.1), hi = q(own, 0.9);
console.log(`\n带 = 自带那组 p10~p90 = ${lo.toFixed(3)} ~ ${hi.toFixed(3)}。我们合成的里落在带外的：`);
const bad = out.filter((x) => !x[4] && (x[1] < lo || (x[1] > hi && sub(x) === '有留白前景')));
if (!bad.length) console.log('    （0 张）');
for (const [pkg, r, fg, mo] of bad) console.log(`    ${r.toFixed(3)}  ${pkg.padEnd(34)} 前景层 ${fg.toFixed(3)}  单色 ${mo.toFixed(3)}`);

// ── **第二条腿：老式图标**（整张位图就是图案，没有前景层可比）──
//   这一族没有 `_fg.png` ⇒ 上面那条尺**一个都看不到**（现量：库里老式·我们合成 37 个包，有前景层的 0 个），
//   而今晚那一刀（`交 = scale ÷ 窗`）恰恰全落在这一族 —— 一条看不见已修之事的闸不算闸。
//   这一族的分母就是**它自己的彩色**：屏上那张彩色 blob 的墨外接框（`min(R,G,B)<235`，与 `_pairshot` 同一条），
//   验收线是 1.000（主人那句"一样的几何结构"在这一族里字面成立：整张位图 = 图案 = 剪影该覆盖的东西）。
const LEG_TOL = 0.05;
const legacy = []; let skippedNonPkg = 0;
{
  const rowsAll = new DatabaseSync(dbp, { readOnly: true }).prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all();
  const picked = new Map();
  for (const r of rowsAll) { const p = r.c.split('/')[0]; if (r.i && r.m && !picked.has(p)) picked.set(p, r); }
  for (const [pkg, r] of picked) {
    // 库里还有一类行不是应用：`custom-widget` 这种**没有点号的假包名**（桌面小组件占位）。
    // 它们会带着自己的 mono_icon 进来，然后被当成"某个应用"判 —— 直接跳过并计数。
    if (!pkg.includes('.')) { skippedNonPkg++; continue; }
    const e = ev.get(pkg);
    if (!e || e.mono === '有' || e.cls.includes('Adaptive')) continue;      // 只看"老式·我们合成"
    const ci = decode(Buffer.from(r.i));
    if (!ci) continue;
    const { w, h, chans, out } = ci;
    // ⚠ 库里的彩色 blob **不保证是 3 通道**（灰度 PNG 的 colorType=0/4，chans=1/2）——
    //   上一版直接读 `out[i+2]`，越界拿到 undefined，`Math.min(...)` 变 NaN，整条腿当场崩。
    const rgb = (i) => chans === 1 ? [out[i], out[i], out[i]] : chans === 2 ? [out[i], out[i], out[i]]
      : chans === 4 ? [out[i], out[i], out[i]] : chans === 6 ? [out[i], out[i + 1], out[i + 2]]
        : [out[i], out[i + 1], out[i + 2]];
    // 🔴 **分母不能是"非白即墨"**（2026-11-15 看图抓的）：老式图标常常是**一整块有颜色的底 + 浅色图案**
    //   （米家绿底白 M、工行红底白标、银联红底白字…），"非白"把整块底都算成图案 ⇒
    //   彩色幅 = 1.000，剪影当然只剩 0.4~0.5 —— 那 12 张里 9 张是这么被冤枉的。
    //   改法与第一条腿同源：**去掉那块整片底色**（边框里最常见的**近不透明**色族），剩下的才是图案。
    const m = Math.max(1, (w * 0.12) | 0), cnt = new Map();
    const bump = (x, y) => {
      const i = (y * w + x) * chans;
      if ((chans === 4 && out[i + 1] < 250) || (chans === 6 && out[i + 3] < 250)) return;  // 透明格不算底色
      const [r, g, b] = rgb(i); const k = fam(r, g, b); cnt.set(k, (cnt.get(k) || 0) + 1);
    };
    for (let x = m; x < w - m; x++) { bump(x, m); bump(x, m + 1); bump(x, h - m - 1); bump(x, h - m - 2); }
    for (let y = m; y < h - m; y++) { bump(m, y); bump(m + 1, y); bump(w - m - 1, y); bump(w - m - 2, y); }
    let bgf = null, bc = -1;
    for (const [k, c] of cnt) if (c > bc) { bc = c; bgf = k; }
    // 那块"底"要真的占画幅一半以上才算底；否则（白底 logo 那种）不剥，免得把图案剥没
    const shareBg = bc / (w * h);
    const useBg = shareBg >= 0.5 ? bgf : null;
    let l = w, t = h, rr = -1, bb = -1;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * chans;
      if ((chans === 4 && out[i + 1] < 64) || (chans === 6 && out[i + 3] < 64)) continue;   // 透明格不是墨
      const [r, g, b] = rgb(i);
      if (useBg != null ? fam(r, g, b) === useBg : Math.min(r, g, b) >= 235) continue;
      if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y;
    }
    if (rr < 0) continue;
    const cw = Math.max(rr - l + 1, bb - t + 1) / w;
    const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length));
    if (n * n !== mb.length || !cw) continue;
    let ml = n, mt = n, mr = -1, mbb = -1;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
      if (mb[y * n + x] <= 8) continue;
      if (x < ml) ml = x; if (x > mr) mr = x; if (y < mt) mt = y; if (y > mbb) mbb = y;
    }
    if (mr < 0) continue;
    const mw = Math.max(mr - ml + 1, mbb - mt + 1) / n;
    // 🔴 **验收线按结构分两档**（2026-11-15 看图 + 剥底失败之后改的）：
    //   源图**几乎铺满不透明**（≥0.9）的老式图标 = 应用自己画了一整块彩底（米家绿底白 M、工行红底白标、
    //   银联、交管 12123…），剪影只留标志**是对的** ⇒ 对它没有"该是几"的外部答案，只能钉地板（不许退步）。
    //   源图带透明边的（白底 logo 那种），彩色那栏就是图案本体 ⇒ 1.000±容差 才是它的验收线。
    //   为什么不再试"剥掉底色"：那些底是**渐变**的，没有任何色族占得到画幅一半，剥不动；
    //   硬设一个占比阈值就是把"我猜它是底"写进判据 —— 本仓那一族（自造尺当裁判）。
    const srcPng = (() => {
      const cands = fs.readdirSync(dir).filter((x) => x.startsWith(pkg + '_') &&
        !x.endsWith('_fg.png') && !x.endsWith('_bg.png') && !x.endsWith('_mono.png') &&
        !x.endsWith('_model.png') && !x.endsWith('_modelbg.png') && !x.includes('preview'));
      return cands.length ? `${dir}/${cands[0]}` : null;
    })();
    let full = NaN;
    if (srcPng) {
      const si = decode(fs.readFileSync(srcPng));
      if (si) { let o = 0; for (let i = 0; i < si.w * si.h; i++) if (si.A[i] >= 250) o++; full = o / (si.w * si.h); }
    }
    const tier = full >= 0.70 ? '满幅彩底' : '有透明边';
    // 0.70 不是拍的：桌面把图标裁成圆角方形，面积≈内切圆 = π/4 ≈ **0.785** 画幅；
    //   "一整块底"= 底铺满那个形状 ⇒ 不透明占比 ≥ 0.9×0.785 ≈ 0.706。
    //   上一版用 0.9，于是圆角方形的底（webapk 橙底、nyarin 绿底）被错分进"有透明边"，
    //   拿 1.000 去要求它们 —— 那正是我今晚被拽回来两次的同一个错（把剪影该只留标志当成故障）。
    legacy.push([pkg, mw / cw, cw, mw, tier, full]);
  }
  legacy.sort((a, b) => a[1] - b[1]);
  const v = legacy.map((x) => x[1]);
  const edge = legacy.filter((x) => x[4] === '有透明边'), tile = legacy.filter((x) => x[4] === '满幅彩底');
  const offEdge = edge.filter((x) => Math.abs(x[1] - 1) > LEG_TOL), offTile = tile.map((x) => x[1]).length ? q(tile.map((x) => x[1]), 0.5) : NaN;
  console.log(`\n第二条腿（老式：单色 ÷ 它自己的彩色），按结构分两档：${legacy.length} 个包`);
  console.log(`  （另跳过 ${skippedNonPkg} 行不是应用：包名里没有点号，如 custom-widget 那种小组件占位）`);
  console.log(`  p10 ${q(v, 0.1).toFixed(3)}  中位 ${q(v, 0.5).toFixed(3)}  p90 ${q(v, 0.9).toFixed(3)}`);
  console.log(`  **有透明边**（彩色那栏就是图案本体，验收线 1.000±${LEG_TOL}）：${edge.length} 个，不达标 ${offEdge.length} 个`);
  for (const [pkg, r, cw, mw] of offEdge) console.log(`      ${r.toFixed(3)}  ${pkg.padEnd(30)} 彩色 ${cw.toFixed(3)}  单色 ${mw.toFixed(3)}`);
  console.log(`  **满幅彩底**（应用自己画了整块底，剪影只留标志=对的 ⇒ **没有外部答案，只钉地板**）：${tile.length} 个，中位 ${Number.isFinite(offTile) ? offTile.toFixed(3) : '-'}（地板见 pin）`);
  if (process.argv.includes('--tiers')) {
    console.log('  逐张分档依据（不透明占画幅几成 —— 0.70 那条线 = 0.9×π/4，圆角方形铺满底）：');
    for (const [pkg, r, , , tr, fl] of legacy.sort((a, b) => (b[5] || 0) - (a[5] || 0))) {
      console.log(`    ${(fl * 100).toFixed(1).padStart(5)}%  ${tr}  ${pkg.padEnd(32)} 比 ${r.toFixed(3)}` + (Number.isNaN(fl) ? '   ⚠ 源图没找到，档位是猜的' : ''));
    }
  }
}

// ── 闸模式（`--pin=<文件>`）：钉**三件事**，任何一件退步就非零退出 ──
//   ① 覆盖：配上对的张数（分母缩了就是台子坏了，不是"没问题"）
//   ② 我们每一子类的**中位**：主人那句验收线是"越接近 1.000 越好" ⇒ **只许往 1.0 走，不许退**
//   ③ 带外张数：只许降不许涨
//   界本身不钉死数（它是从"自带那组"现算的），钉的是"我们相对它的位置"。
const PIN = (process.argv.find((a) => a.startsWith('--pin=')) || '').slice(6);
if (PIN) {
  const now = [
    `covered=${out.length}`,
    ...Object.entries(bands).filter(([, v]) => v.length >= 3)
      .map(([k, v]) => `med_${k.replace(/[·]/g, '_')}=${q(v, 0.5).toFixed(3)}`),
    `outband=${bad.length}`,
    `leg_covered=${legacy.length}`,
    `leg_edge_off=${legacy.filter((x) => x[4] === '有透明边' && Math.abs(x[1] - 1) > LEG_TOL).length}`,
    `leg_tile_med=${(() => { const t = legacy.filter((x) => x[4] === '满幅彩底').map((x) => x[1]); return t.length ? q(t, 0.5).toFixed(3) : 'NaN'; })()}`,
  ].join('\t');
  if (process.argv.includes('--pinwrite')) {
    fs.writeFileSync(PIN, now + '\n');
    console.log(`\n   已认账：${PIN} = ${now}`);
    process.exit(0);
  }
  if (!fs.existsSync(PIN)) { console.log(`\n!! 没有 ${PIN} —— 这道闸没基线，不算过（先 --pinwrite 认一次）`); process.exit(2); }
  const base = new Map(fs.readFileSync(PIN, 'utf8').replace(/\r/g, '').trim().split('\t').map((kv) => kv.split('=')));
  const worse = [];
  const num = (k) => (base.has(k) ? +base.get(k) : NaN);
  if (out.length < num('covered')) worse.push(`覆盖 ${num('covered')} → ${out.length} 张（分母被动过 ⇒ 后面的比对都不成立）`);
  if (bad.length > num('outband')) worse.push(`带外 ${num('outband')} → ${bad.length} 张`);
  // **第二条腿的判法（分档之后）**：
  //   有透明边那档 —— 验收线就是 1.000，不达标张数**只许降**；
  //   满幅彩底那档 —— 没有外部答案，只钉地板：中位**不许往下退**（往下退就是今晚那种"剪影只剩一半"）。
  if (legacy.length < num('leg_covered')) worse.push(`老式覆盖 ${num('leg_covered')} → ${legacy.length} 个包（这一族的腿断了，今晚那一刀就没人看着）`);
  const edgeOffNow = legacy.filter((x) => x[4] === '有透明边' && Math.abs(x[1] - 1) > LEG_TOL).length;
  if (edgeOffNow > num('leg_edge_off')) worse.push(`有透明边那档不达标 ${num('leg_edge_off')} → ${edgeOffNow} 个（这一档的验收线是 1.000）`);
  const tileNow = legacy.filter((x) => x[4] === '满幅彩底').map((x) => x[1]);
  const tm = tileNow.length ? q(tileNow, 0.5) : NaN, tb = num('leg_tile_med');
  if (Number.isFinite(tm) && Number.isFinite(tb) && tm < tb - 1e-9) {
    worse.push(`满幅彩底那档中位 ${tb.toFixed(3)} → ${tm.toFixed(3)}（地板只许升）`);
  }
  for (const [k, v] of Object.entries(bands)) {
    if (v.length < 3) continue;
    // ⚠ **只判"我们"那几子类**：`自带` 那两组是用来算界的，不是我们的成绩 ——
    //   把它们也当判据，抓取目录换一代就会假红（本仓那一族：闸红了但红的原因与代码无关）。
    if (!/·我们$/.test(k)) continue;
    const key = `med_${k.replace(/[·]/g, '_')}`, b = num(key);
    if (Number.isFinite(b)) {
      const cur = q(v, 0.5);
      // 主人那句验收线是"越接近 1.000 越好" ⇒ 退步 = 中位变小
      if (cur < b - 1e-9) worse.push(`${k} 中位 ${b.toFixed(3)} → ${cur.toFixed(3)}（离 1.000 更远）`);
    } else worse.push(`${k} 在基线里没有（新增子类，先 --pinwrite 认账）`);
  }
  if (worse.length) { console.log(`\n!! 剪影幅面账退步（${worse.length} 项）：`); for (const w of worse) console.log('   ' + w); process.exit(1); }
  console.log(`\nSIL-GATE OK —— 覆盖 ${out.length}、带外 ${bad.length}、各子类中位不退（基线 ${fs.readFileSync(PIN, 'utf8').trim()}）`);
}
