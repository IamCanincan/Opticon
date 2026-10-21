// **装机后必跑的一条：射程现在是"默认全裁 + 只排快速设置"，两头都要能单独喊出来。**
//
// 为什么要有它（2026-10-21）：改版之前射程是白名单宿主，守卫守的是"通知行还在不在裁"
// （`_notifyguard.cjs`，已随那条规则退休）。翻成默认全裁之后，会坏的地方换了两个方向：
//  · **磁贴漏了**：`TILE_CALLERS = com.android.systemui.qs.` 这条前缀哪天被 OTA 改名，
//    现象是"快速设置的磁贴被裁成圆"—— 无声、回归台看不见（它不编 SystemUI 的东西）。
//  · **某一户没被覆盖**：注入没进去 / 那条通道的 `from` 名改了，现象与"功能没生效"一模一样。
// ⇒ 三种读数，**只有第一种是绿**；后两种一律非零。不许把"没看到"报成通过。
//
// 跑法：node _croprange.cjs <logcat文件> [更多文件…]
//   装机后要走的动作：**下拉两次通知栏、再下拉一次快速设置（并把编辑/预览页打开一下）**，
//   然后抓这段日志 —— 磁贴那一路只有在这一刻才被走，没打开就等于没测。
const fs = require('fs');
const files = process.argv.slice(2);
if (!files.length) { console.error('用法: node _croprange.cjs <logcat…>'); process.exit(2); }
const RX_NEW = /裁圆新开 host=(\S+) 通道=(.+?)（第一次）/;
const RX_CLIP = /clip from=(\S+) host=(\S+)/;
const RX_TILE = /裁圆按磁贴挡下 host=(\S+)/;
const RX_ANY = /裁圆|clip from=/;
const hosts = new Map(), tiles = new Map(), clipped = new Set();
let anyOpticon = 0;
for (const f of files) {
  if (!fs.existsSync(f)) { console.error(`跳过（不存在）：${f}`); continue; }
  for (const line of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (!/Opticon/.test(line)) continue;
    let m;
    if ((m = RX_NEW.exec(line))) { hosts.set(`${m[2]} ← ${m[1]}`, (hosts.get(`${m[2]} ← ${m[1]}`) || 0) + 1); clipped.add(m[1]); }
    else if ((m = RX_CLIP.exec(line))) { clipped.add(m[2]); }
    else if ((m = RX_TILE.exec(line))) { tiles.set(m[1], (tiles.get(m[1]) || 0) + 1); anyOpticon++; }
    else if (RX_ANY.test(line)) anyOpticon++;
  }
}
console.log(`读了 ${files.length} 份日志：Opticon 裁圆相关 ${anyOpticon} 行；被裁过的宿主 ${clipped.size} 个；按磁贴挡下 ${[...tiles.values()].reduce((a, b) => a + b, 0)} 次`);
for (const [k, n] of [...hosts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)) console.log(`   ${String(n).padStart(3)}  ${k}`);
if (!anyOpticon) {
  console.error('!! ⚪ 这一轮里一行裁圆账都没有 —— 不是"没问题"，是**没测到**（模块没重启 / 屏幕没开 / 抓错了段）');
  process.exit(2);
}
const MUST = ['com.android.systemui', 'com.android.launcher3', 'com.google.android.apps.nexuslauncher', 'com.android.settings'];
const seen = MUST.filter(h => clipped.has(h) || [...hosts.keys()].some(k => k.endsWith('← ' + h)));
if (!tiles.size) {
  console.error('!! 🔴 一次"按磁贴挡下"都没有。两种可能，都要人确认，不许当成通过：');
  console.error('   ① 这一轮**没有真的打开过快速设置**（磁贴那一路没走）⇒ 拉一次再跑；');
  console.error('   ② `TILE_CALLERS` 那条前缀漂了（OTA 改了 `com.android.systemui.qs.` 的名字）⇒ 磁贴正在被裁。');
  console.error('   判别法：SystemUI 有没有裁过东西 —— 有裁而零挡下 = 高度可疑是 ②。');
  console.error(`   本轮 SystemUI 被裁=${clipped.has('com.android.systemui') ? '是' : '否'}；要核的前缀在 CircleIconDrawable.TILE_CALLERS`);
  process.exit(1);
}
if (seen.length < 2) {
  console.error(`!! 🔴 该被覆盖的宿主里只认出 ${seen.length} 个（${seen.join(', ') || '无'}）—— 桌面/设置/SystemUI 三户至少该有两户有账`);
  console.error('   查：作用域有没有注入进去、那条通道的 `from` 名是否改了。');
  process.exit(1);
}
console.log(`✅ 射程两头都对：SystemUI 有裁（通知行那类）· 磁贴挡下 ${[...tiles.values()].join('/')} 次 · 覆盖宿主 ${clipped.size} 户`);
console.log('   （**注意**：这一行只证明"账对"，不证明"屏上好看"。第三方 app 那一族现在也裁了，');
console.log('    哪一户不该裁，拿 `裁圆新开 host=` 点名，往 TILE_CALLERS 旁边加排除，**不要回去改默认值**。）');
