// 一次性：把两趟 `_pairshot.cjs` 的输出按组件逐行对上，报"变了哪几行"。
// 用法：node _harness/_psdiff.cjs <前.txt> <后.txt>
const fs = require('fs');
const parse = (f) => {
  const m = new Map();
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    let t = l.trim();
    if (!t || t.startsWith('组件') || t.startsWith('共') || t.startsWith('缩进')) continue;
    t = t.replace(/\[.*$/, '').trim();          // 旁列/判定那截（`--zoom` 才有）先剥掉
    const p = t.split(/\s+/);
    if (p.length < 6) continue;
    const nums = p.slice(-5).map(Number);       // 彩幅 单幅 比 彩墨 单墨
    if (nums.some(Number.isNaN)) continue;      // 形状不对的行**不配**，不硬塞进表
    m.set(p.slice(0, p.length - 5).join(' '), nums);
  }
  return m;
};
const [fa, fb] = process.argv.slice(2);
const a = parse(fa), b = parse(fb);
// 键会折叠（同一组件按 profile 重复多行，最后一行胜出），所以再拿**整行文本的集合差**兜一层：
// 折叠漏掉的改动在这里一定露出来（两边各自去重，数量对得上就说明没被吃）。
const rawSet = (f) => new Set(fs.readFileSync(f, 'utf8').split(/\r?\n/).map((l) => l.trim())
  .filter((t) => t && !t.startsWith('组件') && !t.startsWith('共') && !t.startsWith('缩进')));
const ra = rawSet(fa), rb = rawSet(fb);
const uniqLine = (s, other) => [...s].filter((l) => !other.has(l));
const onlyA = uniqLine(ra, rb), onlyB = uniqLine(rb, ra);
console.log(`整行（去重）：前 ${ra.size} 条 / 后 ${rb.size} 条；只在前一份 ${onlyA.length} 条、只在后一份 ${onlyB.length} 条`);
for (const l of onlyB.slice(0, 8)) console.log('   新 ' + l.slice(0, 96));
for (const l of onlyA.slice(0, 8)) console.log('   旧 ' + l.slice(0, 96));
let ch = 0, same = 0, only = 0;
const rows = [];
for (const [k, v] of b) {
  const o = a.get(k);
  if (!o) { only++; continue; }
  if (Math.abs(o[1] - v[1]) > 0.005 || Math.abs(o[2] - v[2]) > 0.02) {
    ch++;
    rows.push(k.slice(0, 46).padEnd(47) + '单色幅 ' + o[1].toFixed(3) + '→' + v[1].toFixed(3) +
      '   幅面比 ' + o[2].toFixed(3) + '→' + v[2].toFixed(3));
  } else same++;
}
console.log('比了 ' + b.size + ' 个键（前一份 ' + a.size + ' 个键、这一份里配不上的 ' + only + '）：' +
  '变 ' + ch + ' 行、相同 ' + same + ' 行');
// 键数对不上行数 = 有行被折叠或丢弃，这时"变 0 行"不能当"没动"（本仓反复的死法）。
const uniq = (mm) => mm.size;
if (only > 0) console.log('!! 有 ' + only + ' 个键只在一份里 —— 键的形状变了，先别信"变/相同"那两个数');
console.log('唯一键：前 ' + uniq(a) + ' / 后 ' + uniq(b));
rows.sort();
for (const r of rows) console.log('  ' + r);
