const fs = require('fs');
const p = process.argv[2];
const L = fs.readFileSync(p, 'utf8').split('\n');
// 1-based 行号：把「算法那一大块 + @param plateIsLayer 那一块」从 build 头上搬到 buildOnce 头上。
const big = L.slice(655, 694);                      // 656..694
if (!big[0].trim().startsWith('/**') || !big[big.length - 1].trim().startsWith('*/'))
  throw new Error('边界不对: ' + big[0] + ' / ' + big[big.length - 1]);
const rest = L.slice(0, 655).concat(L.slice(694));  // 去掉那 39 行
// 我写给 buildOnce 的那段短 doc 让位给原文（它本来就该挂在真正算一张的那个函数上）。
const shortDoc = rest.findIndex(l => l.includes('真正算一张。[forceRef]'));
if (shortDoc < 0) throw new Error('找不到 buildOnce 的短 doc');
const start = rest[shortDoc].trim() === '/**' ? shortDoc : shortDoc - 1;
let end = start; while (rest[end].trim() !== '*/') end++;
const note = ['  /** 真正算一张。[forceRef] = true 时**不许**走满幅那一支 —— 选支台要拿它算另一条的成品。 */'];
rest.splice(start, end - start + 1, ...big, ...note);
fs.writeFileSync(p, rest.join('\n'));
console.log('搬完：build 头上只剩选支那一段');
