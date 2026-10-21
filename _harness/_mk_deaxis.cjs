// 生成候选：广色度这一支的浓度轴从 ΔL 换成 **ΔE（离这张自己的主色多远）**。
// 为什么值得试：ChromaKt 量到 驾考 L跨 64 → ΔE跨 100、WPS 11 → 21 —— 色度轴上
// 有明度轴漏掉的量程。而且 ΔE 天生**不需要判方向**（暗=墨 / 亮=墨 那一刀是为了
// 在明度轴上选边，换成"离主色多远"之后这个选择本身消失了）。
const fs = require("fs");
const P = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
let s = fs.readFileSync(P, "utf8");

// ── 1) Tonal 多带四个数：主色的 Lab 与自己的 ΔE p95 ─────────────────────────
const A1 = "  internal class Tonal(val tex: Int, val flat: Int, val span: Int, val lo: Int, val hi: Int) {";
if (!s.includes(A1)) { console.error("A1 没找到"); process.exit(1); }
s = s.replace(A1,
  "  internal class Tonal(val tex: Int, val flat: Int, val span: Int, val lo: Int, val hi: Int,\n" +
  "    /** 【候选 deAxis】主色（占格最多那一格的平均色）的 Lab×64，与这张自己的 ΔE p95。 */\n" +
  "    val rl: Int = 0, val ra: Int = 0, val rb: Int = 0, val deHi: Int = 0) {");

// ── 2) tonalClass：算完格子之后，再走一遍实心像素数 ΔE 分布 ───────────────────
const A2 = "    val lo = percentile(hist, opq, LUM_LOW_PCT) shl 6\n" +
  "    val hi = percentile(hist, opq, LUM_HIGH_PCT) shl 6\n" +
  "    return Tonal(\n" +
  "      (mid * 100 / pairs).toInt(),\n" +
  "      (share.values.maxOrNull() ?: 0) * 100 / cells,\n" +
  "      (hi - lo) shr 6, lo, hi\n" +
  "    )";
if (!s.includes(A2)) { console.error("A2 没找到"); process.exit(1); }
s = s.replace(A2,
  "    val lo = percentile(hist, opq, LUM_LOW_PCT) shl 6\n" +
  "    val hi = percentile(hist, opq, LUM_HIGH_PCT) shl 6\n" +
  "    // 【候选 deAxis】主色 = 占格最多那一格的平均色（**不是精确色计数** —— 那条会被渐变盘骗，\n" +
  "    //   本仓量过：主色落到中间色 → 全图都\u201c离主调很远\u201d → `框内=0.9856` 涂满）。\n" +
  "    val refC = share.maxByOrNull { it.value }?.key\n" +
  "    if (refC == null) return null        // 一格都没有 —— 连主色都无从谈起，不是被挡掉的情形\n" +
  "    val rl = labChannel(refC, 0); val ra = labChannel(refC, 1); val rb = labChannel(refC, 2)\n" +
  "    val deH = IntArray(102); var deN = 0\n" +
  "    for (y in box.top until box.bottom) {\n" +
  "      val row = y * w\n" +
  "      for (x in box.left until box.right) {\n" +
  "        val p = pixels[row + x]\n" +
  "        if (Color.alpha(p) < A_SOLID) continue\n" +
  "        deN++\n" +
  "        val dl = (labChannel(p, 0) - rl) / 64.0; val da = (labChannel(p, 1) - ra) / 64.0\n" +
  "        val db = (labChannel(p, 2) - rb) / 64.0\n" +
  "        deH[Math.sqrt(dl * dl + da * da + db * db).coerceIn(0.0, 100.0).toInt()]++\n" +
  "      }\n" +
  "    }\n" +
  "    val deHi = if (deN > 0) percentile(deH, deN, 95) shl 6 else 0\n" +
  "    return Tonal(\n" +
  "      (mid * 100 / pairs).toInt(),\n" +
  "      (share.values.maxOrNull() ?: 0) * 100 / cells,\n" +
  "      (hi - lo) shr 6, lo, hi, rl, ra, rb, deHi\n" +
  "    )");

// ── 3) 浓度线：ΔL 斜坡 → ΔE 斜坡 ──────────────────────────────────────────
const A3 = "          val l64 = s.labL[i].toInt() and 0xffff\n" +
  "          val ramp = if (tonalBright) l64 - tonal.lo else tonal.hi - l64\n" +
  "          (ramp * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255) *\n" +
  "            Color.alpha(pixels[i]) / 255";
if (!s.includes(A3)) { console.error("A3 没找到"); process.exit(1); }
s = s.replace(A3,
  "          // 【候选 deAxis】浓度 = 离主色的 ΔE76，端点是这张自己的 p5/p95（不是常数）。\n" +
  "          //  方向那一判在这里**不再需要**：\u201c离主色多远\u201d对亮侧暗侧同向，\n" +
  "          //  所以 `tonalBright` 这一支的翻与不翻只影响参照那条路，不影响这里。\n" +
  "          val dl = ((s.labL[i].toInt() and 0xffff) - tonal.rl) / 64.0\n" +
  "          val da = ((s.labA[i].toInt() and 0xffff) - tonal.ra) / 64.0\n" +
  "          val db = ((s.labB[i].toInt() and 0xffff) - tonal.rb) / 64.0\n" +
  "          val de = Math.sqrt(dl * dl + da * da + db * db)\n" +
  "          (de * 255.0 / maxOf((tonal.deHi shr 6).toDouble(), 1.0)).coerceIn(0.0, 255.0).toInt() *\n" +
  "            Color.alpha(pixels[i]) / 255");

fs.writeFileSync("cand/MonochromeMask.deAxis.kt", s);
console.log("写好 cand/MonochromeMask.deAxis.kt");
