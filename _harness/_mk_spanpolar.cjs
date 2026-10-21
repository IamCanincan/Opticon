// 生成候选：开火只看跨度 + 方向由「参照那一支量出的底有多亮」定。
// 为什么单独一个脚本：这台机器上 `node -e` 会把字符串里的双引号吃掉（本仓记过两次），
// 而 Kotlin 注释里想引用词语只能用「」。
const fs = require("fs");
const P = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
let s = fs.readFileSync(P, "utf8");

const GATE = "    val tonal = tonalClass(pixels, w, h, box)?.takeIf {\n" +
  "      it.on || (agree && it.tex >= TONAL_TEX_MIN && it.span >= TONAL_SPAN_MIN)\n" +
  "    }";
if (!s.includes(GATE)) { console.error("!! 闸的锚点没找到"); process.exit(1); }
const NEWGATE = [
  "    // 【候选 spanPolar】两件事一起改：",
  "    //  ① 开火条件**只看跨度**。纹理与平涂那两道拆掉 —— 它们挡住的正是「看图判变好」那批",
  "    //    （驾考 纹理 17、azure 18、易班 13、微博 8），而跨度那道是**结构性**守卫：",
  "    //    真机上被「全开」弄空的 6 张，跨度全是 0~7（支付宝 1、钉钉 0、翻译 7）——",
  "    //    一块白剪影坐在空气上本来就没有调子，而下面那条斜坡会把最亮的白直接归零。",
  "    //  ② **方向不再写死「暗=墨」**，改用参照那一支**已经量出来的那块底有多亮**：",
  "    //    底暗 → 墨在亮侧（翻），底亮 → 墨在暗侧（与今天一致）。前提沿用 [agree] 那道",
  "    //    「这块底得真的存在」（gN 至少占实心两成）—— 满幅插画那类 ground 不可信，那时不翻，",
  "    //    而那一类正是这一支存在的原因。不新造尺子：用的就是 groundPass 交回来的 gL/gN/iN。",
  "    val tonal = tonalClass(pixels, w, h, box)?.takeIf { it.span >= TONAL_SPAN_MIN }",
  "    val tonalBright = tonal != null && ground.gN * 5 >= (ground.gN + ground.iN) &&",
  "      (ground.gL shr 6) < TONAL_PLATE_L_MIN",
].join("\n");
s = s.replace(GATE, NEWGATE);

const ALPHA = "          val l64 = s.labL[i].toInt() and 0xffff\n" +
  "          ((tonal.hi - l64) * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255) *\n" +
  "            Color.alpha(pixels[i]) / 255";
if (!s.includes(ALPHA)) { console.error("!! 浓度那几行没找到"); process.exit(1); }
s = s.replace(ALPHA, "          val l64 = s.labL[i].toInt() and 0xffff\n" +
  "          val ramp = if (tonalBright) l64 - tonal.lo else tonal.hi - l64\n" +
  "          (ramp * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255) *\n" +
  "            Color.alpha(pixels[i]) / 255");

fs.writeFileSync("cand/MonochromeMask.spanPolar.kt", s);
console.log("写好 cand/MonochromeMask.spanPolar.kt");
