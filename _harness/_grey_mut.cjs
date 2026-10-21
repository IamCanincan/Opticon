// 第十四闸「拆方向」这一改的**变异自证**（照 `_notifyguardmut.cjs` 的规矩：每条变异都要自证落地，
// 且必须恰好命中一行、文件真的变了 —— 否则"6 条通过"里可能有一条根本没在测东西）。
//
// 跑法：`node _grey_mut.cjs`
const fs = require("fs");
const { spawnSync } = require("child_process");
const IN = "bench/reg/yard_dump.tsv";
const FLOOR = fs.readFileSync("bench/reg/grey.pin", "utf8").trim();
const raw = fs.readFileSync(IN, "utf8").split(/\r?\n/);
const head = raw[0].split("\t");
const iG = head.indexOf("greyBadPct"), iF = head.indexOf("theirTopOurGreyPct"), iE = head.indexOf("theirEmptyOurGreyPct");
const TMP = "bench/reg/_grey_mut.tsv";

/** 在第 `row` 行的某一列上加 `d`，返回改完的整表文本 + 自证信息。 */
function mutate(rows, d) {
  const out = raw.slice();
  let hits = 0;
  out.slice(1).forEach((l, i) => {
    if (!l.trim() || !rows.includes(i)) return;
    const c = l.split("\t");
    for (const [j, add] of d) c[j] = (+c[j] + add).toFixed(2);
    out[i + 1] = c.join("\t"); hits++;
  });
  return { text: out.join("\n"), hits, changed: out.join("\n") !== raw.join("\n") };
}

function run(text, floor) {
  fs.writeFileSync(TMP, text);
  const r = spawnSync(process.execPath, ["_grey.cjs", `--in=${TMP}`, `--floor=${floor || FLOOR}`],
    { encoding: "utf8", cwd: __dirname });
  return { rc: r.status, out: (r.stdout + r.stderr).split(/\r?\n/).filter(l => /!!|PRESS-GREY|没有/.test(l)).join(" | ") };
}

const CASES = [
  // 名字 / 改哪几行 / 加什么 / 期望退码 / 期望响在哪个键
  ["原表（负对照：什么都没改）", [], [], 0, null],
  ["正对照·只涨铺雾", [1], [[iG, 20], [iE, 20]], 1, "fog"],
  ["正对照·只涨掉色", [2], [[iG, 20], [iF, 20]], 1, "fade"],
  ["**这一改存在的理由**：压 20pp 雾、涨 20pp 色（总量一位不动）",
    [3, 4], [[iE, -20], [iF, 20], [iG, 0]], 1, "fade"],
  ["装置自洽：只动 greyBad、两列没跟上", [5], [[iG, 20]], 2, "拼不回"],
  ["旧表头（没有那两列）", null, null, 2, "没有 theirTop"],
];
let bad = 0;
for (const [nm, rows, d, wantRC, wantMsg] of CASES) {
  if (rows === null) {
    const keep = head.map((_, j) => j).filter(j => j !== iF && j !== iE);
    const t = [head.filter((_, j) => j !== iF && j !== iE).join("\t")]
      .concat(raw.slice(1).filter(l => l.trim()).map(l => {
        const c = l.split("\t"); return keep.map(j => c[j]).join("\t");
      })).join("\n");
    const r = run(t);
    const ok = r.rc === wantRC && r.out.includes(wantMsg);
    console.log(`${ok ? "✓" : "✗"} ${nm}\n    退码 ${r.rc}（要 ${wantRC}）  ${r.out.slice(0, 120)}`);
    if (!ok) bad++; continue;
  }
  const m = mutate(rows, d);
  if (rows.length && (m.hits !== rows.length || !m.changed)) {
    console.log(`✗ ${nm} —— **变异没落地**（命中 ${m.hits}/${rows.length}，changed=${m.changed}）：这条测的是空气`);
    bad++; continue;
  }
  const r = run(m.text);
  const ok = r.rc === wantRC && (!wantMsg || r.out.includes(wantMsg));
  console.log(`${ok ? "✓" : "✗"} ${nm}\n    命中 ${m.hits} 行 · 退码 ${r.rc}（要 ${wantRC}）  ${r.out.slice(0, 150)}`);
  if (!ok) bad++;
}
fs.unlinkSync(TMP);
console.log(bad ? `\n!! ${bad} 条不成立` : "\n全部成立：两把方向尺各自能独立响、总量抵零那一条被抓住、装置不自洽会拒绝判绿");
process.exit(bad ? 1 : 0);
