#!/bin/sh
# 装机 + 收日志，**按正确的顺序**做。2026-10-12 我自己把一轮证据清了，这条就是防它。
#
# 错法（我上一轮干的）：
#   adb install -r app.apk      # 桌面被杀、重启，**立刻**重画图标 → 点名单上那几张在这一秒带 PNG
#   adb logcat -c               # ← 然后我把这一秒清掉了
#   adb logcat ... > live.txt   # 从这以后每次渲染都被 `seen` 去重挡下，一行 `dump` 都没有
# 结果：translate 有 7 次渲染、0 张像素回来、连"不抓"也没报 —— 我一度以为是通道又坏了。
# （通道当然也有责任：那个去重原来是静默 return，已在 `MonoDump.maybe` 补了一行。）
#
# 对法：**先清缓冲，再装机**，装完的那一秒正是带东西回来的时候。
# 跑法：sh _cap.sh <apk 路径> <输出文件>
set -e
cd "$(dirname "$0")/.."          # 一切路径都按**仓库根**算：上一版把默认值写成 `../app/...`，
                                 # 先 cd 到仓根再拼 `..` 就跑到仓外面去了，install 直接 stat 失败。
APK=${1:-app/build/outputs/apk/release/app-release.apk}
OUT=${2:-/d/tmp/cap.txt}
export MSYS_NO_PATHCONV=1
adb logcat -c
adb install -r "$APK"
echo "== 收日志到 $OUT（Ctrl-C 停）：现在去点「清图标缓存并重启桌面」=="
adb logcat -v time Opticon:V "* *:S" > "$OUT" &
CAP=$!
trap 'kill $CAP 2>/dev/null' EXIT INT TERM
wait $CAP
