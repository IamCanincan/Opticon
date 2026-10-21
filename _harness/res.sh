#!/bin/sh
# 两个 git 版本之间，只问一件事：`残盘=` 的总量与涨跌行数。
# 为什么单独一个脚本：今天三次"残盘 0 变化"里有一次是拿错列算的（join 之后
# 旧残盘在第 7 列、新残盘在第 15 列，写成 $13-$12 就变成"新残盘 − 新淡占比"）。
# 列错位这种错，只有把口径写死在一个地方才不会再犯。
set -e
A=$1; B=$2
sh ab_pair.sh $A $B >/dev/null
for v in a b; do
  sh prun.sh $v harness.MaskBenchKt rec3 2>/dev/null | awk -f j16b.awk | grep -v preview | sort -t$'\t' -k1,1 > /tmp/rs_$v.tsv
done
join -t$'\t' -1 1 -2 1 /tmp/rs_a.tsv /tmp/rs_b.tsv | awk -F'\t' '
{ so+=$7; sn+=$15; d=$15-$7; if(d>0)up++; else if(d<0)dn++; if(d>1000) big++; if(so2[NR]==0){} }
END { printf "%s → %s ｜ 残盘合计 %d → %d（净 %+d）｜ 涨 %d 行 跌 %d 行（其中 >1000 格 %d 行）\n", A, B, so, sn, sn-so, up, dn, big }' A=$A B=$B
