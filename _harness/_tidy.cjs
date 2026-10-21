const fs = require('fs');
const A = 'app/src/main/kotlin/com/iamcanincan/opticon/';
let n = 0;
function edit(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    if (!t.includes(a)) { console.log('!! 锚点没对上 @' + path + '：' + a.slice(0, 44)); process.exit(1); }
    t = t.replace(a, b); n++;
  }
  fs.writeFileSync(path, t);
}
edit(A + 'ui/Common.kt', [[
` * 仓库地址：顶栏的源码按钮和「关于」页的跳转都是它。`,
` * 仓库地址：「关于」页的跳转用它。顶栏那颗源码按钮用的是 \`OpticonApp\` 里另存的一份，
 * 原因见下面那条 ⚠（同包同名会撞）—— 改地址要**两处一起改**。`]]);
edit(A + 'ui/module/ModuleScreen.kt', [[
`/** 桌面包名（类原生 / Pixel 两套），发广播点名用。 */\n\n`, '']]);
edit(A + 'ui/icon/IconScreen.kt', [[
` * 「图标」页：只管裁圆这件事 —— 总开关，以及会影响它的那些系统行为说明。`,
` * 「图标」页：这台手机**桌面图标**上的两件事 —— 裁圆，以及未适配图标的单色层合成 ——
 * 各自的开关，加上会影响它们的系统行为说明。`],
[`  // 只放**裁圆自己**的注意事项。模块级的那些（框架 daemon 启动时机之类）
  // 属于「模块」页的启用步骤，别在这里再抄一遍。`,
`  // 只放**这两个功能自己**的注意事项。模块级的那些（框架 daemon 启动时机之类）
  // 属于「模块」页的启用步骤，别在这里再抄一遍。`]]);
edit(A + 'ui/notify/NotifyScreen.kt', [[
`        SectionLabel(stringResource(R.string.notify_section_behavior))
`, '']]);
edit('app/src/main/res/values/strings.xml', [[`    <string name="notify_section_behavior">行为</string>\n`, '']]);
let r = fs.readFileSync('README.md', 'utf8');
const pairs = [
['| **图标** | 裁圆总开关、效果示意、只跟裁圆有关的注意事项、运维按钮（清图标缓存 / 重启进程） |\n| **通知** | 通知总开关、怎么确认生效 |',
 '| **图标** | 裁圆开关、未适配图标的单色层开关、效果示意、注意事项、运维按钮（清图标缓存 / 重启进程） |\n| **通知** | 通知总开关、作用域、怎么确认生效 |'],
['│   ├── IconBitmap             位图的取与压单色（通知那半边，桌面已不再共用）',
 '│   ├── IconBitmap             位图的取与压单色（通知那半边，桌面已不再共用）'],
['│   ├── MonochromeMask         一个浓度量 `dep(a,β,ΔE)` 应用两遍：形状模型（离线）与极性判据\n│   └── CircleIconDrawable     圆形 drawable（自适应图标 + 圆形遮罩）',
 '│   ├── MonochromeMask         一个浓度量 `dep(a,β,ΔE)` 应用两遍：形状模型（离线）与极性判据\n' +
 '│   ├── MonoDump               诊断通道：把它实际吃进去的那张位图经 logcat 带回来重放\n' +
 '│   └── CircleIconDrawable     圆形 drawable（自适应图标 + 圆形遮罩）'],
['├── runtime/                   两个功能共用',
 '├── debug/                     ← 诊断，不参与任何判断\n' +
 '│   └── CorpusCapture            adb 触发的语料抓取（真机图标 → 判据台），见「离线判据台」一节\n' +
 '├── runtime/                   两个功能共用'],
];
for (const [a, b] of pairs) {
  if (!r.includes(a)) { console.log('!! README 锚点没对上：' + a.slice(0, 40)); process.exit(1); }
  r = r.replace(a, b); n++;
}
fs.writeFileSync('README.md', r);
console.log('改了', n, '处');
