# `_harness/` —— 判据台（这套东西是"证据"，不是临时脚本）

2026-10-21 之前，`.gitignore` 里那条 `/_*/` 把整个目录挡在库外 ⇒ 台账（`docs/devlog/`）引用的
每一条证物**只活在这一台机器上**：20 道闸、所有 `*_mut` 变异台、`baseline.json`、各档底线，
换机即不可重跑（这台机器有过换机那天整套接线断掉的案底）。现在**能重跑判定的那部分入库**，
其余仍留本机（见下）。

## 进库的（485 个文件，约 3.2 MB）

| 类 | 是什么 | 少了会怎样 |
|---|---|---|
| `*.kt` | 判据台自己的驱动（`SelfTestKt` / `MaskBenchKt` / `YardKt` / `AirInkKt` / `SizeVarKt` …），它们**编译的是生产源码那一份**（`../app/src/.../MonochromeMask.kt` 等），不是副本 | 编译段 `1/7` 直接红 |
| `*.cjs` | 判定与变异台（`_regress.cjs`、`_gateaudit.cjs`、`_semanticmut.cjs`、`_grey_mut.cjs`、`_namedmut.cjs`、`_namedfloor_mut.cjs`、`_croprange.cjs` …） | 对应那道闸没有证物 |
| `*.sh` | `build.sh` / `regress.sh` / `gateenv.sh` / `namedfloor.sh` / `jdk.sh` / `audit.sh`（**classpath 与判定各只有一份**） | 跑不起来 |
| `named.txt` | 主人肉眼报过的缺陷账 —— 这台机器上唯一一份**不是模型自己口径**的判据（第七闸的分母） | 第七闸没有分母 |
| `bench/baseline.json`、`bench/*_floor.txt`、`bench/reg/*.pin` | 红绿灯的底线 | **底线文件缺失 = 这一闸不算过**（脚本会自己这么说） |
| `bench/gate_evidence.txt`、`bench/semanticmut.txt` | 两道证据账（"真的响过" / "改过生产源码那一行"） | `_gateaudit.cjs` 会把有牙的闸列成没证物 |
| `rec_blank/` | 2026-10-08 真机事故那 34 张**肇事像素**（`node _undump.cjs <日志> rec_blank` 从当时的 logcat 里捞回来的） | 第八闸（没图标账）没有语料，且**这 34 张无法复现** |

## 留在本机的（故意不进库）

- 抓回来的 logcat / dump（`_sui1.txt` 单个 221 MB 那种）、`.apk`、`_kodi.apk` 一类校验副本；
- `out/`、`stubs_out/`（编译产物，`sh build.sh` 重生成）；
- `cand/`（一轮一变体的实验输出）；
- 语料目录 `rec_model/` `corpus_runtime/` `yard_model/` `plate_model/` —— 能重建，但重建需要**连着那台手机**：
  `sh audit.sh` 抓一轮 → `node _mkruntime.cjs <抓取目录> corpus_runtime` → `node _mkrec_model.cjs`。
  ⚠ `yard_model`（设计师手画的 `_mono` 对岸）与 `plate_model` 的重建路径**我没在这份说明里核实过**；
  缺它们时对应的闸（第五闸对表、第十六闸极性入口）会**明说"这一轮没跑"并退非零**，不会冒充通过。
- `MonochromeMask` 的两份历史快照 `_mask_HEAD.kt` / `_mask_f28.kt`（本机 diff 用）—— 副本一进库，
  grep 一个生产常数就可能命中它、读出一个不属于任何版本的数。

## 常用四条

```sh
cd _harness && sh build.sh && sh regress.sh          # 全套回归：任何一项比底线差就非零退出
EDGES=96,192,384,768 sh regress.sh                  # 全扫（第 10 闸"换支账"只有这个模式才跑）
node _gateaudit.cjs                                  # 自审：哪道闸从来没有能重跑的证物
node _semanticmut.cjs --case=8                       # 改生产那一行，验第 N 道闸还连着真刀
```

⚠ 三条硬规矩（都是摔出来的，详见 `docs/DEVELOPMENT.md` 与 `docs/devlog/`）：
全闸跑进行中**不要** `sh build.sh`、**不要**编辑正在被执行的脚本；
还原过生产源码就**先重编再量**；新闸的输出文件**不许复用**别人那份活账本。
