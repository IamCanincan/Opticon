// 把「第二层抬顶」接到满幅那一支上（插在灰膜那一刀之后），并加一列 `抬=`。
// 为什么写成文件：`node -e "…"` 会把反引号当命令替换吃掉 —— 这条今天已经咬过两次。
const fs = require("fs");
const src = "cand/FilmNoTouch.kt", dst = "cand/MarkLift.kt";
let t = fs.readFileSync(src, "utf8");

const anchor = "    val sep = separation(out, w, h, box, s)";
if (!t.includes(anchor)) { console.log("找不到 sep 锚点"); process.exit(1); }

const ins = [
  "    // 满幅那一支以前**根本不跑**第二层抬顶（`build` 里那句 `if (tonal == null)`），于是日志里",
  "    //  那张 `色阶=…+已满` 其实是**没跑**的意思，不是\"已经满了\"—— 主人报的\"不干净\"就藏在这句谎里：",
  "    //  索尼 songpal 一整块**平色橙**（源 #FE6633，占实心 34%）交出去 α=168，屏幕上就是一层橘灰的糊。",
  "    //  参照那一支遇到\"一种颜色画的东西\"会把它抬实，这一支却跳过抬顶 ——",
  "    //  **同一张图换一支就换一种说法，这是不一致，不是设计。**",
  "    // ⚠ 必须排在灰膜那一刀**之后**：抬顶会把膜格子也抬过 128，那一刀就再也认不出它们了。",
  '    var markLift = ""',
  "    if (tonal != null) markLift = stretchMark(out, pixels, w, h, box, s)",
  "",
].join("\n");
t = t.replace(anchor, ins + anchor);

const rep = '(if (tonal == null) "" else " 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN"),';
const repNew = '(if (tonal == null) "" else " 膜刀=$cutP+$cutD 膜尾=$pDeep/$dDeep 膜格=$pN/$dN 抬=$markLift"),';
if (!t.includes(rep)) { console.log("找不到报表锚点 " + JSON.stringify(t.slice(t.indexOf("膜刀=") - 20, t.indexOf("膜刀=") + 120))); process.exit(1); }
t = t.split(rep).join(repNew);
fs.writeFileSync(dst, t);
console.log("已写出 cand/MarkLift.kt");
