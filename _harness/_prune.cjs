const fs = require('fs');
const A = 'app/src/main/kotlin/com/iamcanincan/opticon/';
let n = 0;
function edit(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    if (!t.includes(a)) { console.log('!! 锚点没对上 @' + path + '：' + a.slice(0, 46)); process.exit(1); }
    t = t.replace(a, b); n++;
  }
  fs.writeFileSync(path, t);
}
edit(A + 'notify/NotificationIconPatch.kt', [[
`            // 2) 已经适配过的单色图标不动
            if (options.preserveTinted) {
                val bitmap = IconBitmap.decode(smallIcon, context)
                if (bitmap != null && ToneCheck.isGrayscale(bitmap)) return
            }

            // 3) 未适配的：就地压成单色`,
`            // 2) 未适配的：就地压成单色（已经单色的在 forceMonochrome 里原样放过）
            // ⚠ 这里原来还有一步「先解码一次、判是不是灰度、是就返回」，由
            //   \`options.preserveTinted\` 把闸 —— 而那个字段**没有任何通道会设它**，恒为真，
            //   且 forceMonochrome 里本来就有同一条 isGrayscale 检查。等于同一件事做两遍、
            //   还多解一次码。删的是重复，不是行为。`]]);

edit(A + 'runtime/ModuleOptions.kt', [[
`    /**
     * 已经做主题适配（单色）的图标原样放过 —— 这是「适配过的不要动」的核心。
     */
    val preserveTinted: Boolean = true
`, ''],
[`        "circle=$circleEnabled monoInvert=$monoInvert notify=$notifyEnabled" +
            " preserveTinted=$preserveTinted"`,
 `        "circle=$circleEnabled monoInvert=$monoInvert notify=$notifyEnabled"`]]);

edit(A + 'circle/MonochromeSynth.kt', [[
`      //  通知侧仍然认这个选项（\`preserveTinted\`，那边是系统自己上色，不走这条遮罩路径）。`,
`      //  通知侧不看这个开关（那边是系统自己上色，不走这条遮罩路径）。`]]);

edit(A + 'notify/IconBitmap.kt', [[
`     * @param preserveAlpha 保留原始 alpha 的抗锯齿值（只把颜色换成白色），
     *   而不是二值化成 0 / 255。状态栏图标不需要（96px 下硬边看不出来），
     *   但桌面主题图标的渲染尺寸大得多，硬边一放大就是一圈锯齿 —— 那边传 true。
     *   ⚠ 它只决定「写出去的 alpha 是多少」，**不参与「哪些像素算图形」的判定** ——
     *   判定一律是 \`alpha >= MIN_SHAPE_ALPHA\`（[binarize] 那边同理，判据是
     *   \`class != CLASS_TRANSPARENT\`）。这两件事一旦混在一起，掩码的真实覆盖率
     *   就会和返回的 \`shapeCount\` 差一个数量级，而调用方正是拿 \`shapeCount\`
     *   在判「压出来的形状合不合理」—— 判据和产物对不上，等于没判。`,
`     * ⚠ **「哪些像素算图形」的判定与「写出去的是什么」必须是同一口径**：判定一律是
     *   \`alpha >= MIN_SHAPE_ALPHA\`（[binarize] 那边同理，判据是 \`class != CLASS_TRANSPARENT\`）。
     *   两件事一旦混在一起，掩码的真实覆盖率就会和返回的 \`shapeCount\` 差一个数量级，
     *   而调用方正是拿 \`shapeCount\` 在判「压出来的形状合不合理」—— 判据和产物对不上，等于没判。
     *   曾经这里有一个 \`preserveAlpha\` 参数（保留抗锯齿 alpha、不二值化），是给桌面主题图标
     *   用的；那条路换成 [MonochromeMask] 之后没人传 true 了，参数与它的分支一起删（2026-09-30）。`],
[`    fun monochrome(input: Bitmap, preserveAlpha: Boolean = false): Bitmap? {`,
 `    fun monochrome(input: Bitmap): Bitmap? {`],
[`            alphaShape(pixels, outputPixels, preserveAlpha)`, `            alphaShape(pixels, outputPixels)`],
[`            binarize(pixels, outputPixels, w, h, preserveAlpha)`, `            binarize(pixels, outputPixels, w, h)`],
[`     * 之前 preserveAlpha 分支写成 \`alpha > 0 && (...)\`，判据用的是 128、写出去的却是 1 起步，
     * 两边口径不一致，就是这么踩进去的。
     *
     * @param preserveAlpha 保留原 alpha 的抗锯齿值（只换颜色），不二值化成 0 / 255。
     *   状态栏图标（96px）不需要，桌面主题图标渲染尺寸大得多、硬边一放大就是锯齿，那边传 true。
     * @return 涂了多少个像素（调用方拿它在判「压出来的形状合不合理」）
     */
    private fun alphaShape(pixels: IntArray, outputPixels: IntArray, preserveAlpha: Boolean): Int {`,
`     * 曾经这条分支写成 \`alpha > 0 && (...)\` —— 判据用 128、写出去的从 1 起步，两边口径不一致，
     * 就是这么踩进去的。
     *
     * @return 涂了多少个像素（调用方拿它在判「压出来的形状合不合理」）
     */
    private fun alphaShape(pixels: IntArray, outputPixels: IntArray): Int {`],
[`            outputPixels[i] = if (preserveAlpha) Color.argb(alpha, 255, 255, 255) else Color.WHITE`,
 `            outputPixels[i] = Color.WHITE`],
[`    private fun binarize(
        pixels: IntArray,
        output: IntArray,
        w: Int,
        h: Int,
        preserveAlpha: Boolean,
    ): Int {`,
`    private fun binarize(
        pixels: IntArray,
        output: IntArray,
        w: Int,
        h: Int,
    ): Int {`],
[`                // 同 [monochrome]：preserveAlpha 时保留原 alpha 的抗锯齿边缘
                output[i] = if (preserveAlpha) {
                    Color.argb(Color.alpha(pixels[i]), 255, 255, 255)
                } else {
                    Color.WHITE
                }`,
`                output[i] = Color.WHITE`]]);
console.log('改了', n, '处');
