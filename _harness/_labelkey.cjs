// **同一张图标（按 `describe` 的第一段标签认），在两扇日志窗口里我们交的是不是同一份内容。**
//
// 为什么另起一台而不是用 `_delivery.cjs` 的清单：那份清单**只覆盖 `pm:` 那一支**
//   （资源名形式的标签配不上组件 —— 就是余账第 5 条欠的那半根轴）。而"内容有没有变"这件事
//   **根本不需要组件**：`Mono: diag#N edge=E 键=K <标签> …` 里 `标签@edge` 自己就是可比的一行，
//   它在任何一扇窗口里都存在。用标签当键，是把"配不上组件"这个缺陷绕开而不是假装它没有。
// ⚠ 边界：标签为 `?` 的那批（程序化 drawable 没有资源名，约占 3.4%）**在这台子里合并成一类**，
//   所以它的"同键率"不代表那批的归属 —— 那批要靠 `MonoCache` 的 `index.tsv`（已上线）。
// 跑法：node _labelkey.cjs <旧 logcat> <新 logcat> [--show=N]
const fs = require('fs');
const LINE = /edge=(\d+) (?:sig=\S+ )?键=(\S+) (\S+)/;
const load = f => {
  const m = new Map();
  let diag = 0;
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (!l.includes('Mono: diag')) continue;
    const g = LINE.exec(l); if (!g) continue;
    diag++;
    const k = g[3] + '@' + g[1];
    const s = m.get(k) || new Set(); s.add(g[2]); m.set(k, s);
  }
  return { m, diag };
};
const [A, B] = process.argv.slice(2).filter(a => !a.startsWith('--'));
if (!A || !B) { console.error('用法: node _labelkey.cjs <旧 logcat> <新 logcat>'); process.exit(2); }
const a = load(A), b = load(B);
console.log(`旧 ${A}：${a.diag} 行 diag / ${a.m.size} 个「标签@边长」；新 ${B}：${b.diag} 行 / ${b.m.size} 个`);
if (a.m.size === 0 || b.m.size === 0) {
  console.error('!! 有一边一个标签都没解出来 —— 这不是"没有交集"，是**这台子或那份日志坏了**（新旗第一次跑必须证明它印了东西）');
  process.exit(2);
}
let both = 0, same = 0; const shift = [];
for (const [k, ka] of a.m) {
  const kb = b.m.get(k); if (!kb) continue;
  both++;
  if ([...ka].some(x => kb.has(x))) same++;
  else shift.push(`  ${k.slice(0, 62).padEnd(64)} 旧=${[...ka].join(',')} 新=${[...kb].join(',')}`);
}
const q = k => k.includes('?@') ? '?' : (k.startsWith('pm:') || k.includes('shortcut') || /\/(id\d|new_|compose|search|uninstall|view_|camera_|send_|start_|manifest-|download)/.test(k) ? '快捷方式/动态' : '应用图标');
const tally = {}; for (const s of shift) { const k = s.trim().split(' ')[0]; tally[q(k)] = (tally[q(k)] || 0) + 1; }
const tallyAll = {}; for (const k of a.m.keys()) if (b.m.has(k)) tallyAll[q(k)] = (tallyAll[q(k)] || 0) + 1;
console.log(`两边都有读数 ${both} 个：**同一把键 ${same}** / 换了键 ${shift.length}`);
for (const t of Object.keys(tallyAll)) console.log(`   ${t}：可比 ${tallyAll[t]}，其中换键 ${tally[t] || 0}`);
shift.slice(0, +(process.argv.find(x => x.startsWith('--show=')) || '--show=12').substring(7)).forEach(l => console.log(l));
console.log('\n=== 仪器自证：同一份日志跟自己比，必须 100% 同键 ===');
let t = 0, g = 0;
for (const [k, ka] of a.m) { const kb = a.m.get(k); t++; if ([...ka].some(x => kb.has(x))) g++; }
console.log(`  可比 ${t}，同键 ${g} ⇒ ${t && g === t ? '认得出"没变" ✓' : '坏了，上面的数不能用'}`);
