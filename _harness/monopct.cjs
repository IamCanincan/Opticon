// 桌面 mono_icon（256×256 ALPHA_8）→ 有墨% / 全实%，与离线 InkPct 同一条定义。
// 用法：node monopct.cjs <标签>   （标签只印在行首，方便改前/改后两联并排看）
const { DatabaseSync } = require('node:sqlite');
const tag = process.argv[2] || '';
const db = new DatabaseSync('D:/tmp/app_icons.db', { readOnly: true });
const want = ['me.mugzone.emiria', 'com.icbc', 'cn.com.zjol', 'com.cnspeedtest.globalspeed',
  'run.xbud.android', 'com.fdg.flashplay.farsee', 'com.jingdong.app.mall', 'com.yiban.app',
  'com.netease.uuremote', 'com.feimo.astralparty'];
console.log('总行数 =', db.prepare('SELECT count(*) c FROM icons').get().c);
for (const p of want) {
  const rows = db.prepare('SELECT componentName, mono_icon mo FROM icons WHERE componentName LIKE ?')
    .all(p + '/%');
  if (!rows.length) { console.log(`${tag} ${p}: 库里没有这一行（还没重新绑定）`); continue; }
  for (const r of rows) {
    const b = Buffer.from(r.mo);
    let off = 0;
    if (b.length === 65536) off = 0;
    else if (b.length === 65544) off = 8;
    else { console.log(`${tag} ${r.componentName}: mono 长度 ${b.length}，不是 256² 的 ALPHA_8`); continue; }
    let ink = 0, solid = 0;
    for (let i = off; i < off + 65536; i++) { if (b[i] > 0) ink++; if (b[i] >= 255) solid++; }
    console.log(`${tag} ${r.componentName.padEnd(38)} 有墨 ${(ink * 100 / 65536).toFixed(2)}%  全实 ${(solid * 100 / 65536).toFixed(2)}%`);
  }
}
