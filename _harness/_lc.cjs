// 临时（2026-10-16）：从真机 logcat 的 `Mono: diag#` 行里抽「哪个图标 / 淡盘= / 淡= / 走哪支」
// 用法：node _lc.cjs <logcat.txt> [--nonzero]  （--nonzero 只印这一刀真开火的行）
const fs = require("fs");
const a = process.argv.slice(2);
const f = a.find((x) => !x.startsWith("--")) || "_lc4.txt";
const nz = a.includes("--nonzero");
const L = fs.readFileSync(f, "utf8").split(/\r?\n/);
let all = 0, fired = 0;
for (const l of L) {
  if (!l.includes("Mono: diag#") || !l.includes("淡盘=")) continue;
  all++;
  const dp = +(/淡盘=([^ ]+)/.exec(l) || [0, 0])[1];
  if (dp > 0) fired++;
  if (nz && !(dp > 0)) continue;
  // label 里带空格，所以按"句柄"切：`… 键=xxxx <label> 票=…`
  const m = /(\S+ \d+ \S+ \S+ Opticon : )Mono: diag#(\d+) [^ ]+ [^ ]+ 键=\S+ (.+?) 票=/.exec(l);
  const lab = m ? m[3] : l.slice(0, 160);
  const dn = (/ 淡=([^ ]+)/.exec(l) || ["", "?"])[1];
  const md = (/ 膜刀=([^ ]+)/.exec(l) || ["", "-"])[1];
  const tail = (l.slice(l.indexOf("→")) || "").split(" ").slice(0, 2).join(" ");
  console.log(`淡盘=${dp}\t淡=${dn}\t膜刀=${md}\t${tail}\t${lab}`);
}
console.log(`—— 有判据行的 $all 张，其中**这一刀开火** $fired 张`);
