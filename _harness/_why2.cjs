const fs=require("fs");
const want=["yiban","rimet","meituan","idlefish","steamlink","bilibili","farsee","icbc","taobao.taobao"];
const f=process.argv[2]||"_tmp_b384.txt";
const L=fs.readFileSync(f,"utf8").split(/\r?\n/);
let cur=null; const seen=new Set();
for(const l of L){
  const h=/^--- (\S+)/.exec(l); if(h){cur=h[1];continue}
  if(!cur||!l.includes("色驱=")||seen.has(cur))continue;
  const k=want.find(w=>cur.includes(w)); if(!k)continue;
  seen.add(cur);
  const on=/ 调=1/.test(l);
  let s;
  if(on){const m=/[^编]跨=(\d+)/.exec(l); s="开火，"+(m?"跨="+m[1]:"跨=读不到");}
  else {const m=/调跨=(\d+)/.exec(l); s=m?"没开火，"+m[1]:"没开火，且 tonalClass 返回 null（连跨度都没有）";}
  console.log(cur.replace(/^icon\d+_/,"").slice(0,44).padEnd(46)+s);
}
