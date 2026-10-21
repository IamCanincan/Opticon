// 临时（2026-10-16）：Edit 工具对 MonochromeMask.kt 写不进去（mtime 不动、git 里没 diff），
// 所以这一刀用脚本落地。只做一处功能改动：把 airN/airShare/groundIsAir 那一段从「takeIf 之后」
// 搬到「takeIf 之前」，并给 takeIf 加上「坐在空气上的那一层不进这一支」这条门。
// 门里那条 `ground.gN > 0` 不是新立的尺 —— 与 [tonalBright] 用的是同一个 `ground`、同一条
// 「底真的存在」前提（azure `盘实=0/24178` 就是 gN=0 那一类）。
const fs = require("fs");
const P = "d:\\Github Repositories\\Opticon\\app\\src\\main\\kotlin\\com\\iamcanincan\\opticon\\circle\\MonochromeMask.kt";
const raw = fs.readFileSync(P);
const hasBom = raw.slice(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]));
let s = (hasBom ? raw.slice(3) : raw).toString("utf8");

const TONAL_LINE = "    val tonal = if (forceRef) null else tonalClass(pixels, w, h, box)?.takeIf { it.on }\n";
const OLD_BLOCK = [
  "    var airN = 0",
  "    if (tonal != null) for (y in box.top until box.bottom) for (x in box.left until box.right)",
  "      if (Color.alpha(pixels[y * w + x]) < A_SOLID) airN++",
  "    val airShare = airN * 100 / maxOf(box.width() * box.height(), 1)",
  "    val groundIsAir = airShare >= TONAL_AIR_MIN",
  "",
].join("\n");
const NEW_BLOCK = [
  "    // ⚠ 坐在空气上的那一类不进这一支（2026-10-16，Kodi「整个都上墨了」）：这一支的前提是",
  "    //   「满幅、没有参照可谈」，而「卡片/标记坐在透明空气上」这一类**有参照** —— [groundPass]",
  "    //   认得那块底。它进来纯粹是被**底 vs 墨的亮度差**顶过线的（那跨度量的是对比，不是一张",
  "    //   插画自己的调子）；进来之后这一支没有任何归零底的一步（发档 / 接带 / 两层抬顶全让路），",
  "    //   那块底就按自己的亮度拿到一份**半浓的墨** —— 屏上就是「整个图标都上墨了」。",
  "    //   Kodi 实测：`跨=41` 过线、`盘实=30523/36101`（底认出来了，`H=2`、`底占=0.72`）、",
  "    //   `框内 0.28 / 淡 0.23`；而参照那一支只交出那块蓝标记。",
  "    //   ⚠ 四道前提缺一不可：① 内容框里坐着空气（`groundIsAir`，= 不是满幅画）；",
  "    //   ② `ground.gN > 0` —— **参照真的认出了一块底**（不是空气当底；azure `盘实=0/24178`",
  "    //   正是这一类，它必须留在这一支）；③ `!plateIsLayer && under == null` —— **这一张只喂了",
  "    //   一层**（甲类与有背景层的那批本来就该坐在空气上，它们的盘由另一层 / [PLATE_FLOOR_MIN]",
  "    //    / `盘刀` 负责）。②那条不是新尺：与 [tonalBright] 同一条「底真的存在」前提。",
  "    var airN = 0",
  "    for (y in box.top until box.bottom) for (x in box.left until box.right)",
  "      if (Color.alpha(pixels[y * w + x]) < A_SOLID) airN++",
  "    val airShare = airN * 100 / maxOf(box.width() * box.height(), 1)",
  "    val groundIsAir = airShare >= TONAL_AIR_MIN",
  "    val tonal = if (forceRef) null else tonalClass(pixels, w, h, box)",
  "      ?.takeIf { it.on && !(groundIsAir && ground.gN > 0 && !plateIsLayer && under == null) }",
  "",
].join("\n");

const hitTonal = s.split(TONAL_LINE).length - 1;
const hitBlock = s.split(OLD_BLOCK).length - 1;
console.log("命中：tonal 行", hitTonal, "／ air 段", hitBlock);
if (hitTonal !== 1 || hitBlock !== 1) { console.log("!! 命中数不对，**什么都没写**"); process.exit(1); }
s = s.replace(TONAL_LINE, "");        // 原来那行跟着 air 段一起搬了位置
s = s.replace(OLD_BLOCK, NEW_BLOCK);
fs.writeFileSync(P, Buffer.concat([hasBom ? Buffer.from([0xef, 0xbb, 0xbf]) : Buffer.alloc(0), Buffer.from(s, "utf8")]));
console.log("已写入；takeIf 命中 =", s.split("takeIf { it.on &&").length - 1, "；ground.gN 命中 =", s.split("ground.gN > 0").length - 1);
