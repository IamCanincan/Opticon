// **全空掩码那一族：直接数磁盘上交付的 `.a8`，不靠日志认主。**
//
// 为什么现在能问这句话（2026-10-21）：归属索引 `index.tsv` 已经装机，键→标签能逐张配对；
//   而 `dac950aa…` 那把挂了一整天，靠的就是"下一次它出现时索引带上它"。
// ⚠ 键 = 内容哈希，**不是组件主键**：同一张图不同边长是两个键；这张表数的是"交付了几张掩码"，不是"几个图标"。
// 跑法：node _maskzero.cjs <解出来的 opticon-mono/<身份> 目录>
//   正对照：`--selftest` 会当场造一把全零掩码，验这条判据不是恒 false 的空尺。
const fs = require('fs');
const path = require('path');

const HEADER = 8;               // MonoCache 的头：宽/高/边长那一截，像素从第 9 字节起
const readMask = f => {
  const b = fs.readFileSync(f);
  return { bytes: b, px: b.subarray(HEADER) };
};
const stat = px => {
  let max = 0, ink = 0, sum = 0;
  for (let i = 0; i < px.length; i++) { const v = px[i]; if (v > max) max = v; if (v > 0) ink++; sum += v; }
  return { max, ink, inkPct: (100 * ink) / px.length, mean: sum / px.length, n: px.length };
};

const dir = process.argv.slice(2).find(a => !a.startsWith('--'));
if (!dir || !fs.existsSync(dir)) { console.error('用法: node _maskzero.cjs <身份目录> [--selftest]'); process.exit(2); }

const idxFile = path.join(dir, 'index.tsv');
const label = new Map();
let idxRows = 0;
if (fs.existsSync(idxFile)) {
  for (const l of fs.readFileSync(idxFile, 'utf8').replace(/\r/g, '').split('\n')) {
    const m = /^([0-9a-f]{1,16})\t(\d+)\t(.*)$/.exec(l);
    if (!m) continue;
    idxRows++;
    label.set(`${m[1]}_${m[2]}`, m[3]);
  }
}

const files = fs.readdirSync(dir).filter(f => f.endsWith('.a8')).sort();
const zero = [], thin = [];
let ink128 = 0;
const perEdge = {};
for (const f of files) {
  const { px } = readMask(path.join(dir, f));
  const s = stat(px);
  const edge = f.split('_').pop().replace('.a8', '');
  perEdge[edge] = (perEdge[edge] || 0) + 1;
  if (s.max === 0) zero.push([f, s]);
  else if (s.inkPct < 1) thin.push([f, s]);
  if (s.max >= 128) ink128++;
}

console.log(`身份目录 ${path.basename(dir)}：${files.length} 张 .a8（index.tsv ${idxRows} 行、能配上标签 ${label.size}）`);
console.log(`边长分布 ${Object.entries(perEdge).sort().map(([k, v]) => k + '×' + v).join(' ')}`);
console.log(`**整张全零（max α = 0）：${zero.length}** / 有墨（max α ≥ 128）：${ink128} / 淡到 <1% 上墨但非零：${thin.length}`);
for (const [f, s] of zero) console.log(`  🔴 全零 ${f}  格数=${s.n}  标签=${label.get(f.replace('.a8', '')) ?? '(index 里没这行)'}`);
for (const [f, s] of thin.slice(0, 6)) console.log(`  淡 ${f} 上墨 ${s.inkPct.toFixed(2)}% 均 α=${s.mean.toFixed(1)}  标签=${label.get(f.replace('.a8', '')) ?? '(无)'}`);

const known = files.find(f => f.startsWith('dac950aa'));
console.log(`那把挂了昨天的 dac950aa…：${known ? '出现了 ⇒ ' + known + ' 标签=' + (label.get(known.replace('.a8', '')) ?? '(index 无)') : '这一层里没有（0 命中）'}`);

if (process.argv.includes('--selftest')) {
  const fake = Buffer.alloc(HEADER + 192 * 192, 0);
  const s = stat(fake.subarray(HEADER));
  const okZero = s.max === 0;
  const fake2 = Buffer.alloc(HEADER + 192 * 192, 0); fake2[HEADER + 100] = 200;
  const s2 = stat(fake2.subarray(HEADER));
  const okNonzero = s2.max === 200 && s2.ink === 1;
  console.log(`正对照：造一把全零 ⇒ 判到=${okZero}；造一把只有一格有墨 ⇒ 判到=${okNonzero}`);
  if (!okZero || !okNonzero) { console.error('!! 这把尺自己没牙，上面那两个数不许引用'); process.exit(9); }
}
