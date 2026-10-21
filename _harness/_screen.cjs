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

function files(d) {
  const p = path.join(H, d);
  if (!fs.existsSync(p)) return [];
  return fs.readdirSync(p).filter(f => /\.png$/i.test(f));
}

const rows = idx();
console.log('corpus_runtime rows =', rows.length);

// cls x mono
const tab = new Map();
for (const r of rows) {
  const k = `${r.cls} | mono=${r.mono}`;
  tab.set(k, (tab.get(k) || 0) + 1);
}
console.log('--- 容器类型 × 是否自带单色层 ---');
[...tab.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, v]) => console.log(String(v).padStart(4), k));

// helper: pkg of a filename in a dir (index keys by pkg via file prefix)
const byFile = new Map();
for (const r of rows) byFile.set(r.file, r);
const byPkg = new Map();
for (const r of rows) {
  if (!byPkg.has(r.pkg)) byPkg.set(r.pkg, r);
}

function classify(d) {
  const fs2 = files(d);
  const hit = [], miss = [];
  for (const f of fs2) {
    const base = f.replace(/_mono\.png$/, '').replace(/_fg\.png$/, '');
    let r = byFile.get(f) || byFile.get(base + '.png');
    if (!r) {
      // match by pkg prefix
      const pkg = [...byPkg.keys()].find(p => f.startsWith(p.replace(/[^a-zA-Z0-9._-]/g, '_')) || f.startsWith(p));
      if (pkg) r = byPkg.get(pkg);
    }
    if (r) hit.push(r); else miss.push(f);
  }
  return { hit, miss };
}

for (const d of ['rec3u', 'corpus_audit3', 'rec_blank']) {
  const { hit, miss } = classify(d);
  const mono = hit.filter(r => r.mono === '有');
  const noMono = hit.filter(r => r.mono === '无');
  console.log(`\n--- ${d}: png=${files(d).length} 认到=${hit.length} 认不到=${miss.length} ---`);
  console.log(`   mono=有(直通、上不了屏)=${mono.length}  mono=无(进模型)=${noMono.length}`);
  if (miss.length) console.log('   认不到样本:', miss.slice(0, 5).join(' '));
  if (mono.length) console.log('   直通名单:', mono.map(r => r.pkg).slice(0, 70).join(' '));
}
