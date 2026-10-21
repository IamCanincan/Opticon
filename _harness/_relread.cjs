/*
 * 发布版真机读数：缓存两层的名额、缩略图归属、命中账、时间账。
 * 为什么单独写一个文件而不是临时 -e：这一晚上我把 positional group / 字面量写死 /
 * 过滤条件误伤 这三类解析错误各犯过一次，每次产出的都是"看着合理的数"。
 * 所以这里每条数都要**自证分母**：某类为 0 时，先证明"这一类在日志里真的存在过"。
 */
const fs = require('fs');
const file = process.argv[2];
const PID = process.argv[3] || '8912';
const L = fs.readFileSync(file, 'utf8').split(/\r?\n/);

const grab = (l, n) => {
  const i = l.indexOf(n + '=');
  if (i < 0) return null;
  const j = l.indexOf(' ', i);
  return j < 0 ? l.slice(i + n.length + 1) : l.slice(i + n.length + 1, j);
};

let rec = 0, hitMem = 0, hitDisk = 0, neg = 0, heavy = 0, asciiBlocks = 0, orphan = 0;
let curHit = null, t0 = 1e15, t1 = 0, diagNums = new Set(), sumLines = [], warn = [];
const firstTimes = [];

for (const l of L) {
  if (l.indexOf(' Opticon : ') < 0) continue;
  if (!l.includes(PID)) continue;
  const h = l.match(/^(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)/);
  const t = h ? +h[2] * 3600000 + +h[3] * 60000 + +h[4] * 1000 + +h[5] : null;

  const d = l.match(/Mono: diag#(\d+) edge=(\d+)( sig=([0-9a-f]{12}))?( 键=([0-9a-f]+))?/);
  if (d) {
    if (t !== null) { t0 = Math.min(t0, t); t1 = Math.max(t1, t); }
    diagNums.add(d[1]);
    const isHit = l.indexOf('缓存=') >= 0;
    curHit = isHit;
    if (isHit) { l.includes('缓存=内存') ? hitMem++ : hitDisk++; if (l.includes('负缓存')) neg++; }
    else if (d[4]) rec++;
    if (t !== null) firstTimes.push(t);
    continue;
  }
  // 体检行与缩略图的归属统一在第二趟算（这里只数总量与警告），免得变量还没声明就被引用
  if (/ in=\d+x\d+/.test(l) && /out=/.test(l)) { heavy++; continue; }
  if (/Mono: diag#(\d+) mask 0\|/.test(l)) asciiBlocks++;
  if (l.indexOf('掩码缓存 命中=') >= 0) sumLines.push(l.slice(l.indexOf('Mono:')));
  if (/写不进去|对不上|解不开|拿不到|超上限/.test(l)) warn.push(l.slice(0, 150));
}

let hitMemDiag = 0, coldDiag = 0, hitAscii = 0, coldAscii = 0;
// 上面那条 continue 之前变量还没声明，重跑一遍归属（同一份日志，代价可忽略）
curHit = null; hitMemDiag = coldDiag = hitAscii = coldAscii = orphan = 0;
for (const l of L) {
  if (!l.includes(PID) || l.indexOf(' Opticon : ') < 0) continue;
  if (l.match(/Mono: diag#(\d+) edge=\d+/)) { curHit = l.indexOf('缓存=') >= 0; continue; }
  if (/ in=\d+x\d+/.test(l) && /out=/.test(l)) { if (curHit) hitMemDiag++; else coldDiag++; if (curHit === null) orphan++; continue; }
  if (/Mono: diag#(\d+) mask 0\|/.test(l)) { if (curHit) hitAscii++; else coldAscii++; if (curHit === null) orphan++; }
}

firstTimes.sort((a, b) => a - b);
const g = []; for (let i = 1; i < firstTimes.length; i++) g.push(firstTimes[i] - firstTimes[i - 1]);
const sg = g.slice().sort((a, b) => a - b);
const q = x => sg[Math.floor(x * (sg.length - 1))] || 0;

console.log(`pid ${PID} | 判据行：现算 ${rec} / 命中 ${hitMem + hitDisk}（内存 ${hitMem} + 磁盘 ${hitDisk}）`);
console.log(`窗口 ${((t1 - t0) / 1000).toFixed(1)}s | 相邻判据行 p50 ${q(.5)}ms p90 ${q(.9)}ms`);
console.log(`前 40 / 120 / 300 次请求用时：${((firstTimes[39] - firstTimes[0]) / 1000).toFixed(2)}s / ${((firstTimes[119] - firstTimes[0]) / 1000).toFixed(2)}s / ${((firstTimes[299] - firstTimes[0]) / 1000).toFixed(2)}s`);
console.log(`重诊断体检行：来自现算 ${coldDiag} | 来自命中 ${hitMemDiag}（名额分路 = 两边各自有数）`);
console.log(`ASCII 缩略图块：属于现算 ${coldAscii} | 属于命中 ${hitAscii} | 无主 ${orphan}`);
console.log(`负缓存留痕：${neg} 条`);
console.log('汇总行：' + (sumLines.length ? sumLines.slice(-2).join(' ;; ') : '一条都没有'));
console.log(`缓存警告：${warn.length} 条` + (warn.length ? '\n  ' + warn.join('\n  ') : ''));
