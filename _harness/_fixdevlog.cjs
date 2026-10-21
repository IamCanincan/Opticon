// 修我自己刚才用多行替换写坏的一段：`_notifyguard` 那条子项被留在了文件末尾，
// 而它的父项（SystemUI 类名前缀那条「遗留」）被整段替换掉了。
// 做法：把子项原样搬回 `## 18:20` 之前，并补回父项三行。**只搬不写**，不重打内容。
const fs = require('fs');
const P = 'docs/devlog/2026-10-16.md';
let L = fs.readFileSync(P, 'utf8').split('\n');
const start = L.findIndex(l => l.includes('✅ **当天就把这条兜底做成了判据**'));
if (start < 0) { console.log('!! 没找到那条子项，不动文件'); process.exit(1); }
// 子项 = 从这一行到文件末尾（它是最后一段），但要保留末尾空行
let end = L.length;
while (end - 1 > start && L[end - 1].trim() === '') end--;
const block = L.slice(start, end);
if (!block.every(l => /^\s{2}/.test(l) || l.trim() === '')) console.log('⚠ 子项里有不以两空格开头的行，搬完看一眼');
L = [...L.slice(0, start), ...L.slice(end)];           // 从末尾摘掉
const h = L.findIndex(l => l.startsWith('## 18:20'));
if (h < 0) { console.log('!! 没找到 18:20 那个小节标题，文件已改一半，看 git diff'); process.exit(1); }
const parent = [
  '- ⚪ **遗留**：第 3 层是对 SystemUI **类名前缀**的依赖 —— OTA 改名会让通知行**静默不裁**，',
  '  现象与"功能没生效"一模一样；兜底是那一行"挡住"会点名是谁在取（README「管哪些界面（射程）」记了这条查法）。',
  '  版本账不变：仍按主人「版本到时候再说」，不开新发布版。',
];
// 父项原本挂在「改成按调用方分」那一节末尾：找 18:20 之前最后一个非空行插进去
let at = h - 1;
while (at > 0 && L[at].trim() === '') at--;
at++;
L = [...L.slice(0, at), ...parent, '', ...L.slice(at, h), ...block, '', ...L.slice(h)];
fs.writeFileSync(P, L.join('\n'));
console.log(`搬好了：子项 ${block.length} 行 → 第 ${at} 行后；18:20 小节仍在其后`);
