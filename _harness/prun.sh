#!/bin/sh
# 跑 ab_pair.sh 编出来的那两份：`sh prun.sh a harness.MaskBenchKt <目录>`
set -e
. "$(dirname "$0")/jdk.sh"
v=$1; shift
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
[ -d "pair_$v" ] || { echo "没有 pair_$v —— 先 sh ab_pair.sh"; exit 1; }
"$JAVA" -Dstdout.encoding=UTF-8 -cp "pair_$v;stubs_out;$(cygpath -w "$SL")" "$@"
