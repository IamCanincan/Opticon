echo "== 16/16 极性入口账 =="
PLATECORPUS=${PLATECORPUS:-plate_model}
[ -d "$PLATECORPUS" ] || {
  echo "!! 语料 $PLATECORPUS/ 不在 —— 这一闸**不许**退回 rec_model 空跑（那正是它原本瞎掉的样子）"; exit 1; }
PSRC=0
run harness.PlateSubKtKt "$PLATECORPUS" --min=50 > bench/reg/platesub.txt || PSRC=$?
grep -q '^PS ' bench/reg/platesub.txt || {
  echo "!! 极性入口账没出数 —— 驱动或语料坏了（它自己那 3 行在下面）"; tail -3 bench/reg/platesub.txt; exit 1; }
NEWPS=$(grep '^PS ' bench/reg/platesub.txt)
if [ -n "$UPDATE" ]; then
  echo "$NEWPS" > bench/reg/platesub.pin; echo "   --update：已把这一轮钉成新基线 $NEWPS"
else
  OLDPS=$(cat bench/reg/platesub.pin 2>/dev/null)
  [ -z "$OLDPS" ] && { echo "!! 没有 bench/reg/platesub.pin —— 没基线不算过（先 sh regress.sh --update 钉一次）"; exit 1; }
  [ "$PSRC" != "0" ] && { echo "!! 极性入口账自己判了不通过（退码 $PSRC，最常见是「一张都没开火」）"; tail -2 bench/reg/platesub.txt; exit 1; }
  [ "$NEWPS" = "$OLDPS" ] || {
    echo "!! 极性入口的**集合**动了："; echo "   基线 $OLDPS"; echo "   这轮 $NEWPS"
    echo "   逐张对照见 bench/reg/platesub.txt；变好也要认账（看清是哪几张进出，再 --update）"; exit 1; }
  echo "   $NEWPS —— 与基线逐字相同"
fi
