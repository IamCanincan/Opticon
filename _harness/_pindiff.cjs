// 一次性：比两份逐张 pin（`_shape.cjs --iouwrite` 那种「包\t数」），报改了哪几行、往哪个方向。
// 用法：node _harness/_pindiff.cjs <旧.tsv> <新.tsv> [字段序号=1]
const fs = require('fs');
const rd = (f, col) => {
  const m = new Map();
  fs.readFileSync(f, 'utf8').replace(/\r/g, '').split('\n').filter((l) => l.trim()).forEach((l) => {
    const c = l.split('\t'); m.set(c[0], +c[col]);
  });
  return m;
};
const [fa, fb, colS] = process.argv.slice(2);
const col = colS === undefined ? 1 : +colS;
const a = rd(fa, col), b = rd(fb, col);
let up = 0, down = 0, same = 0;
const list = [];
for (const [k, v] of b) {
  const o = a.get(k);
  if (o === undefined) { list.push(`新增 ${k} = ${v}`); continue; }
  if (Math.abs(v - o) < 1e-9) { same++; continue; }
  if (v > o) { up++; list.push(`变好 ${k.padEnd(42)} ${o} → ${v}`); }
  else { down++; list.push(`变差 ${k.padEnd(42)} ${o} → ${v}`); }
}
for (const k of a.keys()) if (!b.has(k)) list.push(`只在旧里有 ${k}`);
console.log(`改了 ${up + down} 行（变好 ${up}、变差 ${down}、一字未动 ${same}），共 ${b.size} 行`);
for (const l of list.sort()) console.log('   ' + l);
