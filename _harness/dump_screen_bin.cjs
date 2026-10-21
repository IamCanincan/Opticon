const { DatabaseSync } = require('node:sqlite');
const fs = require('fs');
const db = new DatabaseSync('D:/tmp/app_icons.db', { readOnly: true });
const pat = process.argv[2] || 'me.mugzone.emiria/%';
for (const r of db.prepare('SELECT componentName, mono_icon mo FROM icons WHERE componentName LIKE ?').all(pat)) {
  const b = Buffer.from(r.mo);
  if (b.length !== 65544 && b.length !== 65536) { console.log('长度不对', b.length); continue; }
  const f = 'D:/tmp/screen_' + r.componentName.split('/')[0].replace(/[^\w.]/g, '_') + '.bin';
  fs.writeFileSync(f, b);
  console.log('写了', f, b.length, '头 8 字节', b.slice(0, 8).toString('hex'));
}
