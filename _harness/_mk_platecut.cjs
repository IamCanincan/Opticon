const fs = require("fs");
const p = "cand/MonochromeMask.plateCut.kt";
let s = fs.readFileSync(p, "utf8");
let bad = 0;
const rep = (a, b, t) => { if (!s.includes(a)) { console.log("MISS " + t); bad++; return; } s = s.split(a).join(b); console.log("ok   " + t); };

rep("    val sep = separation(out, w, h, box, s)",
  "    // 满幅那一支把「图标自己的底色」也当成一个调子转录了 —— cainiao 那块蓝方底 L*≈50\n" +
  "    // 被映射成 α≈126（分档=3/4(0,126,237,254) 里那个 126 就是它），屏幕上是一层半透明灰，\n" +
  "    // 和已适配图标那种干净的盘对不上。这里把参照**已经判成底**的那些格直接归零。\n" +
  "    // ⚠ 只在这块底**一直顶到画布边缘**时才这么做 —— 那是「整张画布就是一张盘」的形状证据。\n" +
  "    //   azure 的「底」是盾牌、四周是空气、不顶边：刚修好的锁梁不会被这一刀带倒。\n" +
  "    if (tonal != null && plateTouchesBorder(pixels, w, h, s))\n" +
  "      for (i in 0 until total) if (s.plate[i] != 0.toByte()) out[i] = 0\n" +
  "    val sep = separation(out, w, h, box, s)", "cut");

rep("  private fun thinInk(plate: ByteArray, w: Int, box: Rect, x: Int, y: Int): Boolean {",
  "  /** 参照判出来的底有没有一直顶到画布四条边（= 整张画布就是一张盘）。 */\n" +
  "  private fun plateTouchesBorder(pixels: IntArray, w: Int, h: Int, s: Scratch): Boolean {\n" +
  "    fun on(i: Int) = s.plate[i] != 0.toByte() && Color.alpha(pixels[i]) >= A_SOLID\n" +
  "    for (x in 0 until w) if (on(x) || on((h - 1) * w + x)) return true\n" +
  "    for (y in 0 until h) if (on(y * w) || on(y * w + w - 1)) return true\n" +
  "    return false\n" +
  "  }\n\n" +
  "  private fun thinInk(plate: ByteArray, w: Int, box: Rect, x: Int, y: Int): Boolean {", "helper");

fs.writeFileSync(p, s);
console.log(bad ? "有锚点没命中" : "写好了");
