// 装机后必跑的一条：**通知行的应用图标还在不在裁**（2026-10-17，主人「你看着来吧」之后）。
//
// 为什么需要它：认"这是通知行在取图标"靠的是一个**类名前缀**
// （`CircleIconDrawable.NOTIFY_CALLER = com.android.systemui.statusbar.notification.`）。
// AOSP 的 notification 那套代码反复重构，哪次 OTA 改了名，那一行就**静默不裁** ——
// 不报错、回归台看不见（它不编 SystemUI 的东西）、桌面与设置照旧圆。
// 所以这道判据要能在"全绿"里单独喊出来，而且**不许把"没测到"报成通过**。
//
// 三种读数，只有第一种是绿：
//  · ✅ 在裁 —— SystemUI 里有 `clip from=… host=com.android.systemui` 的裁圆行；
//  · 🔴 前缀失配 —— 一行裁圆都没有，**但"挡住"的账里出现了通知行取图标的形状**
//    （`AppIconProvider` / `fetchAppIconBitmapInfo` / `BaseIconFactory`）⇒ 类名改了，点名给你看；
//  · ⚪ 没测到 —— 既没裁也没挡（SystemUI 这一轮根本没加载过图标：屏幕没开、没拉通知栏、
//    或者模块没重启）⇒ **非零退出**，这句不是"没问题"。
//
// 用法：node _notifyguard.cjs            # 直接读设备缓冲（adb logcat -d -s Opticon）
//       node _notifyguard.cjs <日志文件>  # 离线复跑（变异测这台子用的就是这条）
const { execFileSync } = require('child_process');
const fs = require('fs');

const file = process.argv[2];
const log = file ? fs.readFileSync(file, 'utf8') :
  execFileSync('adb', ['logcat', '-d', '-s', 'Opticon'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
// ⚠ 必须按 `\r?\n` 切：logcat 在 Windows 上是 CRLF，而 JS 里 `$`（无 m 标志）只认字符串结尾、
//   `.` 又不吃 `\r` —— 留着 `\r` 的话 `/← (.*)$/` 这种"取后半段"的正则**一条都匹配不上**，
//   于是"点名是谁在取"那一句会退化成整行原样吐出来（本轮真撞上过一次）。
const L = log.split(/\r?\n/);

const SYS = 'com.android.systemui';
// 通知行取图标的"形状"：类名换了包/换了前缀也还在这些词上（今天真机读到的那几个）。
// ⚠ **不许把 `IconAndName` 放进来** —— 那是 `qs/panels/data/repository/IconAndNameCustomRepository`，
//   **快速设置**的自定义磁贴数据仓库，本来就该被挡；放进来会让一台健康的机器自己报"前缀失配"。
const SHAPE = /AppIconProvider|fetchAppIconBitmapInfo|BaseIconFactory|AppIconCache/;
const clipped = L.filter(l => /clip from=/.test(l) && l.includes('host=' + SYS));
const blocked = L.filter(l => /裁圆挡住/.test(l) && l.includes('host=' + SYS));
const allowed = L.filter(l => /裁圆放行\(按调用方\)/.test(l));
const sysAny = L.filter(l => l.includes(SYS) && /Opticon/.test(l));

const uniq = (a) => [...new Set(a)];
console.log(`== 通知裁圆守卫（${file || '设备缓冲'}）：SystemUI 里 Opticon 行 ${sysAny.length} 条 ==`);
console.log(`   裁了 ${clipped.length} 次（去重通道：${uniq(clipped.map(l => (l.match(/clip from=(\S+)/) || [])[1])).join('、') || '无'}）` +
  `  放行对 ${allowed.length} 条  挡住 ${blocked.length} 对`);
if (clipped.length > 0 && allowed.length > 0) {
  console.log('   ✅ 在裁：' + (allowed[allowed.length - 1].match(/：(.*)$/) || [])[1]);
  process.exit(0);
}
if (clipped.length > 0) {
  // 有裁圆但没有"放行对"那一行：多半是这个进程早就起过、第一次出现已经打过了（每对只打一次）
  console.log('   ✅ 在裁（放行对那行每进程只打一次，这一轮没赶上；裁了 ' + clipped.length + ' 次就是证据）');
  process.exit(0);
}
const shape = blocked.filter(l => SHAPE.test(l));
if (shape.length > 0) {
  console.log('   🔴 **前缀失配**：SystemUI 一次都没裁，可"挡住"的账里有通知行取图标的形状 ——');
  uniq(shape.map(l => (l.match(/← (.*)$/) || [])[1] || l)).forEach(c => console.log('      现在这个类叫：' + c));
  console.log('   改法：把上面那个前缀填进 CircleIconDrawable.NOTIFY_CALLER，重装 + 重启 SystemUI。');
  process.exit(1);
}
console.log('   ⚪ **没测到**，不是"没问题"：这一轮 SystemUI 既没裁也没挡 —— 通知行没被重新绑过。');
console.log('   要出读数：确认屏幕亮着（`adb shell dumpsys power | grep mWakefulness` 应为 Awake），');
console.log('   重启过 SystemUI（新代码），然后把通知栏拉下来滑两下再跑这一条。');
process.exit(2);
