// 冷路径逐图标 A/B：三跑基线 vs 三跑候选，同时报 min-of-3 与 median-of-3。
//
// 为什么两种都报：单趟总时间有 ±1s（~3%）环境抖动，min-of-3 会被一次"幸运快跑"带偏
// （bilibili 767/1067/1132、zjol 1287/1366/1484 都是现场），median-of-3 抗单点。
// 结论只认**两种口径同向**；只动一种的，记成噪声。逐图标比的是同名文件，不做模糊匹配。
//
// 用法：node _perfab.cjs <base1> <base2> <base3> <new1> <new2> <new3>
// 输入就是 MaskBench 的日志（`--- <name>  <N>ms` 一行一张）。
const fs = require("fs");
const load = (files) => {
  const m = new Map();
  for (const f of files) for (const L of fs.readFileSync(f, "utf8").split(/\r?\n/)) {
    const h = L.match(/^--- (\S+)\s+(\d+)ms/);
    if (h) { if (!m.has(h[1])) m.set(h[1], []); m.get(h[1]).push(+h[2]); }
  }
  return m;
};
const min = (a) => Math.min(...a);
const med = (a) => { const s = a.slice().sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const q = (a) => { const s = a.slice().sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const tot = (a) => a.reduce((x, y) => x + y, 0);
const b = load(process.argv.slice(2, 5)), n = load(process.argv.slice(5, 8));
const rows = [...b.keys()].filter((k) => n.has(k)).map((k) => [k, b.get(k), n.get(k)]);
for (const [label, pick] of [["min-of-3", min], ["median-of-3", med]]) {
  const bv = rows.map((r) => pick(r[1])), nv = rows.map((r) => pick(r[2]));
  console.log(`${label.padEnd(11)} 基线 median=${q(bv)}ms 总量=${(tot(bv) / 1000).toFixed(2)}s` +
    `   候选 median=${q(nv)}ms 总量=${(tot(nv) / 1000).toFixed(2)}s`);
}
// 逐图标差值按 median-of-3（抗单点），并给出方向计数
const d = rows.map((r) => [r[0], med(r[1]) - med(r[2])]);
console.log(`逐图标(median-of-3)：faster=${d.filter((x) => x[1] > 0).length} slower=${d.filter((x) => x[1] < 0).length}`);
console.log("变快最多：");
for (const [k, v] of d.sort((a, b) => b[1] - a[1]).slice(0, 6)) console.log(`  ${String(v).padStart(5)}ms ${k.slice(0, 48)}`);
console.log("变慢最多：");
for (const [k, v] of d.sort((a, b) => a[1] - b[1]).slice(0, 6)) console.log(`  ${String(v).padStart(5)}ms ${k.slice(0, 48)}`);
