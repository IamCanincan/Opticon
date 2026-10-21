// 把 `SizeVarKt --all` 的明细行分类：**档数**变了（掉档/涨档）还是只有每档浓度动了几个单位。
// 为什么要分：掉档 = 屏幕上"层次少一层"，是能看见的；浓度 ±2 = 同一层的深浅，量级完全不同。
// 用法：node _levelvar.cjs [SizeVarKt 的输出文件]
const fs = require('fs');
const f = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : '_sv_prod.txt';
const dbg = process.argv.includes('--dbg');
const lines = fs.readFileSync(f, 'utf8').split('\n').filter(l => /^ {2}\d+\s{2}\S/.test(l));
if (lines.length === 0) { console.log('!! 明细行 0 条 —— 上游没跑或过滤条件变了，别当"没有漂移"'); process.exit(1); }

const rows = [];
let marks = 0;
for (const l of lines) {
  const name = l.trim().split(/\s+/)[1];
  // 明细行的形状是 `  N  <名> 192:<改了哪些> 288:<改了哪些>`。
  // **不用零宽断言切段**：`\d` 嵌进 `new RegExp(字符串)` 要两层转义，写错一层不会报错，
  // 只会让**其中一档的读数全空**（本轮就是这么得到"192 掉档 11、288 掉档 0"的假形状）。
  // 按字面标记切，切不出来就算 0 —— 那种失败会当场露出来。
  const toks = l.split(' ');
  for (let i = 0; i < toks.length; i++) {
    if (!/^[0-9]{3}:/.test(toks[i])) continue;
    const via = +toks[i].slice(0, 3);
    marks++;
    let body = toks[i].slice(3);
    for (let j = i + 1; j < toks.length && !/^[0-9]{3}:/.test(toks[j]); j++) body += ' ' + toks[j];
    if (dbg) console.log(`   [dbg] ${via} 段：${body.slice(0, 60)}`);
    if (!/分档=/.test(body)) continue;
    // 段里 `分档=A/B(...)→C/D(...)`：第一个 a/b(...) 是原样，最后一个是喂成这一档的结果
    const all = [...body.matchAll(/([0-9]+)\/([0-9]+)\(([^)]*)\)/g)];
    if (all.length < 2) continue;
    const x = all[0], y = all[all.length - 1];
    const before = +x[1], after = +y[1];
    const vb = x[3].split(',').map(Number), va = y[3].split(',').map(Number);
    // 档数变了的时候两组浓度对不齐 —— **不许填 999**（那是个会被人当读数的数）。记 null，名单里写 `—`。
    const maxd = vb.length === va.length ? Math.max(...vb.map((v, k) => Math.abs(v - va[k]))) : null;
    rows.push({ name, via, before, after, maxd, branch: /支=/.test(body) });
  }
}

const cnt = (p) => rows.filter(p).length;
// 自检：`marks` 是切到的「张×尺寸」段数，`rows` 是其中含 `分档=` 的那些。
// 段里没有 `分档=` 是**合法的**（那张在这个尺寸上动的是别的字段），但 **marks 为 0 一定是解析坏了**。
if (marks === 0) { console.log('!! 一个尺寸段都没切出来 —— 是解析坏了，不是"没有漂移"'); process.exit(1); }
const per = v => rows.filter(r => r.via === v);
console.log(`== 分档随源尺寸的变化分类（${rows.length} 个读数 / ${marks} 个「张×尺寸」段，尺寸 ${[...new Set(rows.map(r => r.via))].join('/')}）==`);
for (const v of [...new Set(rows.map(r => r.via))].sort((a, b) => a - b)) {
  const p = per(v);
  console.log(`  喂成 ${v}：掉档 ${p.filter(r => r.after < r.before).length} / 涨档 ${p.filter(r => r.after > r.before).length}` +
    ` / 档数不动只动浓度 ${p.filter(r => r.after === r.before).length}（共 ${p.length}）`);
}
console.log(`  掉档（档数变少）      ${cnt(r => r.after < r.before)}   其中塌到 1 档：${cnt(r => r.after < r.before && r.after <= 1)}`);
console.log(`  涨档（档数变多）      ${cnt(r => r.after > r.before)}`);
console.log(`  档数不变、只动浓度    ${cnt(r => r.after === r.before)}   其中最大差 ≥20 的：${cnt(r => r.after === r.before && r.maxd >= 20)}`);
console.log(`  顺带换了支的          ${cnt(r => r.branch)}`);
console.log('\n  掉档的名单（屏幕上就是"少一层"）：');
for (const r of rows.filter(r => r.after < r.before).sort((a, b) => (b.before - b.after) - (a.before - a.after)))
  console.log(`    ${String(r.via)}  ${r.before}→${r.after} 档（差 ${r.before - r.after}）  ` +
    `浓度最大动 ${r.maxd === null ? '—（档数变了，两组对不齐）' : r.maxd}  ${r.name}`);
