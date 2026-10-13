// Generates a test .litematic (gzip NBT) with a known block distribution
const zlib = require('zlib');
function w(){const parts=[];return{
 b(v){const x=Buffer.alloc(1);x.writeInt8(v);parts.push(x)},
 s(v){const x=Buffer.alloc(2);x.writeInt16BE(v);parts.push(x)},
 i(v){const x=Buffer.alloc(4);x.writeInt32BE(v);parts.push(x)},
 l(v){const x=Buffer.alloc(8);x.writeBigInt64BE(v);parts.push(x)},
 str(v){const d=Buffer.from(v);this.s(d.length);parts.push(d)},
 raw(x){parts.push(x)}, out(){return Buffer.concat(parts)}}}
// Tag-Writer
function tag(W,type,name,val){W.b(type);W.str(name);payload(W,type,val)}
function payload(W,type,val){
 if(type===3)W.i(val);else if(type===8)W.str(val);
 else if(type===10){for(const [t,n,v] of val)tag(W,t,n,v);W.b(0)}
 else if(type===9){W.b(val.t);W.i(val.items.length);for(const it of val.items)payload(W,val.t,it)}
 else if(type===12){W.i(val.length);for(const x of val)W.l(x)}
}
const pal=[['minecraft:air',{}],['minecraft:stone_bricks',{}],['minecraft:oak_slab',{type:'double'}],['minecraft:oak_door',{half:'upper'}],['minecraft:oak_door',{half:'lower'}],['minecraft:redstone_wire',{}],['minecraft:wall_torch',{}],['minecraft:sea_pickle',{pickles:'3'}],['minecraft:potted_cactus',{}],['minecraft:piston',{}]];
const sx=7,sy=5,sz=3, vol=sx*sy*sz; // 105
const bits=Math.max(2,32-Math.clz32(pal.length-1)); // 4
const idx=[]; for(let i=0;i<vol;i++) idx.push(i%pal.length);
const expect={}; idx.forEach(v=>expect[v]=(expect[v]||0)+1);
// seamless packing
const nl=Math.ceil(vol*bits/64); const longs=new Array(nl).fill(0n);
idx.forEach((v,i)=>{const o=BigInt(i*bits);const li=Number(o/64n),s=o%64n;longs[li]|=(BigInt(v)<<s)&((1n<<64n)-1n);if(s+BigInt(bits)>64n)longs[li+1]|=BigInt(v)>>(64n-s)});
const signed=longs.map(x=>BigInt.asIntN(64,x));
const W=w(); W.b(10); W.str('');
const palList={t:10,items:pal.map(([n,p])=>{const e=[[8,'Name',n]];const pe=Object.entries(p);if(pe.length)e.push([10,'Properties',pe.map(([k,v])=>[8,k,v])]);return e})};
payload(W,10,[
 [3,'MinecraftDataVersion',3955],[3,'Version',6],
 [10,'Metadata',[[8,'Name','Test build'
],[8,'Author','Tester'],[10,'EnclosingSize',[[3,'x',sx],[3,'y',sy],[3,'z',sz]]],[3,'RegionCount',1]]],
 [10,'Regions',[[10,'main',[[10,'Size',[[3,'x',-sx],[3,'y',sy],[3,'z',sz]]],[9,'BlockStatePalette',palList],[12,'BlockStates',signed]]]]]
]);
require('fs').writeFileSync(process.argv[2]||'test.litematic', zlib.gzipSync(W.out()));
console.log('expected per palette index', expect);
