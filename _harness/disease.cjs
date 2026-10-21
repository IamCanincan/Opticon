// 从全量 mono_icon（256² ALPHA_8）里按**数值特征**找三类病，不靠看图、不靠旧编号。
// 用法：node _harness/disease.cjs <mono目录> [--selftest]
//   目录里要有 manifest.tsv（dumpmono.cjs 生成）
//
// ⚠ **2026-10-20 改版，因为上一版把两件不存在的事报成了病**（台账 §6 第 2 条「翻译 #104 等『第二块底』
// 那把刀」就是它造的）：
//  ① **α<64 不算墨。** 旧版 `ink` 数的是 `v>0`，于是"一圈 α≈8 的装饰羽片"和"一个实心主体"
//    在 B/C 两把尺里长得一模一样 —— pdd `one_click_search` 就是这么被报成"主体几乎被擦空只剩虚线框"的，
//    看图（`gray2png.cjs`）才知道主体（放大镜）是**满墨**的，那"虚线框"是源里一圈浅色羽片被交成了
//    几乎看不见的淡影。这一条改的是**分母**，不是阈值：淡件照旧单独数（`淡`），只是不再冒充"墨"。
//  ② **一块实心里被挖空的字，是设计不是病。** A 类旧版只看"最大实心块 ≥25% 且盒子填充率 ≥0.85"，
//    而 translate 上屏那一张（`4e6cba7f416f`，10-19 19:54 / 10-19 23:07 / 10-20 12:16 三趟逐字节相同）
//    正是主人 2026-10-12 点名的样子：「右半边全黑、只有"文"是镂空的」。所以 A 类现在**先看这块实心
//    里有没有被封住的空气**（从画布四边洪泛走不到的 α=0 格），有就不算"整块黑"。
//    阳性对照在 `--selftest` 里：把 translate 那张的镂空填平，A 必须重新开火 —— 不响就是这把尺没牙。
const fs = require('fs'), path = require('path');
const dir = process.argv[2] || 'D:/tmp/mono_now';
const W = 256, N = W * W;
/** 低于这个 α 的格子在屏幕上看不见，不算"墨"（2026-10-20；与仓里 `淡=1..63` 那一档同界）。 */
const FLOOR = 64;
/** 实心块里封住的空气占到画布的这么多%，就判"这是被挖过字的卡"，不进 A。 */
const CARVE_MIN = 3;


const man = fs.readFileSync(path.join(dir, 'manifest.tsv'), 'utf8').split(/\r?\n/).slice(1).filter(Boolean);
const file2comp = new Map();
for (const l of man) {
  const [comp, file] = l.split('\t');
  if (!file2comp.has(file)) file2comp.set(file, []);
  file2comp.get(file).push(comp);
}

/** 4 邻接连通块（只踩 mask 为真的格）。返回 [{area, x0,y0,x1,y1}] */
function comps(mask) {
  const lab = new Int32Array(N).fill(-1);
  const out = [];
  const q = new Int32Array(N);
  for (let s = 0; s < N; s++) {
    if (!mask[s] || lab[s] >= 0) continue;
    let hd = 0, tl = 0; q[tl++] = s; lab[s] = out.length;
    let area = 0, x0 = W, y0 = W, x1 = -1, y1 = -1;
    while (hd < tl) {
      const i = q[hd++]; area++;
      const y = (i / W) | 0, x = i - y * W;
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
      if (x > 0 && mask[i - 1] && lab[i - 1] < 0) { lab[i - 1] = lab[s]; q[tl++] = i - 1; }
      if (x < W - 1 && mask[i + 1] && lab[i + 1] < 0) { lab[i + 1] = lab[s]; q[tl++] = i + 1; }
      if (y > 0 && mask[i - W] && lab[i - W] < 0) { lab[i - W] = lab[s]; q[tl++] = i - W; }
      if (y < W - 1 && mask[i + W] && lab[i + W] < 0) { lab[i + W] = lab[s]; q[tl++] = i + W; }
    }
    out.push({ area, x0, y0, x1, y1 });
  }
  return out;
}

/** 从画布四边洪泛（4 邻接）走不到的**空气**格占全画布的百分比 —— 就是"被这块墨围起来的洞"。
 *  ⚠ 口径写死在这里，因为「围合」这个词在本仓有过两个定义、同一张差 74 个点：
 *  这一把量的是**交付那张 256²**、只踩 α===0 的格、种子是画布四边。不是源图，不是可见圆。 */
function enclosedAir(a) {
  const seen = new Uint8Array(N), q = new Int32Array(N);
  let tl = 0;
  const push = (i) => { if (!seen[i] && a[i] === 0) { seen[i] = 1; q[tl++] = i; } };
  for (let i = 0; i < W; i++) { push(i); push((W - 1) * W + i); push(i * W); push(i * W + W - 1); }
  for (let hd = 0; hd < tl; hd++) {
    const i = q[hd], y = (i / W) | 0, x = i - y * W;
    if (x > 0) push(i - 1);
    if (x < W - 1) push(i + 1);
    if (y > 0) push(i - W);
    if (y < W - 1) push(i + W);
  }
  let n = 0;
  for (let i = 0; i < N; i++) if (a[i] === 0 && !seen[i]) n++;
  return n * 100 / N;
}

/** A 类那三条判据单独抽出来，好让 `--selftest` 拿**造出来的图**验这把尺有没有牙。 */
function aHit(a) {
  const mSol = new Uint8Array(N);
  for (let i = 0; i < N; i++) if (a[i] >= 224) mSol[i] = 1;
  const cs = comps(mSol);
  if (!cs.length) return { fire: false, big: 0, fill: 0, carve: 0 };
  const b = cs.reduce((m, c) => c.area > m.area ? c : m, cs[0]);
  const big = b.area * 100 / N;
  const fill = b.area / ((b.x1 - b.x0 + 1) * (b.y1 - b.y0 + 1));
  const carve = enclosedAir(a);
  return { fire: big >= 25 && fill >= 0.85 && carve < CARVE_MIN, big, fill, carve };
}

if (process.argv.includes('--selftest')) {
  // 阳性对照：右半一整块实心 —— 填平的这张**必须**被 A 抓到（抓不到就是这把尺没牙）
  const blank = Buffer.alloc(N);
  for (let y = 0; y < W; y++) for (let x = W / 2; x < W; x++) blank[y * W + x] = 255;
  // 同一张，中间挖掉一个字（就是 translate 上屏那种）—— 这张**不许**再被 A 抓
  const carved = Buffer.from(blank);
  for (let y = 64; y < 192; y++) for (let x = 150; x < 220; x++) carved[y * W + x] = 0;
  const h0 = aHit(blank), h1 = aHit(carved);
  let bad = 0;
  const chk = (name, got, want) => { if (got !== want) { bad++; console.log(`  ✗ ${name} → ${got}（要 ${want}）`); } else console.log(`  ✓ ${name} → ${got}`); };
  console.log('== A 类那把尺的自证（阳性对照 + 负对照）==');
  chk('一整块实心（没挖字）必须开火', h0.fire, true);
  chk('同一块挖出一个字必须不开火', h1.fire, false);
  chk('挖空的空气确实被量到（>3%）', h1.carve > CARVE_MIN, true);
  console.log(bad ? `!! ${bad} 条不成立` : 'OK 尺有牙：填平就红、挖开就不红');
  process.exit(bad ? 1 : 0);
}

const rows = [];
for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith('.gray')) continue;
  const a = fs.readFileSync(path.join(dir, f));
  if (a.length !== N) continue;
  let ink = 0, solid = 0, mid = 0, faint = 0, mass = 0;
  const mInk = new Uint8Array(N), mSol = new Uint8Array(N), mVis = new Uint8Array(N);
  for (let i = 0; i < N; i++) {
    const v = a[i];
    if (v > 0) { ink++; mInk[i] = 1; if (v >= 224) { solid++; mSol[i] = 1; } else if (v >= 64) mid++; else faint++; }
    if (v >= FLOOR) mVis[i] = 1;
    mass += v;
  }
  if (ink === 0) continue;
  const ci = comps(mInk);
  const cv = comps(mVis);          // 看得见的墨（α≥FLOOR）—— B 类改用它
  const cs = comps(mSol);
  const carvePct = enclosedAir(a); // 被实心围住的空气 = 挖出来的字

  const bigInk = ci.reduce((m, c) => c.area > m.area ? c : m, ci[0]);
  const bigSol = cs.length ? cs.reduce((m, c) => c.area > m.area ? c : m, cs[0]) : null;
  const smallInk = ci.filter(c => c.area < 40).reduce((s, c) => s + c.area, 0);
  const bboxFill = bigInk.area / ((bigInk.x1 - bigInk.x0 + 1) * (bigInk.y1 - bigInk.y0 + 1));
  // 贴边墨：画布外圈 10px 内的墨占全部墨
  let edgeInk = 0;
  for (let i = 0; i < N; i++) {
    if (!mInk[i]) continue;
    const y = (i / W) | 0, x = i - y * W;
    if (x < 10 || y < 10 || x >= W - 10 || y >= W - 10) edgeInk++;
  }
  let solBboxFill = 0, solTouchRight = false;
  if (bigSol) {
    solBboxFill = bigSol.area / ((bigSol.x1 - bigSol.x0 + 1) * (bigSol.y1 - bigSol.y0 + 1));
    solTouchRight = bigSol.x1 >= W - 2;
  }
  let vis = 0; for (let i = 0; i < N; i++) if (mVis[i]) vis++;
  const bigVis = cv.length ? cv.reduce((m, c) => c.area > m.area ? c : m, cv[0]) : { area: 0 };
  rows.push({
    f, comp: file2comp.get(f.replace(/\.gray$/, '')) || ['?'],
    inkPct: ink * 100 / N, solPct: solid * 100 / N, midPct: mid * 100 / N, faintPct: faint * 100 / N,
    visPct: vis * 100 / N, nVisComp: cv.length, bigVisPct: bigVis.area * 100 / N, carvePct,
    mean: mass / N, nComp: ci.length, bigInkPct: bigInk.area * 100 / N, bboxFill,
    smallShare: smallInk / ink, edgeShare: edgeInk / ink,
    bigSolPct: bigSol ? bigSol.area * 100 / N : 0, solBboxFill, solTouchRight,
    solW: bigSol ? bigSol.x1 - bigSol.x0 + 1 : 0, solH: bigSol ? bigSol.y1 - bigSol.y0 + 1 : 0,
    solX0: bigSol ? bigSol.x0 : 0,
  });
}

const fmt = (r) => `${r.comp[0].split('/')[0].padEnd(34)} 可见墨${r.visPct.toFixed(1).padStart(5)} 实${r.solPct.toFixed(1).padStart(5)} 中${r.midPct.toFixed(1).padStart(5)} 淡${r.faintPct.toFixed(1).padStart(5)} 块${String(r.nComp).padStart(3)} 大块${r.bigInkPct.toFixed(1).padStart(5)} 盒填${r.bboxFill.toFixed(2)} 碎${(r.smallShare * 100).toFixed(0).padStart(3)}% 贴边${(r.edgeShare * 100).toFixed(0).padStart(3)}% 挖${r.carvePct.toFixed(1).padStart(4)}  ${r.f}`;

console.log(`分母：α<${FLOOR} 不算墨（淡件仍单独数）；A 类另看"挖"= 被实心围住的空气占画布%（≥${CARVE_MIN} 判"这是挖过字的卡"，不进 A）\n`);

console.log('=== A 整块黑：最大实心块 ≥25% 画布 且 盒子填充率 ≥0.85 且 里面没有挖空的字 ===');
rows.filter(r => r.bigSolPct >= 25 && r.solBboxFill >= 0.85 && r.carvePct < CARVE_MIN)
  .sort((a, b) => b.bigSolPct - a.bigSolPct).slice(0, 12)
  .forEach(r => console.log(`实块${r.bigSolPct.toFixed(1).padStart(5)}% 盒填${r.solBboxFill.toFixed(2)} ${r.solW}x${r.solH}@x${r.solX0}${r.solTouchRight ? '贴右' : ''}  ${fmt(r)}`));

const suppressed = rows.filter(r => r.bigSolPct >= 25 && r.solBboxFill >= 0.85 && r.carvePct >= CARVE_MIN);
if (suppressed.length) {
  console.log(`   被"挖空"这一条拦掉的 ${suppressed.length} 张（旧版会报成病）：`);
  suppressed.sort((a, b) => b.carvePct - a.carvePct).slice(0, 12)
    .forEach(r => console.log(`   挖${r.carvePct.toFixed(1).padStart(4)}% 实块${r.bigSolPct.toFixed(1).padStart(5)}%  ${r.comp[0]}`));
}

console.log(`\n=== B 只剩虚线框：可见墨(α≥${FLOOR}) <8% 且 可见块数 ≥25 且 最大可见块 <12% 画布 ===`);
rows.filter(r => r.visPct < 8 && r.nVisComp >= 25 && r.bigVisPct < 12)
  .sort((a, b) => a.visPct - b.visPct).slice(0, 15)
  .forEach(r => console.log(fmt(r)));
const bOld = rows.filter(r => r.inkPct < 8 && r.nComp >= 25 && r.bigInkPct < 12);
console.log(`   （旧口径 α>0 就算墨：${bOld.length} 张 —— 差的就是"一圈看不见的淡羽片"被数成了主体）`);

console.log('\n=== C 灰噪点：淡+中 ≥ 墨的 60% 且 块数 ≥40（碎件多） ===');
rows.filter(r => (r.midPct + r.faintPct) >= r.inkPct * 0.6 && r.nComp >= 40)
  .sort((a, b) => b.nComp - a.nComp).slice(0, 15)
  .forEach(r => console.log(fmt(r)));

console.log('\n共 ' + rows.length + ' 张独立掩码');
