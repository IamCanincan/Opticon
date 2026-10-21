// 语料里的重复行有没有扭曲读数：同一份 bench 输出，按**行**算一遍、按**不同像素**算一遍。
// 跑法：node _dupweight.cjs [edge]
const fs = require("fs");
const crypto = require("crypto");
const e = process.argv[2] || "384";
const L = fs.readFileSync(`bench/reg/bench_${e}.txt`, "utf8").split(/\r?\n/);
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const num = (l, k) => { const v = t(l, k); const n = v === null ? NaN : +v; return Number.isFinite(n) ? n : NaN };
const med = (a) => { const s = a.filter(Number.isFinite).sort((x, y) => x - y); return s.length ? s[s.length >> 1] : NaN };

const rows = [];
let n = null;
for (const l of L) {
  const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
  if (!n || !l.includes("色阶=")) continue;
  rows.push({ n, z: num(l, "字均净"), fill: num(l, "框内"), res: +(t(l, "残盘") || "0/0").split("/")[0], lv: +(t(l, "分档") || "0/0").split("/")[0] || 0, top: num(l, "顶占比") });
  n = null;
}
const md5 = new Map();
for (const r of rows) {
  const f = `rec3/${r.n}.png`;
  r.k = fs.existsSync(f) ? (md5.get(f) || (md5.set(f, crypto.createHash("md5").update(fs.readFileSync(f)).digest("hex")), md5.get(f))) : r.n;
}
const seen = new Set();
const uniq = rows.filter((r) => !seen.has(r.k) && seen.add(r.k));
const rep = (name, set) => {
  const faint = set.filter((r) => r.z < 200).length;
  console.log("  " + name.padEnd(14) + " 行/张数 " + String(set.length).padStart(4) +
    "   淡(<200) " + String(faint).padStart(3) + " (" + (faint * 100 / set.length).toFixed(0) + "%)" +
    "   字均净中位 " + String(med(set.map((r) => r.z))).padStart(4) +
    "   框内中位 " + med(set.map((r) => r.fill)).toFixed(2) +
    "   顶占比中位 " + String(med(set.map((r) => r.top))).padStart(3) +
    "   残盘合计 " + set.reduce((s, r) => s + r.res, 0));
};
console.log(`edge=${e}`);
rep("按行（今天）", rows);
rep("按不同像素", uniq);
console.log(`\n重复带来的权重：${rows.length - uniq.length} 行是逐字节重复`);
// 哪些应用被重复得最狠
const c = {};
for (const r of rows) c[r.k] = (c[r.k] || 0) + 1;
const top = Object.entries(c).filter(([, v]) => v > 1).sort((a, b) => b[1] - a[1]).slice(0, 8);
console.log("被重复最多的（次数  名字）：");
for (const [k, v] of top) { const f = rows.find((r) => r.k === k).n; console.log("  " + v + " ×  " + f.replace(/^icon\d+_/, "").replace(/-src$/, "").slice(0, 56)); }
