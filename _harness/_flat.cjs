// 第 18 闸「平块坡数账」：**源是一块平色、我们却交了一道坡上去** —— 装机侧的掉色，不需要对岸也能问。
//
// 来源：2026-10-20 把第十四闸的 `greyBadPct` 拆成 掉色 / 铺雾 两个方向之后，发现"掉色"在
//   **已适配那 62 对**上有 140.4pp，可那些图根本不进模型；真上屏的 64 张里到底有多少，
//   当时是现写一台临时台子量的。这一闸就是把它变成**常设**的：以后任何一刀动到浓度，
//   不必重新手写台子，跑一次就知道坡涨没涨。
//
// 口径（与 `RegionKt.flatInkLine` 一字不差）：面积 ≥0.5% 画布、源同一颜色 bin、
//   我们平均上了墨(α≥128)、而我们交的 α 的 **p90−p10 > 40**。
// 红绿灯用**两个数**：`blocks`（坡块数）与 `cells`（坡格数）—— 只守块数的话，
//   一块 8,000 格的坡变成 8 块 1,000 格会看着像在变好；只守格数则相反。
// ⚠ 这台子量的是**射程不是收益**：第二十二段已经量过，把这 21 块抬平在 112px 上看不出差别，
//   所以"坡涨了"要查的是**哪一刀带来的**，不是"赶紧去抬平"。
//
// 跑法：node _flat.cjs [--pin] [--floor="blocks=21 cells=31001 pics=5"] [--in=…]
const fs = require('fs');
const arg = (k, d) => { const a = process.argv.find(x => x.startsWith(`--${k}=`)); return a === undefined ? d : a.slice(a.indexOf('=') + 1); };
const IN = arg('in', 'bench/reg/flatin.txt');
const PIN = 'bench/reg/flatin.pin';
if (!fs.existsSync(IN)) {
  console.error(`!! 没有 ${IN} —— 第 18 闸没跑。缺文件不等于"一张都没有"：regress.sh 必须先跑 ` +
    `MaskBench --flatin 并落到这里。`);
  process.exit(2);
}
const L = fs.readFileSync(IN, 'utf8').split(/\r?\n/);
const R = [];
let cur = null;
for (const l of L) {
  if (l.startsWith('--- ')) { if (cur && cur.done) R.push(cur); cur = { n: l.slice(4).split(/\s/)[0], done: 0 }; continue; }
  const m = l.match(/〔平块〕平块=(\d+)\/(\d+)格 坡=(\d+)块\/(\d+)格 抬到255多=(\d+)/);
  if (m && cur) { cur.b = +m[1]; cur.bc = +m[2]; cur.s = +m[3]; cur.sc = +m[4]; cur.raise = +m[5]; cur.done = 1; }
}
if (cur && cur.done) R.push(cur);
// **零行 = 装置坏了**，不是"这一族干净"。（本仓这条踩过：0 命中常被读成 0 问题。）
if (R.length === 0) {
  console.error(`!! ${IN} 里一行 〔平块〕 都没有 —— 台子没跑起来或旗没带，**不算绿**`);
  process.exit(2);
}
const blocks = R.reduce((a, r) => a + r.s, 0);
const cells = R.reduce((a, r) => a + r.sc, 0);
const pics = R.filter(r => r.s > 0).length;
const now = `blocks=${blocks} cells=${cells} pics=${pics}`;
console.log(`平块坡数账：${R.length} 张　坡块 ${blocks} 个 / ${cells.toLocaleString()} 格　有坡的图 ${pics} 张`);
for (const r of R.filter(x => x.s > 0).sort((a, b) => b.sc - a.sc))
  console.log(`   ${String(r.s).padStart(2)} 块 / ${String(r.sc).padStart(6)} 格  坡块平均每格差 ${((r.raise / r.sc)).toFixed(1)} α  ${r.n.slice(0, 46)}`);

if (process.argv.includes('--pin')) { fs.writeFileSync(PIN, now + '\n'); console.log(`   已认账：${PIN} = ${now}`); process.exit(0); }
const pin = arg('floor', fs.existsSync(PIN) ? fs.readFileSync(PIN, 'utf8').trim() : null);
if (pin === null) { console.error(`!! 没有 ${PIN} —— 没地板不算过（先 node _flat.cjs --pin 认一次账）`); process.exit(2); }
const base = {};
for (const kv of pin.split(/\s+/).filter(Boolean)) {
  const m = /^([a-z0-9]+)=(-?\d+)$/.exec(kv);
  if (!m) { console.error(`!! 地板格式不认识：「${pin}」（要的是 "blocks=N cells=N pics=N"）—— 坏地板不当约束`); process.exit(2); }
  base[m[1]] = +m[2];
}
const got = { blocks, cells, pics };
for (const k of Object.keys(got))
  if (base[k] === undefined) { console.error(`!! 地板「${pin}」里没有 ${k}= —— 这一半没人在守，不算过`); process.exit(2); }
const worse = Object.keys(got).filter(k => got[k] > base[k]);
console.log(`   这轮 ${now} / 地板 ${pin}`);
if (worse.length) {
  console.error(`!! 坡涨了（${worse.map(k => `${k} ${base[k]}→${got[k]}`).join(', ')}）—— 名单在上面，逐张查是哪一刀带来的`);
  console.error(`   ⚠ 提醒：抬平本身已量过是**看不见的收益**（第二十二段），所以这里红了是去查**新刀**，不是去抬平。`);
  process.exit(1);
}
console.log(`PRESS-FLAT ${now} ≤ 基线 ${pin} —— 持平或更好`);
