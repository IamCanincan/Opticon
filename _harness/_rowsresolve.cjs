// 现查「每个包的启动图标是哪一行」：设备上 `cmd package resolve-activity`，相对段补全，写钉表。
//   为什么必须现查而不是信库首行：2026-11-15 现量 65/137 个包的库首行是小组件 provider /
//   `add_contact` / `bill` / 空段占位行 —— 拿首行判 = 把快捷方式的几何算进应用的账。
//   为什么进 silgate 而不是入库：与 iconclass.json 同待遇（设备状态，吃过期一轮就造假故障）。
// 用法：node _harness/_rowsresolve.cjs <app_icons.db> <输出 tsv>
const fs = require('fs');
const { execFileSync } = require('child_process');
const { DatabaseSync } = require('node:sqlite');
const [dbp, outp] = process.argv.slice(2);
const pkgs = [...new Set(new DatabaseSync(dbp, { readOnly: true })
  .prepare('SELECT componentName c FROM icons').all().map((r) => r.c.split('/')[0]).filter((p) => p.includes('.')))];
const lines = [];
let bad = 0;
for (const p of pkgs) {
  let raw = '';
  try {
    raw = execFileSync('adb', ['shell', 'cmd', 'package', 'resolve-activity', '--brief',
      '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', p],
      { encoding: 'utf8', timeout: 15000 }).replace(/\r/g, '').trim().split('\n').pop() || '';
  } catch { /* 设备不在/超时 ⇒ 这一包回退首行，尺子会报回退数 */ }
  const seg = raw.split('/')[1] || '';
  if (!seg || raw.includes('No activity')) { bad++; continue; }
  lines.push(`${p}\t${p}/${seg.startsWith('.') ? p + seg : seg}`);
}
fs.writeFileSync(outp, lines.join('\n') + '\n');
console.log(`   钉表现查：${lines.length} 行（${bad} 个包没有启动入口 ⇒ 尺子回退首行）→ ${outp}`);
if (!lines.length) { console.log('!! 一行都没查到 —— 设备没连？这道闸的行钉不住（不是没问题）'); process.exit(1); }