// 横向「文字列紧贴右侧元素」普查。
// 用法：node _harness/_spacing.cjs [head]
//   head = 用 git show HEAD:<path> 的内容跑同一套判据（负控制：修好之前必须红）。
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = 'app/src/main/kotlin/com/iamcanincan/opticon/ui';
const MODE = process.argv[2] === 'head' ? 'head' : 'work';

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.kt')) files.push(p);
  }
})(ROOT);

const indentOf = (s) => s.match(/^\s*/)[0].length;
const isNoise = (s) => !s.trim() || /^\s*(\/\/|\*|\/\*)/.test(s);
// 控制关键字不算调用开头（AboutScreen 那行 `if (url == null) … else Modifier.weight(1f)`）。
const CALL = /^\s*(?!(?:if|else|when|for|while|return|catch)\b)([A-Za-z_][\w.]*)\s*\(/;

function read(f) {
  if (MODE === 'work') return fs.readFileSync(f, 'utf8');
  return execSync(`git show HEAD:${f.split(path.sep).join('/')}`, { encoding: 'utf8', maxBuffer: 1 << 26 });
}

// 调用结束行：先等圆括号归零，再看紧跟的是不是 {，是就等花括号归零。
function callEnd(L, start) {
  let p = 0, b = 0, phase = 'paren', seen = false;
  for (let i = start; i < L.length; i++) {
    const t = L[i];
    for (let c = 0; c < t.length; c++) {
      const ch = t[c];
      if (phase === 'paren') {
        if (ch === '(') { p++; seen = true; }
        else if (ch === ')') {
          p--;
          if (seen && p === 0) {
            const rest = t.slice(c + 1);
            if (/^\s*\{/.test(rest)) { phase = 'brace'; b = 0; }
            else if (rest.trim() === '' || /^\s*\/\//.test(rest)) {
              let k = i + 1;
              while (k < L.length && isNoise(L[k])) k++;
              if (k < L.length && /^\s*\{\s*$/.test(L[k])) { phase = 'brace'; b = 1; i = k; break; }
              return i;
            } else return i;
          }
        }
      } else {
        if (ch === '{') b++;
        else if (ch === '}') { b--; if (b === 0) return i; }
      }
    }
  }
  return L.length - 1;
}

function parentSpaced(L, owner) {
  const ind = indentOf(L[owner]);
  for (let k = owner - 1; k >= 0; k--) {
    const m = L[k].match(/^(\s*)(Row|FlowRow)\s*\(/);
    if (m && indentOf(L[k]) < ind) {
      let hdr = '';
      for (let q = k; q < Math.min(k + 8, owner); q++) hdr += L[q] + '\n';
      return /horizontalArrangement\s*=\s*Arrangement\.spacedBy/.test(hdr);
    }
  }
  return false;
}

// 一个兄弟自己带不带缝（Spacer 兄弟不在这里判：它是替**下一个**元素留的缝）。
// ⚠ if 块内的 Spacer 只替**块内**那点内容负责，不能算给块后面的兄弟
//   （MenuRow 的 `if (trailing != null) { Spacer … }` 后面还跟着 `if (showChevron) { Icon }`）。
function selfGap(L, i, ind) {
  const t = L[i];
  if (/^\s*(if|else)\b/.test(t)) {
    for (let k = i + 1; k < Math.min(i + 8, L.length); k++) {
      if (isNoise(L[k])) continue;
      if (indentOf(L[k]) <= ind) break;
      if (/^\s*Spacer\s*\(/.test(L[k])) return '有缝(if 内)';
      break;
    }
    return false;
  }
  // 按钮自带 contentPadding（左右各 12dp），两个按钮相邻不算贴字。
  if (/^\s*(TextButton|OutlinedButton|Button|FilledTonalButton|ElevatedButton|IconButton|FilledIconButton)\s*\(/.test(t))
    return '按钮自带内边距';
  return false;
}

let total = 0, bad = 0;
const out = [], census = [];
for (const f of files) {
  const L = read(f).split(/\r?\n/);
  let hits = 0;
  for (let i = 0; i < L.length; i++) {
    if (isNoise(L[i]) || !/weight\(\s*1f\s*\)/.test(L[i])) continue;
    hits++; total++;
    let owner = -1;
    const sameLine = L[i].match(CALL);
    if (sameLine && L[i].indexOf('weight(') > L[i].indexOf('(')) owner = i;
    else {
      for (let k = i - 1; k >= 0; k--) {
        if (isNoise(L[k])) continue;
        if (CALL.test(L[k]) && indentOf(L[k]) < indentOf(L[i])) { owner = k; break; }
      }
    }
    if (owner < 0) { out.push(`??无归属  ${f}:${i + 1}`); bad++; continue; }
    const ind = indentOf(L[owner]);
    let name = (L[owner].match(CALL) || [, '?'])[1];
    if (name === 'Spacer') name = 'Spacer[弹性缝]';
    const end = callEnd(L, owner);
    const spaced = parentSpaced(L, owner);
    const short = f.replace(ROOT + path.sep, '');
    let j = end + 1, offender = null;
    let pending = name.startsWith('Spacer') ? '弹性缝' : false;
    const trail = [];
    while (j < L.length) {
      if (isNoise(L[j])) { j++; continue; }
      if (indentOf(L[j]) < ind) break;
      if (indentOf(L[j]) > ind) { trail.push('??更深@' + (j + 1)); break; }
      const t = L[j];
      const blockEnd = callEnd(L, j);
      if (/^\s*Spacer\s*\(/.test(t)) {
        pending = /weight\(/.test(t) ? '弹性缝' : '有缝';
        trail.push('Spacer');
      } else {
        const g = selfGap(L, j, ind);
        if (pending) { trail.push(pending); }
        else if (g) trail.push(g);
        else if (spaced) trail.push('有缝(父 spacedBy)');
        else { trail.push('缺缝'); if (!offender) offender = `@${j + 1} ${t.trim().slice(0, 40)}`; }
        // 前一个元素自己的 end/horizontal padding 也是缝（统计页百分比就是这么留的）。
        const block = L.slice(j, blockEnd + 1).join('\n');
        pending = /padding\([^)]*\b(?:end|horizontal)\s*=\s*[1-9]/.test(block) ? '有缝(前元素 padding)' : false;
      }
      j = blockEnd + 1;
    }
    let verdict;
    if (offender) { verdict = '缺缝'; bad++; }
    else verdict = trail.length ? trail.join(' / ') : '收尾';
    out.push(`${verdict}  ${short}:${owner + 1}  ${name}${offender ? '  ← ' + offender : ''}`);
  }
  if (hits) census.push(`${f.replace(ROOT + path.sep, '')} ${hits}`);
}
out.sort();
for (const l of out) console.log(l);
console.log(`--- [${MODE}] 每文件处数：${census.join(' | ')}`);
console.log(`--- [${MODE}] weight(1f) 共 ${total} 处，缺缝 ${bad} 处 ---`);