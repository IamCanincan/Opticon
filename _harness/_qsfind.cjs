// **静态取证：这台机器的 SystemUI 里，"快速设置"那一族的类命名空间有哪些。**
//
// 为什么要它：裁圆的射程要从"白名单宿主"改成"只排磁贴"，那"磁贴那一路"就不能靠猜前缀 ——
//   本仓为猜类名翻过车（`_notifyguard.cjs` 守的就是"框架里的类名前缀漂了"这一类）。
//   所以先把 dex 里**真实存在的命名空间**摊出来当候选，再定黑名单。
// ⚠ 这是 **latin1 扫字符串表**：只答"哪些名字在这份 dex 里出现过"，**不答"谁调用了谁"**
//   （方法引用按索引存，要 caller 图得上真正的 dex 解析器）。
//   ⇒ 它给的是**候选前缀**；最终定案仍靠真机日志里那行 `通道 ← 调用方`
//   （`CircleIconDrawable.noteSkip` 已经在打这本账）。
//
// 跑法：node _qsfind.cjs <apk> [正则]     默认正则只挑 QS / 通知行 / 插件那几族
const fs = require('fs');
const { execSync } = require('child_process');
const apk = process.argv[2];
if (!apk) { console.error('用法: node _qsfind.cjs <apk.apk> [正则]'); process.exit(2); }
const DIR = 'D:/tmp/_qsfind';
const RXW = new RegExp(process.argv[3] ||
  'android/service/quicksettings|systemui/qs|systemui/plugins|systemui/tiles|systemui/statusbar/notification|systemui/statusbar/row', 'i');
fs.rmSync(DIR, { recursive: true, force: true });
fs.mkdirSync(DIR, { recursive: true });
execSync(`unzip -o -q "${apk}" "classes*.dex" -d "${DIR}"`);
const dexes = fs.readdirSync(DIR).filter(f => f.endsWith('.dex'));
const RX = /[LM]((?:android|com|java|kotlin|art)[a-zA-Z0-9_$]*(?:\/[a-zA-Z0-9_$+]+)+)/g;
const byPkg = new Map();
let hits = 0;
for (const d of dexes) {
  const s = fs.readFileSync(`${DIR}/${d}`).toString('latin1');
  let m;
  RX.lastIndex = 0;
  while ((m = RX.exec(s))) {
    const cls = m[1];
    if (!RXW.test(cls)) continue;
    hits++;
    const pkg = cls.split('/').slice(0, -1).join('/');
    byPkg.set(pkg, (byPkg.get(pkg) || 0) + 1);
  }
}
console.log(`扫 ${dexes.length} 个 dex：类名命中 ${hits} 次，落在 ${byPkg.size} 个命名空间`);
const rows = [...byPkg.entries()].sort((a, b) => b[1] - a[1]);
for (const [k, n] of rows.slice(0, 45)) console.log(`  ${String(n).padStart(6)}  ${k.replace(/\//g, '.')}`);
if (rows.length > 45) console.log(`  …还有 ${rows.length - 45} 个命名空间（提高上限或收紧正则）`);
// **仪器自证**：换一个必然存在的名字扫，必须非零 —— 否则"0 命中"是正则坏了，不是这台没这些类。
let self = 0;
const RXX = /systemui/i;
for (const d of dexes) {
  const s = fs.readFileSync(`${DIR}/${d}`).toString('latin1');
  RXX.lastIndex = 0;
  if (RXX.test(s)) { self = 1; break; }
}
console.log(`正对照（随便找个含 "systemui" 的类名）：${self ? '命中 ✓ 这台仪器能响' : '**没命中 = 扫描坏了，上面那张表不能用**'}`);
if (!self) process.exit(2);
