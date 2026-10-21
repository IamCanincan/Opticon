// 按 **sig** 对表真机 ↔ 判据台语料（不是按包名）。
//
// 为什么要单独一台：`rec_model` 的文件名里带的是抓取那一轮的 sig，真机 diag 行带的是**现在**的 sig。
// 按包名 join 会把两次抓取当成同一张来比 —— 拼多多那张 10% 的「盘实」差就是这么造出来的。
// sig 是 8×8 形状+颜色指纹，它是这两侧唯一共有的字段。
//
// 用法：node _sigjoin.cjs [真机日志] [语料 bench 文件] [--pid=12576]
//
// ⚠ **默认只取"最后一个 pid"的那批行**：一份 `logcat -d` 原始缓冲横跨多次模块加载，
//   而每次加载吃的是**当时那个 APK** —— 混在一起数，报的是"两版代码的字段并集"。
//   实测：缓冲里 pid 534 那批行的判据串**根本没有 `调跨=`/`调未算=` 这两列**（它们属于
//   加这两列之前的那次构建），47 行就这样落进"三个字段都没有"的桶里 —— 看着像判据有洞，
//   其实是分母混了版本。`--pid=all` 才把全部进程的行一起收。
const fs = require('fs');
const LOG = process.argv[2] || '_devlog.txt';
const BENCH = process.argv[3] || 'bench/reg/bench_384.txt';
const PID = (process.argv.find(a => a.startsWith('--pid=')) || '').split('--pid=')[1];

const FIELDS = ['底占', '盘实', '框内', '分档'];
const grab = (s, k) => { const m = s.match(new RegExp('(?:^| )' + k + '=([^ ]*)')); return m ? m[1] : '-'; };

// ── 真机：只收**带 report** 的 diag 行（缓存命中行整条不打判据，那些不算读数）──
const lines = fs.readFileSync(LOG, 'utf8').split('\n');
const pidOf = l => l.split(/\s+/)[2];
const withRep = lines.filter(l => /Mono: diag/.test(l) && l.includes('盘实='));
if (withRep.length === 0) { console.log('!! 这份日志里没有一条带判据的 diag 行 —— 空分母不当结论'); process.exit(1); }
// "最后一个 pid" = 缓冲里**最晚**那批带判据的行所属的进程（logcat 是时间序的）。
const wantPid = PID === 'all' ? null : (PID || pidOf(withRep[withRep.length - 1]));
const dev = new Map();          // sig -> {label, fields}
let devNoSig = 0, devOtherPid = 0;
for (const l of lines) {
  if (!/Mono: diag/.test(l)) continue;
  if (!l.includes('盘实=')) { devNoSig++; continue; }
  if (wantPid && pidOf(l) !== wantPid) { devOtherPid++; continue; }
  const sig = grab(l, 'sig');
  if (sig === '-') continue;
  const label = (l.match(/diag#\d+ edge=\d+ sig=[0-9a-f]+ 键=\S+ (\S+)/) || [])[1] || '?';
  const f = {}; FIELDS.forEach(k => f[k] = grab(l, k));
  dev.set(sig, { label, f });   // 后到的覆盖前面的：要的是屏上最新那一版
}

// ── 语料：bench_384.txt 里 `--- <名字含 sig>` + 下一行的判据 ──
const corp = new Map();
const bl = fs.readFileSync(BENCH, 'utf8').split('\n');
for (let i = 0; i < bl.length; i++) {
  if (!bl[i].startsWith('---')) continue;
  const sig = (bl[i].match(/([0-9a-f]{12})/) || [])[1];
  if (!sig) continue;
  const rep = bl.slice(i + 1, i + 3).join(' ');
  if (!rep.includes('盘实=')) continue;
  const f = {}; FIELDS.forEach(k => f[k] = grab(rep, k));
  corp.set(sig, { name: bl[i].slice(3).trim(), f });
}

let exact = 0, diff = 0, absent = 0;
const diffs = [];
for (const [sig, d] of dev) {
  if (!corp.has(sig)) { absent++; continue; }
  const c = corp.get(sig);
  const bad = FIELDS.filter(k => d.f[k] !== c.f[k]);
  if (bad.length === 0) exact++;
  else { diff++; diffs.push({ sig, label: d.label, bad, dev: d.f, corp: c.f }); }
}

console.log(`== sig 对表：真机 ${dev.size} 个不同 sig（pid ${wantPid || '全部'}，` +
  `${devOtherPid} 行来自别的进程、${devNoSig} 行没有判据 = 缓存命中）／语料 ${corp.size} 个 ==`);
console.log(`  sig 能对上且**逐字相同** ${exact}   对上但**读数不同** ${diff}   真机有、语料没这张 ${absent}`);
for (const x of diffs) {
  console.log(`  !! ${x.label} sig=${x.sig}`);
  for (const k of x.bad) console.log(`       ${k}: 真机 ${x.dev[k]}  语料 ${x.corp[k]}`);
}
// 反向账：语料里那些真机现在压根没喂的张
const orphan = [...corp.keys()].filter(s => !dev.has(s));
console.log(`  语料有、真机本轮没喂 ${orphan.length} 张（**这些的读数从来没有对手**）`);
