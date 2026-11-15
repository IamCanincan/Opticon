// 库里那张单色掩码里，**有没有一圈几乎看不见的底**？
//   起因（2026-11-15 看图）：cainiao 的单色幅 0.953，但看得见的机器人只有 ~0.6 ——
//   差的那一圈是四角上 α 刚过 8 的淡弧（_shotcmp 图里肉眼可见的虚线角）。
//   α>8 这条取墨线会把这种"几乎透明的底盘"算成墨 ⇒ 外接框被它撑到整幅。
// 这把尺只报数：每个包 α∈[9,63] 的格数、α>8 的外接框、α≥64 的外接框、两者差。
// 用法：node _harness/_faintring.cjs <app_icons.db> [包名子串…]
const { DatabaseSync } = require('node:sqlite');
const args = process.argv.slice(2);
const dbp = args.shift();
const needles = args.filter((a) => !a.startsWith('--'));
const ext = (A, n, thr) => {
  let l = n, t = n, r = -1, b = -1;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!thr(A[y * n + x])) continue;
    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  return r < 0 ? 0 : Math.max(r - l + 1, b - t + 1) / n;
};
const rows = [];
const picked = new Map();
for (const r of new DatabaseSync(dbp, { readOnly: true }).prepare('SELECT componentName c, mono_icon m FROM icons').all()) {
  const pkg = r.c.split('/')[0];
  if (!r.m || !pkg.includes('.')) continue;
  if (needles.length && !needles.some((x) => r.c.includes(x))) continue;
  if (!picked.has(pkg)) picked.set(pkg, r);
}
for (const [pkg, r] of picked) {
  const mb = Buffer.from(r.m), n = Math.round(Math.sqrt(mb.length));
  if (n * n !== mb.length) continue;
  const A = new Int32Array(n * n);
  for (let i = 0; i < n * n; i++) A[i] = mb[i];
  let faint = 0; const hist = new Array(8).fill(0);
  for (let i = 0; i < n * n; i++) { const a = A[i]; if (a >= 9 && a <= 63) faint++; hist[Math.min(7, a >> 5)]++; }
  const e8 = ext(A, n, (a) => a > 8), e64 = ext(A, n, (a) => a >= 64);
  // 外圈 4 格里有没有墨：若桌面往库里这张 blob 上补过主题底盘，**每个包**都该有 ⇒ 用它认归属
  let ring8 = 0, ring64 = 0, ringN = 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (x >= 4 && y >= 4 && x < n - 4 && y < n - 4) continue;
    ringN++; const a = A[y * n + x]; if (a > 8) ring8++; if (a >= 64) ring64++;
  }
  rows.push([pkg, faint / (n * n), e8, e64, e8 - e64, hist, ring8 / ringN, ring64 / ringN]);
}
rows.sort((a, b) => b[4] - a[4]);
const moved = rows.filter((x) => x[4] > 0.05);
console.log(`共 ${rows.length} 个包；α>8 与 α≥64 的外接框差 >0.05 的：**${moved.length} 个**`);
for (const [pkg, f, e8, e64, d] of moved) {
  console.log(`  差 ${d.toFixed(3)}  ${pkg.padEnd(40)} 淡格(9..63)占 ${(f * 100).toFixed(2)}%  框 α>8 ${e8.toFixed(3)} → α≥64 ${e64.toFixed(3)}`);
}
const ringed = rows.filter((x) => x[6] > 0.02);
console.log(`外圈 4 格里 α>8 的墨占外圈 >2% 的：**${ringed.length} / ${rows.length} 个**（若桌面补过底盘，这里该是全部）`);
for (const [pkg, , , , , , r8, r64] of ringed.sort((a, b) => b[6] - a[6])) {
  console.log(`  外圈墨 ${(r8 * 100).toFixed(1).padStart(5)}%（其中 α≥64 ${(r64 * 100).toFixed(1)}%）  ${pkg}`);
}
if (needles.length) {
  console.log('  指定包的 α 直方图（每档 32 宽：0-31,32-63,…,224-255）：');
  for (const [pkg, , , , , hist] of rows) console.log(`    ${pkg.padEnd(40)} ${hist.map((h) => String(h).padStart(7)).join(' ')}`);
}