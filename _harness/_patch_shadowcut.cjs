// 把「投影那一补」写进候选 cand/ShadowCut.kt。写成文件而不是 `node -e`：
// 中文注释里的引号在命令行那一层会被咬断（这条本仓记过好几次）。
const fs = require('fs');
const p = 'cand/ShadowCut.kt';
let s = fs.readFileSync(p, 'utf8');
const nl = s.includes('\r\n') ? '\r\n' : '\n';
const miss = [];

const oldA = '          val isPlate = Color.alpha(under[i]) >= A_SOLID && plateKey(pixels[i]) == top.key';
const newA = '          val isPlate = Color.alpha(under[i]) >= A_SOLID &&' + nl +
  '            (plateKey(pixels[i]) == top.key || (shadow[i] && plateKey(pixels[i]) == plateKey(under[i])))';
if (s.includes(oldA)) s = s.replace(oldA, newA); else miss.push('切判据行');

const oldB = '        plateCand = 0' + nl + '        val kept = IntArray(total)';
const addB = '        plateCand = 0' + nl +
  '        // 「投影也画在那块盘上」那一补：背景层里**不是主色**的格，若合计 ≤[SHADOW_MAX_PCT]% 画布、' + nl +
  '        // 且每一块都**薄**（面积/边界 ≈ 半宽 ≤ 边长的 [SHADOW_MAX_HALF]），那是物体投在盘上的影子，' + nl +
  '        // 不是画在背景层里的图形 —— 一起当盘切掉。' + nl +
  '        // ⚠ 两个数都是动手前摊过分布才画的（`_harness/BandThickKt`，57 张带背景层的图）：' + nl +
  '        //   translate 2.40% / 半宽 2.5px，下一个是 contacts 6.37% / 7.1px —— 面积上 2.6 倍空档。' + nl +
  '        //   半宽按**边长比例**表达（绝对格数 = 尺寸依赖，这条烧过一次）。' + nl +
  '        val shadow = BooleanArray(total)' + nl +
  '        var nonPlate = 0' + nl +
  '        for (i in 0 until total)' + nl +
  '          if (Color.alpha(under[i]) >= A_SOLID && plateKey(under[i]) != top.key) nonPlate++' + nl +
  '        if (nonPlate in 1..(total * SHADOW_MAX_PCT / 100)) {' + nl +
  '          val seen = BooleanArray(total); val stack = IntArray(total); var thinAll = true' + nl +
  '          for (i in 0 until total) {' + nl +
  '            if (seen[i] || Color.alpha(under[i]) < A_SOLID || plateKey(under[i]) == top.key) continue' + nl +
  '            var sp = 0; var area = 0; var boundary = 0' + nl +
  '            stack[sp++] = i; seen[i] = true' + nl +
  '            while (sp > 0) {' + nl +
  '              val c = stack[--sp]; area++' + nl +
  '              val cx = c % w; val cy = c / w' + nl +
  '              for (k in 0..3) {' + nl +
  '                val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]' + nl +
  '                if (nx < 0 || ny < 0 || nx >= w || ny >= h) { boundary++; continue }' + nl +
  '                val j = ny * w + nx' + nl +
  '                if (Color.alpha(under[j]) < A_SOLID || plateKey(under[j]) == top.key) { boundary++; continue }' + nl +
  '                if (!seen[j]) { seen[j] = true; stack[sp++] = j }' + nl +
  '              }' + nl +
  '            }' + nl +
  '            if (area >= SHADOW_MIN_AREA &&' + nl +
  '              area / 2.0 / boundary.coerceAtLeast(1) > w * SHADOW_MAX_HALF) thinAll = false' + nl +
  '          }' + nl +
  '          if (thinAll) for (i in 0 until total)' + nl +
  '            if (Color.alpha(under[i]) >= A_SOLID && plateKey(under[i]) != top.key) shadow[i] = true' + nl +
  '        }' + nl +
  '        val kept = IntArray(total)';
if (s.includes(oldB)) s = s.replace(oldB, addB); else miss.push('插入锚点');

const ca = '  private const val PLATE_ALT_MIN = 85';
const cc = ca + nl +
  '  /** 背景层里非主色的格合计最多占画布这么多%，才可能被判成「一条投影」而不是「画在背景层的图形」。 */' + nl +
  '  private const val SHADOW_MAX_PCT = 4' + nl +
  '  /** 小于这么多格的块不参与薄度判定（碎屑本来就薄，判它没意义）。 */' + nl +
  '  private const val SHADOW_MIN_AREA = 400' + nl +
  '  /** 每块的估算半宽（面积/边界 ≈ 半宽）最多到边长的这个比例。 */' + nl +
  '  private const val SHADOW_MAX_HALF = 0.01';
if (s.includes(ca)) s = s.replace(ca, cc); else miss.push('常数');

// 报告列不加：`shadow` 那个数组活在盘刀那个 if 块里，报告串在块外取不到它（加了就是编译错）。
// 影切的格数本来就并进 `盘刀=` 那一列里 —— 要看这一刀有没有开火，比 `盘刀=` 的数就够了。
fs.writeFileSync(p, s);
console.log(miss.length ? '!! 没找到锚点：' + miss.join(' ') : '三样都写进去了（报告列故意不加）');
