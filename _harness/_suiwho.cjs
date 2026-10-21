// 从 SystemUI 的 dexdump 里回答一件事：**哪个类用哪条 API 取应用图标**。
// 这是"通知行该裁、磁贴不该裁"能不能按通道分开的第一手证据 —— 不靠记忆里的 AOSP 长什么样，
// 读的是这台机器上真装着的那一份。
//
// dexdump 每个方法体前面有一行 `地址: |[地址] 全限定类.方法:(签名)`，
// 方法体里的 `invoke-*` 行带被调方。所以扫一遍就能建"调用方 → 被调 API"的账。
// 用法：node _suiwho.cjs <dump.txt> [dump2.txt ...]
const fs = require('fs');
const readline = require('readline');
const WANT = [
  'getApplicationIcon', 'getActivityIcon', 'getDefaultActivityIcon', 'loadItemIcon',
  'ApplicationInfo;.loadIcon', 'PackageManager;.getApplicationIcon',
  'getDrawable', 'NotificationUtils;.loadIcon', 'DrawableUtils;.createBitmapDrawable',
];
const HEAD = /^[0-9a-f]{4,8}:\s+\|\[[0-9a-f]+\] ([\w.$]+)\.([\w$<>-]+):\(/;
const tally = new Map();

async function scan(file) {
  const rl = readline.createInterface({ input: fs.createReadStream(file), crlfDelay: Infinity });
  let cur = '';
  for await (const l of rl) {
    const h = HEAD.exec(l);
    if (h) { cur = h[1] + '.' + h[2]; continue }
    if (!/invoke-/.test(l)) continue;
    const w = WANT.find(k => l.includes(k));
    if (!w) continue;
    // 被调方类名（`Lcom/android/...;.method:`）
    const callee = ((l.match(/L([\w/$]+);\.[\w$]+:/) || [])[1] || '?').replace(/\//g, '.');
    if (!cur.startsWith('com.android.systemui')) continue;      // 只关心 SystemUI 自己的调用方
    const key = w + '  ←  ' + cur;
    tally.set(key, (tally.get(key) || 0) + 1);
  }
}

(async () => {
  for (const f of process.argv.slice(2)) await scan(f);
  const rows = [...tally].sort((a, b) => b[1] - a[1]);
  console.log(`== SystemUI 里"谁用哪条 API 取图标"（dexdump 现读，${process.argv.slice(2).join(' ')}）==`);
  const byApi = new Map();
  for (const [k, n] of rows) {
    const api = k.split('  ←  ')[0];
    if (!byApi.has(api)) byApi.set(api, []);
    byApi.get(api).push([k.split('  ←  ')[1], n]);
  }
  for (const [api, list] of byApi) {
    console.log(`\n── ${api}（${list.length} 个调用方）`);
    for (const [c, n] of list.slice(0, 14)) console.log(`   ×${n}  ${c}`);
    if (list.length > 14) console.log(`   …另 ${list.length - 14} 个`);
  }
})();
