const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/Both.kt";
let t = fs.readFileSync(src, "utf8");
const A = "  fun build(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false): Output {";
const B = "    val tonal = tonalClass(pixels, w, h, box)?.takeIf { it.on }";
const C = "  private const val PLATE_FILM_MIN_PCT = 2";
for (const [nm, s] of [["build入口", A], ["入场判据", B], ["常量锚点", C]]) if (!t.includes(s)) { console.log("找不到 " + nm); process.exit(1); }
const W = [
"  /** **两条都算、按结果选**（主人要「非适配取色应用全覆盖，和已适配的一样美观」）。",
"   *  选参照那一支的两个条件都量自**成品**：淡膜占内切框 ≤ [PICK_SOFT_MAX]，",
"   *  且交出去的墨 ≥ 满幅那一支的 [PICK_INK_MIN]%（后一条专防「被擦成碎墨」）。",
"   *  ⚠ 代价：走满幅那一支的图要算两遍。 */",
"  fun build(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false): Output {",
"    val ramp = buildOnce(pixels, w, h, plateIsLayer, false)",
"    if (!ramp.report.contains(\"调=1\")) return ramp          // 本来就走参照那一支，不必算第二遍",
"    val ref = buildOnce(pixels, w, h, plateIsLayer, true)",
"    return if (picksRef(w, h, ramp, ref)) ref else ramp",
"  }",
"",
"  /** 淡膜占内切框%、交出去的墨占内切框% —— 与判据台 FilmAreaKt **同一把尺**。 */",
"  private fun softAndInk(out: IntArray, w: Int, h: Int): Pair<Int, Int> {",
"    val b = w * 3 / 4; val o = (w - b) / 2; var soft = 0; var ink = 0",
"    for (y in o until o + b) for (x in o until o + b) {",
"      val a = (out[y * w + x] ushr 24) and 0xff",
"      if (a > 0) { ink++; if (a < 128) soft++ }",
"    }",
"    val n = b.toLong() * b / 100",
"    return if (n == 0L) Pair(0, 0) else Pair((soft * 100 / n).toInt(), (ink * 100 / n).toInt())",
"  }",
"",
"  private fun picksRef(w: Int, h: Int, ramp: Output, ref: Output): Boolean {",
"    val r = softAndInk(ref.mask, w, h); val s = softAndInk(ramp.mask, w, h)",
"    return r.first <= PICK_SOFT_MAX && r.second * 100 >= s.second * PICK_INK_MIN",
"  }",
"",
"  private fun buildOnce(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean,",
"    forceRef: Boolean",
"  ): Output {",
""].join("\n");
t = t.replace(A, W);
t = t.replace(B, "    val tonal = if (forceRef) null else tonalClass(pixels, w, h, box)?.takeIf { it.on }");
t = t.replace(C, [
"  /** 选参照那一支的条件①：淡膜占内切框 ≤ 这个数（已适配那批自己 p90 = 3.5%）。 */",
"  private const val PICK_SOFT_MAX = 4",
"  /** 选参照那一支的条件②：墨量至少是满幅那一支的这么多%（防「被擦成碎墨」）。 */",
"  private const val PICK_INK_MIN = 60",
C].join("\n"));
fs.writeFileSync(dst, t);
console.log("已写出 cand/Both.kt");
