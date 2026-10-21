// 变异测 `_notifyguard.cjs` 的三条读数 —— 一台只会说"没问题"的守卫不算守卫。
// 每一步都自证落地（改了几行、文件真的变了），否则"三条都报对了"可能是三条都没跑。
// 用法：node _notifyguardmut.cjs
const fs = require('fs');
const { execFileSync } = require('child_process');

const src = fs.readFileSync('_notif3.txt', 'utf8').split('\n');
const SYS = 'com.android.systemui';
const isClip = (l) => /clip from=/.test(l) && l.includes('host=' + SYS);
const clipN = src.filter(isClip).length;
if (clipN === 0) { console.log('!! 原日志里就没有 SystemUI 的裁圆行，这份语料不能用'); process.exit(2); }

function run(label, lines, want) {
  const f = '_ng_' + label + '.txt';
  fs.writeFileSync(f, lines.join('\n'));
  const changed = fs.readFileSync(f, 'utf8').split('\n').length;
  let rc = 0, out = '';
  try { out = execFileSync(process.execPath, ['_notifyguard.cjs', f], { encoding: 'utf8' }); }
  catch (e) { rc = e.status === undefined ? -1 : e.status; out = (e.stdout || '') + (e.stderr || ''); }
  const ok = rc === want;
  console.log(`${ok ? '✓' : '!!'} ${label}：退码 ${rc}（期望 ${want}），文件 ${changed} 行`);
  out.split('\n').filter(x => x.trim()).slice(1).forEach(x => console.log('     ' + x.trim().slice(0, 130)));
  return ok;
}

let all = true;
// ① 真日志：必须绿
all = run('real', src, 0) && all;
// ② 前缀失配：删掉 SystemUI 的裁圆行与放行行，塞一条"挡住"、调用方是**改了名的**通知行取图标类
const renamed = src.filter(l => !isClip(l) && !/裁圆放行\(按调用方\)/.test(l));
const removed = src.length - renamed.length;
if (removed === 0) { console.log('!! ②没改动任何行，这条变异没落地'); process.exit(2); }
const probe = '10-17 17:99:99.999 3884 3884 D Opticon : 裁圆挡住 host=com.android.systemui 第99 次：' +
  'Resources.getDrawable·标记id ← ApplicationInfo;.loadUnbadgedIcon<-com.android.systemui.newplace.icon.AppIconProviderImpl.fetchAppIconBitmapInfo';
all = run('renamed', [...renamed, probe], 1) && all;
// ③ 没测到：SystemUI 一行都没有
const quiet = src.filter(l => !l.includes(SYS));
if (src.length - quiet.length === 0) { console.log('!! ③没删掉任何 SystemUI 行，这条变异没落地'); process.exit(2); }
all = run('quiet', quiet, 2) && all;
console.log(all ? '== 三条读数都按预期 ==' : '!! 有读数不对，这台守卫不能算立起来');
process.exit(all ? 0 : 1);
