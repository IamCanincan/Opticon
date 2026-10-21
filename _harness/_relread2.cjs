/*
 * 发布版真机读数（第二版解析）：pid 必须按**列**取，不能 includes()。
 * 为什么要重测：上一版我用 l.includes("11156") 过滤，任何字段含这串数字的行都会被算进来；
 * 而名额分路的预期（冷 12 / 命中 8 两套）与实际读出的块数差 2 块 —— 我不能拿一个
 * 自己没核对过的差去说"生效了"。
 * 用法：node _relread2.cjs <日志> <pid>
 */
const fs = require('fs');
const [file, PID] = [process.argv[2], process.argv[3]];
if (!file || !PID) { console.log('!! 用法：node _relread2.cjs <日志> <pid>'); process.exit(1); }
const L = fs.readFileSync(file, 'utf8').split(/\r?\n/);

// threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L Opticon : ..."
const COL = new RegExp('^\\d\\d-\\d\\d (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d\\d\\d)\\s+(\\d+)\\s+(\\d+)\\s+[DWIEV]\\s+Opticon\\s*: ');
const rows = [];
for (const l of L) {
  const h = l.match(COL);
  if (!h) continue;
  if (h[5] !== PID) continue;                       // ← 按列比，不是 includes
  const t = +h[1] * 3600000 + +h[2] * 60000 + +h[3] * 1000 + +h[4];
  const n = (l.match(/Mono: diag#(\d+)/) || [, null])[1];
  const kind = l.indexOf('Mono: diag#') >= 0 && /缓存=/.test(l) ? 'hit'
    : l.indexOf('Mono: diag#') >= 0 && / sig=/.test(l) ? 'rec'
      : / in=\d+x\d+/.test(l) && /out=/.test(l) ? 'heavy'
        : /Mono: diag#\d+ mask 0\|/.test(l) ? 'ascii' : null;
  if (kind) rows.push({ t, n, kind, line: l });
}
if (!rows.length) { console.log('!! 这个 pid 一行都没抓到（列格式不对？），下面的数一条都别信'); process.exit(1); }

// 稠密段（一次重启的突发）：相邻间隔 > 30s 就断开，取最长的一段
const sorted = rows.slice().sort((a, b) => a.t - b.t);
const segs = []; let cur = [sorted[0]];
for (let i = 1; i < sorted.length; i++) {
  if (sorted[i].t - sorted[i - 1].t > 30000) { segs.push(cur); cur = []; }
  cur.push(sorted[i]);
}
segs.push(cur);
const seg = segs.reduce((a, b) => (b.length > a.length ? b : a));
const cnt = (k) => seg.filter(r => r.kind === k).length;
const hits = seg.filter(r => r.kind === 'hit');
const mem = hits.filter(r => r.line.indexOf('缓存=内存') >= 0).length;

// 时间账：按**请求**（hit+rec）排序后取第 40/120/300 次
const reqs = seg.filter(r => r.kind === 'hit' || r.kind === 'rec');
const at = (i) => i < reqs.length ? ((reqs[i].t - reqs[0].t) / 1000).toFixed(2) + 's' : '不足';
const gaps = []; for (let i = 1; i < reqs.length; i++) gaps.push(reqs[i].t - reqs[i - 1].t);
const sg = gaps.slice().sort((a, b) => a - b);
const q = (x) => sg.length ? sg[Math.floor(x * (sg.length - 1))] : 0;

// 名额归属：体检行挂在**前一条判据行**上；缩略图块挂在**前一条体检行**上 ——
// 因为 noteDiagnosis 自己也会占一个 diag# 号（`val n = diagnosed.incrementAndGet()`），
// 块的编号并不等于判据行的编号。上一版拿判据行去接块，冷那半就被算成"无主"。
let owner = null, heavyOwner = null;
const heavyBy = { hit: 0, rec: 0, 无主: 0 }, asciiBy = { hit: 0, rec: 0, 无主: 0 };
for (const r of seg) {
  if (r.kind === 'hit') { owner = 'hit'; heavyOwner = null; }
  else if (r.kind === 'rec') { owner = 'rec'; heavyOwner = null; }
  else if (r.kind === 'heavy') { heavyBy[owner || '无主']++; heavyOwner = owner; }
  else if (r.kind === 'ascii') { asciiBy[heavyOwner || '无主']++; }
}
console.log(`pid ${PID} | 突发段 ${((seg[seg.length - 1].t - seg[0].t) / 1000).toFixed(1)}s，${seg.length} 行` +
  `（本次日志里这个 pid 共 ${rows.length} 行，切成 ${segs.length} 段）`);
console.log(`   现算 ${cnt('rec')} | 命中 ${hits.length}（内存 ${mem} + 磁盘 ${hits.length - mem}）` +
  ` | 体检行 ${cnt('heavy')} | 缩略图块 ${cnt('ascii')}`);
console.log(`   请求间隔 p50 ${q(.5)}ms p90 ${q(.9)}ms | 前 40/120/300 次：${at(39)} / ${at(119)} / ${at(299)}`);
console.log(`   体检行归属 命中 ${heavyBy.hit} / 现算 ${heavyBy.rec} / 无主 ${heavyBy.无主}` +
  ` | 缩略图归属 命中 ${asciiBy.hit} / 现算 ${asciiBy.rec} / 无主 ${asciiBy.无主}`);
const warn = L.filter(l => l.includes('Opticon') && /写不进去|对不上|解不开|拿不到|超上限/.test(l));
console.log(`   缓存警告 ${warn.length} 条` + (warn.length ? '\n     ' + warn.slice(0, 4).map(w => w.slice(0, 130)).join('\n     ') : ''));
const sum = L.filter(l => l.indexOf('掩码缓存 命中=') >= 0 && (l.match(COL) || [null,null,null,null,null,PID])[5] === PID);
console.log('   计数行最后两条: ' + (sum.length ? sum.slice(-2).map(s => s.slice(s.indexOf('Mono:'))).join(' ;; ') : '无'));
