// 路线 A 的**能活下来的那一版**：只清"敞着的一片"，不清"被自己的轮廓包起来的一块"。
//
// 上一版（`cand/GreyFar.kt`）把 α 40..127 且离墨 > 一圈带宽的格子一起归零，A/B 拼图上：
//   易班那只熊、驾考那辆车、知到那个"到"字 —— **整块没了**。它们和膜一样是"浅的、离墨远的"，
//   所以那条尺子量错了对象：那不是膜，那是**有轮廓包着的内容**。
// 换一个形状事实：**从画布边上出发，只踩着浅格子走，走不到的那些浅格子就是被墨围起来的**
//   —— 熊有黑色的眼睛鼻子勾边，走进去就断；而浙江在线/滴滴那种灰膜整片是**敞开的**，一路能走到边。
// 这把尺不新造任何数：用的还是"浅/墨"那两条已经钉好的线（α<128 与 α≥128）+ 四邻接连通。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/OpenGrey.kt";
let t = fs.readFileSync(src, "utf8");
const anchor = "    val sep = separation(out, w, h, box, s)";
if (!t.includes(anchor)) { console.log("找不到锚点"); process.exit(1); }
const ins = [
  "    // 只在**已经开门的图**上做（两堆都没有暗部尾巴 = 整张就是一层灰），没开门的一张不碰。",
  "    var openGrey = 0",
  "    if (tonal != null && (filmP || filmD) && noDark) {",
  "      val seen = ByteArray(total)",
  "      val q = IntArray(total); var head = 0; var tail = 0",
  "      fun shallow(i: Int) = Color.alpha(pixels[i]) >= A_SOLID &&",
  "        (out[i] ushr 24) in 1..127",
  "      fun push(i: Int) { if (seen[i] == 0.toByte() && shallow(i)) { seen[i] = 1; q[tail++] = i } }",
  "      for (x in 0 until w) { push(x); push((h - 1) * w + x) }",
  "      for (y in 0 until h) { push(y * w); push(y * w + w - 1) }",
  "      while (head < tail) {",
  "        val i = q[head++]",
  "        val cy = i / w; val cx = i - cy * w",
  "        if (cx > 0) push(i - 1)",
  "        if (cx < w - 1) push(i + 1)",
  "        if (cy > 0) push(i - w)",
  "        if (cy < h - 1) push(i + w)",
  "      }",
  "      // 走不到的浅格子 = 被墨围起来的**内容**，保留；走得到的 = 敞开的膜，且离真墨一圈以上才清。",
  "      for (i in 0 until total) {",
  "        if (seen[i] != 0.toByte() || !shallow(i) || dist[i] <= bd0) continue",
  "        out[i] = 0; openGrey++",
  "      }",
  "    }",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);
const rep = '" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
if (!t.includes(rep)) { console.log("找不到报表锚点"); process.exit(1); }
t = t.split(rep).join('" 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN 敞灰=$openGrey"),');
fs.writeFileSync(dst, t);
console.log("已写出 cand/OpenGrey.kt");
