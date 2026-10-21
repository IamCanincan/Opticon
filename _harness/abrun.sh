#!/bin/sh
# 跑 ab.sh 编出来的那两份：`sh abrun.sh old harness.SelfTestKt`
# 单独一个脚本是因为每条 shell 命令都是新进程 —— 上一版把 run() 定义在 ab.sh 里，
# 下一条命令就找不到它了（"两边跑的是同一份"就是这么来的另一种）。
set -e
. "$(dirname "$0")/jdk.sh"
v=$1; shift
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
[ -d "var_$v" ] || { echo "没有 var_$v —— 先 sh ab.sh"; exit 1; }
"$JAVA" -Dstdout.encoding=UTF-8 -cp "var_$v;stubs_out;$(cygpath -w "$SL")" "$@"
