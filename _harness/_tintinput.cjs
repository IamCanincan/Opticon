// 一次性手术：把「裁圆调整好的那张图标」搬到**判据之前**（主人 2026-10-15 的裁决：
// 「取色判断得按调整后的图标来，不然到时候出错了」）。
// 为什么用脚本而不是多次 sed：多段 hunk 的 sed 在这个仓库翻过车。跑完必须 git diff 逐行看。
const fs = require("fs");
const R = "D:/Github Repositories/Opticon/app/src/main/kotlin/com/iamcanincan/opticon/circle/";
let cuts = 0;

function load(f) { return fs.readFileSync(R + f, "utf8"); }
function save(f, s) { fs.writeFileSync(R + f, s); }
function rep(store, file, a, b, what) {
  const n = store.src.split(a).length - 1;
  if (n !== 1) throw new Error(file + " → " + (n === 0 ? "找不到" : "匹配到 " + n + " 处，不敢猜") + "：" + what);
  store.src = store.src.split(a).join(b); cuts++;
  console.log("  ok " + what);
}
/** 从 startMarker 起、到 endMarker（含）整段换掉。 */
function cut(store, file, start, end, text, what) {
  const i = store.src.indexOf(start);
  if (i < 0) throw new Error(file + " → 找不到起点：" + what);
  const j = store.src.indexOf(end, i);
  if (j < 0) throw new Error(file + " → 找不到终点：" + what);
  store.src = store.src.slice(0, i) + text + store.src.slice(j + end.length);
  cuts++;
  console.log("  ok " + what + "（换掉 " + (j - i) + " 字符）");
}

/* ───────────── 1. IconCropWindow：采样换回最近邻 ───────────── */
const W = { src: load("IconCropWindow.kt") };
cut(W, "IconCropWindow.kt",
  "   * ## 采样：搬**掩码**用双线性",
  "   *   最近邻则 `branch`/`platecut` 与基线逐字相同。见分支 `wip/tint-silhouette` 的提交说明。\n",
  `   * ## 采样必须是最近邻 —— 因为现在**判据吃的就是这张重排后的图**
   * 2026-10-15 主人把这一刀搬到判据之前（「取色判断得按调整后的图标来，不然到时候出错了」）：
   * 交出去的那张 = 判过的那张，不再事后 resample。代价是采样不能再用双线性 —— 双线性会造出
   * **源图里根本没有的中间浓度**，而底色场/分档吃的正是浓度分布。那条路量过：双线性让
   * \`com.microsoft.xboxone.smartglass\` 在 384 上**换支**、\`底占 0.45→0.00\`（整张交成一块黑饼），
   * 源尺寸漂移面 81%→84%；最近邻则 \`branch\`/\`platecut\` 与基线**逐字相同**。
   * ⚠ 最近邻不做抗锯齿：重排完边缘是阶梯。桌面那 1.5 倍缩放会抹掉一部分，但 \`淡\`/\`soft\`
   *   那一列会变 —— 所以这一版的账必须重量，不许引用双线性那版的数。
   */\n`,
  "采样那段文档");

rep(W, "IconCropWindow.kt",
  `    for (y in 0 until h) {
      // 目标像素 → 源坐标（draw() 那一步的逆变换：先减目标心、除 scale、再加回窗口心）
      val sy = (y + 0.5f - cyDst) / scale + cySrc
      for (x in 0 until w) {
        val sx = (x + 0.5f - cxDst) / scale + cxSrc
        out[y * w + x] = sample(px, w, h, sx, sy)
      }
    }`,
  `    for (y in 0 until h) {
      // 目标像素 → 源坐标（draw() 那一步的逆变换：先减目标心、除 scale、再加回窗口心）。
      // ⚠ **最近邻**：判据吃的就是这张，插值会凭空造出浓度（理由见函数上那段文档）。
      val sy = ((y + 0.5f - cyDst) / scale + cySrc).toInt()
      if (sy !in 0 until h) continue
      val row = sy * w
      for (x in 0 until w) {
        val sx = ((x + 0.5f - cxDst) / scale + cxSrc).toInt()
        if (sx in 0 until w) out[y * w + x] = px[row + sx]
      }
    }`,
  "重排换成最近邻");

cut(W, "IconCropWindow.kt",
  "  /**\n   * ⚠ **只对 alpha 双线性插值",
  "    return (a shl 24) or (nearest and 0x00FFFFFF)\n  }\n",
  "", "删掉双线性那个 sample（没有调用方了）");
save("IconCropWindow.kt", W.src);

/* ───────────── 2. MonochromeSynth：几何搬到判据之前 ───────────── */
const S = { src: load("MonochromeSynth.kt") };
rep(S, "MonochromeSynth.kt",
  "      val out = MonochromeMask.build(pixels, edge, edge, plateLayer, underPx)",
  `      // ── 几何搬到**判据之前**（主人 2026-10-15：「取色判断得按调整后的图标来，不然到时候出错了」）──
      //   交出去那张 = 判过那张，不再事后 resample。上一版（甲）判据吃原件、只把成品搬位置，
      //   于是**判据台量的那张与屏幕上的那张不是同一张**：倍数逐张相同也能差 1.5 倍，
      //   2026-10-15 为这件事返工了三轮，最后是靠量交付掩码的外接框才抓出来。
      // ⚠ 目标矩形**分两档** —— 这一条就是昨晚乙翻车的地方：
      //   · 自适应：桌面画这一层按 1.5 倍铺进去、只画中间那 2/3 → 输入映射到**可见区**；
      //   · 非自适应：判据必须吃**满幅口径**。先缩进可见区会在四周留一圈透明边，把一张满幅
      //     不透明的老式图标量成"透明底 + 图形" —— 那正是 2026-09-24「非自适应图标全部变成
      //     色块」的病根（见 [rasterize] 上面那段）。所以这一档映射到整张画布，位置留到掩码
      //     算完之后由 [intoVisibleArea] 收。**各收一次，不许收两遍。**
      // ⚠ \`plateLayer\` 仍按**原始像素**判：它问的是"盘在哪一层"，那是图层结构的事，不因放大
      //   而变；而 35% 那条线是在原始取景下摊过分母定的（上面那段），换取景再比 = 拿没标定的尺量。
      val inset = visibleInset(edge)
      val visT = edge - 2 * inset
      val tOff = if (adaptive) inset else 0
      val tSide = if (adaptive) visT else edge
      val pxIn = if (win == null) pixels
        else IconCropWindow.reframe(pixels, edge, edge, win, tOff, tOff, tSide, tSide)
      // \`under\` 必须一起搬，否则模型看到的合成图与它背后那张层底对不上（真机上整张图标都动了）。
      val underIn = if (win == null || underPx == null) underPx
        else IconCropWindow.reframe(underPx, edge, edge, win, tOff, tOff, tSide, tSide)
      val out = MonochromeMask.build(pxIn, edge, edge, plateLayer, underIn)`,
  "输入侧几何 + 交给模型");

rep(S, "MonochromeSynth.kt",
  "      val sig = MonoDump.sigOf(pixels, edge, edge)",
  "      val sig = MonoDump.sigOf(pxIn, edge, edge)",
  "指纹打在模型真吃的那张上");

rep(S, "MonochromeSynth.kt",
  "        MonoDump.maybe(id, label, raster, pixels, out.mask, underPx, plateLayer, edge, edge,",
  "        MonoDump.maybe(id, label, raster, pxIn, out.mask, underIn, plateLayer, edge, edge,",
  "抓取带回搬过的那两张");

cut(S, "MonochromeSynth.kt",
  "      // 甲：判据算完**之后**才搬位置",
  "      val alpha = toAlphaMask(moved, edge, edge)",
  `      // 掩码这一步**不再搬位置**：输入已经是裁圆调好的那一张，判出来的墨天然就在那个尺寸上。
      // （这里原来挂着甲那一刀 —— 判完再 resample 交付掩码。2026-10-15 撤，理由见上面那段。）
      val alpha = toAlphaMask(ramp, edge, edge)`,
  "撤掉输出侧那一刀");
save("MonochromeSynth.kt", S.src);
console.log("写回完成，共 " + cuts + " 处");
