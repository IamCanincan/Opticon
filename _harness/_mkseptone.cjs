// 把 SepTone 候选打进一份 MonochromeMask 副本里。用文件不用 `node -e`（今天被引号咬了两次）。
const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/SepTone.kt";
let s = fs.readFileSync(src, "utf8");
const anchor = "    val shift = HashMap<Int, Int>(pick.size)";
if (!s.includes(anchor)) { console.error("!! 找不到 shift 那一行的锚点"); process.exit(1); }
const block = `    // 【候选 SepTone】两块**平色、颜色离得远、都落在中间带**却拿到几乎同一浓度 → 把小的那块挪开。
    //   来源：主人 2026-10-13 报「青带和熊头粘在一起」。逐像素看完才发现病不在"边上没台阶"，
    //   而在**两个分离的物体被分到同一浓度**（交付 135 与 166，差 12%，肉眼当同一片）。
    //   判据量过（62 张真机语料）：不带"颜色离得远"命中 **50/60 张**（xbox 250~250 那种两块本来就该实心）；
    //   加上它之后只命中 **3 张 5 对** —— 易班 135~166 / 166~199、sgame 166~196、bili 179~204 / 192~204。
    //   ⚠ **易班排不下**：六块（110/135/166/199/225/255）要两两 ≥55 需要 330 的量程，而 60~255 只有 195。
    //   所以这一版是"尽力而为"：把小的那块挪到"与其它所有块的最小距离最大"的那个位置。
    //   （这是**口味**决定：它选择牺牲"带子 vs 白气泡"这一对，保住熊脸五官与蝴蝶结 —— 主人 10-13 指的方向。）
    run {
      val n2 = pick.size
      val tone = IntArray(n2) { target[it] }
      val area = IntArray(n2) { cnt[pick[it]] }
      fun labD(i: Int, j: Int) = deltaE(colL[i], colA[i], colB[i], colL[j], colA[j], colB[j]) shr 6
      repeat(2) {
        for (i in 0 until n2) for (j in i + 1 until n2) {
          val ti = tone[i]; val tj = tone[j]
          if (Math.abs(ti - tj) > SEP_TONE_COLLIDE) continue
          if (ti < SEP_TONE_MID_LO || ti > SEP_TONE_MID_HI) continue
          if (tj < SEP_TONE_MID_LO || tj > SEP_TONE_MID_HI) continue
          if (labD(i, j) < SEP_TONE_FAR) continue
          val k = if (area[i] <= area[j]) i else j
          var bestV = tone[k]; var bestMin = -1
          for (v in SEP_TONE_MIN..255) {
            var m = Int.MAX_VALUE
            for (t in 0 until n2) if (t != k) m = minOf(m, Math.abs(v - tone[t]))
            if (m > bestMin) { bestMin = m; bestV = v }
          }
          tone[k] = bestV
        }
      }
      for (idx in 0 until n2) target[idx] = tone[idx]
    }
`;
s = s.replace(anchor, block + anchor);
const consts = `  private const val SEP_SINK_DEP = 224`;
s = s.replace(consts, consts + `
  /** 【候选 SepTone】两块撞浓度的判据：差 ≤40、都落在中间带（两头本来就该实心，不该动）、
   *  源色 Lab 距离 ≥60（低于它的是同一条坡的切片 —— 那是 2026-10-12 明令不许动的那一类）。 */
  private const val SEP_TONE_COLLIDE = 40
  private const val SEP_TONE_MID_LO = 100
  private const val SEP_TONE_MID_HI = 210
  private const val SEP_TONE_FAR = 60
  /** 挪出去时的下界。比 [LEVEL_FLOOR_MIN] 更低：那一段是留给"层层让开"的，这里是"必须让开"。 */
  private const val SEP_TONE_MIN = 60`);
fs.writeFileSync(dst, s);
const n1 = (s.match(/SEP_TONE_COLLIDE/g) || []).length;
console.log("写出 cand/SepTone.kt，SEP_TONE_COLLIDE 出现 " + n1 + " 次（应为 2：常量声明 + 使用）");
