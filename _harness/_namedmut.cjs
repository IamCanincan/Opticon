// **第七闸（肉眼账 / 主人点名那 15 张的覆盖率）到底会不会响** —— 改输入，不改生产，所以不占重编。
//
// 为什么今天要做它：`_gateaudit.cjs` 的表头正则修好之后（2026-10-21），第七、第八两道**真闸**才第一次进审计表，
//   而当时两档都是「什么都没有」= 我从没证明过它们能响。第八闸已经用语义变异结掉
//   （`node _semanticmut.cjs --case=8`：负对照 `bad=0` → 变异 `bad=6` 红），这台子补第七闸那一半。
// 测的是**裁判读不读得懂"点名表里少了一张"**：拿第 7 段现成的三份中间产物（**只读，原文件一个字节不动**），
//   把主口径那份 dump **复制一份、删掉某个具名包的整块**（`--- <名字>` 到下一个 `---` 为止），
//   再走同一条 `_named.cjs` 判定 —— 合计要从 15/15 掉到 14/15，底线 15 ⇒ 红。
// ⚠ 复用规矩：本仓清空过别人的活账本（平色饼 124→0 造出一次假退步）⇒ 全程只写 `*_mut` 文件，
//   跑完逐字节自证原文件未变。
const fs = require('fs');
const { spawnSync } = require('child_process');
const D = 'bench/reg';
const SRC = `${D}/named_model.txt`, TSV = `${D}/ledger_model.txt`, BR = `${D}/bridge_model.txt`;
const MUTDUMP = `${D}/named_model_mut.txt`, MUTOUT = `${D}/named_model_full_mut.txt`;
const sh = (c, a) => spawnSync(c, a, { cwd: __dirname, encoding: 'utf8', maxBuffer: 1 << 26 });
const fail = m => { console.error('!! ' + m); process.exit(9); };

for (const f of [SRC, TSV, BR, 'bench/named_floor.txt', 'named.txt'])
  if (!fs.existsSync(f)) fail(`缺 ${f} —— 第 7 段的料不在（先跑一次 sh regress.sh），这一台什么都不测`);

const before = fs.readFileSync(SRC);
console.log(`读的是第 7 段现成产物（${SRC}，${before.length} 字节，mtime ${fs.statSync(SRC).mtime.toISOString()}）`);
console.log('   —— 这台子测的是**裁判灵敏度**，不是语料：拿旧中间文件是设计的一部分，结论里不许引用任何覆盖率绝对值之外的数');

const FLOOR = parseInt(fs.readFileSync('bench/named_floor.txt', 'utf8').trim(), 10);
const cover = f => {
  const m = /合计\s+(\d+)\/(\d+)/.exec(fs.readFileSync(f, 'utf8'));
  if (!m) fail(`${f} 里没有「合计 n/m」那一行 —— 判定读不到分母，这台子什么都证不了`);
  return [parseInt(m[1], 10), parseInt(m[2], 10)];
};
const judge = dump => {
  const r = sh('node', ['_named.cjs', dump, `--tsv=${TSV}`, `--bridge=${BR}`]);
  if (r.status !== 0) fail('_named.cjs 退码非 0 —— 量具坏了，不算证过');
  fs.writeFileSync(MUTOUT, r.stdout);
  return cover(MUTOUT);
};

// 点名表里的包名（`named.txt` 第一列），以及 dump 里的分块（`--- 名字  …` 起，到下一个 `---` 止）。
const pkgs = [...new Set(fs.readFileSync('named.txt', 'utf8').split(/\r?\n/)
  .filter(l => l && !l.startsWith('#')).map(l => l.split('\t')[0]).filter(Boolean))];
const lines = before.toString('utf8').replace(/\r/g, '').split('\n');
const starts = lines.map((l, i) => /^---+\s+(\S+)/.test(l) ? i : -1).filter(i => i >= 0);
const blocks = starts.map((s, k) => {
  const e = k + 1 < starts.length ? starts[k + 1] : lines.length;
  return { name: /^---+\s+(\S+)/.exec(lines[s])[1], s, e };
});
const namedBlocks = blocks.filter(b => pkgs.some(p => b.name.startsWith(p + '_') || b.name === p));
console.log(`dump 里 ${blocks.length} 块，其中对得上点名表的 ${namedBlocks.length} 块；底线 ${FLOOR}`);
if (!namedBlocks.length) fail('一块都对不上点名表 —— 这台子的变异没东西可删（先去查 named.txt 与语料的对应关系）');

// ① 负对照：原样（整份 dump 的副本）走一遍判定，必须**绿**。
fs.writeFileSync(MUTDUMP, lines.join('\n'));
const [c0, d0] = judge(MUTDUMP);
if (c0 < FLOOR) fail(`负对照就没过（覆盖 ${c0}/${d0} < 底线 ${FLOOR}）—— 第 7 段本来就红，先修它再谈变异`);
console.log(`负对照（原样）：覆盖 ${c0}/${d0}，底线 ${FLOOR} ⇒ 绿 ✓`);

// ② 变异：逐块删，直到覆盖掉下来（删一块没反应 = 那张本来也没被点到，**不能算证过**，继续找下一块）。
let hit = null;
for (const b of namedBlocks) {
  const cut = lines.slice(0, b.s).concat(lines.slice(b.e)).join('\n');
  fs.writeFileSync(MUTDUMP, cut);
  const [g, den] = judge(MUTDUMP);
  if (g < c0) { hit = { b, g, den }; break; }
  console.log(`   删掉 ${b.name} 之后覆盖仍是 ${g}/${den} —— 这一张本来就没被名单点到，换下一块`);
}
if (!hit) { console.log('!! 删遍所有具名块，覆盖一格没掉 ⇒ **第七闸对"少了一张点名的图"是瞎的**'); }
else {
  const red = hit.g < FLOOR;
  console.log(`变异（删掉 ${hit.b.name} 整块 ${hit.b.e - hit.b.s} 行）：覆盖 ${hit.g}/${hit.den}` +
    ` ⇒ ${red ? '红 ✓（底线 ' + FLOOR + ' 被踏住）' : `**还是绿的** —— 底线 ${FLOOR} 比实测 ${hit.g} 低，这一闸拦不住`}`);
}

// ③ 收尾自证：原文件逐字节没动（这台子全程只写 `*_mut`）。
if (!fs.readFileSync(SRC).equals(before)) fail('原 dump 被改动了 —— 这台子违规，去查写路径');
fs.rmSync(MUTDUMP, { force: true }); fs.rmSync(MUTOUT, { force: true });
console.log('原文件逐字节未变 ✓；两份 *_mut 已删。');
process.exit(hit && hit.g < FLOOR ? 0 : 1);
