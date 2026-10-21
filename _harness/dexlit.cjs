// 数一份 **classes.dex**（先自己用 unzip 解出来）里某串字面量出现几次。
// R8 会改名标识符，但**日志字面量**不会 —— 所以验"改动编进 release 包没有"要 grep 新日志字面量。
// 用法：node dexlit.cjs D:/tmp/classes.dex "是谁=" "场洗" ...
const fs = require('fs');
const d = fs.readFileSync(process.argv[2]);
for (const p of process.argv.slice(3)) {
  const needle = Buffer.from(p, 'utf8');
  let n = 0, i = 0;
  while ((i = d.indexOf(needle, i)) >= 0) { n++; i += needle.length; }
  console.log(String(n).padStart(3) + '  ' + p);
}
