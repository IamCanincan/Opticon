const fs = require('fs');
let done = 0;
function edit(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    if (!t.includes(a)) { console.log('!! 锚点没对上 @' + path + '：' + a.slice(0, 44)); process.exit(1); }
    t = t.replace(a, b); done++;
  }
  fs.writeFileSync(path, t);
}
const O = 'app/src/main/kotlin/com/iamcanincan/opticon/';

edit(O + 'runtime/ModuleOptions.kt', [[
` * 本模块的做法：**遮罩仍由桌面出，我们只决定翻不翻**。判据是「这张图的墨比它的底暗吗」，
     * 与过去那条合成路共用同一份 \`MonochromeMask\`（同一个 \`groundPass\`），不另写一套
     * "哪边是图形"。判不出来的图**不动**（= 桌面本来的样子）并单独计数。
     *
     * ⚠ 过去这里还有第二条路（自己合成一个单色层交上去）。它已经不上屏了 —— 判据留着，
     *   出图那半在 \`MonochromeSynth\` / \`MonochromeMask.build\` 里，判据台与真机重放还在跑它。`,
` * 本模块的做法：**不给它出遮罩的机会**。钩 \`AdaptiveIconDrawable.getMonochrome()\`，应用自带
     * 单色层就原样透传，没有就自己合成一张极性正确的剪影交上去 —— 桌面只在 getter 返回 null
     * 时才 new 那个 Factory，所以合成一接上，"亮度当 alpha" 那条路根本不进。
     * 极性怎么判与判据台共用同一份 \`MonochromeMask\`（同一个 \`groundPass\`），不另写一套"哪边是图形"。
     *
     * ⚠ 旧版的第一条路是**翻转兜底**（让桌面先算错、我们再整张翻回来），已被合成取代；
     *   \`MonochromeMask.polarity()\` 现在只剩判据台与性质在用，业务侧无调用者 —— 别拿它当
     *   "屏幕上那条路还在翻转"的证据。`]]);

edit(O + 'ui/icon/IconScreen.kt', [[
` * 模块的做法是**遮罩照旧由桌面出，我们只决定翻不翻**：每张图先量一次"墨比它的底暗吗"，
 * 暗就翻，亮或者看不准就不动（不动 = 桌面本来的样子）。判据与判据台跑的是同一份
 * \`MonochromeMask\`，界面上不再多给一个"强度"之类的旋钮 —— 那是口味，不是这里要答的问题。`,
` * 模块的做法是**不给它出遮罩的机会**：钩 \`getMonochrome()\`，未适配的应用由本模块现场合成一张
 * 极性正确的剪影交上去，桌面那条"亮度当 alpha"的路径根本不进。极性的判据与判据台跑的是同一份
 * \`MonochromeMask\`，界面上不再多给一个"强度"之类的旋钮 —— 那是口味，不是这里要答的问题。`],
[` * 关掉它是逃生口，不是「看个人口味」：**两条路一起停**，只有某个 ROM / 桌面版本上实测`,
 ` * 关掉它是逃生口，不是「看个人口味」：**整条合成就停**，桌面回到自己那套算法。只有某个 ROM / 桌面版本上实测`]]);

edit(O + 'runtime/ModulePrefs.kt', [[
`——**首选合成、兜底翻转**，关掉就两条一起停（见 [ModuleOptions.monoInvert]）；`,
`——**未适配的图标由本模块合成一张极性正确的单色层**，关掉就完全不介入（见 [ModuleOptions.monoInvert]）；`]]);

edit(O + 'ui/OpticonApp.kt', [[
`    // 首次打开就把默认配置落盘（含 mode 键）。放在外壳而不是各页面里：
    // 默认打开的是「图标」页，而模块侧有几条回退通道是**以「配置里有 mode 键」
    // 作为「这份文件是不是有效配置」的判据**的 —— 只打开过图标页就改开关的话，
    // 文件里没有 mode，那几条通道会整份配置都不认，用户改的开关读不回来。
    val context = LocalContext.current

    // 首次打开就把默认配置落盘（含 mode 键）。放在外壳而不是各页面里：
    // 默认打开的是「图标」页，而模块侧有几条回退通道是**以「配置里有 mode 键」
    // 作为「这份文件是不是有效配置」的判据**的 —— 只打开过图标页就改开关的话，
    // 文件里没有 mode，那几条通道会整份配置都不认，用户改的开关读不回来。
    //`,
`    // 首次打开就把默认配置落盘。放在外壳而不是各页面里：默认打开的是「图标」页，而模块侧有
    // 几条回退通道要先认「这份文件是不是 Opticon 的配置」—— 判据是
    // \`ModulePrefs.CONFIG_KEYS\` 里**任意一个键存在**（见 looksLikeConfig）。从没打开过界面的
    // 用户文件里一个键都没有，那几条通道就不该拿它当配置，否则空文件会被当成"用户全用默认值"
    // 把真设置顶掉。所以第一次开界面必须把整组默认值写下去。
    //
    // ⚠ 这是带磁盘读写的副作用，不能放在 remember {} 里：remember 的求值发生在**组合期**，`]]);

edit(O + 'circle/IconHooks.kt', [['[hookMaskPolarity]', '[hookMaskFallThrough]']]);

let r = fs.readFileSync('README.md', 'utf8');
const s = '现在只有一条路：**遮罩仍由桌面出，模块只决定翻不翻**。两道钩子：';
const e = '出现第二份就会各自漂移），判据台与真机重放也还在跑它。取舍见下一节。';
const i = r.indexOf(s), j = r.indexOf(e);
if (i < 0 || j < 0) { console.log('!! README 大块锚点没对上'); process.exit(1); }
const NEW = `现在只有一条路：**模块自己合成一张极性正确的单色层交上去**，桌面根本不进它自己那套算法。两道钩子（\`circle/IconHooks.kt\`）：

1. \`hookSynthesizedMonochrome\` 钩 \`AdaptiveIconDrawable.getMonochrome()\`。应用自带单色层就原样
   透传（一个像素都不碰）；没有的话，把这张图光栅化、交给 \`MonochromeSynth.forIcon\` →
   \`MonochromeMask.build\` 压一张**极性正确的剪影**直接返回。桌面只在 getter 返回 null 时才去 new
   它自己的 \`MonochromeIconFactory\` —— 合成一接上，那条把亮度当不透明度的路径**根本不进**。
   代码里对这条的原话是「这是主路径，也是唯一路径」。
2. \`hookMaskFallThrough\` 只数一件事：**桌面自己出了多少张遮罩** = 上游合成漏接了多少张。
   纯合成模式下这个数应该是 0；它非零是「屏幕上有一批图标是反的」唯一的可见信号，
   日志是 \`Mono: N 张走了桌面自己的遮罩…\`。

「已适配的不动」不用判条件：桌面只在 \`getMonochrome()\` 为 null 时才 new 那个 Factory，
自带单色层的应用根本走不到这条路径。

开关是**有的**（图标页 →「未适配的图标由本模块生成单色层」）：关掉 = 完全交回桌面自己处理。
它不是口味旋钮，而是"这台 ROM 上厂商已经修过极性、我们整体不该介入"时的逃生口。
一张一张定"这个应用翻不翻"的调试面板已经删掉了（2026-09-30）：它给的是"绕开判据"，
而这一路要的是把判据量对。

⚠ 这一路**不读桌面任何私有字段** —— 旧版翻转兜底要读 \`mAlphaBitmap\`，字段名一变整条静默失效，
那是它被合成换掉的直接原因之一。极性怎么判、为什么"亮的不一定是墨"，全在下一节的模型里
（\`MonochromeMask\` 的 \`groundPass\` 是全仓唯一一份"哪边是图形"的实现，出现第二份就会各自漂移）。

> 这里曾经的第一条路是**翻转兜底**：让桌面先算出一张极性错误的遮罩、模块再整张翻回来。
> 换成合成优先之后它只剩兜底，现在整条已删。\`MonochromeMask\` 里那套极性判据
> （\`Polarity\` / \`polarity()\`）**只剩判据台与性质在用**，业务侧已无调用者 —— 别把它当成
> "屏幕上那条路还在翻转"的证据。取舍见下一节。`;
r = r.slice(0, i) + NEW + r.slice(j + e.length);
fs.writeFileSync('README.md', r); done++;
console.log('README 大块已重写');

edit('README.md', [[
`配套还会阻止系统把替换后的图标重新染回单色（挂钩 \`IconManager#setIcon\`、
\`StatusBarIconView#updateIconColor\`、\`Notification.Builder#processSmallIconColor\`）。`,
`⚠ 过去这里还有一句「配套还会阻止系统把替换后的图标重新染回单色」（钩 \`IconManager#setIcon\`、
\`StatusBarIconView#updateIconColor\`、\`Notification.Builder#processSmallIconColor\`）—— 那是
「保留原色」那一档的配套，2026-09-30 随那一档一起删了（\`ca93cba\`）。现在通知侧只有黑白一条路。`],
[`- **自适应图标光栅化是「遮罩贴边」的** ⇒ \`fill()\` 对整张是空操作，只对**前景层**才有意义。
- **尺寸填满**：自适应图标光栅化后内容只占画布中间约 61%，四周是空的。模块会先裁掉空白
  再放大填满，否则缩到通知里那点尺寸会又小又糊。`,
`- **自适应图标光栅化是「遮罩贴边」的** ⇒ 裁空白、放大填满这类操作对**整张**是空操作，只对
  前景层才有意义。曾经"彩色桌面图标"那一档会先裁掉空白再放大填满（自适应图标光栅化后内容只占
  画布中间约 61%），否则缩到通知里那点尺寸会又小又糊 —— 黑白这一档不做这件事，\`fill()\` /
  \`scaleTo()\` 随那一档一起删了（\`ca93cba\`）。`],
[`options: circle=true notify=true preserveTinted=true (source=provider)`,
 `options: circle=true monoInvert=true notify=true preserveTinted=true (source=provider)`],
[`- \`notify/IconBitmap.kt\` —— 位图的取、裁、合成`, `- \`notify/IconBitmap.kt\` —— 位图的取与压单色`]]);
console.log('共改', done, '处');
