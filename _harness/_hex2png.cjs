// sqlite3 quote(blob) 的 X'…' 十六进制 → 原始字节（icon 列 blob 是 PNG）
// 用法：node _hex2png.cjs <in.hex> <out.png>
const fs = require('fs');
const t = fs.readFileSync(process.argv[2], 'latin1').trim();
const m = t.match(/^X'([0-9A-Fa-f]*)'$/);
if (!m) { console.log('not a quote(blob): ' + t.slice(0, 40)); process.exit(1); }
fs.writeFileSync(process.argv[3], Buffer.from(m[1], 'hex'));
console.log('ok ' + m[1].length / 2 + ' bytes');