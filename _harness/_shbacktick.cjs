// **挡一类 shell 错：双引号里的反引号 = 命令替换，不是排版。**
// 起因（2026-10-21 现量）：我给 `regress.sh` 第 3 段补"缺语料就说句话"的门，门里那句
//   `echo "… sh audit.sh …"` 用了反引号 ⇒ **那句话把 `sh audit.sh` 真的执行了**，
//    regression 没停住，反而去 `adb pull` 了主人的手机（退码 124 是被我外面的 timeout 掐的，不是它自己停的）。
// 同批还揪出两处**潜伏**的（都在 `exit 1` 分支里，所以从没印过）：`echo "（\`GeoKt\` …）"`、`echo "…\`~\`…"`。
// 跑法：`node _shbacktick.cjs [目录…]`（默认 `_harness`）；退码 0 = 干净，1 = 有雷。
const fs = require('fs');
const path = require('path');
const dirs = process.argv.slice(2).length ? process.argv.slice(2) : ['.'];
const files = [];
for (const d of dirs) {
  if (!fs.existsSync(d)) { console.error(`!! 目录不存在：${d}`); process.exit(9); }
  for (const f of fs.readdirSync(d)) if (f.endsWith('.sh')) files.push(path.join(d, f));
}
const bad = [];
for (const f of files) {
  const lines = fs.readFileSync(f, 'utf8').replace(/\r/g, '').split('\n');
  lines.forEach((l, i) => {
    if (/^\s*#/.test(l)) return;                       // 整行注释里反引号是排版，不动 shell
    const noSQuote = l.replace(/'[^']*'/g, "''");       // 单引号段里什么也不会执行
    if (/"[^"]*`[^"]*"/.test(noSQuote)) bad.push(`${f}:${i + 1}: ${l.trim().slice(0, 110)}`);
  });
}
console.log(`扫了 ${files.length} 个 .sh（${dirs.join(', ')}）：**双引号里带反引号的行 ${bad.length} 处**`);
for (const b of bad) console.log('  🔴 ' + b);
if (bad.length) console.log('  ⇒ 改成单引号，或把反引号换成别的引号；双引号里每一对 ` 都会被 shell 执行一次。');
// ⚠ **扫到 0 个文件不是"干净"，是仪器没电**（本仓那条：0 命中常常是读不到，不是没有）。
//   2026-11-05 现量：从仓库根跑 `node _harness/_shbacktick.cjs .` 就是这个 0 —— 它以前退 0，
//   于是"路径写错"会被 `regress.sh` 读成"这一闸过了"。
if (!files.length) { console.log('  !! 一个 .sh 都没扫到 —— 这不是通过，是这台子没电（退码 2）'); process.exit(2); }
process.exit(bad.length ? 1 : 0);
