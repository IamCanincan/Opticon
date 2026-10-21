// 第七闸的合计器：两份口径（runtime 与装机那一张）的肉眼账输出，报「取高」与「并集」。
// 为什么要单独一个文件：这段逻辑以前写成 `node -e '…'` 塞在 regress.sh 里，
// 里面一个单引号就把整条 shell 字符串截断了（闸当场 exit 1，报的是 node 的语法错，
// 看着像"这一闸测出问题了"）。闸自己的语法不该藏在引号里。
// 用法：node _named_sum.cjs <runtime输出> <装机口径输出>     → "11 12"（并集算不出就是 "11 -"）
const fs = require("fs");
const pair = (f) => { const m = /合计\s+(\d+)\/(\d+)/.exec(fs.readFileSync(f, "utf8")); return m ? [+m[1], +m[2]] : [0, 0] };
const unc = (f) => { const m = /^UNCOVERED (.*)$/m.exec(fs.readFileSync(f, "utf8")); return m ? m[1].split(",").filter(Boolean) : null };
const [a, b] = process.argv.slice(2);
const [ta, na] = pair(a), [tb, nb] = pair(b);
const ua = unc(a), ub = unc(b);
// 「任一口径点到」= 两份都报没点到的那一交（交集）才算没点到 —— 少跑一份就不给这个数，
// 因为一份的 UNCOVERED 里没有，可能只是那份语料压根没这一张。
const both = ua && ub ? ua.filter((p) => ub.includes(p)) : null;
console.log(Math.max(ta, tb) + " " + (both ? Math.max(na, nb) - both.length : "-"));
