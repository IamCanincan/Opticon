// 路线 B：把"抬顶"这一层也交给满幅那一支，并修掉它自己那道**误否决**。
//
// 病灶（量出来的，不是猜的）：`stretchMark` 抬之前/抬之后各算一次 `minGap`，只要变小就整批回滚。
// 而 `分档` 顶端天生有一对 254/255 —— 差 1。它**早就**低于性质台要求的 25，不是一处需要保护的层次，
//  可它让每一次抬顶都"变小"（254、255 抬完都夹到 255，差 0）→ 于是 7 张该抬的一张都没抬。
// 修法：`minGap` 只数**还在门槛之上**的那些间隔（<25 的那两档本来就算并好了）。
// 这条不是新阈值：25 就是性质台「四块两两至少差 25」用的同一个数，从那里引用，不另立。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/Lift2.kt";
let t = fs.readFileSync(src, "utf8");

// 1) 抬顶不再区分两支
const A = '    val stretch = "${global?.second ?: if (tonal == null) "已满" else "跳过"}+" +\n' +
          '      (if (tonal == null) stretchMark(out, pixels, w, h, box, s) else "跳过")';
if (!t.includes(A)) { console.log("找不到抬顶那一行"); process.exit(1); }
const A2 = '    val stretch = "${global?.second ?: if (tonal == null) "已满" else "跳过"}+" + stretchMark(out, pixels, w, h, box, s)';
t = t.split(A).join(A2);

// 2) minGap 忽略已经并档的那些间隔
const B = "  private fun minGap(means: IntArray): Int {\n" +
          "    if (means.size < 2) return 255\n" +
          "    var g = 255\n" +
          "    for (i in 1 until means.size) g = minOf(g, means[i] - means[i - 1])\n" +
          "    return g\n" +
          "  }";
if (!t.includes(B)) { console.log("找不到 minGap"); process.exit(1); }
const B2 = "  private fun minGap(means: IntArray): Int {\n" +
           "    if (means.size < 2) return 255\n" +
           "    var g = 255\n" +
           "    for (i in 1 until means.size) {\n" +
           "      val d = means[i] - means[i - 1]\n" +
           "      if (d < SEPARATION_FLOOR) continue      // 这一对**早就**并成一档了，不是要保护的层次\n" +
           "      g = minOf(g, d)\n" +
           "    }\n" +
           "    return g\n" +
           "  }";
t = t.split(B).join(B2);

const C = "  private const val PLATE_FILM_MIN = 3000";
if (!t.includes(C)) { console.log("找不到常量锚点"); process.exit(1); }
t = t.split(C).join("  /** 相邻两档\"还算分得开\"的地板 = 性质台那条「四块两两至少差 25」同一个数。\n" +
  "   *  低于它的间隔在 `minGap` 眼里**已经是同一档**，不该再拿它去否决一次抬顶。 */\n" +
  "  private const val SEPARATION_FLOOR = 25\n" +
  "  private const val PLATE_FILM_MIN = 3000");
fs.writeFileSync(dst, t);
console.log("已写出 cand/Lift2.kt");
