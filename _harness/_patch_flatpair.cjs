// 把「对档」那一刀需要的四样补丁写进候选（helpers / 常数 / 报告列 / 探子口）。
// 为什么写成文件而不是 `node -e`：这仓的注释里全是中文引号与反斜杠，命令行那一层
// 的转义会把脚本咬断（今天已经咬过两次）。
const fs = require('fs');
const p = 'cand/FlatPair.kt';
let s = fs.readFileSync(p, 'utf8');
const nl = s.includes('\r\n') ? '\r\n' : '\n';
let miss = [];

const helpers =
  '  /** 源像素明度（Rec.709 整数）。与判据台 `TerrKt` 同一式子。 */' + nl +
  '  private fun lumaOf(p: Int): Int =' + nl +
  '    ((0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff))).toInt()' + nl + nl +
  '  /** 两色的 RGB 平方距离。用 RGB 距离而不是 ΔL：这一刀要认的正是「同明度不同色」的那两块。 */' + nl +
  '  private fun rgbDist2(a: Int, b: Int): Long {' + nl +
  '    val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)' + nl +
  '    val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)' + nl +
  '    val db = (a and 0xff) - (b and 0xff)' + nl +
  '    return (dr * dr + dg * dg + db * db).toLong()' + nl + '  }' + nl + nl;
const a1 = '  private fun plateKey(p: Int): Int =';
if (s.includes(a1)) s = s.replace(a1, helpers + a1); else miss.push('helpers');

const consts =
  '  /** 对档那一刀：一块源色族至少占框这么多%，才配当「一个部件」。 */' + nl +
  '  private const val PAIR_AREA_MIN = 3' + nl +
  '  /** 族内要有这么多% 的格落在本族平均明度 ±PAIR_FLAT_DEV 里，才算这块自己「是平色」。 */' + nl +
  '  private const val PAIR_FLAT_PCT = 90' + nl +
  '  private const val PAIR_FLAT_DEV = 12' + nl +
  '  /** 两块之间源 RGB 距离均值至少这么大，才承认那里源里真有一条色边。 */' + nl +
  '  private const val PAIR_RGB_MIN = 90' + nl +
  '  /** 较弱那一块降多少档、地板在哪、整张最多降框的多少%（主体永远不许被「造」成配角）。 */' + nl +
  '  private const val PAIR_STEP = 50' + nl +
  '  private const val PAIR_MIN = 128' + nl +
  '  private const val PAIR_CAP = 40';
const a2 = '  private const val PLATE_ALT_MIN = 85';
if (s.includes(a2)) s = s.replace(a2, a2 + nl + consts); else miss.push('const');

const a3 = '" 盘刀=$plateCut 盘候=$plateCand 盘层=${if (under == null) 0 else 1}"';
if (s.includes(a3)) s = s.replace(a3, a3 + ' +' + nl + '          " 对档=$pairGroups/$pairCells"'); else miss.push('report');

const a4 = '    val ref = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl +
  '    return if (picksRef(w, h, ramp, ref)) ref else ramp' + nl + '  }';
if (s.includes(a4)) s = s.replace(a4, a4 + nl + nl +
  '  internal fun probeRamp(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, false, under)' + nl + nl +
  '  internal fun probeRef(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl + nl +
  '  internal fun probePick(w: Int, h: Int, ramp: Output, ref: Output) = picksRef(w, h, ramp, ref)');
else miss.push('probe');

fs.writeFileSync(p, s);
console.log(miss.length ? '!! 没找到锚点：' + miss.join(' ') : '四样补丁都写进去了');
