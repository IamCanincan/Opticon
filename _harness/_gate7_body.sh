echo "== 7/7 肉眼账（named.txt 15 张，主口径 = 装机那一张）=="
if [ -d corpus_runtime ] && [ -f corpus_runtime/index.tsv ]; then
  # 装机那一张：判据行 + 族尺侧档（`LedgerKt` 逐张出擦空/平台跨那几个数，判据行里没有）
  run harness.MaskBenchKt --edge=384 "$CORPUS" > bench/reg/named_model.txt
  run harness.LedgerKtKt "$CORPUS" --tsv=bench/reg/ledger_model.txt
  # H 尺（细桥）：`merge` 那一族（主人新报的「粘连」）对得上的就是它 —— 源里两块不靠细线连，
  #   交付里连起来了 = 我们凭空搭的桥。`--take=999` 让它把每一张都印出来，`_named.cjs` 直接读表。
  run harness.BridgeKtKt "$CORPUS" --take=999 > bench/reg/bridge_model.txt
  [ "$(wc -l < bench/reg/ledger_model.txt 2>/dev/null || echo 0)" -gt 1 ] || {
    echo "!! 族尺侧档没出数 —— F/G 这一轮一张都没量，不许退回只剩 A~E 的覆盖账"; exit 1; }
  grep -q "^BRIDGE n=[1-9]" bench/reg/bridge_model.txt || {
    echo "!! H 尺（细桥）没出数 —— merge 这一族本轮没量，不许算过"; exit 1; }
  node _named.cjs bench/reg/named_model.txt --tsv=bench/reg/ledger_model.txt \
    --bridge=bench/reg/bridge_model.txt > bench/reg/named_model_full.txt
  # 对照口径（前景层）：只看不判
  run harness.MaskBenchKt --edge=384 corpus_runtime > bench/reg/named_bench.txt
  run harness.LedgerKtKt corpus_runtime --tsv=bench/reg/ledger.txt
  node _named.cjs bench/reg/named_bench.txt --tsv=bench/reg/ledger.txt > bench/reg/named.txt
  echo "   ── 主口径：装机那一张（$CORPUS）──"
  tail -6 bench/reg/named_model_full.txt
  echo "   ── 对照：前景层口径（corpus_runtime，只看不判）──"
  tail -2 bench/reg/named.txt
  GOT=$(node -e 'const fs=require("fs");const m=/合计\s+(\d+)\/(\d+)/.exec(fs.readFileSync("bench/reg/named_model_full.txt","utf8"));console.log(m?(+m[1])+" "+(+m[2]):"0 15")')
  # ⚠ 底线文件不在就是**没有底线**，`|| echo 0` 会把"没基线"读成"底线 0 → 恒过"（2026-10-16 全量检查抓的）。
  [ -f bench/named_floor.txt ] || { echo "!! 没有 bench/named_floor.txt —— 底线不明，这一闸不算过（要认账就先钉一次）"; exit 1; }
  FLOOR=$(cat bench/named_floor.txt)
  [ "${GOT%% *}" -ge "$FLOOR" ] || { echo "!! 肉眼账覆盖率 $GOT < 底线 $FLOOR —— 这一刀让台子对主人的账更瞎了"; exit 1; }
  # 分母从台账自己读，别写死：`named.txt` 加一行（易班这次就加了第四族 merge）分母就变了，
  # 写死的 15 会把 "16/16" 印成 "16/15" —— 一个说不清自己分母的数，跟没数一样。
  echo "   覆盖 主口径 ${GOT%% *}/${GOT#* }（底线 $FLOOR）"
else
  echo "!! 没有 corpus_runtime/（或它没有 index.tsv）—— **这一闸这一轮没跑**，不算通过。"
  echo "   建它：sh audit.sh 抓一轮，再 node _mkruntime.cjs <抓取目录> corpus_runtime"
  exit 1   # 2026-10-16 全量检查：这里原先只 print 不退，"不算通过"四个字整机没人执行
fi
