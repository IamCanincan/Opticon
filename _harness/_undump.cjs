// 把 logcat 里 `Mono: dump#K src P###/### <base64>` 的分片**按 id 重组**成 PNG。
// 为什么要有它：装机那条路喂给模型的那一张，与 `CorpusCapture` 抓回来的**不一定是同一张**
// （抓取用的是视口几何 `layerBounds`，`rasterizeLayers` 画的是整张 108dp 画布）。
// 拿设备真吃进去的那张重放，才能解释"判据台上刀响了、装机没响"。
const fs = require('fs');
const txt = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/);
const outDir = process.argv[3] || 'D:/tmp/undump';
fs.mkdirSync(outDir, { recursive: true });
// 键必须是 **(进程, dump#, what)**：`dump#N` 每进程各自从 1 数起，一次抓取里 2~4 个进程
// （桌面重启前后各一段）会各自发一组同号不同物的分片。旧版按 (dump#, what) 收、同片号后写覆盖，
// 实测把两个进程的图标拼成了一张 PNG（live2 里 7 组、live 里 10 组）。
// 新包每行带 `p=<十六进制 pid>`；旧日志没这个字段，退回 logcat 前缀里的 `( pid)`。
const procOf = (l, field) => field || (l.match(/\(\s*(\d+)\):/) || [, '0'])[1];
const names = new Map();   // "p|dump#|what" -> {name,total}
const parts = new Map();   // "p|dump#|what" -> {pages:{n:b64}}
const clash = new Set();   // 同一片号来了两份不同内容的组
for (const l of txt) {
  // `id=-1` = 命中路径的缓存 dump（`MonoDump.dumpCached`）。只认 `\d+` 会让整组静默消失 ——
  //   2026-10-23 Pinduoduo 白心案里第一次撞上：17:24 明明打了 `dump#90001`，重组结果是 0 张。
  let m = l.match(/Mono: dump#(\d+)(?: p=(\w+))? BEGIN id=(-?\d+) what=(\w+) parts=(\d+) (\S+)/);
  if (m) { const k = procOf(l, m[2]) + '|' + m[1] + '|' + m[4]; names.set(k, { name: m[6], total: +m[5] }); continue; }
  m = l.match(/Mono: dump#(\d+)(?: p=(\w+))? (\w+) P(\d+)\/(\d+) (\S+)/);
  if (!m) continue;
  const key = procOf(l, m[2]) + '|' + m[1] + '|' + m[3];
  if (!names.has(key)) continue;
  if (!parts.has(key)) parts.set(key, { pages: {} });
  const p = parts.get(key).pages;
  if (p[+m[4]] !== undefined && p[+m[4]] !== m[6] && !clash.has(key)) clash.add(key);
  p[+m[4]] = m[6];
}
let done = 0, skipped = 0;
for (const [key, meta] of names) {
  const p = parts.get(key);
  if (clash.has(key)) { console.log('  混拼拦截 ' + key + ' —— 同片号有两份不同内容，整组不写'); skipped++; continue; }
  if (!p) continue;
  const idx = Object.keys(p.pages).map(Number).sort((a, b) => a - b);
  if (idx.length < meta.total) continue;           // 没收全，别写半张
  const b64 = idx.map(i => p.pages[i]).join('');
  const tag = meta.name.replace(/[^A-Za-z0-9._]/g, '_') + '.p' + key.split('|')[0] + '.' + key.split('|')[2] + '.png';
  try {
    fs.writeFileSync(outDir + '/' + tag, Buffer.from(b64, 'base64'));
    done++;
    if (done <= 12) console.log('  重组 ' + tag + '  ' + fs.statSync(outDir + '/' + tag).size + 'B');
  } catch (e) { console.log('  失败 ' + tag + ' ' + e.message); }
}
console.log('共重组 ' + done + ' 张到 ' + outDir + '（组 ' + names.size + ' / 混拼拦截 ' + skipped + '）');
