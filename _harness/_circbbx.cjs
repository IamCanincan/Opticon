// 量真图标的「实际有像素外框」：裁圆要按哪块内切，先得知道这块有多大、有多少张不是满幅。
// 为什么不能靠眼睛：满幅/留白差几个百分点，肉眼看两张缩略图分不出来，而这里要的正是那个数。
// 用法：node "D:/Github Repositories/Opticon/_harness/_circbbx.cjs" <目录或png...> [--thr=8] [--map=名字子串]
const fs = require("fs"), zlib = require("zlib"), path = require("path");

function decodePng(buf) {
  let pos = 8, idat = [], ihdr = null, plte = null, trns = null;
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString("latin1", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === "IHDR") ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === "PLTE") plte = data;
    else if (kind === "tRNS") trns = data;
    else if (kind === "IDAT") idat.push(data);
    else if (kind === "IEND") break;
    pos += len + 12;
  }
  if (!ihdr) throw new Error("no IHDR");
  if (ihdr.interlace) throw new Error("interlaced");
  if (ihdr.depth !== 8) throw new Error("depth=" + ihdr.depth);
  const { w, h, color } = ihdr;
  const chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!chans) throw new Error("color=" + color);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * chans;
  const out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  // → RGBA
  const px = new Uint8Array(w * h * 4);
  for (let i = 0, n = w * h; i < n; i++) {
    const s = i * chans, d = i * 4;
    if (color === 6) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = out[s + 3]; }
    else if (color === 2) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = 255; }
    else if (color === 4) { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = out[s + 1]; }
    else if (color === 0) { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = 255; }
    else { const k = out[s] * 3; px[d] = plte[k]; px[d + 1] = plte[k + 1]; px[d + 2] = plte[k + 2]; px[d + 3] = trns && out[s] < trns.length ? trns[out[s]] : 255; }
  }
  return { w, h, px };
}

function bbox(px, w, h, thr) {
  let x0 = w, y0 = h, x1 = -1, y1 = -1, n = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (px[(y * w + x) * 4 + 3] >= thr) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  return x1 < 0 ? null : { x0, y0, x1, y1, bw: x1 - x0 + 1, bh: y1 - y0 + 1, n };
}

const args = process.argv.slice(2);
const thr = +(args.find(a => a.startsWith("--thr="))?.slice(6) ?? 8);
const mapName = args.find(a => a.startsWith("--map="))?.slice(6);
const targets = args.filter(a => !a.startsWith("--"));
const files = [];
for (const t of targets) {
  const st = fs.statSync(t);
  if (st.isDirectory()) {
    for (const e of fs.readdirSync(t)) if (/\.png$/i.test(e)) files.push(path.join(t, e));
  } else {
    files.push(t);
  }
}
const rows = [];
for (const f of files) {
  try {
    const { w, h, px } = decodePng(fs.readFileSync(f));
    const all = bbox(px, w, h, 1), solid = bbox(px, w, h, thr);
    const b = solid ?? all;
    if (!b) { rows.push({ f, w, h, note: "全透明" }); continue; }
    rows.push({
      f: path.basename(f), w, h,
      sq: w === h ? "" : "非方",
      bw: b.bw, bh: b.bh,
      mL: (b.x0 / w * 100).toFixed(1), mR: ((w - 1 - b.x1) / w * 100).toFixed(1),
      mT: (b.y0 / h * 100).toFixed(1), mB: ((h - 1 - b.y1) / h * 100).toFixed(1),
      fill: (b.bw * b.bh / (w * h) * 100).toFixed(1),          // 外框占画布（=放大倍数²）
      aspect: (b.bw / b.bh).toFixed(2),
      cov: (b.n / (b.bw * b.bh) * 100).toFixed(0),             // 外框内真的有像素的比例
      a0: all && solid ? (all.bw - solid.bw) + "/" + (all.bh - solid.bh) : "-", // 弱像素（alpha<thr）多出来的边
    });
  } catch (e) { rows.push({ f: path.basename(f), note: "跳过:" + e.message }); }
}
rows.sort((a, b) => (a.fill ?? 999) - (b.fill ?? 999));
console.log("外框 = alpha>=" + thr + " 的最小包围盒；fill=外框/画布（<100 就是要放大）；aspect=外框宽/高（1.00 才是方）");
console.log("文件".padEnd(52) + "尺寸    外框        左R 右R 上R 下R   fill  aspect 外框内像素  弱边");
for (const r of rows) {
  if (r.note) { console.log(r.f.padEnd(52) + r.note); continue; }
  console.log(r.f.slice(0, 50).padEnd(52) + `${r.w}x${r.h}`.padEnd(8) + `${r.bw}x${r.bh}`.padEnd(12) +
    `${r.mL} ${r.mR} ${r.mT} ${r.mB}`.padEnd(19) + r.fill.padStart(5) + "  " + r.aspect.padStart(5) + "  " + r.cov.padStart(4) + "%  " + r.a0);
}
const full = rows.filter(r => !r.note && +r.fill >= 99).length, padded = rows.filter(r => !r.note && +r.fill < 99).length;
const nonsq = rows.filter(r => !r.note && (r.w !== r.h || Math.abs(+r.aspect - 1) > 0.02)).length;
console.log(`\n共 ${rows.length} 张：满幅(>=99%) ${full}，留白 ${padded}，尺寸或外框非方 ${nonsq}`);
if (mapName) for (const r of rows) {
  if (r.note || !r.f.includes(mapName)) continue;
  const { w, h, px } = decodePng(fs.readFileSync(files.find(f => path.basename(f) === r.f)));
  console.log("\n== " + r.f + " (" + w + "x" + h + ")");
  const N = 32;
  for (let ry = 0; ry < N; ry++) {
    let line = "";
    for (let rx = 0; rx < N; rx++) {
      let s = 0, c = 0;
      for (let y = Math.floor(ry * h / N); y < Math.floor((ry + 1) * h / N); y++)
        for (let x = Math.floor(rx * w / N); x < Math.floor((rx + 1) * w / N); x++) { s += px[(y * w + x) * 4 + 3]; c++; }
      const a = s / c; line += a >= 200 ? "#" : a >= 64 ? "+" : a >= 8 ? "." : " ";
    }
    console.log(line);
  }
}
