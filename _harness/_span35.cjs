// 一次性：跨度 40 vs 35，逐张看点名的那几张。
const fs = require("fs");
const g = (p, pat) => {
  const t = fs.readFileSync(p, "utf8").replace(/\r/g, "").split("\n");
  for (let i = 0; i < t.length; i++) {
    if (!new RegExp(pat).test(t[i])) continue;
    for (let j = i; j < Math.min(i + 4, t.length); j++) {
      const m = (t[j].match(/字均净=(\d+)/) || [])[1];
      if (m) return { z: +m, box: (t[j].match(/框内=([0-9.]+)/) || [])[1] };
    }
  }
  return null;
};
const A = "bench/ab_blank/spanPolar40_corpus_runtime.txt";
const B = "bench/ab_blank/span35_corpus_runtime.txt";
const rows = [["azure「没盾」", "azure"], ["驾考「线条不清晰」", "jiakao"],
  ["滴滴", "didi"], ["抖音", "ugc.aweme"], ["小黑盒", "xiaoheihe"], ["12306", "MobileTicket"]];
for (const [name, pat] of rows) {
  const a = g(A, pat), b = g(B, pat);
  const f = (x) => (x ? "净" + String(x.z).padStart(3) + " 框内" + x.box : "—");
  console.log(name.padEnd(18) + " 跨度40: " + f(a) + "   |  跨度35: " + f(b));
}
