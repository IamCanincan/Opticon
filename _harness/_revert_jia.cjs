// 一次性手术：把染色那一路从「乙（搬输入）」退回「甲（搬成品掩码）」，并去掉剪影归一。
// 为什么用脚本而不是多次 sed：多段 hunk 的 sed 在这个仓库翻过车。跑完必须 git diff 逐行看。
const fs = require("fs");
const P = "D:/Github Repositories/Opticon/app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeSynth.kt";
let src = fs.readFileSync(P, "utf8");
let cuts = 0;
function rep(a, b, what) {
  if (!src.includes(a)) throw new Error("找不到：" + what);
  src = src.split(a).join(b); cuts++;
  console.log("  ok " + what);
}
function cut(startMarker, endMarker, replacement, what) {
  const i = src.indexOf(startMarker);
  if (i < 0) throw new Error("找不到起点：" + what);
  const j = src.indexOf(endMarker, i);
  if (j < 0) throw new Error("找不到终点：" + what);
  src = src.slice(0, i) + replacement + src.slice(j); cuts++;
  console.log("  ok " + what + "（删 " + (j - i) + " 字符）");
}

rep("MonoDump.sigOf(pxIn, edge, edge)", "MonoDump.sigOf(pixels, edge, edge)", "指纹回到原样像素");
rep("MonoDump.maybe(id, label, raster, pxIn, out.mask, underIn, plateLayer",
  "MonoDump.maybe(id, label, raster, pixels, out.mask, underPx, plateLayer", "抓取回到原样像素");

cut("      // 掩码这一步**不再搬位置**", "      noteDiagnosis(edge, label, raster, placed)",
  `      // 甲：判据算完**之后**才搬位置 —— 浓度、分档、盘刀、极性一个像素都不动，只把算好的墨
      // 摆到裁圆定的那个尺寸上。目标矩形**没传**（= 整张图层画布）：这正是主人对着判"没问题"
      // 的那一版，改它等于改 12 张已验收的图标，不在这一刀的范围里（见 GEO_ZOOM_CAP 那段）。
      val alpha = toAlphaMask(if (win == null) ramp else IconCropWindow.reframe(ramp, edge, edge, win), edge, edge)
      // 非自适应图标的内容是满幅的，而它在图层 bounds 里只该占中间那 2/3（可见区），
      // 所以掩码算完**之后**才缩进 —— 顺序反了就会踩 [rasterize] 上面那个坑。
      val placed = if (adaptive) alpha else intoVisibleArea(alpha, edge)
`, "掩码出口回到甲");

cut("  /**\n   * 裁圆那个窗口 —— **量在彩色那支量的同一个对象上**", "  /**\n   * 把 [target] 光栅化成", "", "删掉 geometryWindow（量整图标那一路不用了）");
cut("  /**\n   * 交付掩码的**剪影归一**", "  private fun intoVisibleArea(source: Bitmap, edge: Int): Bitmap {", "", "删掉 normalizeSilhouette");
cut("  /** 「几何量不到」每进程最多打几条", "  /** 「剪影归一」每进程放行几条日志", "", "删掉量不到的计数");
cut("  /** 「剪影归一」每进程放行几条日志", "\n  /** ASCII 缩略图的行列数", "\n", "删掉归一日志计数");

fs.writeFileSync(P, src);
console.log("写回完成，共 " + cuts + " 处");
