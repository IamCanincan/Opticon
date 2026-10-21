const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/FilmRel.kt";
let t = fs.readFileSync(src, "utf8");
const A = "    fun ok(h: IntArray, n: Int, deep: Int) = n >= PLATE_FILM_MIN && deep in 0..PLATE_FILM_DEEP";
const B = "    val noDark = (pN < PLATE_FILM_EVID || pDeep in 0..PLATE_FILM_DEEP) &&\n      (dN < PLATE_FILM_EVID || dDeep in 0..PLATE_FILM_DEEP)";
for (const [nm, s] of [["ok()", A], ["noDark", B]]) if (!t.includes(s)) { console.log("找不到 " + nm); process.exit(1); }
t = t.replace(A, [
"    // ⚠ **门槛必须是分数，不能是绝对格数**（2026-10-11 量出来的自己的 bug）：",
"    //   原来写 `n >= 3000`，而 96 档整张才 9216 格 —— 易班的底集 2810 格、京东 1401 格，",
"    //   **同一把刀在低分辨率上根本不响**（384 档分别是 43532 / 21397）。桌面按图层 bounds 出图，",
"    //   尺寸由桌面定，不是我们能挑的 —— 绝对数等于把修法绑死在某一档边长上。",
"    //   与 [tonalClass] 那条「框比格小就不算」是同一类错（6a2c318 修过一次，这次是我新写的）。",
"    fun ok(n: Int, deep: Int) = n * PLATE_FILM_MIN_PCT >= total && deep in 0..PLATE_FILM_DEEP",
].join("\n"));
t = t.replace(B, [
"    // 反证那一格也一样：`200 格`在 96 档是 2.2% 的画布、在 384 档只有 0.14% —— 换成占画布的分数。",
"    val noDark = (pN * 1000 < total || pDeep in 0..PLATE_FILM_DEEP) &&\n" +
"      (dN * 1000 < total || dDeep in 0..PLATE_FILM_DEEP)",
].join("\n"));
t = t.replace("    if (filmP) cutP = filmCutOf(true)", "    if (filmP) cutP = filmCutOf(true)");
t = t.replace("      if (filmP) cutP = filmCutOf(true)", "      if (filmP) cutP = filmCutOf(true)");
t = t.replace("val filmP = ok(hp, pN, pDeep); val filmD = ok(hd, dN, dDeep)",
              "val filmP = ok(pN, pDeep); val filmD = ok(dN, dDeep)");
const C = "  private const val PLATE_FILM_MIN = 3000";
if (!t.includes(C)) { console.log("找不到常量"); process.exit(1); }
t = t.replace(C, [
"  /** 这一堆至少占**整张画布**的这么多% 才算一块「铺开的膜」（不是绝对格数，见上）。 */",
"  private const val PLATE_FILM_MIN_PCT = 2",
"",
"  /** @deprecated 已被分数版取代，留着只为让旧日志的读数还能对上。 */",
"  private const val PLATE_FILM_MIN_UNUSED = 3000",
].join("\n"));
fs.writeFileSync(dst, t);
console.log("已写出 cand/FilmRel.kt");
