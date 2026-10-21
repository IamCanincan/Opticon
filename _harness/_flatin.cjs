// 汇总 `MaskBench --flatin`：**没有对岸的那本掉色账**（真上屏那 64 张里，源是平色、我们却铺了一道坡的块）。
// 跑法：`sh run.sh harness.MaskBenchKt --edge=384 --flatin rec_model > bench/flatin.txt` 然后 `node _flatin.cjs`。
const fs = require('fs');
const L = fs.readFileSync(process.argv[2] || 'bench/flatin.txt', 'utf8').split(/\r?\n/);
const R = [];
let cur = null;
for (const l of L) {
  if (l.startsWith('--- ')) { if (cur) R.push(cur); cur = { n: l.slice(4).split(/\s/)[0] }; continue; }
  const m = l.match(/〔平块〕平块=(\d+)\/(\d+)格 坡=(\d+)块\/(\d+)格 抬到255多=(\d+)/);
  if (m && cur) { cur.b = +m[1]; cur.bc = +m[2]; cur.s = +m[3]; cur.sc = +m[4]; cur.raise = +m[5]; cur.done = 1; }
}
if (cur) R.push(cur);
const D = R.filter(r => r.done);
console.log(`${D.length} 张（都有 〔平块〕 行）`);
console.log(`  有坡块的张数 = ${D.filter(r => r.s > 0).length}　坡块合计 ${D.reduce((a, r) => a + r.s, 0)} 块 / ${D.reduce((a, r) => a + r.sc, 0)} 格`);
console.log(`  全抬到 255 会多出的墨当量 = ${D.reduce((a, r) => a + r.raise, 0).toLocaleString()}（255×格 计）`);
const g = D.filter(r => r.s > 0).sort((a, b) => b.raise - a.raise);
console.log('\n### 按"抬到 255 能多换多少墨"排（这就是那把刀的射程，不是它的收益）');
for (const r of g.slice(0, 14))
  console.log(`   ${String(r.s).padStart(2)} 块 / ${String(r.sc).padStart(6)} 格  墨当量 +${r.raise.toLocaleString()}  = ${(r.raise / 255).toFixed(0)} 格实心  ${r.n.slice(0, 46)}`);
console.log(`\n其余 ${Math.max(g.length - 14, 0)} 张在表尾；一张坡块都没有的 ${D.filter(r => r.s === 0).length} 张`);
