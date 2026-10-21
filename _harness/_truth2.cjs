const fs = require('fs');
let t = fs.readFileSync('README.md', 'utf8');
const pairs = [
[`配套还会阻止系统把替换后的图标重新染回单色（挂钩 \`IconManager#setIcon\`、
\`StatusBarIconView#updateIconColor\`、\`Notification.Builder#processSmallIconColor\`）。`,
`⚠ 过去这里还有一句「配套还会阻止系统把替换后的图标重新染回单色」（钩 \`IconManager#setIcon\`、
\`StatusBarIconView#updateIconColor\`、\`Notification.Builder#processSmallIconColor\`）—— 那是
「保留原色」那一档的配套，2026-09-30 随那一档一起删了（\`ca93cba\`）。通知侧现在只有黑白一条路。`],
[`- **自适应图标光栅化是「遮罩贴边」的** ⇒ \`fill()\` 对整张是空操作，只对**前景层**才有意义。
- **尺寸填满**：自适应图标光栅化后内容只占画布中间约 61%，四周是空的。模块会先裁掉空白
  再放大填满，否则缩到通知里那点尺寸会又小又糊。`,
`- **自适应图标光栅化是「遮罩贴边」的** ⇒ 裁空白、放大填满这类操作对**整张**是空操作，只对前景层
  才有意义。曾经「彩色桌面图标」那一档会先裁掉空白再放大填满（光栅化后内容只占画布中间约 61%，
  不填满缩到通知里那点尺寸会又小又糊）—— 黑白这一档不做这件事，\`fill()\` / \`scaleTo()\` 随那一档
  一起删了（\`ca93cba\`）。`],
['options: circle=true notify=true preserveTinted=true (source=provider)',
 'options: circle=true monoInvert=true notify=true preserveTinted=true (source=provider)'],
['│   ├── IconBitmap             位图的取、裁、合成（通知那半边，桌面已不再共用）',
 '│   ├── IconBitmap             位图的取与压单色（通知那半边，桌面已不再共用）'],
];
for (const [a, b] of pairs) {
  if (!t.includes(a)) { console.log('!! 锚点没对上：' + a.slice(0, 40)); process.exit(1); }
  t = t.replace(a, b);
}
fs.writeFileSync('README.md', t);
console.log('README 余下 4 处已补');
