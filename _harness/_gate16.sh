#!/bin/sh
# 只跑 regress.sh 的**第十六闸**，逐字 source 那一段，不另写一份实现。
#
# ⚠ 为什么不再写死行号（2026-10-16 自己踩的）：这驱动原先写的是 `sed -n '333,352p'`，
#   而我随后给 regress.sh 前段加了 13 行守卫注释 —— 段落到 346 行，驱动当场抽成**一段纯注释**，
#   连 pin 比对都没抽进去。这次是**语法报错**救了我（不是守卫灵），但换一种漂移就会
#   "什么都没测"却打一句"通过"。所以：按段标题定位 + **抽出来的东西必须含判据本身**。
set -e
cd "$(dirname "$0")"
. ./jdk.sh
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CPW="out;stubs_out;$(cygpath -w "$SL")"
run() { "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CPW" "$@" 2>/dev/null; }
UPDATE=""
for a in "$@"; do [ "$a" = "--update" ] && UPDATE=1; done
CORPUS=${CORPUS:-rec_model}

S=$(grep -n '^echo "== 16/16 极性入口账 =="' regress.sh | cut -d: -f1 | head -1)
[ -n "$S" ] || { echo "!! 找不到第 16 闸的段标题 —— regress.sh 把它改名了，别拿旧驱动测"; exit 1; }
E=$(awk -v s="$S" 'NR>s && /^fi$/ {print NR; exit}' regress.sh)
[ -n "$E" ] || { echo "!! 第 16 闸找不到收尾的 fi —— 抽取边界不确定，不跑"; exit 1; }
sed -n "${S},${E}p" regress.sh > _gate16_body.sh
# 自检：抽出来的这一段**必须真的含 pin 比对**。少了它，这一跑就是在测空壳。
grep -q 'platesub.pin' _gate16_body.sh || {
  echo "!! 抽出来的第 16 闸里没有 platesub.pin（区间 ${S}..${E}）—— 边界错了，这一跑不构成任何证据"; exit 1; }
. ./_gate16_body.sh
echo "GATE16 通过（抽取区间 ${S}..${E}，已确认含 pin 比对）"
