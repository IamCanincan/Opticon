const fs = require("fs");
const p = "cand/Evid2.kt";
let t = fs.readFileSync(p, "utf8");
const A = [
"      if (s.plate[i] != 0.toByte()) hp[(a0 / 32).coerceAtMost(7)]++",
"      else if ((s.distT[i].toInt() and 0xffff) <= ground.zero) {",
"        if (nearInk(i)) continue",
"        hd[(a0 / 32).coerceAtMost(7)]++",
"      }"].join("\n");
if (!t.includes(A)) { console.log("找不到直方图分支"); process.exit(1); }
const B = [
"      if (s.plate[i] != 0.toByte()) { hp[(a0 / 32).coerceAtMost(7)]++; hpRaw++ }",
"      else if ((s.distT[i].toInt() and 0xffff) <= ground.zero) {",
"        hdRaw++;",
"        if (nearInk(i)) continue",
"        hd[(a0 / 32).coerceAtMost(7)]++",
"      }"].join("\n");
t = t.replace(A, B);
t = t.replace("    val hp = IntArray(8); val hd = IntArray(8)",
              "    val hp = IntArray(8); val hd = IntArray(8)\n    var hpRaw = 0; var hdRaw = 0");
const C = "    val noDark = (pN < PLATE_FILM_EVID || pDeep in 0..PLATE_FILM_DEEP) &&\n      (dN < PLATE_FILM_EVID || dDeep in 0..PLATE_FILM_DEEP)";
if (!t.includes(C)) { console.log("找不到 noDark"); process.exit(1); }
t = t.replace(C, [
"    // ⚠ **样本量按剔之前数，尾巴率按剔之后数**。上一版（`cand/Evid.kt`）让剔格把样本也缩小了，",
"    //  于是 384 档驾考剔完掉到地板以下、反证被当\"不存在\"→ 整刀不开（少清 703 格）。",
"    //  这两件事本来就是两个问题：\"这堆够不够大、尾巴率可信吗\"看**原始样本**；",
"    //  \"这一片离字远的地方有没有暗的东西\"看**剔掉边缘之后的尾巴率**。",
"    val noDark = (hpRaw < PLATE_FILM_EVID || pDeep in 0..PLATE_FILM_DEEP) &&\n" +
"      (hdRaw < PLATE_FILM_EVID || dDeep in 0..PLATE_FILM_DEEP)"].join("\n"));
fs.writeFileSync(p, t);
console.log("已改 cand/Evid2.kt");
