#!/bin/sh
# 跑 out/ 里的判据台驱动：`sh run.sh harness.DeliverKtKt rec3u`
# 为什么要这一个文件：ab/prun/cand 各有一套 classpath，直接敲 java 会漏 kotlin-stdlib，
# 报的是 NoClassDefFoundError: kotlin/jvm/internal/Intrinsics —— 那是**启动器**坏了，
# 不是这一轮没测。这类"看着像结果、其实是工具没牙"的坑本仓记过不止一次。
set -e
. "$(dirname "$0")/jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
[ -d out ] || { echo "!! 没有 out/ —— 先 sh build.sh"; exit 1; }
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$(cygpath -w out);$(cygpath -w stubs_out);$(cygpath -w "$SL")" "$@"
