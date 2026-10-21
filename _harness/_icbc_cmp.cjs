const fs = require("fs");
for (const e of ["108", "162", "192", "216", "384"]) {
  const dir = `_ea/${e}`;
  const f = fs.readdirSync(dir).find((n) => n.endsWith(".alpha"));
  const a = fs.readFileSync(`${dir}/${f}`);
  const b = fs.readFileSync(`_eb/${e}/${f}`);
  let up = 0, down = 0, maxd = 0;
  for (let i = 0; i < a.length; i++) {
    const d = a[i] - b[i];
    if (d !== 0) { if (d > 0) up++; else down++; if (Math.abs(d) > maxd) maxd = Math.abs(d); }
  }
  console.log(
    `edge=${e.padStart(3)}  共${a.length}格  抬${up}  抹${down}  最大单格Δα=${maxd}` +
      (up + down === 0 ? "   ← flatVote 一张没动" : "")
  );
}
