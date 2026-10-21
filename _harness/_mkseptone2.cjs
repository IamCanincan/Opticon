// SepTone v2：**受约束**地只挪一块 —— 找不到合法位置就整张不动（v1 是"最小距离最大"= 全图重排，
// 量出来青带 135→83 但气泡 110→146、黑字 199→160、熊身 166→223，把一张图重排了）。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
let s = fs.readFileSync(src, "utf8");
const anchor = "    val shift = HashMap<Int, Int>(pick.size)";
if (!s.includes(anchor)) { console.error("!! 锚点不在"); process.exit(1); }
const block = `    // 【候选 SepTone v2】两块**平色、颜色离得远、都落在中间带**却拿到几乎同一浓度 → 把小的那块挪开。
    //   来源：主人 2026-10-13 报「青带和熊头粘在一起」。病不在"边上没台阶"，在**两个分离的物体被分到同一浓度**。
    //   判据量过（62 张真机语料）：不带"颜色离得远"命中 50/60 张（xbox 250~250 那种本来就该实心）；
    //   加上它只命中 3 张 5 对 —— 易班 135~166 / 166~199、sgame 166~196、bili 179~204 / 192~204。
    //   ⚠ **v1 栽在哪**：写成"挪到与其它块最小距离最大的位置"——那是**全图重排**，易班当场变成 83/146/160/223/251/255，
    //   气泡与黑字都被拖着走。这一版把它约束住：**只挪一块、只挪到合法的位置、没有合法位置就整张不动**。
    //   合法 = 与伙伴 ≥[SEP_TONE_SEP]、与其它每一块都 ≥[SEP_MIN_GAP]（就是那条钉着的"相邻部分必须有差别"）。
    run {
      val n2 = pick.size
      val tone = IntArray(n2) { target[it] }
      val area = IntArray(n2) { cnt[pick[it]] }
      fun labD(i: Int, j: Int) = deltaE(colL[i], colA[i], colB[i], colL[j], colA[j], colB[j]) shr 6
      var moved = 0
      for (i in 0 until n2) for (j in i + 1 until n2) {
        val ti = tone[i]; val tj = tone[j]
        if (Math.abs(ti - tj) > SEP_TONE_COLLIDE) continue
        if (ti < SEP_TONE_MID_LO || ti > SEP_TONE_MID_HI) continue
        if (tj < SEP_TONE_MID_LO || tj > SEP_TONE_MID_HI) continue
        if (labD(i, j) < SEP_TONE_FAR) continue
        val k = if (area[i] <= area[j]) i else j          // 挪小的那一块（主体别动）
        val other = if (k == i) j else i
        var best = -1; var bestD = Int.MAX_VALUE
        for (v in SEP_TONE_MIN..255) {
          if (Math.abs(v - tone[other]) < SEP_TONE_SEP) continue
          var ok = true
          for (t in 0 until n2) if (t != k && Math.abs(v - tone[t]) < SEP_MIN_GAP) { ok = false; break }
          if (!ok) continue
          val d = Math.abs(v - tone[k])                    // 取**改动最小**的那个合法位置
          if (d < bestD) { bestD = d; best = v }
        }
        if (best >= 0) { tone[k] = best; moved++ }
      }
      if (moved > 0) for (idx in 0 until n2) target[idx] = tone[idx]
    }
`;
s = s.replace(anchor, block + anchor);
const consts = `  private const val SEP_SINK_DEP = 224`;
s = s.replace(consts, consts + `
  /** 【候选 SepTone】撞浓度判据：差 ≤40、都在中间带（两头本来就该实心）、源色 Lab 距离 ≥60
   *  （低于它的是同一条坡的切片 —— 2026-10-12 明令不许动的那一类）。 */
  private const val SEP_TONE_COLLIDE = 40
  private const val SEP_TONE_MID_LO = 100
  private const val SEP_TONE_MID_HI = 210
  private const val SEP_TONE_FAR = 60
  /** 挪开之后要与伙伴隔多远，以及能挪到的最低值。 */
  private const val SEP_TONE_SEP = 55
  private const val SEP_TONE_MIN = 60`);
fs.writeFileSync("cand/SepTone2.kt", s);
console.log("写出 cand/SepTone2.kt");
