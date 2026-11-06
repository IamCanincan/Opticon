echo "== 23/23 反射子台活着账 =="
PA_FIX=rec_model/com.able.wisdomtree_logo_149c234870d7.png
[ -f "$PA_FIX" ] || { echo "!! fixture $PA_FIX 不在 —— 这一闸没牙（fixture 缺不许算过）"; exit 1; }
PA_LIST='harness.RimDbgKt|--brief rec_model|盘实=[0-9]+
harness.PixelDumpKt|PA_FIX|家族#[0-9]+
harness.CellDumpKt|PA_FIX|分家的格子
harness.FieldMixKtKt|PA_FIX|双峰的格'
PA_N=0; PA_DEAD=''
PA_OLDIFS=$IFS; IFS='
'
for pa in $PA_LIST; do
  # ⚠ 循环列表在 `for` 这一句就已经按"一行一项"切完了，进到体内就可以把 IFS 换回去 ——
  #   不换的话 `$pa_args` 里的空格**不会被切开**，`--brief rec_model` 会当成一个文件名传进去，
  #   于是这台闸自己错报"RimDbg 读不出东西"（2026-11-06 第一跑就是这么红的，被抓的是我这段 shell）。
  IFS=$PA_OLDIFS
  [ -n "$pa" ] || continue
  pa_cls=${pa%%|*}; pa_rest=${pa#*|}; pa_args=${pa_rest%%|*}; pa_pat=${pa_rest#*|}
  case "$pa_args" in PA_FIX) pa_args="$PA_FIX" ;; esac
  PA_N=$((PA_N + 1))
  pa_out=$(run "$pa_cls" $pa_args 2>&1) || true
  printf '%s\n' "$pa_out" | grep -qE "$pa_pat" || PA_DEAD="$PA_DEAD $pa_cls"
done
IFS=$PA_OLDIFS
[ "$PA_N" -gt 0 ] || { echo "!! 清单一条都没有 —— 这不是通过，是这台闸被删空了"; exit 1; }
if [ -n "$PA_DEAD" ]; then
  echo "!! 这几台反射子台今天读不出东西：$PA_DEAD"
  echo "   多半是生产私有方法的参数个数变了（反射编译期查不出来）。逐台跑一次看它自己报什么："
  echo "   sh run.sh harness.RimDbgKt --brief rec_model"
  exit 1
fi
echo "   反射子台 $PA_N 台全部印出了自己的读数"
