// 变异测 `_xedge.cjs`：把**一个**图标的 192 那一行 `几何=` 改掉，守卫必须点名报红。
// 不这么测一次，"翻档=0"这三个字只是这台子从没开火的另一种写法。
// 用法：node _xedgemut.cjs
const fs = require('fs');
const src = fs.readFileSync('_wc2.txt', 'utf8').split('\n');
// 找一个"两档都算过"的图标（与守卫同一口径：有包名、edge=192 且 384 都有行）
const seen = new Map();
src.forEach((l, i) => {
  if (!/Mono: diag#/.test(l) || !l.includes('几何=')) return;
  const m = l.match(/键=\S+ (\S+)\s/); if (!m || !m[1].includes('/')) return;
  const e = (l.match(/ edge=(\d+)/) || [])[1]; if (!e) return;
  (seen.get(m[1]) || seen.set(m[1], new Map()).get(m[1])).set(e, i);
});
const hit = [...seen].find(([, m]) => m.has('192') && m.has('384'));
if (!hit) { console.log('!! 找不到双档图标，变异没造出来'); process.exit(2); }
const [name, idx] = hit;
const i = idx.get('192');
const before = src[i];
src[i] = before.replace(/几何=(\S+)/, '几何=放1.99×');
if (src[i] === before) { console.log('!! 这一行没有 `几何=` 字段，变异没落地'); process.exit(2); }
fs.writeFileSync('_xemut.txt', src.join('\n'));
console.log(`已造变异：${name} 的 edge=192 那一行 几何 ${(before.match(/几何=(\S+)/) || [])[1]} → 放1.99×`);
