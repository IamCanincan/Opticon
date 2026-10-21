#!/bin/sh
# 全语料数一遍"真断口"：半径 1 就能接的、和必须半径 2 才接得到的。
EG=/d/tmp/eg9
SRC="/d/Github Repositories/Opticon/_harness/rec_model"
OUT=/d/tmp/gapall.txt
: > "$OUT"
for g in $(ls "$EG"); do
  case "$g" in *modelbg*) continue ;; esac
  [ -f "$SRC/$g" ] || continue
  java -Dstdout.encoding=UTF-8 -cp /d/tmp GapOrEdge "$EG/$g" "$SRC/$g" 2>/dev/null |
    grep "离墨" | grep -oE "断口=[0-9]+" | cut -d= -f2 | tr '\n' ' ' |
    sed "s|\$| ${g}|" >> "$OUT"
  echo "" >> "$OUT"
done
awk '{ if (NF >= 2) { g1 = $1 + 0; g2 = $2 + 0; n = $3 } else { g1 = 0; g2 = 0; n = "" }
       if (n == "") next
       a += g1; b += g2; if (g1 + g2 > 0) { print g1, g2, n; c++ } }
     END { printf "----\n有真断口的图标=%d  半径1可接=%d  半径2新增可接=%d  合计=%d\n", c, a, b, a + b }' \
     "$OUT" | sort -k2 -rn
