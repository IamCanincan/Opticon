// 从 `dumpsys shortcut` 里把 **bitmap 形式**的快捷方式图标捞成语料目录。
//
// 为什么要这一台：`_shotdump.cjs` 数清楚这台机器上 173 条快捷方式的图标形式是
//   `res=124 / uri=4 / bitmap=45`。`res=` 那批我们早就抓得到（`shortcut_model` 183 张，走资源名），
//   `bitmap=` 那批以前记成"抓不到"—— 但 dump 里写着 `bitmapPath=/data/system_ce/0/shortcut_service/bitmaps/<包>/<时间戳>.png`：
//   **ShortcutService 自己把应用交来的位图落盘了**，root 读得到 ⇒ 这一族的源就在设备上，不用改生产、
//   也不用主人长按任何一个图标。`uri=` 那 4 条仍然是边界，别混进来说成"整类补齐"。
//
// ⚠ **不能按目录扫**：目录里 81 个文件，而被现存快捷方式引用的只有 45 个 —— 其余 36 个是旧版本留下的孤儿。
//   按目录扫会把**没主的图**混进分母（本仓那条"join 键粒度会造出不存在的故障"）。这里一律以 dump 为准。
//
// 用法：node _shotbit.cjs <dumpsys_shortcut.txt> --src=<拉回来的 bitmaps 根> --out=<语料目录>
// 退码：0 全对上；1 有引用文件不在 --src 里（点名，不许静默少一份）；2 解析器自己漏了（数不上）
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const file = process.argv[2];
const opt = (k) => { const a = process.argv.find((s) => s.startsWith('--' + k + '=')); return a ? a.substring(k.length + 3) : null; };
const SRC = opt('src'), OUT = opt('out');
if (!file || !SRC || !OUT) {
  console.log('用法: node _shotbit.cjs <dump> --src=<bitmaps 目录> --out=<语料目录>');
  process.exit(2);
}
const text = fs.readFileSync(file, 'utf8');
const lines = text.split(/\r?\n/);

// ① 独立数一遍"文本里到底有几个 bitmapPath 引用" —— 这是给解析器自己的**正对照**：
//    块解析与正则各数一次，两个数不等就说明有一条我没接住（而不是"这台机器上没有"）。
const rxRefs = [...text.matchAll(/bitmapPath=(\/data\S+?\.png)/g)].map((m) => m[1]);
const rxUri = [...text.matchAll(/iconUri=(content:\/\/[^\s,}]+)/g)].length;

const rows = [];
let cur = null;
const flush = () => {
  if (cur && cur.bmp) rows.push(cur);
  cur = null;
};
for (const raw of lines) {
  const l = raw.trim();
  const s = l.match(/^ShortcutInfo \{id=([^,]+),/);
  if (s) { flush(); cur = { id: s[1], pkg: null, label: '', bmp: null, size: null }; continue; }
  if (!cur) continue;
  const pk = l.match(/^packageName=(\S+)/); if (pk) cur.pkg = pk[1];
  const lb = l.match(/^shortLabel=([^,]*),/); if (lb) cur.label = lb[1];
  const bp = l.match(/^iconRes=\d+\[[^\]]*\], bitmapPath=(\/data\S+?\.png)/); if (bp) cur.bmp = bp[1];
  const bs = l.match(/^bitmap size=(\d+)/); if (bs) cur.size = +bs[1];
}
flush();

console.log(`dump 里 ShortcutInfo 块解析出的 bitmap 引用 ${rows.length} 条｜正则独立数到 ${rxRefs.length} 条｜uri ${rxUri} 条（这一族拿不到，见文件头）`);
if (rows.length !== rxRefs.length) {
  console.log('!! 两个数对不上 —— 解析器漏了条目，这一份不能用（别看成"这台机器上就这么多"）');
  process.exit(2);
}
if (!rows.length) { console.log('!! 一条都没解析出来 —— 先确认这份 dump 是不是 `dumpsys shortcut` 的全量输出'); process.exit(2); }

fs.mkdirSync(OUT, { recursive: true });
const safe = (s) => s.replace(/[^A-Za-z0-9._-]/g, '_');
const used = new Set();
const table = [];
const missing = [];
let dup = 0;
for (const r of rows) {
  // dump 里的绝对路径 → 拉回来的相对路径（`--src` 给的是 `bitmaps` 那一层或其父目录都能接住）
  const rel = r.bmp.replace(/^\/data\/system_ce\/0\/shortcut_service\//, '');
  let p = path.join(SRC, ...rel.split('/'));
  if (!fs.existsSync(p)) p = path.join(SRC, ...r.bmp.split('/').slice(-2));
  if (!fs.existsSync(p)) { missing.push(`${r.pkg}/${r.id} → ${r.bmp}`); continue; }
  const bytes = fs.readFileSync(p);
  if (bytes.subarray(0, 4).toString('hex') !== '89504e47') {
    console.log(`!! ${r.bmp} 不是 PNG（magic=${bytes.subarray(0, 4).toString('hex')}）—— 这份语料不收来路不明的字节`);
    process.exit(1);
  }
  const sig = crypto.createHash('md5').update(bytes).digest('hex').slice(0, 12);
  let name = `${safe(r.pkg)}_${safe(r.id)}_${sig}.png`;
  if (used.has(name)) { dup++; name = `${safe(r.pkg)}_${safe(r.id)}_${sig}_${dup}.png`; }
  used.add(name);
  fs.writeFileSync(path.join(OUT, name), bytes);
  // 19 列，**与 shortcut_model 同一个表头**（`Inputs.kt` 的 indexFlags 按位置读第 6/8 列，顺序不许动）
  table.push([
    r.pkg, `${r.pkg} 快捷方式(自 ShortcutService 落盘位图) ${r.id}`, name, 384, sig,
    'BitmapDrawable', '-', '-', '无', '1', `${bytes.length}/100`, '0/100', '-', '-', '-',
    '-', '-', name, '-',
  ].join('\t'));
}
fs.writeFileSync(path.join(OUT, 'index.tsv'),
  ['pkg', 'label', 'file', 'edge', 'sig', 'cls', 'fg', 'bg', 'mono', 'kb', 'fgAlpha', 'bgAlpha',
    'monoFile', 'monoAlpha', 'bgColor', 'fgFile', 'bgFile', 'modelFile', 'modelBgFile'].join('\t') +
  '\n' + table.join('\n') + '\n');

if (missing.length) {
  console.log(`!! 有 ${missing.length} 条被现存快捷方式引用、却在 --src 里找不到（这就是"分母自己少了"，不许放行）：`);
  missing.slice(0, 8).forEach((m) => console.log('     ' + m));
  process.exit(1);
}
console.log(`SHOTBIT n=${table.length} 落盘=${table.length} 缺=0 uri拿不到=${rxUri} 同名撞车=${dup} 目录=${OUT}`);
