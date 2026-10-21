// 把一张 256² ALPHA_8 掩码打成 64 行 ASCII，直接读形状（不依赖看图）。
// 用法：node _harness/a8ascii.cjs <文件.gray 或 mono_now 里的文件名> [宽]
//   4×4 均值降采样；字符：' '=0  '.'=1-63  '+'=64-159  '#'=160-223  '@'=224-255
const fs = require('fs'), path = require('path');
const arg = process.argv[2];
const cols = parseInt(process.argv[3] || '64', 10);
let p = arg;
if (!p.endsWith('.gray')) p = path.join('D:/tmp/mono_now', arg + '.gray');
const a = fs.readFileSync(p);
const W = 256, N = W * W;
if (a.length !== N) { console.log('长度 ' + a.length + '，不是 256²'); process.exit(1); }
const S = Math.floor(W / cols);
const ch = (v) => v === 0 ? ' ' : v < 64 ? '.' : v < 160 ? '+' : v < 224 ? '#' : '@';
console.log('# ' + path.basename(p) + '  ' + cols + '×' + cols);
for (let gy = 0; gy < cols; gy++) {
  let line = '';
  for (let gx = 0; gx < cols; gx++) {
    let s = 0, n = 0;
    for (let y = gy * S; y < (gy + 1) * S; y++) for (let x = gx * S; x < (gx + 1) * S; x++) { s += a[y * W + x]; n++; }
    line += ch(Math.round(s / n));
  }
  console.log('|' + line + '|');
}
