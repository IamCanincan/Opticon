const fs = require("fs");
const p = "cand/Evid.kt";
let t = fs.readFileSync(p, "utf8");
const A = [
"    val hp = IntArray(8); val hd = IntArray(8)",
"    if (tonal != null) for (i in 0 until total) {",
"      if (Color.alpha(pixels[i]) < A_SOLID) continue",
"      val a0 = out[i] ushr 24",
"      if (a0 == 0) continue",
"      if (s.plate[i] != 0.toByte()) hp[(a0 / 32).coerceAtMost(7)]++",
"      else if ((s.distT[i].toInt() and 0xffff) <= ground.zero) hd[(a0 / 32).coerceAtMost(7)]++",
"    }"].join("\n");
if (!t.includes(A)) { console.log("找不到直方图那一段"); process.exit(1); }
const B = [
"    val hp = IntArray(8); val hd = IntArray(8)",
"    // ⚠ **贴着强墨两格以内的不算证据**（2026-10-11）：dep 堆里那些「暗部尾巴」其实是**字边缘的混色格**",
"    //  —— 底与字混出来的颜色离底很近（于是被 dep 收进证据堆），本身又比底暗（于是撑出一条假尾巴）。",
"    //  这一堆的占比按 **1/边长** 缩：同一张易班，96 档 dep 尾巴 42%、192 档 37%、384 档只有 2% ——",
"    //  「两堆一起过关」因此随尺寸翻（这也是「按成品选支」那条路的死因）。剔掉贴墨的两格，",
"    //  证据问的才是**这一片离字远的地方，有没有暗的东西压着**。",
"    fun nearInk(i: Int): Boolean {",
"      val cy = i / w; val cx = i - cy * w",
"      for (dy in -2..2) { val ny = cy + dy; if (ny < 0 || ny >= h) continue",
"        for (dx in -2..2) { val nx = cx + dx; if (nx < 0 || nx >= w) continue",
"          if (((out[ny * w + nx] ushr 24) and 0xff) >= 180) return true } }",
"      return false",
"    }",
"    if (tonal != null) for (i in 0 until total) {",
"      if (Color.alpha(pixels[i]) < A_SOLID) continue",
"      val a0 = out[i] ushr 24",
"      if (a0 == 0) continue",
"      if (s.plate[i] != 0.toByte()) hp[(a0 / 32).coerceAtMost(7)]++",
"      else if ((s.distT[i].toInt() and 0xffff) <= ground.zero) {",
"        if (nearInk(i)) continue",
"        hd[(a0 / 32).coerceAtMost(7)]++",
"      }",
"    }"].join("\n");
fs.writeFileSync(p, t.replace(A, B));
console.log("已改 cand/Evid.kt");
