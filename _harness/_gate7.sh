#!/bin/sh
# 只跑 regress.sh 的**第七闸**（肉眼账），逐字 source 那一段 —— 同 _gate16.sh：
# 按段标题定位，并且**抽出来的必须含底线判据本身**，否则宁可报错也不报"通过"。
set -e
cd "$(dirname "$0")"
. ./jdk.sh
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CPW="out;stubs_out;$(cygpath -w "$SL")"
run() { "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CPW" "$@" 2>/dev/null; }
UPDATE=""
for a in "$@"; do [ "$a" = "--update" ] && UPDATE=1; done
CORPUS=${CORPUS:-rec_model}

S=$(grep -n '^echo "== 7/7 肉眼账' regress.sh | cut -d: -f1 | head -1)
[ -n "$S" ] || { echo "!! 找不到第七闸的段标题 —— regress.sh 把它改名了，别拿旧驱动测"; exit 1; }
E=$(awk -v s="$S" 'NR>s && /^fi$/ {print NR; exit}' regress.sh)
[ -n "$E" ] || { echo "!! 第七闸找不到收尾的 fi —— 抽取边界不确定，不跑"; exit 1; }
sed -n "${S},${E}p" regress.sh > _gate7_body.sh
grep -q 'named_floor.txt' _gate7_body.sh || {
  echo "!! 抽出来的第七闸里没有 named_floor.txt（区间 ${S}..${E}）—— 边界错了，这一跑不构成任何证据"; exit 1; }
. ./_gate7_body.sh
echo "GATE7 通过（抽取区间 ${S}..${E}，已确认含底线判据）"
