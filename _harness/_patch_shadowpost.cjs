// 第二版：把「投影那一补」放在**选支之后**，只作用在已经交出去的那张掩码上。
// 为什么：第一版（cand/ShadowCut.kt）把它写在 buildOnce 里面，于是它参与了 `picksRef` 的比较，
// 切掉 165 格就把 translate 从参照那一支翻到满幅那一支 —— 「文」变成空心描边，比原缺陷更坏。
// 放在选支之后，它**不可能**改变决策，只能改变交出去那张上的那条带。
const fs = require('fs');
const src = '../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt';
let s = fs.readFileSync(src, 'utf8');
const nl = s.includes('\r\n') ? '\r\n' : '\n';
const miss = [];

// 1) build()：选完支之后再切。
const a1 = '    val ref = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl +
  '    return if (picksRef(w, h, ramp, ref)) ref else ramp' + nl + '  }';
const n1 = '    val ref = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl +
  '    val chosen = if (picksRef(w, h, ramp, ref)) ref else ramp' + nl +
  '    // ⚠ **这一刀必须排在选支之后**：它只负责"把投在盘上的那道影子从交出去那张上擦掉"，' + nl +
  '    //   不许参与"哪一支更像设计师"的比较 —— 第一版写在 `buildOnce` 里，切掉 165 格就把' + nl +
  '    //   translate 翻到了满幅那一支，「文」变成空心描边（比原缺陷更坏）。' + nl +
  '    cutShadowOnDelivery(chosen, pixels, under, w, h)' + nl +
  '    return chosen' + nl + '  }';
if (s.includes(a1)) s = s.replace(a1, n1); else miss.push('build()');

// 2) 探子口（判据台要）
const hooks = n1 + nl + nl +
  '  internal fun probeRamp(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, false, under)' + nl + nl +
  '  internal fun probeRef(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,' + nl +
  '    under: IntArray? = null) = buildOnce(pixels, w, h, plateIsLayer, true, under)' + nl + nl +
  '  internal fun probePick(w: Int, h: Int, ramp: Output, ref: Output) = picksRef(w, h, ramp, ref)';
if (s.includes(n1)) s = s.replace(n1, hooks); else miss.push('探子口');

// 3) 那一刀本体（作用在 Output 的 mask 上，就地改）
const fn = '  /**' + nl +
  '   * 把「物体投在那块盘上的影子」从**已经交出去的那张掩码**上擦掉。' + nl +
  '   * 判据与 `盘刀` 同源（背景层单独那张 = 事实），但多两道闸，为的是只认"一条薄影"、' + nl +
  '   * 不碰"画在背景层里的图形"（chrome / jelly / browndust2 那些整块都在背景层）：' + nl +
  '   *   ① 背景层里非主色的格合计 ≤ [SHADOW_MAX_PCT]% 画布；' + nl +
  '   *   ② 这些格每一块的估算半宽（面积 / 边界 ≈ 半宽）≤ 边长的 [SHADOW_MAX_HALF]。' + nl +
  '   * 两个数都是动手前摊过分布才画的（`_harness/BandThickKt`，57 张带背景层的图）：' + nl +
  '   * translate 2.40% / 2.5px，下一个 contacts 6.37% / 7.1px —— 面积上 2.6 倍空档。' + nl +
  '   * 半宽按**边长比例**表达（绝对格数 = 尺寸依赖，这条烧过一次）。' + nl +
  '   */' + nl +
  '  private fun cutShadowOnDelivery(out0: Output, pixels: IntArray, under: IntArray?, w: Int, h: Int) {' + nl +
  '    val out = out0.mask ?: return' + nl +
  '    if (under == null || under.size < out.size) return' + nl +
  '    val total = out.size' + nl +
  '    val fam = HashMap<Int, Int>(); var solid = 0' + nl +
  '    for (i in 0 until total) {' + nl +
  '      if (Color.alpha(under[i]) < A_SOLID) continue' + nl +
  '      solid++; val k = plateKey(under[i]); fam[k] = (fam[k] ?: 0) + 1' + nl +
  '    }' + nl +
  '    val top = fam.maxByOrNull { it.value } ?: return' + nl +
  '    if (solid <= 0 || top.value * 100 < solid * PLATE_ALT_MIN) return' + nl +
  '    var nonPlate = 0' + nl +
  '    for (i in 0 until total)' + nl +
  '      if (Color.alpha(under[i]) >= A_SOLID && plateKey(under[i]) != top.key) nonPlate++' + nl +
  '    if (nonPlate <= 0 || nonPlate * 100 > total * SHADOW_MAX_PCT) return' + nl +
  '    val seen = BooleanArray(total); val stack = IntArray(total); var thinAll = true' + nl +
  '    for (i in 0 until total) {' + nl +
  '      if (seen[i] || Color.alpha(under[i]) < A_SOLID || plateKey(under[i]) == top.key) continue' + nl +
  '      var sp = 0; var area = 0; var boundary = 0' + nl +
  '      stack[sp++] = i; seen[i] = true' + nl +
  '      while (sp > 0) {' + nl +
  '        val c = stack[--sp]; area++' + nl +
  '        val cx = c % w; val cy = c / w' + nl +
  '        for (k in 0..3) {' + nl +
  '          val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]' + nl +
  '          if (nx < 0 || ny < 0 || nx >= w || ny >= h) { boundary++; continue }' + nl +
  '          val j = ny * w + nx' + nl +
  '          if (Color.alpha(under[j]) < A_SOLID || plateKey(under[j]) == top.key) { boundary++; continue }' + nl +
  '          if (!seen[j]) { seen[j] = true; stack[sp++] = j }' + nl +
  '        }' + nl +
  '      }' + nl +
  '      if (area >= SHADOW_MIN_AREA &&' + nl +
  '        area / 2.0 / boundary.coerceAtLeast(1) > w * SHADOW_MAX_HALF) { thinAll = false; break }' + nl +
  '    }' + nl +
  '    if (!thinAll) return' + nl +
  '    for (i in 0 until total) {' + nl +
  '      if (Color.alpha(out[i]) == 0) continue' + nl +
  '      // 只擦"屏幕上看到的就是背景层那块颜色"的格：前景在那儿画了东西（合成色 ≠ 背景色）就不许碰。' + nl +
  '      if (Color.alpha(under[i]) >= A_SOLID && plateKey(under[i]) != top.key &&' + nl +
  '        plateKey(pixels[i]) == plateKey(under[i])) out[i] = 0' + nl +
  '    }' + nl +
  '  }' + nl + nl;
const a3 = '  private fun softAndInk(out: IntArray?, w: Int, h: Int): Pair<Int, Int> {';
if (s.includes(a3)) s = s.replace(a3, fn + a3); else miss.push('函数锚点');

const ca = '  private const val PLATE_ALT_MIN = 85';
const cc = ca + nl +
  '  /** 背景层里非主色的格合计最多占画布这么多%，才可能被判成「一条投影」。 */' + nl +
  '  private const val SHADOW_MAX_PCT = 4' + nl +
  '  /** 小于这么多格的块不参与薄度判定。 */' + nl +
  '  private const val SHADOW_MIN_AREA = 400' + nl +
  '  /** 每块估算半宽最多到边长的这个比例。 */' + nl +
  '  private const val SHADOW_MAX_HALF = 0.01';
if (s.includes(ca)) s = s.replace(ca, cc); else miss.push('常数');

fs.writeFileSync('cand/ShadowPost.kt', s);
console.log(miss.length ? '!! 没找到锚点：' + miss.join(' ') : 'cand/ShadowPost.kt 写好了');
