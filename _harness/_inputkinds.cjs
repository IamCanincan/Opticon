// 「单色算法的输入一共有哪几种」—— 拿**抓取语料的 index.tsv**数，不背教科书分类。
// 字段是 `CorpusCapture` 在真机上现读的：cls=最外层 drawable，fg/bg=前景/背景层的类，
// mono=应用自己有没有交单色层，fgAlpha/bgAlpha=那两层有没有透明，bgColor=纯色盘的色。
// 用法：node _inputkinds.cjs [corpus_runtime]
const fs = require('fs');
const dir = process.argv[2] || 'corpus_runtime';
const lines = fs.readFileSync(dir + '/index.tsv', 'utf8').split(/\r?\n/).filter(Boolean);
const head = lines[0].split('\t');
const col = (n) => head.indexOf(n);
const rows = lines.slice(1).map(l => l.split('\t')).filter(r => r.length > 5);
const at = (r, n) => (col(n) >= 0 ? r[col(n)] : '?') || '-';
const cls = (v) => (v || '-').split('.').pop().replace(/Drawable$/, '') || '空';
const tally = (f) => {
  const m = new Map();
  for (const r of rows) { const k = f(r); m.set(k, (m.get(k) || 0) + 1); }
  return [...m].sort((a, b) => b[1] - a[1]);
};
const P = (n) => ((n / rows.length) * 100).toFixed(0) + '%';
console.log(`== ${dir}：${rows.length} 张运行时输入 ==`);
console.log('── 最外层 cls（图标"是什么"）');
tally(r => cls(at(r, 'cls'))).forEach(([k, v]) => console.log(`   ${String(v).padStart(4)}  ${P(v)}  ${k}`));
console.log('── 前景层 fg');
tally(r => cls(at(r, 'fg'))).forEach(([k, v]) => console.log(`   ${String(v).padStart(4)}  ${P(v)}  ${k}`));
console.log('── 背景层 bg（决定我们走"只取前景"还是"整张"）');
tally(r => cls(at(r, 'bg'))).forEach(([k, v]) => console.log(`   ${String(v).padStart(4)}  ${P(v)}  ${k}`));
const adaptive = rows.filter(r => cls(at(r, 'cls')) === 'AdaptiveIcon').length;
const solid = rows.filter(r => cls(at(r, 'bg')) === 'Color').length;
console.log(`── 分支账（全量）：自适应 ${adaptive} 张里背景是纯色盘的 ${solid} 张 → 只取前景；` +
  `其余 ${adaptive - solid} 张吃整张；非自适应 ${rows.length - adaptive} 张整张直接光栅化`);
// ⚠ 上面那三个数**混着"应用已自带单色层"的那批** —— 那批一个像素都不碰，走不到任何分支。
//   真正要紧的是"轮到我们"的那部分怎么分，所以再数一遍。
const ours = rows.filter(r => at(r, 'mono') !== '有');
const oAd = ours.filter(r => cls(at(r, 'cls')) === 'AdaptiveIcon');
const oSolid = oAd.filter(r => cls(at(r, 'bg')) === 'Color').length;
console.log(`── 分支账（只数"没有自带单色层"的 ${ours.length} 张）：` +
  `① 只取前景 ${oSolid} ② 吃整张(flatten) ${oAd.length - oSolid} ③ 非自适应整张 ${ours.length - oAd.length}`);
console.log('── 应用自带的单色层 mono（列里的值就是"有"/"无"两个字，**不许自己判真假**）');
// ⚠ 第一版写成 `v==='-'||v==='0' ? '无':'有'` —— 这一列存的是**中文**，于是 91 张自适应**全被判成"有"**，
//   与代码里那句"mono=有 的那 61 张"当场对不上。凡是把文本值再映射一遍的计数，先抽两行看原值。
tally(r => at(r, 'mono')).forEach(([k, v]) => console.log(`   ${String(v).padStart(4)}  ${P(v)}  ${k}`));
console.log('── fg × bg 交叉（谁和谁配在一起）');
const x = new Map();
for (const r of rows) { const k = cls(at(r, 'fg')) + ' × ' + cls(at(r, 'bg')); x.set(k, (x.get(k) || 0) + 1); }
[...x].sort((a, b) => b[1] - a[1]).slice(0, 12).forEach(([k, v]) => console.log(`   ${String(v).padStart(4)}  ${P(v)}  ${k}`));
