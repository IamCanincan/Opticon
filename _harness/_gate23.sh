#!/bin/sh
# 只跑 regress.sh 的**第廿三闸**（反射子台活着账），逐字 source 那一段，不另写一份实现。
#
# 为什么要有这个驱动：这一闸本身就是为了抓"仪器静默腐坏"而加的，那它自己更不许是空壳 ——
# 抽取边界按**段标题**定位（不写死行号，理由见 `_gate16.sh` 文件头那次：写死行号曾经把一段
# 纯注释抽出来当"通过"），抽完还要确认里面真的有那句"哪台死了就红"的判据。
set -e
cd "$(dirname "$0")"
. ./jdk.sh
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CPW="out;stubs_out;$(cygpath -w "$SL")"
run() { "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CPW" "$@" 2>/dev/null; }
CORPUS=${CORPUS:-rec_model}

S=$(grep -n '^echo "== 23/23 反射子台活着账 =="' regress.sh | cut -d: -f1 | head -1)
[ -n "$S" ] || { echo "!! 找不到第 23 闸的段标题 —— regress.sh 把它改名了，别拿旧驱动测"; exit 1; }
E=$(awk -v s="$S" 'NR>s && /^echo "   反射子台/ {print NR; exit}' regress.sh)
[ -n "$E" ] || { echo "!! 第 23 闸找不到收尾那句读数 —— 抽取边界不确定，不跑"; exit 1; }
sed -n "${S},${E}p" regress.sh > _gate23_body.sh
# 自检：抽出来的这一段**必须真的含"哪台死了就红"那一句**。少了它，这一跑不构成任何证据。
grep -q 'PA_DEAD' _gate23_body.sh || {
  echo "!! 抽出来的第 23 闸里没有 PA_DEAD（区间 ${S}..${E}）—— 边界错了，这一跑不构成证据"; exit 1; }
. ./_gate23_body.sh
echo "GATE23 通过（抽取区间 ${S}..${E}，已确认含开火判据）"
