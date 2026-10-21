// 一次性手术（补上一刀）：`plateIsSubject` 不能只否 H1 —— 真机上 H2 会以 `底占=0.86` 接手，
// 屏上一点没变（2026-10-15 03:31，pid 4375：`拦=128 H=2 底占=0.86 框内=0.07`）。
// 裁圆说"这块大色块要整块保住"，意思就是**这张图里没有底**：直接退到 H3，
// 不再让 H1 / H2 / 薄底接手 / H0 四路依次把它捡回来。
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

// ① 把上一版"只否 H1"改回原样
rep(
  `      // ⚠ **极性跟着裁圆的档位走**（2026-10-15 主人定，为 \`org.mewx.wenku8\` 那本蓝书）：
      //   彩色那支判这张是"裁不动、整幅缩进圆里、还剩 55%" —— 也就是说**这块大色块是要被整块
      //   保住的图案**。那染色就没有资格把它当"承载图形的板"摘掉：同一个模块里两套主张，
      //   屏幕上就是主人那句「应该蓝色部分上色」（当时只剩一条书签当墨，\`框内=0.07\`）。
      //   退掉 H1 之后的效果量过（今晚 \`周空\` 那一版顺手否掉 H1 时看到的）：wenku8
      //   \`底占=0.00 H=3 框内=0.93\` —— 整本书成墨、书签是镂空。
      //   线摆在哪、为什么只有这一张过线，见 [MonochromeSynth.PLATE_SUBJECT_MIN]。
      if (plateIsSubject) s.veto = s.veto or 128
      else if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p`,
  "      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p",
  "H1 那道改回原样");

// ② 改成整条"没有底"：在四个候选之前直接退到 H3
rep(
  "    val fams = cornerFamilies(pixels, w, box, band, s)",
  `    if (plateIsSubject) {
      // **极性跟着裁圆的档位走**（2026-10-15 主人定，为 \`org.mewx.wenku8\` 那本蓝书）：
      // 彩色那支判这张是"裁不动、整幅缩进圆里、裁了只剩 55%" —— 这块大色块是**要被整块保住的
      // 图案**，那染色就没有资格把它当"承载图形的板"摘掉。同一个模块对同一张图给两个主张，
      // 屏幕上就是「应该蓝色部分上色」（当时只剩一条书签当墨，\`框内=0.07\`）。
      // ⚠ 必须在这里就退干净，不能只否 H1：真机上 H2 会以 \`底占=0.86\` 把同一块蓝再当一次底
      //   （2026-10-15 03:31 pid 4375：\`拦=128 H=2 底占=0.86 框内=0.07\` —— 屏上一点没变），
      //   后面还有"薄底接手"与 H0 两条路会接手。裁圆那句话的意思就是**这张图里没有底**，
      //   参照只剩空气：直接交 H3，四个候选一个都不问。线摆在哪见 [MonochromeSynth.PLATE_SUBJECT_MIN]。
      bandSeed(s, w, box, band, seed); s.veto = s.veto or 128
      return fitGround(pixels, w, h, box, g, s, 0, seed).apply { hyp = 3 }
    }
    val fams = cornerFamilies(pixels, w, box, band, s)`,
  `整条退成「没有底」`);

fs.writeFileSync(P, src);
console.log("写回完成，共 " + cuts + " 处");
