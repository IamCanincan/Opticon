// 「未适配单色层」到底有多少张：按证据分三类，不许用默认值猜。
//   ① `index.tsv`（抓取台账，带 cls + mono 有/无）② 装机日志 `Mono: diag#` 里的类名
//   ③ 两个都没有的 ⇒ 列出来，交给拆 APK 那一步（`aapt2 dump badging/resources`）
// 用法：node _harness/_unadapted.cjs <app_icons.db> <index.tsv> <装机日志>
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const argv = process.argv.slice(2);
const dumpTo = (argv.find((a) => a.startsWith('--dump=')) || '').slice(7) || null;
const rest = argv.filter((a) => !a.startsWith('--dump='));
const [dbp, idxp, logp] = rest;
const idx = new Map();
for (const l of fs.readFileSync(idxp, 'utf8').replace(/\r/g, '').split('\n').slice(1)) {
  const c = l.split('\t');
  if (c.length < 9) continue;
  idx.set(c[0], { cls: c[5], mono: c[8] });
}
const log = fs.readFileSync(logp, 'utf8');
const d = new DatabaseSync(dbp, { readOnly: true });
const pkgs = new Set();
for (const r of d.prepare('SELECT componentName c FROM icons').all()) pkgs.add(r.c.split('/')[0]);

const rows = [];
for (const p of pkgs) {
  if (idx.has(p)) { rows.push([p, '台账', idx.get(p).cls, idx.get(p).mono]); continue; }
  const re = new RegExp('Mono: diag#[^\\n]*' + p.replace(/[.$]/g, '\\$&') + '[^\\n]*', 'g');
  const m = log.match(re);
  if (m) {
    const s = m[0];
    const cls = (s.match(/ (AdaptiveIconDrawable|BitmapDrawable|VectorDrawable|InsetDrawable|LayerDrawable|ScaleDrawable|ColorDrawable)/) || [, '?'])[1];
    const adaptive = (s.match(/adaptive=(\w+)/) || [, '?'])[1];
    rows.push([p, '日志', cls + (adaptive === '?' ? '' : ` (adaptive=${adaptive})`), '?']);
  } else rows.push([p, '**都没有**', '要拆 APK', '?']);
}
const tally = {};
for (const [, src] of rows) tally[src] = (tally[src] || 0) + 1;
console.log(`库里 ${pkgs.size} 个包，身份来源：` + Object.entries(tally).map(([k, v]) => `${k} ${v}`).join('、'));
const need = rows.filter((r) => r[1] === '**都没有**');
console.log(`\n没有单色层（mono=无 / 老式）的、以及身份待查的：`);
for (const [p, src, cls, mono] of rows) {
  const unadapted = mono === '无' || (mono === '?' && /Bitmap|Vector|老式|要拆/.test(cls));
  if (unadapted || src === '**都没有**') {
    console.log(`  ${p.padEnd(36)} ${String(mono).padEnd(3)} ${cls.padEnd(34)} 来自${src}`);
  }
}

if (dumpTo) {
  fs.writeFileSync(dumpTo, JSON.stringify(rows.map(([p, src, cls, mono]) => ({ pkg: p, src, cls, mono })), null, 1));
  console.log(`已写身份表 ${dumpTo}（${rows.length} 个包）`);
}
