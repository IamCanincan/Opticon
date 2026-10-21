// 造一份**新格式**的抓取日志（每行带 `p=`，一个图标的 src 与 under 共用同一个 dump#N），
// 用来在装机之前证明：新通道写的行新解析器读得回来、`under` 真能喂进模型。
// 用法: node _mk_synlog.cjs <src.png> <under.png> <输出.log> [--no-under]
const fs = require('fs');
const [srcP, underP, outP, ...rest] = process.argv.slice(2);
const CHUNK = 3000, PID = 4321, PROC = PID.toString(16);
const HEAD = `10-12 18:40:00.000 D/Opticon (${String(PID).padStart(5)}): `;
const NOTE = 'com.google.android.apps.translate_ce1fbfbd3fe7_432x432 | 票=0抹/0抬/0块 底占=0.94 H=1 ' +
  '盘刀=0 盘候=-1 盘层=1 反相=66427/3413';
const L = [];
// 与 MonoDump.emit 逐字同形：BEGIN 在 what 的位置、分片行带 what、**同一个图标的三份共用一个 dump#N**。
for (const [file, what] of [[srcP, 'src'], ...(rest.includes('--no-under') ? [] : [[underP, 'under']])]) {
  const b = fs.readFileSync(file), s = b.toString('base64'), parts = Math.ceil(s.length / CHUNK);
  const dn = 1;
  L.push(`${HEAD}Mono: dump#${dn} p=${PROC} BEGIN id=7 what=${what} parts=${parts} ${NOTE} 层底=0 层=1`);
  for (let i = 0; i < parts; i++)
    L.push(`${HEAD}Mono: dump#${dn} p=${PROC} ${what} P${String(i).padStart(3, '0')}/${String(parts - 1).padStart(3, '0')} ${s.slice(i * CHUNK, (i + 1) * CHUNK)}`);
  L.push(`${HEAD}Mono: dump#${dn} p=${PROC} END id=7 what=${what} bytes=${b.length}`);
}
fs.writeFileSync(outP, L.join('\r\n') + '\r\n');
console.log('写出 ' + outP + '：' + L.length + ' 行 / ' + (rest.includes('--no-under') ? '只有 src' : 'src + under') + '，p=' + PROC);
