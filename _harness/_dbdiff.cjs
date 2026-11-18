const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');
function load(p) {
  const db = new DatabaseSync(p, { readOnly: true });
  const m = new Map();
  for (const r of db.prepare("SELECT componentName, icon FROM icons").all()) {
    m.set(r.componentName, r.icon && r.icon.length ? crypto.createHash('md5').update(Buffer.from(r.icon)).digest('hex') : 'empty');
  }
  db.close();
  return m;
}
const a = load(process.argv[2]), b = load(process.argv[3]);
let changed = 0, same = 0, onlyB = 0;
const ex = [];
for (const [k, v] of b) {
  if (!a.has(k)) { onlyB++; continue; }
  if (a.get(k) !== v) { changed++; if (ex.length < 15) ex.push(k); } else same++;
}
console.log('same=' + same, 'changed=' + changed, 'onlyInB=' + onlyB);
console.log(ex.join('\n'));