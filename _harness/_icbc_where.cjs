const fs = require("fs");
const E = 384, N = E * E;
const a = fs.readFileSync("_ea/384/com.icbc_icon_8d0a0996d4c9.alpha"); // after (flatVote)
const b = fs.readFileSync("_eb/384/com.icbc_icon_8d0a0996d4c9.alpha"); // before
const src = fs.readFileSync("one_icbc/com.icbc_icon_8d0a0996d4c9.png");

// Δα 分布
const hist = {};
const changed = [];
for (let i = 0; i < N; i++) {
  const d = a[i] - b[i];
  if (d !== 0) {
    const k = Math.min(255, Math.abs(d));
    const bucket = k <= 8 ? "1-8" : k <= 16 ? "9-16" : k <= 32 ? "17-32" : k <= 64 ? "33-64" : ">64";
    hist[bucket] = (hist[bucket] || 0) + 1;
    changed.push({ i, d, x: i % E, y: (i / E) | 0 });
  }
}
console.log("Δα 分布:", JSON.stringify(hist));

// 变化格落在"墨"还是"盘"上（以 before 的 α 判：>=128 视为已在墨里）
let onInk = 0, onPlate = 0, onEdge = 0;
for (const c of changed) {
  const v = b[c.i];
  if (v >= 200) onInk++;
  else if (v <= 40) onPlate++;
  else onEdge++;
}
console.log(`落在 已墨(α≥200)=${onInk}  过渡(40<α<200)=${onEdge}  盘上(α≤40)=${onPlate}`);

// 变化格连成块（8 邻接），报块大小 + 离主体（α≥200 的格）最近距离
const seen = new Uint8Array(N);
const ch = new Uint8Array(N);
for (const c of changed) ch[c.i] = 1;
const ink = new Uint8Array(N);
for (let i = 0; i < N; i++) if (b[i] >= 200) ink[i] = 1;
const blocks = [];
for (const c of changed) {
  if (seen[c.i]) continue;
  const q = [c.i]; seen[c.i] = 1; let n = 0, minx = 1e9, maxx = -1, miny = 1e9, maxy = -1;
  const cells = [];
  while (q.length) {
    const p = q.pop(); n++; cells.push(p);
    const px = p % E, py = (p / E) | 0;
    if (px < minx) minx = px; if (px > maxx) maxx = px;
    if (py < miny) miny = py; if (py > maxy) maxy = py;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const nx = px + dx, ny = py + dy;
      if (nx < 0 || ny < 0 || nx >= E || ny >= E) continue;
      const np = ny * E + nx;
      if (ch[np] && !seen[np]) { seen[np] = 1; q.push(np); }
    }
  }
  // 这块离 α≥200 的主体多远（在大墨格上找最近，采样够用）
  let far = 1e9;
  for (const p of cells) {
    const px = p % E, py = (p / E) | 0;
    for (let r = 0; r <= 64; r++) {
      let hit = false;
      for (let dy = -r; dy <= r && !hit; dy++) for (let dx = -r; dx <= r && !hit; dx++) {
        const nx = px + dx, ny = py + dy;
        if (nx < 0 || ny < 0 || nx >= E || ny >= E) continue;
        if (ink[ny * E + nx]) hit = true;
      }
      if (hit) { if (r < far) far = r; break; }
    }
    if (far === 0) break;
  }
  blocks.push({ n, w: maxx - minx + 1, h: maxy - miny + 1, far });
}
blocks.sort((x, y) => y.n - x.n);
console.log(`变化块共 ${blocks.length} 块，前 8 大：`);
for (const bl of blocks.slice(0, 8))
  console.log(`  格数=${String(bl.n).padStart(4)}  包围盒=${bl.w}x${bl.h}  离主体=${bl.far === 1e9 ? "∞" : bl.far}格`);
const boxCells = blocks.reduce((s, x) => s + x.n, 0);
console.log(`合计 ${boxCells} 格 = 画布的 ${(boxCells / N * 100).toFixed(2)}%`);
