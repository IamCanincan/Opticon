// 满幅支「参照说是底」那批格子（plate ∪ dep≤零）的浓度尾巴，到底能不能把
// "这是一层灰膜" 和 "底下真的压着东西" 分开？
// 直方图 8 档、每档 32（底史=a0/a1/.../a7，a7=α≥224）。尾巴率 = α≥edge 的格数 / 这一区总格数。
// 看点不是某个 cut 的绝对数，而是**两组之间那条沟有多宽、线该画在沟里的哪个位置**。
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/);
const rows = [];
let name = null;
for (const L of txt) {
  const m = L.match(/^--- (\S+)/);
  if (m) { name = m[1].replace(/^icon\d+_/, "").replace(/_.*$/, ""); continue; }
  if (!name || !L.includes("底史=")) continue;
  const h = (L.match(/底史=(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)/) || [])
    .slice(1).map(Number);
  rows.push({ name, n: +(L.match(/主色=(\d+)/) || [0, -1])[1], h });
  name = null;
}
const T = (r) => r.h.reduce((a, b) => a + b);
const tail = (r, b) => r.h.slice(b).reduce((a, c) => a + c);
const P = (r, b) => (T(r) === 0 ? -1 : (tail(r, b) * 100) / T(r));
// 金标准 = 主人点名"要这种风格"的四张 + 已经修好、不许回退的三张。
const GOLD = ["com.levelinfinite.sgameGlobal", "com.feimo.astralparty", "com.tencent.tmgp.sgame",
  "com.zyddz.wisdomgame", "com.cainiao.wireless", "com.baidu.tieba", "com.wuba.zhuanzhuan"];
for (const b of [2, 3, 4, 5, 6]) {
  const sel = rows.filter((r) => T(r) >= 500).sort((x, y) => P(x, b) - P(y, b));
  console.log("\n=== 尾巴线 α≥" + b * 32 + "  （有底的 " + sel.length + " 张，按尾巴率升序）===");
  for (const r of sel) console.log((GOLD.includes(r.name) ? "金 " : "  ") + r.name.padEnd(38) +
    " 底格" + String(T(r)).padStart(7) + "  尾巴率 " + P(r, b).toFixed(2).padStart(6) + "%");
  const g = sel.filter((r) => GOLD.includes(r.name)).map((r) => P(r, b));
  const f = sel.filter((r) => !GOLD.includes(r.name)).map((r) => P(r, b));
  if (g.length && f.length) {
    const lo = Math.max(...f), hi = Math.min(...g);
    console.log("  → 非金最深 " + lo.toFixed(2) + "%  vs  金标准最浅 " + hi.toFixed(2) + "%  沟=" +
      (hi - lo).toFixed(2) + (hi > lo ? " 点（线可画在中间）" : " 点（**分不开**）"));
  }
}
