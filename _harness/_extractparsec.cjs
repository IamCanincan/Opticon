const { DatabaseSync } = require('node:sqlite');
const fs = require('fs');
const db = new DatabaseSync(process.argv[2] || 'D:/tmp/app_icons_v5.db', { readOnly: true });
const rows = db.prepare("SELECT componentName, icon, mono_icon FROM icons WHERE componentName LIKE ?").all('%parsec%');
for (const r of rows) {
  const cn = r.componentName;
  const tag = cn.replace(/[^a-zA-Z0-9]/g, '_');
  for (const col of ['icon', 'mono_icon']) {
    const b = r[col];
    if (!b || b.length === 0) { console.log(cn, col, 'EMPTY'); continue; }
    const buf = Buffer.from(b);
    const out = `D:/tmp/parsec_${tag}_${col}.png`;
    fs.writeFileSync(out, buf);
    // PNG IHDR: width@16 height@20 bitDepth@24 colorType@25
    const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20), ct = buf[25];
    console.log(cn, col, 'len=' + buf.length, w + 'x' + h, 'colorType=' + ct, '->', out);
  }
}
db.close();