const fs = require("fs");
const lines = fs.readFileSync("cand_bm.txt", "utf8").split(/\r?\n/);
// BLK 行出现在 `--- 名字` **之前**（MaskBench 先 build 再打名字），所以按"下一张"归属。
let pending = [];
const rows = [];
for (const L of lines) {
  const m = L.match(/^\s*BLK maj=(\d+) n=(\d+) n0=(\d+) nf=(\d+) ns=(\d+) bandMean=(-?\d+) inkMean=(-?\d+) target=(-?\d+)/);
  if (m) {
    pending.push({ maj: +m[1], n: +m[2], n0: +m[3], nf: +m[4], ns: +m[5], bm: +m[6], im: +m[7], t: +m[8] });
    continue;
  }
  const h = L.match(/^--- (\S+)/);
  if (h) {
    const vote = lines.slice(lines.indexOf(L), lines.indexOf(L) + 3).join(" ").match(/票=(\d+)抹\/(\d+)抬\/(\d+)块/);
    rows.push({ name: h[1], blocks: pending, erase: vote ? +vote[1] : 0, raise: vote ? +vote[2] : 0 });
    pending = [];
  }
}
const m1 = [];
for (const r of rows) for (const b of r.blocks) if (b.maj === 1) m1.push({ ...b, name: r.name });
console.log(`扫了 ${rows.length} 张，开火块 ${rows.reduce((s, r) => s + r.blocks.length, 0)} 个`);
console.log(`其中「多数侧=地板带」(maj=1) 的块：${m1.length} 个，牵动 ${new Set(m1.map((x) => x.name)).size} 张图`);
const hist = {};
for (const b of m1) { const k = Math.floor(b.bm / 5) * 5; hist[k] = (hist[k] || 0) + 1; }
console.log("\n地板带均值(α) 直方，每格 5：");
for (const k of Object.keys(hist).sort((a, b) => a - b))
  console.log(`  ${String(k).padStart(3)}-${String(+k + 4).padStart(3)}  ${"#".repeat(Math.min(hist[k], 60))} ${hist[k]}`);
const byBand = m1.sort((a, b) => a.bm - b.bm);
console.log("\n最淡的 12 块（把干净地板抬成一层膜的那些）：");
for (const b of byBand.slice(0, 12))
  console.log(`  bandMean=${String(b.bm).padStart(2)}  n=${String(b.n).padStart(5)}  n0=${String(b.n0).padStart(5)} nf=${String(b.nf).padStart(5)}  target=${String(b.t).padStart(2)}  ${b.name}`);
console.log("\n最浓的 8 块（这些是地板带里真有东西，动它们要谨慎）：");
for (const b of byBand.slice(-8))
  console.log(`  bandMean=${String(b.bm).padStart(2)}  n=${String(b.n).padStart(5)} n0=${String(b.n0).padStart(5)} nf=${String(b.nf).padStart(5)}  target=${String(b.t).padStart(2)}  ${b.name}`);
// 阈值扫描：bandMean < T 时改判为"抹到底(0)"，各档会救回多少格抬、动多少块
console.log("\n候选阈值 T：bandMean<T 的块不再抬、统一抹到 0");
for (const T of [12, 16, 20, 24, 32, 40]) {
  const hit = m1.filter((b) => b.bm < T);
  const cells = hit.reduce((s, b) => s + b.n0, 0);
  console.log(`  T=${String(T).padStart(2)}  改判 ${String(hit.length).padStart(3)} 块 / ${new Set(hit.map((x) => x.name)).size} 张  少抬约 ${cells} 格`);
}
