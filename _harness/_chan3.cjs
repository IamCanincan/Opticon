const fs = require('fs');
const path = require('path');
const H = __dirname;
const lines = fs.readFileSync(path.join(H, 'corpus_runtime', 'index.tsv'), 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const rows = lines.slice(1).map(l => { const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]); return o; });

const audit = new Set(fs.readdirSync(path.join(H, 'corpus_audit3')));

// 模型这一轮实际吃哪一张（与 MonochromeSynth 的 flatten 规则同口径）
function channel(r) {
  if (r.cls === 'AdaptiveIconDrawable') {
    if (r.bg === 'ColorDrawable') return '甲·只吃前景层（盘在别的层，结构性已知）';
    return '乙·层合成图（盘=背景层，结构性已知）';
  }
  return '丙·整幅位图（盘没有任何图外证据）';
}

const onModel = rows.filter(r => r.mono !== '有');
console.log('总行', rows.length, '进模型', onModel.length, '直通', rows.length - onModel.length);

const g = new Map();
for (const r of onModel) { const k = channel(r); g.set(k, (g.get(k) || 0) + 1); }
console.log('\n--- 进模型的 60 张按通道分 ---');
[...g.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, v]) => console.log(String(v).padStart(3), k));

console.log('\n--- 乙类（层合成图）名单：盘是结构已知的 ---');
onModel.filter(r => channel(r).startsWith('乙')).forEach(r =>
  console.log('  ', r.pkg.padEnd(42), 'fg=' + r.fg.padEnd(16), 'bg=' + String(r.bg).padEnd(16), 'fgAlpha=' + String(r.fgAlpha).padEnd(8), 'bgAlpha=' + r.bgAlpha));

console.log('\n--- 甲类缺 _fg 文件的（有几张模型输入拿不到）---');
const noFg = onModel.filter(r => channel(r).startsWith('甲')).filter(r => !audit.has(r.fgFile));
console.log('   甲类总数=', onModel.filter(r => channel(r).startsWith('甲')).length, '缺 _fg=', noFg.length);
noFg.slice(0, 10).forEach(r => console.log('   缺:', r.fgFile));

console.log('\n--- 丙类（整幅位图）名单 ---');
onModel.filter(r => channel(r).startsWith('丙')).forEach(r =>
  console.log('  ', r.pkg.padEnd(46), 'kb=' + String(r.kb).padStart(4), 'fgAlpha=' + r.fgAlpha));
