// 只加**诊断**，不改行为：把 H1 那块候选底的"墨里被围住的占比"打进日志（`围墨=`）。
// 为什么要它：主人要修 `org.mewx.wenku8`（蓝书 + 从顶边挂下来的白书签，现在只剩书签当墨）。
// 结构上的差别不在"贴不贴画布边"（那条今晚量过，兼容性台退 8 张，已撤），在**围合**：
// 书签没有被书围住 → 书是主体；而「渐变底+白字」那类字是被围住的 → 底该摘。
// 但 `围合` 这一列在 H2 那张真值表里已经被判过"分不开"（2026-10-01），所以这一列**先只量不裁**：
// 摊在夹具 + 语料 + 真机上看过分布、确有空档，再谈当闸。
const fs = require("fs");
const P = "D:/Github Repositories/Opticon/app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
let src = fs.readFileSync(P, "utf8");
let cuts = 0;
function rep(a, b, what) {
  const n = src.split(a).length - 1;
  if (n !== 1) throw new Error((n === 0 ? "找不到" : "匹配到 " + n + " 处，不敢猜") + "：" + what);
  src = src.split(a).join(b); cuts++;
  console.log("  ok " + what);
}

// ① Scratch 加一个字段
rep(
  "    var bandSolid = 0",
  `    var bandSolid = 0
    /** H1 那块候选底成立时，**墨里有多少 % 被这块底围住**（0..100，-1=没量）。只当诊断。 */
    var h1Enclosed = -1`,
  "Scratch 加 h1Enclosed"
);

// ② H1 里算一次（在 fitGround 之后、验收之前），只记数不裁
rep(
  "      p.thick = plateThickness(w, box, s)",
  `      p.thick = plateThickness(w, box, s)
      // ⚠ **只量不裁**：这一列是为主人点名的 wenku8 摊分布用的（见 [h1Enclosed]）。
      //   它不进下面任何一道验收 —— 摊完确认有空档之前，它没有资格当闸。
      s.h1Enclosed = enclosedShare(pixels, w, box, s)`,
  "H1 量一次围墨"
);

// ③ 报告里加一列
rep(
  '" 盒布=${boxArea * 100 / (w.toLong() * h)} H0=${s.h0why}" +',
  '" 盒布=${boxArea * 100 / (w.toLong() * h)} 围墨=${s.h1Enclosed} H0=${s.h0why}" +',
  "报告加 围墨= 一列"
);

fs.writeFileSync(P, src);
console.log("写回完成，共 " + cuts + " 处");
