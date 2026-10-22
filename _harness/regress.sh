#!/bin/sh
# 回归台：一条命令跑完 **性质 48 条 + 语料指标 + 单调性审计 + 兼容性台 + 边长漂移**，
# 任何一项比基线差就**非零退出**（六段：编译 / 性质 / 语料 / 单调性 / 对表 / 兼容性）。
#
# 为什么要它：2026-09-29 一天里翻出两处"跑了、印了、没人拦"——
#   ① 语料目录混进判据台自己的 `*_preview.png`，于是 155 行的 A/B 里 42 行是输出；
#   ② `ab.sh` 那道"两份字节码一样=没在比东西"的闸门**永远不会响**（源文件名不同，md5 必然不同）。
#   两个都不是算法问题，是**测量装置**的问题 —— 而这类问题只有"不绿就不让过"才拦得住。
#
# 跑法：
#   sh regress.sh                 # 快（语料只跑 384）
#   sh regress.sh --update        # 认账，把这一轮写成新基线
#   EDGES=96,192,384,768 sh regress.sh   # 全扫：同一批真图在四档边长上各跑一遍，报漂移
set -e
# classpath 与 `run()` 在 `gateenv.sh`（与语义变异台共用，别在这里再抄一份）。
. "$(dirname "$0")/gateenv.sh"
EDGES=${EDGES:-384}
# **语料按像素去重。** `rec3` 是逐轮 dump 攒下来的，同一个 drawable 每抓一次换一个后缀 ——
# 2026-10-04 量到 113 个文件里 **51 个与别人逐字节相同**，真正不同的只有 62 张。
# 那不只是难看：`框内中位` 被拉低 0.08（0.27 vs 0.35）、`残盘合计` 是纯行求和、放大近 3 倍。
# 所以默认换成 `rec3u`（= rec3 去掉重复，`_harness/_dedupe.cjs` 建的）。
# ⚠ 这是一次**分母变更**，不是一次清理：旧基线里的 113 行读数与新读数的，是两把尺子。
#
# **2026-10-11 深夜第二次分母变更：`rec3u` → `rec_model`。** `rec3u` 是真机 dump，**口径对但素材旧**，
# 而且目录里**没有 `_modelbg.png`** —— 也就是说 `盘刀` 在这份语料上从来不开火，装机那条路的一半
# 从没被回归台量过。`rec_model` 是抓取通道补上 `_model.png`/`_modelbg.png` 之后建的第一份
# **素材新 + 与 `MonochromeSynth` 同一分支同一几何**的语料（62 张非适配，其中 20 张带 `under`）：
#   node _mkrec_model.cjs /d/tmp/corpus_model/corpus rec_model
# ⚠ 换分母之后 6 项判据全部重钉过（淡 21→28、字均净 229→214、残盘 1180→1480、ρ色 .973→.953、
#   ρ色<0.95 30→31、平色饼 10→11）—— **旧基线的绝对值从今天起不可比**，别拿它说"这一刀让 X 退步了"。
# 变异测试（重钉之后当场做的，两次都响）：把 `faint` 钉成 15 → 报"淡 28 vs 基线 15"退步；
#   把 `n` 钉成 63 → 报"分母 62 vs 63 —— 少一张就是分母被动过"。还原后逐字节相同、全绿。
CORPUS=${CORPUS:-rec_model}
# 每轮开头清空"本轮跳过了哪几闸"名单 —— 它由第 10 闸那种"设计上允许跳过"的分支追加，
# 最后由 `_regress.cjs` 印进判定行。留着上一轮的会谎报"这轮又跳了"。
mkdir -p bench/reg && : > bench/reg/skipped.txt
# ⚠ **同一时间只许跑一台。** `bench/reg/` 是共享目录，而每台开头都要 `rm -f` 它 ——
#   2026-10-17 真撞上：我前后起了两台（第一台还在第 11 闸），第二台的 `rm` 把第一台
#   刚写的 `parts.txt` 删了，第一台在第 12 闸 ENOENT 崩掉；而外层那句
#   `sh regress.sh > 日志; echo EXIT=$?` 的退码是 `echo` 的 —— **崩了还报"跑完没红"**。
#   这是本仓记过的"没跑却报绿"家族的又一种形态：不是判据骗人，是**两台量具踩同一张桌子**。
LOCKF=bench/reg/.running
if [ -f "$LOCKF" ] && kill -0 "$(cat "$LOCKF" 2>/dev/null)" 2>/dev/null; then
  echo "!! 已经有一台 regress 在跑（pid $(cat "$LOCKF")）—— 先等它跑完，或删掉 $LOCKF"
  exit 1
fi
echo $$ > "$LOCKF"
trap 'rm -f "$LOCKF"' EXIT INT
mkdir -p bench/reg
rm -f bench/reg/*.txt
# ⚠ **逐张读数落盘的文件也必须每轮清掉。** 第 13 闸吃 `yard_dump.tsv`：如果 `--dump` 被谁从
#   第 5 段挪走，这份文件会**停在上一轮**，而上一轮是绿的 —— 于是那一闸变成一把永远不响的尺。
#   `*.txt` 那行盖不到 `.tsv`（`drift.tsv` 同理，但它没有闸吃它）。
rm -f bench/reg/yard_dump.tsv

echo "== 1/7 编译 =="
# **先扫 shell 雷**：双引号包反引号 = 命令替换。这条不是洁癖 —— 2026-10-21 我给这一段补"缺语料要说句话"的门，
# 门里那句 `echo "… sh audit.sh …"`（反引号）把 `audit.sh` **真的执行了**，回归台没停住，反而去 pull 了手机。
# 缺 node 就明说没查，不许装成查过（本仓那一族：0 命中常常是仪器读不了，不是没有）。
if command -v node >/dev/null 2>&1; then
  SB=0; node _shbacktick.cjs . > /dev/null 2>&1 || SB=$?
  [ "$SB" = "0" ] || { echo "!! .sh 里有「双引号包反引号」的行 —— 那会被执行，不是排版；见 _shbacktick.cjs 的输出"; exit 1; }
  echo "   shell 雷 0 处 ✓"
else
  echo "   ⚠ 这台没有 node —— shell 雷这一项**没查**（不是查过且干净）"
fi
sh build.sh > /dev/null 2>&1
E=$(grep -cE "error:" _kt.log || true)
[ "$E" != "0" ] && { echo "!! 编译有 $E 个 error，先修它"; exit 1; }
echo "   0 error"

echo "== 2/7 性质台 =="
run harness.SelfTestKt > bench/reg/selftest_raw.txt || true
# 判定本体在 `selftestgate.sh`（**只有一份**：语义变异台 `_semanticmut.cjs --case=2` 调的是同一段）。
# 口径是「不许出现底线名单之外的**新红**」，不是「一条都不许红」—— 后者曾让这一段每次 exit 1，
# 把 3~8 闸（语料/单调性/对表/平色饼/兼容性/肉眼账/没图标账）**一次都没跑过**四天。
. ./selftestgate.sh
selftest_gate bench/reg/selftest_raw.txt || exit 1

echo "== 3/7 语料（边长 $EDGES）=="
# ⚠ 2026-10-21 现量（干净克隆第一次跑）：这一段原先**没有入口门** —— 语料不在时 MaskBench 非零退出，
#   `set -e` 直接把整机掐死在表头后面，新机器上看到的就是一片黑（退码 1，但没话说）。
#   缺什么、怎么重建，必须在缺的那一刻说清楚。
# 入口门在 `corpusgate.sh`（**只有一份**：`_corpusgate_mut.cjs` 变异台调的是同一段，不是抄本）。
. ./corpusgate.sh
corpus_gate "$CORPUS" || exit 1
for e in $(echo $EDGES | tr ',' ' '); do
  run harness.MaskBenchKt --edge=$e "$CORPUS" > bench/reg/bench_$e.txt
  echo "   edge=$e → $(grep -c '^---' bench/reg/bench_$e.txt) 行"
done

echo "== 4/7 单调性审计 =="
# ⚠ 以前这行是 `run harness.MonoKtKt … | grep "^AUDIT" | tee bench/reg/audit.txt` ——
#   **管道后面的退码是 `tee` 的**，MonoKt 自己退 1 整机照样往下绿（第八闸那段注释早就写过这条，
#   今天新加的同色起伏正对照差点死在这儿：单独跑会响、进回归台就哑）。先落盘、再判退码、再取行。
MONO_RC=0
run harness.MonoKtKt "$CORPUS" > bench/reg/mono_full.txt 2>&1 || MONO_RC=$?
[ "$MONO_RC" = "0" ] || {
  echo "!! 单调性审计自己判了不通过（退码 $MONO_RC，最常见是「同色起伏的正对照没响」= 这把尺子钝了）"
  grep -E "^!!|^自检" bench/reg/mono_full.txt | head -4; exit 1; }
grep "^AUDIT" bench/reg/mono_full.txt > bench/reg/audit.txt
grep -q '^AUDIT ' bench/reg/audit.txt || { echo "!! 单调性审计没出 AUDIT 行 —— 不算过"; exit 1; }
grep -E "^自检" bench/reg/mono_full.txt
cat bench/reg/audit.txt

# —— 对表台：真机上设计师亲手画的单色层 = 台子里唯一一把不是我们自己定的尺子。
# ⚠ 这一项**必须有输出**：语料没抓、`*_mono.png` 被删、驱动跑崩，都会让它变成"少一项指标"，
#   而少一项指标在回归台上是**不会红的**（`_regress.cjs` 只比它看得见的键）。所以这里当场拦。
#
# ⚠⚠ **语料必须是带前景层的那一轮抓取**，不能是 `corpus_dev2`。那批是**合成图**：盘在背景层里，
#   而 `MonochromeSynth` 只把 `source.foreground` 交给模型 —— 2026-10-06 量过：同一批 61 对，
#   合成图口径 `iouMed 0.844`，前景层口径 **0.791**，逐格|Δα| 10.4 → 8.1。**印在基线里的那个
#   0.844 是本仓已经收回过的数**（README「对表台跑在合成图上」那条），而回归台一直照着它放行。
#   `YARDDIR` = 原始抓取目录（同时有 `*_fg.png` 与 `*_mono.png`）；
#   `FLATDIR` = `node _mkruntime.cjs <抓取目录> corpus_runtime` 拼出来的"每图标一张运行时输入"。
echo "== 5/7 对表（人画的单色层）+ 平色饼 =="
# ⚠ 2026-10-12：对表语料换成 `yard_model` —— 里面每张已适配图标都同时有
#   `_mono.png`（设计师画的那一层，**可见区几何**）与 `_model.png`（装机那一张，**满幅几何**）。
#   `YardKt --input=model` 把**我们的**输出换算回可见区再比 —— 这是第一次两边同框架。
#   旧口径（`corpus_audit3` + 吃 `*_fg.png`）量出的 iouMed 0.909 是"两边都恰好是可见区"的巧合，
#   而喂给模型的输入是错的；新口径 iouMed **0.940**、med 1.2→0.6。重建：
#     node _mkrec_model.cjs <抓取目录> … 再把 index 里 mono=有 的那些行的三张文件拷进 yard_model/
YARDDIR=${YARDDIR:-yard_model}
FLATDIR=${FLATDIR:-corpus_runtime}
run harness.YardKtKt "$YARDDIR" --input=model --dump=bench/reg/yard_dump.tsv | tee bench/reg/yard.txt | tail -4
grep -q "^YARD n=[1-9]" bench/reg/yard.txt || { echo "!! 对表台没有配对数 —— 语料或驱动坏了，不许当成「这一项不用看」"; exit 1; }
# **跑了不等于跑对了口径**：`YardKt` 自报"输入=前景层 N / 合成图 M"，N=0 就是又回到合成图上打分。
FGN=$(grep -oE "输入=model [0-9]+" bench/reg/yard.txt | grep -oE "[0-9]+$")
YARN=$(grep -oE "^YARD n=[0-9]+" bench/reg/yard.txt | grep -oE "[0-9]+$")
if [ "${FGN:-0}" = "0" ]; then
  echo "!! 对表台这一轮**一张装机输入都没吃**（$YARDDIR 里没有 *_model.png，或 index 没有 modelFile 列）——"
  echo "   那它退回 *_fg.png / 合成图口径：乙类两边差 1.5 倍构图，iouMed 会虚高或虚低都说不清"
  echo '   （GeoKt 量过底案：mono↔fg 0.939、mono↔model 0.175）。'
  echo "   换 YARDDIR= 指向同时有 *_mono.png 与 *_model.png 的那轮抓取，别把这一项当过了。"; exit 1
fi
echo "   对表口径：$FGN/$YARN 张吃的是装机那一张（_model.png，输出换算回可见区后对表）"
# 平色饼：**源里本来有 ≥4 档亮度、交出去却塌到 Neff ≤2.5** —— 只数模型真会处理的那些
# （语料带 index.tsv 时按 mono 列排除已适配的）。这条**有阳性对照**：拿两刀之前的源码跑
# 同一个探针报 23 / 49，现在 6 / 4 —— 量具抓得住被修掉的那个病，不是又一个「看起来有数」的指标。
run harness.ObjKtKt "$FLATDIR" | grep "^FLAT" > bench/reg/flat.txt
run harness.ObjKtKt "$CORPUS" | grep "^FLAT" >> bench/reg/flat.txt
cat bench/reg/flat.txt
[ "$(grep -c "^FLAT n=[1-9]" bench/reg/flat.txt)" = "2" ] || { echo "!! 平色饼台没数出两条 —— 语料或驱动坏了"; exit 1; }

echo "== 6/7 兼容性台 =="
run harness.CompatKtKt 96,192,384,768 | grep "合计" | tee bench/reg/compat.txt

# —— 第七闸：**主人肉眼报的那 15 张**（`named.txt`）。这是台子里唯一一份不是我们自己口径的判据。
# ⚠ **主口径必须是装机那一张（`$CORPUS`=rec_model，自适应里有背景层的那批交的是合成图 + under）**。
#   `corpus_runtime` 是 `_mkruntime.cjs` 按 2026-10-06 的规则拼的（"自适应取 *_fg.png"），
#   而 `MonochromeSynth` 在 2026-10-09 改了：`background !is ColorDrawable` 就交**整张**
#   —— 91 张自适应里这样的有 **57 张**，对这批"前景层口径"根本就不是装机那一张。
#   实测 songpal：fg 口径 `带占=8 底占=0.48 H=3 交付墨 36317 格`，装机口径 `〔under〕实心=100% 底占=0.89
#   H=1 交付墨 6722 格` —— `框内 0.52 vs 0.11` **不是两条通道打架，是喂了两个输入**。
#   它现在只留作**对照**（拿来看"少了那一层会判成什么样"），不再当达标线。
# ⚠ E 尺（`反相`）读的是背景层，而 corpus_runtime 一张背景层都没有 → 它在那份上**恒为 0**，
#   等于"刚装上去的那一刀在肉眼账里永远不可能响"。
# ⚠ 语料不在**不算过**：这一闸没跑就在明面上说没跑，别让"少一项指标"变成"没红"。
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
  GOT=$(node -e 'const fs=require("fs");const m=/合计\s+(\d+)\/(\d+)/.exec(fs.readFileSync("bench/reg/named_model_full.txt","utf8"));console.log(m?(+m[1])+" "+(+m[2]):"读不到")')
  # 判定本体在 `namedfloor.sh`（**只有一份**：`_namedfloor_mut.cjs` 变异台调的是同一段，不是抄本）。
  . ./namedfloor.sh
  namedfloor_verdict "$GOT" || exit 1
  # 分母从台账自己读，别写死：`named.txt` 加一行（易班这次就加了第四族 merge）分母就变了，
  # 写死的 15 会把 "16/16" 印成 "16/15" —— 一个说不清自己分母的数，跟没数一样。
else
  echo "!! 没有 corpus_runtime/（或它没有 index.tsv）—— **这一闸这一轮没跑**，不算通过。"
  echo "   建它：sh audit.sh 抓一轮，再 node _mkruntime.cjs <抓取目录> corpus_runtime"
  exit 1   # 2026-10-16 全量检查：这里原先只 print 不退，"不算通过"四个字整机没人执行
fi

# —— 第八闸：**"没图标"账**。`rec3u` 那批以"有盘的图标"为主，**白剪影坐在空气上这一类一张都没有**，
#   所以 2026-10-08 "广色度全开"那一版离线判"10 好 / 3 反"、真机却是**不少应用整个没图标**
#   （浓度式 `alpha = hi − L` 把最亮的白直接归零）。这 34 张是从那次事故的真机日志里
#   `_undump.cjs` 捞回来的**肇事像素**，是这台机器上第一份能看见该缺陷的语料。
#   底线 **0**：这一族一张都不许多。阳性对照 = 拿 `cand_allTonal` 跑同一份，报 6 张。
echo "== 8/8 没图标账（rec_blank 真机肇事语料）=="
if [ -d rec_blank ] && [ "$(ls rec_blank/*.png 2>/dev/null | wc -l)" -gt 0 ]; then
  run harness.MaskBenchKt --edge=384 rec_blank > bench/reg/blank_bench.txt
  # ⚠ **不能用 `node … | tee`**：管道的退出码是 tee 的，`set -e` 拦不到 —— 一道会被管道吃掉的闸
  #   等于没闸。先落盘、再判、再原样打出来。
  # 阈值 **2%**：这一族在仓里这份上是 0 张，拿 `cand_allTonal` 跑同一份是 6 张（阳性对照）。
  BLANK_RC=0
  node _blank.cjs 2 --in=bench/reg/blank_bench.txt:rec3u \
    --floor="$(cat bench/blank_floor.txt 2>/dev/null || echo 0)" > bench/reg/blank.txt || BLANK_RC=$?
  tail -4 bench/reg/blank.txt
  [ "$BLANK_RC" = "0" ] || { echo "!! 没图标账红了 —— 见上面那张名单，这一刀让图标在屏幕上消失"; exit 1; }
else
  echo "!! 没有 rec_blank/ —— **这一闸这一轮没跑**，不算通过。"
  echo "   它是真机事故捞回来的 34 张（node _undump.cjs <那份日志> rec_blank），别删。"
  exit 1
fi

# —— 第九闸：**空气上墨账**（源那一格是全透明，交付却给了墨）。
# 屏幕上它长成"图标外面一圈灰方框"，形状就是内容外接框 —— 本仓历史上叫过它两次
# （`measure` 的 bridge 那一段、以及"全透明的像素不许上墨"那条钉着的性质）。
# 为什么必须单列一闸：判据台的 `淡=` 与 `浓度[…]` **只数交了墨的格，不看源那格是不是空气**，
# 所以这一族在表里与"标记自己偏淡"完全同形 —— 而"偏淡"在满幅那一支上是**目的**（feimo 82% 偏淡）。
# 底线 **0 张**。阳性对照 = 把甲类"只许空气当底"那一版开关打开（`cand/MonochromeMask.noSolid.kt`），
# 同一份语料当场报 拼多多 11051 / azure 11250 格 —— 这台子抓得住它要防的那件事。
echo "== 9/9 空气上墨账 =="
AIR_RC=0
# 语料跟着换成 `rec_model`（见上面第 3 段那次分母变更）。
# 🔴 **2026-10-21 语义变异：这台子以前是瞎的。** 它用 3 参 `build(src,…)`，那一句顺手把
#   `plateIsLayer` 也钉成 false —— 而【地板】那一刀（`MonochromeMask` 里 `plateLayer` 为真才走）
#   正住在这个分支里。现量：把地板从 `× 源α` **之前**挪到**之后**（= 注释里点名的历史坏版本
#   `cand/MonochromeMask.ghostFloor.kt`，当年 13 张变实心方块），老口径照样 `0 / 98`、退码 0。
#   那条"第九闸 0/219 钉住这件事"的注释**说的是钉住修好之后**，不是抓住过这次改动。
#   现在两条路各数一遍（`装机` = 与 [MaskBench] 同一套输入构造；`从严` = 仍不传 `under`，
#   保留当年"擦得掉的墨也算墨"那句本意，只把 `plateLayer` 还回来）。变异重放：`2 / 64`、退码 1 ✓
run harness.AirInkKtKt rec_model rec_blank > bench/reg/airink.txt || AIR_RC=$?
tail -1 bench/reg/airink.txt
[ "$AIR_RC" = "0" ] || { echo "!! 有图标在空气上上墨 —— 屏幕上就是一圈灰方框，见 bench/reg/airink.txt"; exit 1; }
# ⚠ 这条以前写的是 `grep -q "^0 / "`：两份语料都不在的时候 AirInkKt 打的是 `0 / 0` 并**退 0**，
#   那句正好匹配 → 语料丢了这一闸反而"通过"（2026-10-16 现量：喂两个不存在的目录，退码 0、`0 / 0`）。
#   分母必须是**真数出来的张数**，所以要求 `=0/[1-9]`。
# ⚠ **数"四行都对"而不是"有一行对"**：两条路 × 两份语料 = 4 行机器可读账。
#   少一行 = 有一条路压根没跑（今天缺的正是这一种检查），不能算过。
NALL=$(grep -ac '^空气上墨账\[' bench/reg/airink.txt)
NOK=$(grep -acE '^空气上墨账\[[^]]*\]=0/[1-9]' bench/reg/airink.txt)
[ "$NALL" = "4" ] || { echo "!! 空气上墨账只印了 $NALL 行机器可读账，应有 4 行（2 条路 × 2 份语料）—— 驱动坏了，不算过"; exit 1; }
[ "$NOK" = "4" ] || { echo "!! 空气上墨账 4 行里只有 $NOK 行是「0 / 非零分母」—— 见 bench/reg/airink.txt"; exit 1; }

# —— 第十闸：**换支账**（同一张图在不同边长下会不会从满幅换到参照，或反过来）。
# 已有的 `框内随边长漂移` 看不见它：10 张换支的里面只有 4 张在漂移名单上 —— 换支可以只改
# "浓度怎么排"而不改"多少格有墨"。真机不是单一尺寸（`learnEdge` 学到更大尺寸会重压），
# 所以换支 = **同一个图标在桌面两个位置长得不一样**。
# 只有全扫（EDGES=…）时才有四档可比；单档时**明说没跑**，不算过（第 2 闸那次教训）。
echo "== 10/10 换支账 =="
# 🔴 触发条件以前**只看文件在不在**（`ls bench_192 bench_384 | wc -l`）。那等于：谁哪天手工跑过一次
#   四档，`bench/reg/` 里留下文件，**之后每一次快档都会拿上一轮那批边长的数当这一轮的结论** ——
#   而这一轮明明只跑了 384。2026-10-17 我升级分类尺时手工补跑了 96/192/768 三份，正好踩中。
#   所以必须**先看本轮 EDGES 真有几档**，文件存在只是必要条件。
NE=$(echo "$EDGES" | tr ',' '\n' | grep -c .)
if [ "$NE" -le 1 ]; then
  echo "   本轮 EDGES=$EDGES 只有 $NE 档 → **换支账没跑**（要 EDGES=96,192,384,768 sh regress.sh）"
  mkdir -p bench/reg && echo "10 换支账（本轮只有 $NE 档边长）" >> bench/reg/skipped.txt
elif [ "$(ls bench/reg/bench_192.txt bench/reg/bench_384.txt 2>/dev/null | wc -l)" = "2" ]; then
  node _branchstab.cjs $(ls bench/reg/bench_*.txt | sort -t_ -k2 -n) > bench/reg/branch.txt || {
    tail -6 bench/reg/branch.txt; echo "!! 换支账红了"; exit 1; }
  tail -4 bench/reg/branch.txt
else
  # 走到这里 = 本轮 EDGES 明明有多档，可比的那两份 bench_*.txt 却不在 —— 那是**扫描没跑全**，
  #   不是"设计上跳过"（跳过那条路在上面 NE<=1 已经走掉了）。这里必须硬失败，
  #   否则"我全扫了"和"我扫坏了"在输出上长得一模一样。
  echo "!! EDGES=$EDGES 有 $NE 档，但 bench/reg/bench_192.txt / bench_384.txt 不齐 —— 这一轮没跑全，不算过"
  ls bench/reg/bench_*.txt 2>/dev/null
  exit 1
fi

# —— 第十一闸：**源尺寸账**（`edge` 钉死 384，只换**喂进来的那张位图**多大）。
# 第十闸扫的是模型自己的画布边长，**这一轴它完全看不见**：真机 `edge` 永远是 384，变的是桌面
# 递过来的前景层（2026-10-12 从 `live2.txt` 逐行数出来的：同一张 translate 有 `@432x432` 45 次与
# `@425x425` 3 次，两张算出的掩码不同 —— `分档` 第 2 级 134 vs 155、`盘刀` 59430 vs 59351）。
# 上一刀"离线好、装机坏"就是这么判错的（详见 README 2026-10-12 那条）。
# 底线钉在**当前实测**：喂成 320 时换支 4 张（wps / farsee / icbc 满幅→参照、易班 反向）。
# ⚠ 这一闸**不是"零才叫过"**，是"不许新增"—— 它钉的是我们自己还欠着的那笔债，别把它读成"已经稳了"。
# ⚠ 只扫**比原生小**的那两档：语料存的就是 384，放大再缩回等于没动（假绿，见 SizeVarKt 文件头）。
UPDATE=""
for a in "$@"; do [ "$a" = "--update" ] && UPDATE=1; done
echo "== 11/11 源尺寸账 =="
if [ -n "$UPDATE" ]; then
  # `--update` 仍然在这里钉：钉基线是**人的认账动作**，不该由量具自己做。
  run harness.SizeVarKtKt "$CORPUS" --via=256,320 > bench/reg/sizevar.txt
  grep -q '^SV ' bench/reg/sizevar.txt || { echo "!! 源尺寸账没数出「SV」行 —— 驱动或语料坏了"; exit 1; }
  NEW=$(grep '^SV ' bench/reg/sizevar.txt | tr '\n' ';')
  echo "$NEW" > bench/reg/sizevar.pin; echo "   --update：已把这一轮钉成新基线 $NEW"
else
  # **判定在 `SizeVarKtKt --pin=` 里，这一层只读它的退码**（与 `_press.cjs --pin=` 同一条规矩）。
  # 为什么必须搬：这根 pin 比的是**整行字符串**，几乎任何数值改动都会先撞它 —— 于是整台重跑时
  # 变异永远停在第 11 段，**第十一闸自己有没有牙从来没被单独量过**（2026-10-21 语义变异第 4 条撞上的）。
  PR_RC=0
  run harness.SizeVarKtKt "$CORPUS" --via=256,320 --pin=bench/reg/sizevar.pin > bench/reg/sizevar.txt 2>&1 || PR_RC=$?
  [ "$PR_RC" = "0" ] || { tail -8 bench/reg/sizevar.txt; echo "!! 第十一闸红了（退码 $PR_RC；2 = 没有基线或基线格式变了）"; exit 1; }
  grep -q '^SV ' bench/reg/sizevar.txt || { echo "!! 源尺寸账没数出「SV」行 —— 驱动或语料坏了"; exit 1; }
  grep '^SVLINE\|SV-PIN OK' bench/reg/sizevar.txt
fi


# —— 第十二闸：**中调被压账**（"交少了"，与 F 擦空那族"没交"分开）。
# 来源：主人 2026-10-13 报的知到「底部波浪带 + Treenity 首字母看不见」—— `dep₁` 说该交 112/129，
# 实际只交 37/54；而同一张图里笔画的黑块与橙红块一比一兑现。现有闸门对它**全都不响**
# （`淡` 0.03、`平色饼` 不中、`分档` 4/6 没塌），所以单立一闸。
# 钉的是**当前债**（6 张 / 11 块）：之后只许降不许涨 —— 涨了就是这一刀把它弄坏了。
echo "== 12/12 中调被压账 =="
run harness.PartsKtKt "$CORPUS" > bench/reg/parts.txt
# **判定在 `_press.cjs --pin=` 里，这一层只读它的退码**（2026-10-21 把十行 shell 字符串比对搬过去）。
# 为什么搬：抄在 shell 里的判据**没法单独量** —— 语义变异台只能整台重跑，而整台重跑会把"谁先红"
# 记到第一个接住改动的闸头上（今天 `EMAX 100→200` 那版就是红在第 11 闸，不是第 12 闸）。
# 与 `_flat.cjs --floor=` 同一条规矩：**判据在哪把尺上，判定就在哪把尺的退出码里。**
PR_RC=0
node _press.cjs bench/reg/parts.txt --pin=bench/reg/press.pin > bench/reg/press.txt 2>&1 || PR_RC=$?
[ "$PR_RC" = "0" ] || { tail -8 bench/reg/press.txt; echo "!! 第十二闸红了（退码 $PR_RC；2 = 没有基线或基线格式变了）"; exit 1; }
grep -q '^PRESS icons=' bench/reg/press.txt || { echo "!! 中调被压账没出数 —— 驱动或语料坏了"; exit 1; }
head -3 bench/reg/press.txt
grep '^SPLIT' bench/reg/press.txt
grep '^PRESS-PIN' bench/reg/press.txt

# —— 第十三闸：**对表外形账**（尾部张数，不是中位数）。
# 第 5 闸报的是 `iouMed 0.940` —— 中位数会糊掉尾部：44 张像到 0.94+ 与 14 张完全不像
# （IoU 0.002~0.455）在同一个中位数上长得一模一样。主人报过的「还剩一块大色块」「反色」「半边没有」
# 全部落在尾部那 14 张里，所以要数**张数**。
# ⚠ **这把尺的边界**：语料是**已适配**的那 62 对（自带单色层，按硬规则根本不进我们的模型）。
#   它量的是"若由我们画，外形离人画的差多远" = **通用性验收标准**，不是当前屏幕上的缺陷清单。
#   它值钱的地方恰恰是它**不是我们自己定的口径**。
# ⚠ 判据为什么带绝对差：只看比值有小分母陷阱（人画 0.8% 时我们随手 2% 就"超标"，屏幕上看不见）。
#   `node _shape.cjs --scan` 把切点摊开过：比值 ≥2 且差 ≥10pp → 5 张，且 0/5/10pp 三档都是 5 张
#   （这一条不飘）；而 IoU<0.5 那 14 张里面**只有 5 张是涂多**，另外 9 张是"墨量对、位置错"
#   （credentialmanager ×1.13 而 IoU 0.260）—— 别把这一闸读成"14 张都涂过头了"。
echo "== 13/13 对表外形账 =="
SHAPE_RC=0
node _shape.cjs --ioupin=bench/reg/iou.pin > bench/reg/shape.txt || SHAPE_RC=$?
# 侧档（按支路分那一段）在名单后面，`head -18` 会正好把它切掉 —— 侧档才是"该动哪一刀"的依据。
head -34 bench/reg/shape.txt
[ "$SHAPE_RC" = "0" ] || { echo "!! 对表外形涨了，或第 5 闸没交出逐张读数（见上面那句）—— 都不算过"; exit 1; }

# —— 第十四闸：**无对岸半灰账**（主人 2026-10-13 那句「就是部分图标有点掉色」）。
# 定义：我们交了 α128~239 的一层灰，而**设计师那一格本来是实心黑或本来是空**。
# 为什么不用现成的两把尺：`顶占比`（32/62 张 <80%）**不能当债** —— 层次本来就靠不满量程换，
#   `顶平%` 甚至把贴顶太多当坏事；"两边都上墨的格的平均浓度"量出来 61 张里只有 1 张 <200 ——
#   **浓度根本没给低**。只有这一把对得上他那句话。
# ⚠ 红绿灯为什么是**总量**不是张数：这条尾巴从 81% 连续降到 5%，**没有宽边距的切点**
#   （≥5% 是 18 张，而第 18 名 5.24% 与第 19 名 4.49% 只差 0.75pp）—— 骑在人群上的阈值
#   任何一刀都会让它翻牌，翻牌又不代表变坏。总量无阈值、任何净变差都会动它。
# ⚠ 总量的已知盲区：**一张变好 + 一张变坏可以抵成零** → 所以名单逐张印出来，认账前必须看名单。
# ⚠ 这把尺的量程先测过：设计师自己那层的中间调占画布 中位 0.90% / p90 2.43%、完全二值只有 1/62 张
#   → "有灰"本身不算错，判据只数"人画那一格是黑或空"的那些格（守住主人「颜色可以多点」那条）。
# 🔴 **2026-10-20 拆成两个相反的方向**（地板多两个键 `fade` / `fog`）：`greyBadPct` 一个数把两件事混在一起 ——
#   **掉色**（人画实心、我们交灰 ⇒ 该往上抬）140.4pp / 22 张 >1%，**铺雾**（人画留空、我们交灰 ⇒ 该往下压）
#   179.4pp / 17 张 >1%。两类的修法方向相反，只守总量的话**一刀"压雾"可以把总量做得很好看、同时把 22 对的
#   掉色压得更狠，而这一闸照样绿** —— 这条不是推测，`_grey_mut.cjs` 第三条变异就是这么打的（总量一位不动、
#   只有 `fade` 响）。两列还必须**刚好拼成总量**，拼不上 = 有一列被改坏，直接退 2 不判绿。
echo "== 14/14 无对岸半灰账 =="
GREY_RC=0
node _grey.cjs > bench/reg/grey.txt || GREY_RC=$?
head -12 bench/reg/grey.txt
[ "$GREY_RC" = "0" ] || { echo "!! 无对岸半灰涨了（或第 5 闸没交出带 greyBadPct 列的读数）—— 见上面"; exit 1; }

# —— 第十五闸：**掩码缓存的键与文件格式**（`MonoCache` / `MonoKey`）。
# 为什么这一条必须单列：缓存错了**不会让前面十四闸任何一闸红** —— 命中那条路根本不进模型，
# 判据没跑、缩略图来自缓存里那张。而它的两种错法都直接上屏：键撞了交出**别的图标**的掩码，
# 文件坏了交出一张破的。所以把"命中"拆成两条能判的：键对不对（确定性 / 单像素敏感 /
# 六个输入每一位都进键 / 语料互不撞）、格式对不对（五种边长往返 + 七种坏输入必须全拒）。
# ⚠ 这一闸自己也被抓过一次撒谎：`坏-行步长不符` 原本写成 `decode(enc, e, e)`，在 96/192/384
#   三档上 `e == (e+3)/4*4`，**根本没构造出坏的输入**，却报"通过"。负例必须是真坏的。
echo "== 15/15 掩码缓存账 =="
run harness.MonoKeyKtKt "$CORPUS" > bench/reg/monokey.txt || {
  tail -8 bench/reg/monokey.txt; echo "!! 掩码缓存的键或格式红了"; exit 1; }
grep -q '^KEYKt OK' bench/reg/monokey.txt || {
  echo "!! 这一闸没出结论（没跑成不等于通过）"; tail -8 bench/reg/monokey.txt; exit 1; }
tail -4 bench/reg/monokey.txt

# —— 第十六闸：**极性入口账**（`MonochromeSynth.plateSubject`，即 164eb47 那一刀）。
# 为什么必须单列（2026-10-16 现量，不是推断）：这一刀改的是染色侧的极性开关，而
#   ① `build.sh` 的编译清单里**没有 `MonochromeSynth.kt`**（台子只编 `MonochromeMask` 那一侧），
#   ② 更要紧的是 **`rec_model` 上这条支一张都不开火**：实测 `plate && !spiky` = **0 张**，
#      而 `plate && spiky` 那 5 张（azure / smartglass / uu远程 / termux / 拼多多）`keepPct`
#      全 ≤31，够不着 50 那条线 → 波及也是 **0**。
#   所以**光补 ① 不够**：旧分母上这一闸是空跑，「15 闸全绿」对这一刀一条都不算 ——
#   这正是 Kodi 那一刀在仓里躺了 19 小时没人看见的原因（`COVER_BOX_FLOOR` 给 plate 档开了
#   第二条入口，当夜没有一台子会响）。本闸因此自带语料 `plate_model`，**不许退回 `rec_model`**。
# 钉的是**集合**不是张数：开火集 = 极性被「这块大色块是要保住的图案」接管的那几张；
#   波及集 = 靠 `!spiky` 那道守卫才没被接管的那几张。下一把"给 plate 档再加一条入口"的刀
#   一定动这两个集合，所以它一响就是真出事；两张集合都不许靠"数差不多"糊过去。
echo "== 16/16 极性入口账 =="
PLATECORPUS=${PLATECORPUS:-plate_model}
# 快捷方式那一类（2026-10-22 加）：这一刀真机翻的两张就在这里面，而它在**别的三份语料里一张都没有**。
# 少了这一档，第 16/19 闸的"近线带"就只有启动图标那一类 —— 那正是"离线说 0 变化、真机翻 2 张"的成因。
SHOTCORPUS=${SHOTCORPUS:-shortcut_model}
[ -d "$PLATECORPUS" ] || {
  echo "!! 语料 $PLATECORPUS/ 不在 —— 这一闸**不许**退回 rec_model 空跑（那正是它原本瞎掉的样子）"; exit 1; }
[ -d "$SHOTCORPUS" ] || {
  echo "!! 语料 $SHOTCORPUS/ 不在 —— 第 16/19 闸的分母会少了**快捷方式**那一类（重建见 .gitignore 那条注释）"; exit 1; }
# bitmap 形式那一类（2026-10-22 加）：ShortcutService 把应用交来的位图**自己落盘**在
#   `/data/system_ce/0/shortcut_service/bitmaps/<包>/<时间戳>.png`，`_shotbit.cjs` 按 dump 里的
#   `bitmapPath=` 接住 ⇒ 45 张。少了这一档，"快捷方式"分母就只有资源 id 那一半，
#   而 dump 里 173 条快捷方式有 45 条（26%）是这一类 —— 现量，不是推测。
SHOTBITCORPUS=${SHOTBITCORPUS:-shortcut_bit}
[ -d "$SHOTBITCORPUS" ] || {
  echo "!! 语料 $SHOTBITCORPUS/ 不在 —— 第 16/19 闸会少了 bitmap 形式那 45 张"
  echo "   重建：adb shell dumpsys shortcut > dump.txt；adb exec-out 'toybox tar -c -C /data/system_ce/0/shortcut_service bitmaps' > bitmaps.tar（解开）"
  echo "        node _shotbit.cjs dump.txt --src=<解开的目录> --out=$SHOTBITCORPUS"; exit 1; }
PSRC=0
run harness.PlateSubKtKt "$PLATECORPUS" "$SHOTCORPUS" "$SHOTBITCORPUS" --min=50 > bench/reg/platesub.txt || PSRC=$?
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

# —— 第十七闸：**跨 canvas 判据不变账**（`IconCropWindow` 那套"窗口存比例 + 一律降到扫描格子"）。
# 为什么必须单列（2026-10-17 现量，不是推断）：真机第一次有了双档样本 —— 19 张图标在 192 与 384
#   两联都真算过，判据 19/19 没翻、拉交付掩码逐格比 19/19 仍是同一张画（`_a8sweep.cjs`）。
#   可这**一条子都没守着**：裁圆的门槛只要有一处从"比例"滑回"绝对格数"，就会变成
#   "同一个图标在桌面和抽屉长得不一样"，而前十六闸一条不红（同一族已经记过一次：绝对格数门槛
#   = 潜伏的尺寸依赖；那次是**掉档**，这次是**两联不一致**）。
# 钉的是 XS **整行逐字**（与第十六闸同一口径）：类漂移由台子自己退非 0，
#   数层（`剩差最大` / `倍数差最大` 连同是谁）由这一行钉住 —— 只判类的话，
#   "去掉降采样"那个变异**当场溜走**（现量：类漂移仍 0，只有 XS 行的数会动）。
echo "== 17/17 跨 canvas 判据不变账 =="
XSRC=0
run harness.CrossEdgeKtKt "$CORPUS" > bench/reg/crossedge.txt || XSRC=$?
grep -q '^XS ' bench/reg/crossedge.txt || {
  echo "!! 跨 canvas 账没出数 —— 驱动或语料坏了（它自己那几行在下面）"; tail -6 bench/reg/crossedge.txt; exit 1; }
NEWXS=$(grep '^XS ' bench/reg/crossedge.txt)
if [ -n "$UPDATE" ]; then
  echo "$NEWXS" > bench/reg/crossedge.pin; echo "   --update：已把这一轮钉成新基线 $NEWXS"
else
  OLDXS=$(cat bench/reg/crossedge.pin 2>/dev/null)
  [ -z "$OLDXS" ] && { echo "!! 没有 bench/reg/crossedge.pin —— 没基线不算过（先 sh regress.sh --update 钉一次）"; exit 1; }
  [ "$XSRC" != "0" ] && { echo "!! 跨 canvas 账自己判了不通过（退码 $XSRC：类漂移或开窗=0），见 bench/reg/crossedge.txt"; tail -8 bench/reg/crossedge.txt; exit 1; }
  [ "$NEWXS" = "$OLDXS" ] || {
    echo "!! 跨 canvas 的读数动了："; echo "   基线 $OLDXS"; echo "   这轮 $NEWXS"
    echo '   逐张对照见 bench/reg/crossedge.txt（~ 那几行是数层，!! 是类漂移）；'
    echo "   同一张在两个尺寸上长得不一样 = 屏幕上桌面与抽屉不一致，认账也要看清是谁进出再 --update"; exit 1; }
  echo "   $NEWXS —— 与基线逐字相同"
fi

# —— 第十八闸：**平块坡数账**（2026-10-20 深夜，把"没有对岸也能问的掉色"从临时台子变成常设闸）。
# 定义：面积 ≥0.5% 画布、源同一颜色 bin、我们平均上了墨(α≥128)、而我们交的 α 的 p90−p10 > 40。
# 为什么要有它：第十四闸那本账要**对岸**（设计师那一层），可对岸那 62 对**根本不进模型**；
#   真上屏的 64 张里掉色有多少，当时是现写一台临时台子量的（第二十二段：5 张 / 21 块 / 31,001 格）。
#   常设化之后，任何动浓度的刀一提交就会被量到，不必重新手写台子。
# ⚠ 这台量的是**射程不是收益**：抬平已被量过是"112px 上看不见的差别"，所以这里红了是去查**哪一刀**，
#   不是去抬平。守的是"别把平色块画成一道坡"，不是"把坡都抬成 255"。
# 红绿灯用两个数（`blocks` 与 `cells`）：只守块数，一块 8,000 格裂成 8 块 1,000 格会看着像在变好；
#   只守格数则相反。变异：`node _flatmut.cjs`（七条全过，含"零行/缺文件/坏地板一律退 2"）。
echo "== 18/18 平块坡数账 =="
FLAT_RC=0
run harness.MaskBenchKt --edge=384 --flatin "$CORPUS" > bench/reg/flatin.txt || FLAT_RC=$?
[ "$FLAT_RC" = "0" ] || { echo "!! 平块台没跑完（退码 $FLAT_RC）—— 缺读数不等于干净"; exit 1; }
node _flat.cjs > bench/reg/flatink.txt || FLAT_RC=$?
head -7 bench/reg/flatink.txt
[ "$FLAT_RC" = "0" ] || { echo "!! 平块坡数涨了（或地板/输入坏了）—— 见上面"; exit 1; }

# —— 第十九闸：**两本账一本账**（2026-10-21，极性的取数从"判据那一本"改回"彩色那一本"）。
# 为什么必须单列（现量，不是推断）：`PLATE_SUBJECT_MIN=50` 这条线是在**彩色那行 `裁了只剩`** 上标定的
#   （wenku8 真机 55%），却一直由判据那一本去比（量喂进模型那张、还多卡一道 `GEO_ZOOM_CAP`）。
#   两本账装机现量差 **3 个点**：wenku8 55→52、`com.android.contacts` 彩色 **50** / 判据 **49** ——
#   只差 1 个点，同一个模块就会对同一张图给两个答案（彩色把那块缩进圆里保住、染色把它当底摘掉）。
#   而**前十八闸没有一条看着这根缝**：第十六闸钉的是判据那一本的开火集/波及集。
# 钉的是**集合与散布**：两本口径各自的开火集（`开火集=` / `老开火集=`）+ 三条支各自的
#   「差≥3 / 差≥5 / 极性翻 / 档翻 / 最大差」+ `fgNear`。
# ⚠ `fgNear` 是这台子**自己看得见多少**（只给前景层那一支里、老口径够得着 40% 的张数，现量 0）：
#   没有这一位，"极性翻 0 张"分不清是**不会翻**还是**语料里没有候选**（本仓那族：空跑当通过）。
# ⚠ 这一闸现在的绿 = **波及面为空**，不是"这条缝不重要"。它买的是那条 1 个点的结构缝；
#   而非自适应那一支 55↔52 的**采样**漂移离线复现不了（语料只有 384 那一张），
#   装机由 `_twobook.cjs` 逐包核（`判剩=` 必须等于彩色那行的 `裁了只剩`，不等就非零退出）。
echo "== 19/19 两本账一本账 =="
# 这一闸**跑两档**（384 + `--edge=96`），两份 pin：`twoacct.pin` / `twoacct96.pin`。
# 驱动是 `run harness.TwoAcctKtKt`（`_harness/TwoAcctKt.kt`），分母四份：`rec_model plate_model shortcut_model twobook_ctrl`。
# 判定本体**只有一份**：`twobookgate.sh`（回归台与变异台 `_semanticmut.cjs` 调同一段代码，
#   照 `namedfloor.sh` / `selftestgate.sh` / `corpusgate.sh` 那三段的先例）。
# 为什么必须抽出去：这一闸除了"与 pin 逐字相同"还有**两条不看基线**的当场断言
#   （96 档 `格子分岔` 不许是 0、96 与 384 的极性归属一字不差）。内联在这里时变异台只能再抄一遍
#   "跑台子 + 比 pin"，那证的是**抄的那半** —— 新加的两条它一条也没证过（2026-10-22 现修，见用例 21）。
. ./twobookgate.sh; twobook_gate || exit 1

# —— 第廿闸：**自画层接管账**（2026-10-22，主人问"能不能全覆盖，尤其飞鸟那种"查出来的洞）。
# 第 8 闸只管**该不该接管**；这一闸管**接管之后交出去的是什么**。
# 为什么必须单列（现量，不是推断）：`_mkrec_model.cjs` 过去按 `mono=有 ⇒ 直通、不上模型` 把
#   **自带单色层那一整类**从所有语料里剔掉（本机 129 张里有 67 张），可第 8 闸的 veto 恰恰从这批里
#   挑走 2 张改由我们画（etar、飞鸟）⇒ **我们接管的那两张的输出，之前不在任何一把尺的分母里**。
#   飞鸟这次量到的数：`字均净 68` vs 这一类中位 `244` —— 笔画只有同类三分之一粗，
#   这就是主人看见的"字标被啃"。有数之后才谈该不该为它开刀。
# ⚠ 这些中位数**不是收益表**：这一类没有对岸（设计师那一层就是那块饼），所以钉它是为了
#   "别把接管的那两张画得更坏"，不是为了"越小越好"。
echo "== 20/20 自画层接管账 =="
# 驱动：`run harness.AdaptKtKt`（`_harness/AdaptKt.kt`），分母与第 8 闸同一份 `adapted_model` 67 张。
# 判定本体**只有一份**：`adaptgate.sh`（回归台与变异台调同一段；为什么必须这样，见那文件头）
. ./adaptgate.sh; adapt_gate || exit 1

# == 8/8 「自画单色层是一块饼」那条闸的射程账（2026-10-19 深夜加，2026-10-22 换分母）==
# 它管的不是像素，是**别改谁的图**：这一类里只许开火"整块都是墨"的那几张（本机现量 etar + 飞鸟），
# 其余 65 张交回桌面自己上色。三条控制里有一条专量"窗"：拿整张画布当窗的写法会在那一条上红。
echo "== 8/8 自画层不透传账 =="
# 驱动：`run harness.VetoKtKt`（`_harness/VetoKt.kt`），分母 = `adapted_model` 67 张（在库里，可重建）。
# 判定本体**只有一份**：`adaptgate.sh`（与第 20 闸同一段代码、同一个理由）
. ./adaptgate.sh; veto_gate || exit 1

# —— 第廿一闸：**分母去向账**（2026-10-22）。这一闸不量像素，量的是"这句话我还查得动吗"：
# 「离线量到 0 变化」曾把真机翻的两张快捷方式漏掉 —— 根因是**没人知道某类图标在哪道闸的分母里**。
# 以前那句话靠我读 `regress.sh` 记住，而分母是 `$CORPUS/$SHOTBITCORPUS/T_BIT` 这些变量、闸体还抽进了
# `*gate.sh` —— grep 数一遍必漏（今天 `_corpusmap.cjs` 第一版就没认 `="${X:-dir}"` 里那个引号，
# 把 `shortcut_bit` 报成"没进第 19 闸"：**假警报**）。现在这台子自己展开变量列表，
# 两头都查：新冒出一份没人吃的在册语料 ⇒ 红；名单里那份不在了（理由作废）⇒ 也红。
# 控制台 `_corpusmap_mut.cjs` 四条 + 一条元对照（把那句退码摘掉，第 1 条必须假通过）。
echo "== 21/21 分母去向账 =="
node _corpusmap.cjs || exit 1

# ⚠ **收尾自证：平色饼那本账到最后必须还是两条。**（2026-10-20：我加第 18 闸时把它的读数写成
#   `bench/reg/flat.txt` —— 正是第 3 段在 `>>` 追加的那个文件 —— 于是在最后一步把别人的账本清空，
#   制造出 `平色饼分母 0（基线 124）` 这种**自己造的假退步**。第 3 段那道 `= 2` 的检查当时已经过了，
#   因为它跑在覆盖之前。）放在**所有闸之后**、判定表之前，专抓"后面某一段覆盖了前面某一段的文件"。
TAILFLAT=$(grep -c '^FLAT n=[1-9]' bench/reg/flat.txt 2>/dev/null)
[ "$TAILFLAT" = "2" ] || { echo "!! 平色饼账本到最后不是两条（现在是 ${TAILFLAT:-0} 条）——" \
  "查哪一段把 bench/reg/flat.txt 覆盖了；新加闸的输出文件名不许复用"; exit 1; }

# ⚠ **判定表必须排在所有闸之后**（2026-10-17 加第 17 闸时排错过一次：新闸落在
#   `_regress.cjs` 后面，于是那张表先印"全绿"、第 17 闸才跑 —— 正是本仓反复拦的"没跑却报绿"的形状。）
node _regress.cjs "$@" --corpus="$CORPUS"
