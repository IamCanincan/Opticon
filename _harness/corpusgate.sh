# 第 3 段（语料）的**入口门** —— 只有一份：`regress.sh` 与变异台 `_corpusgate_mut.cjs` 都调它。
#
# 为什么单独成文件：干净克隆第一次跑的时候，这一段没有门，`MaskBench` 非零退出被 `set -e` 掐在表头后面 ——
#   **新机器上是一片黑**（退码 1，但没话说）。我把门补回去之后，审计器立刻把它算成"判定闸"，
#   于是又多出一道"没有可重跑证物"的段。所以判定本体抽到这里，变异台调的就是回归台跑的这一段。
# 用法：`. ./corpusgate.sh; corpus_gate "$CORPUS"` —— 退 0 = 能跑；非 0 = 这一轮根本不该跑（并印清缺什么、怎么重建）。
corpus_gate() {
  C="${1:?corpus_gate 需要语料目录}"
  if [ ! -d "$C" ]; then
    echo "!! 语料 $C/ 根本不存在 —— 这一台子跑的必须是真图标，不许退回任何自造样本。"
  elif [ "$(ls "$C"/*.png 2>/dev/null | wc -l)" -lt 1 ]; then
    echo "!! 语料 $C/ 在，但里面一张 .png 都没有 —— 同样不许跑。"
  else
    echo "   语料 $C/：$(ls "$C"/*.png 2>/dev/null | wc -l) 张 .png ✓"
    return 0
  fi
  echo '   重建：连着那台手机 sh audit.sh 抓一轮 → node _mkruntime.cjs <抓取目录> corpus_runtime → node _mkrec_model.cjs。'
  echo '   另三份：rec_blank/ 在库里（真机事故那 34 张，不可复现）；plate_model/、yard_model/ 的重建路径**没核实**，'
  echo '   缺它们时第 16 闸与第 5 闸会各自明说没跑。全都在 → 再跑一次这条。'
  return 1
}
