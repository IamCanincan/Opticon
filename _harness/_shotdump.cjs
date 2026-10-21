// 读 `adb shell dumpsys shortcut` 的输出，数清楚**这台机器上的快捷方式图标到底能不能从我们这边拿到**。
//
// 为什么要先数再动手：`CorpusCapture` 第一版走 `PackageManager.getXml()` 去枚举 manifest 快捷方式，
// 真机跑回来是 **129 个包里 0 个声明** —— 而 `dumpsys shortcut` 里明明有 `flags=0x1a4 [ImManIc-rStr]`
// 那种带 `m`(manifest) 的条目。也就是说"0 张"是我的**通道选错了**，不是这台机器上没有这一类。
// 这一份就是用来问：那批图标分别以什么形式存在（资源 id / 落盘的 bitmap / uri），
// 各自能不能被一个普通应用读到 —— 决定第二版走哪条路。
//
// 用法：node _shotdump.cjs <dumpsys_shortcut.txt> [--list] [--marker <输出文件>]
const fs = require('fs');
const file = process.argv[2];
if (!file) { console.log('用法: node _shotdump.cjs <dump 文件> [--list]'); process.exit(2); }
const text = fs.readFileSync(file, 'utf8');
const lines = text.split(/\r?\n/);

// 一条 ShortcutInfo 块：从 `ShortcutInfo {id=…` 到下一个 `ShortcutInfo {` 或 `Package:` 之前。
const shots = [];
let cur = null; let pkg = null;
for (const raw of lines) {
  const l = raw.trim();
  const pm = l.match(/^Package: (\S+)/);
  if (pm) { pkg = pm[1]; continue; }
  const si = l.match(/^ShortcutInfo \{id=([^,]+), flags=(0x[0-9a-f]+) \[([^\]]*)\]/);
  if (si) { if (cur) shots.push(cur); cur = { pkg, id: si[1], flags: si[2], tag: si[3] }; continue; }
  if (!cur) continue;
  let m;
  if ((m = l.match(/^iconRes=(\d+)\[([^\]]*)\], bitmapPath=(\S+), iconUri=(\S+)/))) {
    cur.resId = +m[1]; cur.resName = m[2]; cur.bitmap = m[3] === 'null' ? null : m[3]; cur.uri = m[4] === 'null' ? null : m[4];
  } else if ((m = l.match(/^shortLabel=([^,]*)(?:, resId=(\d+)\[([^\]]*)\])?/))) {
    cur.label = m[1] || (m[3] || '');
  } else if ((m = l.match(/^activity=ComponentInfo\{([^/]+)\/([^}]+)\}/))) {
    cur.activity = m[1] + '/' + m[2];
  } else if (/^\}/.test(l)) { shots.push(cur); cur = null; }
}
if (cur) shots.push(cur);

const has = (s) => s.resId && s.resId !== 0 ? 'res' : s.bitmap ? 'bitmap' : s.uri ? 'uri' : 'none';
const by = {};
for (const s of shots) by[has(s)] = (by[has(s)] || 0) + 1;

console.log(`ShortcutInfo 块 ${shots.length} 条｜图标形式：` +
  Object.entries(by).map(([k, v]) => `${k}=${v}`).join(' / '));
console.log(`涉及包 ${new Set(shots.map(s => s.pkg)).size} 个`);
// ⚠ **不按 flag 字母分类**：AOSP 那串 `[ImManIc-rStr]` 每一位代表什么，我第一版是猜的（猜成"第 3 位是 m"），
//   结果数出"manifest 0 条"，而真机上明明有 —— 猜编码就是自造边界。
//   对语料真正要紧的是**图标以什么形式存在**：资源 id 能从属主包的 Resources 解出来（我们抓得到），
//   bitmap / uri 那两类在 /data/system 里或要 ContentResolver（这一档先不覆盖，报出来别当覆盖）。
const resShots = new Map();
for (const s of shots.filter((x) => has(x) === 'res')) resShots.set(s.pkg + '|' + s.resId, s);
console.log(`资源 id 形式的图标：**${resShots.size} 张不同的图**（去重后 = 这一档的语料分母）` +
  `｜bitmap ${by.bitmap || 0} 条、uri ${by.uri || 0} 条**这一档拿不到**（记成覆盖边界，别说成整类）`);
if (process.argv.includes('--list')) {
  let n = 0;
  for (const s of resShots.values()) { if (n++ < 12) console.log(`  ${s.pkg} ${s.resId} ${s.resName}  「${s.label}」 id=${s.id}`); }
  if (resShots.size > 12) console.log(`  …另 ${resShots.size - 12} 张`);
  const other = shots.filter((x) => has(x) !== 'res');
  for (const s of other.slice(0, 6)) console.log(`  [非资源] ${s.pkg} ${s.id} 形式=${has(s)} ${s.bitmap || s.uri || ''}`);
  if (other.length > 6) console.log(`  …另 ${other.length - 6} 条非资源形式`);
}

// ── --marker：把这张表写成 `CorpusCapture` 认领的清单（一行一张）──
// 为什么清单在 PC 上生成、而不是让应用自己去枚举：应用侧那条枚举路（`PackageManager.getXml`）
// 真机跑回来是 **129 个包里 0 个声明**，而这份 dump 里有 124 张资源图标 —— 枚举通道选错了。
// `dumpsys shortcut` 是 ShortcutService 自己的权威名单（静态 + 动态都在里面），所以由它出清单、
// 应用只负责"按包 + 资源 id 把那张图画出来"。
//
// ⚠ **但 dump 不是全的**：真机翻了极性的那两张 `com.android.contacts/drawable/logo_quick_contacts_*`
//   在这份 dump 里出现 **0 次**（它们是桌面按别的路子拿到的）。补法 = 从**我们自己的日志**回填：
//   判据行里带着完整 `包/类型/资源名`，设备侧按名字 `getIdentifier` 解回 id 再画。
//   用法：`--logs=<logcat 文件>[,<另一个>…]`，出来的行走 `res=` 那一种。
// ⚠ 参数两种写法都要认（`--marker=<路径>` 与 `--marker <路径>`）：第一版只认后一种，
//   于是 `--marker=…` 静默不写文件、退出码还是 0 —— 新加旗标必须先证明它"印了东西"（本仓那条）。
const mkArg = process.argv.find((a) => a.startsWith('--marker='))?.substring(9)
  || ((i) => (process.argv[i] === '--marker' ? process.argv[i + 1] : null))(process.argv.indexOf('--marker'));
if (mkArg) {
  const out = mkArg;
  const lines = ['mode=shortcut'];
  for (const s of resShots.values()) lines.push(`shot=${s.pkg}/${s.resId}`);
  const logArg = (process.argv.find((a) => a.startsWith('--logs=')) || '').substring(7);
  const seenRes = new Set(resShots.values().map((s) => `${s.pkg}|${s.resName}`));
  let fromLogs = 0, pmForm = 0;
  for (const f of logArg ? logArg.split(',').map((x) => x.trim()).filter(Boolean) : []) {
    if (!fs.existsSync(f)) { console.log(`!! --logs 指定的文件不存在：${f}（这一路没并进来，不是"日志里没有"）`); continue; }
    for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
      const m = /Mono: diag#\d+ edge=\d+(?: sig=\S+)?(?: 键=\S+)? (\S+)\/(drawable|mipmap)\/(\S+)/.exec(l);
      if (!m) continue;
      const key = `${m[1]}|${m[3]}`;
      if (seenRes.has(key)) continue;
      seenRes.add(key); lines.push(`res=${m[1]}/${m[2]}/${m[3]}`); fromLogs++;
    }
    // `pm:包名/id` 那种（动态快捷方式）按资源名解不出来，只能数出来报个覆盖边界。
    for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) if (/diag#\d+.* 键=\S+ pm:/.test(l)) pmForm++;
  }
  if (logArg) console.log(`日志回填：另加 ${fromLogs} 张（按资源名），` +
    `另有 ${pmForm} 行是 ` + '`pm:包名/id` 形式（动态发布、无资源名）—— 这一路解不出来，记成覆盖边界');
  const body = lines.join('\n') + '\n';
  fs.writeFileSync(out, body);
  console.log(`写出清单 ${out}（${lines.length - 1} 条，${body.length} 字节）`);
}
