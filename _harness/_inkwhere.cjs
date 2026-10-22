// **主体上墨账**：主人 2026-10-22 验收时给的判据 —— 快捷方式「都是主体上墨」
// （屏上那块底是框架/我们另画的，**不许把盘铺进掩码**）。
//
// 这条判据原来没有任何一把尺在看：极性那两道闸（16/19）管的是"哪本账说了算"，不看你把墨铺在哪；
//   而"把整块盘也上墨"恰恰是 2026-10-08 那次事故的形状（屏幕上多一块实心饼）。
// 这里用的读数 `框内` 是 MaskBench 早就在印的一列：**墨的内容框面积 / 整幅**。
//   · 只给主体 ⇒ 小（这批中位 0.09）；
//   · 连盘一起上墨 ⇒ 逼近 1.0。
// 所以本台钉三件：`框内` 中位、`框内 ≥ 0.9` 的张数（**必须 0**）、`框内 ≤ 0.3` 的张数（"光一个符号"那档）。
//
// 用法：node _inkwhere.cjs --sh=<MaskBench 输出> --sm=<MaskBench 输出> [--pin=bench/reg/inkwhere.pin] [--update]
// 退码：0 与 pin 逐字相同（或刚 --update 钉上）；1 读数动了（点名变的是哪一位）；2 输入读不出来/分母空
const fs = require('fs');
const arg = (k) => { const a = process.argv.find((s) => s.startsWith('--' + k + '=')); return a ? a.substring(k.length + 3) : null; };
const SH = arg('sh'), SM = arg('sm');
const PIN = arg('pin') || 'bench/reg/inkwhere.pin';
const UPDATE = process.argv.includes('--update');
if (!SH || !SM) { console.log('用法: node _inkwhere.cjs --sh=<文件> --sm=<文件> [--pin=…] [--update]'); process.exit(2); }

function stats(file, tag) {
  if (!fs.existsSync(file)) { console.log(`!! 没有 ${file} —— ${tag} 这一档一张都没量，不能当"0 张"`); process.exit(2); }
  const rows = [];
  let cur = null;
  for (const l of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const h = /^--- (\S+)/.exec(l);
    if (h) { cur = { name: h[1] }; rows.push(cur); continue; }
    if (!cur) continue;
    const m = /框内=(-?[\d.]+)/.exec(l);
    if (m) cur.box = +m[1];
  }
  const a = rows.filter((r) => r.box !== undefined).map((r) => r.box).sort((x, y) => x - y);
  if (a.length < 20) { console.log(`!! ${tag} 只解析出 ${a.length} 张（<20）—— 分母是空的，这张表不能读`); process.exit(2); }
  const q = (p) => a[Math.round(p * (a.length - 1))];
  // **逐张摘要**：只钉中位/计数的话，一张图从 0.09 飘到 0.08 这类事永远看不见
  //   （构成本身没变、中位也没动）。摘要盖的是「每张的名字 + 框内」，任何一张变了它都变。
  const sig = require('crypto').createHash('md5')
    .update(rows.filter((r) => r.box !== undefined).map((r) => r.name + '=' + r.box).sort().join('\n'))
    .digest('hex').slice(0, 8);
  return { n: a.length, med: q(0.5), full: a.filter((x) => x >= 0.9).length, sym: a.filter((x) => x <= 0.3).length, sig };
}
const s = stats(SH, 'shortcut_bit');
const m = stats(SM, 'shortcut_model');
const line = `INKWHERE sh n=${s.n} 中位=${s.med.toFixed(2)} 满幅=${s.full} 符号=${s.sym} sig=${s.sig}` +
  ` ｜ sm n=${m.n} 中位=${m.med.toFixed(2)} 满幅=${m.full} 符号=${m.sym} sig=${m.sig}`;
console.log(line);
// 🔴 **结构性不变量，只对 `shortcut_bit` 那 45 张成立**（不靠基线、不靠常数）：
//   那批源位图的 `框内` 最大只有 **0.69**（见 `_shotbit.cjs` 造语料时量的分布）——
//   源里根本没有满幅的图，所以交付里出现任何一张 `框内 ≥ 0.9`，只可能是**把盘铺进了掩码**。
//   `shortcut_model` **不**受这条管：它里面现量有 3 张满幅，而三张的源**本身就是满幅图**
//   （Settings 的电池磁贴 / Aperture 的视频缩略图 / 一个 FCM 图）—— 那是"主体占满整幅"，正是主人那句
//   「都是主体上墨」要的样子。所以那三张钉在 pin 里，变了要人看一眼，而不是一有就红。
if (s.full > 0) {
  console.log(`!! shortcut_bit 里有 ${s.full} 张的墨铺到了整幅（框内 ≥0.9）——` +
    ` 而那 45 张的源最大只有 0.69 ⇒ 这就是"盘被铺进掩码"的形状`);
  process.exit(1);
}
if (UPDATE) {
  fs.writeFileSync(PIN, line + '\n');
  console.log(`   --update：已把这一轮钉成新基线`);
  process.exit(0);
}
const old = fs.existsSync(PIN) ? fs.readFileSync(PIN, 'utf8').trim() : '';
if (!old) { console.log(`!! 没有 ${PIN} —— 没基线不算过（先 --update 钉一次）`); process.exit(1); }
if (old !== line) {
  console.log('!! 主体上墨的读数动了：');
  console.log('   基线 ' + old);
  console.log('   这轮 ' + line);
  process.exit(1);
}
console.log('   —— 与基线逐字相同');
