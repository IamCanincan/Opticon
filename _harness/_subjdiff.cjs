// 比对两份 _subj.cjs 的 DUMP 表（修前/修后各一张设备库），列出比值动了的包。
// 用法：node _harness/_subjdiff.cjs <before.tsv> <after.tsv> [阈值=0.005]
const fs = require('fs');
const [bp, ap, th] = process.argv.slice(2);
const rd = (p) => Object.fromEntries(fs.readFileSync(p, 'utf8').trim().split('\n').map((l) => {
  const a = l.split('\t');
  return [a[0], a.slice(1, 5).map(Number).concat(a[5])]; // [比值, 彩色主体幅, 洪泛后占, 单色幅, 组件名]
}));
const A = rd(bp), B = rd(ap);
const T = +(th || 0.005);
const ch = Object.keys(A).filter((k) => B[k] && Math.abs(A[k][0] - B[k][0]) > T)
  .map((k) => [k, A[k][0], B[k][0], A[k][3], B[k][3], A[k][4] === B[k][4] ? '' : `  ⚠选的行换了 ${A[k][4]} → ${B[k][4]}`])
  .sort((x, y) => Math.abs(y[1] - y[2]) - Math.abs(x[1] - x[2]));
console.log(`变了 ${ch.length} / ${Object.keys(A).length} 个包（比值动过 ${T}）`);
for (const c of ch) console.log(`  ${c[0].padEnd(30)} ${c[1].toFixed(3)} → ${c[2].toFixed(3)}   单色幅 ${c[3].toFixed(3)} → ${c[4].toFixed(3)}${c[5]}`);
const stat = (o) => {
  const x = Object.values(o).map((r) => r[0]).sort((a, b) => a - b);
  const q = (p) => x[Math.min(x.length - 1, Math.round(p * (x.length - 1)))];
  return `n=${x.length} 中位 ${q(0.5).toFixed(3)} p90 ${q(0.9).toFixed(3)}  >1.10:${x.filter((t) => t > 1.1).length}  <0.85:${x.filter((t) => t < 0.85).length}`;
};
console.log('修前 ' + stat(A));
console.log('修后 ' + stat(B));
