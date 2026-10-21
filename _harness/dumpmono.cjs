// 把桌面 app_icons.db 里**真正上屏的那一张**（mono_icon，256×256 ALPHA_8）全量倒成 PNG + 清单，
// 并且**同一行把彩色源（`icon` 列 = 一张 PNG）一起倒成 `<名>.src.png`** —— 2026-10-20 加的。
// 为什么要源：台账那条「翻译 #104 右半整块黑」是**看 montage 缩略图**立的项目，而库里这张
// 交付图（三趟逐字节相同）画的其实是"实心卡 + 镂空『文』"，正是主人 2026-10-12 点名的样子。
// ⇒ 以后任何"全量冒出来的病"必须先有 **源｜交付** 这一对再看，不许拿缩略图立项。
// 去重：同一个位图可能被 N 个 component 共用（快捷方式/小部件），只出一份，但清单里全记。
const { DatabaseSync } = require('node:sqlite');
const fs = require('fs'), path = require('path');
const out = process.argv[2] || 'D:/tmp/mono_all';
fs.mkdirSync(out, { recursive: true });
const db = new DatabaseSync('D:/tmp/app_icons.db', { readOnly: true });
const rows = db.prepare('SELECT componentName, label, mono_icon mo, icon src FROM icons').all();
const seen = new Map(); const man = [];
for (const r of rows) {
  if (!r.mo) { man.push([r.componentName, '-', '没有 mono_icon（走彩色）']); continue; }
  const b = Buffer.from(r.mo);
  if (b.length !== 65536) { man.push([r.componentName, '-', `字节数 ${b.length}（不是 256²）`]); continue; }
  let ink = 0, solid = 0, mass = 0;
  for (let i = 0; i < 65536; i++) { if (b[i] > 0) ink++; if (b[i] >= 255) solid++; mass += b[i]; }
  const key = b.toString('base64').slice(0, 64) + ':' + mass;
  if (!seen.has(key)) {
    const name = (r.componentName || 'x').replace(/[^\w.]+/g, '_');
    const png = path.join(out, name + '.gray');
    fs.writeFileSync(png, b);
    if (r.src) {
      const s = Buffer.from(r.src);
      if (s.slice(0, 8).toString('hex') === '89504e470d0a1a0a') fs.writeFileSync(path.join(out, name + '.src.png'), s);
      else console.log(`!! ${name} 的 icon 列不是 PNG（头 ${s.slice(0, 4).toString('hex')}），只倒了交付图`);
    }
    seen.set(key, name);
  }
  man.push([r.componentName, seen.get(key), `有墨 ${(ink * 100 / 65536).toFixed(1)} 全实 ${(solid * 100 / 65536).toFixed(1)} 均α ${(mass / 65536).toFixed(1)}`]);
}
fs.writeFileSync(path.join(out, 'manifest.tsv'), 'component\tfile\tnote\n' + man.map(r => r.join('\t')).join('\n'));
console.log('行数 ' + rows.length + '  去重后独立位图 ' + seen.size + '  → ' + out);
