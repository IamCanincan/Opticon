// 我们交出去的那批掩码（宿主 cacheDir 的 .a8）浓度分布 —— 一次看清"这一族到底交的是几张能看的图"。
// 用法：node _harness/_a8dist.cjs <a8目录> [边长=384]
const fs = require('fs'), path = require('path');
const dir0 = process.argv[2], edge = +(process.argv[3] || 384);
const dir = fs.statSync(dir0).isDirectory() && fs.readdirSync(dir0).some((f) => fs.statSync(path.join(dir0, f)).isDirectory())
  ? path.join(dir0, fs.readdirSync(dir0).find((d) => fs.statSync(path.join(dir0, d)).isDirectory())) : dir0;
const rows = [];
for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith(`_${edge}.a8`)) continue;
  const b = fs.readFileSync(path.join(dir, f));
  const a = b.length - edge * edge === 8 ? b.subarray(8) : b;      // 去 8 字节头
  let ink = 0, sol = 0, m = 0;
  for (const v of a) { if (v > 0) ink++; if (v >= 255) sol++; m += v; }
  rows.push({ k: f.split('_')[0], ink: ink * 100 / a.length, sol: sol * 100 / a.length, mean: m / a.length });
}
rows.sort((x, y) => y.mean - x.mean);
const band = (lo, hi) => rows.filter((r) => r.mean >= lo && r.mean < hi).length;
console.log(`${rows.length} 张我们交出去的 ${edge}² 掩码，按均α 分档：`);
console.log(`  均α <2 = ${band(0, 2)}   2~10 = ${band(2, 10)}   10~50 = ${band(10, 50)}   ≥50 = ${band(50, 256)}`);
console.log(`  全实(α=255) 一格都没有的：${rows.filter((r) => r.sol === 0).length} 张`);
console.log('最淡的 8 张：'); rows.slice(-8).reverse().forEach((r) => console.log(`  ${r.k}  有墨${r.ink.toFixed(1).padStart(5)} 全实${r.sol.toFixed(1).padStart(5)} 均α${r.mean.toFixed(2)}`));
console.log('最浓的 3 张：'); rows.slice(0, 3).forEach((r) => console.log(`  ${r.k}  有墨${r.ink.toFixed(1).padStart(5)} 全实${r.sol.toFixed(1).padStart(5)} 均α${r.mean.toFixed(2)}`));
