const fs = require('fs');
const p = 'README.md';
let t = fs.readFileSync(p, 'utf8');
const subs = [
['| **通知** | 通知总开关、图标模式（系统黑白 / 彩色桌面图标）、怎么确认生效 |',
 '| **通知** | 通知总开关、怎么确认生效 |'],
['options: circle=true notify=true mode=monochrome keepColor=false preserveTinted=true (source=provider)',
 'options: circle=true notify=true preserveTinted=true (source=provider)'],
['**通知小图标**：出现 `patched <包名> 2->1 via=monochrome` 就是替换成功了\n（图标类型从「资源」变成「位图」，`via=` 后面是本次实际用的模式）。',
 '**通知小图标**：出现 `patched <包名> 2->1` 就是替换成功了（图标类型从「资源」变成「位图」）。'],
['### 两种模式\n\n| 模式 | 做法 | 适合 |\n|---|---|---|\n| **系统黑白通知**（默认） | 把 App 自己给的小图标压成单色剪影，交给系统按主题着色 | 想和其它通知风格统一 |\n| **彩色桌面图标** | 换成 App 在桌面上那个图标，颜色原样保留 | 想让通知一眼认出是谁 |\n\n两个模式的区别只在「未适配的图标怎么处理」；已经是单色 / 灰度的图标，两种模式下都不动。',
 '### 只做黑白\n\n把 App 自己给的小图标压成**单色剪影**，交给系统按主题着色 —— 只此一条，没有可选模式。\n\n⚠ 曾经是两种（另一种「换成桌面彩色图标、原色保留」），2026-09-30 按主人的要求删掉：\n那条要成立得钩进 SystemUI 的着色链里改图标颜色，是整条功能里最依赖系统内部实现的一部分，\n而实际没人用。已经是单色 / 灰度的图标仍然**一律不动**。'],
['### 改模式为什么不生效', '### 改完为什么不生效'],
['  本应用的数据只有 `ModulePrefs` 的两个布尔 + 一个模式枚举，以及「检查更新」的缓存，',
 '  本应用的数据只有 `ModulePrefs` 里那几个开关，以及「检查更新」的缓存，'],
];
let n = 0;
for (const [a, b] of subs) {
  if (!t.includes(a)) { console.log('!! 锚点没对上：' + a.slice(0, 46)); process.exit(1); }
  t = t.replace(a, b); n++;
}
fs.writeFileSync(p, t);
console.log('README 改了', n, '处');
