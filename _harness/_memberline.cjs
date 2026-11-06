// 第 24 闸「两档退族一致账」的判定本体（**只有一份**，`regress.sh` 与语义变异台共用）。
//
// 为什么是这一道：2026-11-06 那笔密度债的根，是"哪些格子算横幅那一族"借了**选族**的互异线 18。
// 今天换成 45 之后，驾考宝典那张在 384 与 768 上第一次给出同一个答案。而"同一个答案"这件事
// 以前只有 15 分钟一轮的四档全扫能发现 —— 那台太贵，贵到没人会在改一行常数后跑它。
// 这一道只跑两档、专问退族，一分钟级，并且**必须看见至少一张在两档都开火**：
// 否则它就退化成一把永远不响的尺（本仓为"没有候选的闸与没有牙的闸输出完全一样"记过账）。
//
// 用法：node _memberline.cjs <装机档输出> <对照档输出> [--lo=384 --hi=768]
const fs = require('fs');
const A = process.argv[2], B = process.argv[3];
if (!A || !B) { console.log('用法: node _memberline.cjs <低档输出> <高档输出>'); process.exit(2); }
const lo = (process.argv.find((a) => a.startsWith('--lo=')) || '--lo=384').substring(5);
const hi = (process.argv.find((a) => a.startsWith('--hi=')) || '--hi=768').substring(5);

function parse(f) {
  const m = new Map();
  let n = null;
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    const h = /^--- (\S+)/.exec(l);
    if (h) { n = h[1]; continue; }
    if (!n) continue;
    const d = /退族=(-?\d+)/.exec(l);
    if (!d) continue;
    const bf = /框内=([0-9.]+)/.exec(l);
    m.set(n, { drop: +d[1], boxin: bf ? +bf[1] : NaN });
    n = null;
  }
  return m;
}
const a = parse(A), b = parse(B);
// **分母自证**：两边都有的图标数。0 张 = 台子或语料坏了，不许读成"一格都没漂"。
const both = [...a.keys()].filter((k) => b.has(k));
if (!both.length) {
  console.log(`ML n=0 !! 两份输出没有一张同名（低档 ${a.size}、高档 ${b.size}）—— 这一闸没跑，不算过`);
  process.exit(1);
}
const diff = both.filter((k) => a.get(k).drop !== b.get(k).drop);
const firedBoth = both.filter((k) => a.get(k).drop > 0 && b.get(k).drop > 0);
for (const k of diff)
  console.log(`   漂：${k} 退族 ${a.get(k).drop}(${lo}) ↔ ${b.get(k).drop}(${hi}) 框内 ${a.get(k).boxin} ↔ ${b.get(k).boxin}`);
console.log(`ML n=${both.length} 漂=${diff.length} 两档都开火=${firedBoth.length}（${lo}/${hi}）`);
if (diff.length) { console.log('!! 同一张图两档给的退族不同 —— 这一刀的判据跟着光栅化漂'); process.exit(1); }
if (!firedBoth.length) {
  console.log('!! 两档都开火的张数 = 0：这一闸今天没有候选（与"没有牙"长得一样），不许读成通过');
  process.exit(1);
}
