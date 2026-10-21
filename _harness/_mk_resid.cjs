// 在**已装机那一份**之上只加两个观测量（不改任何交付）：
//   灰总 = 交付 α 落在 40..127 的格子数（屏幕上就是一层看得见的灰）
//   灰内 = 其中**离最近的真墨（α≥128）超过一圈带宽**的那些 —— 也就是"一大片灰、旁边没有东西"。
// 为什么要它：上一把刀是按 `plate`/`dep` 标签下刀的，标签没盖到的灰就是它的覆盖缺口，
//  而缺口只能用一把不看标签的尺子量。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/Resid.kt";
let t = fs.readFileSync(src, "utf8");
const anchor = "    val sep = separation(out, w, h, box, s)";
if (!t.includes(anchor)) { console.log("找不到 sep 锚点"); process.exit(1); }
const ins = [
  "    var greyAll = 0; var greyFar = 0",
  "    for (i in 0 until total) {",
  "      if (Color.alpha(pixels[i]) < A_SOLID) continue",
  "      val a2 = out[i] ushr 24",
  "      if (a2 < 40 || a2 > 127) continue",
  "      greyAll++; if (dist[i] > bd0) greyFar++",
  "    }",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);
const rep = '(if (tonal == null) "" else " 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
if (!t.includes(rep)) { console.log("找不到报表锚点"); process.exit(1); }
t = t.split(rep).join('(if (tonal == null) "" else " 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN") +\n          " 灰内=$greyFar 灰总=$greyAll",');
fs.writeFileSync(dst, t);
console.log("已写出 cand/Resid.kt");
