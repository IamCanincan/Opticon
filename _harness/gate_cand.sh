#!/bin/sh
# 把**候选**临时放进仓里跑一遍十闸，跑完一定还原（不管红不红）。
# 用法：sh gate_cand.sh cand/BothShip.kt [边长表]
. "$(dirname "$0")/jdk.sh" 2>/dev/null
SRC=$(dirname "$0")/../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt
BK="$(dirname "$0")/_mask_HEAD.kt"
cp "$SRC" "$BK"
trap 'cp "$BK" "$SRC"; echo "[gate_cand] 仓里那份已还原"' EXIT INT TERM
cp "$1" "$SRC"
cd "$(dirname "$0")"
EDGES=${2:-96,192,288,384} sh regress.sh
echo "[gate_cand] regress 退出码 = $?"
