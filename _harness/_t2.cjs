const fs=require('fs');
const blk=fs.readFileSync('bench/cdev.txt','utf8').split(/\n(?=--- )/).find(x=>x.startsWith('--- com.tencent.tmgp.sgame'));
const k='盒布';
const pat=k+'=(\S+)';
console.log('pattern codepoints:',[...pat].map(c=>c.codePointAt(0).toString(16)).join(' '));
console.log('test:',new RegExp(pat).test(blk));
console.log('simple:',new RegExp(k).test(blk), new RegExp('\d+').test(blk));
console.log('esc form:',(blk.match(new RegExp(k+'=(\d+)'))||[])[1]);
