const fs = require('fs');
const path = require('path');
const H = __dirname;

function readIdx(d) {
  const lines = fs.readFileSync(path.join(H, d, 'index.tsv'), 'utf8').trim().split(/\r?\n/);
  const head = lines[0].split('\t');
  return lines.slice(1).map(l => { const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]); return o; });
}
const rt = readIdx('corpus_runtime');
const byFile = new Map(rt.map(r => [r.file.replace(/\.png$/i, ''), r]));
const pkgs = [...new Set(rt.map(r => r.pkg))];

function chan(r) {
  if (!r) return '未匹配';
  if (r.mono === '有') return '直通(不上屏)';
  if (r.cls === 'AdaptiveIconDrawable')
    return r.bg === 'ColorDrawable' ? '甲 只吃前景层' : '乙 层合并';
  return '丙 整幅位图';
}
function pkgOf(name) {
  const r = byFile.get(name);
  if (r) return r;
  const p = pkgs.filter(p => name.includes(p)).sort((a, b) => b.length - a.length)[0];
  return p ? rt.find(r => r.pkg === p) : null;
}

function parse(file) {
  const txt = fs.readFileSync(path.join(H, file), 'utf8').split(/\r?\n/);
  const out = [];
  let cur = null;
  for (const l of txt) {
    if (l.startsWith('--- ')) { cur = { name: l.slice(4).trim().split(/\s/)[0] }; out.push(cur); continue; }
    if (!cur) continue;
    const g = (k) => { const m = l.match(new RegExp(k + '=([^ ]+)')); return m ? m[1] : null; };
    const cut = g('盘切'); if (cut) cur.cut = cut;
    const pol = g('极性'); if (pol) cur.pol = pol;
    const air = g('气'); if (air) cur.air = air;
    const ton = /调=1/.test(l) ? 1 : null; if (ton) cur.tonal = ton;
    const kw = g('框内'); if (kw) cur.box = kw;
    const main = g('主色'); if (main) cur.flat = main;
  }
  return out;
}

function report(file, label) {
  const rows = parse(file);
  const tab = new Map();
  for (const r of rows) {
    const k = chan(pkgOf(r.name));
    const t = tab.get(k) || { n: 0, tonal: 0, cut: 0, pol: 0, list: [] };
    t.n++;
    if (r.tonal) t.tonal++;
    if (r.cut && r.cut.split('/')[0] === '1') { t.cut++; t.list.push(r.name.slice(0, 40) + ' 切' + r.cut + ' 框内' + r.box + ' 主色' + r.flat); }
    if (r.pol === '1') t.pol++;
    tab.set(k, t);
  }
  console.log(`\n######## ${label} (${rows.length} 张) ########`);
  for (const [k, t] of [...tab.entries()].sort((a, b) => b[1].n - a[1].n)) {
    console.log(`${k.padEnd(14)} 张=${String(t.n).padStart(3)} 走满幅=${String(t.tonal).padStart(3)} 盘切开火=${String(t.cut).padStart(3)} 翻极性=${String(t.pol).padStart(3)}`);
    t.list.forEach(x => console.log('      ·', x));
  }
}
report('bench/plateProbe_rec3u.txt', 'rec3u = 真机模型实际吃进去的那 62 张');
report('bench/plateProbe_runtime.txt', 'corpus_runtime = 123 张合成图（⚠ 甲档这里喂的是合成图，不是真机输入）');
