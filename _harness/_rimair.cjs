// 一次性手术：给 H1 加一道「整座色块浮在空气里 → 不许当底」的闸（主人 2026-10-15 点名 wenku8）。
// 为什么用脚本而不是多次 sed：多段 hunk 的 sed 在这个仓库翻过车；跑完必须 git diff 逐行看。
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

// ① 助手函数：量"画布最外那一圈有多少格是空气"
rep(
  "  private fun contentBounds(pixels: IntArray, w: Int, h: Int): Rect? {",
  `  /**
   * 画布**最外那一圈**里透明格占多少（0..100）。圈宽取 \`max(1, 边长/64)\` —— 384 档就是 6 格：
   * 挡得住一圈抗锯齿，又不会把"贴边的细条"当成整圈。
   *
   * 为什么另量一列、不复用 \`带占\`：\`带占\` 那条带是**内容框**的边界带（\`contentBounds\` 收到图形的
   * 外框就停了），所以"整座图形浮在空气里"的图标在它眼里带子是实心的（wenku8 \`带占=96\`）。
   * 这一列问的是另一件事：**这块画布到底有没有任何东西顶到边**。
   */
  private fun rimAirPct(pixels: IntArray, w: Int, h: Int): Int {
    val ring = (w / 64).coerceAtLeast(1)
    var air = 0
    var n = 0
    for (y in 0 until h) {
      val onRow = y < ring || y >= h - ring
      for (x in 0 until w) {
        if (!onRow && x >= ring && x < w - ring) continue
        n++
        if (((pixels[y * w + x] ushr 24) and 0xFF) < A_BOX) air++
      }
    }
    return if (n == 0) 0 else air * 100 / n
  }

  private fun contentBounds(pixels: IntArray, w: Int, h: Int): Rect? {`,
  "插入 rimAirPct"
);

// ② H1 的验收式加这一道，并把被它挡掉记进 veto（位 128）
rep(
  "      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p",
  `      // ⚠ 还有一句没写在纸面上的前提：**这座底得真有一块板**。四角下种子看的是内容框的带，
      //   所以"整座图形浮在空气里"的那种（蓝书 + 白书签、四周全透明）在它眼里也是"角上有块色"
      //   —— wenku8 就是这么把书腾空、只剩一条丝带的（\`底占=0.91 框内=0.07\`）。见 [RIM_ALL_AIR_PCT]。
      if (s.rimAir >= RIM_ALL_AIR_PCT) {
        if (p.share < PLATE_ALL_SHARE || p.families <= 1) s.veto = s.veto or 128
      } else if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p`,
  "H1 加外周全空气这道闸"
);

// ③ 报告里加一列，装机上能当场看见这一列是多少
rep(
  '" 盒布=${boxArea * 100 / (w.toLong() * h)} H0=${s.h0why}" +',
  '" 盒布=${boxArea * 100 / (w.toLong() * h)} 周空=${s.rimAir} H0=${s.h0why}" +',
  "报告加 周空= 一列"
);

// ④（groundPass 开头那一行已单独插好，见 s.rimAir = rimAirPct）

fs.writeFileSync(P, src);
console.log("写回完成，共 " + cuts + " 处");
