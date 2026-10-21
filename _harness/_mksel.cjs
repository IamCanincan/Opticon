const fs = require('fs');
const path = require('path');
const H = __dirname;
const lines = fs.readFileSync(path.join(H, 'corpus_runtime', 'index.tsv'), 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const rows = lines.slice(1).map(l => { const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]); return o; });
const shot = fs.readFileSync(path.join(H, 'bench', 'shot_runtime.txt'), 'utf8').split(/\r?\n/);
const tonal = new Set();
let cur = null;
for (const l of shot) {
  if (l.startsWith('--- ')) { cur = l.slice(4).trim().split(/\s/)[0]; continue; }
  if (cur && /调=1/.test(l)) tonal.add(cur);
}

const which = process.argv[2] || 'yi';
const pick = rows.filter(r => {
  if (r.mono === '有') return false;
  const adaptive = r.cls === 'AdaptiveIconDrawable';
  if (which === 'yi') return adaptive && r.bg !== 'ColorDrawable';
  if (which === 'jia') return adaptive && r.bg === 'ColorDrawable';
  if (which === 'bing') return !adaptive;
  return false;
});
const outDir = path.join(H, 'bench', 'mt_' + which);
fs.mkdirSync(outDir, { recursive: true });
fs.readdirSync(outDir).forEach(f => fs.unlinkSync(path.join(outDir, f)));
const src = path.join(H, 'bench', 'preview');
let n = 0;
const missing = [];
for (const r of pick) {
  const base = r.file.replace(/\.png$/i, '');
  const p = path.join(src, base + '.png');
  if (!fs.existsSync(p)) { missing.push(base); continue; }
  const tag = (tonal.has(base) ? 'T' : 'R') + '_' + String(r.fgAlpha || '').replace(/[\/% ]/g, '-');
  fs.copyFileSync(p, path.join(outDir, r.pkg + "_" + tag + ".png"));
  n++;
}
console.log(which, '拼了', n, '张 →', outDir, ' 缺', missing.length, '满幅支=', [...tonal].length);
