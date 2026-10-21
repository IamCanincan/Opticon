const fs = require('fs');
const path = require('path');
const H = __dirname;
const lines = fs.readFileSync(path.join(H, 'corpus_runtime', 'index.tsv'), 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const rt = lines.slice(1).map(l => { const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]); return o; });
const byFile = new Map(rt.map(r => [r.file.replace(/\.png$/i, ''), r]));
const pkgs = [...new Set(rt.map(r => r.pkg))];
function row(name) {
  if (byFile.has(name)) return byFile.get(name);
  const p = pkgs.filter(p => name.includes(p)).sort((a, b) => b.length - a.length)[0];
  return p ? rt.find(r => r.pkg === p) : null;
}
function chan(r) {
  if (!r) return '未匹配';
  if (r.mono === '有') return '直通';
  if (r.cls === 'AdaptiveIconDrawable') return r.bg === 'ColorDrawable' ? '甲' : '乙';
  return '丙';
}
function parse(f) {
  const out = new Map();
  let cur = null;
  for (const l of fs.readFileSync(path.join(H, f), 'utf8').split(/\r?\n/)) {
    if (l.startsWith('--- ')) { cur = l.slice(4).trim().split(/\s/)[0]; continue; }
    if (!cur) continue;
    let m = out.get(cur); if (!m) out.set(cur, m = {});
    const g = k => { const x = l.match(new RegExp(k + '=([^ ]+)')); return x ? x[1] : null; };
    if (g('极性')) m.pol = g('极性');
    if (g('框内')) m.box = g('框内');
    if (g('字均净')) m.net = g('字均净');
    if (g('淡')) m.dan = g('淡');
    if (/调=1/.test(l)) m.tonal = 1;
  }
  return out;
}
const [base, next] = process.argv.slice(2);
const b = parse(base), n = parse(next);
let ch = 0;
console.log('包名/文件'.padEnd(46) + '通道  极性      框内        字均净       淡');
for (const [k, nb] of n) {
  const ob = b.get(k); if (!ob) continue;
  if (ob.pol === nb.pol && ob.box === nb.box && ob.net === nb.net && ob.dan === nb.dan) continue;
  ch++;
  const r = row(k);
  console.log(k.slice(0, 44).padEnd(46) + chan(r).padEnd(5) +
    `${ob.pol}→${nb.pol}    ${String(ob.box)}→${String(nb.box)}    ${String(ob.net)}→${String(nb.net)}   ${ob.dan}→${nb.dan}`);
}
console.log(`变了 ${ch} 张 / 共 ${n.size}`);
