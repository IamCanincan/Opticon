// 2026-10-23「主体内部那块该不该留空」第九轮：**新特征**的分离度与代价模拟。
//
// 前八轮（KNOWN-LIMITS）：六个数（面积/触边/离边/离空/明度/饱和/围合）在内部区域 AUC 0.51–0.63；
// 面积/暗部那两把尺两头死。这一轮加的八个特征**全部运行时可得**（不碰设计师那层）：
//   源α / 质心 / 细长 / 环Δ / 盘同 / 盘Δ / 族占 / 长宽
//
// 跑法：`node _harness/_regionfeat.cjs bench/regions_feat.tsv yard_model/index.tsv`
// 输出：① 量程与单特征 AUC（全量 / 内部 / 甲类 / 乙类）；② 留一图标逻辑回归（旧七 / 新八 / 全部）；
//      ③ 置换负控制；④ 「近盘色且当前是雾」的区域级代价模拟（甲/乙分开）。
//
// ⚠ 三条自证，缺一条这台的数就不许引用：
//   ① 解析后的旧特征 AUC 必须复现 KNOWN-LIMITS 的内部四行（0.538/0.552/0.513/0.631）；
//   ② 置换标签的 pooled AUC 必须 ≈0.5（本轮 10 次；第一版 50 次纯随机 0.492）；
//   ③ 甲/乙与类别的计数必须与 `YardKt` 的汇总一致（62 对 / 645 块 / 256 涂墨 / 233 留空）。
const fs = require('fs');

const HF = process.argv[2] || 'bench/regions_feat.tsv';
const IX = process.argv[3] || 'yard_model/index.tsv';
const L = fs.readFileSync(HF, 'utf8').trim().split(/\r?\n/);
const h = L[0].split('\t');
if (h.length < 19) { console.log('!! 表只有 ' + h.length + ' 列 —— 不是带新特征的那一份'); process.exit(2); }
const rows = L.slice(1).map(l => { const c = l.split('\t'); const o = {}; h.forEach((k, i) => o[k] = c[i]); o.pkg = c[0]; return o; })
  .filter(o => o.reg !== '噪点')
  .map(o => { for (const k of h) if (k !== 'pkg' && k !== 'reg') o[k] = parseFloat(o[k]); return o; });
const types = {};
for (const l of fs.readFileSync(IX, 'utf8').trim().split(/\r?\n/).slice(1)) {
  const c = l.split('\t');
  types[c[0]] = (c[5].includes('Adaptive') && c[7] === 'ColorDrawable') ? '甲' : '乙';
}
for (const r of rows) r.jia = types[r.pkg] || '?';

const name = f => f;
const ALL = ['面积%', '触边', '离边', '离空', '明度', '饱和', '围合', '源α', '质心', '细长', '环Δ', '盘同', '盘Δ', '族占', '长宽'];
const OLD = ['面积%', '触边', '离边', '离空', '明度', '饱和', '围合'];
const NEW = ['源α', '质心', '细长', '环Δ', '盘同', '盘Δ', '族占', '长宽'];
const inner = rows.filter(r => r['离空'] >= 12);
const ink = inner.filter(r => r['设计师α'] >= 200);
const empty = inner.filter(r => r['设计师α'] <= 40);
const mid = inner.filter(r => r['设计师α'] > 40 && r['设计师α'] < 200);
const pairs = new Set(rows.map(r => r.pkg)).size;

function auc(f, A, B) {
  let u = 0;
  for (const a of A) for (const b of B) {
    const av = a[f], bv = b[f];
    if (av > bv) u++; else if (av === bv) u += 0.5;
  }
  return u / (A.length * B.length);
}
const med = a => { const v = a.slice().sort((x, y) => x - y); return v.length ? v[v.length >> 1] : NaN; };
const fmt = x => Number.isFinite(x) ? x.toFixed(3) : '—';

console.log(`区域 ${rows.length} 块（${pairs} 对）；内部（离空≥12）${inner.length} = 涂墨 ${ink.length} / 留空 ${empty.length} / 中间调 ${mid.length}`);
console.log(`甲类 ${rows.filter(r => r.jia === '甲').length} 块 / 乙类 ${rows.filter(r => r.jia === '乙').length} 块；甲类内部 ${inner.filter(r => r.jia === '甲').length}`);

// 自证①：旧特征在内部子集上的 AUC 必须复现台账
console.log('\n### 自证①：旧七个数在内部子集的 AUC（台账：0.538 / 0.552 / 0.513 / 0.631）');
for (const f of OLD) console.log('   ' + f.padEnd(5) + ' ' + fmt(auc(f, ink, empty)));

// 量程 + 单特征 AUC（全量 / 内部 / 甲 / 乙）
console.log('\n### 单特征（内部子集；>0.5 越大越像"涂墨"，<0.5 反向；n 是有效值条数）');
for (const f of NEW) {
  const A = ink.filter(r => r[f] >= 0), B = empty.filter(r => r[f] >= 0);
  const A2 = A.filter(r => r.jia === '甲'), B2 = B.filter(r => r.jia === '甲');
  const A3 = A.filter(r => r.jia === '乙'), B3 = B.filter(r => r.jia === '乙');
  const v = rows.filter(r => r[f] >= 0).map(r => r[f]).sort((a, b) => a - b);
  const line = [f.padEnd(4), 'n=' + (A.length + '/' + B.length),
    '内部=' + (A.length && B.length ? fmt(auc(f, A, B)) : '—'),
    '甲=' + (A2.length && B2.length ? fmt(auc(f, A2, B2)) : '—'),
    '乙=' + (A3.length && B3.length ? fmt(auc(f, A3, B3)) : '—'),
    '量程 ' + (v.length ? v[0].toFixed(1) + '..' + v[v.length - 1].toFixed(1) : '—')];
  console.log('   ' + line.join('  '));
}

// 留一图标逻辑回归（标准化 + L2 + 平衡类权重）。pooled AUC 用所有 held-out 预测一起算。
function fitPredict(train, test, feats) {
  const mu = {}, sd = {};
  for (const f of feats) {
    const v = train.map(r => r[f]);
    mu[f] = v.reduce((a, b) => a + b, 0) / v.length;
    sd[f] = Math.sqrt(v.reduce((a, b) => a + (b - mu[f]) ** 2, 0) / v.length) || 1;
  }
  const X = train.map(r => feats.map(f => (r[f] - mu[f]) / sd[f]));
  const y = train.map(r => r['设计师α'] >= 200 ? 1 : 0);
  const pos = y.reduce((a, b) => a + b, 0), neg = y.length - pos;
  const w = y.map(v => v ? (y.length / (2 * pos)) : (y.length / (2 * neg)));
  let b = 0; const wv = feats.map(() => 0);
  const lr = 0.15, l2 = 0.01, iters = 400;
  for (let it = 0; it < iters; it++) {
    let gb = 0; const g = feats.map(() => 0);
    for (let i = 0; i < X.length; i++) {
      let z = b; for (let j = 0; j < feats.length; j++) z += wv[j] * X[i][j];
      const p = 1 / (1 + Math.exp(-z)), e = (p - y[i]) * w[i];
      gb += e; for (let j = 0; j < feats.length; j++) g[j] += e * X[i][j];
    }
    b -= lr * gb / X.length;
    for (let j = 0; j < feats.length; j++) wv[j] -= lr * (g[j] / X.length + l2 * wv[j]);
  }
  return test.map(r => {
    const x = feats.map(f => (r[f] - mu[f]) / sd[f]);
    let z = b; for (let j = 0; j < feats.length; j++) z += wv[j] * x[j];
    return { p: 1 / (1 + Math.exp(-z)), y: r['设计师α'] >= 200 ? 1 : 0, pkg: r.pkg };
  });
}
function groupCV(data, feats, shuffle = false) {
  const by = {};
  for (const r of data) (by[r.pkg] = by[r.pkg] || []).push(r);
  const labels = data.map(r => r['设计师α'] >= 200 ? 1 : 0);
  if (shuffle) { for (let i = labels.length - 1; i > 0; i--) { const j = (Math.random() * (i + 1)) | 0; [labels[i], labels[j]] = [labels[j], labels[i]]; } }
  const shuffled = data.map((r, i) => ({ ...r, '设计师α': labels[i] ? 240 : 5 }));
  const held = [];
  for (const p of Object.keys(by)) {
    const test = shuffled.filter(r => r.pkg === p);
    const train = shuffled.filter(r => r.pkg !== p);
    held.push(...fitPredict(train, test, feats));
  }
  const A = held.filter(r => r.y === 1), B = held.filter(r => r.y === 0);
  return A.length && B.length ? auc('p', A, B) : NaN;
}
const labeled = inner.filter(r => r['设计师α'] >= 200 || r['设计师α'] <= 40);
console.log('\n### 留一图标（group by pkg）逻辑回归：pooled AUC（内部已标注 ' + labeled.length + ' 块 / ' + new Set(labeled.map(r => r.pkg)).size + ' 图标）');
for (const [tag, feats] of [['旧七', OLD], ['新八', NEW], ['全部', ALL]]) {
  const scores = [];
  for (let k = 0; k < 10; k++) scores.push(groupCV(labeled, feats, true));
  const real = groupCV(labeled, feats, false);
  const perm = scores.filter(Number.isFinite);
  console.log('   ' + tag + '  AUC=' + fmt(real) + '   置换10次 mean=' + fmt(perm.reduce((a, b) => a + b, 0) / perm.length) + ' max=' + fmt(Math.max(...perm)));
}

// 代价模拟：近盘色 + 当前是雾 → 归零。按甲/乙分开（乙类在上一轮是净负）。
console.log('\n### 区域级代价模拟：「盘Δ < t 且 我们α < fog」→ 归零；单位 = 面积%×α 的 |Δα| 变化');
for (const t of ['甲', '乙']) {
  const sub = inner.filter(r => r.jia === t);
  const out = [];
  for (const fog of [200, 255]) for (const cut of [10, 15, 20, 25, 30]) {
    let gain = 0, cost = 0, gN = 0, cN = 0; const by = {};
    for (const r of sub) {
      if (!(r['盘Δ'] < cut && r['我们α'] < fog)) continue;
      const d = (Math.abs(r['我们α'] - r['设计师α']) - Math.abs(0 - r['设计师α'])) * r['面积%'];
      if (d > 0) { gain += d; gN++; } else if (d < 0) { cost += -d; cN++; }
      by[r.pkg] = (by[r.pkg] || 0) + d;
    }
    const bad = Object.entries(by).filter(([, v]) => v < -5).length;
    out.push(`${t} fog<${fog} t=${cut}: 净${(gain - cost).toFixed(0)} (好${gN}/坏${cN}, 受伤图标${bad})`);
  }
  console.log('   ' + out.join('\n   '));
}

// 甲类内部标注块的盘Δ全表：阈值的空档就在这张表里
console.log('\n### 甲类内部已标注块的 盘Δ@我们α（ink / empty 分开，阈值只能落在这两族之间的空档里）');
for (const [tag, rs] of [['ink', ink.filter(r => r.jia === '甲')], ['empty', empty.filter(r => r.jia === '甲')]]) {
  console.log('   ' + tag + ': ' + rs.map(r => r['盘Δ'].toFixed(0) + '@' + r['我们α']).join(' '));
}
