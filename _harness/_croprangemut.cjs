// **给 `_croprange.cjs` 做变异测：每条都要自证"真落地了"，并且退码必须按预期变。**
//
// 为什么每台守卫都要配这个（本仓规矩）：一道"永远不回红"的守卫与没有守卫等价，
//   而**报错被 `;` 吞掉时，你会拿到一份假的"全部通过"** —— 所以每条变异同时核
//   ① 样本确实被改了（行数/内容变了）② 退码确实是那一条要的那个。
// 样本是**内联造的**（不读用户日志）：这样每次跑的都是同一份金标准，也不会因为
//   某轮日志不完整而把"守卫坏了"误读成"屏上有问题"。
// 跑法：node _croprangemut.cjs
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const TMP = path.join(__dirname, 'cand', '_croprange_mut.log');
const SYSUI = 'com.android.systemui', LAU = 'com.android.launcher3', SET = 'com.android.settings';
const line = (s) => `10-21 12:00:00.000  1000  1000 D/Opticon: ${s}\n`;
const GOLD = [
  line(`裁圆新开 host=${LAU} 通道=PM.getApplicationIcon（第一次）`),
  line(`clip from=PM.getApplicationIcon host=${LAU} : AdaptiveIconDrawable 192x192`),
  line(`裁圆新开 host=${SET} 通道=Resources.getDrawable·标记id（第一次）`),
  line(`裁圆新开 host=${SYSUI} 通道=PM.loadItemIcon（第一次）`),
  line(`clip from=PM.loadItemIcon host=${SYSUI} : BitmapDrawable 192x192`),
  line(`裁圆按磁贴挡下 host=${SYSUI}：PM.loadItemIcon ← 磁贴(按调用方挡住)`),
  line(`裁圆累计 200 次，通道×宿主 top：…`),
].join('');
/** 每条：名字 → [怎么改, 期望退码]。`control` 那条是**负对照**：它本来就不该改样本（改了就没基准了）。 */
const CASES = [
  ['金标准（负对照：什么都没改）', s => s, 0, true],
  ['磁贴一次都没挡下（前缀漂了 或 没打开面板）', s => s.split(line(`裁圆按磁贴挡下 host=${SYSUI}：PM.loadItemIcon ← 磁贴(按调用方挡住)`)).join(''), 1],
  ['宿主没被覆盖（只剩 SystemUI 一户）', s => s.split('\n').filter(l => !l.includes(`host=${LAU}`) && !l.includes(`host=${SET}`)).join('\n') + '\n', 1],
  ['整轮没有任何裁圆账（没测到 ≠ 通过）', () => line('Mono: 别的东西一行'), 2],
  ['空文件', () => '', 2],
];
let bad = 0;
for (const [name, fn, want, control] of CASES) {
  const text = fn(GOLD);
  if (!control && text === GOLD) { console.error(`!! 「${name}」没改动样本一分 —— 这条在测空气`); bad++; continue; }
  fs.mkdirSync(path.dirname(TMP), { recursive: true });
  fs.writeFileSync(TMP, text);
  const r = spawnSync(process.execPath, [path.join(__dirname, '_croprange.cjs'), TMP],
    { encoding: 'utf8', cwd: __dirname });
  const rc = r.status;
  const out = (r.stdout + r.stderr).split(/\r?\n/).filter(l => /^(!!|✅)/.test(l))[0] || '';
  const ok = rc === want;
  if (!ok) bad++;
  console.log(`${ok ? '✅' : '❌'} 退码 ${rc}（要 ${want}）「${name}」 ${out.slice(0, 66)}`);
}
fs.writeFileSync(TMP, GOLD);
const base = spawnSync(process.execPath, [path.join(__dirname, '_croprange.cjs'), TMP], { encoding: 'utf8' }).status;
console.log(`还原金标准后再跑一次：退码 ${base}（必须 0，否则上面那些"通过"是这条守卫在别的分支上蒙的）`);
if (base !== 0) bad++;
console.log(bad ? `\n!! ${bad} 条变异没按预期变（这台守卫不可信）` : '\n五条变异全部按预期变，还原后仍绿 ✓');
process.exit(bad ? 1 : 0);
