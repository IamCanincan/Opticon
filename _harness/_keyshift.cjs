// **同一组件，我们这一次交的掩码与上一次交的是不是同一份内容** —— 用 `键=`（内容哈希）比。
//
// 方向要说清楚，这台子和 `_monocmp.cjs` 问的不是同一个问题：
//  - `_monocmp.cjs` 比的是**桌面库里那张位图**（`mono_icon`）。那里混着桌面自己的合成与重绘，
//    快捷方式每次重绑都可能不一样 ⇒ 它说"不同"，不能直接算到我们头上。
//  - 这台比的是**我们交出去的那张掩码**：`键=` 是 `MonoKey.encode(rowBytes, edge, body)` 的纯内容哈希，
//    **同一组件在两扇日志窗口里的键不同 ⇒ 我们的产物字节真的变了**（这才是"这版动了画面"的直接证据）。
//  ⚠ 反过来不成立：**同名同键不能证明桌面画出来一样**，而**同键不同名**才是"键不能当组件主键"那条。
//
// 跑法：node _keyshift.cjs <旧 delivery.tsv> <新 delivery.tsv> [--show=N]
const fs = require('fs');
const [A, B] = process.argv.slice(2).filter(a => !a.startsWith('--'));
if (!A || !B) { console.error('用法: node _keyshift.cjs <旧清单> <新清单>'); process.exit(2); }
const load = f => {
  const m = new Map();
  for (const l of fs.readFileSync(f, 'utf8').replace(/\r/g, '').split('\n').slice(1)) {
    const c = l.split('\t');
    if (c.length < 3 || !c[2]) continue;
    const k = c[0] + '@' + (c[3] || '');
    const s = m.get(k) || new Set(); s.add(c[2]); m.set(k, s);
  }
  return m;
};
const a = load(A), b = load(B);
let both = 0, sameKey = 0, shifted = [];
for (const [k, ka] of a) {
  const kb = b.get(k); if (!kb) continue;
  both++;
  const inter = [...ka].filter(x => kb.has(x));
  if (inter.length) sameKey++;
  else shifted.push(`  ${k}  旧=${[...ka].join(',')}  新=${[...kb].join(',')}`);
}
const show = +(process.argv.find(x => x.startsWith('--show=')) || '--show=20').substring(7);
console.log(`旧清单 ${a.size} 个「组件@边长」、新清单 ${b.size} 个，两边都有读数 ${both} 个`);
console.log(`其中**同一把键**（我们的产物逐字节没变）：${sameKey}`);
console.log(`**键变了**（我们的产物真的换了一张）：${shifted.length}`);
if (!both) console.log('  ⇒ 可比 0：两扇窗口没有共同组件，这个数什么也没说');
shifted.slice(0, show).forEach(l => console.log(l));
console.log('\n=== 仪器自证：拿同一份清单跟自己比必须 100% 同键 ===');
const s1 = load(A), s2 = load(A);
let t = 0, g = 0;
for (const [k, ka] of s1) { const kb = s2.get(k); if (!kb) continue; t++; if ([...ka].some(x => kb.has(x))) g++; }
console.log(`  可比 ${t}，同键 ${g} ⇒ ${t && g === t ? '比较器认得出"没变" ✓' : '比较器坏了，上面那个数不能用'}`);
