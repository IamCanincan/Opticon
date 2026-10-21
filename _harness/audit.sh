#!/bin/sh
# 全量审计一条命令：**抓 → 渲 → 分类点名 → 与上一轮逐张 diff**。
#
# 为什么要有它（2026-10-03）：主人原话「你得把算法变得通用，不然每次又要一个一个报给你问题」。
# 算法侧的通用今天量到底了 —— 对已经合成平的位图，"哪块是图形、哪块是承载面"在颜色统计里
# 没有答案（八把尺子 + 两次口径事故，见 README）。但**"发现问题"这一步确实可以搬离他**：
# 抓全机图标不需要他的手（CorpusCapture 三条 adb），分类点名不需要他的手，
# 与上一轮比谁变好谁变坏也不需要。所以这条脚本干的就是这件事。
#
# 跑法：
#   sh audit.sh                 # 抓 + 渲 + 点名 + 与上一轮 diff
#   sh audit.sh --no-capture    # 用上次抓回来的语料（不碰手机）
#   sh audit.sh --dir=corpus_dev2
set -e
. "$(dirname "$0")/jdk.sh"
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/*.jar)
CPW="out;stubs_out;$(cygpath -w "$SL")"
run() { "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CPW" "$@" 2>/dev/null; }
DIR=${DIR:-corpus_audit}
# ⚠ 参数按**任意顺序**认，不再拿 `$1` 当开关：`--dir=` 写在第一个位置时，旧写法会把它
#   当成"不是 --no-capture"→ 去走抓取，抓不到再退回一个**根本没解析**的默认目录。
#   今天就是这么死的：`sh audit.sh --dir=corpus_dev2` 实际跑的是 corpus_audit/，
#   那个目录压根不存在，于是 MaskBench 拿到空目录、`set -e` 直接退出，**一句话都没说**。
NOCAP=0
for a in "$@"; do
  case "$a" in
    --no-capture) NOCAP=1 ;;
    --dir=*) DIR="${a#--dir=}" ;;
  esac
done
# ⚠ 这道闸门**只能查"不抓的那条路"**：`corpus_audit/` 本来就是抓取这一步建出来的，
#   在抓取之前要求它存在 = 新机器上第一次抓取永远跑不起来（2026-10-06 就是这么把自己堵死的，
#   而且它报错报得很响，看着像"语料没准备好"，其实是主路进不去）。
need_corpus() {
  [ -d "$DIR" ] || { echo "!! 语料目录 $DIR/ 不存在。手机在跑：sh audit.sh（会抓）；用现成的：sh audit.sh --no-capture --dir=corpus_dev2"; exit 1; }
  NPNG=$(ls "$DIR"/*.png 2>/dev/null | wc -l)
  [ "$NPNG" -gt 0 ] || { echo "!! 语料目录 $DIR/ 里一张 png 都没有（$NPNG）—— 这一轮没有可点名的东西，不往下跑"; exit 1; }
}

if [ "$NOCAP" = 0 ] && adb get-state >/dev/null 2>&1; then
  echo "== 1/5 抓全机图标（adb 触发，不需要手）=="
  adb shell 'echo "edge=384" > /sdcard/Android/data/com.iamcanincan.opticon/files/corpus_request.txt; chmod 666 /sdcard/Android/data/com.iamcanincan.opticon/files/corpus_request.txt'
  adb shell content query --uri content://com.iamcanincan.opticon.config >/dev/null
  echo "   等应用自己画一遍…"
  sleep 40
  rm -rf ".audit_tmp/$DIR"; mkdir -p ".audit_tmp/$DIR"
  # ⚠ **落点必须是 Windows 路径**：adb.exe 不看 MSYS 的挂载表，`/tmp/...` 和
  #   `.audit_tmp/...` 它都只当无效路径，报 `cannot create file/directory`。
  #   这条错以前一直被"手机不在"挡在前面从没跑到 —— 2026-10-06 第一次连上才露出来。
  #   远端那头的双斜杠 + MSYS_NO_PATHCONV 仍然要：git-bash 会把 `/sdcard/...` 改写成
  #   `D:/Git/sdcard/...`，于是 adb 报"remote object 不存在"，看着像应用没写文件。
  MSYS_NO_PATHCONV=1 adb pull "//sdcard/Android/data/com.iamcanincan.opticon/files/corpus" "$(cygpath -w ".audit_tmp/$DIR")" >/dev/null
  mkdir -p "$DIR" && cp ".audit_tmp/$DIR/corpus/"*.png "$DIR/" 2>/dev/null || cp ".audit_tmp/$DIR/"*.png "$DIR/"
  rm -rf ".audit_tmp/$DIR"
  NPNG=$(ls "$DIR"/*.png 2>/dev/null | wc -l)
  # 抓了个空回来必须停：不然下面渲染的是**上一批**语料，而屏幕上写着"抓过了"。
  [ "$NPNG" -gt 0 ] || { echo "!! 抓取回来 0 张（$DIR/ 是空的）—— 应用没写文件，或那 40 秒不够。这一轮不跑"; exit 1; }
  echo "   $DIR/ 共 $(ls "$DIR"/*.png 2>/dev/null | grep -vc "_mono" ) 张（不含 *_mono 对照层）"
else
  # ⚠ 设备不在**不是失败**：这是一条要长期站着的审计线，掉线时退回上一批语料继续点名，
  #   但必须把"这一轮没抓新的"说在明面上 —— 悄悄用旧语料跑出一轮新数，比不跑更坏。
  [ "$NOCAP" = 0 ] && echo "!! 手机不在（adb get-state 失败），退回现有语料 $DIR/ —— 这一轮**没有新抓**"
  need_corpus
  echo "== 1/5 跳过抓取，用 $DIR/ 现有语料（$NPNG 张）=="
fi

echo "== 2/5 拼「模型真吃的那一张」=="
# ⚠ **合成图不是运行时输入。** 自适应图标在运行时只把**前景层**交给模型
#   （`MonochromeSynth` 取 `source.foreground`），盘在背景层里 —— 真机 91 个自适应图标的
#   前景层**中位 74% 是透明**。2026-10-06 量定：15 张缺陷账里有 **7 张**两种口径交出来的
#   `框内` 差 1.7~6 倍（驾考宝典 0.24→0.58、Google 翻译 0.36→0.06）。审计之前一直对着
#   一个运行时不存在的输入挑毛病。抓取端已另存 `*_fg.png`，这里按 index 拼出真实输入集。
RT="${DIR}_rt"
node _mkruntime.cjs "$DIR" "$RT" || { echo "!! 拼运行时输入集失败，退回合成图口径 —— 这一轮的数**不可与屏幕对照**"; RT=$DIR; }

echo "== 3/5 编译判据台（编的是仓里那份 MonochromeMask.kt）=="
sh build.sh >/dev/null
grep -qE "error:" _kt.log && { echo "!! 编译有 error，这一轮一张都没测"; exit 1; }
echo "   0 error"

echo "== 4/5 渲染 + 拼总表（对着 $RT/ —— 模型真吃的那一张）=="
rm -rf bench/preview bench/montage
run harness.MaskBenchKt --shot --edge=384 "$RT" > "bench/audit_bench.txt"
run harness.MontageKtKt bench/preview | tail -1
echo "   总表在 bench/montage/montage_0*.png（一屏几十张，人眼扫类别）"

echo "== 5/5 分类点名 + 与上一轮 diff =="
node _audit.cjs bench/audit_bench.txt --snap=bench/audit --corpus="$RT"
