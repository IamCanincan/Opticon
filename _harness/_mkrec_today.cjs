// 把 `_undump.cjs` 重组出来的**设备真输入**（`*.src.png`）建成一份能进判据台的语料。
//
// 为什么需要：`CorpusCapture` 存的合成图是**已经画上圆角遮罩**的那一张（translate 实心 78%、四角透明），
// 而模型吃进去的是**整张 108dp 画布**（同一张实心 100%、四角不透明）；贴吧反过来，
// 设备输入内容只占 69%、抓的那张占 99%。所以今天所有拿 `corpus_runtime` 量的账量的都不是装机那张。
// 这里按包名去 `corpus_new/index.tsv` 里取 `cls/bg/mono`，凑出判据台要的 `index.tsv`
// （`plateIsLayer` 那一位必须对，否则量的又不是装机那条路）。
const fs = require('fs');
const SRC = 'D:/tmp/undump', IX = 'D:/tmp/corpus_new/index.tsv', OUT = process.argv[2] || 'D:/tmp/rec_today';
fs.mkdirSync(OUT, { recursive: true });
const rows = fs.readFileSync(IX, 'utf8').trim().split('\n');
const head = rows[0].split('\t');
const byPkg = new Map();
for (const l of rows.slice(1)) { const c = l.split('\t'); if (!byPkg.has(c[0])) byPkg.set(c[0], c); }
const out = [head.join('\t')];
let n = 0, noPkg = 0;
for (const f of fs.readdirSync(SRC).filter(x => x.endsWith('.src.png')).sort()) {
  const base = f.replace(/\.src\.png$/, '');
  let pkg = null;
  for (const p of byPkg.keys()) if (base.startsWith(p + '_') && (!pkg || p.length > pkg.length)) pkg = p;
  if (!pkg) { noPkg++; continue; }
  const c = byPkg.get(pkg).slice();
  const name = base + '.png';
  fs.copyFileSync(SRC + '/' + f, OUT + '/' + name);
  c[2] = name;                                   // 判据台按 `file` 列取图
  out.push(c.join('\t')); n++;
}
fs.writeFileSync(OUT + '/index.tsv', out.join('\n') + '\n');
console.log('建好 ' + n + ' 张（按包名对上 index 的）到 ' + OUT + '；对不上包名的 ' + noPkg + ' 张');
const mono = out.slice(1).filter(l => l.split('\t')[8] === '有').length;
const lay = out.slice(1).filter(l => l.split('\t')[5].includes('Adaptive') && l.split('\t')[7] === 'ColorDrawable').length;
console.log('其中已适配（直通、不该进分母）' + mono + ' 张，走"只给前景层"那条的 ' + lay + ' 张');
