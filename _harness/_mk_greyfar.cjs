// 把上一把刀的**下刀范围**从"标签盖到的格子"放宽到"这一片灰本身"：
//   已经判成"整张就是一层灰"的那些图（两堆尾巴都 ≤5%），除了标签格子，
//   连**没被标签盖到**的 α 40..127 且离真墨 > 一圈带宽 的格子一起清。
// 为什么只在这些图上放宽：闸门还是原来那一个（两堆都没有暗部尾巴），
//   没开门的图一个像素都不碰 —— 王者荣耀/feimo/browndust2 那些"离墨很远的灰"是**画自己的层次**，
//   不是膜，这一版不动它们。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/GreyFar.kt";
let t = fs.readFileSync(src, "utf8");
const anchor = "    val sep = separation(out, w, h, box, s)";
if (!t.includes(anchor)) { console.log("找不到 sep 锚点"); process.exit(1); }
const ins = [
  "    // 开了门的图：把**没被标签盖到**的那部分灰也一起清掉（覆盖缺口就是这一条，见 README 2026-10-10）",
  "    var greyFarCut = 0",
  "    if (tonal != null && (filmP || filmD) && noDark) {",
  "      for (i in 0 until total) {",
  "        if (Color.alpha(pixels[i]) < A_SOLID) continue",
  "        val a2 = out[i] ushr 24",
  "        if (a2 < 40 || a2 > 127 || dist[i] <= bd0) continue",
  "        out[i] = 0; greyFarCut++",
  "      }",
  "    }",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);
const rep = '" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
if (!t.includes(rep)) { console.log("找不到报表锚点"); process.exit(1); }
t = t.split(rep).join('" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN 散灰=$greyFarCut"),');
fs.writeFileSync(dst, t);
console.log("已写出 cand/GreyFar.kt");
