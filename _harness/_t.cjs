const fs=require('fs');
const blk=fs.readFileSync('bench/cdev.txt','utf8').split(/\n(?=--- )/).find(x=>x.startsWith('--- com.tencent.tmgp.sgame'));
console.log('len',blk.length);
console.log('literal:',(blk.match(/盒布=(\S+)/)||[])[1]);
const k='盒布';
console.log('k codepoints',[...k].map(c=>c.codePointAt(0).toString(16)));
console.log('ctor:',(blk.match(new RegExp(k+'=(\S+)'))||[])[1]);
