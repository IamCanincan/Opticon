// 一次性：桌面 `app_icons.db` 里那张 256² 的 mono_icon，到底是不是我们交付的那张 384² 掩码。
// 两种可能都要试：① 等比缩小 384→256（双线性/最近邻）；② 中心裁一块 256（不缩）。
// 用法：node _harness/_dbvsmon.cjs <我们的.a8> <db> <componentName 子串>
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');

const [a8p, dbp, needle] = process.argv.slice(2);
const b = fs.readFileSync(a8p);
const edge = Math.round(Math.sqrt(b.length - 9));
const m = (y, x) => b[9 + y * edge + x];
const d = new DatabaseSync(dbp, { readOnly: true });
const rows = d.prepare('SELECT componentName c, mono_icon m FROM icons').all()
  .filter((r) => r.c.includes(needle) && r.m);
const toBuf = (arr) => Buffer.from(arr);
const md5 = (buf) => crypto.createHash('md5').update(buf).digest('hex').slice(0, 12);
console.log(`我们的掩码 ${a8p.replace(/^.*\//, '')} edge=${edge} md5=${md5(b.subarray(9))}`);
for (const r of rows) {
  const blob = Buffer.from(r.m);
  const side = Math.round(Math.sqrt(blob.length));
  console.log(`库里 ${r.c} ${side}² md5=${md5(blob)}`);
  // ① 中心裁（不缩）
  if (side < edge) {
    const off = Math.floor((edge - side) / 2);
    const crop = Buffer.alloc(side * side);
    for (let y = 0; y < side; y++) for (let x = 0; x < side; x++) crop[y * side + x] = m(y + off, x + off);
    console.log(`   中心裁 ${side}²（offset ${off}）md5=${md5(crop)} 与库里${crop.equals(blob) ? ' **逐字节相同**' : ' 不同'}`);
  }
  // ② 最近邻缩小
  const near = Buffer.alloc(side * side);
  for (let y = 0; y < side; y++) for (let x = 0; x < side; x++) {
    near[y * side + x] = m(Math.min(edge - 1, Math.floor((y + 0.5) * edge / side)), Math.min(edge - 1, Math.floor((x + 0.5) * edge / side)));
  }
  console.log(`   最近邻缩 ${side}² md5=${md5(near)} 与库里${near.equals(blob) ? ' **逐字节相同**' : ' 不同'}`);
  // 各自墨框
  const box = (get, n) => {
    let l = n, t = n, rr = -1, bb = -1;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { if (get(y, x) <= 8) continue; if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y; }
    return rr < 0 ? '空' : `${rr - l + 1}x${bb - t + 1} @${n}`;
  };
  console.log(`   墨框：我们 ${box(m, edge)}  库里 ${box((y, x) => blob[y * side + x], side)}`);
}
