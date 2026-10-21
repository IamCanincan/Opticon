// 把一份 logcat 落成**归属清单**：每个组件的单色遮罩是"我们合成过的"还是"透传的"。
// 为什么能分：这三类行在设备侧天然互斥，且都带组件可解析的名字 ——
//   `Mono: diag#N edge=E 键=K <label> … 底占=…`        → 我们**当场合成**（有完整判据行）
//   `Mono: diag#N … 键=K <label> … 缓存=磁盘|内存 → 直接交缓存里那张` → 我们**交过货**，只是没重算
//   `Mono: 自带单色层，透传 #N 是谁=<label>`            → **不是我们的**（应用自带单色层，按硬规则一个像素没碰）
// ⚠ 这份清单只在**日志窗口还活着**的时候有效：logcat 一滚就没 ⇒ 必须在清图标缓存那一次当场跑它。
// 用法：node _harness/_delivery.cjs <logcat文件> [--dump=目录]   （--dump 会把清单写出去）
const fs = require('fs');
const [, , file, ...rest] = process.argv;
if (!file) { console.error('用法: node _harness/_delivery.cjs <logcat> [--dump=目录]'); process.exit(2); }
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => l.includes('Mono:'));

/** 我们的标签 → 桌面库 componentName 的形状：`pm:包名/id` 能直接配；资源名形式配不上（两根轴）。 */
function toComponent(label) {
  if (!label || label === '?') return null;
  if (label.startsWith('pm:')) return label.slice(3);           // 包名/id  ← 与库同形
  return null;                                                   // 资源名形式：诚实承认配不上
}
const ours = new Map(), pass = new Map(), unknown = new Map();
for (const l of lines) {
  let m = l.match(/自带单色层，透传[^]*?是谁=(\S+)/);
  if (m) { const c = toComponent(m[1]); if (c) pass.set(c, m[1]); else unknown.set(m[1] || '?', (unknown.get(m[1] || '?') || 0) + 1); continue; }
  m = l.match(/diag#\d+ edge=(\d+)\s+(?:sig=\S+\s+)?(?:键=([0-9a-f]+)\s+)?(\S+)\s.*?(缓存=(磁盘|内存)|底占=)/);
  if (!m) continue;
  const [, edge, key, label, , cacheHit, judged] = m;
  const c = toComponent(label);
  if (!c) { unknown.set(label, (unknown.get(label) || 0) + 1); continue; }
  const kind = cacheHit ? `缓存命中(${cacheHit})` : judged ? '当场合成' : '其他';
  const prev = ours.get(c);
  if (!prev || prev.kind === '缓存命中(磁盘)') ours.set(c, { comp: c, label, key: key || '-', edge, kind });
}
console.log(`日志 ${lines.length} 行 Mono: ⇒ 组件级归属`);
console.log(`  我们交过货（动态快捷方式那批，名字可配）：${ours.size}`);
console.log(`  明确透传 = 不是我们的：${pass.size}`);
console.log(`  ⚠ 名字配不上组件的（资源名形式 / 问号）：${[...unknown.values()].reduce((a, b) => a + b, 0)} 行、${unknown.size} 个标签`);
console.log(`    ⇒ 这份清单**现在只覆盖 pm: 那一支**；应用图标那批要配组件得另找一根轴（库里没有资源名列）。`);
if (ours.size) { console.log('\n我们交过货的:'); [...ours.values()].forEach((r) => console.log(`  ${r.comp.padEnd(52)} ${r.kind.padEnd(14)} 键=${r.key} edge=${r.edge}`)); }
if (pass.size) { console.log('\n明确不是我们的:'); [...pass.keys()].forEach((c) => console.log(`  ${c}`)); }
const out = rest.find((a) => a.startsWith('--dump='));
if (out) {
  const dir = out.slice(7); fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(dir + '/delivery.tsv', 'component\tkind\tkey\tedge\n' +
    [...ours.values()].map((r) => [r.comp, r.kind, r.key, r.edge].join('\t'))
      .concat([...pass.keys()].map((c) => [c, '透传(应用自带单色层)', '-', '-'].join('\t'))).join('\n'));
  console.log(`\n清单已落盘 ${dir}/delivery.tsv（下一次 disease.cjs 的分母就读它）`);
}
