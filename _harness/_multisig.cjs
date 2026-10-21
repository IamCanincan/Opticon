// 找"同一个图标在盘上有几张**不同的真张**"—— 只在 MonoDump 那一支（`*-src.png`）里找。
//
// 为什么只在这一支：`rec_model` 与 `plate_model` 是两轮抓取，但 78 个同名张**sig 全同**
// （抓取通道自己按 384 重画，两轮当然一样）→ 那一支量不到"桌面按两种 intrinsic 各画一遍"这件事。
// 只有 MonoDump 带回来的是**桌面递进来的那一张**，同一张 drawable 的两次画法才会是两个 sig。
//
// ⚠ 不做任何判据计算，只回答"有没有对手"。没有对手的比较就不必跑。
// 用法：node _multisig.cjs [目录…]（默认那批 dump 目录）
const fs = require('fs');

const DIRS = (process.argv.length > 2 ? process.argv.slice(2) :
  ['rec3u', 'rec10', 'og', 'dev1001', 'captured_live', 'rec_blank', 'bench/au3u', 'bench/pf/src', 'bench/au_blank'])
  .filter(d => fs.existsSync(d));

// 名字归一：去掉 `iconN_` 前缀、去掉 12 位 hex、去掉 `-src`。
// ⚠ 本仓吃过两次"两台台子归一化不一样 → join 静默丢一半"，所以这里**把两边的键数都打出来**，
//   并且把 sig 单独留着 —— 键相同而 sig 不同才是"同一个图标的两张真张"。
const sigOf = f => (f.match(/[0-9a-f]{12}/) || [])[0];
const keyOf = f => f.replace(/^icon\d+_/, '').replace(/[0-9a-f]{12}/g, '').replace(/-?src\.png$/, '.png');

const groups = new Map();
let files = 0, noSig = 0;
for (const d of DIRS) {
  for (const f of fs.readdirSync(d)) {
    if (!f.endsWith('.png') || !/-src\.png$/.test(f)) continue;
    const sig = sigOf(f);
    if (!sig) { noSig++; continue; }
    files++;
    const k = keyOf(f);
    if (!groups.has(k)) groups.set(k, new Map());
    const g = groups.get(k);
    if (!g.has(sig)) g.set(sig, []);
    g.get(sig).push(d + '/' + f);
  }
}

console.log(`== dump 支的多真张普查：扫 ${files} 张（另有 ${noSig} 张名字里没有 sig），图标名 ${groups.size} 个 ==`);
const multi = [...groups].filter(([, g]) => g.size >= 2);
console.log(`  **同一个图标在盘上有 ≥2 个不同 sig 的：${multi.length} 个**`);
const hist = {};
for (const [, g] of groups) hist[g.size] = (hist[g.size] || 0) + 1;
console.log('  每个图标各有几张真不同的张：', Object.entries(hist).map(([k, v]) => `${k}张×${v}个`).join('  '));

// 只看那些"同一张 drawable、两个画法"的：同一目录内出现两个 sig 的最可疑
// （同一次抓取里同一包给出两张 = 桌面真的按两种尺寸各画了一遍）
const sameDir = multi.filter(([, g]) => [...g.values()].some(paths => new Set(paths.map(p => p.split('/')[0])).size > 1
  || paths.length > 1));
console.log('\n  同一次抓取里就给了两张的（最干净的一组对照）：');
for (const [k, g] of multi.slice(0, 400)) {
  const paths = [...g.values()].flat();
  const perDir = {};
  for (const p of paths) { const d = p.split('/').slice(0, -1).join('/') || '.'; perDir[d] = (perDir[d] || 0) + 1; }
  if (Object.values(perDir).some(n => n > 1))
    console.log(`    ${k.padEnd(50)} ` + Object.entries(perDir).map(([d, n]) => `${n}@${d}`).join(' '));
}
if (sameDir.length === 0) console.log('    （无）');

console.log('\n  跨抓取轮的（sig 不同 = 两次真张，可当对照，但两轮之间桌面可能也换了画法）：');
for (const [k, g] of multi.slice(0, 14))
  console.log(`    ${k.padEnd(48)} ${g.size} 个 sig: ${[...g].map(([s, p]) => s + '(' + p.length + ')').join(' ')}`);
