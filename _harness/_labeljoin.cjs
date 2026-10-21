// **不装机、不新钩子，能不能把"包名/资源名"标签配到桌面库的 componentName 上？**
//
// 这是余账第 5 条欠的那半根轴：`MonoCache` 的归属索引给的是**标签**（`com.openai.chatgpt/images` 这种
//   `包名/资源种类/资源名`，或动态快捷方式的 `pm:包名/id`），而桌面 `app_icons.db` 存的是 **componentName**。
//   以前我一句话把它推给"要做新钩子"，**没量过** —— 本仓的规矩是：用"量级"拒绝一个改动之前，先把那个量级测出来。
//
// 跑法：node _labeljoin.cjs <index.tsv 或 一堆标签文件> <app_icons.db>
// 三种配法都试，报**每一种命中几张**（宁可用最笨的配法，也不许把"配不上"当成"要新钩子"）：
//   A 整串相等；B 去掉资源种类段（`pkg/mipmap/x` → `pkg/x`）；C 大小写不敏感 + 只比斜杠后那一段。
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const IDX = process.argv[2], DB = process.argv[3];
if (!IDX || !DB) { console.error('用法: node _labeljoin.cjs <index.tsv> <app_icons.db>'); process.exit(2); }
const labels = new Map();
for (const l of fs.readFileSync(IDX, 'utf8').replace(/\r/g, '').split('\n')) {
  const c = l.split('\t');
  if (c.length < 3) continue;
  const name = c[2].trim().split(' ')[0];
  if (name) labels.set(name, (labels.get(name) || 0) + 1);
}
const db = new DatabaseSync(DB, { readOnly: true });
const comps = db.prepare('select componentName as c, label as l from Icons').all().map(r => String(r.c));
db.close();
const set = new Set(comps);
const low = new Map(comps.map(c => [c.toLowerCase(), c]));
const tail = new Map();
for (const c of comps) {
  const k = c.split('/').pop().toLowerCase();
  if (!tail.has(k)) tail.set(k, []);
  tail.get(k).push(c);
}
let A = 0, B = 0, C = 0, none = [];
for (const name of labels.keys()) {
  if (name.startsWith('pm:')) continue;                       // 那一支早就配得上（第三十七段：一致 9/9）
  const p = name.split('/');
  const strip = p.length === 3 ? `${p[0]}/${p[2]}` : null;    // pkg/mipmap/x → pkg/x
  if (set.has(name)) A++;
  else if (strip && set.has(strip)) B++;
  else if (p.length >= 2 && (tail.get(p[p.length - 1].toLowerCase()) || []).length === 1) C++;
  else none.push(name);
}
const pm = [...labels.keys()].filter(n => n.startsWith('pm:')).length;
const want = labels.size - pm;
console.log('标签共 ' + labels.size + ' 个（pm: 那一支 ' + pm + ' 个已可配）⇒ 要配的资源名式 ' + want + ' 个');
console.log('A 整串相等 ' + A + ' / B 去掉资源种类段 ' + B + ' / C 只比末段且唯一 ' + C +
  ' ⇒ 配上 ' + (A + B + C) + ' / ' + want);
console.log('配不上 ' + none.length + ' 个' + (none.length ? '，前 12 个：' : ''));
none.slice(0, 12).forEach(n => console.log('   ' + n));
