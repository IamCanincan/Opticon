const fs = require('fs');
const path = require('path');
const H = __dirname;

function idx() {
  const lines = fs.readFileSync(path.join(H, 'corpus_runtime', 'index.tsv'), 'utf8').trim().split(/\r?\n/);
  const head = lines[0].split('\t');
  return lines.slice(1).map(l => {
    const c = l.split('\t');
    const o = {};
    head.forEach((k, i) => o[k] = c[i]);
    return o;
  });
}
const rows = idx();
const pkgs = new Set(rows.map(r => r.pkg));
const byPkg = new Map(rows.map(r => [r.pkg, r]));

function guessPkg(f) {
  const cands = [...pkgs].filter(p => f.includes(p)).sort((a, b) => b.length - a.length);
  return cands[0] || null;
}

for (const d of ['rec3u', 'rec_blank', 'live61']) {
  const p = path.join(H, d);
  if (!fs.existsSync(p)) { console.log(d, 'MISSING'); continue; }
  const fsn = fs.readdirSync(p).filter(x => /\.png$/i.test(x));
  const hit = [], miss = [];
  for (const f of fsn) { const k = guessPkg(f); if (k) hit.push(k); else miss.push(f); }
  const u = [...new Set(hit)];
  const monoYes = u.filter(k => byPkg.get(k).mono === '有');
  const monoNo = u.filter(k => byPkg.get(k).mono === '无');
  const bmp = u.filter(k => byPkg.get(k).cls === 'BitmapDrawable');
  console.log(`\n=== ${d}: ${fsn.length} png, 认到包名 ${u.length}, 认不到 ${miss.length}`);
  console.log(`   mono=有=${monoYes.length} mono=无=${monoNo.length} BitmapDrawable=${bmp.length}`);
  if (monoYes.length) console.log('   ⚠ 直通却出现在设备语料里:', monoYes.join(' '));
  if (miss.length) console.log('   认不到:', miss.slice(0, 8).join(' '));
}
