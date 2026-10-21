// 给 cand/WhiteUp.kt 补三样：常数、报告列、探子口。写成文件是为了躲开命令行那层引号转义。
const fs = require('fs');
const p = 'cand/WhiteUp.kt';
let s = fs.readFileSync(p, 'utf8');
const nl = s.includes('\r\n') ? '\r\n' : '\n';
const miss = [];

const ca = '  private const val PLATE_ALT_MIN = 85';
const cc = ca + nl +
  '  /** 「白字差最后一段」：源明度至少这么亮（Rec.709，0..255）才算"白"。 */' + nl +
  '  private const val WHITE_UP_LUMA = 220' + nl +
  '  /** 交付至少已经实到这一档才补满 —— 普查里这堆格的 p10 是 172、p50 是 226，' + nl +
  '   *  门槛画在 190 就是"只补最后一段"，α<160 那 6% 一律不碰。 */' + nl +
  '  private const val WHITE_UP_MIN = 190';
if (s.includes(ca)) s = s.replace(ca, cc); else miss.push('常数');

const ra = '" 盘刀=$plateCut 盘候=$plateCand 盘层=${if (under == null) 0 else 1}"';
if (s.includes(ra)) s = s.replace(ra, ra + ' +' + nl + '          " 抬白=$whiteUp"'); else miss.push('报告列');

const pa = '    val ref = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl +
  '    return if (picksRef(w, h, ramp, ref)) ref else ramp' + nl + '  }';
const hooks = pa + nl + nl +
  '  internal fun probeRamp(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, false, under)' + nl + nl +
  '  internal fun probeRef(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl + nl +
  '  internal fun probePick(w: Int, h: Int, ramp: Output, ref: Output) = picksRef(w, h, ramp, ref)';
if (s.includes(pa)) s = s.replace(pa, hooks); else miss.push('探子口');

fs.writeFileSync(p, s);
console.log(miss.length ? '!! 没找到锚点：' + miss.join(' ') : '三样都写进去了');
