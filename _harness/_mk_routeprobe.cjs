// 把已经量过的"这块底有没有暗部尾巴"**提前**到换支判据之前，好让它当**路由器**用。
// 提前就不能等斜坡了 —— 斜坡在 build 深处。所以试一个**只看源色**的替身：
//   底那一片（plate ∪ dep≤零）里，源 L ≤ 40 的格子占多少。
// 这一列只是观测量，先验证它和**装机那把尺**（交付 α≥128 的尾巴率）排不排得开同一批人，
//  排不开就没有路由器可谈 —— 那五把死尺已经教过：想路由，先证明判据分得开。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/RouteProbe.kt";
let t = fs.readFileSync(src, "utf8");
const anchor = "    var rimGrew = 0";
if (!t.includes(anchor)) { console.log("找不到锚点"); process.exit(1); }
const ins = [
  "    var srcDark = 0; var srcReg = 0",
  "    if (tonal != null) for (i in 0 until total) {",
  "      if (Color.alpha(pixels[i]) < A_SOLID) continue",
  "      val on = s.plate[i] != 0.toByte() || (s.distT[i].toInt() and 0xffff) <= ground.zero",
  "      if (!on) continue",
  "      srcReg++",
  "      if (((s.labL[i].toInt() and 0xffff) shr 6) <= 40) srcDark++",
  "    }",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);
const rep = '" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
if (!t.includes(rep)) { console.log("找不到报表锚点"); process.exit(1); }
t = t.split(rep).join(
  '" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN 源暗=${if (srcReg == 0) -1 else srcDark * 100 / srcReg}"),');
fs.writeFileSync(dst, t);
console.log("已写出 cand/RouteProbe.kt");
