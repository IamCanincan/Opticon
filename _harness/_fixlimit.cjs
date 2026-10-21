// 把 KNOWN-LIMITS 里 wenku8 那条从"已知不修"改成"已修（用裁圆的档位）"。
const fs = require("fs");
const P = "D:/Github Repositories/Opticon/docs/KNOWN-LIMITS.md";
const L = fs.readFileSync(P, "utf8").split("\n");
const i = L.findIndex((x) => x.includes("文庫吧那张"));
if (i < 0) { console.log("没找到那条"); process.exit(1); }
L[i] = "- **文庫吧那张（`org.mewx.wenku8`）的极性：三条像素尺都分不开，最后用**裁圆自己的档位**修掉了**" +
  "（2026-10-15 主人点名「应该蓝色部分上色」→「就是整本书成墨、书签是镂空，继续吧」）。";
const j = L.findIndex((x, k) => k > i && x.includes("结论：这不是阈值没调好"));
if (j < 0) { console.log("没找到结论那行"); process.exit(1); }
L[j] = "  结论：这不是阈值没调好 —— 这块大色块是主体还是承载面，**不在像素里**。真正的证据在模块另一支自己的判决里：";
L[j + 1] = "  彩色那支对这张判的是「裁不动、整幅缩进圆里、裁了只剩 **55%**」= 这块蓝是**要被整块保住的图案**，" +
  "那染色就没资格把它当底摘掉。一条 `plateIsSubject`（`PLATE_SUBJECT_MIN = 50`）把档位传给极性判据，" +
  "wenku8 `底占 0.92→0.00`、`框内 0.07→0.93`，语料 83 张里**只有它一张开火**。";
fs.writeFileSync(P, L.join("\n"));
console.log("已改两条");
