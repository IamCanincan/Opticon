const fs = require("fs");
const lines = fs.readFileSync("cand_bm.txt", "utf8").split(/\r?\n/);
let pending = [];
const rows = [];
for (let i = 0; i < lines.length; i++) {
  const L = lines[i];
  const m = L.match(/BLK leak=(true|false) maj=(\d+) n=(\d+) n0=(\d+) nf=(\d+) ns=(\d+) bandMean=(-?\d+) inkMean=(-?\d+) target=(-?\d+)/);
  if (m) {
    pending.push({ leak: m[1] === "true" ? 1 : 0, maj: +m[2], n: +m[3], n0: +m[4], nf: +m[5], ns: +m[6], bm: +m[7], im: +m[8], t: +m[9] });
    continue;
  }
  const h = L.match(/^--- (\S+)/);
  if (h) {
    const v = (lines.slice(i, i + 3).join(" ").match(/票=(\d+)抹\/(\d+)抬\/(\d+)块/) || [0, 0, 0, 0]);
    rows.push({ name: h[1], blocks: pending, erase: +v[1], raise: +v[2] });
    pending = [];
  }
}
console.log(`开火块 ${rows.reduce((s, r) => s + r.blocks.length, 0)} 个 / ${rows.filter((r) => r.blocks.length).length} 张`);
console.log(`  判成膜(leak=1)：${rows.flatMap((r) => r.blocks).filter((b) => b.leak).length} 块`);
console.log(`  仍按均值统一往上抬的 maj=1：${rows.flatMap((r) => r.blocks).filter((b) => b.maj === 1 && !b.leak).length} 块`);
const up = rows.filter((r) => r.raise > 0).sort((a, b) => b.raise - a.raise);
console.log(`\n还在抬墨的 ${up.length} 张（抬=往上加浓度，这一族的账要逐张说得清）：`);
for (const r of up) {
  const kinds = r.blocks.map((b) => `${b.leak ? "膜→0" : b.maj === 2 ? "多数=墨" : "带均值" + b.bm}`).join(", ");
  console.log(`  抹${String(r.erase).padStart(5)} 抬${String(r.raise).padStart(5)}  ${r.name.slice(0, 46).padEnd(46)}  [${kinds}]`);
}
