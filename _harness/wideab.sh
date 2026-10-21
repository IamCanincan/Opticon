#!/bin/sh
# 广色度 A/B 像素台：HEAD 那份与工作区那份**各渲染一遍交付遮罩**，再逐格比墨。
#
# 为什么不用 `FaintShotKt`：那一台调的是 `build(px, w, h)` 默认参数，而判据台喂进去的是
# `build(src, edge, edge, plateLayer, under)` —— 少了 `plateLayer` 与 `under` 两样输入。
# 2026-10-19 当场露馅：贴吧 / UU 遥控器在像素比对里一格没变，判据行却是 `顶占比 14→34 / 10→49`。
# ⇒ 渲染必须走**判据台那一份输入构造**（`MaskBench --ink=`），否则比的不是同一件事。
#
# 用法：先 `sh ab.sh`（建 var_old/var_new 并过指纹闸），再 `sh wideab.sh [语料目录]`
set -e
cd "$(dirname "$0")"
. "./jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
win() { cygpath -w "$1"; }
CORPUS=${1:-rec_model}
[ -d var_old ] && [ -d var_new ] || { echo "!! 没有 var_old/var_new —— 先 sh ab.sh"; exit 1; }
# 指纹再核一次：这一台的全部意义是"两边跑的字节码不同"，而 var_* 可能被上一轮留下。
A=$(md5sum var_old/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
B=$(md5sum var_new/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
echo "指纹  old=$A  new=$B"
[ "$A" = "$B" ] && { echo "!! 两份字节码一模一样 —— 这一轮什么都没比"; exit 1; }
for d in /d/tmp/wA /d/tmp/wB; do rm -rf "$d"; mkdir -p "$d"; done
for p in A:old B:new; do
  o=${p%%:*}; c=${p##*:}
  echo "== 渲染 $o <= var_$c"
  "$JAVA" -Dstdout.encoding=UTF-8 -cp "$(win var_$c);$(win stubs_out);$(win "$SL")" \
    harness.MaskBenchKt --edge=384 --ink="D:/tmp/w$o" "$CORPUS" > "_wideab_$o.txt"
  echo "   行数 $(grep -c '^---' "_wideab_$o.txt")，出图 $(ls /d/tmp/w$o | wc -l)"
done
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$(win /d/tmp)" DiffInk D:/tmp/wA D:/tmp/wB
