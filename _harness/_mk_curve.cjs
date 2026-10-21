// 满幅那一支上叠一条**桌面自己那条对比度曲线**（AOSP MonochromeIconFactory：
//   value' = contrast·value + 128·(1−contrast)，contrast=1.5 → α' = 1.5α − 64）。
// 为什么是它而不是我上一版试的"整批乘 1.35"：
//   乘只往上推 → 254/255 一起夹到 255、暗部并档（实测把刚修好的闲鱼/华为又压平，平色饼 8→10）；
//   绕 128 对称拉开 → 亮的推向空、暗的推向实，**中间那段的排序一格不动**，两端都留了余量。
// 数不自造：1.5 与 128 都是桌面那份的常量，我们只是把它给未适配图标的那条曲线，
//   也给我们自己合成的那张。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/Curve.kt";
let t = fs.readFileSync(src, "utf8");
const anchor = "    val sep = separation(out, w, h, box, s)";
if (!t.includes(anchor)) { console.log("找不到锚点"); process.exit(1); }
const ins = [
  "    // 桌面给未适配图标的那条曲线，这里给满幅那一支也上一遍（见 README「上屏的浓度是桌面给的」）。",
  "    var curve = 0",
  "    if (tonal != null) {",
  "      for (i in 0 until total) {",
  "        if (Color.alpha(pixels[i]) < A_SOLID) continue",
  "        val a3 = out[i] ushr 24",
  "        if (a3 == 0) continue",
  "        val v = ((a3 * 3 / 2) - 64).coerceIn(0, 255)",
  "        if (v != a3) { out[i] = (v shl 24) or 0x00FFFFFF; curve++ }",
  "      }",
  "    }",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);
const rep = '" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
if (!t.includes(rep)) { console.log("找不到报表锚点"); process.exit(1); }
t = t.split(rep).join('" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN 曲线=$curve"),');
fs.writeFileSync(dst, t);
console.log("已写出 cand/Curve.kt");
